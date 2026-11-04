# Vela 本地源事件 journal 与音频归档

日期：2026-10-23。任务 05 第二阶段的实现与静态回顾记录。已实现私有存储配置下的本地捕获/记录 journal 和 WAV 归档；副本卡片动作、模式变化、S1 私有存储接入及双端传输等仍未完成，整个任务保持未勾选。没有运行测试、构建、模拟器或实机。

后续更新：副本卡片、专注状态与模式请求已接入类型化源事件，见 [30 投影 journal](30-vela-projection-journal.md)。下文保留本阶段范围；全部用户操作、S1 配置和完整网络复制仍未完成。

## 接入位置与范围

新增 `app/buffer_app/buffer_source_events.c/.h`，Make/CMake 已加入源文件，现有 host fixture 的编译源文件列表也加入模块；未运行 fixture、未添加测试用例。

- `buffer_agent_atomic_json` 对 `records/<合法 capture_id>.json` 走 journal 提交，覆盖文本捕获、转写、分类结果、助手结果及本机记录卡片修改。`.tools.json` 检查点、`.processed` 基线、配置和回执文件不误判为捕获记录。
- `buffer_agent_read_json` 对这些记录在同一个 journal 锁内完成待提交恢复与实际读取，避免读到记录已变化但 journal head 未提交的中间状态。
- `buffer_audio_record_cancelable` 在开始接受录音前核对已启用的 journal 存储；录音完成后先同步 WAV 和元数据，再建立源捕获事实与归档。后续归档/事件失败时保留已完成的队列文件并显示等待存储恢复，不把已录音频删除，也不取消关联追问。
- `buffer_store_read_audio` 在 ASR/委派读取前补齐捕获事实及归档。`buffer_store_ack` 在删除仍存在的 WAV 前强制确认归档及源记录成功；失败保留队列原件。文本或重复 ACK 没有 WAV 时仍可清理残留队列文件。

`buffer_agent_atomic_bytes/read_bytes` 是私有存储的受限底层原语；原有 Agent 凭据检查仍要求 LittleFS 和文件属性。普通记录写入必须经过 journal，底层旁路只用于内部提交/恢复。JSON 解析前增加 32 层深度检查，记录线程在启用 journal 的配置下从平台默认栈改用 32 KiB，避免把新恢复、校验工作放在 S1 的 4 KiB 默认线程栈上。

## 数据布局

路径相对于 `CONFIG_BUFFER_VELA_DATA_DIR/event-journal/`：

| 文件 | 内容与作用 |
| --- | --- |
| source.json | 持久 source ID、最后提交序号/逻辑时钟、最后事件 ID/指纹及旧记录迁移状态 |
| local/000…001.json | [28 中的 v1 envelope](28-source-event-protocol.md)，精确 UTF-8 文本，不重建或覆盖已存在的不同内容 |
| local/000…001.sha256 | 对应原文的 SHA-256；读取时重新核对，不能把损坏内容当成新版本发送 |
| captures/<id>.json | 同一 capture 的最后事件、记录序号及音频描述索引 |
| pending.json | 待提交 envelope 原文、目标 record_json 和 capture 索引，固定本地目标类型，不接受任意路径 |
| blobs/<sha256>.wav | 按内容哈希寻址的私有 WAV 原件，独立于可清理的上传队列 |

source ID 使用受支持的安全随机源生成并持久化；不以墙钟作为唯一身份。事件 ID 由 source ID 与单调序号形成，逻辑时钟递增，父引用包含前一个本地事件及同一 capture 的前一个事件。当前只生成本机源事件，接收其他源后的时钟观察仍须随下一阶段接收链路接入。

事件 payload 中的 `record_json` 保存原记录的精确 JSON 文本。旧文件迁移也保留这段原文，避免解析、重新序列化时丢失历史表示；不会把 JSON 文本当作指令执行。音频捕获 payload 保存 blob 哈希、字节数、内容类型、原时间/模式/时长，并保存原始 `capture_metadata` 文本，包含原 target 等队列字段。

旧记录在初始化时按实际存在的文件恢复为 `LegacyCaptureSnapshot`，标为 `legacy_local`；缺少新固件 source_events 标记的旧队列音频标为 `LegacyAudioSnapshot`。它们是明确的兼容快照，不伪造旧设备曾存在但未保存的事件链。后续新事件包括 CaptureCreated、CaptureTranscribed、ClassificationProposed、AssistantResultRecorded、CardRecordChanged 和 CaptureRecordUpdated。内部同步字段的实际变更也可记录为快照变化，不冒充用户动作。

## 提交和恢复顺序

同一运行时由 journal mutex 串行化本机提交及读取：

1. 完成已有 pending 恢复，核对持久 source head、原文指纹及序号；不在存有遗留 journal/索引/blob 的目录里重新生成 source 身份。
2. 为本次变化分配稳定序号、时钟和 ID，先持久保存 pending，其中包含完整源事件和目标记录。
3. 保存该序号的不可变原文与 SHA-256；已存在文件只接受完全相同的内容。
4. 写目标记录与 capture 索引，再持久更新 source head，最后清理 pending。
5. 断电或 I/O 失败保留 pending。下次初始化、读取或写入先恢复同一个事件，避免重新分配 ID。若 head 已提交该事件，只清理 pending，不用旧意图回退当前记录。

head 未提交时，发送入口不能把新文件视为已提交事件；孤立的下一序号文件或校验失败会报错，不能覆盖它后继续声称历史完整。原文上限为 1 MiB，记录仍保持原有 64 KiB 上限，pending 有独立 2 MiB 上限；溢出、非法 UTF-8/JSON、坏索引或迁移失败均不通过截断、跳过旧记录来伪造成功。

这不是多进程共享目录的协调协议。当前 Vela 内建应用使用单一运行时和共享 journal 锁；不能把两个独立实例指向同一目录后当作多设备同步。

## WAV 的独立保留

归档采用流式复制到私有临时文件，核对文件类型、大小、完整 SHA-256 与权限，fsync 后 rename 并同步目录。已有哈希文件也必须通过内容核对；不会因同名文件存在就假定 blob 有效。原始音频上限仍取现有 MAX_CAPTURE_BYTES。

归档文件在队列 ACK 后保留。本阶段尚无 blob 网络请求、远端提交回执或归档淘汰策略。后续 [42 Vela blob 服务](42-vela-source-blob-service.md)增加读取与独立回执服务，但手机接收器仍未接入，因此不能说 blob 已交付给手机。不会自动删除归档释放空间；空间不足明确失败并保留已有事实与原件。旧版升级前已经删除的音频无法恢复，不作相反声明。

## 配置与当前设备边界

新增 `BUFFER_VELA_SOURCE_JOURNAL`，依赖 LittleFS 及 FS_LITTLEFS_ATTR_UPDATE。已存在的私有模拟磁盘配置默认启用；其他板需先接入私有存储再显式启用。普通 FAT/HOSTFS 配置保持旧录音路径，source API 返回不支持，不把旧卡片同步当成源事件复制。没有自动格式化、移动或部署任何存储。

当前 S1 defconfig 没有声明 LittleFS/属性支持，不能标记为已启用此能力。仍需完成板级私有存储接入并核对挂载失败、旧数据迁移和错误入口；这些是未完成代码/配置工作，不以“尚未实机测试”代替。

## 关联修复与剩余步骤

交叉回顾修复了背光恢复日志对私有 Agent 存储的误依赖。它只含 LCD 路径、原值和目标值，现改为排他创建最终文件、完整写入并 fsync 后才调光；读取限制为普通文件与 1 KiB，已有日志不覆盖。SDK FAT 的 fat_sync 路径会同步数据并更新目录项，因此不需要为了这份非敏感日志启用事件/凭据存储。日志保存失败仍不调光；原私有凭据规则保持原样。此次仍未进行硬件验收。

下一阶段必须继续：

- 补齐副本卡片动作、模式/会话等相关事实的源事件入口，避免仅覆盖 records 就称所有操作已记录。
- 接入 S1 私有存储配置；明确 capability 不支持与实际可用状态。
- 接入双方协商、事件分片、提交回执、权限保留序号、缺口/父引用补传、接收端观察时钟、受限投影和 blob 独立交付。
- 回顾 source journal 与旧 processed/CRDT/回执的关系；历史导入不重复执行设备助手、日历、健康或通知动作。

当前完成的是本地存储实现与静态调用链回顾，整个任务 05 仍未完成。

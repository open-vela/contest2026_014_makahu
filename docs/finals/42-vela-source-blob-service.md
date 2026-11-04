# Vela 源音频读取与独立交付回执服务

日期：2026-10-24。任务 05 的 blob 服务子阶段。仅修改源码和文档并做静态回顾，没有测试、构建、编译/语法检查、配置生成或设备操作。当前只接入 Vela 服务端；Android 源 blob 接收与对应发送器尚未接入，不能宣称音频独立交付已经完成。

## 既有路径与本阶段用途

后续 Android 接收、私有归档、任务恢复及独立回执调用者见 [43 阶段](43-android-source-blob-receiver.md)。下文保留本服务端阶段边界；双向 blob 仍未完成。

Vela 本地 journal 已在捕获时保存音频 SHA、大小、内容类型、原始 metadata，并在 `event-journal/blobs/<sha256>.wav` 留存不可变原件。上传队列 ACK 可以清理 queue 中的 WAV，但不能删除这个归档。现有 `vela.capture.input.*` 是仍待处理捕获的输入链路，不能查询所有历史归档；processed 结果和 source envelope 入库也不能证明音频已经交付。

本阶段为已认证 source 通道增加归档读取和独立 blob 应用回执。它不创建捕获、不调用转写/分类、不修改卡片，也不改变旧 raw/processed 回执的含义。

## 原文与 blob 绑定

新增 `buffer_source_blob_open(sequence, record_fingerprint, blob_fingerprint, bytes, &fd)`。它在本地 journal 锁内恢复 pending、读取已提交原文、核对 envelope SHA 和源序号，再检查 CaptureCreated/LegacyAudioSnapshot 的 `payload.blob`：content_type 必须是 audio/wav，哈希及大小必须与请求完全一致。不能仅知道一个 blob 哈希就读取任意文件。

路径只从固定归档根、64 位小写十六进制哈希及固定 WAV 后缀构造。打开使用 O_NOFOLLOW（平台提供时），检查常规文件、私有权限和大小；小于 44 字节或超出 `CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES` 的文件拒绝读取。默认上限 512 KiB，现有 Kconfig 允许最高 2 MiB，仍以当前实际配置为准。

新增 `descriptor_digest` 对**将用于分片读取的同一个文件描述符**完成 SHA 和大小核对。保留旧 `file_digest` 的路径包装，用于原有归档与复制检查；包装负责关闭 fd，blob open 则将核对后的 fd 交给 worker。避免先校验路径、关闭后再重新打开而读到另一个对象。内部流式哈希缓冲只有 1 KiB，不把整份音频载入 RAM。

## Wire 服务

公共字段为 type、protocol=1、exchange_id；沿用严格 JSON、字段数、nonce 格式、身份、安全整数和协商帧预算检查。

| 帧 | 公共字段之外的字段 |
| --- | --- |
| `buffer.events.blob.get` | source_id、source_sequence、record_sha256、blob_sha256、offset、total |
| `buffer.events.blob.part` | source_id、source_sequence、record_sha256、blob_sha256、offset、total、blob_base64 |
| `buffer.events.blob.accepted` | source_id、source_sequence、record_sha256、blob_sha256、total |
| `buffer.events.blob.receipt` | source_id、source_sequence、record_sha256、blob_sha256、total、state=stored |

请求只允许本机 source 和已提交 head 范围内的记录，不转发远端归档。初次打开及 accepted 都先复核对应事件的完整分享策略；其中 schema、私密上下文和副本 origin 规则与 source pull 相同。敏感事件仍 withheld，不因请求的是音频而绕过门禁。当前通道授权在每个请求入口与返回前复核。

worker 最多保留一个已验证的只读音频 fd，绑定 peer、generation、source、sequence、两种哈希及总大小。内容分享判定只依赖固定原文与绑定 peer；会话授权单独实时检查。其他事件分片的缓存和反向接收组装保持独立。

每次分片重新检查 fd 的类型、大小和私有权限，并按请求 offset 定位；短读、提前 EOF 或 I/O 错误不会返回成功分片。分片最多 3072 字节，按 budget 预留 640 字节头部再缩小；无法容纳分片时明确 unsupported。数据与 Base64 临时缓冲使用后擦除。完整音频仍需接收端在入库前自行核对 SHA；发送端首次核对不能替代接收端完整验证。

fd 在最后一次访问后 30 秒关闭；授权/generation/peer 变化、worker 停止和 wire reset 也关闭。单调时钟不可用时不继续保留这个缓存。文件持有期间不持 journal 锁；初次完整哈希和回执核对需要串行读取当前配置上限内的文件。这些是容量和持有范围，未验证实际耗时或整机 RAM 峰值。

## 独立持久回执

新增 `buffer_source_blob_accept`，再次核对来源、原文、描述符绑定与真正归档内容，再把应用确认保存到 `event-journal/blob-deliveries/<peer>-<sequence>.json`。七个字段为 schema_version、peer、source_id、source_sequence、record_sha256、blob_sha256 和 total。

该记录独立于 source event 的连续交付游标，不追加另一条 source event，也不更新/删除音频、源原文或旧历史。它表示已认证对端应用对特定原文所绑定音频的确认，不是密码学上的远端持有证明；对应接收器必须在完整校验及持久保存后才发送 accepted。接收器尚未接入，当前没有自动发送这些确认的 Android 调用者。

写入沿用私有原子 writer 的临时文件、数据 fsync、rename 和后端提交边界。重复请求从磁盘读取严格 JSON 并逐字段比较，相同才幂等返回；已有冲突或坏回执不会被覆盖。

先前 rename 可见而后端同步失败时，不能仅凭文件可读就确认持久化。新增 `confirm_receipt`：宿主后端再次同步父目录；NuttX 不支持目录 fd fsync，因此通过原子 writer 重新提交相同传输元数据，重新经过真实 LittleFS/cfg.sync 边界。已验证的 source 发送游标也采用此路径，补齐 39 阶段在 NuttX 上的同类边界。原文和音频不被改写，不产生新 source event；代价是读取已有回执/游标时多一次小型元数据写入，实际写放大与硬件耗时仍待运行验收。

回执目录同时进入 journal 初始化和身份丢失保护；留有 blob 交付元数据时不重新生成 source。授权回调只读取 38 阶段的短时快照，不反向获取 Fabric 或 journal 锁。对外回复仍在当前会话有效时才入队。

本地文件权限错误现归为 storage_error，避免误报为远端 source_owner；父事件本地定位服务也采用相同区分。远端来源绑定错误仍保留其 source_owner 语义。

## 静态回顾与剩余范围

逐段读取了旧 file_digest/归档调用、同 fd 核对与关闭、声明和关闭配置 stub、本地原文读取、描述符绑定、原子回执、初始化身份保护、wire 字段/预算、重复请求、缓存隔离、空闲/关闭/撤销和本地错误分类。C 文件已在现有 Make/CMake 清单中，没有新增构建文件或运行检查程序。

后续仍须接入 Android 的 blob 描述符发现、持久接收任务、文件 fsync/原子保存、重连补传、旧 raw 输入复用和源 blob 状态显示；接收完成后才发送独立 accepted，并补齐 Android→Vela 方向及手机原始音频的可移植哈希描述符。权限保留槽、完整父链、业务投影、全部事实覆盖和任务 06、07 继续待办。任务 05 和整体目标保持未完成。

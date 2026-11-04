# Android 到 Vela 的源事件发送与回执

日期：2026-10-24。任务 05 的反向传输子阶段。只修改源码、文档并做静态回顾，没有测试、构建、语法检查、配置生成或设备操作。已接通手机自己的 source stream 到 Vela 远端 journal，并加入手机发送侧逐事件持久回执和回执补记。权限保留序号、Vela 发送侧回执、投影重放与 blob 仍未完成。

后续 Vela 发送侧持久回执与手机已入库证明补记见 [39 阶段](39-vela-delivery-receipts.md)。下文保留本阶段的实现边界。

## 接入与仓库边界

Android 新增 `data/device/VelaSourceEventPush.kt`，在 Enrollment 的已认证 Active 状态创建，和 pull 控制器共用应用 worker 与现有发送器，断连时销毁。卡片、pull、push 三项轮流获得优先发送机会；配置、既有回执、心跳和 runtime 保留原有优先级。各控制器只接受当前逻辑请求 nonce 的回复，不因同一 `buffer.events.*` 命名空间而串用回执。

Vela 扩展 `buffer_source_wire.c`，从 Fabric 应用 worker 处理 offer、put 和 proof。35 阶段的远端接收、进度与原文读取 API 已有网络调用者；36 阶段的分批索引重建也通过 busy/EAGAIN 接到实际重试路径。

重新读取独立 Fabric 的授权消息契约，并核对独立 Android BufferRepository 仍是数据库版本 19、没有现行源事件控制器。此阶段继续修改参赛快照中的应用集成，未覆盖外部仓库或更改通用 Fabric 协议实现。现行 Android 数据库从 22 增至 23，只增加交付回执表；原 source ID、事件、序号和 envelope 不重建。

## 新增 wire-v1 帧

公共字段为 type、protocol、exchange_id，protocol=1；每个逻辑请求有新的 32 位十六进制 nonce，重试复用同一 nonce。

| type | 公共字段之外的字段 |
| --- | --- |
| `buffer.events.offer` | source_id、last_sequence |
| `buffer.events.ready` | source_id、next_sequence |
| `buffer.events.put` | source_id、source_sequence、offset、total、record_sha256、record_base64 |
| `buffer.events.proof` | source_id、source_sequence、record_sha256、total |
| `buffer.events.receipt` | source_id、source_sequence、record_sha256、next_offset、total、state、next_sequence |

错误帧沿用 37 阶段的固定五字段格式。新增 restart、history_rollback、source_owner 和 invalid 等固定状态，不携带原文或敏感错误详情。客户端、服务端均验证字段集合/类型、安全整数、来源身份、标准 Base64、协商预算和请求 nonce；Fabric 分发入口也按当前协商上限检查实际请求长度。

## 协商、分片与持久提交

每次连接先 offer 本机 source 与当时 head。Vela 按已认证 peer 和独立来源绑定返回真正的连续提交进度；手机不以旧本地交付记录替代这个查询。对端报告进度超过手机的已知源 head 时拒绝隐式回退或重置来源。

每个 put 的原文片最多 3072 字节，并按当前消息预算缩小。Vela 只组装一个最多 1 MiB 的入站 envelope，绑定 peer、generation、source、sequence、总大小和指纹。重复片必须与已组装字节一致，未来偏移、重叠不一致、变更总大小或指纹都不会越过检查。没有可续用的前缀时返回 restart，手机重新协商并保留原始事件。

partial 只表示 RAM 前缀，不是持久回执。完整组装后，Vela 验证全记录 SHA-256、严格 envelope、源 ID/序号，再调用 `buffer_source_remote_receive`。只有原文、指纹、全局 ID 索引、待投影项、观察时钟、独立 owner 序号和源 head 经过可恢复提交后，才返回 stored/duplicate。gap/conflict 不推进接收事实，也不执行 payload。

索引重建返回 EAGAIN 时发 busy。手机保留同一请求和待确认原文，有界退避后继续；完整最后一片也可以重复提交，驱动同一索引迁移继续。普通无响应有较长退避，旧固件不支持此协议时继续保留既有 processed/CRDT 链路。

Vela 的入站组装和出站 pull 缓存分开：查询 pull manifest 不会破坏正在接收的反向分片，offer 不会破坏出站原文缓存。每方向最多一个 1 MiB 原文缓冲，另有有界 JSON/分片对象；这不是整个进程的总 RAM 承诺。会话失效或 worker 停止会清理缓冲，空闲检查还会丢弃 30 秒未使用的缓存。已提交数据不随缓冲过期删除。

## 手机发送侧持久回执与恢复

数据库版本 23 新增 `source_event_delivery_receipts`，主键为 peer/source/sequence，内容为 event_id 和 fingerprint。它属于传输元数据，不再追加一条 source event，避免交付回执被再次复制形成循环。

手机只为完全匹配当前原文的 stored/duplicate 回执写入该表，事务内重新核对本机来源、head、事件 ID、序号、指纹和精确原文。重复回执幂等，冲突不覆盖。提交前后仍复核当前通道授权。手机回执存储失败时保留已确认记录并重试本机事务，不推进到下一事件，也不重新解释为远端没有保存。

为处理“Vela 已提交，但手机在记录回执前退出”，客户端会查找本机尚无匹配回执的最早事件：

- 若接收进度尚未越过该事件，重新发送原文。即使本机旧回执存在，也不能跳过接收端现在缺少的事实。
- 若接收进度已经越过它，则请求 proof。Vela 从真正已提交原文重新读取并核对其哈希与大小，才返回 duplicate 证明；不能仅凭 head 或缓存回复。

这样可补记单条回执而不必重传整个已保存 envelope。手机仍保留全部源原文，不因交付记录或 proof 删除事件、音频或旧历史。旧本地回执无法单方面掩盖接收端数据丢失。Vela→Android 方向目前仍没有对应的 Vela 发送侧持久回执，本阶段不将两端的交付状态都标为完成。

## 授权与锁顺序

此前的 `app_authorize` 会获取 Fabric 执行锁；而执行线程的既有发送路径会在该锁内读取 journal。如果远端 journal 持锁时再调用它，会形成 Fabric→journal 与 journal→Fabric 的锁序风险。

新增源事件专用授权快照，记录当前 generation 和已认证通道是否有效，受独立的短时 status_lock 保护；它不使用 UI status_view 作为授权。快照由执行锁拥有者在信任失效、Active 状态更新、发布状态和释放条件等待前更新。信任写入之前先发布失效。源事件存储回调只读取这个快照，不获取传输锁、不调用 journal 或存储 API。

Fabric 应用 worker 仍在传输锁外做 JSON、分片和文件 I/O；回复入队前继续检查当前 generation 与绑定。status_lock 内只复制/核对内存，不再进入 Fabric 或 journal，因此不会引入反向锁路径。此项是源码锁序回顾，未以运行压力测试替代证明，也没有声称已做并发验收。

## 隐私与副作用边界

手机只选择本机 source，不转发接收的第三方 source。每次真正发送分片或 proof 前，包括缓存请求的重试，重新核对 `mayShareSourceEvent`、当前分享设置、本机系统权限及会话授权；撤销分享会清掉尚未发送的队列和原文缓冲。未知事件 schema 不自动放行。为避免每片反复解析整份原文，控制器保留自己拥有的已验证事件对象，但分享策略与权限仍每次重新读取。

重协商先擦除旧编码分片，再释放用于分享检查的事件对象；即使新 manifest 的数据库读取失败，也不会留下缺少策略对象却仍可重试发送的旧帧。发送器还显式拒绝这种不一致状态，避免异常路径绕过逐片分享检查。

核对了当前 `DocumentMerged` 的 payload：它只含文档 ID 和时钟，并不携带完整 CRDT 上下文，不能误报为已有泄漏。CaptureContextCaptured 只在明确分享及对应本机权限仍有效时允许；请求、策略和第三方导入上下文等其他 CaptureContext 历史仍保留在本机。已经进入载体或已经被对端保存的事实不被描述为可以通过本机开关追溯撤回。

**受限序号现在仍阻塞范围。** 没有把它改写成空 payload，也没有跳过后宣称同步完整；权限保留槽与后续补发需要继续实现。源数据导入仍只进入原文 journal 和待投影队列，不能启动历史日历、健康、提醒、助手或模型动作。

## 静态回顾及未完成项

逐段回顾了新建/升级数据库、正常分片、重复与迟到请求、暂忙重试、最后一片后的中断、手机回执事务失败、proof 补记、接收端进度回退、sharing 撤销、peer/generation 更换、双向缓冲互相干扰、缓存过期、协商预算与锁序。现有 C 模块已在 Make/CMake/host fixture 源文件列表中；Android 源文件由现有构建目录收集。未运行任何 fixture、测试或构建。

双向 source 原文入口已接通，但权限保留序号、Vela 发送侧回执、缺失父项处理、受限投影重放、blob 交付、完整状态 UI、剩余业务入口覆盖和最终全链路回顾仍待完成。任务 05、能力生命周期任务 06、多成员任务 07 及整体目标均未完成。

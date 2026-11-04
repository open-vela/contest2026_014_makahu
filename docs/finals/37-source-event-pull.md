# Vela 到 Android 的源事件拉取

日期：2026-10-24。任务 05 的单向传输子阶段，仅实现与静态回顾代码，没有测试、构建、语法检查或设备操作。已将 Vela 本机 journal 的拉取服务接入 Fabric 应用 worker，将 Android 拉取控制器接入现行 Enrollment。反向发送、权限保留序号、发送侧持久交付回执、投影及 blob 交付仍未完成。

后续反向接入和手机发送侧回执见 [38 阶段](38-source-event-push.md)，并增加独立的会话授权快照以避免存储回调反向获取传输锁；Vela 发送侧回执见 [39 阶段](39-vela-delivery-receipts.md)。下文保留本单向阶段边界；权限保留序号、投影和 blob 仍未完成。

## 仓库与载体边界

重新读取独立 Fabric 仓库 `crates/platform/src/messages.rs`：ReliableMessageTransport 的 send 只说明载体接受消息，不证明应用处理或持久化。新增协议复用已认证的 Buffer 应用消息通道，不修改 Fabric 的可靠消息、安全或 Session 实现。

独立仓库的 Android BufferRepository 仍为数据库版本 19，且没有当前 Enrollment/source journal 接入；本阶段修改参赛快照的现行版本 22 应用，没有把旧独立应用覆盖回项目，也没有向外部仓库写入。源码路径分别为 Vela `app/buffer_app/buffer_source_wire.*`、Android `data/device/VelaSourceEventPull.kt`，以及对应 Fabric 分发与 repository 入口。

## wire-v1 拉取帧

每个逻辑请求使用新的 32 位小写十六进制 `exchange_id`；重试保留原请求和 ID。服务端逐次原样回显 ID。客户端推进到下一片或下一请求后，迟到的旧回复不再匹配，不会推进新请求、覆盖组装内容或误用旧错误。

| type | 固定字段 |
| --- | --- |
| `buffer.events.manifest.get` | type、protocol、exchange_id |
| `buffer.events.manifest` | 上述公共字段、source_id、last_sequence |
| `buffer.events.read` | 公共字段、source_id、source_sequence、offset、record_sha256 |
| `buffer.events.part` | 公共字段、source_id、source_sequence、record_sha256、offset、total、record_base64 |
| `buffer.events.error` | 公共字段、state、retry_after_ms |

protocol 当前为 1。manifest 是本机源身份与当时 head 的快照，不是整个 Buffer 已同步的证明。首次读取可使用空 fingerprint；后续分片必须携带已确认指纹。source 更换或指纹不匹配返回 conflict，客户端重新查询 manifest，旧历史继续保留。

双方检查固定字段与类型、有限整数及安全计数范围。C 入口和 Android 控制器共享各自已有的严格 JSON/Unicode/重复键检查；不因平台 JSON 解析器宽松而接受歧义对象。分片是精确 envelope 的 Base64，不重排键、不重新生成事件身份或逻辑时钟。服务端根据当前协商预算计算分片大小，每片原文最多 3072 字节，并再次核对实际编码后的整帧长度。客户端检查协商上限、标准 Base64、精确来源/序号、连续字节位置、固定总大小、全记录 SHA-256 与 UTF-8 原文往返。

## 持久接收与恢复

Android 的 `sourceEventResume` 从持久 source owner/cursor 恢复下一序号，并核对 head 对应的原始 envelope 与指纹。owner、cursor 和原文检查在同一数据库事务快照中完成，避免其他 repository 实例恰好提交首条记录时误报 owner 丢失。有历史但缺少 owner/cursor 时拒绝将其当作新来源；已保存进度高于远端 manifest 时报告 history_rollback，不自行回退或覆盖。

手机最多组装一个 1 MiB envelope。收全后调用现有 `importSourceEvent`，只有 stored/duplicate 的事务提交返回后才推进请求序号。源原文、接收进度和待投影队列仍由同一数据库事务保存。重复分片必须与已保存前缀字节一致；最后一片收到后本机事务失败，会保留完整组装内容并重试最后请求，不把本机存储错误当成载体丢包或成功。

断连或进程结束可以丢弃未提交的组装缓冲，因为 Vela 仍保留源原文；下一连接按持久进度重新拉取该事件。已提交原文不因重连重新生成新的 event_id。gap 重新协商，conflict、缺失源记录、权限拒绝或存储失败均不会推进本机提交游标。busy/storage_error 有界退避；连续无响应会进入较长退避，以兼容没有该协议的旧设备，继续保留既有 processed/CRDT 路径。

这个拉取阶段尚未向 Vela 写入独立的持久“源事件已交付”回执，也不会因此删除源日志或音频。下一次读取请求只负责请求数据，不被服务端当作清理源历史的授权。音频在 envelope 中有哈希引用，不表示 blob 已发送或保存。

## 授权、隐私与执行边界

Vela 在应用 worker 处理请求，只有短暂获取 Fabric 锁来复制当前 peer 与消息预算，随后在锁外做存储和编码。每个分片请求处理前与返回回复前都复核应用通道当前 generation 和授权。内存中只缓存一个经过验证的源 envelope，绑定 generation、peer、source、sequence；更换范围/对端、重新查询 manifest、处理请求时发现授权失效或 worker 停止时清除。保留缓存期间，worker 的空闲等待设一秒检查点，在锁外清除观察到失效的缓存；没有缓存时继续使用普通条件等待。新连接不会用旧 generation 的缓存回复。

当前内容策略只接受已知的本机 schema/payload 形态。复制卡片只能返回其原 authenticated origin，且含 capture_context、context_summary 或 context 等非空上下文的结构均拒绝从新通道导出；上下文事件和未知 schema/投影类型也拒绝。判断由不可变内容与当前 peer 决定，因此可以绑定缓存；实时通道授权仍逐片复核。不能把曾收到一份上下文卡片理解成永久转发许可。

**本阶段的 withheld 明确阻塞当前源范围。** 它不是已经持久化的可见性保留槽，也不会让接收端跳过该序号、修改 envelope 或宣称之后历史完整。受限项后面的事件仍等待后续权限保留序号协议接入。这一边界已保留在总任务清单，不能用本阶段关闭完整同步要求。

Android 只把拉取结果存入原始 journal 和待投影队列，不写入本机外部动作回执表，不启动分类、日历、健康、计时器或通知执行器。原 processed capture 与 CRDT 接口继续各自工作；其与新原始历史的去重、关联和最终投影仍待后续完成。

## 生命周期与调度

Enrollment 仅在经过身份核对的 Active 分支创建拉取器，在同一应用 worker 上接收回复和定时推进；关闭连接时清除请求与组装缓冲。请求发送与接收使用当前连接 generation 的授权闭包，旧 nonce/旧 generation 不进入新流。

已有回执、协调心跳、配置与 runtime 保留优先顺序；卡片副本与源事件请求轮流先调用发送器，共用现有每 100ms 一次的发送节奏，避免持续卡片更新饿死源历史。异常只保留未确认工作，不创建远端已完成标记。同步问题目前保存在控制器状态并以固定错误名写日志，不记录原文、个人信息或凭据；完整产品状态 UI 仍需接入最终同步状态。

Make/CMake 与现有 host fixture 的源文件清单已包含新 C 模块。静态回顾覆盖正常分片、预算下限、重复/迟到回复、最终提交失败、nonce 更新、source/head 变化、旧设备不响应、受限上下文、授权撤销、并发数据库读取及销毁路径。未运行 fixture 或编译，没有当前二进制或实机链路验收结论。

## 剩余范围

Android→Vela 源事件发送、Vela 远端接收器的网络调用、有界索引重建的暂忙接入、权限保留槽与后续补发、缺失父项处理、受限投影、blob 独立交付、发送侧持久回执、产品同步状态 UI 和全入口回顾都未完成。任务 05、06、07 与整个目标保持进行中。

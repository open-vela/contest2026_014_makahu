# Buffer 原始事件复制：静态审核

日期：2026-10-23。任务 05 的结论：尚未实现完整的源事件历史复制。已有可靠传输、捕获结果导入和卡片 CRDT 复制不能替代该要求。此次仅静态读取源码并更新审核记录，没有运行测试、构建或设备，也没有修改业务代码。

后续实施更新：Android envelope/journal 与原文导入存储已接入，见 [28 第一阶段实现](28-source-event-protocol.md)。以下保留最初审核证据；后续修改尚未完成 Vela、网络、重放或 blob 路径，因此整个任务仍未完成。实施阶段同样未运行测试或构建。

依据：[产品事件簿](../app.md)第 8 节、[手机与 Vela 文档](../buffer_phone_openvela_docs.md)第 2 节的不可变原始事件原则及第 3.3 节本地事件库。建议表名不是强制实现方式；审核判断以源事件身份、历史保留和恢复能力为准。

## 仓库与框架职责已核对

独立 Fabric 源仓库为 `/home/simon/projects/mocha/foundation.fabric`。本轮逐字节比较了源仓库与参赛快照的 `crates/platform/src/messages.rs`、`crates/storage/src/lib.rs`，两文件当前相同；未同步或修改任一仓库。

- `AuthorizedMessageChannel::from_plan` 从有效 SessionPlan 建立 ReliableMessages 授权，核对本地参与者、远端设备、通道端点、payload security 与容量。它负责通信边界，不定义 Buffer 的事件事实。
- `ReliableMessageTransport::send` 明确只表示载体接受消息，不表示接收应用已经处理或持久化。源事件复制仍需自己的提交回执。
- `Storage` 的 `registry_outbox`、`peer_registry_revisions` 跟踪 Offer/Requirement 注册表修订；`operation_results` 保存有过期时间的操作结果。这些表都不是 Buffer 事件日志，不应直接当作原始事件的永久去重账本。

因此，本项应优先在 Buffer 应用层定义复制语义，复用现有授权载体。不能据此将业务缺口归为 Fabric 没有可靠消息能力。

## 当前实际的数据路径

以下 Android 路径相对于参赛快照的 `foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/`；Vela 路径相对于 `app/buffer_app/`。

| 路径 | 已有证据 | 能证明的范围 |
| --- | --- | --- |
| Vela 未处理文本/音频 → 手机 | `VelaPendingCaptureReceiver` 校验分片、完整记录哈希及元数据，导入前与回执前复核授权；`importVelaPendingCapture` 保存捕获并调度相应处理 | 捕获输入导入，不是源事件链复制 |
| Vela 处理结果 → 手机 | `buffer_agent_sync.c` 发送 `vela.capture.processed.part`；`VelaProcessedCaptureReceiver` 完整组装、校验后调用 `importVelaProcessedCapture` | 不可变处理结果快照导入 |
| processed 应用回执 | 手机事务保存 receipt、capture、proposal；Vela 对匹配 capture/hash/request 的最终回执写独立 `.receipt`，不删除源记录 | 处理快照级的幂等交付；不能证明每条源事件已复制 |
| 手机 → Vela 卡片 | `velaReplicaPage` 枚举卡片、CRDT 文档和墓碑，`VelaCardReplicaSender` 按 card_id 扫描、分片并等待回执 | 派生视图及删除状态的复制 |
| Vela → 手机 CRDT | `buffer_crdt_sync.c` 扫描副本文档及 `.crdtack`；`VelaCrdtReceiver` 校验并调用 `mergeCrdtDocument` | 文档合并；本地创建可等待 processed 导入回执后发送后续字段变更 |
| 应用同步请求 | `buffer.sync.request/reply` 触发卡片扫描，`buffer_fabric.c` 检查当前 session generation 与请求 ID | 当前约定的卡片同步完成，不是事件全集完整性证明 |

这些现有路径应保留。`buffer_agent_model.c` 在用户修改前保存 `.processed` 基线，不能说已有处理快照会被普通卡片操作随意覆盖；Vela 的源记录和应用回执也不是收到传输 ACK 后即被删除。

## 尚未达到要求的部分

### 1. 导入端重建本地事件，未保存源事件身份与因果关系（P1）

`VelaProcessedCapture` 的数据模型包含 capture ID、转写、分段、状态及哈希，没有源 event ID、源父事件列表或源逻辑时钟。`importVelaProcessedCapture` 用这些字段新建 `CaptureCreated`、`CaptureTranscribed`、`ClassificationProposed` 等本地事件。

`BufferRepository.appendEvent` 每次生成新的 `evt` ID，actor 为本地 `deviceId()`，parent 从接收端已有事件中选择，logical clock 为本地 MAX+1。payload 中记录 source_device 或保留快照哈希，不能保留原始事件的身份及因果引用。先后在两台手机导入同一份设备结果，会形成各自的本地事件，现有代码没有将它们判定为同一条源事件的规则。

后果是可以恢复部分业务结果，但不能据此重建跨设备一致的“念头 → 研究 → 行动 → 结果”历史。已有事件表具有 ID、parent、clock 字段不等于传输路径已经保留它们。

### 2. 现有同步完整性和回执都以快照/文档为单位（P1）

卡片 sender 的 `after` 是扫描 card_id 的内存游标，processed sender 根据各记录的独立哈希回执选择未交付快照。这些路径可以重扫并重试，不应误报为完全没有断连恢复。

但当前数据协议没有源事件序列、事件范围摘要、缺口请求或按源设备持久化的事件导入进度。收齐当前卡片和 processed 结果，不能发现缺失的中间源事件；`buffer.sync.reply=complete` 也不能证明源事件全集已经完整复制。补齐时需要单独的事件层完成条件，避免改变既有卡片同步回执的含义。

### 3. 派生视图不能作为源历史的恢复替身（P2）

`mergeCrdtDocument` 合并当前字段并追加本地 `DocumentMerged` 或相关导入事件，既有 CRDT 解决的是文档状态冲突。它没有保存被覆盖的每一次源变更，也没有源事件重放到视图的入口。将当前 CRDT 展开成新事件只能得到接收端重建历史，不能补回已经遗漏的来源与因果关系。

复制范围还需要区分原始音频、转写、processed 结果和后续操作：已有 raw 输入链路可发送音频，但 processed-only 消息不携带音频；不能因为收到转写就声明对应 blob 已交付。未获上下文共享授权的私密字段也不能因新增事件通道绕过现有分享门禁。

## 按依赖关系拆分的后续步骤

1. 定义有版本的事件 envelope：namespace、源设备、稳定事件 ID、来源 actor、源时间、逻辑时钟、因果引用、payload 与校验；明确授权转发时如何验证真正的源身份。
2. 设计两端不可变 journal 与迁移：事件内容和稳定来源保持不变，接收顺序/本机索引另存；旧重建事件明确标为历史兼容数据，不伪造不存在的源 ID。
3. 建立幂等导入事务：同 ID 同内容可重放，同 ID 异内容必须报冲突；源事件落盘、接收进度和本地投影原子提交或通过可恢复队列衔接。
4. 实现有界批次、分片、校验、持久提交回执及增量同步；断连后从持久进度继续，能发现并请求缺失范围。明确父事件未到达时的等待与异常策略。
5. 明确 blob 的独立哈希、容量、传输和授权；事件已导入与音频已完整保存分别确认。沿用现有 processed/raw 路径时提供显式关联。
6. 接入事件投影与副作用边界：历史导入不自动新建系统日历、写健康数据或重复发送通知；执行命令与历史事实分离，现有用户确认、执行租约和回执仍是门禁。
7. 在两端已认证消息入口接入新协议，保留旧 processed/CRDT 兼容；明确降级时只同步了哪些数据，不能返回事件同步完整。
8. 完成后静态回顾新建/重放/冲突/坏包/容量/父事件缺口/重启恢复/撤销授权各路径，再勾选整个任务。运行验收仍单列，当前不执行。

本记录只完成了框架与应用职责的核对。事件协议、存储、导入和两端接入均保持未完成，没有以审核文档代替实现。

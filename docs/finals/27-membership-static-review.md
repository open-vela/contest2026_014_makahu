# Buffer 多成员接入与协调：静态审核

日期：2026-10-23。任务 07 尚未完成。本轮只做代码和文档审核，没有修改业务代码，没有运行测试、构建或设备。

依据：[产品设备网格要求](../app.md)、[应用协调设计](02-coordinator-election.md)第 12 节。目标包含多台移动设备及 Vela 的已认证成员集合、能力选择、成员变化、失联与任务恢复；不能把双端运行路径或框架接口存在当作该场景已经实现。

## 独立 Fabric 源仓库核对

读取的是 `/home/simon/projects/mocha/foundation.fabric`。已将 `platform/portable/src/session.rs` 和 `crates/session/src/election.rs` 与参赛快照逐字节比较，当前相同；未修改或同步框架。

- `crates/sdk/src/lib.rs` 定义发布 Offer、注册 Requirement、候选流、Session 提议、邀请及接受的通用接口。接口契约可以承载接入设计，但不是 Buffer 已调用这些多成员流程的证据。
- `crates/session/src/election.rs` 实现固定已提交成员配置的选举证书与持久投票；`crates/hub/src/runtime.rs` 有实际选举 tick、心跳和选举消息处理。框架能力不应误报为只有应用级优先级排序。
- 选举的 `record_committed_plan` 明确拒绝 membership hash 改变并返回 `MembershipChangeRequiresTransition`。已有固定成员选举不等于已经提供任意加入/退出的成员配置过渡。
- `platform/portable/src/session.rs` 明确是 two-member、one ReliableMessages-channel profile；`ChannelContext::validate` 要求 epoch=1。当前 S1 所用 portable BLE 路径不能直接承担通用多成员 Session 或选举后的任意 epoch 更新。
- Android SDK 的 `openBleMessages` 是按 address、ability、channel/port 等参数打开消息连接；调用该 API 本身不会自动构建 Buffer 多设备成员视图。

本轮没有运行框架现有测试，也不据历史文档中的测试数量声称当前多设备链路已验收。

## Buffer 当前实际接入

Android 路径相对于参赛快照 `foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/`；Vela 路径相对于 `app/buffer_app/`。

| 边界 | 代码证据 | 当前范围 |
| --- | --- | --- |
| Android 成员构造 | `data/device/VelaFabricEnrollment.kt` Active 分支对 local/device 两个 ID 求 membership hash，显式创建 PHONE 与 VELA 列表 | 固定双成员 |
| Android 路由 | 同分支 sendToPeer 仅接受 target==device | 单个已连接对端 |
| Android 应用选举 | `BufferCoordinatorElection` 构造固定 membersById，拒绝不在集合内的 heartbeat；PHONE/TABLET 同层，之后按 deviceId 排序，VELA 次之 | 算法可处理传入集合；实际 Enrollment 没有接入多设备集合或动态更新 |
| Android 任务门禁 | `BufferCoordinatorJobGate` 单例保存一个 election、sourceVelaId、generation；attach 更换时使旧租约失效 | 当前 Vela 来源 transcribe/classify 的本机协调租约，不是跨多成员任务分发服务 |
| Android readiness | 有模型配置/可达性检查；后台可用性由进程 importance 映射 | 不等于系统保证后台长任务一定完成 |
| Vela 成员 | `buffer_coordinator.c` 全局 self_id、peer_id，`buffer_coordinator_bind(local,peer)` 由二者生成 membership | 一个本机加一个手机 |
| Vela 选择 | 当前 phone 可用时优先，否则就绪本机接管，term 持久化并用于提交门禁 | 现有双端应用任务规则，不是框架多数选举 |
| 业务状态 | runtime、processed/raw capture、卡片 CRDT 和应用回执已有接入 | 第 05 项原始事件历史仍缺失，无法据此判断完整多成员事实源同步 |

## 尚未达到要求的部分

### 1. 多设备成员发现没有进入 Buffer 运行状态（P1）

即使 Android 枚举包含 TABLET，当前入口仍固定将本机标为 PHONE，并只接收一台 Vela。没有从经过认证和授权的 Fabric 成员状态构建手机 A、平板、手机 B、Vela 的统一 Buffer 集合。Vela 的单 peer 存储也不能表达目标集合。

只扩大 election 的 members 参数不足以补齐：数据路由、任务输入、授权 Channel 和失联通知仍然各指向一个对端。

### 2. 缺少成员过渡与授权通道生命周期（P1）

应用 election 针对固定 membership；框架固定配置选举也会拒绝直接更换 membership。当前没有从加入/退出请求、成员批准、版本提交到关闭旧通道/租约、建立新授权路径的完整过程。不能用排序后的设备 ID hash 单独替代双方认可的成员变更。

portable BLE 的双成员/epoch 1 限制是当前所选承载的实际边界，需要明确扩展方案或通过已有框架能力设计独立的授权链路。即使采用多个双端 Session，应用仍需实现全局成员视图、转发授权与任务归属，不能假设会自动形成 mesh。

### 3. 双端租约和 fencing 不构成多成员任务执行闭环（P1）

现有任务门禁有价值：本机租约含任期、输入修订、Skill 版本、owner incarnation 和期限，断连或更换 election 会使旧句柄失效。现有应用选举明确允许分区各有协调者，不声称全局共识。

但 JobGate 只执行分配给本机协调者的 Vela 来源阶段，没有接入另一台手机的任务输入、远端 grant/renew/提交、结果去重与合并。多分区恢复时对同一逻辑任务的重复结果和外部副作用，也没有由现行双端门禁解决。不能把“有 lease 数据类”当作跨设备租约协议已经完成。

## 按依赖关系拆分的后续步骤

1. 选定实际承载拓扑并核对框架接口：可用的通用 Session 路径、portable BLE 扩展或多个授权双端 Session；记录每种路径的容量与不支持状态。
2. 建立已认证成员存储与集合事件：设备身份、用户批准、设备类别、能力/版本、成员配置版本与撤销状态。附近发现或 payload 自报不能直接成为成员。
3. 定义成员加入/退出的提交过程及失败恢复；旧配置仍有任务时明确租约失效、交接与结果接收规则，避免两个配置同时接受新任务。
4. 以成员集合替换 Android Enrollment、单例 JobGate 和 Vela 单 peer 假设；按成员维护授权通道、心跳、状态修订、进程 incarnation 与断连清理。
5. 接入能力/readiness 选择及稳定优先级：先判断目标阶段可执行，再比较移动端与 Vela，最后按稳定身份排序。能力版本与输入修订进入任务身份。
6. 实现远端任务输入、grant/renew、持久接收、执行提交和最终回执；区分 Fabric Session term/epoch 与应用任务 term，旧配置、旧任期、旧进程结果按明确规则处理。
7. 接入任务 05 的原始事件复制及现有 CRDT 投影，处理分区恢复、同任务结果冲突、blob 缺失和外部动作幂等；不能以最后收到的状态覆盖源历史。
8. 静态回顾成员加入/退出、协调者失联、候选 readiness 变化、重启、重复消息、过期租约、旧成员延迟消息和撤销授权的完整路径，再勾选整个任务。运行及多设备验收仍单列。

当前只完成框架与应用接入边界核对，以上步骤尚未实现完成。任务 07 保持未完成。

# Coordinator 自动选举与任务接管

日期：2026-10-17。状态：Android Buffer 应用任务 Coordinator 已实现并接入 Vela Fabric Enrollment 的认证 peer 消息和 100 ms worker tick。当前运行视图固定为手机与一台 Vela；这是应用层 term/任务租约机制，不是全局多数共识，也不代表 BLE 多成员 mesh 已完成。

关联：[A2UI renderer](01-a2ui-lvgl-renderer.md)、[Agent 能力下放 Vela](03-vela-agent-capabilities.md)。

## Foundation Fabric 开发仓库位置

框架相关开发在独立仓库 **`/home/simon/projects/mocha/foundation.fabric`**（即 `~/projects/mocha/foundation.fabric`）进行，远端为 [6xingyv/foundation.fabric](https://github.com/6xingyv/foundation.fabric.git)。

`/home/simon/vela/contest2026_014_makahu/foundation.fabric/` 是参赛项目内的源码快照，不是本轮框架修改的工作仓库。本文指向项目内 Fabric 文件的相对链接仅供阅读当前快照；实现前应在上述独立仓库核对最新接口与约束。框架变更完成并验证后，再按需同步到参赛项目快照。

## 1. 已确定的产品规则

Coordinator 自动产生，产品规则为 **手机 > Vela**，不提供手动选择主设备的产品入口。先筛选具有目标能力、配置有效且可运行的设备，再比较优先级。当前 Android 实现把 PHONE 与 TABLET 放在同一移动设备优先级，再由稳定 `device_id` 排序；Vela 排在其后。实际 Vela Enrollment 当前只建立 PHONE + VELA 两成员视图，尚未接入平板或第二台手机。

手机正常时优先承担跨设备的转写、分类、关联及生成工作。手机休眠、退出、网络或服务不可用时，具备相应能力的 Vela 可以接管。设备都不可用时任务持久化等待，录音与文本采集仍可继续。

Coordinator 不是唯一数据库。手机与 Vela 保留可同步的数据副本，Vela 从同步到的卡片中自行挑选展示内容。设备本地闹钟、计时器及其提醒执行不随 Coordinator 迁移。

[早期产品文档](../app.md)中“可先手动指定协调设备”的表述是历史阶段设想；本设计采用自动选举。

## 2. 两种 Coordinator 必须分开

| 角色 | 负责 | 不负责 |
| --- | --- | --- |
| Fabric Session Coordinator | Session 协商、成员视图、路由计划、prepare/commit、会话 epoch | Buffer 语义理解、LLM 任务归属、业务写入冲突 |
| 应用任务 Coordinator | 能力匹配、任务分派、租约、接管与结果归并 | 绕过 Session 契约修改传输路由、改变物理设备执行归属 |

[Fabric 通用 Hub 的 Coordinator 选举已接入 Rust 生产 QUIC 控制路径，并由三成员 host QUIC 集成测试验证；portable BLE 仍不承载多成员选举，边界见 [Fabric 独立设计](04-foundation-fabric.md)。这与 Android Buffer 应用任务选举是两套独立实现。两层使用不同的状态和版本：Fabric `session_epoch` 管会话计划，应用 `coordinator_term` 管 Buffer 任务提交 fencing。

应用优先级不强制改变已可用的 Fabric Session 路由；确需重配置时，通过 Fabric 的正式会话流程完成。

## 3. 候选资格与能力公告

Android Buffer 当前在已认证 Fabric application channel 上交换以下心跳字段。这是应用协议，不是 Fabric Hub election 控制消息：

| 当前字段 | 当前用途与约束 |
| --- | --- |
| `protocol_version`, `session_id`, `membership_version` | 固定应用会话及成员视图；Enrollment 当前以本地 Fabric ID 与已认证 Vela ID 的排序组合计算 membership hash |
| `device_id`, `incarnation_id` | 载荷设备 ID 必须等于 Fabric 认证 peer ID；incarnation 区分进程实例，拒绝已退休实例的迟到心跳 |
| `state_revision`, `heartbeat_sequence` | 拒绝较旧状态和重复/倒退的心跳序号；重放的旧心跳不刷新存活时间 |
| `readiness` | `configuration_valid`, `service_reachable`, `background_execution_available`, `storage_available`, `can_coordinate`, `capabilities` |
| `coordinator_term`, `coordinator_device_id` | 当前应用协调者公告；term 独立于 Fabric Session epoch，并按 session 与 membership 持久化 |

设备类别来自本地已认证成员配置，不在心跳载荷里自报。当前能力枚举为 `TRANSCRIBE`、`CLASSIFY`、`ANSWER`、`GENERATE`；没有能力版本字段。心跳不包含 `current_jobs`；单个任务的输入修订和所有权由租约记录表达。动态成员变更、成员视图共识和多设备公告仍属设计范围，不是现有协议能力。

心跳只公告 readiness，不发送 API key。Enrollment 用配置有效标志和合成文本/音频的 LLM、ASR 实际探测结果形成资格；仅有配对、BLE 活跃或最近心跳不足以让手机 eligible。当前 `background_execution_available` 由 Android 进程 importance 不高于 `IMPORTANCE_VISIBLE` 映射，不能解读为操作系统保证后台长任务一定可运行。能力与 readiness 必须绑定已认证的 Fabric peer，陌生设备不能参与。

候选顺序：满足协调资格和任务能力 → PHONE/TABLET 移动设备优先于 VELA → 同优先级按稳定 `device_id` 排序。电量等综合分数尚未实现，不参与现有排序。

默认 Coordinator 负责调度，具体阶段仍按能力选择执行者。例如手机可以整理但暂时不能转写时，允许把转写阶段交给具备该能力的 Vela。

## 4. 选举与平稳交接

建议状态：观察中、候选、协调中、交接中、降级等待。选举基于已认证成员的当前可见视图；不能把本地判断误当作全网共识。

当前 domain 默认值为心跳 3 秒、漏 3 次超时（9 秒）、任务租约 20 秒、每 5 秒续约、优先候选恢复稳定窗口 15 秒。Android Vela Enrollment 显式把漏心跳数设为 10，因此实际该两成员运行时的静默失联门槛为 30 秒；Enrollment 每 100 ms tick，runtime adapter 按 3 秒 cadence 尝试公告心跳。显式的本地认证连接断开会立即 detach job gate 并 fence 本机旧任务。15 秒是较高优先级候选恢复后的稳定观察窗口，不是 30 秒失联检测的一部分。这些参数不提供网络分区下的全局唯一执行保证。

2026-10-17 的 BLE 运行时采用每 3 秒发送、30 秒未收到有效心跳才判定静默失联：原始音频、卡片和控制消息共用有界可靠通道，Windows 联调实际观察到超过 9 秒的投递间隔，不能据此立即中断健康手机的任务。明确的链路断开仍立即失效租约；恢复稳定窗口保持 15 秒，任务租约仍为 20 秒并每 5 秒续约。Android domain 的默认参数测试保留 3 次心跳，实际 Enrollment 显式传入 10 次；Vela 使用相同 30 秒边界。

较高优先级手机恢复后，必须在 15 秒稳定窗口内持续 ready，随后 Vela 进入 `HANDING_OFF`，停止接受新任务；手机 term 被认证公告采纳并持久化后，Vela 的旧 term lease 不能提交。当前实现没有跨设备任务检查点迁移协议，不承诺长任务无损续接；接管可导致重复计算，提交 fence 阻止旧结果覆盖新任期结果。

所有 lease 超时使用本机单调时钟判断，不能直接比较两台设备的单调时钟值。重启后旧本地租约视为不可继续，恢复前重新协商。墙上时间只用于可读日志及业务时间，不作为唯一的选举依据。

## 5. 任务所有权与提交

当前 `CoordinatorLeaseGrant` 字段为：`job_id`、`capture_id`、`stage`、`input_revision`、`skill_version`、`owner_device_id`、`coordinator_term`、`lease_id`、`ttl_ms`、`renewal_sequence`、`owner_incarnation_id`。Buffer job gate 当前为 Vela 来源的 `transcribe` 和 `classify` 建本地 20 秒 lease，并由 Enrollment tick 每 5 秒续期；这是本机协调域中的 lease 更新，不应描述成已验证的跨设备 renew 请求/回执协议。`attempt_count`、阶段检查点和结果摘要尚未成为该 lease schema。

fence 当前只作用于 `source_device` 是 64 位十六进制 Fabric Vela identity 的远端采集。手机本地 capture 继续走既有处理路径；Vela 独立 Assistant 留在 native executor，不由 Android Buffer gate 接管。提交时在 Repository 锁下调用 domain `commitIfCurrent`，复核本机 Coordinator role、term、owner、lease 和 deadline；断链、term 提升或角色降级后丢弃旧输出。分类 fencing 失败会释放本次分类请求租约回 pending，避免旧请求发布卡片。

1. 根据源记录、处理阶段、输入修订和 Skill 版本生成稳定的逻辑任务 ID。
2. 在本地事务中领取任务，记录 owner 与 fencing token，然后开始外部调用。
3. 转写、检索、结构化生成分别保存检查点；接管从可验证的检查点继续。
4. 提交结果前再次校验所有权、输入修订、删除状态及用户修改状态。
5. 以确定性的结果 ID 去重提交，再同步到其他副本。

fencing token 由接收写入的业务存储验证，而不只是请求里附带一个数字。已被该存储观察到的新 term 应拒绝旧 owner 写入。独立分区之间没有共同权威时，单靠递增 term 不能阻止两边各自提交，必须走下一节的冲突归并。

用户编辑具有保护边界：处理期间修改了正文、分类或关联关系，旧 AI 输出不得覆盖相应字段。删除使用 tombstone，同步旧结果不能复活已删除卡片。有效但过期的生成内容可保留为待处理建议，不静默取代用户内容。

## 6. 网络分区与副作用

两台设备在无仲裁节点的情况下，无法同时保证分区可用和全局唯一执行。本项目首版选择允许采集与可归并的 AI 计算继续，在恢复连接后去重归并；不承诺 LLM 请求 exactly-once，也不承诺绝不产生重复计费。

分区期间双方可能都认为自己应执行任务。归并时保留两份执行记录，按预先定义的有效性及稳定顺序选择一个展示结果；不能按最后到达直接覆盖。输入修订不同的结果分别处理，用户编辑和删除优先受保护。

有物理副作用的操作采用设备归属约束：

- Vela 闹钟、计时器由目标 Vela 的本地服务统一提交，远端只发送幂等命令。
- 手机无法连接目标 Vela 时显示“待设备确认”，不能宣称已经设置成功。
- 创建请求以稳定 operation_id 去重，重复传送不得产生第二个闹钟。
- 取消等命令校验对象版本，拒绝过期命令修改重建后的实例。

若未来要求跨设备外部动作严格唯一，需要另行引入仲裁/共识或服务端幂等机制；不能靠缩短心跳间隔解决。

## 7. 同步与展示

同步范围是全部获授权卡片及关联、修改、删除和任务状态，采用增量传输和断点续传。媒体按需求传输，避免把全量音频当作每轮同步的前置条件。

Vela 的当前展示卡片、暂时跳过、置顶及展示历史属于设备展示策略，不由 Coordinator 强制推送“唯一当前卡片”。全局收藏状态与本地展示偏好应使用不同字段。

选举、控制消息和任务确认保留独立带宽预算，音频或大批量卡片同步不得阻塞续约。Fabric 队列或链路恢复不代表业务任务恢复完成，仍需交换检查点与版本。

## 8. 实施顺序与验收

现有 Android domain 测试覆盖手机优先、失联后 Vela 提升 term、重复心跳拒绝、15 秒手机恢复窗口、term 持久化、20 秒 lease/5 秒续期、进程重启不恢复旧 lease，以及失去 Coordinator fence 后不提交旧结果。Enrollment 已将 domain 接入认证 BLE peer 与 100 ms worker tick，并通过真实 LLM/ASR readiness probe 控制手机资格。

仍需分别验收实际设备上的 30 秒 BLE 静默超时、明确断链即时 fence、15 秒恢复交接与长任务提交；当前实现不证明四设备收敛、动态成员共识、跨设备 checkpoint 迁移、全局唯一分区执行或 exactly-once LLM 副作用。

| 验收情景 | 预期 |
| --- | --- |
| 手机与 Vela 都 ready | 当前固定两成员 domain 选择手机；无手动主设备入口 |
| 手机缺少 LLM/ASR readiness 或处理输入路径 | 手机不 eligible；由已接入的 Vela 侧能力处理，或等待可用执行者 |
| 静默失联 / 明确断链 | Enrollment 静默超时 30 秒；本地明确断链立即 detach gate |
| Vela 执行期间手机恢复 | 手机 ready 持续 15 秒后交接；旧 term 写入被 fence，不保证无重复计算 |
| Coordinator term 改变或 app 进程重启 | 旧 lease 不恢复；旧结果不能通过当前 domain commit fence |
| 多设备、分区和副作用 exactly-once | 仍是设计/验证边界，不由当前两成员应用选举保证 |

应记录任务 ID、owner、term、切换原因和结果去重原因；普通日志不记录密钥或完整私人内容。README 或旧说明中的“应用自动选举尚未实现”已过时，应改为本文列出的已实现范围与剩余边界。


## 9. 多设备接入与通信拓扑

目标场景包含手机 A、平板、手机 B 和 Vela 同时接入。选举及业务协议不能只存一个固定“对端手机”字段，应使用已认证设备集合、成员视图和各设备能力状态。

### 9.1 Fabric 已有多对多模型

独立开发仓库 `/home/simon/projects/mocha/foundation.fabric` 的 Channel 支持多个 sources 和多个 destinations，Router 按 SessionPlan 将各来源消息分发给目标。已有 `compiles_many_to_one_and_many_to_many_without_payload_schema` 和 `fans_out_one_source_to_many_destinations` 路由测试，见该仓库 `crates/router/src/lib.rs`。

这是已有的路由能力，不代表本项目的四设备选举和平台链路已经联调完成。逻辑多对多无需应用自行建立所有两两连接；底层链路与中继依照 Fabric 可用能力和 SessionPlan 安排，不能假设任意拓扑天然互通。

### 9.2 选举阶段：多对多 ReliableMessages

未来多成员方案可使用 `ReliableMessages` 交换候选公告、就绪状态、轮次及协调结果。当前 Android 业务运行时只使用固定 phone + Vela 成员视图，不能据此声称已实现手机、平板、第二手机和 Vela 的动态多对多应用选举。

已确定优先级继续为手机高于 Vela。两台手机资格相同时按稳定 device_id 排序；平板先按具备同等执行能力的移动端候选处理，作为待产品确认的默认策略，不擅自声称已确定手机与平板的额外优先级。排序前仍需校验目标任务能力和 readiness。

Fabric 的可靠消息保证单 Channel、单发送者有序，不保证不同发送者的全局顺序。因此公告需要携带成员视图版本、发送者序号及选举轮次；处理时丢弃过期状态，重复消息保持幂等。候选排序负责同一成员视图下的确定性选择，不能替代网络分区下的共识。

应用选举依托已经建立的认证 Fabric application channel，不要求先选出应用 Coordinator 才允许通信。通用 Fabric Hub 的生产 Coordinator 选举运行在 QUIC 控制路径；portable BLE 的两成员、单 `ReliableMessages` channel、epoch 1 transport 边界不等于 BLE 多成员选举。框架和应用的选举机制互不依赖，避免相互等待。

### 9.3 选举之后：一对一与一对多

| 通信内容 | 拓扑 |
| --- | --- |
| 采集设备向 Coordinator 提交任务 | 一对一请求及确认 |
| Coordinator 向执行设备分派工作 | 一对一请求及结果 |
| Coordinator 发布卡片变更、处理状态和协调公告 | 一对多 |
| 设备查询缺失记录、补传检查点 | 一对一 |
| 当前 Coordinator 失联或成员变化触发重选 | 恢复选举阶段的多对多交换 |

多台设备各自向 Coordinator 提交请求，整体也可表达为多对一，但每笔任务仍有独立请求 ID 和确认。设备本地计时器、闹钟继续由目标设备执行，不因业务消息集中到 Coordinator 而改变归属。

稳定阶段保留轻量选举控制 Channel，降低公告频率；业务数据按需要使用一对一和一对多 Channel。成员或业务拓扑变化时按 Fabric 正式流程重配置，不为每次任务重新建立全网会话。

### 9.4 与 BLE 可靠消息承载的关系

逻辑多对多和底层承载是两个维度。`ReliableMessages` over BLE transport 已实现并可承载应用心跳，但 portable BLE 目前限于两个参与端、一个授权 channel、epoch 1；它不提供 BLE 多成员选举、全互连 mesh 或 epoch 变更后的 channel 重建保证。平台连接数量、central/peripheral 角色和实际可达性仍须单独验证。

正常多设备接入优先使用可用的 QUIC 网络连接；仅有 BLE 时仅将实际可达的已认证成员纳入当前视图。需要中继时必须有显式支持的路由，不能将蓝牙广播直接视为可靠多对多消息。

### 9.5 新增验收

- 手机 A、平板、手机 B、Vela 同时加入，所有设备在连通且成员视图一致后收敛到同一 Coordinator。
- 同资格手机的结果不依赖启动或消息到达顺序；Coordinator 离线后剩余成员能够重选。
- 稳定阶段任务一对一分派，结果一对多同步，各设备只处理一次有效业务结果。
- 成员新增、退出、乱序跨源消息及恢复连接不会让旧候选公告覆盖新状态。
- 不同分区可分别继续允许的工作，重连后按既定任务归并规则处理，不能声称全程只有一个 Coordinator。

# 06. Session 状态机与通用路由

## 1. Session actor

每个 Session 由一个串行 actor 处理控制事件：

```rust
pub enum SessionEvent {
    ProposalReceived(...),
    LocalAccepted(...),
    PeerReady(...),
    CommitReceived(...),
    ParticipantDisconnected(...),
    LinkSuspended(...),
    ReconfigureRequested(...),
    Timeout(...),
    CloseRequested(...),
}
```

Actor 输出 commands，不能直接执行网络 I/O：

```rust
pub enum SessionCommand {
    SendControl { peer: DeviceId, message: ControlMessage },
    ReserveChannel(ChannelReservation),
    ReleaseEpoch(u64),
    NotifyApp(AppEvent),
    Persist(SessionRecord),
}
```

这样状态机可纯测试和模型检查。

## 2. 状态转换

```text
Proposed -> Negotiating -> Preparing -> Active
Active -> Reconfiguring -> Active
Active -> Suspended -> Active
任意非终态 -> Closing -> Closed
任意非终态 -> Failed
```

非法事件必须返回 `InvalidTransition`，不能忽略。

## 3. Coordinator

Coordinator 负责：

- 汇总 Session 提案；
- 分配 epoch；
- 发起 Prepare/Commit；
- 维护成员视图；
- 发起 ClockDomain 和 Barrier；
- 触发 reconfiguration。

Coordinator 不负责：

- 解释 payload；
- 业务级主从关系；
- 自动拥有所有数据流；
- 读取 E2EE payload。

Coordinator 离线时：

- 若契约允许重新选举，按确定性优先级选举；
- 新 Coordinator 必须提出 `epoch + 1` 的重配置；
- 若不允许选举，Session 进入 Suspended 并最终关闭。

## 4. 路由表

Router 将每个 Channel 编译为本地转发表：

```rust
pub struct CompiledRoute {
    pub channel_id: ChannelId,
    pub local_sources: Vec<LocalPortEndpoint>,
    pub remote_sources: Vec<RemoteEndpoint>,
    pub local_destinations: Vec<LocalPortEndpoint>,
    pub remote_destinations: Vec<RemoteEndpoint>,
    pub contract: ChannelContract,
}
```

路由只依据 SessionPlan，不动态猜测。

## 5. Fanout

### PerPeerUnicast

源 payload 对每个目标设备分别排队。优点：独立拥塞、权限、统计和 E2EE envelope。默认使用此模式。

### AllowRelay

Coordinator 可把图编译为 relay tree，但逻辑 Channel destinations 不变。Relay 只能转发不透明 payload。

### AllowMulticast

仅表示 Ability 和策略允许，实际 multicast adapter 不属于核心必需后端。使用时必须具备：

- 组密钥；
- epoch；
- replay protection；
- 成员变更 rekey；
- 单独的丢包反馈设计。

## 6. Backpressure

### ReliableMessages

每个 destination 有有界队列。队列满时：

- `BlockProducer`：SDK await，受 timeout 限制；
- `RejectNewest`：返回 BACKPRESSURE；
- 不允许静默丢弃可靠消息。

### ReliableStream

依赖 QUIC flow control，并在 IPC 层传播写入背压。Hub 不应把整流读入内存。

### Datagram

支持：

```rust
pub enum DatagramOverflow {
    DropNewest,
    DropOldest,
}
```

Router 记录丢弃计数。不能对 Datagram producer 无限阻塞。

## 7. 公平调度

同一 Link 的发送队列使用 deficit weighted round robin：

- 控制流永远有保留预算；
- 可靠高吞吐文件流不能饿死媒体 Datagram；
- Datagram 也不能饿死控制消息；
- priority 映射到权重而不是绝对抢占；
- 每个 App 和 Session 还要有总带宽/队列上限。

## 8. Ordering

- 控制流：Link 内有序；
- ReliableMessages：单 Channel、单 sender 有序；多个 sender 的全序不由框架保证；
- ReliableStream：单流字节有序；
- Datagram：无序、可丢失、可重复。

需要全局排序的 Ability 必须在 payload 中加入逻辑时钟或序列规则。

## 9. 多对一

Router 不自动合并语义，只标记 sender。多源可靠消息可以到达同一 destination port；SDK 事件必须包含 sender participant。

## 10. 多对多

不要自动建立 App full mesh。Hub-to-Hub Link 可复用，Session 逻辑图独立。对于同一 Channel，Router 按 Plan 将每个 source fanout 到所有 destinations，并避免回送给同一 PortRef，除非 Plan 明确包含自环。

## 11. Suspend/Resume

Link 丢失：

- 停止向该 peer 发送；
- 本地可靠队列按 Channel 的 suspend buffer limit 缓冲；
- 超限后向 App 报错；
- Datagram 直接丢弃并统计；
- Session actor 进入 Suspended 或做成员重配置。

恢复时：

- 验证 resume token；
- 确认 epoch；
- 可靠流通常重新打开，由 Ability 做断点协议；
- 不假设 QUIC stream 可跨连接恢复。

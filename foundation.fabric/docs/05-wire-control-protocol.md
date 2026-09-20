# 05. Hub-to-Hub 控制协议

## 1. 编码

控制协议使用 Protobuf。所有消息置于可靠、有序、双向 QUIC control stream。

外层帧：

```text
varint frame_length
varint message_type
varint request_id
bytes payload
```

必须设置：

- 最大帧 4 MiB；
- 最大递归深度；
- 字符串和集合数量限制；
- 未知字段保留；
- 未知 message type 返回错误但不必关闭 Link，除非违反版本协商。

## 2. 控制流建立

每个 Link 只允许一个 active control stream。首消息必须是 `ControlStreamHello`，包含 link generation。重复 control stream 按 generation 和 stream id 仲裁。

## 3. 消息族

### 3.1 链路与能力同步

```text
HubHello
LinkReady
RegistrySnapshotBegin
OfferUpsert
OfferRemove
RegistrySnapshotEnd
RegistryAck
RegistryResyncRequest
```

Registry 使用每个 peer 独立 revision：

```rust
pub struct RegistryRevision(pub u64);
```

Receiver 必须按 revision 原子应用 snapshot/delta。发现 revision 缺口时请求重同步。

### 3.2 Session

```text
SessionPropose
SessionCounterOffer
SessionAccept
SessionReject
SessionPrepare
SessionReady
SessionNotReady
SessionCommit
SessionAbort
SessionSuspend
SessionResume
SessionReconfigurePropose
SessionReconfigureAccept
SessionReconfigureCommit
SessionClose
```

所有 Session 控制消息必须包含：

```text
session_id
expected_epoch
sender_device_id
message_nonce
```

### 3.3 Clock 与 Barrier

```text
ClockProbe
ClockReply
ClockStatus
BarrierPrepare
BarrierReady
BarrierReject
BarrierCommit
BarrierAbort
```

### 3.4 安全

```text
PairingStart
PairingChallenge
PairingConfirm
PairingComplete
AppAttestationRequest
AppAttestationResponse
E2eeKeyOffer
E2eeKeyAccept
GroupKeyRotate
PolicyDenied
```

## 4. 请求幂等性

所有改变状态的请求携带 `operation_id`。Hub 保存最近操作结果的有界缓存：

- 重复 `operation_id` 返回原结果；
- 相同 ID 不同 payload 返回 `OPERATION_ID_REUSED`；
- 缓存至少覆盖最大重试窗口；
- Session commit、rekey、close 必须幂等。

## 5. Session 协商

固定流程：

```text
Proposer -> peers: SessionPropose(draft, nonce)
Peers -> proposer: Accept / CounterOffer / Reject
Proposer: 合并完全一致的接受结果
Proposer -> peers: SessionPrepare(final_plan, next_epoch)
Peers: 预留资源、验证本地 App 仍在线、准备端口
Peers -> proposer: Ready(plan_hash) / NotReady
Proposer -> peers: SessionCommit(plan_hash, epoch)
All: Active
```

`SessionCommit` 只有在所有 required participant Ready 后才能发送。optional participant 可按契约决定是否缺席。

## 6. Reconfiguration

任何成员、Channel、安全或时钟域变更均创建完整的新 `SessionPlan`：

```text
current epoch = N
proposed epoch = N + 1
```

禁止对当前 Plan 原地修改。Commit 后原子切换。旧 epoch Channel 按 `drain_policy` 关闭。

## 7. 流打开

每个 QUIC uni/bi stream 首部使用 `StreamOpen`：

```text
protocol_magic
session_id
epoch
channel_id
sender_participant
destination_binding_id
stream_flags
optional e2ee header
```

接收方验证：

- Session Active 或允许 resume；
- epoch 相等；
- sender 是 Channel source；
- 本地 participant 是 destination；
- stream 数量与权限未超限。

验证失败立即 reset stream，并记录结构化错误。

## 8. Datagram 外层

```text
u8  datagram_version
128 session_id
64  epoch
128 channel_id
128 sender_participant
varint sequence_or_zero
varint flags
bytes payload
```

Datagram 不得依赖控制流逐包确认。必要的 ACK/FEC/重传由 Ability 或独立 Channel 实现。

## 9. 错误

稳定错误类别：

```text
INVALID_ARGUMENT
UNSUPPORTED_VERSION
CONTRACT_MISMATCH
NOT_FOUND
ALREADY_EXISTS
UNAUTHENTICATED
PERMISSION_DENIED
POLICY_REQUIRES_USER_ACTION
RESOURCE_EXHAUSTED
STALE_EPOCH
SESSION_NOT_ACTIVE
PARTICIPANT_OFFLINE
CHANNEL_NOT_BOUND
MESSAGE_TOO_LARGE
BACKPRESSURE
CLOCK_UNAVAILABLE
CLOCK_UNCERTAIN
E2EE_REQUIRED
E2EE_NEGOTIATION_FAILED
TIMEOUT
INTERNAL
```

错误必须包含 `code`、`safe_message`、可选 `retry_after_ms` 和相关对象 ID。不要向远端暴露本地路径、堆栈或 SQL。

## 10. 规范文件

`protocol/fabric.proto` 是线协议唯一来源。Rust 领域类型与 protobuf 类型通过显式转换，禁止在核心中直接使用生成类型。

# 08. 通用公共时钟与同步屏障

## 1. 边界

`clock` 提供设备间时间映射和执行屏障。它不负责：

- 音频硬件输出延迟；
- 媒体采样时钟；
- 视频渲染延迟；
- 业务事件排序。

Ability 将业务时间映射到 `GroupInstant`。

## 2. 时间类型

```rust
pub struct LocalInstant(pub u64); // 单调时钟纳秒，进程/启动域内
pub struct GroupInstant(pub i128); // 会话时钟纳秒

pub struct Estimated<T> {
    pub value: T,
    pub uncertainty_ns: u64,
    pub measured_at_local: LocalInstant,
}
```

禁止使用 wall clock 做同步执行。系统时间跳变不得影响 SessionClock。

## 3. ClockDomain

```rust
pub struct ClockDomainSpec {
    pub id: ClockDomainId,
    pub master: DeviceId,
    pub epoch: u64,
    pub target_uncertainty_ns: u64,
    pub probe_interval_ms: u32,
    pub stale_after_ms: u32,
}
```

一个 Session 可无时钟，也可有一个主 ClockDomain。复杂 Ability 可在 payload 内定义其他媒体时钟。

## 4. 四时间戳测量

```text
client send      t1 (client local)
master receive   t2 (master group/local mapping)
master send      t3
client receive   t4
```

记录原始样本：

```rust
pub struct ClockSample {
    pub t1: u64,
    pub t2: i128,
    pub t3: i128,
    pub t4: u64,
    pub rtt_ns: u64,
    pub offset_ns: i128,
}
```

采用 NTP 类似公式估计 round-trip delay 和 offset；不要直接调整 OS 时钟。

## 5. 滤波和漂移

维护仿射模型：

```text
group_time = group_ref + rate * (local_time - local_ref)
```

```rust
pub struct ClockMapping {
    pub local_ref_ns: u64,
    pub group_ref_ns: i128,
    pub rate: f64,
    pub uncertainty_ns: u64,
    pub valid_until_local_ns: u64,
}
```

实现要求：

1. 保留最近固定数量样本；
2. 丢弃 RTT 明显异常的 outlier；
3. 优先低 RTT 样本估计 offset；
4. 用一段时间样本回归估计 `rate`；
5. 变化使用 slew，不做瞬时跳变；
6. uncertainty 随样本老化增加；
7. 超过契约要求时报告 `CLOCK_UNCERTAIN`。

## 6. Master 选择

默认由 Session proposer 指定。契约可声明选举策略：

```rust
pub enum ClockMasterPolicy {
    FixedParticipant(ParticipantId),
    CoordinatorDevice,
    DeterministicElection,
}
```

确定性选举排序示例：

1. 声明有硬件稳定时钟者；
2. 外接电源设备；
3. 更低历史 jitter；
4. DeviceId 字节序。

所有节点必须得到相同结果。

## 7. Barrier

```rust
pub struct BarrierSpec {
    pub id: BarrierId,
    pub session_id: SessionId,
    pub epoch: u64,
    pub participants: Vec<ParticipantId>,
    pub activate_at: GroupInstant,
    pub ready_deadline: GroupInstant,
    pub context: Bytes,
    pub policy: BarrierPolicy,
}
```

流程：

```text
Coordinator -> all: Prepare
Participant: 验证 epoch、时钟质量、资源和本地 Ability 状态
Participant -> Coordinator: Ready(estimate) / Reject(reason)
Coordinator: 根据 policy 判断
Coordinator -> all: Commit / Abort
Participant: 在 group_to_local(activate_at) 执行
```

## 8. BarrierPolicy

```rust
pub enum BarrierPolicy {
    AllRequired,
    Quorum { minimum: u16 },
    RequiredAndOptional,
}
```

音频同步通常 `AllRequired`；传感器采样可以 quorum。

## 9. Late commit

收到 Commit 时若已晚：

- 晚于 `activate_at` 但在 `max_lateness` 内：Ability 决定追赶或跳过；
- 超出：返回 `BARRIER_MISSED` 并不执行；
- 框架不猜测音频该丢多少采样。

## 10. 失效

- Clock master 离线：ClockDomain 失效，Session 根据契约暂停或重配置；
- uncertainty 超标：禁止发起要求精确时钟的 Barrier；
- reconfiguration：新 epoch 必须重新确认 ClockDomain；
- sleep/wake：本地 monotonic 连续性由平台 adapter 检查，不可信时重新采样。

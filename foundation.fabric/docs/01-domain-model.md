# 01. 领域模型与稳定数据结构

本文件中的类型应优先放入 `core`。它们不得依赖 Tokio、Quinn、SQLite、BLE、mDNS 或具体序列化框架。

## 1. ID 规则

所有外部可见 ID 使用 128 位随机值；Rust 内部使用 newtype，禁止裸 `Uuid` 在模块间流动。

```rust
#[repr(transparent)]
pub struct SessionId(pub uuid::Uuid);
#[repr(transparent)]
pub struct AbilityInstanceId(pub uuid::Uuid);
#[repr(transparent)]
pub struct RequirementId(pub uuid::Uuid);
#[repr(transparent)]
pub struct ParticipantId(pub uuid::Uuid);
#[repr(transparent)]
pub struct ChannelId(pub uuid::Uuid);
#[repr(transparent)]
pub struct PortId(pub uuid::Uuid);
```

稳定的 `DeviceId` 从设备公钥指纹派生：

```rust
pub struct DeviceId(pub [u8; 32]);
```

禁止以 IP、MAC、主机名或应用名生成 ID。

## 2. Ability 标识

```rust
pub struct AbilityKey {
    pub namespace: Namespace,
    pub name: AbilityName,
    pub major: u32,
}

pub struct AbilityContractRef {
    pub key: AbilityKey,
    pub protocol_hash: [u8; 32],
}
```

规范：

- `namespace` 采用反向域名或组织命名空间，例如 `com.mocharealm`；
- `name` 采用小写点分段，例如 `media.audio.render`；
- `major` 表示线协议不兼容版本；
- 次版本能力通过 feature 与 schema 演化表达；
- `protocol_hash` 是规范化契约文件的 SHA-256，用于阻止同名不同义实现连接。

## 3. Role 与 Port

Role 只表示 Ability 定义的参与者角色，Hub 不解释其含义。

```rust
pub struct RoleId(pub String);

pub struct RoleDeclaration {
    pub id: RoleId,
    pub min_instances: u16,
    pub max_instances: Option<u16>,
    pub ports: Vec<PortDeclaration>,
}
```

Port 是会话图中的端点：

```rust
pub enum PortDirection {
    Send,
    Receive,
    Bidirectional,
}

pub enum PortMode {
    ReliableMessages,
    ReliableStream,
    Datagram,
}

pub struct PortDeclaration {
    pub id: String,
    pub direction: PortDirection,
    pub mode: PortMode,
    pub schema: Option<SchemaId>,
    pub required: bool,
    pub multiplicity: PortMultiplicity,
}
```

`schema` 只用于兼容检查和 SDK 生成，Router 不解析 payload。

## 4. Offer

```rust
pub struct AbilityOffer {
    pub instance_id: AbilityInstanceId,
    pub contract: AbilityContractRef,
    pub app: AppPrincipal,
    pub roles: Vec<RoleId>,
    pub properties: PropertyMap,
    pub visibility: OfferVisibility,
    pub access_policy: PolicyRef,
    pub lease: LeaseSpec,
}
```

`properties` 必须是受限类型：

```rust
pub enum PropertyValue {
    Bool(bool),
    I64(i64),
    U64(u64),
    String(String),
    Bytes(Vec<u8>),
    StringSet(BTreeSet<String>),
    U64Set(BTreeSet<u64>),
}
```

禁止任意 JSON 作为核心匹配格式，因为它难以做稳定规范化、比较和索引。

## 5. Requirement

```rust
pub struct AbilityRequirement {
    pub requirement_id: RequirementId,
    pub selector: AbilitySelector,
    pub desired_role: RoleId,
    pub property_predicate: PropertyPredicate,
    pub device_selector: DeviceSelector,
    pub session_policy: SessionPolicy,
    pub lease: LeaseSpec,
}
```

Matcher 只做粗筛：Ability key、版本、role、属性谓词、设备条件。业务层的复杂协商必须由 Ability 在 Session 提案阶段完成。

## 6. Participant

```rust
pub struct Participant {
    pub id: ParticipantId,
    pub device_id: DeviceId,
    pub app_principal: AppPrincipal,
    pub ability_instance: AbilityInstanceId,
    pub role: RoleId,
    pub port_bindings: Vec<PortBinding>,
}
```

参与者是“某设备上的某应用，以某个 Offer 实例承担某个 Role”。不要把 Device 当成 Participant，因为一个设备上可能有多个 Ability 实例。

## 7. Session 图

```rust
pub struct SessionPlan {
    pub session_id: SessionId,
    pub contract: AbilityContractRef,
    pub epoch: u64,
    pub coordinator: ParticipantId,
    pub participants: Vec<Participant>,
    pub channels: Vec<ChannelBinding>,
    pub extensions: SessionExtensions,
    pub policy_snapshot: PolicySnapshotId,
}
```

```rust
pub struct ChannelBinding {
    pub id: ChannelId,
    pub sources: Vec<PortRef>,
    pub destinations: Vec<PortRef>,
    pub contract: ChannelContract,
}
```

一个 Session 中允许：

- 一个源到多个目标；
- 多个源到一个目标；
- 多个源到多个目标；
- 同一对 Participant 之间多个不同 Channel；
- 控制和数据采用不同模式。

## 8. ChannelContract

```rust
pub struct ChannelContract {
    pub mode: PortMode,
    pub delivery: DeliverySemantics,
    pub priority: u8,
    pub max_message_bytes: Option<u64>,
    pub max_buffered_bytes: u64,
    pub latency_target_ms: Option<u32>,
    pub idle_timeout_ms: Option<u64>,
    pub fanout: FanoutPolicy,
    pub payload_security: PayloadSecurity,
    pub timing: TimingPolicy,
}
```

约束：

- `priority` 0 最高，255 最低；
- `max_buffered_bytes` 必须有限，禁止无界队列；
- `ReliableMessages` 必须有 `max_message_bytes`；
- `Datagram` 的消息必须能放入协商后的最大 Datagram；框架不做自动分片；
- `EndToEnd` 时 Hub 只能读取固定外层头，不能读 payload。

## 9. Envelope

```rust
pub struct EnvelopeHeader {
    pub session_id: SessionId,
    pub epoch: u64,
    pub channel_id: ChannelId,
    pub sender: ParticipantId,
    pub sequence: Option<u64>,
    pub created_at: Option<GroupInstant>,
    pub deadline: Option<GroupInstant>,
    pub presentation_at: Option<GroupInstant>,
    pub flags: EnvelopeFlags,
}
```

可靠流不逐块套 Envelope；它在打开流时发送一次 `StreamOpen` 头，之后是 Ability 字节流。

## 10. 生命周期枚举

```rust
pub enum SessionState {
    Proposed,
    Negotiating,
    Preparing,
    Active,
    Reconfiguring,
    Suspended,
    Closing,
    Closed,
    Failed,
}
```

状态转换必须由 `session` 唯一控制，其他模块只能提交事件，禁止直接修改状态。

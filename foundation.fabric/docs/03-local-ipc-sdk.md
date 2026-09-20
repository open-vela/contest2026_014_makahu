# 03. 本地 IPC 与 Ability SDK

## 1. 目标

本地 IPC 必须：

- 验证应用 OS 身份；
- 支持请求/响应和服务端事件；
- 支持可靠消息、可靠字节流和 Datagram-like 消息；
- 对每个连接施加配额；
- App 进程退出后自动撤销租约；
- 不把远端网络细节暴露给 App。

## 2. 平台传输

建议：

- Linux/macOS：Unix Domain Socket；
- Windows：Named Pipe；
- Android：Binder 或受保护的 Unix Socket；
- iOS：应用间能力受平台限制，Hub 可能必须嵌入宿主 App、Extension 或使用系统允许的 IPC；平台 adapter 需单独实现。

核心 IPC 协议不依赖某个平台。

## 3. 握手

顺序固定：

```text
Client -> Hub: ClientHello(protocol versions, sdk version, requested features)
Hub: read OS peer credential
Hub -> Client: ServerHello(selected version, connection_id, limits, hub features)
Client -> Hub: BindApplication(optional declared metadata)
Hub: compare declaration with OS identity
Hub -> Client: ApplicationBound(AppPrincipal, granted base permissions)
```

应用声明的包名只能作为辅助字段；最终 `AppPrincipal` 由 Hub 构造。

## 4. 帧格式

```text
u32 big-endian frame_length
u8  frame_kind
u64 request_or_event_id
bytes protobuf_payload
```

约束：

- 默认最大控制帧 1 MiB；
- 解码前检查长度；
- 未知 `frame_kind` 返回 `UNSUPPORTED_MESSAGE`；
- 请求 ID 在单连接内唯一；
- 事件 ID 单调递增，用于诊断，不承诺持久重放。

## 5. SDK API

```rust
#[async_trait]
pub trait FabricClient: Send + Sync {
    async fn publish_offer(&self, offer: OfferDraft)
        -> Result<PublishedOffer, FabricError>;

    async fn register_requirement(&self, requirement: RequirementDraft)
        -> Result<RequirementHandle, FabricError>;

    async fn propose_session(&self, proposal: SessionProposal)
        -> Result<SessionHandle, FabricError>;

    fn invitations(&self) -> Pin<Box<dyn Stream<Item = SessionInvitation> + Send>>;

    async fn accept_invitation(
        &self,
        invitation: InvitationId,
        acceptance: SessionAcceptance,
    ) -> Result<SessionHandle, FabricError>;
}
```

### Offer handle

```rust
pub trait PublishedOffer {
    fn instance_id(&self) -> AbilityInstanceId;
    async fn update_properties(&self, patch: PropertyPatch) -> Result<(), FabricError>;
    async fn renew(&self) -> Result<(), FabricError>;
    async fn withdraw(self) -> Result<(), FabricError>;
    fn incoming_sessions(&self) -> SessionInvitationStream;
}
```

### Requirement handle

```rust
pub trait RequirementHandle {
    fn id(&self) -> RequirementId;
    fn candidates(&self) -> CandidateStream;
    async fn update(&self, patch: RequirementPatch) -> Result<(), FabricError>;
    async fn cancel(self) -> Result<(), FabricError>;
}
```

### Session handle

```rust
pub trait SessionHandle {
    fn id(&self) -> SessionId;
    fn epoch(&self) -> u64;
    fn state(&self) -> SessionStateStream;
    fn participants(&self) -> ParticipantSnapshot;
    fn clock(&self) -> Option<Arc<dyn SessionClock>>;

    async fn open_message_port(&self, port: &str) -> Result<MessagePort, FabricError>;
    async fn open_stream_port(&self, port: &str) -> Result<StreamPort, FabricError>;
    async fn open_datagram_port(&self, port: &str) -> Result<DatagramPort, FabricError>;

    async fn request_reconfiguration(
        &self,
        change: SessionChange,
    ) -> Result<u64, FabricError>;

    async fn close(self, reason: CloseReason) -> Result<(), FabricError>;
}
```

## 6. 本地数据通道

### ReliableMessages

- SDK `send(Bytes)`；
- 消息大小不得超过 Channel limit；
- Hub 返回已进入本地有界队列，不代表远端已消费；
- 可选 delivery receipt 必须由 Ability 协议实现。

### ReliableStream

- SDK 返回 AsyncRead/AsyncWrite；
- 打开时固定 Session、epoch、Channel、sender、destination set；
- Session reconfiguration 后旧流可以 drain 或被 reset，行为由 Channel policy 指定。
- Desktop 本地数据面使用独立本地字节流连接，控制连接只分配 stream id；
- Android 本地数据面使用 `ParcelFileDescriptor` pipe，AIDL 只传递 fd 和生命周期控制，不承载大块 payload；
- Hub 将本地字节流与远端 QUIC bidirectional stream 做双向 copy。

Android `IFabricHub.openStream(deviceId, ability, streamName)` 返回两个 fd：index 0
用于读取远端写入的数据，index 1 用于写入要发往远端的数据。远端设备打开本机
provider stream 时，Hub 通过
`IFabricAbility.onStreamOpen(requestId, ability, streamName, readFromRemote, writeToRemote)`
交付同样方向的 fd。跨设备音频使用这个路径传递 `media` stream，Binder payload
只用于控制命令和小型反馈。

Android `IFabricHub.devices()` 返回信任设备行，固定格式为
`id|state|online|label`。本机 `label` 优先使用 Android 系统设备名称，拿不到时退回
平台/型号；远端 `label` 由 discovery advertisement 在配对成功后写入 Hub 的
`device-labels.txt`。App 必须直接展示 Hub 返回的 `label`，不再自行拼接短设备 id。

### Datagram

- SDK `try_send` 优先，禁止无限等待；
- 队列满时按 Channel policy 丢弃 newest/oldest；
- 发送结果只表示本地接受，不表示网络到达。

## 7. 租约

默认 App 注册项与 IPC 连接绑定。持久注册必须显式声明：

```rust
pub enum LeaseSpec {
    ConnectionBound,
    Renewable { ttl_ms: u64 },
}
```

持久注册恢复条件：

- 相同 `AppPrincipal`；
- 提供 Hub 签发的恢复 token；
- token 未过期且未撤销；
- Offer contract 未改变。

## 8. 配额

Hub 必须按 AppPrincipal 限制：

- Offer 数量；
- Requirement 数量；
- Session 数量；
- 并发流数；
- 每秒控制请求；
- 总缓冲字节；
- Datagram 速率。

超限返回稳定错误，不得拖垮 Hub。

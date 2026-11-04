# 04. 发现、链路与设备身份

## 1. 分层

发现只产生候选；安全握手确认身份；Registry 同步在认证之后进行。

```text
DiscoveryBackend -> PeerCandidate -> LinkDialer -> AuthenticatedLink -> RegistrySync
```

## 2. PeerCandidate

```rust
pub struct PeerCandidate {
    pub discovery_key: DiscoveryKey,
    pub label: String,
    pub connection_hints: Vec<ConnectionHint>,
    pub device_hint: Option<[u8; 16]>,
    pub proximity: Option<ProximityEvidence>,
    pub sources: Vec<DiscoverySource>,
    pub expires_at_ms: u64,
}
```

`device_hint` 必须是不可逆、可轮换的截断提示，不是完整 DeviceId。
`label` 是用户可见设备名，只能来自平台设备名或用户设置名，不得包含账号、能力、
媒体状态或完整 DeviceId。Hub 在 discovery 配对成功后把 `DeviceId -> label` 写入
本地 `device-labels.txt`，App 通过 Hub 设备列表读取，不自行拼短 ID。

## 3. mDNS/DNS-SD

服务类型：

```text
_device-fabric._udp.local.
```

TXT 只允许：

```text
pv=1                 # discovery advertisement version
port=44330           # QUIC port
hint=<base32>         # rotating device hint
label=<device name>   # 用户可见设备名，最多 64 字节
pair=0|1              # 是否接受配对请求
features=q,b          # 极小的 transport feature 位图
```

禁止发布：

- Ability 名称或完整列表；
- 应用列表；
- 用户账号；
- 完整 DeviceId、公钥或证书；
- 文件、剪贴板、媒体状态。

发现实现必须处理：

- 多网卡和地址变化；
- IPv4/IPv6；
- 服务 add/update/remove；
- 休眠与唤醒；
- TXT 记录大小限制；
- 结果 TTL 过期。

## 4. BLE

BLE 只承担：

- 近场候选发现；
- 用户选择附近设备；
- 交换短配对 nonce；
- 可选传递当前局域网连接提示；
- 物理接近证据。

BLE 不承担：

- 完整 Ability 交换；
- 大文件传输；
- 音视频数据面；
- `ReliableStream` 或 `Datagram` 数据面。

配对完成后，平台可选择为已授权 Session 的 `ReliableMessages` Channel 提供可选 BLE 承载。
消息仍是应用自己的 Channel payload；不得放入配对、Session 控制或其他框架特权控制消息。
该承载复用已配对的 DeviceId、信任状态和 Channel 权限，不创建新的 provisioning 身份或
OOBE 协议。只有双方能力和策略都允许时才选择 BLE；QUIC 可用时优先选择 QUIC。应用可在
网络配置后重连 QUIC 并继续同一应用协议，应用负责用自身请求 ID/状态恢复未确认操作。

`crates/platform` 中的 BLE 分片引擎要求平台 `BlePacketIo` 绑定既有对端身份，并提供机密性和
完整性保护；缺少真实数据适配器的平台必须返回 `UnsupportedCapability`。`ReliableMessages`
统一接口另有 `QuicMessageTransport`，它使用带 Session/epoch/channel 标记的 `FabricLink` 双向
流；接收侧可用 `is_reliable_message_stream(&StreamOpen)` 识别该标记，再调用 `accept(...)` 交给
授权的消息路由。当前 Hub 尚未完成
这段分派和 SDK 接线。内存测试适配器只验证协议状态机，不代表射频适配已通过。当前 Android
BLE bridge 仍只有发现/配对能力；Vela 的 NuttX C 应用也没有 `std`/Tokio 执行器适配，因此这两
个目标目前均不可用作真实 BLE 消息承载。

消息承载只在已有有效的 committed SessionPlan/Channel 授权后可用；它不会自行创建 Session。
当前 BLE 发现/配对接口没有 Session Propose/Accept/Prepare/Commit 控制通路，Hub 也没有将这些
通用控制事件经 BLE 路由。因此，即使应用 payload 使用 ReliableMessages 且不放入控制帧，当前
实现仍不能在无 Wi-Fi 状态下完成首次 Session 授权和 OOBE 配置；这段通用控制面接线仍待实现。

Vela 侧的适配路线是由 NuttX C 继续拥有 BLE 回调和调度器，将消息分片/重组状态机抽到可在
`no_std + alloc` 下运行的核心，再提供明确的 C ABI 供 NuttX worker 投递收包、取出待发包及
上报完成/超时；不能把当前 Tokio crate 直接链接进 NuttX，也不能用 Linux mock 代替设备适配。
这条路线尚未实现，ABI、NuttX BLE packet MTU 和设备端认证加密回调仍需单独落地并实测；在此
之前 Vela 的 BLE `ReliableMessages` 必须保持 Unsupported。

抽象接口：

```rust
#[async_trait]
pub trait DiscoveryBackend {
    async fn start(&self, sink: Arc<dyn CandidateSink>) -> Result<(), DiscoveryError>;
    async fn stop(&self) -> Result<(), DiscoveryError>;
}

#[async_trait]
pub trait ProximityExchange {
    async fn advertise_pairing(&self, offer: PairingAdvertisement) -> Result<(), DiscoveryError>;
    async fn exchange(&self, peer: DiscoveryKey, request: PairingRequest)
        -> Result<PairingResponse, DiscoveryError>;
}
```

跨平台 BLE peripheral 能力不一致，因此 `discovery-ble` 必须允许 platform adapter，而不是假设一个 crate 覆盖所有广播和 GATT server 行为。Android 的 BLE 广播与扫描由 JNI bridge 调用系统 `BluetoothLeAdvertiser`/`BluetoothLeScanner`，Rust crate 仍负责 payload 编解码和候选合并。

## 5. Wi-Fi Aware

Wi-Fi Aware 只承担本地近邻候选发现，service specific info 只编码 rotating hint 和可选局域网连接提示。它不承载 Ability 清单、Session 数据面或长期身份。

`discovery-wifi-aware` 与 BLE 一样保留平台 adapter。Android 通过 JNI bridge 调用 `WifiAwareManager.publish/subscribe`；Windows 当前没有公开 Wi-Fi Aware/NAN API，desktop hub 必须把该能力视为不可用，而不是改用 Wi-Fi Direct。

## 6. QUIC 链路

每对 Device 原则上只保留一个逻辑 Link，可承载多个 Session。

链路提供：

```rust
#[async_trait]
pub trait FabricLink {
    fn peer(&self) -> DeviceId;
    fn state(&self) -> LinkStateStream;
    async fn send_control(&self, msg: ControlEnvelope) -> Result<(), LinkError>;
    async fn open_uni(&self, header: StreamOpen) -> Result<SendStream, LinkError>;
    async fn open_bi(&self, header: StreamOpen) -> Result<BiStream, LinkError>;
    fn try_send_datagram(&self, datagram: Bytes) -> Result<(), LinkError>;
    async fn close(&self, reason: LinkCloseReason);
}
```

## 6. Link 握手

TLS/QUIC 完成后仍执行应用协议握手：

```text
A -> B: HubHello(protocol versions, device proof, nonce A, feature set)
B -> A: HubHello(protocol versions, device proof, nonce B, feature set)
双方：验证 DeviceId、公钥绑定、信任状态、重放
双方：选择 protocol version 和 limits
双方：交换 LinkReady(transcript hash)
```

首次未配对设备进入 `Untrusted`，只能使用配对控制消息。不得同步 Offer。

## 7. 双连接仲裁

两设备可能同时拨号。固定规则：

1. 比较 `DeviceId` 字节序；
2. 较小 DeviceId 负责保留 outbound，较大 DeviceId 保留 inbound；
3. 如连接代际不同，优先握手完成且 path quality 更高者；
4. 关闭重复连接前迁移未绑定的新请求；已绑定流不跨连接迁移。

此规则必须确定性一致，否则会连接抖动。

## 8. 路径与恢复

- Link 可以经历多 IP 地址；
- QUIC path migration 可用时由 transport adapter 管理；
- Link 断开时 Session 进入 `Suspended`；
- 重连后先验证 DeviceId，再比较 Session resume token 和 epoch；
- 不匹配则完整重新协商，不能直接继续旧数据流。


### Portable BLE profile 实际平台接线

`platform/portable` 提供无 std/Tokio、无 Rust allocator 的 C ABI，以及共享的 GATT packet host、
首次配对 host 和框架身份存储后端。`FBP1` 签名 hello 显式协商 profile、仅 ReliableMessages
能力和 max application bytes；应用消息支持 1..12288 字节，超限拒绝、不截断。初始 Session
固定 epoch 1，两个 participants、单个 bidirectional channel，无 stream/datagram/E2EE、选举
或跨 profile resume。此 wire 与 desktop protobuf Session / 早期 Rust BLE prototype 尚不互通。

首次配对沿用 request/Challenge/用户确认/Complete 语义，nonce commitment 与加密 reveal 后才
生成 SAS；双端真实确认后才持久化原 Fabric Ed25519/DeviceId 信任。QR/GATT public identity
只是 locator，BLE bond 与应用旧 token 不提供框架权限。`trusted-devices.txt` 与现有 Hub 同源，
public cache 不能独立授予信任；NuttX 使用带真实属性与 flush 的 LittleFS，拒绝 synthetic 0777
的 semihost hostfs。C host 与 bare-metal ARM64 archive 已按实际 NuttX flags 编译/链接。

`platform/android` 的真实 GATT central + JNI 已构建 AAR；Android 原生 ELF fixture 实际通过
首次配对和 12KiB 消息状态机，生产 Kotlin/JNI 也有 JVM 回归。它们都不是射频验证。
Android 跨 UID 应用必须调用 `fabric-sdk` 的 `openBleMessages`，由现有 FabricHubService 持有
唯一身份、GATT 与 Session，不得在应用 filesDir 另造 authority。新增 Binder owner/ability/
permission/lease 和死亡清理接线正在实装验证；安装与真实 Bluetooth controller 结果须独立记录。

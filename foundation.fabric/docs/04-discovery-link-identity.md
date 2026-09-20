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
- 长期 Session 数据面。

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

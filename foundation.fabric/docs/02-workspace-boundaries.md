# 02. Rust Workspace 与模块边界

## 1. Workspace

```text
crates/
  core
  protocol
  registry
  matcher
  session
  router
  link
  transport-quic
  ipc
  discovery
  discovery-mdns
  discovery-ble
  identity
  policy
  crypto
  clock
  storage
  sdk
  hub
```

## 2. 每个 crate 的唯一职责

### `core`

纯领域类型、ID、错误类别、traits。禁止依赖异步运行时和 I/O。

### `protocol`

Protobuf 生成代码、wire/domain 转换、长度限制、协议版本。禁止包含业务状态机。

### `registry`

本地/远端 Offer、Requirement、租约、delta revision。禁止建立网络连接。

### `matcher`

确定性粗匹配。输入快照，输出候选列表和拒绝原因。禁止访问数据库或网络。

### `session`

Session 状态机、epoch、成员变更、提案、接受、提交、暂停和关闭。

### `router`

Port/Channel 绑定、源到目标路由、背压、优先级、公平性、统计。payload 必须保持不透明。

### `link`

定义 Hub-to-Hub 抽象：控制消息、可靠流、Datagram、链路事件和路径信息。

### `transport-quic`

用 Quinn 实现 `link`。负责 QUIC endpoint、TLS、连接复用、流映射、Datagram 和链路 keepalive。

### `ipc`

本地 App-to-Hub 协议服务端、OS peer credential、帧编解码、连接租约。

### `discovery`

统一 `DiscoveryBackend` trait、候选合并、去重、过期。

### `discovery-mdns`

只实现 mDNS/DNS-SD 广播与浏览。

### `discovery-ble`

只实现 BLE 发现、近场确认和配对数据交换；平台能力不足时提供 platform adapter trait。Android 通过 JNI bridge 调用 Framework BLE API，Windows 通过 WinRT API 实现。

### `discovery-wifi-aware`

只实现 Wi-Fi Aware/NAN 候选发现；Android 通过 JNI bridge 调用 `WifiAwareManager`，桌面平台没有可用 Wi-Fi Aware API 时必须返回 `UnsupportedCapability`，不能用 Wi-Fi Direct 伪装。

### `identity`

设备长期密钥、DeviceId、证书、设备证明、远端 App attestation 的签发与验证。

### `policy`

ACL、用户授权、PolicySnapshot、capability grant、权限解释。

### `crypto`

Ability E2EE、group key epoch、nonce/replay window。禁止管理设备 TLS 身份。

### `clock`

ClockDomain、四时间戳测量、滤波、漂移估计、Barrier。

### `storage`

SQLite repository、迁移、事务、崩溃恢复标记。禁止包含业务决策。

### `sdk`

App 使用的客户端 API、事件流、SessionHandle、ChannelHandle。不得暴露内部 Hub 数据库模型。

### `hub`

组装上述组件、启动服务、配置、生命周期管理。只做 composition root。

## 3. 依赖方向

```text
core
  ↑
protocol, registry, matcher, link, identity, policy, crypto, clock
  ↑
session, router, storage, ipc, transport-quic, discovery-*
  ↑
sdk, hub
```

必须通过 CI 检查禁止依赖：

```text
core -> tokio/quinn/sqlx/mdns/platform discovery APIs
router -> any ability crate
hub -> foundation.audio
protocol -> storage
identity -> policy
clock -> audio/video crate
```

## 4. Feature flags

平台和可选后端使用 feature：

```toml
[features]
default = ["quic", "mdns"]
quic = ["dep:transport-quic"]
mdns = ["dep:discovery-mdns"]
ble = ["dep:discovery-ble"]
clock = ["dep:clock"]
e2ee = ["dep:crypto"]
```

Feature 只能控制后端是否编译，不能改变领域模型或 wire tag。

## 5. 错误处理

每个 crate 定义本地错误，但跨 IPC/Link 的错误必须转换为稳定 `ProtocolErrorCode`。禁止把 `anyhow::Error` 作为库公开 API。二进制入口可使用 `anyhow` 聚合启动错误。

## 6. 并发规则

- 单个 Session 的控制状态由一个 actor/task 串行处理；
- Registry 使用 revision + snapshot，不让 Matcher 长期持锁；
- Router 每个 Link 有独立写队列，按 Channel priority 做加权公平调度；
- 不允许在音频/媒体实时线程直接调用 Hub IPC；
- 所有队列必须有容量、溢出策略和指标。

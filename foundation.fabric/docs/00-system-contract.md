# 00. 系统契约

## 1. 目标

Device Fabric 是每台设备上的本地 Hub 与多个应用共同组成的跨设备能力总线。它必须允许：

- 本地应用向本机 Hub 发布 Ability Offer；
- 本地应用向本机 Hub 注册 Ability Requirement；
- Hub 通过局域网或近场发现其他 Hub；
- Hub 建立经过设备认证的安全链路；
- Hub 在授权后交换可见的 Offer 摘要；
- Hub 匹配 Requirement 与 Offer，并创建 Session；
- Session 支持一对一、一对多、多对一和多对多；
- Session 内可同时存在多个不同方向、不同可靠性和不同安全要求的 Channel；
- Ability 可选使用公共时钟、同步屏障、端到端加密、远端应用认证和组成员重配置；
- 任意业务 Ability 能在框架之外实现。

## 2. 非目标

核心框架禁止承担以下职责：

- 文件目录扫描、分块、哈希、冲突合并；
- 剪贴板 MIME 解析、历史管理、冲突规则；
- 密钥业务格式、密钥库导入导出；
- 音频/视频解码、播放、重采样、抖动缓冲；
- 业务状态的 CRDT、数据库或领域模型；
- 云端账号系统、互联网中继和 NAT 穿透的具体产品逻辑；
- 通用插件运行时或把不可信代码加载进 Hub 进程。

这些内容必须位于 Ability 应用或专用 adapter 中。

## 3. 信任边界

系统有四个独立边界：

```text
[App Process] --local authenticated IPC--> [Local Hub]
[Local Hub] --authenticated encrypted link--> [Remote Hub]
[Remote Hub] --local authenticated IPC--> [Remote App]
[Ability payload] --optional E2EE------------------------>
```

必须分别解决：

1. 本地应用是谁；
2. 远端设备是谁；
3. 远端 Hub 声明的应用身份是否满足当前 Ability；
4. Hub 是否允许读取 Ability payload。

不得把“QUIC 已加密”等同于“应用端到端加密”。

## 4. 核心不变量

### 4.1 身份不变量

- `DeviceId` 必须从设备长期公钥派生或与其强绑定。
- `AppPrincipal` 必须来自本地 OS 凭据验证。
- 远端应用身份必须由其本地 Hub 签名证明。
- 网络地址、蓝牙地址、mDNS 实例名不能作为稳定身份。

### 4.2 Session 不变量

- 每个 Session 有唯一 `SessionId`。
- 每次成员、权限、密钥或通道图改变，`epoch` 必须递增。
- 所有控制消息必须携带 `session_id` 和期望 `epoch`。
- 旧 epoch 的数据必须被丢弃或显式映射，不能静默接受。
- Coordinator 不拥有业务数据语义，只协调控制面。

### 4.3 Channel 不变量

- 每个 Channel 必须声明模式、安全级别、源端口和目标端口。
- 可靠消息必须保留消息边界并有大小上限。
- 可靠流不得假设应用消息边界。
- Datagram 必须允许丢失、乱序和重复；Ability 自行处理这些情况。
- Router 不得解析 Ability payload。

### 4.4 生命周期不变量

- App IPC 断开后，由该 App 注册的临时 Offer 和 Requirement 必须撤销。
- App 可使用可恢复租约注册持久 Offer，但重连后必须重新证明身份并续租。
- Hub 崩溃恢复后不得自动恢复 E2EE 会话密钥，除非密钥明确设计为可持久化。
- 远端 Link 断开不等于立即销毁 Session；Session 应进入 `Suspended` 并按策略等待恢复。

## 5. 一条完整路径

```text
App A -> Local Hub A: PublishOffer
App B -> Local Hub B: RegisterRequirement
Hub A/B: mDNS 或 BLE 发现候选
Hub A/B: QUIC + 设备身份握手
Hub A/B: 交换授权后的 Offer 摘要
Hub B: Matcher 生成候选
App B: 选择候选并提出 Session
Hub A/B: 策略检查、远端应用证明、Session 协商
参与 App: Accept
Coordinator: Commit SessionPlan(epoch=1)
各 Hub: 打开绑定后的 Channel
各 App: 发送/接收不透明 Ability payload
```

任何步骤失败都必须返回结构化错误，不能用日志代替协议错误。

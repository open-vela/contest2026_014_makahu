# 14. 技术依据与设计理由

## 1. QUIC 作为 Hub-to-Hub 传输

选择 QUIC 的理由：

- 一条连接内支持多个独立可靠流，减少跨业务 head-of-line blocking；
- 提供双向和单向流；
- 原生 TLS 加密；
- QUIC Datagram 可承载无需自动重传的低延迟消息；
- Connection 可作为多个 Session 的复用 Link。

官方/实现资料：

- RFC 9000: https://www.rfc-editor.org/rfc/rfc9000
- RFC 9221: https://www.rfc-editor.org/rfc/rfc9221
- Quinn docs: https://docs.rs/quinn/latest/quinn/

注意：QUIC Datagram 可能丢失、乱序，且必须小于协商/路径允许的单包大小。因此框架明确不对 Datagram 承诺可靠性，也不自动分片。

## 2. mDNS/DNS-SD 只做局域网 Hub 发现

mDNS/DNS-SD 适用于无中心 DNS 的本地链路服务发现。框架使用单一 Hub 服务类型，不将 Ability 清单编码进广告，从而降低隐私泄露与广告膨胀。

资料：

- RFC 6762 mDNS: https://www.rfc-editor.org/rfc/rfc6762
- RFC 6763 DNS-SD: https://www.rfc-editor.org/rfc/rfc6763
- mdns-sd: https://docs.rs/mdns-sd/latest/mdns_sd/

`mdns-sd` 提供服务注册和浏览，且不强绑定异步运行时，适合作为一个后端；核心仍保持 `DiscoveryBackend` 抽象。

## 3. BLE 仅作为近场发现和配对辅助

跨平台 BLE API 对 central/peripheral、广播、GATT server 等能力支持不完全一致。因此不能让框架核心假设单一 Rust crate 在所有平台同时提供完整能力。

资料：

- BlueR: https://github.com/bluez/bluer

Linux 上 BlueR 是 BlueZ 官方 Rust 接口，并覆盖本地 GATT 发布。当前实现不使用跨平台 BLE 后端：Android 由 JNI 调用 Framework API，Windows 由 Rust 直接调用 WinRT API。最终必须保留 platform adapter。

## 4. Wi-Fi Aware 只作为 Android 近邻发现后端

Wi-Fi Aware/NAN 的 Android API 位于 Java Framework `WifiAwareManager`，不是 NDK C API。Windows 当前没有公开 Wi-Fi Aware/NAN API；Wi-Fi Direct 是不同能力，不能替代 Wi-Fi Aware。

资料：

- Android Wi-Fi Aware: https://developer.android.com/develop/connectivity/wifi/wifi-aware
- Android `WifiAwareManager`: https://developer.android.com/reference/android/net/wifi/aware/WifiAwareManager
- Windows Wi-Fi Direct: https://learn.microsoft.com/windows/uwp/devices-sensors/wifi-direct
- Windows Bluetooth LE advertisements: https://learn.microsoft.com/uwp/api/windows.devices.bluetooth.advertisement

## 5. 公共时钟使用四时间戳测量

NTPv4 的 on-wire 模型使用四个时间戳估计 offset 与 delay。框架借用测量模型，但只建立 Session 内单调时钟映射，不调整系统 wall clock。

资料：

- RFC 5905: https://www.rfc-editor.org/rfc/rfc5905

同步播放仍需 Ability 处理音频硬件时钟、输出延迟和重采样；公共时钟只提供设备间时间坐标。

## 5. 为什么核心只提供三种数据原语

业务需求可分解为：

- 需要消息边界、可靠、有序：ReliableMessages；
- 需要连续可靠字节与背压：ReliableStream；
- 需要低延迟并可容忍丢失：Datagram。

文件、媒体、状态、剪贴板的差异主要是 payload 语义和业务状态机，不需要为每种业务在 Hub 增加新传输类别。

## 6. 为什么 Session 使用图而不是 Topology 枚举

一个音频 Session 同时含：source->renderers 的媒体 Channel、renderers->coordinator 的反馈 Channel、controller<->participants 的控制 Channel。单一 `OneToMany` 无法表达整个会话。图模型还能自然表达多源聚合、不同 Port 和多个独立通道。

## 7. 为什么 Provider/Receiver 不作为运行时角色

Provider/Requirement 适合注册和发现，但双向同步、会议、复制等能力中，参与者往往同时发送和接收。运行时改用 Ability 自定义 Role 和 Port，避免框架内置业务语义。

## 8. 为什么设备链路加密不能可选

即使 Ability payload 不敏感，控制面仍包含设备关系、应用证明、能力摘要、权限和 Session 元数据。允许明文链路会使身份认证和策略失去基础。因此链路认证加密是系统不变量；只有 payload E2EE 是可协商项。

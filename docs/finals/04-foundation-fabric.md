# Foundation Fabric：框架现状、BLE 可靠消息与 Coordinator 选举

日期：2026-10-17。状态：通用 Hub 的 Coordinator 选举已接入 Rust 生产运行路径，并通过 host 三成员真实 QUIC 集成测试；Android Hub APK 的 Gradle/JNI/NDK 三 ABI 全量构建已通过。主线程报告已用 `adb install -r` 更新 Android original QEMU（`emulator-5582`，Android 17，PID 29204）中的 APK；新 Hub 正在该 AVD 运行（PID 11069），已实际完成认证 BLE coordinator term 56、pf17 手机 ASR 分类及回传。尚未验证的是三台或四台 Android 设备的 BLE mesh，不是新 APK 是否在 QEMU 运行。

## 1. 独立开发仓库

框架开发位置：**`/home/simon/projects/mocha/foundation.fabric`**，即 `~/projects/mocha/foundation.fabric`。

远端：[6xingyv/foundation.fabric](https://github.com/6xingyv/foundation.fabric.git)。参赛项目内的 `/home/simon/vela/contest2026_014_makahu/foundation.fabric/` 是源码快照。框架变更在独立仓库实现和验证，随后按需同步快照。下文源码路径均相对于独立仓库；相对文件链接仅用于阅读参赛快照。

应用文档：[Coordinator 自动选举](02-coordinator-election.md)、[Vela Agent 与 OOBE](03-vela-agent-capabilities.md)。

## 2. 范围与已确认现状

| 范围 | 当前实现与验证 | 验收边界 |
| --- | --- | --- |
| Hub Coordinator 选举 | QUIC Hub 控制路径已接入心跳超时、候选竞争、持久化投票、多数证书、任期 fencing 和 epoch+1 Session 重配置 | 三成员 host QUIC 集成通过；不是三台或四台实体设备测试 |
| 持久化 | 投票状态与选举证书和 Session checkpoint 一起持久化；重启恢复时校验提交状态 | SQLite 持久化由 Rust 单测及 host 集成路径验证 |
| Portable BLE | `ReliableMessages` 有分片、确认、重试、去重、流控及授权 Channel 绑定 | 当前 BLE 测试边界为两端、单个 ReliableMessages Channel、epoch 1；未验证 BLE 多成员选举或全互连 mesh |
| Android 运行验收 | 主线程报告 Android 17 QEMU/AVD（`emulator-5582`）中的新 Hub 已通过认证 BLE coordinator term 56，并完成 pf17 手机 ASR 分类和回传 | 这是已运行的新 APK 两成员业务验收；不能据此声称完成三台或四台 Android 设备 mesh |
| Android 交付 | Hub APK 的 Gradle/JNI/NDK 三 ABI 全量构建通过；主线程报告已通过 `adb install -r` 更新 original QEMU，Hub PID 11069 正在运行 | QEMU 为 Android 17 的 `emulator-5582`（PID 29204）；仍缺多 Android 设备 mesh 验收 |

最近一次 Rust 验证统计：`cargo test -p fabric-hub` 为 12 项单测和 2 项集成测试，共 14 项通过；`cargo test -p fabric-protocol -p fabric-session -p fabric-storage` 分别为 21、10、7 项，共 38 项通过；这两组共 52 项通过。`cargo check -p fabric-hub` 通过，`cargo check -p fabric-android-jni` 的宿主目标也通过。宿主检查不等同于 NDK ABI 构建；完整 Android 构建结果如下。

生产 APK 构建日志为 `/home/simon/.cache/buffer-vela/hub-production-election-full-build.log`：`compileRustAndroid` 分别构建 `arm64-v8a`、`armeabi-v7a`、`x86_64`，随后 `:hub-app:assembleDebug` 以 `BUILD SUCCESSFUL` 结束。日志中 Cargo cache 自动清理有权限警告，但没有阻止三 ABI 编译或 APK 打包。运行验收按主线程报告记录：通过 `adb install -r` 更新 Android original QEMU 后，新 Hub 已运行并完成上述 BLE Buffer 业务闭环。

三成员选举集成测试使用三个独立 Hub 身份、真实 QUIC loopback 连接和 SQLite 文件，覆盖已认证会话协商、原 Coordinator 断连、存活多数选举、term 1 证书、epoch 2 提交、旧 Coordinator 的旧任期 Prepare 拒绝，以及延迟旧哈希心跳不关闭新 epoch 链路。它是 host 上的网络集成测试，不是 BLE 测试，也不是实体设备测试。

本次不增加框架专用 OOBE/provisioning 协议。配对后的 Wi-Fi、LLM 配置继续属于应用协议。

## 3. Portable BLE：仅承载 ReliableMessages

实现范围：仅让 `ReliableMessages` 支持可选 BLE 承载。应用使用相同的可靠消息接口和配置协议，由 Fabric 选择可用承载；具体无 Wi-Fi 配网流程仍须由平台 adapter 和应用集成验证。

需要区分以下两层：

| 层次 | 现有类别 | 含义 |
| --- | --- | --- |
| 应用 PortMode | ReliableMessages | 有消息边界的可靠传递，适合配置、命令和回执 |
| 应用 PortMode | ReliableStream | 连续可靠字节流，适合文件和音频数据 |
| 应用 PortMode | Datagram | 不可靠数据报，适合允许丢失的实时数据 |
| 底层 Link 实现 | QUIC | 实际网络传输 |
| 底层 Link 实现 | MemoryLink | 内存测试及故障模拟，不是跨设备无线传输 |
| 消息承载扩展（已实现） | ReliableMessages over BLE | Portable 分片、确认、重试和授权 Channel 校验；不增加蓝牙流或数据报模式 |

来源：[PortMode 定义](../../foundation.fabric/docs/01-domain-model.md)、[FabricLink 与 MemoryLink](../../foundation.fabric/crates/link/src/lib.rs)。mDNS、BLE discovery 和 Wi-Fi Aware discovery 属于发现机制，不能与上述三种消息模式混为一谈。

`crates/platform` 已实现面向 `ReliableMessages` 的 portable BLE 消息承载，包括分片重组、确认与重试、重复抑制、队列和大小限制。每个传输实例绑定一个 `AuthorizedMessageChannel`，并核对该 Channel 的 peer DeviceId。具体平台的 `BlePacketIo` 仍须提供已配对身份绑定的机密性与完整性；没有实际 GATT adapter 的平台明确返回 Unsupported。

BLE 只支持 `ReliableMessages`，不提供 `ReliableStream` 或 `Datagram`。载体选择在 QUIC 与 BLE 之间只对可靠消息生效；需要流或数据报的会话不能因 BLE 可用而被误判为满足能力。业务消息继续遵守已授权 Session Channel，GATT 写入成功也不代表应用已执行，应用结果仍由应用协议确认。

当前 portable BLE 覆盖限于两端之间的单个授权 ReliableMessages Channel；测试计划使用 epoch 1 的 SessionPlan。它没有验证同一 BLE Hub Session 的多成员选举、超过两端的端到端拓扑、Coordinator 任期接管，或 epoch 变更后的 BLE Channel 重建。Hub 的生产选举目前运行在已认证 QUIC Hub 控制路径上，不应把 host QUIC 选举结果外推为 BLE 选举已完成。

这项扩展保持应用协议不变：Fabric 配对 → 经 BLE 发送应用 Wi-Fi 配置 → 联网 → 优先经 QUIC 发送后续应用配置。模型配置也可以在 BLE 消息预算内传输，不将“必须切到 QUIC”写成协议限制。


现有 portable BLE 测试使用成对的内存 PacketIo 夹具，验证单 Channel 的分片、丢包重试、重复包处理、边界与授权检查；这些结果是传输单测，不代表真实 GATT、Windows 多设备网状连接或 Hub election over BLE 已通过。完整设备载体仍需按平台独立验收。


## 4. 多对多消息与会话拓扑

Channel 的 sources 和 destinations 都支持多个端点，Router 按 SessionPlan 分发消息。默认逐目标单播，逻辑多对多不要求应用自行创建完整的两两连接网。

独立仓库 `crates/router/src/lib.rs` 已有多对多及一对多路由测试。可靠消息仅保证单 Channel、单发送者有序，不保证不同发送者间全序；来源身份、去重、版本与选举轮次仍须由相应协议处理。

业务应用可在选举时使用多对多可靠消息，稳定后使用一对一任务请求和一对多状态发布。Hub host 集成测试已验证三个成员各自建立到另外两个成员的 QUIC 链接并完成 Coordinator 接管；这只验证 host QUIC 路径。Windows 实机 BLE 联调为两成员，Android 17 QEMU/AVD 也已完成新 APK 的两成员业务闭环；三台及以上 Android 设备的 BLE mesh 仍未验证。

## 5. 当前 Session epoch 如何工作

核对位置：独立仓库 `crates/session/src/lib.rs` 的 `SessionMachine`，以及 `crates/router/src/lib.rs`。现有机制如下：

1. 当前已提交 SessionPlan 为 epoch N；Active 状态重配置只接受 N+1，跳号或旧值返回 InvalidEpoch。
2. 保存 pending plan，清空 Ready 集合，进入 Reconfiguring，预留新计划资源。
3. Ready 必须来自 pending plan 的参与者，且 plan_hash 与当前 pending plan 一致。
4. Commit 再校验 plan_hash，并检查 pending plan 中所有参与者已 Ready，然后切换计划，发出旧 epoch 释放和持久化命令。
5. Router 拒绝 epoch 不匹配的数据；状态机在有界缓存内通过 operation_id 去重，同 ID 不同内容报错。该缓存不能当作永久业务幂等账本。

初建会话的 Prepare 使用初始计划 epoch；不是每次 Prepare 都额外加一。重配置才要求已提交 epoch 加一。

需要注意实现与文档边界：文档允许按契约处理 optional participant，但当前状态机 Commit 检查的是 pending plan 中全部参与者。应在形成计划时明确成员，不能直接声称代码已经支持缺席 optional participant 后照常提交。Hub 选举产生新授权后，状态机仍要求 epoch+1 计划的全部参与者 Ready 才能 Commit。

**epoch 是会话配置版本，不是 Coordinator 选举轮次。** 两个候选都提出 N+1 时，epoch 相同不代表计划相同；plan_hash 可识别不同计划，但不能选出谁应成为 Coordinator。

当前通用 Hub 选举已不止 primitives：QUIC Hub runtime 的周期 tick 驱动心跳及失联检测、候选退避、选举请求/投票/证书消息分发；投票在回复前写入持久存储，多数证书经签名校验后应用到 Session，Coordinator term 与 Session epoch 分别校验。新 Coordinator 通过 epoch+1 Prepare/Ready/Commit 完成计划变更，旧 term 的控制任务不能覆盖新计划。host 三成员验证边界见第 2 节；真实多设备部署仍未达到三成员或四成员实机验证。

## 6. Coordinator 选举轮次与 Hub 运行时

本节描述已实现的通用 Hub 选举路径及仍适用的安全边界。首版基于固定已提交成员集合做多数确认，确保同一会话中的 Coordinator 授权可验证；不同可用性策略不能隐式混用。

### 6.1 分清三个版本

| 字段 | 含义 |
| --- | --- |
| election_term | 本 Session 的 Coordinator 选举轮次 |
| session_epoch | 已提交的 SessionPlan 版本 |
| membership_hash | 固定已提交成员配置的哈希；当前没有独立递增的 membership_version |

候选发起新选举时使用本地持久化及已观察最大 term 加一。收到更高 term 时更新并持久化，退出旧候选/领导状态；旧 term 的状态变更控制被 fencing，迟到的旧心跳只忽略且不续租。相同 term 中出现不同候选时按投票规则仲裁，不能按最后收到的消息覆盖。Hub runtime 已把这些控制消息接入已认证 QUIC 控制流，并以周期 tick 驱动心跳和选举计时。

同 term 的投票只能授予一个候选，投票记录在答复前持久化，重启不允许再次投给另一人。消息绑定 SessionId、成员配置、发送者和请求 ID，防止跨会话、跨成员配置及重复消息混淆。选举证书编码随 Session checkpoint 一起持久化，恢复时校验成员配置、提交 epoch/hash 和投票签名。

### 6.2 选举过程

1. 当前 Coordinator 心跳超时后，参与者把失联视作怀疑并进入选举计时；明确链路断开可即时判定不可达。
2. 候选按稳定身份偏好并加入随机退避，减少同时竞争；排序只影响发起偏好，不替代投票。
3. 候选广播绑定 term、成员配置和最后提交计划 epoch/hash 的投票请求。
4. 接收者检查身份、成员资格、term 和计划兼容性；在答复前持久化单票。冲突或无法确认兼容时不以更大的数字自动覆盖。
5. 严格多数签名投票组成授权证书。接收者校验证书后应用更高 term， fencing 旧 Coordinator；仅凭候选自称当选不足以接受写入。
6. 新 Coordinator 提出已提交 epoch+1 并完成 Prepare/Ready/Commit。当选本身不修改路由，也不等于新计划已提交。

同一 term 依靠投票交集避免产生两份多数授权；更高 term 必须阻止旧 term 继续获得有效控制确认。Prepare、Ready、Commit 的网络封装都需绑定 term 与 plan_hash，并拒绝旧授权。只有 term 数字而没有授权校验与提交约束，仍不足以解决竞争。

### 6.3 故障恢复与成员变更边界

多数选举不等于完整的共识日志。当前持久化边界是 election term/vote 与最后已提交 Session checkpoint；选举证书随新 checkpoint 原子持久化。尚未提交的 pending plan 不视作 committed 状态，重启后不能把它当成已完成的 epoch。需要复制完整提交日志、跨设备提交确认传播或动态成员配置时，仍需另行定义协议；恢复信息无法确定一致时应进入 Suspended，不能继续猜测。

成员变化必须通过已授权、可验证的成员变更流程完成。不能先删除失联成员，再按剩余可见设备自行计算“多数”；首版可在无法按旧配置安全完成变更时暂停会话。未来增加动态成员能力时，应采用明确的过渡成员配置规则。

两台设备中任意一台失联，另一台无法单独取得严格多数。因此本方案不能同时承诺双设备分区时自动维持原 Session 和全局唯一 Coordinator。少数侧暂停原会话的协调操作，但本地录音、计时和任务排队继续。应用允许的离线计算及重连归并由应用协议处理。

如果产品要求单设备也继续建立独立工作域，应明确创建新的作用域，并在重连后归并；不能假装它仍持有原会话的唯一领导权。是否引入仲裁设备或选择更偏可用性的策略，应作为后续明确设计决定。

### 6.4 与应用 Coordinator 的关系

框架负责 Session 控制权，应用负责 LLM 任务调度。框架选举不硬编码“手机高于 Vela”的业务排序。两者使用各自轮次，不能让应用 coordinator_term 代替框架 election_term，也不能把 session_epoch 当作任务 fencing token。

应用选举需要可用通信，但框架失联恢复不能依赖尚未恢复的应用多对多 Channel。框架选举消息应使用现存已认证 Link 的框架控制路径，并补齐断线重建能力；应用业务消息仍必须走正式授权 Channel。

## 7. 当前验收与剩余边界

已完成的 host 验证包括：选举控制协议及任期绑定、持久投票和证书、Hub 三成员 QUIC 失联多数选举、epoch+1 重新提交、旧 term 拒绝，以及重试/延迟消息下的 fencing。第 2 节列出测试命令与精确数量。

Hub Gradle/JNI/NDK 三 ABI 全量构建已通过。主线程报告已通过 `adb install -r` 更新 Android original QEMU（`emulator-5582`，Android 17，PID 29204）；新 Hub PID 11069 正在运行，且已实际认证 BLE coordinator term 56、完成 pf17 手机 ASR 分类并回传。独立 framework 与项目快照源码比对未发现遗漏的 framework 源码修改；`crates/`、`protocol/`、`platform/portable/`、Android Fabric SDK、Hub app 和 JNI 源码一致。独立仓库有三 ABI JNI `.so` 构建产物而项目快照没有；Android Buffer 应用文件存在项目集成差异，不属于本次 framework 源码同步范围。

已有 Android 17 QEMU 上新 APK 的两成员 BLE 业务运行验收；尚未完成三台或四台 Android 设备的 Hub election mesh 验收。Portable BLE 当前仍是两成员、单授权 Channel、epoch 1 的可靠消息范围，不应标记为通用 Hub 选举承载或多成员 BLE mesh 已完成。

后续验证应分别记录：Android 17 QEMU 已完成的两成员 BLE coordinator 与 Buffer 处理闭环；两设备 BLE 可靠消息与断连恢复；三成员及以上 Android 设备的已认证 Hub 链路和 Coordinator 接管；以及 BLE Channel 在 epoch 变更后的重建行为。不得将 host loopback 结果表述为多 Android 设备实机 mesh 验收。

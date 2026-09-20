# 13. 依赖有序的完整实现说明

本文件不是路线图。所有条目共同构成最终系统。顺序只用于避免在依赖不存在时提前编写上层代码。

每个任务完成后必须先通过该任务验收，再进入下一任务。禁止跳过边界测试后“之后补”。

## T01：创建 workspace 与依赖守卫

**输出文件**：根 `Cargo.toml`、全部空 crate、`deny.toml`、CI。

**操作**：

1. 建立 `02-workspace-boundaries.md` 中的 crate；
2. 各 crate 只放 `lib.rs` 和 crate-level 文档；
3. 配置 `cargo deny`、`cargo fmt --check`、`cargo clippy -- -D warnings`；
4. 写脚本检查禁止依赖边；
5. 不实现任何 Ability crate。

**验收**：workspace 编译；加入非法 `fabric-core -> tokio` 依赖时 CI 必须失败。

## T02：实现 `fabric-core`

**输出**：所有 ID、Ability、Offer、Requirement、Participant、Port、SessionPlan、ChannelContract、错误类型。

**操作**：

1. 按 `01-domain-model.md` 逐类型实现；
2. 所有 ID 使用 newtype；
3. 加 serde 仅作为可选 feature；
4. 对属性 key、namespace、name 做长度和字符验证；
5. 对 Plan 写 deterministic canonicalization 和 hash。

**验收**：canonical hash 跨插入顺序一致；非法名称和无界配置被拒绝。

## T03：定义 `protocol/fabric.proto` 与转换层

**输出**：proto、生成代码、wire/domain `TryFrom`、错误映射。

**操作**：

1. 只定义 `05-wire-control-protocol.md` 的消息；
2. 每个 enum 固定数值，保留 0 为 UNSPECIFIED；
3. 所有 bytes/array 转换检查长度；
4. 不在 core 中泄露 prost 类型；
5. 添加 golden vector。

**验收**：未知字段 round-trip；超长集合拒绝；domain->wire->domain 相等。

## T04：实现 Registry 与 Lease

**输出**：内存 repository、revision、snapshot/delta、租约事件。

**操作**：

1. Offer/Requirement CRUD；
2. App connection-bound 清理；
3. Renewable lease 到期；
4. 每次变更 revision +1；
5. 生成 deterministic delta。

**验收**：并发更新不丢 revision；过期产生 remove event；snapshot 原子。

## T05：实现 Matcher

**输出**：纯函数 matcher、拒绝原因、候选排序。

**操作**：

1. Ability key/major/hash；
2. role；
3. property predicate；
4. device selector；
5. policy prefilter hook；
6. 确定性排序。

**验收**：相同快照始终相同结果；不调用网络/数据库；拒绝原因可测试。

## T06：实现 Session 纯状态机

**输出**：actor core，但暂不接 I/O。

**操作**：

1. 实现全部状态和合法转换；
2. propose/counter/accept/reject；
3. prepare/ready/commit；
4. close/suspend/resume；
5. reconfiguration 创建 epoch+1 完整 Plan；
6. operation id 幂等。

**验收**：property tests；非法转换显式错误；重复 commit 不产生第二次副作用。

## T07：实现 Router 内存后端

**输出**：CompiledRoute、message/stream/datagram 本地抽象、背压和公平调度器。

**操作**：

1. 从 SessionPlan 编译路由；
2. 一对一、一对多、多对一、多对多；
3. 有界队列；
4. priority fairness；
5. epoch 校验；
6. opaque bytes-only。

**验收**：Router crate 不引用任何 Ability schema；所有拓扑测试通过；内存上限测试通过。

## T08：实现 Identity 与 Policy

**输出**：设备密钥接口、DeviceId、AppPrincipal adapter traits、attestation、Policy engine。

**操作**：

1. 设备身份生成/加载；
2. 测试 OS credential adapter；
3. remote app attestation 签发/验证；
4. policy input/decision；
5. snapshot 和 revoke；
6. capability grant。

**验收**：应用自报 ID 不能覆盖认证身份；过期 attestation/grant 拒绝；策略变更触发 revoke event。

## T09：实现 Storage

**输出**：SQLite migrations、repository traits 实现、恢复逻辑。

**操作**：

1. 建表；
2. 事务和 revision；
3. startup recovery 标记；
4. lease GC；
5. operation result cache；
6. resume token hash。

**验收**：崩溃注入后数据库一致；Active 不被直接恢复；敏感 key 不入库。

## T10：实现本地 IPC server 与 SDK

**输出**：Unix/测试 transport、握手、请求、事件、Channel handles。

**操作**：

1. frame codec；
2. OS principal adapter；
3. publish/require/propose/accept；
4. server event subscription；
5. message/stream/datagram 本地传输；
6. 配额和断连清理。

**验收**：两个模拟 App 可通过同一 Hub 建立本地 Session；恶意长度帧不崩溃；App crash 清理 Offer。

## T11：实现 Link trait 的内存传输

**输出**：可控延迟/丢包/断线的 MemoryLink。

**操作**：

1. control；
2. uni/bi stream；
3. datagram；
4. link state；
5. fault injection。

**验收**：多 Hub 集成测试不依赖真实网络；可重现指定随机 seed。

## T12：实现 QUIC transport

**输出**：Quinn endpoint、认证、control stream、stream/datagram 映射、重复连接仲裁。

**操作**：

1. rustls 设备证书验证；
2. HubHello/LinkReady；
3. 单 control stream；
4. StreamOpen；
5. Datagram header；
6. keepalive/path change；
7. duplicate connection rule。

**验收**：真实 localhost 两 Hub 完整 Session；无认证 peer 只能配对；文件流和 Datagram 并发不饿死 control。

## T13：实现 Registry peer sync

**输出**：授权过滤后的 snapshot/delta、revision ack/resync。

**操作**：

1. Link ready 后过滤 Offer；
2. snapshot；
3. delta；
4. revision gap；
5. peer disconnect cache expiry；
6. policy change 后重新过滤。

**验收**：未配对设备零 Offer；revision 缺口自动 resync；撤销即时传播。

## T14：实现 mDNS discovery

**输出**：服务广播、浏览、候选缓存。

**操作**：

1. 固定服务类型和 TXT；
2. 多地址；
3. add/update/remove；
4. TTL；
5. hint rotation；
6. 不发布 Ability。

**验收**：两设备自动发现并拨号；抓包/测试断言无 Ability 名称。

## T15：实现 BLE discovery adapter

**输出**：统一 BLE trait、至少一个可运行平台后端、其他平台接口占位但不伪实现。

**操作**：

1. scan advertisement；
2. pairing nonce exchange；
3. connection hint；
4. candidate merge；
5. 平台能力检测。

**验收**：BLE 只建立候选/配对，不承载大数据；不可用功能返回 UnsupportedCapability。

## T16：实现 Wi-Fi Aware discovery adapter

**输出**：`discovery-wifi-aware` crate、Android JNI platform adapter、桌面 unsupported adapter。

**操作**：

1. publish/subscribe service specific info；
2. connection hint；
3. candidate merge；
4. Android `WifiAwareManager` 能力与权限检测；
5. 桌面平台明确返回 `UnsupportedCapability`。

**验收**：Wi-Fi Aware 只建立候选，不承载大数据；Windows 不用 Wi-Fi Direct 冒充 Wi-Fi Aware。

## T17：实现 E2EE

**输出**：session/channel key agreement、AEAD envelope、replay window、group rekey。

**操作**：

1. 选定成熟 suite；
2. key context 绑定 session/channel/epoch/sender；
3. sequence nonce；
4. zeroize；
5. group member change；
6. Hub opaque forwarding。

**验收**：Hub 测试只能看到密文；nonce replay 拒绝；退出成员不能解密新 epoch。

## T17：实现 ClockDomain

**输出**：ClockProbe/Reply、sample filter、affine mapping、uncertainty、virtual clock tests。

**操作**：

1. 四 timestamp；
2. RTT/outlier；
3. offset；
4. rate/drift；
5. staleness；
6. master fail event。

**验收**：仿真 drift/jitter 下误差和 uncertainty 合理；wall clock jump 不影响 mapping。

## T18：实现 Barrier

**输出**：Prepare/Ready/Commit/Abort、deadline、policy。

**操作**：

1. Session extension；
2. local app prepare event；
3. ready aggregate；
4. commit schedule；
5. late/missed；
6. reconfiguration cancellation。

**验收**：4 participant 同一虚拟 GroupInstant 执行；一个 Reject 时按 policy Abort。

## T19：实现 Hub composition

**输出**：可配置 daemon、生命周期、管理接口、metrics/tracing。

**操作**：

1. 按固定启动顺序组装；
2. graceful shutdown；
3. 配置验证；
4. 限额；
5. tracing/metrics；
6. health diagnostics。

**验收**：无 Ability 依赖；重启恢复规范；所有 integration tests 通过。

## T20：编写 Ability conformance kit

**输出**：契约解析器、hash 工具、SDK mock、golden test runner、示例空 Ability。

**操作**：

1. 验证 ability.toml；
2. 生成协议 hash；
3. 生成 Rust schema binding；
4. 模拟 participant；
5. 模拟 loss/reorder/epoch；
6. 安全声明 lint。

**验收**：新 Ability 可以不修改 Hub 代码完成注册和 Session。

## T21：验证参考能力

只写最小 conformance adapter，不把业务做进 Hub：

1. echo reliable message；
2. byte stream transfer；
3. latest-state Datagram；
4. one-source-to-many timed tick Barrier；
5. `foundation.audio` adapter 接口设计和 mock，不要求修改 Hub。

**最终验收**：上述四种通道/拓扑演示都通过，同一个 Hub 二进制无需针对能力重新编译业务代码。

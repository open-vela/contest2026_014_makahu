# Device Fabric：能力无关的跨设备框架规范

本目录是一套面向实现者的**最终架构规范**。它不把音频、文件、剪贴板等业务能力写进 Hub 内核。所有文档描述同一个完整系统；`13-implementation-order.md` 只是依赖顺序，不代表功能版本或裁剪版产品。

## 实现者必须遵守的总原则

1. Hub 只理解设备、应用身份、Ability 契约、Session、Port、Channel、权限、时钟和传输。
2. Hub 不理解音频帧、文件块、剪贴板内容、密钥格式、媒体编解码或业务冲突规则。
3. 设备链路必须认证并加密；Ability payload 是否端到端加密由契约和策略决定。
4. Provider/Requirement 只用于发现和匹配；会话运行时统一使用 Participant、Role、Port 和 Channel。
5. Session 是有向多重图，不能用单个 `OneToMany` 枚举替代真实通道图。
6. 框架数据面只提供：可靠消息、可靠字节流、不可靠 Datagram。
7. 公共时钟和 Prepare/Commit Barrier 是通用可选扩展，不属于音频模块。
8. 本地应用必须通过 OS 可验证身份连接 Hub，不能信任应用自报的包名。
9. mDNS/BLE/Wi-Fi Aware 仅发现 Hub 候选，不公开完整 Ability 清单。
10. `foundation.audio` 只能由音频 Ability adapter 依赖，Hub 及核心 crate 禁止依赖它。

## 阅读顺序

1. `00-system-contract.md`
2. `01-domain-model.md`
3. `02-workspace-boundaries.md`
4. `03-local-ipc-sdk.md`
5. `04-discovery-link-identity.md`
6. `05-wire-control-protocol.md`
7. `06-session-routing.md`
8. `07-security-policy.md`
9. `08-clock-and-barrier.md`
10. `09-storage-recovery.md`
11. `10-ability-authoring.md`
12. `11-reference-abilities.md`
13. `12-testing-observability.md`
14. `13-implementation-order.md`
15. `14-rationale-and-sources.md`

## 规范性词语

- **MUST / 必须**：不满足即违反架构。
- **MUST NOT / 禁止**：实现中不得出现。
- **SHOULD / 应当**：除非有记录在案的技术理由，否则必须遵循。
- **MAY / 可以**：兼容实现可选择。

## 交付物

最终仓库至少应包含：

```text
Cargo.toml
crates/
  core/
  protocol/
  registry/
  matcher/
  session/
  router/
  link/
  transport-quic/
  ipc/
  discovery/
  discovery-mdns/
  discovery-ble/
  discovery-wifi-aware/
  identity/
  policy/
  crypto/
  clock/
  storage/
  sdk/
  hub/
protocol/
  fabric.proto
  errors.md
docs/
tests/
  integration/
  fault/
```

不要在 `hub` 中实现任何具体 Ability。

## 构建与运行

```powershell
cargo test --workspace --all-features
cargo clippy --workspace --all-targets --all-features -- -D warnings
.\scripts\check-dependency-boundaries.ps1
cargo run -p fabric-hub
# 或显式指定数据库与设备密钥路径：
cargo run -p fabric-hub -- fabric.sqlite fabric-device.key
```

Hub 默认在平台用户状态目录（Windows 为 `%LOCALAPPDATA%\DeviceFabric`，Unix 为 `$XDG_STATE_HOME/device-fabric`）创建数据库和 32 字节设备种子文件；生产部署仍建议用实现 `DeviceKeyStore` 的 OS 安全存储 adapter 替换文件后端。核心初始化后保持 `Starting`，只有 IPC、发现和 Link 平台组件按顺序实际启动并通过 `attach_component` 报告成功后才进入 `Healthy`；`Ctrl+C` 触发逆序关闭。

实现覆盖领域模型、wire 协议、Registry/Matcher、Session/Router、身份与策略、SQLite 恢复、本地 IPC/SDK、MemoryLink/QUIC、mDNS/BLE/Wi-Fi Aware 发现、E2EE、Clock/Barrier、Hub composition，以及 Ability conformance kit。可执行的参考能力测试位于 `crates/hub/tests/reference_abilities.rs`。

## 代码组织

### 核心原则

* 文件按职责拆。
* 模块按概念拆。
* crate 按依赖边界拆。
* 不按类型数量或固定行数机械拆分。

### 推荐

* 同一职责的类型、`impl`、错误、配置、Builder 和测试放在一起。
* 按领域组织目录，而不是按 `struct`、`trait`、`enum` 分类。
* 用模块入口统一导出公共 API，并隐藏内部文件结构。
* 默认保持私有，按需使用 `pub(super)`、`pub(crate)`、`pub`。
* `lib.rs` 只负责模块声明、公共导出和 crate 文档。
* `main.rs` 只负责配置、依赖装配和启动。
* 平台相关实现拆到独立模块。
* 单元测试放在实现旁边，集成测试放在 `tests/`。
* 文件和模块使用 `snake_case`，类型使用 `UpperCamelCase`。
* 使用具体职责名称，如 `Registry`、`Router`、`Pool`、`Store`、`Scheduler`。

### 何时拆文件

满足以下情况时考虑拆分：

* 存在多个独立职责。
* 某部分有独立状态机或生命周期。
* 某部分可以单独命名和测试。
* 平台实现或依赖明显不同。
* 私有细节已经遮蔽主要 API。
* 很难用一个准确名称描述整个文件。

数百行的 Rust 文件通常正常。文件较长只是检查信号，不是拆分依据。

### 常见反模式

* 一个类型一个文件。
* `structs/`、`traits/`、`enums/`、`impls/` 分类目录。
* 所有代码都设为 `pub`。
* 向外暴露内部模块路径。
* 巨大的 `utils`、`common`、`misc`。
* 巨大的全局 `types.rs`、`models.rs`、`error.rs`。
* 到处使用 `Manager`、`Helper`、`Service` 等模糊名称。
* 模块嵌套过深。
* 为了缩短文件而拆散紧密相关代码。

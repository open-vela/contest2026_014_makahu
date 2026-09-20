# 10. Ability 契约与实现指南

## 1. Ability 包必须包含

```text
ability-name/
  ability.toml
  schemas/
    control.proto
    data.proto          # 如需要
  docs/
    semantics.md
    state-machine.md
    security.md
    interoperability.md
  tests/
    contract-vectors/
```

## 2. `ability.toml`

示例：

```toml
namespace = "com.example"
name = "example.state-stream"
major = 1
protocol_hash_algorithm = "sha256"

[[roles]]
id = "publisher"
min_instances = 1

[[roles.ports]]
id = "control"
direction = "bidirectional"
mode = "reliable_messages"
schema = "schemas/control.proto#ControlMessage"
required = true

[[roles.ports]]
id = "updates"
direction = "send"
mode = "datagram"
schema = "schemas/data.proto#Update"
required = true

[[roles]]
id = "subscriber"
min_instances = 1
max_instances = 65535

[requirements]
clock = "optional"
barrier = false
minimum_device_trust = "trusted"
remote_app_auth = "hub_attested"
payload_security = "link_encrypted"
```

构建工具规范化此文件和 schema，计算 `protocol_hash`。

## 3. 必写语义

`semantics.md` 必须精确定义：

- 每个 Role 的业务含义；
- 每个 Port 的 sender/receiver；
- 消息顺序和幂等规则；
- 丢包、重复、乱序处理；
- 大小和速率限制；
- 错误和重试；
- Session reconfiguration 行为；
- participant join/leave 行为；
- 是否允许 relay/multicast；
- 版本兼容规则。

不要把这些语义留给实现模型猜测。

## 4. Properties

Offer property 只用于候选筛选和协商提示，例如 codec、最大文件大小、能力标签。规则：

- 明确 key 类型；
- 明确单位；
- 明确 required/optional；
- 明确比较规则；
- 不能把敏感信息放入公开 property；
- 最终参数必须写入 SessionPlan 的 Ability opaque config，并由 participant 接受。

## 5. Ability 协商

框架只匹配候选。Ability 自己通过 `SessionProposal.opaque_ability_config` 协商：

```text
proposer 提出参数
每个 participant 验证并 Accept/CounterOffer
参数完全确定后纳入 final plan hash
```

禁止在 Session Active 后悄悄改变 codec、schema 或业务模式；需要 reconfiguration。

## 6. 运行时 adapter

Ability 实现应由两层组成：

```text
Ability protocol engine
    只依赖 fabric-sdk 和业务协议
Local capability adapter
    依赖平台 API 或现有核心库
```

例如：

```text
ability-audio-render-protocol -> fabric-sdk
ability-audio-render-foundation -> ability-audio-render-protocol + foundation.audio
```

## 7. 安全声明

`security.md` 必须列出：

- payload 敏感级别；
- 最低设备信任；
- 远端 App 认证；
- 用户确认条件；
- 是否强制 E2EE；
- replay/nonce 规则；
- 日志禁止字段；
- 成员退出后的密钥处理。

## 8. Clock 声明

```rust
pub enum ClockRequirement {
    None,
    Optional,
    Required { max_uncertainty_ns: u64 },
}
```

需要同步动作时，还要定义：

- `presentation_at` 或业务时间戳含义；
- Barrier context schema；
- late handling；
- 本地硬件/业务时钟如何映射到 SessionClock。

## 9. Contract test vectors

至少提供：

- 合法最小消息；
- 合法最大消息；
- 未知字段；
- 非法 enum；
- 重复/乱序/丢包序列；
- stale epoch；
- join/leave；
- E2EE nonce reuse 拒绝；
- 跨语言序列化 golden files。

## 10. 禁止事项

Ability 实现禁止：

- 直接连接远端 App，绕过 Hub；
- 使用 IP 作为 participant identity；
- 访问其他 Session 的 Channel；
- 要求 Hub 解析业务 payload；
- 假设所有设备时钟相同；
- 假设 Datagram 到达或有序；
- 将长期私钥交给 Hub-readable payload。

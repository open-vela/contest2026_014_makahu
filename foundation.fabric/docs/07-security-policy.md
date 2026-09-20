# 07. 身份、安全与权限

## 1. 威胁模型

必须考虑：

- 同一局域网恶意设备伪造服务；
- 恶意本地 App 伪造包名；
- 已配对设备上的未授权 App 请求能力；
- 重放旧 Session/epoch 消息；
- Hub 被用作无限缓存或网络放大器；
- 成员退出后继续解密组数据；
- mDNS/BLE 广告泄露用户和应用信息；
- 日志泄露 payload、密钥、路径或剪贴板。

不假设本地网络可信。

## 2. 设备身份

首次启动：

1. 生成设备长期签名密钥；
2. 私钥存入 OS 安全存储或受保护文件；
3. `DeviceId = SHA-256(canonical_public_key)`；
4. 生成自签或内部证书，将证书公钥与 DeviceId 绑定；
5. 私钥禁止导出到日志和普通配置。

设备更换身份必须被视为新设备。

## 3. 配对

配对至少包含：

- 双向设备证明；
- 随机 nonce；
- transcript hash；
- 用户确认或预共享信任来源；
- 防止中间人替换；
- 存储信任记录。

推荐 UX：BLE/局域网建立临时通道，双方显示相同短验证码，用户在至少一端确认。验证码由完整握手 transcript 派生，不是随机展示。

信任状态：

```rust
pub enum DeviceTrust {
    Unknown,
    PendingUserConfirmation,
    Trusted,
    Blocked,
}
```

## 4. 本地 AppPrincipal

```rust
pub struct AppPrincipal {
    pub platform: Platform,
    pub stable_app_id: String,
    pub publisher_id: Option<String>,
    pub signing_digest: Option<[u8; 32]>,
    pub os_subject: OsSubject,
}
```

平台 adapter 必须从 peer credential/签名系统获取。App 传入的 metadata 只能附加，不能覆盖认证字段。

## 5. 远端应用证明

远端 Hub 对以下结构签名：

```text
remote_device_id
app_principal_digest
ability_instance_id
ability_contract
issued_at
expires_at
nonce
```

本地 Hub 验证签名和设备信任后，将其作为 `HubAttestedApp`。Ability 可要求：

```rust
pub enum RemoteAppRequirement {
    AnyAppOnTrustedDevice,
    HubAttested,
    SamePublisher,
    ExactApp { digest: [u8; 32] },
}
```

## 6. Policy

策略输入：

```rust
pub struct PolicyInput {
    pub local_app: AppPrincipal,
    pub remote_device: DeviceId,
    pub remote_app: Option<AttestedAppPrincipal>,
    pub ability: AbilityContractRef,
    pub requested_role: RoleId,
    pub requested_channels: Vec<ChannelContract>,
    pub user_presence: UserPresence,
}
```

输出：

```rust
pub enum PolicyDecision {
    Allow(PolicyGrant),
    RequireUserAction(UserActionRequest),
    Deny(DenyReason),
}
```

PolicyGrant 必须冻结为 `PolicySnapshot` 并写入 SessionPlan。策略后续变化应触发 revoke 或 reconfiguration。

## 7. Capability grant

Hub 向本地 App 发短期 grant，限制：

- SessionId；
- ParticipantId；
- 允许的 Port；
- 动作；
- epoch；
- 到期时间；
- 最大资源。

SDK 每次打开 Port 时附带 grant reference。Hub 不允许应用凭 SessionId 猜测访问其他通道。

## 8. 链路加密

Hub-to-Hub 控制和数据链路始终使用 QUIC/TLS 认证加密。不存在“关闭链路加密”的配置。

## 9. Ability E2EE

```rust
pub enum PayloadSecurity {
    HubReadable,
    EndToEnd {
        suite: CryptoSuiteId,
        membership: KeyMembershipPolicy,
    },
}
```

E2EE 只加密 Ability payload；外层路由头仍可被 Hub 读取。必须使用：

- 每 Session 或每 Channel 独立 key context；
- epoch 进入 key derivation；
- 单调 sequence/nonce；
- replay window；
- 明确 sender authentication；
- key zeroization。

禁止自行设计未经审查的密码算法。使用成熟 AEAD 与 KDF 实现。

## 10. Group key

成员变化时：

1. Session 进入 Reconfiguring；
2. 生成新 epoch；
3. 为保留成员分发新 key material；
4. 新成员只获得新 epoch key；
5. 被移除成员不能解密新 epoch；
6. Commit 后发送新 epoch 数据。

旧 key 在 drain 窗口结束后清零。

## 11. 高风险 Ability

契约可声明最低安全要求：

```rust
pub struct SecurityRequirements {
    pub require_trusted_device: bool,
    pub remote_app_requirement: RemoteAppRequirement,
    pub require_user_presence: bool,
    pub minimum_payload_security: PayloadSecurityClass,
    pub allow_background: bool,
}
```

密钥同步等能力必须强制 E2EE、精确应用认证和用户确认；Hub 不通过通用配置降级。

## 12. 日志

禁止记录：

- payload；
- 私钥、会话密钥、nonce 完整值；
- 剪贴板文本；
- 文件内容或完整敏感路径；
- 用户账号 token。

日志只记录哈希化对象 ID、长度、错误类别和统计。

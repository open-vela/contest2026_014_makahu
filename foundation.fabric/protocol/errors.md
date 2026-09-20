# Stable protocol error codes

| Code | Meaning | Retry |
|---|---|---|
| INVALID_ARGUMENT | 字段非法或不满足约束 | 修正请求 |
| UNSUPPORTED_VERSION | 无共同协议版本 | 升级实现 |
| CONTRACT_MISMATCH | Ability key 相同但契约不兼容 | 更换实现/版本 |
| NOT_FOUND | 对象不存在 | 通常不重试 |
| ALREADY_EXISTS | 冲突对象已存在 | 使用已有对象或新 ID |
| UNAUTHENTICATED | 身份证明失败 | 重新认证/配对 |
| PERMISSION_DENIED | 策略拒绝 | 不重试，除非权限变化 |
| POLICY_REQUIRES_USER_ACTION | 需要用户确认 | 完成 UI 操作后重试 |
| RESOURCE_EXHAUSTED | 配额或容量不足 | 可按 retry_after |
| STALE_EPOCH | Session epoch 过旧 | 获取新 Plan |
| SESSION_NOT_ACTIVE | 会话状态不允许 | 等待状态变化 |
| PARTICIPANT_OFFLINE | participant 不在线 | 等待或重配置 |
| CHANNEL_NOT_BOUND | Port/Channel 不属于调用方 | 修正实现 |
| MESSAGE_TOO_LARGE | 超过协商上限 | 分块或降低大小 |
| BACKPRESSURE | 本地/链路队列满 | 延迟重试或丢弃 Datagram |
| CLOCK_UNAVAILABLE | 无公共时钟 | 重新协商 |
| CLOCK_UNCERTAIN | 不确定度超限 | 等待更多采样 |
| E2EE_REQUIRED | 当前 Channel 必须 E2EE | 重新协商安全 |
| E2EE_NEGOTIATION_FAILED | 密钥协商失败 | 关闭/重建 Session |
| TIMEOUT | 操作超时 | 依操作幂等规则重试 |
| INTERNAL | 未分类内部错误 | 有限重试并报告 |

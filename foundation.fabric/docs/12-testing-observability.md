# 12. 测试、可观测性与验收

## 1. 测试层次

### 单元测试

- ID/contract 规范化；
- Matcher 属性谓词；
- Session 纯状态机；
- epoch/reconfiguration；
- Router fanout 与背压；
- Clock sample filter；
- Policy decisions；
- protobuf/domain 转换。

### 属性测试

使用 proptest：

- 任意事件序列不产生非法 Session 状态；
- Commit 后所有节点 Plan hash/epoch 一致；
- 旧 epoch 数据永不交付到新 epoch Port；
- Router 不向未绑定 destination 发送；
- 消息大小/队列限制始终生效；
- E2EE nonce 不重复。

### 模糊测试

- IPC frame decoder；
- control frame decoder；
- StreamOpen；
- Datagram header；
- Ability contract parser；
- discovery TXT/GATT payload。

### 集成测试

至少模拟 4 Hub、每 Hub 多 App：

1. 一对一可靠消息；
2. 一对多 Datagram；
3. 多对一消息；
4. 多对多；
5. participant join/leave；
6. coordinator 断开；
7. link 重连；
8. app crash；
9. stale epoch；
10. policy revoke；
11. E2EE group rekey；
12. clock barrier。

## 2. Fault injection

测试工具必须可注入：

- packet loss、reorder、duplicate；
- 延迟和 jitter；
- QUIC connection reset；
- control message duplicate；
- SQLite commit failure；
- App IPC disconnect；
- Hub restart；
- clock drift 和 clock jump；
- queue saturation；
- BLE/mDNS 候选重复和过期。

## 3. 确定性仿真

为 `fabric-session`、`fabric-router` 和 `fabric-clock` 提供虚拟时间和内存 Link。核心测试不能依赖真实 sleep 和随机网络。

## 4. 指标

最低指标：

```text
fabric_links_active
fabric_link_connect_duration_ms
fabric_registry_revision
fabric_sessions_by_state
fabric_session_reconfigurations_total
fabric_channels_active
fabric_channel_buffered_bytes
fabric_messages_sent_total
fabric_datagrams_dropped_total{reason}
fabric_policy_decisions_total{decision}
fabric_clock_offset_ns
fabric_clock_uncertainty_ns
fabric_barrier_result_total{result}
fabric_ipc_connections
fabric_protocol_errors_total{code}
```

所有 metric label 必须低基数。禁止使用 SessionId、DeviceId、AppId 做 label。

## 5. Tracing

结构化 span：

```text
link.connect
registry.sync
session.propose
session.prepare
session.commit
session.reconfigure
channel.open
barrier.prepare
barrier.commit
```

对象 ID 只记录短哈希。payload 和密钥绝不记录。

## 6. 性能验收

框架级目标必须通过基准确定，至少包括：

- 1000 Offer 匹配延迟；
- 100 并发 Session 控制开销；
- 单 Link 多流公平性；
- 一对多 8 destinations Datagram fanout；
- 可靠流零拷贝/有限拷贝路径；
- 10k msg/s 控制消息下内存有界；
- Hub restart 与 registry recovery。

不要在文档中虚构具体毫秒指标；在目标硬件上建立 baseline 后写入 CI threshold。

## 7. 架构验收清单

完成实现必须全部满足：

- [ ] Hub 无任何 audio/file/clipboard/key 业务依赖；
- [ ] 一个 Session 可声明多个方向不同的 Channel；
- [ ] 一对多、多对一、多对多测试通过；
- [ ] mDNS/BLE 广告不含 Ability 列表；
- [ ] 未认证设备不能收到 Registry；
- [ ] App 身份来自 OS；
- [ ] Link encryption 不能关闭；
- [ ] E2EE Channel 的 Hub 看不到 payload；
- [ ] stale epoch 数据被拒绝；
- [ ] 所有队列有上限；
- [ ] Hub restart 不直接恢复 Active；
- [ ] Clock uncertainty 可传播；
- [ ] Barrier 有 Prepare/Ready/Commit/Abort；
- [ ] `foundation.audio` 仅出现在音频 adapter 依赖图中。

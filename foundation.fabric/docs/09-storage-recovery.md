# 09. 持久化、租约与崩溃恢复

## 1. 存储原则

SQLite 是 Hub 的元数据存储。数据 payload、媒体缓存和文件内容不放入核心数据库。

必须区分：

- durable identity/policy；
- renewable registry records；
- active runtime state；
- ephemeral secrets。

## 2. 表

建议 schema：

```sql
CREATE TABLE devices (
    device_id BLOB PRIMARY KEY,
    public_key BLOB NOT NULL,
    display_name TEXT,
    trust_state INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE app_principals (
    principal_digest BLOB PRIMARY KEY,
    platform INTEGER NOT NULL,
    stable_app_id TEXT NOT NULL,
    publisher_id TEXT,
    signing_digest BLOB,
    last_seen_at INTEGER NOT NULL
);

CREATE TABLE policies (
    policy_id BLOB PRIMARY KEY,
    revision INTEGER NOT NULL,
    document BLOB NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE offers (
    instance_id BLOB PRIMARY KEY,
    owner_principal BLOB NOT NULL,
    contract_key TEXT NOT NULL,
    protocol_hash BLOB NOT NULL,
    encoded_offer BLOB NOT NULL,
    lease_kind INTEGER NOT NULL,
    lease_expires_at INTEGER,
    revision INTEGER NOT NULL
);

CREATE TABLE requirements (
    requirement_id BLOB PRIMARY KEY,
    owner_principal BLOB NOT NULL,
    encoded_requirement BLOB NOT NULL,
    lease_kind INTEGER NOT NULL,
    lease_expires_at INTEGER,
    revision INTEGER NOT NULL
);

CREATE TABLE session_records (
    session_id BLOB PRIMARY KEY,
    epoch INTEGER NOT NULL,
    state INTEGER NOT NULL,
    encoded_plan BLOB,
    resume_token_hash BLOB,
    updated_at INTEGER NOT NULL
);

CREATE TABLE peer_registry_revisions (
    peer_device_id BLOB PRIMARY KEY,
    local_sent_revision INTEGER NOT NULL,
    remote_applied_revision INTEGER NOT NULL
);
```

## 3. 不持久化内容

默认禁止持久化：

- QUIC connection/stream 状态；
- Datagram 队列；
- E2EE session key；
- ClockMapping 原始可用状态；
- Barrier Ready 状态；
- App connection capability grant。

## 4. 启动恢复

Hub 启动顺序：

1. 打开数据库并迁移；
2. 加载设备身份和信任；
3. 将所有非终态 Session 标记为 `SuspendedAfterRestart`；
4. 清理过期租约；
5. 启动 IPC；
6. 等待 App 重连并恢复可续租 Offer；
7. 启动发现和 Link；
8. 对可信 peer 发 Registry resync；
9. 仅在双方和相关 App 都证明可恢复时重建 Session，否则关闭旧记录。

禁止把数据库中 `Active` 直接恢复为 Active。

## 5. 事务

以下操作必须单事务：

- Offer upsert + registry revision 递增；
- Requirement update + revision；
- Session epoch commit + plan snapshot；
- Policy update + snapshot revision；
- 信任状态改变 + revoke 相关 grant。

网络发送在事务提交后进行，采用 outbox 或可重建 delta，避免“已发送但未落库”不一致。

## 6. 租约清理

定时任务：

- 删除过期 renewable Offer/Requirement；
- 生成 registry remove delta；
- 通知受影响 Session；
- 清理终态 Session 记录，仅保留审计摘要；
- 清理 operation idempotency cache。

## 7. Resume token

Resume token 必须是随机高熵秘密，只存哈希。它绑定：

- session_id；
- epoch；
- peer devices；
- app principals；
- expires_at。

恢复成功后立即轮换 token。

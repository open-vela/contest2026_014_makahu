# 独立源码审计处置记录

本记录逐项回应 [09-independent-code-audit.md](09-independent-code-audit.md)。原审计报告保持不变。所有改动留在工作树中，没有提交或推送；模拟器按用户要求保持关闭，没有安装或启动 app，也没有修改设备/AVD 用户数据。

## P1：Fabric runtime 双向同步

已补齐 Android 与 Vela 的 Fabric 路径，覆盖 mode、focus reminder、research session。runtime snapshot 使用 `buffer.runtime.state` / `buffer.runtime.reply`，字段严格限定为 `type`、`protocol`、`revision`、`mode`、`focus_reminder`、`research_session`；所有字段在任何状态变更前完成解析和校验。Vela 只接受当前已授权 phone peer 与当前 live session generation 的消息，以 session 内单调 revision 拒绝过期快照；断开后清空 session fence。重复 revision 幂等回复。若本地 mode 仍 dirty，返回 `deferred` 并保持本地选择，避免旧手机快照覆盖它。

phone snapshot 的三类状态分别应用到 Vela；持久化错误向上传播，不发送 `applied`，revision 仅在所有 helper 成功后提交。Android 每 session 从 revision 1 开始，仅保留一个待 ACK snapshot；相同 revision 超时重发原字节，`deferred` 后生成更新快照，断开时重置 sender。

反向 mode 使用 `buffer.mode.change` / `buffer.mode.reply`。Vela 只为当前 durable dirty mode 发送，并每 5 秒重试；Android 按 `mode_id` 持久去重后才回复 `applied`。Vela 对回执同时核对当前 mode 与 mode ID，先成功持久清除 pending，再清 dirty；持久写失败不确认。Fabric handler 在分发及 ACK 前后均检查活跃 peer/session generation，不借用旧 TCP 路径。

## P1：Vela 本地卡片后续动作回写 Android

processed capture 现在作为不可变导入基线保存。卡片后续变更走独立、版本化的 `buffer.card.state.change` / `buffer.card.state.reply` 协议，不再把修改过的 capture 当第二次导入，也不把 Android 的 immutable-capture `conflict` 当成功。

状态变更携带 capture/card ID、单调 revision、action ID、previous/new state、deleted tombstone 与 review time。只有 capture 已收到原始导入回执后才发送状态变更；未确认消息按 5 秒重试。ACK 必须匹配当前 card/revision/action；重复 ACK 幂等持久化，conflict/stale/rejected 保持未解决并可见，不推进为已送达。旧 receipt 与带动作元数据的已处理记录迁移时仍按 immutable 原始 hash 判断，不会重导入或覆盖原件。

Android 在单个数据库事务内核对来源、前一状态、revision 与 action ID，并同时写入状态和 receipt。冲突不会改卡；删除是保留内容行的软 tombstone，查询隐藏该卡但副本仍传播 tombstone。历史库缺少状态基线时先验证 previous-state 投影，不吻合则报告冲突，不猜测覆盖。没有删除用户数据。

## 暂停遗留的 C 接口阻断

`buffer_sync_remote_mode` 的声明、定义统一为 `int`，持久化/helper 错误由调用方保留；清除 pending mode 的调用检查 `buffer_store_clear_pending_mode` 返回值。之前只声明而无实现的 runtime parser、mode ACK 与状态处理接口已实现并纳入全量 Vela 编译。research session 测试也改为要求本地 dirty mode 返回 `-EAGAIN` 且不应用旧快照；已结束 session 不会被相同 ID 复活。

## P2：两份 host harness

`test_fabric_att_payload.py` 补齐从真实 SDK profile 提取 ATT handler 所需的 `io_wake` 和 `unlock_status` harness 依赖；测试使用 SDK ATT MTU 253 验证 250-byte payload 可进入生产 fragment handler。`test_agent_queue_fairness.py` 补齐三个 store/OOBE stub，使 harness 能链接并运行真实 agent queue fairness 代码；stub 只隔离无关持久层依赖，没有替换被测调度逻辑，也没有放宽断言。

## P2：多设备 coordinator 边界与可行方案

审计指出的问题仍是实际产品边界：当前 Buffer enrollment/coordinator 只构成 phone + 一台 Vela 的两成员视图。portable BLE profile 也规定两个参与者、一个 ReliableMessages channel 和 epoch 1。权威 `foundation.fabric` Rust session election 的固定成员、持久 vote 与 strict-majority certificate 虽有独立 host 测试，但 Buffer BLE app 尚未接入，不能据此声称多 Vela/多 phone coordinator 已实现。本轮没有改权威 Fabric 或项目 snapshot 来伪装此能力。

若产品要求多设备一致 coordinator，建议先在权威仓库定义可持久化的 cluster identity 与 membership epoch、成员加入/移除规则、leader term/vote 和 strict-majority commit；Fabric transport 再支持多 peer 路由。随后在 Buffer Android/C 接入同一 election 状态机，以 membership digest + term fence 旧 leader 和分区双主，并在 card/runtime 更新上明确 quorum 与离线语义。验收需覆盖并发选举、重启后旧 vote、成员变更、分区失去 quorum、旧成员重连及状态重放，再同步权威改动到项目 snapshot。该项需要独立协议与多节点测试设备，本次不扩展实现范围。

## P2：设备 API key 静态存储

风险仍存在：Vela 当前把 API key 写在 agent JSON 中。0700/0600 权限、LittleFS 文件属性及拒绝 FAT/HOSTFS 的检查降低普通文件访问面，但**不加密文件内容，也不抵御取得存储介质/root 权限的读取**。当前项目没有已验证的 S1 硬件密钥槽、TEE/PSA protected storage 或 OS keystore API。本轮未加入同盘密钥/硬编码密钥等“加密”包装，也未声称已有硬件保护。

真实修复需先以芯片和板级证据确认不可导出的 SoC HUK、secure element 或受保护存储服务，再定义 `SecretProvider` 接口，由硬件服务保存/解封 API key；同时提供导入、轮换、撤销、掉电恢复和失败关闭测试。密钥只在请求时短暂进入 RAM。若板上没有可验证的根密钥能力，应明确保留此风险，或另行设计受认证手机 broker 发放短时凭据；不得把同一 LittleFS 上的密文称为硬件保护。S1 flash 的权限、属性和掉电保证仍需独立验收。

## 验证

- Host 回归 17/17 通过：`test_oobe_protocol.py`、`test_fabric_sync_protocol.py`、`test_fabric_runtime_protocol.py`、`test_coordinator_runtime.py`、`test_fabric_admission.py`、`test_capture_target.py`、`test_research_session.py`、`test_oobe_store.py`、`test_oobe_completion_store.py`、`test_sdk_bluetooth_disconnect.py`、`test_sdk_gatt_lifetime.py`、`test_sdk_littlefs_path.py`、`test_sdk_littlefs_handle_attrs.py`、`test_fabric_att_payload.py`、`test_agent_queue_fairness.py`、`test_agent_card_state_protocol.py`、`test_agent_state_outbox.py`。
- Android `:buffer-app:testDebugUnitTest`：308 tests，0 failures / 0 errors；`:buffer-app:lintDebug` 与 `:buffer-app:assembleDebug` 成功。
- 权威 Framework 的 `fabric-session`、`fabric-portable`、`fabric-platform` 分别通过 10、8、8 项 `--locked --offline` host tests；portable `aarch64-unknown-none` release build 成功。
- Vela `goldfish-arm64` 在 `/tmp/buffer-audit-remediation-vela-20261018-lm` 隔离树完成全量 CMake/SDK build，最终 `nuttx_post_build` 成功；包含 Buffer app、系统链接与镜像生成。SDK 原 `.config` 和设备数据未触碰。
- `git diff --check` 通过。

以下未宣称验证：模拟器/设备端到端、Android instrumentation、BLE 射频、多节点 coordinator、云端 ASR/LLM、真实麦克风录音、S1 secure storage 与掉电恢复。模拟器按要求关闭，未重启。

## 本次审计相关代码与测试路径

- Vela C：`app/buffer_app/{buffer_app.h,buffer_app_main.c,buffer_fabric.c,buffer_wire.c,buffer_store.c,buffer_agent_model.c,buffer_agent_sync.c}`。
- Android：`foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/data/device/{VelaRuntimeProtocol.kt,VelaFabricEnrollment.kt,VelaCardStateProtocol.kt}`、`foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/data/local/BufferRepository.kt`；unit tests `foundation.fabric/apps/android/buffer-app/src/test/kotlin/com/mocharealm/foundation/fabric/buffer/{VelaRuntimeProtocolTest.kt,VelaCardStateProtocolTest.kt}`。
- Host tests/harness：`tests/{test_fabric_runtime_protocol.py,test_agent_card_state_protocol.py,test_agent_state_outbox.py,test_fabric_att_payload.py,test_agent_queue_fairness.py,test_research_session.py,agent_queue_fairness.c,agent_fixture_service.py}`。
- 本报告：`docs/finals/10-audit-remediation.md`。`docs/finals/09-independent-code-audit.md` 保持原样。

## 构建产物

- Android APK：`foundation.fabric/apps/android/buffer-app/build/outputs/apk/debug/buffer-app-debug.apk`，SHA-256 `d048d5c9d685cea207ab51bd9a5927cc4ae1a3a63f4a16269a4841bcf780367f`。已构建，未安装。
- Vela ELF：`/tmp/buffer-audit-remediation-vela-20261018-lm/build/nuttx`，goldfish-arm64；SHA-256 `b0da7a2a0b2b85007d3f67f42dd96b48607f01428696de7d358268b2a22ce919`。
- Vela raw image：`/tmp/buffer-audit-remediation-vela-20261018-lm/build/nuttx.bin`；SHA-256 `1ec31f1e76e0d396203494111501f8f244805fddfeb7ba2dc15f9b0a9307ac49`。

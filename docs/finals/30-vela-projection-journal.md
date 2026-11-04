# Vela 副本、专注与模式源事件

任务 05 的第三阶段。按用户授权修改代码并静态回顾，没有运行测试、构建、模拟器或实机。整个任务 05 仍未完成。

后续进展：计时器、闹钟、原始助手请求、专注提醒及本地投影修复已接入，见 [31 提醒源事件](31-vela-reminder-journal.md)。长期操作去重、全部入口覆盖及完整网络复制仍未完成。

长期操作去重的后续实现见 [32 操作历史](32-vela-operation-history.md)；全部入口覆盖和网络复制继续保持未完成。

## 写入范围

`buffer_source_events.c` 在原捕获 journal 上增加固定类型的投影提交。仅允许 `replicas/<合法 id>.json`、`focus.json`、`mode.conf`、`pending.action`、`pending.mode`，路径由类型和受限 ID 推导，不从事件 payload 接受任意目标路径。

- 副本的普通写入记录 `ReplicaDocumentObserved`，明确这是本机观察到的文档，不能冒充远端原始事件。本机 CRDT 操作显式记录 `CardActionPerformed` 和原 action，连同操作后的精确文档文本提交。
- 专注导入记录 `FocusStateObserved`；本机 start/finish/review 保存为 `FocusSessionChanged`，模式请求检查点保存为 `FocusModeRequestRecorded`。完整 session、operation 去重列表及 pending 标记保存在同一投影文本中。
- `mode.conf`、`pending.mode`、`pending.action` 的实际保存分别记录模式状态、模式请求和卡片请求。同内容保存不重复分配源事件。
- 请求确认后的删除也先提交源事件，通过 null 投影表示移除。卡片请求的清理函数改为返回错误；BLE 和旧 LAN 回执路径仅在持久清理成功后清除内存 pending，失败继续保留重试身份。

事件的 `capture_id` 为 null；实体关系以 `payload.projection_kind/entity_id` 表达。卡片原有 capture 关系保留在精确文档内，不把实体 ID 伪装成捕获 ID。父列表包含前一个源事件及该实体前一个事件，实体索引在 `event-journal/entities/<kind>-<id>.json`。

## 恢复与迁移

捕获原有的三字段 pending 保持兼容。新投影 pending 包含 envelope、projection_kind、projection_text、projection_index 四个字段，复用原文/指纹、投影、索引、head、清理意图的提交顺序。新旧 pending 互斥，共享 journal mutex 和源序号，不存在两个独立提交者。

恢复校验固定目标、实体、源 ID、事件 ID、序号、时钟、父引用、payload 与投影原文的一致性。投影和索引先于 head；head 已提交时只清理 pending，不重新写回旧内容。删除分支只 unlink 固定类型的文件并同步，不调用模式、专注或卡片动作 API。

旧副本、专注与模式文件在首次初始化扫描并记录 `LegacyProjectionSnapshot`；已存在的实体索引防止重启重复迁移。首次写入时仍检查后来发现的无索引旧文件，先保存旧快照。旧文件没有可恢复的用户操作历史，因此只声明兼容快照，观察时间不声称是原操作时间。迁移或提交失败不跳过坏记录、覆盖不一致的源事件或伪造成功。

JSON 投影读取在同一 journal 锁内恢复并读取；三个文本状态读取改为有界快照后解析，避免 init 解锁后直接 fopen 读到提交中的文件。锁顺序保持调用方的 capture/focus/runtime 锁先于 journal；journal 内部只使用底层文件 I/O，不获取这些上层锁或执行外部操作。

未启用 `BUFFER_VELA_SOURCE_JOURNAL` 的配置继续使用原文件写入路径，不声明已经支持源事件同步。

## 静态回顾范围与未完成工作

已逐段读取新旧 pending 的构造、校验、落盘、清理与故障返回，检查副本本机动作/远端合并、专注导入/本机操作、模式请求/确认及 BLE/LAN 回执的实际入口；未执行任何检查程序。

这只补齐上述文件的源事实记录。专注提醒、计时器/闹钟及其他用户操作还需逐项清查，不能据此勾选“全部相关操作已覆盖”。S1 私有存储、源事件传输与权限保留序号、父项补传、远端受限投影、blob 交付、容量策略和任务 06/07 均继续保留未完成状态。

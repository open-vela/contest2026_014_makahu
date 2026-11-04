# 专注会话、结束反馈与日程触发

2026-10-20。核对原专注功能后补齐的首版闭环。用户随后要求不做模拟器验收，本次不以模拟器运行或真实语音作为完成证据。

## 行为

- Vela 按住录音后说“我要开始写作业了”等当前开始意图，Agent 可调用 `focus_session start` 保存目标并切换专注。时长不是必填项，普通倒计时仍走原有工具。
- “结束专注”走 `finish`。结束时间与任务完成状态独立；未说明进展时保存 `review_pending`，提示可补充进展和感受，不编造反馈。
- `finish` 可以同时携带用户明确表达的反馈；已结束会话也可以 `review`。进展为 done/partial/not_started，体验为 good/tired/hard 或留空，另存剩余事项。
- 手机开始专注可选择已有待办，也可只填写目标。首页显示当前目标，提供结束回顾、历史与日程入口。切离专注模式会保存待回顾会话。
- 回顾可以跳过体验、稍后补充或开始新一段；历史反馈不会替换新会话。反馈“做完了”后仍需明确确认才完成关联待办。
- 剩余工作可仅保留在反馈、显式放回待办，或先创建关联的后续待办再进入已有日历日期/时间确认。后续任务按原会话去重；不会移动原来的日历时间块或自动挤占其他安排。

## 日程

读取用户授权的系统日历，提供未来七天的非全天、未取消事项供选择；列表一次显示至多一百项。每个选定 occurrence 可设置：到点提醒，确认后开始；到点自动开始；关闭。

只对用户选择的 occurrence 生效，不将整本日历自动视为专注任务。写入新任务日历成功后，也提供上述设置入口。Buffer 自己写入的日程通过现有日历回执恢复原待办关联，开始专注不会改变待办的已有状态。

调度在执行前重新读取 provider。日程被移动、删除、改名或改结束时间时取消旧的触发安排，需重新选择；不在此版自动追踪修改或批量订阅重复日程。读取权限撤销或 provider 出错时保留安排，之后重试。

自动开始仅在 normal 且没有活动专注时执行；不抢占专注、睡前或研究模式。提醒策略只显示通知，点击开始后再次核对日程及有效时间。过期或已开始的 occurrence 不会再次启动。使用非精确 AlarmManager 和既有轮询，不能承诺系统省电状态下按秒准时。

按日程开始的专注到该时间块结束后保存为待回顾，提供回顾通知；这不会自动完成待办。语音/手动无时长专注由用户结束。没有接入空闲时段推荐、任务依赖或自动重排算法。

## 持久化与双端

Vela 新增 `buffer_focus.c/.h`：有界、原子保存的会话与操作去重记录，依赖现有私有存储检查。工具须引用真实 session ID，过期操作拒绝执行；一次已成功操作的恢复不会创建第二段专注。未确认的模式变更使用原 durable mode outbox。

既有 `buffer.mode.change` / `buffer.runtime.state` 增加可选 `focus_session`。接收端检查字段、整数时间、状态及 mode 一致性；旧的无扩展消息保持兼容。Android 将 mode receipt、会话快照及事件放在同一 SQLite 事务中，重试旧 mode ID 不会重开已经结束的专注。并发或过期会话拒绝覆盖，不宣称有完整冲突解决界面。

语音工具的实际成功结果还写入不可变 processed capture，经既有分片/哈希/回执通道同步历史。后续开始、结束或反馈不会改写较早的会话结果。历史导入可以补齐历史，不能以旧 capture 改变当前模式。手动模式菜单复用同一专注 domain，目标显示通过内存状态刷新，不在 LVGL 刷新中读文件。

Android 会话、反馈、日程计划及提醒去重沿用 `runtime_settings` 和事件库，无需升级数据库表版本。能力发现、外置 Skill 动态加载和根据历史自动提出能力建议不在本次实现内。

## 主要入口

- Vela：`buffer_focus.c/.h`、`buffer_agent_assistant.c`、`buffer_agent_model.c`、`buffer_agent_sync.c`、`buffer_fabric.c`、`buffer_wire.c`、`buffer_ui.c`。
- Android：`domain/FocusSession.kt`、`BufferFocusCalendar.kt`、`BufferCareScheduler.kt`、`BufferRepository.kt`、`VelaRuntimeProtocol.kt`、`VelaProcessedCaptureProtocol.kt`、`BufferCaptureAgent.kt`、`MainActivity.kt` 和 `BufferScreen.kt`。

## 验证

- 最终源码主机回归：`python3 -m pytest -q --tb=short`，38 passed（93.08s），日志 `/tmp/buffer-focus-all-host-final.log`。包含真实 SDK mbedTLS + 本地 HTTPS fixture 的 start/finish、模型续答失败恢复、工具检查点、不可变历史，以及原 runtime、录音与 LVGL 回归。
- 完整 goldfish-arm64 CMake 构建：最终 `post_build` 成功，日志 `/tmp/buffer-focus-native-final2.log`。使用 SDK AIDL 编译器重新生成隔离构建树的 Binder 输出；未改动 SDK 源码。
- Android 新增会话与 provider 回归覆盖持久恢复、事务失败回滚、历史反馈、旧模式重放、日程时间窗、一次触发、原待办关联、提醒与自动策略、日程变更及权限撤销。最终完整 Android 单测 333 项通过，0 failures/errors/skipped；`lintDebug` 与 `assembleDebug` 均通过，日志 `/tmp/buffer-focus-android-complete.log`。
- 未进行本版真实语音、云端模型意图准确率、BLE 往返或系统省电/掉电实机验收。Windows Android 仅启动，用户要求停止模拟器验收后未安装新版 APK、未操作页面；未启动 WSL 模拟器，也未启动 Vela 模拟器。

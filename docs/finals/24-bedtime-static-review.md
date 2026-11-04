# 睡前策略与夜间亮度：静态审核

日期：2026-10-23。更新结论：恢复目标后已修复下述四项缺口，并完成实现调用链的静态回顾；任务 04 可标记代码实现及静态回顾完成。运行验收仍未进行。最初审核只读，之后本轮修改了应用、SDK 源码和 S1 defconfig，并保存 SDK 补丁；没有运行测试、构建、模拟器或设备。

依据：[产品要求](../app.md)第 6 节、[手机与 Vela 文档](../buffer_phone_openvela_docs.md)第 3.10 节及夜间低亮度要求。以下情景均为沿源码分支推导，不是运行结果。

## 原审核发现（以下描述修复前状态）

### 1. S1 实际背光调节没有接通（P1，要求缺失）

`app/buffer_app/buffer_backlight.c` 仅在 `CONFIG_BUFFER_VELA_NIGHT_DIM` 且 `CONFIG_LCD_MAXPOWER > 1` 时编译调光路径；Kconfig 默认关闭，要求板级驱动具有真实多级背光能力。SDK 当前 `nuttx/.config:1384` 为 `CONFIG_LCD_MAXPOWER=1`。S1 的 `nsh_minidisplay/defconfig` 选择 `LCD_SUPPORT_ILI9341`，厂商 `disp2/soc/Makefile:18` 编译 `ili9341_lcd_spi.o`。

实际板级回调位于 SDK `vendor/allwinnertech/chips/r528/drivers/rtos-hal/hal/source/disp2/soc/ili9341_lcd_spi.c:506`：`ili9341_backlight()` 直接返回 OK，没有操作背光；同文件初始化在 638–643 行将 PD20 配成 GPIO 输出并拉高。因此当前应用会报告不支持调光，不能声称 S1 已实现夜间低亮度。

补充纠正：该厂商的 framebuffer `sunxifb_setpower/getpower` 确实转接 LCD `set_bright/get_bright`，但 `disp_lcd_set_bright()` 在没有有效 PWM 或面板亮度回调时仍更新内存值并返回成功，`get_bright()` 读取的也是该值。仅切换到 framebuffer 并读回数字，不能证明操作了物理背光。需要核对所选面板、PWM 通道与引脚，接通实际驱动，再保留应用已有的恢复日志和错误反馈。其他面板配置中出现 PWM 参数，不是 S1 当前选中驱动已实现的证据。

### 2. 日程切回固定时间可重复触发（P2）

位置：Android 快照 `data/local/BufferRepository.kt:547–550`、`:596`；`domain/BedtimePlanner.kt`。

日程分支写入 `bedtime_triggered:<日期>`，固定分支却仅将 `KEY_BEDTIME_LAST_TRIGGERED_DAY` 交给 Planner，不读取这个日期账本。情景：某晚日程策略已触发，用户退出睡前模式，随后改成当天已到时的固定时间。策略保存清空 pending 等键，但日程触发并没有更新固定分支使用的 last-triggered 键；Planner 将当天再次判断为待触发并切回睡前模式。固定分支写入共享日期账本，并不能弥补它从未读取该账本的问题。

待完成：统一两种策略对“本晚已经自动触发”的读取和写入，明确午夜后的夜晚归属及切换时区语义；核对关闭、重新启用和用户手动退出后的行为。

### 3. 已删除的 processed capture 分段可能导致整笔导入回滚（P2）

位置：Android 快照 `data/local/BufferRepository.kt:2468–2469`、`:2496`、`:720–721`。

`VelaProcessedCaptureProtocol` 接受 `deleted` 分段。导入循环对已删除分段或已有 CRDT 墓碑的卡片跳过 `insertCard()`，之后仍无条件调用 `saveBedtimeAssessment()`。如果该分段携带非空且有效的 bedtime 建议，同时本地没有相应卡片，该方法的 `requireNotNull(findCard(cardId))` 必然抛错。异常发生在整笔 capture 导入事务内，将阻止同一记录中其他正常分段导入，重试相同记录也不能自行消除该条件。

待完成：明确已删除内容的评估保留策略；不存在可展示卡片时不执行内容绑定。不能为绕过异常重新创建已删除卡片；原始分类建议仍应按事件语义保留。

### 4. 无当天日程时，回退时间没有重新检查次日凌晨冲突（P2）

位置：Android 快照 `domain/BedtimePolicy.kt:66–87`。

解析器先以 `sleepTime - 1h` 扫描日程，最后才在当天无日程时将 target 替换为 fallback。情景：期望入睡 23:00、回退 23:30，当天无日程，次日 00:15–01:00 有日程。初始 target 为 22:00，扫描用午夜作为上界，因此跳过 00:15 的事项；随后直接得到 23:30–00:30 的回退窗口，与该事项重叠。注释要求处理收束窗口内的后续事项，但 fallback 分支绕过了这项处理。

待完成：先确定本晚采用的基准时间，再扫描实际收束窗口内的日程；顺延后继续处理相接的事项，并保留最终导致顺延的来源及窗口上限。

## 已看到的实现路径及证据边界

- `BedtimePolicy` 保存关闭、固定、所选日历、期望入睡时间、回退时间及策略修订；`BufferBedtimeCalendar` 在后台读取 Instances，有取消信号、缓存、内容变更代次和权限失败回退。
- `BufferCareScheduler` 在 executor 中读取日程，入库前重新获取当前时间；repository 对日程计划校验策略修订和时区。固定与日程分支均避让 focus、rabbit_hole。
- `BedtimeAssessment` 将语义建议绑定到内容哈希；人工睡前可读确认另存内容哈希。`BedtimeDeck` 排除高刺激标记、稍后、研究、删除及完成状态，限制长度并收窄操作。高刺激问题建议使用已有稍后选择器，取消不写入计划。
- Vela 的卡组元数据包含可读性和指纹，缓存及副本路径有睡前筛选；通用多级 LCD 适配包含先持久化恢复信息、退出恢复、手动亮度变化让权及故障反馈。这些代码不能补足上述 S1 驱动缺口。

上述是原审核的实现范围。下节记录缺口的修复与再次回顾；硬件亮度、CalendarProvider、后台行为等运行验收仍未进行。

## 修复与再次回顾

1. **S1 背光。** SDK 新增默认关闭的 `LCD_ILI9341_R528_PWM_BACKLIGHT`，限定 R528、PWM、多级 LCD 以及没有 sys_config 引脚覆盖的配置。S1 defconfig 显式启用该配置、`LCD_MAXPOWER=255` 和应用夜间调光。板级 callback 通过 SoC 已有映射的 PWM4/PD20，以正常极性和 50 kHz 周期写入 duty；板级初始化通过 LCD setpower 建立真实输出和一致的报告值，不再在 PWM 初始化后将引脚改回 GPIO。PWM 包装函数返回实际配置错误，ILI9341 将 callback 错误传回 ioctl，失败时不更新报告级别。没有使用 Sunxi framebuffer 内存亮度值作为调光依据。
2. **每晚触发账本。** `BedtimePlanner` 为所有 pending、missed 和 today 候选调用 repository 提供的 `wasTriggered`；repository 同时核对旧固定触发键与两种策略共用的 `bedtime_triggered:<夜晚日期>`。固定时刻与日程都把上午时刻归到前一晚。旧固定日期在策略替换时按旧时刻迁移一次，并保存稳定 night 键，避免后续切换策略又用新时刻重解释旧触发。保存策略同时清理两种调度的 pending/retry 状态。自动触发与模式写入同属数据库事务；已处于 bedtime 时不重复切换模式。
3. **删除分段导入。** `saveBedtimeAssessment` 仍校验建议结构，但只给存在且未删除的卡片绑定内容哈希；缺少物化卡片时清除派生评估，不抛出 requireNotNull、不复活卡片。完整建议仍保留在事务中的 `ClassificationProposed` 事件。调用点包含 processed capture 导入与手机分类提交；两者仍使用原事务及回执逻辑。
4. **实际回退窗口。** 日程解析先确定期望时刻或回退基准，然后扫描当天及实际收束窗口内的事项。窗口顺延后继续处理后续重叠事项，跨过次日中午的上限则省略该晚。期望 23:00、回退 23:30、次日 00:15–01:00 日程的原问题分支，现在将目标推到 01:00，而不会保留冲突的 23:30–00:30 窗口。来源改为保留最后 16 个导致顺延的 occurrence，包含最终决定目标时间的事项。这是静态分支推导，没有执行该情景。

再次回顾覆盖：设置保存和旧数据兼容 → CalendarProvider 权限/失败回退与缓存失效 → 策略修订/时区核对 → 专注及研究避让 → 每晚幂等与重启读取 → 可解释语义/人工内容确认 → 稍后选择取消与已存在计划排除 → 两端卡组指纹/过滤 → 背光 worker/ioctl/PWM/失败反馈 → 日志恢复与外部亮度写入让权 → 服务退出。

SDK 改动归档为 `patches/openvela/0010-r528-ili9341-pwm-backlight.patch`、`0011-ili9341-report-backlight-errors.patch`、`0012-gemini-s1-night-backlight-config.patch`；README 区分 vendor 与 NuttX 根目录。0012 只保留本次新增配置，不捎带板级既有未提交改动。

完成标记仅指源码和配置闭环。当前生成的 `nuttx/.config` 仍可为旧的二值配置，现有固件未刷新；实际亮度、时序、CalendarProvider 和后台唤醒仍需后续授权的运行验收。

后续交叉回顾补充：背光日志曾调用只允许 LittleFS 的 Agent 私有写入器，导致 S1 普通存储配置不能保存恢复值。现已拆为非敏感的 1 KiB 专用日志：排他创建、完整写入并 fsync 后才调光，已有日志不覆盖；失败保留原亮度。读取检查普通文件和固定字段，不要求凭据级文件权限。私有凭据和源事件 journal 仍使用原有 LittleFS/属性门禁。此修改同样只做静态回顾，未运行硬件。

## 后续任务 05 的仓库边界证据

已只读核对独立源仓库 `/home/simon/projects/mocha/foundation.fabric`：`crates/platform/src/messages.rs` 的 `AuthorizedMessageChannel::from_plan` 校验 SessionPlan、端点、消息模式及容量；`ReliableMessageTransport::send` 明确只表示可靠载体接受，不能代替应用持久化回执。`crates/storage/src/lib.rs` 的 registry outbox 与 peer revision 属于框架注册表同步，不是 Buffer 原始事件历史。

参赛快照 Android 的 `VelaCardReplicaProtocol.kt` 当前发送卡片/CRDT 文档；`BufferRepository.appendEvent()` 为导入过程新建本地 event ID、actor 和父事件。`importProcessedCapture` 能保留捕获结果，不等于保留源事件身份及因果历史。任务 05 仍需要逐项核对和实现，不能用已有 ReliableMessages 或 CRDT 同步判定完成。

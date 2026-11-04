# Vela 日历与共享 CRDT 文档

2026-10-20。接续专注会话功能，落实 Vela 日历、带日期待办的日历投影，以及手机和 Vela 的内容同步。未启动或操作模拟器。

## 用户可见行为

- Vela 的日历入口放在待办旁边。默认展开周一开头的六行月历，上滑下个月、下滑上个月，标题旁也有换月按钮。
- 日期下显示两端圆头的分段条，颜色由事项 ID 稳定生成，长度按事项在当天占用的时长分配。全天事项使用当地实际一天长度。小屏每格最多十二段，超出部分聚合为灰色；日程本身不会因此丢弃。
- 点击日期收起月历，显示当天可滚动的日程列表；点击日期标题展开月历。列表每页八项，支持前后翻页，日期内全部事项均可访问。后台刷新保留当前页锚点和滚动位置。
- 带明确日期或时间的待办直接投影到共享日历，不再为同一待办创建另一份独立事实。完成状态、标题和日期均来自该待办。手机待办详情可设置/修改/清除日期，选择全天或具体时间与时长。
- 初始文本支持年月日、月日、今天/明天/后天、周几和下周、冒号时间以及中文“下午三点”等。相对日期按捕获时间解析；只有时间时使用捕获当天，只有日期时为全天，有时间且未指定时长时默认一小时，可在详情调整。模糊表达不擅自生成日程。
- 手机新增共享日历入口。系统日历读取仍需用户授权；没有读取权限时，带日期的待办照常可用。已确认写入系统日历的 Buffer 事项使用关联 ID 去重。
- Vela 换月会请求手机读取该六周窗口的系统日程（包括重复事件的 occurrence），再同步共享文档。Provider 删除、取消、移出窗口的旧 occurrence 会被标为删除；恢复出现的系统事件使用新 incarnation ID，不清除旧墓碑。
- Vela 断连时显示缓存及断连提示。手机未授权系统日历、Provider 读取失败分别显示提示，不把权限失败冒充空日历。日期与时间显示跟随设备配置的本地时区，设备需要正确的时钟和时区配置。

## CRDT 的范围与规则

卡片和日历内容使用 C/Kotlin 两端实现的 state-based CRDT：字段级 Lamport LWW register map，比较 `(counter, actor)`；删除使用只增长 tombstone。两端分别修改不同字段时可以合并；同一字段并发修改按上述确定顺序取胜。数组和 schedule 对象各作为一个字段，不提供数组内部逐元素合并。

同一文档支持重复、乱序、重连后重新传输；相同 stamp 却携带不同值会被拒绝。已经删除的 ID 不允许被旧消息或并发编辑复活。手机在合并前先把本地 SQL 修改冻结为寄存器，再将合并结果投影回 SQL；Vela 在已有存储锁下合并并原子写入投影。规范化 JSON 用于手机快照指纹，避免字段顺序造成无意义的同步。

`buffer.crdt.part` / `buffer.crdt.reply` 复用 Fabric 授权通道、SHA-256、Base64 有界分片与回执。Vela 的原子文件与手机 SQLite runtime_settings 保存文档，不清空既有数据。旧数据按访问惰性生成文档；旧的接收适配器与尚未完成的迁移消息仍保留，已有 CRDT 文档不被旧式完整快照覆盖。Vela 本地新建卡片保留原始 processed capture，确认手机已导入后才发送后续 CRDT 修改。

内容合并不再以整卡 revision 大小作为胜负依据。原 transport 回执、原始捕获的导入记录、协调器租约及专注模式的执行/确认协议仍各司其职；本次没有把实时模式执行、录音或设备配对改成 CRDT。手机系统日历是授权读取适配器，新增/删除系统事件仍沿用显式确认的写入操作，不把任意 CRDT 更新直接执行为系统日历写操作。

每个文档最多 32 个字段，文档预算 60 KiB，传输信封约 64 KiB；超预算会拒绝并保留原有数据，不静默截断正文。Vela 将系统日历投影单独存放在 calendars 目录，避免日历数量挤占普通卡片展示扫描。

## 主要实现

- Vela：`buffer_calendar.c/.h`、`buffer_calendar_ui.c`、`buffer_crdt.c/.h`、`buffer_crdt_sync.c/.h`，以及 `buffer_replica.c`、`buffer_fabric.c`、`buffer_app_main.c` 和主页接入。
- Android：`TaskSchedule.kt`、`CalendarEntry.kt`、`BufferCrdt.kt`、`BufferCalendarStore.kt`、`VelaCrdtReceiver.kt`，以及 repository、Fabric、卡片详情和日历入口。

## 验证

- 主机回归：40 passed，日志 `/tmp/calendar-host-final.log`。包含真实生产 C CRDT、真实 SDK mbedTLS 的双向分片与合并、拒绝未经授权的写入、独立标题/完成/日期修改、删除后重放，以及原同步/专注/录音回归。
- 真实 LVGL 宿主组件使用 ASan/UBSan，覆盖 320×480 和 240×320、35 项日期聚合与翻页、闰年、月份切换、真实指针滑动、折叠/展开及滚动保留。没有用模拟器截图代替验证。
- Android：343 项单测，0 failures/errors/skipped，日志 `/tmp/calendar-android-complete.log`；完整 `lintDebug`、`assembleDebug` 通过，日志 `/tmp/calendar-android-complete.log`。新增测试覆盖 CRDT 合并/墓碑、日期归属、跨 DST 全天、Provider occurrence 更新、重复分片、缺口及错误哈希。
- Vela：goldfish-arm64 全量 CMake 构建及 post_build 通过，日志 `/tmp/calendar-native-complete.log`。
- 未进行新版设备安装、真实手机 CalendarProvider/BLE 联调或模拟器验收。本文的“通过”指上述构建和隔离自动化验证。

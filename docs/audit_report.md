# Buffer Android / Vela 审计报告

审计日期：2026-09-20  
审计范围：`docs/app.md`、`docs/buffer_phone_openvela_docs.md`、Android `buffer-app`、Gemini S1 Vela `buffer_app`。

## 结论

Android 与 Vela 已实现主要捕获、分类、展示和同步路径，但当前证据不足以认定文档要求全部完成。编译成功仅证明构建可用；下列未闭合项及 Gemini S1 实机验收仍需继续处理。

## 复核新增的未闭合项

- 已补手机对 Vela 随机挑战的 HMAC 证明，Vela 在发送设备证明前校验手机持有配对令牌。配对及后续业务数据仍是明文 TCP，缺少加密与逐条消息认证，尚未满足完整安全传输要求。
- 日历确认已支持显式日期/时间、时长、优先级和日历选择；已补应用进程内跨 Activity 串行执行、关联日程查找和可重试本地提交；真实 provider 写入/撤销及强杀恢复仍需设备验收。
- 文档 3.12 的提醒配置及延后/跳过操作已接入卡片同步；版本去重及存储失败重试通过主机测试，两端实机时序仍待验收。Vela 离线重启保留提醒间隔，但不保留上次延后的剩余时间，重连后重新对齐手机。
- 分类接受、编辑、拒绝路径已实现，并通过纯状态逻辑测试；Robolectric 下的生产 SQLite 事务已通过失败回滚测试；真实设备断电恢复、后台任务与界面操作并发及界面流程仍需集成验证。已确认的分类不允许被过期编辑/拒绝操作覆盖。
- Android 桌面 Widget 和常驻通知快捷捕获入口已实现；添加 Widget、锁屏点击、权限拒绝、界面重建及真实录音后台行为仍需设备验收。本轮 `adb devices -l` 未发现已连接设备。
- 睡前展示已增加用户标记和短内容筛选，但不能据此认定自动识别高刺激内容、明天/周末重新提醒及通知降噪全部完成；外部日历/健康写入的崩溃恢复仍缺少运行验证。跨午夜补触发已有持久化调度实现和纯 JVM 逻辑测试，但系统闹钟、实际进程重启及存储故障仍需 Android 设备验证。

- Vela TCP 建连现已对每个候选地址设置 5 秒期限；DNS 解析仍由系统控制，多个候选地址累计耗时也不等于单个 5 秒期限。专注 timer 已移到独立线程，不再依赖网络操作返回；两端提醒配置和延后操作已加入同步，实际网络延迟与手机后台调度影响仍需设备验证。

这些项目保留在当前开发范围内；后续须用实现和对应行为验证逐项关闭。

## 功能矩阵

| 能力 | 当前结果 | 证据或限制 |
| --- | --- | --- |
| Vela 身份初始化、持久化 6 位配对码 | 源码完成 | `/data/buffer/device.id`、`pairing.code`，屏幕持续显示配对码 |
| Vela 独立 Wi-Fi 配置、DHCP、重连 | 源码完成 | `buffer_app wifi`、WAPI 配置和后台重连；未在 S1 无线芯片上实测 |
| Android 扫描 Vela、输入配对码、保存令牌 | 源码完成 | UDP 48886 发现、TCP 48887 配对；错误码和失败限流已覆盖 |
| 数据面认证与重连 | 部分实现 | TCP 48888，已补双方令牌持有证明；配对/业务加密与逐条消息认证未完成 |
| Vela 状态事件与重试事件 | 源码完成 | Android 事件簿幂等记录 `ButtonPressed`、变化的 `DeviceStatusUpdated` 和 `UploadRetryRequested` |
| 按键录音、临时文件、WAV pending queue | 源码完成 | 最大 8 秒、队列上限 8 条、重启后保留未确认 WAV |
| Capture 幂等上传 | 源码完成 | `capture_id` 重复上传返回原卡片，不重复创建 Capture |
| 卡片缓存、3–5 张卡片浏览、stale 标记 | 源码完成 | 本地 `cards.json`，上一张/下一张，断网显示旧卡片 |
| Spark / Question / Task / Care / Weekly 卡片 | 源码完成 | Android 本地分类和模式过滤 |
| 答案卡显示、重新提醒、归档、稍后、完成、兔子洞 | 源码完成 | `answer` 字段已贯通；Vela 五个操作按钮和持久化操作回执 |
| 专注、睡前、兔子洞模式 | 部分实现 | Vela 本地计时和模式切换已实现；已补提醒配置同步；高刺激内容后续提醒、通知降噪及 Android 后台闹钟实测未闭合 |
| Android Widget / 通知快捷捕获 | 已实现，待实机验证 | 复用本地捕获面板；manifest/资源打包检查不能代替桌面与锁屏测试 |
| Android 本地事件库、离线音频、手动转写 | 源码完成 | SQLite append-only events、音频 blob、人工转写入口 |
| 可选远程转写和分类 | 已实现，待集成验证 | 接受/编辑/拒绝、旧卡片操作补齐及后台覆盖保护已有实现；状态逻辑测试通过，数据库与界面并发未实测 |
| 答案箱、研究笔记、外部链接、标签和关联卡片 | 源码完成 | 物化卡片和对应事件均已写入 |
| 日历写入、确认、撤销和回滚 | 部分实现 | 已补时间/优先级/日历确认、进程内串行执行与恢复逻辑；provider 和强杀测试待完成 |
| Health Connect 本机授权写入 | 部分实现 | 已有授权和写入路径；外部写入与本地回执之间的崩溃恢复仍需验证 |
| Fabric Hub ability | 源码完成 | `app.buffer` ability 已注册；Vela LAN 数据面独立可用 |

## 本轮修复

- Android 接入录音时长/文件大小上限和设备错误回调，只结束对应的当前 MediaRecorder；应用侧及时收尾，旧回调不会结束新会话。时长元数据不超过配置上限。依据 [MediaRecorder 文档](https://developer.android.com/reference/android/media/MediaRecorder#setMaxDuration(int))，上限停止是异步的，回调后仍走停止/释放流程，不直接读取未完成文件。
- 录音停止、释放、入库、失败保留、缓存清理统一到 `RecordingFinalizer`，删除重复异常分支。停止或保存失败进入原始音频恢复路径；持久化均失败时保留唯一缓存，释放失败也保留原文件；清理失败不撤销已保存捕获。界面刷新失败不再误触发重复的恢复副本。

- Android 新增“Buffer · 记下此刻”Widget，常驻桥接通知也提供“记文字/录语音”；共用显式、不可变 PendingIntent 和主界面捕获面板，不经后台广播启动录音。
- 快捷入口复用现有文字保存与按住录音流程。未按录音按钮不请求麦克风，授权成功不自动开始；首次快捷启动暂不弹出局域网/Hub 权限，允许先做本地捕获。重复 Intent 复用 Activity，恢复文字草稿与面板状态，打开入口不清空已有草稿。
- 录音手势取消通过 finally 收尾，Activity 进入后台也结束并保存录音；初始化失败释放已分配的 MediaRecorder。新增应用 README，说明 Widget 添加、通知入口和验证限制。

- 分类接受/编辑/拒绝统一为 `ClassificationReview`，使用同一数据库事务更新卡片与追加 `ClassificationAccepted` / `ClassificationEdited` / `ClassificationRejected`。编辑支持五类卡片及可选健康类别；拒绝保留原始捕获、摘要、答案、研究笔记、标签和关联，转为待重新分类卡片，不提供日历或健康写入操作。
- 旧提案在读取时补齐编辑/拒绝入口，无需重建数据库；拒绝后仅可重新编辑选分类，不能直接接受旧提案。重复决定不重复追加事件，保留用户的稍后/完成/归档状态。
- 分类确认比对用户看到的卡片快照；后台已更改提案则要求刷新。未转写的语音不能确认，用户分类决定加入后台分类覆盖保护事件集合；确认分类本身不调用日历或 Health Connect。
- 删除仓库中重复的分类状态/操作映射和仅支持接受的专用处理。改分类后旧答案仍保存在原数据中，但只在问题卡展示及下发，避免旧问题答案误出现在新待办/照顾卡上。

- 睡前首页与 Vela 下发复用 `BedtimeDeck`：最多 5 张，照顾卡优先；其他短问题/灵感/回顾需用户标记“睡前可读”，问题需已有短答案。排除任务、深挖类型/候选、稍后、归档和完成项，以及摘要超过 280 字符或答案超过 400 字符的内容。此规则是显式选择和长度限制，不是语义上的刺激度模型。
- 睡前展示只保留稍后/归档/完成操作，移除研究笔记、外链和关联入口；手机首页使用简化卡片，不再出现生成答案/研究编辑表单。原卡片数据保留，用户主动打开其他栏目仍可编辑，规则不等于全局操作锁定。
- 删除原来仅按类型与状态筛选的睡前 SQL 分支，采用单一展示规则，避免手机首页与 Vela 各自采用不同规则。

- Vela 专注提醒由独立线程每 100 ms 推进，网络线程不再承担计时；新增线程启动失败会停止已启动服务，正常退出会 join 并清除线程状态。计时仍使用共享运行时锁，不能据此承诺存储长时间持锁或实际调度过载时完全无延迟。
- 清理同步线程旧计时调用、计时函数公共声明和发现接收中不可达的错误分支；将计时函数设为文件内部函数。删除未参与构建的 Windows `Zone.Identifier` 标记，新增忽略规则，排除 NuttX 对象文件、依赖标记及 Python 缓存。

- Android 配对响应和数据连接改用有字符上限的流式读行，在追加超限字符前报错；接受 LF/CRLF，拒绝未换行就 EOF 的截断消息。配对仍限制 16 Ki 字符，数据消息仍限制 1,200,000 字符。此限制按 UTF-16 字符计，不是 UTF-8 字节上限。
- Android 为客户端工作任务增加异常边界，断连、超时、截断行和协议处理异常由该连接关闭并记录，不再直接逃出工作线程。

- Vela TCP 建连改为非阻塞 connect + poll + SO_ERROR 校验，每个候选地址限时 5 秒；被信号打断不会重置期限，成功后恢复原有标志并检查读写超时设置是否成功，失败保留具体错误码。
- 独立 UDP 发现线程与 TCP 配对处理分离，慢配对客户端不再阻塞扫描；服务退出先停止并 join 发现线程，再关闭其 socket 和销毁锁。
- 配对完整请求也使用单调时钟 5 秒期限，持续发送少量字符不能延长占用时间。发现与配对的 4 KiB 请求缓冲区移到堆上，避免仅一个局部数组就耗尽 S1 默认 4 KiB 线程栈。

- Vela 不再回答 `buffer.phone.discover` 并冒用已配对手机身份，避免重连时将 Vela 的地址写成手机地址；`buffer.discover` 的设备发现响应保留。
- 寻找手机改为单调时钟限定总计 250 ms，使用 poll 与非阻塞接收，连续无效数据包不再无限延长发现循环。TCP 建连的期限另见下项。

- Vela 卡片缓存提交返回存储错误，失败保留旧 deck 并标为 stale；错误贯通同步返回值，避免被后续成功状态覆盖。刷新按卡片 ID 保留阅读位置。
- Vela 首次设备 ID 改为 128 位随机标识；已有合法 ID 保留，损坏文件或过小输出缓冲区明确报错，避免静默更换或截断身份。
- Vela 检查 `hello_ack` 的手机 ID、协议和 ability；此项不代表双向认证已完成。
- Vela 解析、缓存和显示 Android 下发的答案字段，并加入“提醒”物理操作。
- Android 分类后台任务现在会识别答案、研究、标签、关联、日历、健康和卡片操作等用户触碰，避免远程分类结果覆盖用户修改。
- 日历写入改为选择可写日历并加入外部事件回滚；移除日历时，系统事件已被手动删除也按幂等成功处理。
- Android 待处理音频计数改为读取未转写数据库记录和恢复队列，不再把已完成转写但仍保留的原始 `.bin` blob 误报为待处理。
- Vela 上传流程增加“上传中/已上传/失败待重试”反馈，并在 Android 事件簿记录设备状态和上传重试请求。
- Vela 数据连接的传输错误现在会返回给后台同步线程；断开连接时立即进入重连计数，避免把坏连接误判为同步成功。
- Android 周报的兔子洞候选现在与统计窗口一致，只取最近 7 天创建的卡片；同一 Vela、同一录音和同一错误详情的重复重试通知只记一条事件。
- Vela 每次同步前刷新待上传计数，独立 `sync` 入口上报的设备状态不再使用旧队列值；卡片操作和模式切换改为先写入持久化待处理文件，再让同步线程读取。
- Vela 模式 ACK 只有在 `mode.conf` 成功写入后才会清除待处理模式，瞬时存储写失败会保留并继续重试，重启不会回退到旧模式。
- Vela 模式和卡片操作 ACK 现在在同一把运行时锁内清理旧待处理文件，新操作不会被旧 ACK 误删；手机模式先成功落盘再更新 Vela 内存状态。
- Vela pending 元数据现在持久化 `uploading_metadata`、`uploading_blob`、`waiting_ack`、`uploaded` 和 `failed_retryable` 状态；本地读取、编码和请求创建失败也会发送 `UploadRetryRequested`，且重试事件必须收到匹配 ACK 才视为成功。
- Vela 录音只有 WAV 和元数据都提交成功才进入队列；WAV 头、刷新、关闭和元数据完整性都会检查，异常响应或元数据读取失败会保留队列并触发重试。
- Vela 对设备状态、模式、卡片操作和卡片刷新严格校验协议回执；收到错误或异常响应不再误报同步成功。
- Vela 收到 Capture ACK 后逐项检查本地 WAV、元数据和临时文件的清理结果；远端已接收但本地清理失败时会明确提示并保留待处理状态，避免把本地残留误报为完全完成。
- Vela 专注模式的屏幕摘要持续显示已专注时长和下一次照顾提醒，即使手机暂时离线也能看到本地计时状态。
- Android 开机广播在已获得局域网权限时自动拉起前台 Vela 桥接服务，手机重启后无需先打开 Buffer 界面即可继续被 Vela 发现和同步。
- Android 收到 Vela 原始音频时先保存为未确认的待转写卡；如果用户在转写完成前执行了稍后/归档等操作，转写和分类不会覆盖该用户状态，恢复文件的元数据提交失败也会清理已提交的孤儿音频。
- Android 本地长按录音设置 30 秒和 512 KiB 边界，避免超过协议上限后留下未登记的大文件缓存。
- Android 转写、分类和答案服务的响应读取有明确大小上限；超限响应会按服务失败处理，保留原始音频或分类重试状态。
- Android 音频转写完成后若保留了用户先前的稍后/归档状态，会继续提供确认分类入口，不会因后台转写丢失用户确认路径。
- Android 写入日历前要求用户明确选择日期和时间，并可选择时长、优先级和有写权限的日历；确认选择写入 `CalendarWriteRequested`，优先级也保存到外部事件描述中。未选时间不能提交，取消不执行插入；当前 Activity 内的操作锁抑制重复点击，不代表跨 Activity 或进程的持久化去重。
- 移除根据“今天/明天”猜测时间的 `calendarStart` 和旧的消息/单选列表组合确认框，改为完整确认表单；明确拒绝过去时间及夏令时空档/重复时刻。
- 修复准备日历失败时在工作线程修改 UI 操作集合的问题；日历处理结束统一释放锁，失败回执本身写入失败也不会阻止清理。若外部事件可能保留，会提示先检查手机日历，避免盲目重复添加。
- Android Health Connect 同一条卡片增加已写入回执和进程内去重保护，重试使用稳定的 `clientRecordId`，不会因重复点击重复推进本地状态。
- Android 睡前调度统一为 `BedtimePlanner`：持久化待触发日期、固定重试期限和原计划触发时间，15 秒轮询不再无限推迟闹钟，跨午夜及延迟送达时保留原待触发日。退出专注即补触发，多天未处理时合并为最近到期日；禁用或修改时间清理旧待处理状态。
- 删除调度器两套重复日期计算及独立“排到明天”方法，专注延后/跳过操作统一调用同一睡前调度入口；移除不再使用的仓库 getter/标记方法和常量。状态落盘失败保留重试标记，避免 SharedPreferences 内存已更新导致后续跳过磁盘重试；此故障路径尚未做设备注入验证。
- 保留并核对了 Capture、卡片操作、模式切换的持久化幂等回执和 Vela 重启恢复逻辑。

## 构建验证

Android：

```text
bash gradlew :buffer-app:assembleDebug :buffer-app:lintDebug :buffer-app:testDebugUnitTest
BUILD SUCCESSFUL
```

`testDebugUnitTest` 实际执行 143 个 JVM 测试（79 个纯逻辑测试、64 个 Robolectric HTTP/调度/协议/数据库/配置测试），0 失败/0 错误：

| 测试类 | 数量 | 验证范围 |
| --- | --- | --- |
| `CareSchedulerDatabaseTest` | 8 | 调度/操作异常恢复、投递重试、仓库重开、取消旧提示、回执失败、无效动作忽略 |
| `BedtimeDatabaseTest` | 3 | 执行标记失败回滚、专注延迟重开恢复、v14 睡前配置迁移 |
| `ModeTransactionTest` | 6 | 模式/睡前/延后/配置事件失败回滚、旧模式重试、v13 配置迁移 |
| `WireActionTest` | 4 | 真实 JSON 请求/ACK、非法 ID、回执失败与重试、ID 绑定 |
| `CardActionDatabaseTest` | 4 | 并发操作去重、回执失败回滚、操作 ID 绑定、旧重试保留新状态 |
| `AudioCaptureDatabaseTest` | 10 | 并发入库、主音频/备用副本保留、冲突保护、临时文件清理、重开恢复及失败重试 |
| `TranscriptionDatabaseTest` | 3 | 双实例首次转写竞争、失败回滚和音频重试、迟到转写保护 |
| `PhoneAuthenticationTest` | 3 | 独立 HMAC 向量、双方挑战与身份绑定、非法挑战/身份拒绝 |
| `AnswerDatabaseTest` | 4 | 手动答案/归档保护、过期响应拒绝、答案事件失败回滚 |
| `AnswerHttpTest` | 4 | 请求数组、未解优先、非法字段类型、答案/响应上限与 503 |
| `ClassificationHttpTest` | 4 | 实际回环 HTTP 请求、字段类型/置信度、畸形分段、503 与响应上限 |
| `ClassificationLeaseTest` | 3 | 双实例领取、过期成功/失败结果隔离、领取回执失败回滚 |
| `ClassificationDatabaseTest` | 5 | 接受/拒绝/编辑后保护、过期快照拒绝、两个独立仓库实例并发 |
| `RepositoryTransactionTest` | 6 | 日历创建/删除、健康和分类事务失败回滚、旧回执归属 SQL、专注配置重开恢复 |
| `FocusReminderConfigTest` | 10 | 默认配置、间隔/延后上下界、倒计时上限、提示剩余时间、过期与时钟回退 |
| `BoundedLineReaderTest` | 6 | 连续消息、LF/CRLF、长度边界、无限输入截断、EOF 和非法参数 |
| `BedtimePlannerTest` | 8 | 固定重试期限、跨午夜、延迟闹钟、同日幂等、禁用与改时间、多天合并 |
| `BedtimeDeckTest` | 6 | 显式标记、短答案、排除深挖/任务、保留原始数据、重分类后的展示 |
| `CalendarReceiptsTest` | 10 | 删除回执、重新添加、跨卡片隔离、旧格式归属与歧义、无效 ID、重复删除 |
| `HealthSyncRecoveryTest` | 7 | 成功回执恢复、提交失败、失败回执异常、查询失败、提供方 ID 保存、并发提交、缺少必要信息 |
| `CalendarRecoveryTest` | 6 | 插入成功但回执/调用失败后的重试、查询失败、跨线程并发去重、删除后提交失败、删除错误 |
| `CalendarWriteSelectionTest` | 6 | 日期时间、日历/时区、跨午夜时长、过去时间、优先级及夏令时异常 |
| `RecordingFinalizerTest` | 6 | 停止/释放/持久化顺序、停止或释放失败、入库失败、两条保存路径均失败、缓存清理失败 |
| `ClassificationReviewTest` | 11 | 旧提案入口、接受/编辑/拒绝、内容保留、幂等、语音门槛、状态保留、旧快照与过期操作保护 |

APK 已用 `aapt2 dump xmltree` 和 ZIP 条目检查 Widget receiver、APPWIDGET_UPDATE、provider 元数据、布局与配置文件存在；未实际添加到桌面。

其中 79 项为 JVM 纯逻辑测试，另 64 项使用 Robolectric API 35 执行生产数据库和 SharedPreferences 路径。未覆盖真实设备上的 SQLite 断电恢复、AlarmManager、Compose 点击、日历/健康 provider，也不替代 Android 与 Vela 实机集成验收。后台分类覆盖保护的数据库事件查询已核对源码，尚未进行设备上的并发故障注入。录音测试注入的是函数替身；未实际触发设备的 MediaRecorder 上限/错误回调，也未证明异常片段一定可解码。


Vela 专注计时测试：`python3 tests/test_focus_timer.py` 使用生产计时函数、计时线程、同步线程循环及停止函数，注入可控时钟并阻塞同步调用，验证提醒触发、15 秒显示到期、退出专注、线程 join 和重启。此测试不运行真实网络驱动，也不证明两端提醒配置同步。

Vela 连接与并发测试：`python3 tests/test_connect_timeout.py` 验证真实回环 TCP 连接/拒绝、标志恢复、读超时设置，并通过系统调用替身验证中断不重置期限、异步错误与 SO_ERROR 读取失败；`python3 tests/test_pairing_concurrency.py` 使用生产服务循环和发现函数，将 TCP 处理替换成受控阻塞，验证 UDP 仍可响应及三次启动/退出；`python3 tests/test_pairing_read.py` 使用生产读行函数，验证 CRLF、超长输入、EOF、非法参数，以及每 100 ms 输入一个字符仍在 5.000 秒超时。上述测试不覆盖完整 Android 配对与实际 S1 线程调度。

Vela UDP 主机测试：`python3 tests/test_pairing_discovery.py` 与 `python3 tests/test_phone_discovery.py` 已通过。前者使用生产发现处理函数和真实回环 UDP，确认 Vela 可被发现、忽略手机发现及错误协议/非法请求；后者仅把广播目标改为回环，使用生产手机发现函数验证静默与连续错误包均约 251 ms 超时，以及忽略其他手机后接受匹配手机。测试不能替代 S1 无线网络验收。

Vela 主机行为测试：`python3 tests/test_card_cache.py` 已通过。它编译生产 `buffer_update_cards` 函数，以存储替身注入 ENOSPC，验证错误传播、旧 deck 保留、重试、重排后的阅读位置、卡片移除、空 deck 和非法参数；不模拟实际闪存驱动。

Vela：

```text
env CROSSDEV=arm-none-eabi- \
  PATH="/home/simon/vela/prebuilts/gcc/linux-x86_64/arm-none-eabi/bin:$PATH" \
  ./build.sh vendor/allwinnertech/boards/r528/r528s3-gemini-s1/configs/nsh_minidisplay -j4
```

构建和链接成功。LTO 输出中的告警来自现有第三方 zblue/NAND 代码，未出现 Buffer 应用编译错误。两个仓库的 `git diff --check` 历史检查通过；该命令不覆盖尚未跟踪的新文件，不能作为全部源码的格式验证。

## 本轮提醒同步与代码清理

- Android 增加专注提醒设置：间隔 5–180 分钟、延后 1–60 分钟；修改设置后重新计时。下发 `focus_reminder`，包含 `FocusReminderConfigUpdated` 类型、间隔、剩余时间和稳定版本号。
- Vela 仅在版本变化时校准单调时钟倒计时，重复卡片刷新不重置期限；延后/跳过清除当前提示，正常推进保留已显示提示。本地未确认的模式操作优先。间隔保存失败返回错误，保留旧计时状态，等待后续同步重试。
- 新增 `tests/test_focus_config.py`，编译生产解析和同步函数，验证字段校验、版本去重、延后、存储失败重试及模式冲突；使用存储替身，不证明真实闪存写入与断电恢复。8 个 Vela 主机测试全部通过。
- 删除未使用的 Hello QuickApp 和占位板级目录、对应 manifest 映射及工作区软链接。实际 S1 仍使用 Allwinner BSP；保留需求文档、日志格式示例和回归测试。
- 未宣称两端提醒精确同时出现：传输延迟、手机推进提醒期限与 Vela 本地触发的先后顺序仍需集成验证。离线重启只恢复间隔；模式与专注配置后续已迁入 SQLite，事务验证见模式事务修复。

## 日历恢复修复

- 日历写入在插入时记录稳定的卡片关联 URI，重试先按应用包名和 URI 查询未删除的日程；已存在则恢复本地关联，不再次插入，也不覆盖原日程时间。使用 Android 官方定义的 [CUSTOM_APP_PACKAGE / CUSTOM_APP_URI 字段](https://developer.android.com/reference/android/provider/CalendarContract.EventsColumns)。空查询结果才允许插入；查询失败或发现多个关联日程时停止。
- `CalendarRecovery` 在应用进程内串行执行写入和删除，避免不同 Activity 同时通过“尚未存在”检查。当前 manifest 未配置独立应用进程；此锁不提供跨进程互斥。
- 本地卡片动作和成功回执放入同一个 SQLite 事务。删除先操作 provider，成功或已不存在后才提交本地事务；提交失败后保留原撤销入口，重试允许 provider 返回 0。
- 移除原先“先改卡片再删除”和写入失败时的补偿回滚分支，失败回执写入也加异常处理。外部操作成功、本地事务失败时保留关联供重试恢复。
- 新增 6 项 JVM 故障/并发测试，Android assembleDebug、lintDebug、testDebugUnitTest 通过，共 56 项测试。测试使用 provider/commit 函数替身，未执行真实 SQLite 故障注入或 Android provider 查询。
- 限制：旧版未标记的日程无法通过新 URI 找回；外部应用或同步服务删改关联字段后也无法保证查重。当前恢复由用户重试触发，尚无自动扫描未完成请求的恢复流程。跨 provider、本地数据库的原子提交不能由这些测试证明，必须继续进行设备强杀与重试验收。

## 日历回执归属修复

- 删除旧 `calendarEventId` 查询；新查询同时读取成功创建和删除回执，按逻辑时钟及行号倒序，优先匹配精确卡片 ID。最新删除回执不会再被忽略；重复删除直接结束，不再次操作 provider 或提交本地动作。
- 缺少卡片 ID 的旧格式仅在 capture_id 非空、数据库中该捕获唯一对应当前卡片、所有旧回执只涉及同一个有效日程 ID 时恢复。多个日程、缺少归属或无效 ID 均不猜测删除目标。精确匹配的最新回执损坏时直接报错，不回退到更旧日程。
- 新增 `CalendarReceiptsTest` 10 项；Android 构建、Lint 和全部 66 项 JVM 测试通过。测试覆盖生产回执选择逻辑和重复删除控制流，数据库查询条件经过源码检查；尚未验证真实 SQLite 数据迁移与 provider 行为。

## Health Connect 回执恢复修复

- 保留原有稳定的 `clientRecordId = buffer-<card_id>`，重试不生成随机 ID。官方 [Metadata 文档](https://developer.android.com/reference/androidx/health/connect/client/records/metadata/Metadata) 说明同一客户端、数据类型及 clientRecordId 只保留一条记录，依据 clientRecordVersion 更新或忽略重复写入。此协议保证不等于本项目已通过真实 provider 重试验收。
- 新增 `HealthSyncRecovery`：应用进程内串行执行外部写入和本地回执提交，已有成功回执时只恢复本地状态；查询异常时不继续外部写入。Health Connect 成功而本地事务失败时保留重试入口，并捕获失败回执本身的写入异常。
- 回执提交移出界面线程，保存返回的 provider record ID 和 clientRecordId（若 provider 未返回单条 ID，保留为空）。卡片状态和成功回执继续在同一 SQLite 事务内提交；合并原有两套重复更新分支。
- 新增 7 项 JVM 故障与并发测试。Android 构建、Lint、全部 73 项 JVM 测试通过；这些测试使用 export/commit 替身，不覆盖真实 Health Connect、SQLite 断电、授权撤销或跨进程运行。当前恢复仍由用户重试触发，后台自动恢复和健康数据内容变更后的版本策略尚需继续核对。

## 专注提醒触发顺序修复

- 已确认并修复：手机先触发并推进下一期限时，Vela 原先可能在本地 timer 到期前收到新的未来期限，从而漏掉本次提示。
- 卡片同步新增 `focus_reminder.notice_remaining_ms`，取值为 0–15000 的整数。仅正常触发后的 15 秒窗口携带剩余时间；延后、跳过、设置变更、过期或时钟回退均为 0。
- Vela 仅在新 revision 时补显仍有效的提示；如果本地已经显示，则保留原到期时间。重复同步不会延长提示。旧手机缺少此字段时按 0 处理。
- 扩展 Android 测试 3 项及 Vela 生产函数主机测试，覆盖手机先触发、本地先触发、重复同步、清除、过期、字段边界和非整数拒绝。专注配置和独立 timer 测试均通过；Android 构建/Lint/76 项测试与 S1 固件构建成功。
- 此为可控时钟与生产函数测试，尚未运行真实双端网络时序；传输耗时仍会影响实际显示到期时间，不保证两端毫秒级对齐。

## 数据库故障注入验证

- 按 [Robolectric 官方配置](https://robolectric.org/getting-started/) 引入测试依赖 4.17，仅作用于测试。测试使用 API 35、普通 Application 和生产 `BufferRepository`，执行实际表结构与 SQL；不启动 Buffer 后台网络服务，也不连接用户日历或健康库。
- `RepositoryTransactionTest` 通过 SQLite `BEFORE INSERT` trigger 对指定成功回执执行 `RAISE(ABORT)`。验证日历创建、日历删除、健康写入及分类编辑失败时，卡片状态和事务内事件一起回滚；删除测试关闭并重开数据库后重试成功。健康提交重复调用不增加成功事件。
- 实际执行旧回执归属 SQL：同一捕获只对应一张卡片时可找回旧格式日程；增加第二张归属相同捕获的卡片后，两个查询均拒绝猜测。
- 执行 SharedPreferences 路径验证 repository 重开保留提醒 revision，延后产生新 revision 并清除提示。这里只重建仓库对象，没有模拟进程强杀或断电，不能证明异步落盘的耐久性。
- 全量 Android assembleDebug、lintDebug、testDebugUnitTest 通过，共 82 项测试，0 失败。Robolectric 运行环境不是连接的 Android 设备；当前 adb 仍无设备，SDK 中无 emulator，因此 provider、硬件、系统调度与真实进程恢复验收仍保留。

## 分类跨实例并发修复

- 源码发现：`applyRemoteClassification` 的用户活动检查及 `reviewClassification` 的旧快照读取在事务之前，实例级 `@Synchronized` 不能防止另一个仓库实例在检查与写入之间提交改变。
- 将两条路径的读取、用户活动/快照/转写检查与写入纳入同一 SQLite 写事务。后台先提交时，用户旧快照要求重新确认；用户先提交时，后台读取最新状态和用户事件并跳过覆盖。
- 新增 `ClassificationDatabaseTest` 5 项，直接执行生产仓库及 SQL，覆盖接受/拒绝/编辑后的迟到后台结果、过期确认快照，并用两个独立 SQLiteOpenHelper 实例进行 12 轮同时提交。结果保留成功用户决定；否则只留下未确认的新提案。
- 全量 Android 构建、Lint、87 项测试通过。并发测试是 Robolectric 环境中的有限调度样本，不是所有交错的穷尽证明，也不替代真实后台服务、UI 生命周期与强杀验证。

## 首次转写并发修复

- `completeAudioTranscription` 原先在事务外检查 capture.transcript，手动与后台两个仓库实例可能同时读取空值。现将读取、首次提交检查、转写保存和卡片/事件更新纳入同一事务，后到结果返回已提交的卡片。
- 新增 `TranscriptionDatabaseTest` 3 项：两个独立仓库实例进行 12 轮竞争，每个捕获只有一条 CaptureTranscribed 事件及一张卡片；注入 ClassificationProposed 插入失败后，转写和卡片更新全部回滚，原音频仍在待处理队列，解除故障后重试成功；迟到转写不替换已确认卡片。
- Android 构建、Lint、90 项测试通过。使用 Robolectric SQLite 和最小音频字节夹具，未解码音频、调用真实转写服务或模拟设备断电；多段转写的并发分段一致性仍需进一步覆盖。

## 音频入库与首次建库修复

- `createAudioCapture` 将查重、文件确认和数据库写入放入同一写事务，避免多个 helper 在事务外同时通过查重。独立临时文件写入并 sync 后重命名；临时文件在 finally 清理。
- 移除数据库失败时无条件删除目标音频的分支。已落盘但未入库的 blob 留给重试；再次提交前比较长度和字节，相同内容复用，不同内容报错并保留原件。已成功入库的 capture_id 返回原卡片，不覆盖其音频。
- 新增并发测试暴露首次建库重复创建 events 表的问题；数据库打开和 schema 初始化按进程串行化，已打开的连接通过缓存绕开初始化锁，允许持有事务的实例继续提交。缓存关闭后由父类重新打开。该锁不覆盖另一个进程。
- `AudioCaptureDatabaseTest` 4 项及全部 94 项 Android 测试、构建、Lint 通过。测试使用实际临时文件及 Robolectric SQL，未验证文件系统断电和目录元数据持久性；未入库 blob 若没有发送方重试或配套恢复元数据，仍不会自动进入队列，不能将这类原件当无用文件删除。

## 主音频自动恢复

- 在主 blob 写入前保存 `<capture_id>.pending.json`，包含原 capture_id、来源、时间、模式、格式及长度元数据（duration_ms）。元数据和音频各自经过临时文件写入、sync、重命名；成功数据库提交后才删除 pending 元数据。
- 复用现有启动 `recoverPendingAudio` 入口，同时扫描主 `.bin` 与旧 `.retry` 备用副本。主音频恢复成功后保留 `.bin`，仅清理恢复元数据；原备用副本按已有流程清理。数据库再次失败时均保留供下次恢复。
- 恢复读取前检查元数据/音频大小，并校验文件名 capture_id 与元数据一致；已有音频或元数据与重试内容不一致时拒绝覆盖。
- 新增 3 项数据库/文件恢复测试：关闭并重开仓库后恢复一次、重复扫描不重复入库、ID 不匹配时保留原件、持续数据库故障后再次成功恢复。Android 构建、Lint 和全部 97 项测试通过。
- 测试没有模拟设备突然断电或实际 BufferApplication 生命周期。缺少元数据的历史孤立 blob 仍无法推断来源，不自动删除；目录重命名的断电持久性仍待设备验证。

## 备用音频保存与清理

- 删除 `preserveAudioForRetry` 的同名临时文件覆盖及失败删除已提交副本的旧分支。主音频和备用副本统一使用 `RecoveryFiles`：进程内串行确认目标内容；同内容复用，冲突报错；独立临时文件写入、sync、rename，finally 仅删除临时文件。
- 备用副本先保存元数据再保存音频。后一步失败保留已写入元数据及调用方源文件，重试可继续；不把可恢复原件列为无用文件。主路径也补齐元数据大小检查。
- 新增 3 项备用路径测试：重复保存后的单次恢复、冲突不覆盖、用目录占用目标路径注入写入失败后保留源文件和元数据并重试成功。测试夹具使用实际 `.retry.m4a` 后缀，未解码音频。
- Android assembleDebug、lintDebug、全部 100 项测试通过。文件锁仅作用于应用进程；真实文件系统断电、跨进程修改与录音解码仍未验收。

## 卡片操作回执并发修复

- `performCardAction` 的 action_id 查重与旧卡片校验原先在事务外，两个仓库实例可能同时通过，随后第二次回执插入因唯一约束失败。现将读取、校验、卡片更新及回执写入纳入同一事务。
- 已存在回执的重复操作不再次修改卡片；同一 ID 的卡片与动作必须匹配。旧操作在更新的操作之后重试，卡片保持当前状态，ACK 仍可查询旧操作原始 resulting_state。提前返回分支显式标记事务成功，兼容日历调用方的外层事务。
- 新增 4 项 Robolectric 数据库测试覆盖并发重复操作、回执插入失败的整体回滚与重试、ID 误用拒绝、旧操作重放。Android 构建、Lint、104 项测试通过。真实 TCP 重连投递与 S1 ACK 丢失场景仍需双端集成验证。

## 操作协议校验与 ACK 验证

- `action_id`、`mode_id` 共用严格解析：显式提供时必须是 1–80 字符的合法字符串。拒绝 null、数字、布尔值、空白及超长值，避免 optString 类型转换和空值退化为无去重请求。兼容旧客户端完全省略 ID 的行为；该兼容路径不承诺幂等。
- 新增 `WireActionTest` 4 项，在 Robolectric 中经生产 `BufferWire.handle`、JSONObject 和 SQLite 执行请求，验证旧重试 ACK 返回原状态而卡片保留新状态、非法请求无数据修改、回执失败返回 error 并可重试 ACK、ID 改绑动作时不返回成功。
- Android 构建、Lint、108 项测试通过。本测试未经过 TCP、认证、S1 客户端 ACK 匹配或真实网络故障，不能据此关闭端到端重连验收项。

## 模式与专注状态事务修复

- 确认旧实现的问题：setMode 在 SQL 提交前修改 SharedPreferences，ModeChanged 或 mode_receipts 插入失败时，SQL 回滚但模式/专注计时已改变；睡前 deck 事件则在提交后单独写入，也可能导致“返回错误但模式已切换”。
- 数据库版本升至 14，新增 runtime_settings；首次创建或从 v13 升级时复制旧 mode 和 focus_* 配置。迁移后这组状态不再读写旧 SharedPreferences；其他应用配置保持原存储。
- `DatabaseSettings` 提供同步 SQL 设置更新，并嵌入外层事务。模式/专注开始、触发、延后、配置变更和相应事件共同提交；mode_id 查重在同一事务内；BedtimeDeckUpdated 也纳入模式事务。
- `ModeTransactionTest` 6 项验证 ModeChanged、BedtimeDeckUpdated、FocusReminderDeferred、FocusReminderConfigUpdated 失败时状态回滚；旧 mode_id 重试不撤销新模式；构造 v13 表结构和偏好设置验证迁移，并确认重开不会重新导入旧偏好设置。
- Android 构建、Lint、114 项测试通过。现有配置重开测试也已在 SQL 存储路径通过。测试为 Robolectric SQL 故障注入与仓库重开，不是实际升级安装、断电或系统后台调度验收。旧报告中的 SharedPreferences 专注测试记录仅描述当时实现。

## 专注调度异常恢复

- 专注 reconcile 路径原先未捕获数据库/系统服务异常，异常可越过周期 Runnable 或闹钟入口。现局部捕获并记录异常，保留下一轮重试，同时仍执行独立的睡前 reconcile。
- 新增 `CareSchedulerDatabaseTest` 2 项，通过生产 scheduler.onAlarm 及 SQL 故障注入：FocusReminderShown 写入失败不向外抛异常，期限保持原值，解除故障后相同 scheduler 重试成功；模式表暂不可读时也能在恢复后继续处理。
- Android 构建、Lint、116 项测试通过。测试直接调用与周期任务共用的 reconcile 入口，没有等待真实 15 秒周期、模拟系统唤醒或注入真实通知服务失败；通知投递与数据库之间的持久化重试仍需单独核对。

## 专注通知待投递恢复

- 提醒到期时在同一 SQL 事务保存 pending notification revision 与下一期限。调度先安排下一次闹钟，再尝试投递；通知权限未授予、应用/频道通知关闭或调用失败时不清除 pending 标记，后续轮询可重试。
- `deliverPendingFocusReminder` 在数据库事务内确认模式和待投递版本，调用成功后清除标记并记录 FocusNotificationPosted。延后/跳过、退出专注、设置变更均清除旧待投递状态；新提醒覆盖旧标记。
- 通知固定 ID，并设置 onlyAlertOnce。若 notify 成功但 SQL 回执失败，重试更新同一条通知；不宣称系统通知与 SQLite 之间存在原子提交或用户必然看见提示。原 FocusReminderShown 事件仍表示提醒计时已触发，实际 notify 返回成功另有事件记录。
- 增加 4 项数据库/调度测试，覆盖投递返回 false 后重试且不重复推进期限、仓库重开后补投、取消旧提示、通知已投递但回执失败后的重试。Android 构建、Lint、120 项测试通过。
- 测试注入投递函数，未向真实设备发通知；进程被杀时重试依赖系统下一次唤醒/应用启动，尚未证明及时补投。真实权限撤销、频道关闭、后台闹钟与通知可见性仍需设备验收。

## 通知操作异常保护

- onReminderAction 捕获数据库与系统服务异常，避免错误传播至广播入口；合并延后/跳过重复分支，未知动作提前返回。先提交延后事务，再撤下通知和安排下一闹钟，数据库失败时保留原期限及待投递状态。
- 新增 2 项 Robolectric 测试：注入 FocusReminderDeferred 插入失败后，入口不抛异常且旧 pending 保留；解除故障后再点击可延后并清除旧提示；非专注模式及未知动作不改变状态。
- Android 构建、Lint、122 项测试通过。测试直接调用操作入口，尚未通过真实 PendingIntent 点击或注入 NotificationManager/AlarmManager 服务异常；若事务成功而后续系统服务失败，依赖下一轮 reconcile 修正。

## 睡前调度事务修复

- 数据库版本升至 15，将 bedtime_* 设置从旧 SharedPreferences 迁入 runtime_settings。睡前计划修改、待触发日期、固定重试期限、当天执行标记及模式切换现在通过同一 SQL 事务提交。
- 删除 bedtimeStateNeedsCommit 及 SharedPreferences 磁盘提交失败的专用重试分支。SQL 失败时保留原状态，下轮仍可根据原期限重新计算；不再依赖进程内标志补偿两套存储的不一致。
- 新增 `BedtimeDatabaseTest` 3 项：在当天执行标记插入处注入失败，验证此前的模式切换和事件一起回滚；解除故障后仅触发一次；专注期间重开仓库保持原重试期限，退出专注后补触发；v14 偏好配置迁移后不重复导入。
- Android 构建、Lint、125 项测试通过。现有 v13 模式迁移也通过升级至 v15 的路径。实际 APK 升级安装、系统时区变更、闹钟送达及断电恢复仍需设备验收。

## 后台分类任务领取修复

- 数据库版本升至 16，classification_jobs 新增 lease_id。替换原无条件 markClassificationRequestStarted：通过同一事务内的带状态/期限条件 UPDATE 领取任务，只有成功更新的实例发起请求，领取事件失败则回滚。
- Worker 为每次请求携带独立领取标识。应用结果或记录失败前核对 capture_id、processing 状态和 lease_id；超时被重新领取后，旧请求返回不会覆盖新提案或把已完成任务重新标为 pending。重复成功回写同样忽略。
- 后台轮询外围增加异常保护，数据库/失败回执写入异常不再取消后续周期；未完成任务保留超时重新领取机制。删除旧的无条件领取方法。
- 新增 `ClassificationLeaseTest` 3 项验证双仓库只领取一次、过期成功/失败回写隔离、领取事件失败回滚。迁移夹具更新为真正不含 lease_id 的旧表结构；原 v13/v14 配置迁移测试继续通过升级到 v16 的路径。
- Android 构建、Lint、128 项测试通过。测试未调用真实 HTTP 分类服务、执行进程强杀或跨进程并发；租约保护的可选参数仍允许本地直接应用提案，后台 worker 已始终传入领取标识。

## 远程分类响应校验

- 分类解析改为严格字符串字段及数值置信度校验；拒绝数字/布尔值冒充文本、字符串置信度、非有限值或超出 0–1 的置信度。缺省置信度仍为 0.5，缺省标题仍取摘要；问题卡不可携带健康类别。
- 保留 BufferHttp 的有界流读取，删除完成读取后重复的长度校验。客户端仅在全部分段解析成功后返回提案列表。
- 新增 `ClassificationHttpTest` 4 项，使用本机 HttpServer 与生产 HttpURLConnection 客户端执行真实回环 HTTP：核对请求 task/capture_id/text，验证有效响应、类型错误与越界置信度、混合畸形分段、503 和超过 100 万字符的响应。
- Android 构建、Lint、132 项测试通过。测试不是外部 Coordinator/LLM 联调，也未覆盖 TLS、代理、慢响应总期限或服务语义准确性；本轮测试不调用数据库写入。

## 远程回答响应校验

- 回答请求的 allowed_outputs 显式使用 JSONArray，避免普通集合被错误编码。unanswered 必须为布尔值；answer/text/reason 若存在且非 null，必须为字符串，不再把数字或布尔值转换成答案。
- unanswered=true 优先返回未解，忽略同时附带的答案；缺少/null/空白答案仍保持未解。保留 4000 字符答案上限和 64 Ki 字符响应上限，未解原因显示也限制长度。
- 新增 `AnswerHttpTest` 4 项，经实际回环 HTTP 核对请求结构、有效答案、未解优先、非法字段、答案/响应上限与 503。抽取 HttpTestServer 供分类和回答测试共用。
- Android 构建、Lint、136 项测试通过。本轮验证客户端输出，不运行 UI 保存流程，也不证明外部服务回答可靠性或真实网络故障恢复。

## 迟到远程答案保护

- saveQuestionAnswer 将卡片读取、类型校验和写入放入同一 SQL 事务；remote 来源必须传入请求时快照并与当前卡片匹配，否则拒绝保存。手动答案、归档或其他卡片修改不再被迟到响应覆盖。
- MainActivity 传递请求快照，问题已删除时不报告保存成功；失败回执写入使用独立异常处理。仓库统一限制非空答案不超过 4000 字符。
- 新增 `AnswerDatabaseTest` 4 项验证手动答案保护、归档保护、重复过期响应拒绝，以及 QuestionAnswered 插入失败时卡片回滚且可重试。Android 构建、Lint、140 项测试通过。
- 测试在 Robolectric 数据库中模拟响应先后顺序，未执行 Compose 生命周期、用户点击与真实远程服务联调；仅描述内容快照保护，不保证外部请求被取消。

## 手机令牌持有证明

- Vela 每次 hello 新增 32 字节随机 client_nonce；Android hello_challenge 返回 phone_proof，使用配对令牌对 `buffer-phone-v1:<client_nonce>:<server_nonce>:<vela_id>:<phone_id>` 执行 HMAC-SHA256。角色前缀与设备方向证明分开，避免错用证明；HMAC 原语参见 [RFC 2104](https://www.rfc-editor.org/rfc/rfc2104.html)。该 RFC 不构成本应用握手协议的安全审计。
- Vela 固定校验已配对手机 ID、随机数格式及 64 字符证明，比较循环不提前退出；通过后才发送自己的 hello_proof。Android 拒绝缺少或非法 client_nonce，Vela 不接受缺少手机证明的旧服务。须配套更新 Android 和固件，现有令牌格式不变。
- 新增 3 项 Android 纯逻辑测试及 `tests/test_phone_auth.py`：Java、生产 C 证明校验和 Python 独立向量一致；C 主机测试使用 OpenSSL 适配 HMAC，覆盖缺失证明、错误令牌、挑战/身份更换和设备方向证明反射。目标固件使用原有 mbedTLS HMAC。
- Android 构建、Lint、143 项测试通过；Vela 9 个主机脚本及 Gemini S1 固件编译成功。未完成实际 TCP 双端握手或 S1 熵源验证。
- 安全限制仍然存在：初次配对令牌可被明文窃听，证明之后的业务消息可被篡改；中继攻击、会话通道绑定和完整加密传输没有解决。此修复仅关闭“手机完全没有令牌持有证明”的源码缺口，不代表安全传输已经完成。

## 当前产物

| 产物 | SHA-256 |
| --- | --- |
| `/home/simon/vela/nuttx/vela.bin` | `acd1b2da0699c5243b4a4010937679470755e143a2467b94656fc6d54fa84c3b` |
| `/home/simon/vela/vendor/allwinnertech/lichee/board/r528s3/gemini-s1_nand/configs/nsh.fex` | `acd1b2da0699c5243b4a4010937679470755e143a2467b94656fc6d54fa84c3b` |
| `/home/simon/projects/mocha/foundation.fabric/apps/android/buffer-app/build/outputs/apk/debug/buffer-app-debug.apk` | `f35790e9b898847943a81b4ac7b0f72ec9b5e38d4945929f4b50e71eca51034b` |

## 实机验收清单

接入 Gemini S1 后需要按顺序验证：

1. 刷入 `vela.bin` 或对应 BSP 打包产物，确认系统启动脚本自动拉起 `buffer_app`。
2. 执行 `buffer_app wifi <SSID> <密码>`，确认 DHCP 地址和重启自动重连。
3. Android 与 S1 在同一 Wi-Fi，扫描到 Vela，输入屏幕 6 位配对码并完成首次同步。
4. 长按/松开实体按键，确认录音状态、WAV 落盘、手机收到 Capture、收到 ACK 后文件删除。
5. 断开 Wi-Fi 后继续录音，重启 Vela，再恢复网络，确认 pending queue 逐条上传且不重复建卡。
6. 在 Vela 上浏览卡片并执行“提醒/稍后/完成/归档/深挖”，确认 Android 状态和事件回执一致。
7. 切换专注和睡前模式，确认 Vela 离线 timer、卡片过滤和恢复同步行为。

本机之前记录的 Windows USB Code 43、`USBDrv_AMD64.sys` 代码完整性阻止仍是实机验收的外部阻塞条件；它不影响本次源码构建结果。

## 文档范围内的后续项

本报告没有把文档明确列为后续阶段的功能计入 MVP 缺陷：本地离线 ASR、完整 Personal Mesh/CRDT、多端事件复制、跨公网连接和完整外部项目管理器仍需后续实现。当前转写可使用配置的 HTTP 服务或 Android 手动转写入口。

## 独立复核状态

本轮 `gpt-5.6-luna`（reasoning effort: `max`，代理 ID `01a0bd98-0f0c-7a71-83f2-9a7126125a43`）已完成设备发现与首次配对的独立只读源码复核。它确认 TCP 配对处理与 UDP 发现共用线程，慢请求可能超过 Android 1.2 秒扫描窗口，导致设备扫描不到。

主线程据此分离发现线程，并补充并发与请求总超时测试，结果见上文；Luna 未复核修改后的实现，不能将这些修复称为已获其独立验收。其本次范围未覆盖重连超时，也未执行 Android → Vela 扫描、配对、令牌落盘的端到端测试。

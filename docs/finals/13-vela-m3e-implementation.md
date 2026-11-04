# Vela M3 Expressive 实施与验证记录

日期：2026-10-19。接续 [11 界面审查](11-vela-m3-expressive-review.md) 与 [12 动效清单](12-vela-motion-inventory.md)。本轮实施覆盖 Vela 的公共组件、OOBE、四模式首页、全局录音、卡片、A2UI 及新增完整待办列表。**代码实现、host 实测与目标设备验收是不同结论。没有启动 Windows/Linux 模拟器，没有安装或启动应用，没有生成运行截图，没有修改 Android 产品 UI、Fabric/coordinator 协议或用户设备盘，没有 commit/push。**

## 共用基础与实现边界

- `app/buffer_app/buffer_ui_theme.h`：共用语义颜色、成对 on-color、30/20/18/16/14 文字角色、间距和 shape。`buffer_ui_motion.c` 把 h1/h2/h3/body/caption 绑定实际字体；缺失 MiSans 时保持原有 fallback，不能据 host 英文字体用例宣布中文视觉通过。
- `buffer_motion.c/.h`：真实单位质量 spring 积分，spatial fast/default/slow 与 effects fast/default/slow 分离。采用 damping/stiffness 映射 `(0.6,800)/(0.8,380)/(0.8,200)`、`(1,3800)/(1,1600)/(1,800)`；不是官网 Web fallback 曲线/时长。effects 不越过目标；空间回弹仅用于短距离/形状，正文、二维码和安全数字保持静止。
- `buffer_ui_motion.c/.h`：LVGL UI 线程 16ms 独立 frame timer；业务全量刷新仍为 **500ms**。24 条有界轨道；已有属性从当前值/速度重定向，不排旧动画；DELETE 撤销，无业务完成 callback。没有活跃轨道时暂停帧时钟。恢复间隔超过 120ms 直接呈现当前目标；连续慢帧退化为静止，轨道不足也直接呈现目标。减弱动效由模式菜单“减少动态”和 `BUFFER_REDUCED_MOTION=1` 控制；`BUFFER_UI_LOW_LOAD=1` 可强制静止。尚未测得目标设备 FPS/CPU/输入延迟，16ms 只是调度请求。
- 共用 button helper 覆盖 normal/pressed/checked/disabled/focus-key。OOBE 主按钮 pressed 为白字 / `#2C523E`，不再白字 / `#A9D4BA`。录音使用独立红色 pressed 配对，不被绿色通用 pressed 覆盖。触摸控件不通过空间移动做按压反馈，使用圆角变化；焦点轮廓为稳定显示。
- OOBE 保持原有真实 finish worker 与持久完成门禁；READY 是配置就绪，“开始使用”提交成功才进 HOME。图形改成原生形状、阶段符号与原生矢量勾号；无渐变 B、无 74/61 伪进度、无无限旋转。READY 阶段交接只播放一次图形建立；恢复首次呈现直接使用最终成功图形。
- 首页直接显示模式候选；卡片动作放回所属卡片，“稍后/完成”与“更多”分层，维护动作与录音分离；最多五张仍仅是推荐投影。首尾翻页禁用；更换卡 ID 才触发方向交接。原地内容更新、秒数、长文阅读默认 STATIC。
- `buffer_audio_record_cancelable` 用原子 decision 将取消与提交线性化：取消被接受时不发布 WAV/meta；开始提交后返回 `-EALREADY`，如实提示正在保存，不删除已经入队的录音。正常松开仍完成当前 read period、保留原 WAV+metadata 提交/错误清理流程；PRESS_LOST 明确取消，采集/文件 I/O/join 均在原 worker。没有伪造波形或把动画当持久提交回执。
- A2UI 按 surface ID、task revision、component ID/type 区分新任务、同任务内容与结构变化。通用容器/Text/Button/Divider 复用；定制组件仅在自身 payload 或动作索引变化时局部重建。不存在每个 sequence 都 clean 整页和强制 scroll-top；删除对象立即撤销轨道。用户按压/滚动时延后差异提交。新任务等待显式“新结果”入口；后台提醒仅进入首页 notice，不直接激活页面。关闭按屏幕实际 surface ID 处理，迟到更新遵守原有 closed-ID fence。
- A2UI Row 文本限定父内容宽度，长文换行；深层 card 降为留白分组，避免每层 16px padding；字形和图形在容器边界内布局。timer running/paused 分别显示“暂停/继续”，fired 可关闭提醒，cancelled/失效禁用；失败在任务页原地反馈。关闭任务卡不取消 timer/alarm。没有逐字伪流式、虚构音频幅度或每秒数字弹跳。

官方来源：[Motion specs](https://m3.material.io/styles/motion/overview/specs)、[States](https://m3.material.io/foundations/interaction/states)、[Buttons](https://m3.material.io/components/buttons)、[Cards](https://m3.material.io/components/cards)、[Typography](https://m3.material.io/styles/typography)。本轮核实官网页面及 `2026-10-13_06-10-19` 官网发布 JSON 可访问；原文缓存留在 `/home/simon/.cache/buffer-vela/material-official/`，不复制第三方包或整篇官网文档入仓库。访问证据在产物目录 `official-origin-check.json`。本实现不是官方认证，具体 LVGL 映射仍需设备验收。

## Host 证据索引

| 代号 | 实际代码/测试 | 验证范围 |
| --- | --- | --- |
| T1 | `tests/test_ui_motion.py` + `tests/fixtures/ui/ui_host.c` | 编译 SDK 真实 LVGL 和生产 spring、动效、OOBE、A2UI renderer；ASan/UBSan；六 spring 收敛/重定向/不越界/减弱/恢复，真实对象删除与后续帧，surface/component 稳定 ID，scroll 保留，timer 标签/可用性与 inline 原位错误反馈，新任务等待，OOBE 创建/重复 READY/销毁；240×320 列表长标题宽度和稳定 row/detail 复用，以及整页完成后返回清单起点。模型/domain 输入为受控 stub，不能代替端到端业务或中文视觉。没有启动 app 或模拟器。 |
| T2 | `tests/test_capture_target.py`、`tests/test_touch_recording.py` | 实际音频函数搭配 ALSA stub：自动目标和旧目标兼容、取消后不留 WAV/meta/part；真实 touch intent/worker：短点、重复按下、重复释放、取消 decision/提交拒绝边界、shutdown。没有验证真实麦克风。 |
| T3 | 既有 `test_oobe_protocol.py`、`test_oobe_store.py`、`test_oobe_completion_store.py` 等 | 真实 OOBE domain/持久化/恢复与完成门禁；不是动画观看验收。 |
| T4 | 既有 `test_assistant_a2ui_core.py`、`test_a2ui_official_schema.py` | 真实 assistant/A2UI domain、计时、工具幂等、surface/closed-ID 等原有覆盖；不是完整触摸矩阵。 |
| T5 | `tests/test_todo_list.py` | 真实查询 helper +真实显式 ID 提交 helper；137 replica 待办+本地/待同步共139项跨页，反向分页，重复/墓碑/非待办/未转写排除，phone 权威、本地 pending，明确 ID 不改首页索引，单槽 busy 与落盘失败。文件存储/动作提交边界使用私有 fixture/stub，原 card.state 队列由既有测试补充覆盖。 |
| T6 | 既有 `test_agent_card_state_protocol.py`、`test_agent_state_outbox.py`、focus/research/card-cache 等 | 保留原有状态回执、幂等 outbox、计时与五张投影回归。 |

## 90 项逐项落实

“已实现”指对应代码/STATIC 策略已接入，不是 90 个运行用例全部通过。“源码”仅表示 self-review，没有额外 host 场景实测；每一项的设备视觉/触摸/连续输入验收仍待运行。下表路径均相对项目根目录 `app/buffer_app/`。

| ID | 场景 | 实现/静止策略与代码路径 | Host 证据 | 设备验收 |
| --- | --- | --- | --- | --- |
| G01 | 普通按钮按下 | 已实现 SF 圆角+成对 pressed 色；buffer_ui_motion.c:buffer_ui_button_style/press | T1 | 待运行 |
| G02 | 松开、滑出、失去触点 | 已实现释放/失触重定向；录音失触真实取消；buffer_ui_motion.c:press、buffer_ui.c:buffer_record_event | T1/T2 | 待运行 |
| G03 | 选中/取消选择 | 已实现 checked 成对色与模式当前项；buffer_ui.c:buffer_ui_refresh | 源码 | 待运行 |
| G04 | 禁用、busy、重新可用 | 已实现 disabled/busy 与真实请求边界；buffer_ui_motion.c、buffer_oobe_ui.c | T1/T3 | 待运行 |
| G05 | 键/辅助输入焦点 | 已实现稳定 focus-key 轮廓；选择关闭恢复已有 LVGL group 焦点；buffer_ui_motion.c、buffer_ui.c；设备键/辅助输入待验证 | 源码 | 待运行 |
| G06 | 页面进入与返回 | 已实现 SD/SL+ED 页面进入，关闭按真实 surface ID；退出立即失去命中并 STATIC；buffer_a2ui_lvgl.c | T1/T4 | 待运行 |
| G07 | 局部状态条出现/消失 | 已实现局部状态 ED；notice 空间变化按 flex，录音保持固定归属；buffer_ui.c:buffer_status_text/buffer_assistant_notice_refresh | 源码 | 待运行 |
| G08 | 滚动与边界 | 已实现手势优先与 STATIC 边界；buffer_a2ui_lvgl.c:ui_input_busy、buffer_todo_ui.c | T1 | 待运行 |
| G09 | 操作结果 | 已实现真实提交/回执后更新；无后端撤销则无假撤销；buffer_app_main.c、buffer_replica.c | T2/T6 | 待运行 |
| G10 | 可恢复错误 | 已实现原位置错误文案，无摇晃/闪屏；buffer_a2ui_lvgl.c:ui_failure、buffer_ui.c | T1仅状态边界，其余源码 | 待运行 |
| G11 | 异步新内容/状态快照 | 已实现稳定 ID/sequence 差异；重复快照 STATIC；buffer_a2ui_lvgl.c | T1/T4 | 待运行 |
| G12 | 字体/语言/尺寸变化 | 已实现文字角色/宽度/换行，字体尺寸变化 STATIC；buffer_ui_motion.c:buffer_ui_type、buffer_a2ui_lvgl.c | T1仅英文/逻辑宽度 | 待运行 |
| G13 | 闭屏/恢复、页面重新显示 | 已实现超长帧直接目标，不追赶计时/重播 hero；buffer_motion.c、buffer_oobe_ui.c | T1/T3 | 待运行 |
| O01 | 第一次展示欢迎页 | 已实现标题 ED 入场，QR STATIC；buffer_oobe_ui.c:buffer_oobe_ui_render | T1仅创建/生命周期 | 待运行 |
| O02 | 等待手机 | 已实现等待文案 STATIC，无假百分比；buffer_oobe_ui.c:ui_hint_text | 源码 | 待运行 |
| O03 | 欢迎 → 核对数字 | 已实现 hero 内容交接；数字一次完整更新且 STATIC；buffer_oobe_ui.c | T3，转换视觉仅源码 | 待运行 |
| O04 | 确认数字一致 | 已实现按钮 SF+状态色，成功只来自 domain；buffer_ui.c:buffer_oobe_action | T3 | 待运行 |
| O05 | 取消/拒绝/超时 | 已实现拒绝/超时 domain 恢复，旧数字/命中更新；buffer_ui.c、buffer_oobe_ui.c | T3 | 待运行 |
| O06 | 进入 NETWORK | 已实现 NETWORK 原生 WIFI 符号+ED 说明；buffer_oobe_ui.c | 源码 | 待运行 |
| O07 | 正在连接网络 | 已实现真实连接任务文字 STATIC（未知总量）；buffer_oobe_ui.c:ui_hint_text | T3，显示仅源码 | 待运行 |
| O08 | 网络失败 → 重试 | 已实现 retry_busy 禁用/连接提示，不重播全页；buffer_oobe_ui.c、buffer_ui.c:buffer_oobe_retry_request | T3 | 待运行 |
| O09 | NETWORK → SERVICE | 已实现 SERVICE 内容交接，固定步骤；buffer_oobe_ui.c | T3，视觉仅源码 | 待运行 |
| O10 | SERVICE 配置中/验证中 | 已实现配置/验证真实任务文字 STATIC；buffer_oobe_ui.c:ui_hint_text | T3 | 待运行 |
| O11 | 服务失败/重新验证 | 已实现服务错误/重试原位显示；buffer_ui.c:buffer_oobe_refresh、buffer_oobe_ui.c | T3 | 待运行 |
| O12 | SERVICE → READY | 已实现一次就绪形状/勾号入场，非持久完成；buffer_oobe_ui.c | T1/T3 | 待运行 |
| O13 | READY 空闲 | 已实现 READY STATIC，无假进度/旋转/循环；buffer_oobe_ui.c | T1重复READY | 待运行 |
| O14 | 点击“开始使用” | 已实现 start_busy 及防重入；buffer_ui.c:buffer_oobe_finish_request、buffer_oobe_ui.c | T3 | 待运行 |
| O15 | 完成标记持久化成功 → 首页 | 已实现真实持久完成后 HOME 门禁；HOME 直接交接 STATIC；buffer_ui.c:buffer_oobe_refresh | T3 | 待运行 |
| O16 | 完成标记写入失败 | 已实现写入失败留 READY+恢复提示；buffer_ui.c、buffer_oobe_ui.c | T3 | 待运行 |
| O17 | BLOCKED | 已实现 BLOCKED 说明+原生警示，不伪装成功；buffer_oobe_ui.c | T3，图形仅源码 | 待运行 |
| O18 | 步骤状态/重连恢复 | 已实现相同阶段不重播，步骤局部更新/恢复 STATIC；buffer_oobe_ui.c | T1/T3 | 待运行 |
| H01 | 进入/恢复日常首页 | 已实现首页真实快照恢复 STATIC；buffer_ui.c:buffer_ui_refresh | T6，视觉仅源码 | 待运行 |
| H02 | 日常 → 专注 | 已实现 mode 差异触发专注面板 SD，录音固定；buffer_ui.c | T6，转换仅源码 | 待运行 |
| H03 | 专注 → 日常/其他模式 | 已实现从专注恢复目标面板 SD；buffer_ui.c | T6，转换仅源码 | 待运行 |
| H04 | 睡前进入/退出 | 已实现睡前标记与卡片上下文，面板小位移/减弱 STATIC；buffer_ui.c | 源码 | 待运行 |
| H05 | 深挖进入/退出 | 已实现研究真实主题/时间 STATIC，面板 SD 不启动 session；buffer_ui.c | T6 | 待运行 |
| H06 | 打开模式选择/选中/关闭 | 已实现可见单选菜单/checked/关闭，直接目标模式；buffer_ui.c:buffer_mode_event/buffer_mode_select | 源码 | 待运行 |
| H07 | 手机远端模式更新 | 已实现远端 mode 快照只在差异时交接，录音按住时 STATIC；buffer_ui.c | T6，显示仅源码 | 待运行 |
| H08 | 专注秒数与进度圆环 | 已实现真实时钟数字/arc 更新 STATIC，不用动画计时；buffer_ui.c:buffer_focus_refresh | T6 | 待运行 |
| H09 | 专注提醒到期 | 已实现真实专注到期提示 STATIC；buffer_ui.c:buffer_focus_refresh | T6 | 待运行 |
| H10 | 研究到期/结束/中断 | 已实现研究结束状态局部文字 STATIC；buffer_ui.c | T6 | 待运行 |
| H11 | 空卡片 → 第一张卡 | 已实现空态→首卡 ID 差异 SD；buffer_ui.c | 源码 | 待运行 |
| H12 | 有卡片 → 空状态 | 已实现推荐空态，不暗示全副本删除；buffer_ui.c | 源码 | 待运行 |
| H13 | 短内容 ↔ 长内容/正文滚动 | 已实现长文自动高度/手指滚动 STATIC，无程序 scroll-top；buffer_ui.c | T1仅A2UI/待办布局 | 待运行 |
| H14 | 未配对/手机在线/离线/恢复 | 已实现真实连接状态差异 ED，缓存保留；buffer_ui.c:buffer_status_text | T6，显示仅源码 | 待运行 |
| H15 | 手动同步进行中/完成/冲突/失败 | 已实现真实同步请求/回执文本 ED，不提前庆祝；buffer_ui.c:buffer_sync_event | T6，UI仅源码 | 待运行 |
| H16 | 后台整理等待/失败/成功 | 已实现录音/后台处理原有阶段文案 ED，失败明确；buffer_ui.c、buffer_app_main.c | T2/T6 | 待运行 |
| R01 | 闲置 → 按下开始 | 已实现即时开始 intent、红色 pressed 配对+SF；buffer_ui.c:buffer_record_event | T2，颜色实际目标待验 | 待运行 |
| R02 | 持续录音 | 已实现真实持续秒数 STATIC，无伪波形；buffer_ui.c | T2，麦克风未验 | 待运行 |
| R03 | 松开保存 | 已实现正常释放保存，原音频尾帧与提交保留；buffer_app_main.c、buffer_audio.c | T2 | 待运行 |
| R04 | 滑出/失去触点/明确取消 | 已实现 PRESS_LOST 真取消/提交已开始时诚实提示；buffer_audio.c、buffer_app_main.c | T2 | 待运行 |
| R05 | 无声/太短/采音失败 | 已实现零帧拒绝、采音/保存错误显示；ASR 无声沿原处理失败，不伪造结果；buffer_audio.c、buffer_ui.c | T2部分，其余源码 | 待运行 |
| R06 | 保存成功 → 上传/待处理 | 已实现真实保存后待同步文本 STATIC；buffer_audio.c、buffer_ui.c | T2 | 待运行 |
| R07 | 转写/分类/助手处理 | 已实现现有实际转写/处理阶段展示 STATIC；buffer_ui.c、buffer_agent.c | T6，UI仅源码 | 待运行 |
| R08 | 处理成功 | 已实现真实结果进入卡片/A2UI，SD/ED；buffer_ui.c、buffer_a2ui_lvgl.c | T1/T4/T6 | 待运行 |
| R09 | 处理失败、重试、重新连接 | 已实现失败/重试沿原 durable worker，不以动画重做业务；buffer_ui.c、buffer_agent.c | T6 | 待运行 |
| R10 | OOBE 未完成/入口不可用 | 已实现 OOBE 门禁及入口不可见，不绕过 domain；buffer_ui.c、buffer_app_main.c | T2/T3 | 待运行 |
| R11 | 页面切换、提醒出现时仍在录音 | 已实现全局固定 recorder，按压时延后任务重建/提醒不抢页；buffer_a2ui_lvgl.c、buffer_ui.c | T1部分，密集触摸待验 | 待运行 |
| C01 | 上一张/下一张 | 已实现方向 SD，ID 变化才触发、不排旧动画；buffer_ui.c:buffer_previous_card_event/buffer_next_card_event | T1 spring，其余源码 | 待运行 |
| C02 | 首尾边界 | 已实现首尾禁用 STATIC，无抖动；buffer_ui.c:buffer_ui_refresh | 源码 | 待运行 |
| C03 | 卡片整体与独立按钮按下 | 已实现非交互卡容器 STATIC，内部按钮独立 SF；buffer_ui.c | 源码 | 待运行 |
| C04 | 归档/完成/稍后 | 已实现所属卡片动作/真实状态提交，内容按 ID 变化交接；buffer_ui.c、buffer_app_main.c | T6 | 待运行 |
| C05 | 提醒/深挖 | 已实现提醒/深挖原 domain 操作、文案 STATIC，不提前庆祝；buffer_ui.c | T6 | 待运行 |
| C06 | 打开/关闭更多操作 | 已实现所属卡片更多菜单 SD，关闭恢复已有 group 焦点；buffer_ui.c:buffer_card_more_event | 源码 | 待运行 |
| C07 | 新卡、编辑后的快照 | 已实现同 ID 编辑原地 STATIC，新 ID SD，重复不入场；buffer_ui.c | T6，UI仅源码 | 待运行 |
| C08 | 删除/撤销或失败 | 现有首页不提供删除/撤销入口；STATIC，保留 domain 墓碑/失败，不虚构撤销；buffer_ui.c、buffer_agent_model.c | T6 | 待运行 |
| C09 | 自动高度变化、图片与长文本 | 已实现卡片自动高度/正文换行，图形裁剪由容器；STATIC；buffer_ui.c、buffer_a2ui_lvgl.c | T1逻辑宽度 | 待运行 |
| C10 | 嵌套卡片/Row/按钮组 | 已实现嵌套深度减 padding/Row 限宽，整组 SD，无 child 延迟；buffer_a2ui_lvgl.c | T1部分 | 待运行 |
| C11 | 远端更新与本地动作并发 | 已实现原有 card.action/card.state 回执 fencing，呈现接受状态 STATIC；buffer_replica.c、buffer_agent_model.c | T6 | 待运行 |
| C12 | 助手/主页之间切换卡片 | 已实现任务进入 SD、关闭 STATIC 返回，保留 domain 任务；buffer_a2ui_lvgl.c | T1/T4 | 待运行 |
| A01 | 任务页打开/关闭 | 已实现只转换任务内容，关闭立即隐藏/按 surface ID；buffer_a2ui_lvgl.c | T1 | 待运行 |
| A02 | 助手问答结果首次出现 | 已实现完整问答按层级 ED/SL 呈现，无逐字伪流式；buffer_a2ui_lvgl.c | T1/T4 | 待运行 |
| A03 | 同一回答内容更新 | 已实现稳定 Text 对象/原地内容更新 STATIC；buffer_a2ui_lvgl.c:render_node | T1 | 待运行 |
| A04 | 翻译页首次进入 | 已实现翻译原文/语言/译文固定顺序，父内容交接；buffer_a2ui_lvgl.c:ui_render_translation | T4，显示仅源码 | 待运行 |
| A05 | 翻译替换/失败 | 已实现 payload 差异局部替换/错误原位；buffer_a2ui_lvgl.c | T1机制，其余源码 | 待运行 |
| A06 | 菜谱页打开/滚动 | 已实现菜谱步骤一次成组呈现，滚动 LIVE，无逐步延迟；buffer_a2ui_lvgl.c | T4，显示仅源码 | 待运行 |
| A07 | 点击菜谱步骤“开始计时” | 已实现真实创建成功再 bind timer，按钮 SF；buffer_a2ui_lvgl.c:ui_action_event | T4 | 待运行 |
| A08 | 步骤计时进行/暂停/结束 | 已实现步骤绑定计时，关闭不 cancel；buffer_a2ui_lvgl.c:ui_timer_controls | T1/T4 | 待运行 |
| A09 | 独立计时卡展开/收起 | 已实现 inline 内容 SD/ED，关闭立即 STATIC；buffer_a2ui_lvgl.c:ui_render_surface | T1/T4 | 待运行 |
| A10 | 倒计时运行 | 已实现真实 remaining 数字稳定 STATIC；buffer_a2ui_lvgl.c:ui_dynamic_refresh | T1/T4 | 待运行 |
| A11 | 暂停/继续 | 已实现 running 暂停/paused 继续，操作即时 domain；buffer_a2ui_lvgl.c | T1/T4 | 待运行 |
| A12 | 结束计时 | 已实现结束/失效 toggle 禁用，无空动作；buffer_a2ui_lvgl.c | T1 | 待运行 |
| A13 | 计时到期 | 已实现到期一次 notice +可关闭提醒，任务持续可找；buffer_ui.c、buffer_a2ui_lvgl.c | T1/T4 | 待运行 |
| A14 | 闹钟设定/查看 | 已实现闹钟实际日期/状态 STATIC；buffer_a2ui_lvgl.c:ui_dynamic_refresh | T4，显示仅源码 | 待运行 |
| A15 | 闹钟触发 | 已实现闹钟 notice，不强制激活；buffer_ui.c:buffer_assistant_notice_refresh | T4，密集触摸待验 | 待运行 |
| A16 | 关闭闹钟提醒/取消闹钟 | 已实现真实 cancel/错误原位，已取消禁用；buffer_a2ui_lvgl.c | T1状态机制/T4 | 待运行 |
| A17 | 任务卡关闭后重新打开 | 已实现重新显式打开读真实 domain，不回放旧秒数；buffer_a2ui_lvgl.c | T4 | 待运行 |
| A18 | 助手插图、步骤图、进度 | 已实现助手装饰 STATIC，不重复品牌 hero；buffer_a2ui_lvgl.c | 源码 | 待运行 |
| A19 | 新提醒到来、其他任务正打开 | 已实现当前任务保留，新结果显式入口/提醒 notice；buffer_a2ui_lvgl.c、buffer_ui.c | T1新任务等待 | 待运行 |
| A20 | 工具失败、结果过长、失效操作 | 已实现错误反馈、长文限宽、失效禁用；buffer_a2ui_lvgl.c:ui_failure/ui_dynamic_refresh | T1部分/T4 | 待运行 |

## 新增待办整列表（独立补充，不虚增原 90 项计数）

实现路径：`buffer_todo.c/.h`、`buffer_todo_ui.c`、`buffer_app_main.c:buffer_request_action_id`，入口位于首页正文。任务型列表和五张推荐投影相互独立，不新增“记录/助手”全局模式。

- 仅 `task_card`；本地记录必须 ready；隐藏 done/archived/deleted、replica tombstone、pending_transcription，不把原始音频当待办。任意存在的 phone replica（包括墓碑）优先于同 ID 本地 segment；ready records 中未回流项目仍可达。没有伪造日期/逾期分组。
- 稳定 ID cursor 双向分页，每次最多 8 个 item；扫描时只解析当前一个 JSON，不复制 1024 项数组或创建 1024 个 LVGL 对象。查询/文件 I/O 和明确 ID 操作在专用 worker，UI 仅取有界页面；八行对象池按 ID 复用，详情展开状态随 ID 保留；刷新避开手指按压/滚动，不强制 scroll-top。不承诺未经设备测量的千项扫描耗时。
- 每项独立“完成待办”和“查看详情”命中；长标题换行。不是偷换当前首页 `card_index`。远端复用原 durable `card.action` 单槽；busy 时序列化并清楚禁用，落盘失败不排队、不假完成。本地动作复用 `buffer_agent_apply_action` 和 `card.state` durable outbox。pending revision 仍显示“待同步 · 尚未确认完成”；真实回执/权威快照到来后才从列表消失。
- STATIC row 更新，整组进入可 SD，禁用/选中复用公共样式；减弱动效直接最终状态。关闭列表只收起视图。

| 补充场景 | Host 实测 | 设备状态 |
| --- | --- | --- |
| 超过五张、所有符合条件项可达/正反分页 | T5：139项，8项一页，全遍历无遗漏 | 待运行 |
| phone 权威/墓碑/本地未回流/同 ID 去重/未转写排除 | T5 | 待运行 |
| 明确 ID 完成，不改变首页选中卡 | T5 | 待运行 |
| 单槽 busy、持久化失败、pending 不假完成 | T5+T6 | 待断网/冲突端到端验收 |
| 240×320 长标题、行 ID/展开详情复用 | T1：真实 LVGL+ASan/UBSan | 待中文字体与触摸验收 |

## 构建与验证

原有 36 host item 全部保留；本轮增加 2 item。最后执行的命令、结果、ELF/bin 路径和 SHA-256 见下方交付记录。Android 308 tests+lint+assemble 是前一测试代理的结果，本轮没有改 Android 产品 UI，也没有将其作为本轮重新执行结果。

构建使用项目既有 `tools/build_vela_sim_isolated.py`，SDK `/home/simon/vela`，独立根 `/home/simon/.cache/buffer-vela/m3e-20261019`。host venv/tools 复用缓存；全量 goldfish-arm64 构建只产生隔离目录里的构建盘，没有触碰原 SDK `.config` 或已有设备盘。增量修复后重新执行完整 `cmake --build ... --parallel 12`，不是仅链接一个 UI object。

## Self-review 与尚未完成的验收

1. 所有 90 项有对应代码或明确 STATIC/不适用策略；不适用的是首页不存在的删除/撤销入口，不假造后端。未把表格填满当成运行通过。
2. 已检查动画无业务 ready callback；commit、音频、工具幂等、timer/alarm 时钟继续由原 domain 决定。发现并修正 recorder pressed 被通用绿色覆盖、旧页面关闭可能关闭新任务、单槽动作覆盖、局部删除 callback 清理、inline 计时错误在专注模式不可见和字体层级遗漏等问题。
3. 尚未完成 **320×480/240×320 中文整屏视觉与触摸**、所有 OOBE 失败/重连的真实设备流程、录音驱动尾帧/滑出实际触点、断网待办回执/冲突端到端、深层长菜谱/翻译的人工阅读验收、键/辅助输入初始焦点/返回与物理触摸尺寸、设备无障碍语义通道、真实帧率/CPU/输入延迟。Host 使用英文字体和受控 domain 输入，不能消除这些边界。
4. 减弱动效设置本轮为 UI 会话和启动环境策略，未新增系统级持久无障碍设置；未声称设备具备屏幕阅读器。A2UI 仍使用有界 snapshot 结构，当前改造保留了一份 incoming 以保护正在阅读的任务，目标设备内存预算应实测。
5. 不交付伪造截图；没有运行已关闭设备或模拟器。任何需要设备的新验收必须等用户另行允许；此报告不代替该验收。


## 持久交付产物

最终完整构建成功，日志：`/home/simon/.cache/buffer-vela/m3e-20261019/build-delivered.log`。产物为整个 goldfish-arm64 固件，没有安装/启动。已用 `file` 确认 ARM aarch64 ELF，并用工具链 `nm` 确认包含 `buffer_spring_step`、`buffer_ui_motion_init`、`buffer_todo_page`、`buffer_request_action_id`、`buffer_audio_record_cancelable` 的实际链接符号。

| 文件（绝对持久路径） | 大小 bytes | SHA-256 |
| --- | ---: | --- |
| `/home/simon/.cache/buffer-vela/m3e-20261019/artifacts/vela-m3e-goldfish-arm64.elf` | 246396896 | `266b1a166e5d1d484756b1decef724e06fcc7c98218398d096fa67ef1537a7ff` |
| `/home/simon/.cache/buffer-vela/m3e-20261019/artifacts/vela-m3e-goldfish-arm64.bin` | 27578368 | `1501e4d6781734acb42e1e2d03a4a236d0b2d47ca965ad920f6fc4b828f894d4` |

可复核清单：`/home/simon/.cache/buffer-vela/m3e-20261019/artifacts/SHA256SUMS`、同目录 `manifest.json`。18 个实际构建输入文件的 SHA-256 位于 `/home/simon/.cache/buffer-vela/m3e-20261019/source-sha256.json`，产物复制时再次确认源码未变化。ELF 保留调试信息，体积不是可烧录镜像大小；bin 另列。

复现构建入口：

```sh
python3 tools/build_vela_sim_isolated.py --sdk /home/simon/vela   --root /home/simon/.cache/buffer-vela/m3e-20261019 -j 12
# 已准备的隔离目录可用相同缓存 tools PATH 继续完整构建：
cmake --build /home/simon/.cache/buffer-vela/m3e-20261019/build --parallel 12
```

该脚本只准备/构建，不运行模拟器。复用缓存 PATH 包含 `host-venv/bin`、`host-tools/usr/{bin,sbin}`、SDK `prebuilts/tools/linux/x86_64` 和 `prebuilts/gcc/linux-x86_64/aarch64-none-elf/bin`；不需要更改 SDK 活动配置。


## 最终实跑结果（最终源码）

| 实跑检查 | 结果 | 持久证据 |
| --- | --- | --- |
| `python3 -m pytest -q` | **38 passed in 51.28s**，原有36项+新增2项，无跳过 | `/home/simon/.cache/buffer-vela/m3e-20261019/host-delivered.log` |
| `python3 -m pytest --collect-only -q` | 38 tests collected，0.03s；仅核对收集，不代替执行 | 测试 collector 保留 |
| 完整 goldfish-arm64 `cmake --build ... --parallel 12` | PASS，最终 `post_build` 成功 | `/home/simon/.cache/buffer-vela/m3e-20261019/build-delivered.log` |
| 真实 LVGL host + ASan/UBSan | 包含在38项中通过；stable IDs/scroll、销毁取消、timer状态/inline失败、小屏待办行复用/空页恢复 | `tests/fixtures/ui/ui_host.c`、`~/.cache/buffer-vela/m3e-host-ui/build.log` |
| `sha256sum -c SHA256SUMS` | ELF 与 bin 均 OK | `artifacts/SHA256SUMS` |
| `git diff --check` | PASS | 无 commit/push |

以上均不是90场景设备运行通过；全部设备运行与视觉边界仍按前文待验收执行。

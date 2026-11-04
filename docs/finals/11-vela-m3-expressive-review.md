# Vela 界面与 Material 3 Expressive 对照审查

审查日期：2026-10-19。结论：**当前界面采用了部分 Material 3 色彩与形状语言，但尚不足以称为完整的 M3 Expressive 实现。最需要调整的是任务与信息层级、交互语义、卡片容器范围、状态反馈和动效，不能只调整圆角或加插图。**

这是设计与实现对照，不是官方认证或符合率评分。审查未修改 UI、未启动模拟器，也没有把静态截图当作触摸、动画或无障碍运行验证。

## 规范来源与审查边界

直接读取 Material 官网当前发布的 23 个页面的数据，包含各页的 Overview、Specs、Guidelines、Accessibility 等已发布 section，不只阅读搜索摘要。官网页面需要 JavaScript；本次根据官网前端路由及其 `/_dsm/content/m3/…` 发布接口读取正文，发布版本为 `2026-10-13_06-10-19`。原始规范仅缓存于本机，不复制进项目，不提交第三方整篇文档。

重点来源：

| 维度 | 官方规范 |
|---|---|
| 可用性与任务层级 | [Usability](https://m3.material.io/foundations/usability) |
| 视觉与交互无障碍 | [Accessibility: Designing](https://m3.material.io/foundations/designing)、[Writing](https://m3.material.io/foundations/writing) |
| UI 状态与手势 | [States](https://m3.material.io/foundations/interaction/states)、[Gestures](https://m3.material.io/foundations/interaction/gestures)、[Selection](https://m3.material.io/foundations/interaction/selection) |
| 动效体系 | [Motion physics: How it works / Specs](https://m3.material.io/styles/motion/overview/how-it-works) |
| 样式与布局 | [Color roles](https://m3.material.io/styles/color/roles)、[Typography](https://m3.material.io/styles/typography)、[Shape](https://m3.material.io/styles/shape)、[Layout](https://m3.material.io/foundations/layout/layout-overview)、[Grids & spacing](https://m3.material.io/foundations/layout/grids-spacing) |
| 操作与选择组件 | [Buttons](https://m3.material.io/components/buttons)、[Button groups](https://m3.material.io/components/button-groups)、[Menus](https://m3.material.io/components/menus) |
| 容器与反馈组件 | [Cards](https://m3.material.io/components/cards)、[Dialogs](https://m3.material.io/components/dialogs)、[Progress indicators](https://m3.material.io/components/progress-indicators)、[Loading indicator](https://m3.material.io/components/loading-indicator)、[Snackbar](https://m3.material.io/components/snackbar) |
| 文案与插图语义 | [UX writing](https://m3.material.io/foundations/content-design/style-guide)、[Alt text](https://m3.material.io/foundations/content-design/alt-text) |

实现依据为当前 `buffer_ui.c`、`buffer_ui_theme.h`、`buffer_oobe_ui.c`、`buffer_a2ui_lvgl.c` 及 A2UI model。视觉依据为 `docs/finals/assets/` 中 2026-10-18 的首页、OOBE、计时器和问答截图；它们不是本次新构建的运行截图。

M3 Expressive 是 M3 的扩展，不等同于“所有元素都要夸张”。品牌静态配色、MiSans、扁平卡片均可以保留。LVGL 自绘也可以实现相应设计行为，不必为了符合规范改用 Compose。不能把 Wear OS 的圆屏布局、字体或小目标例外直接搬到矩形 Vela 屏幕。

## 当前值得保留的基础

- 录音入口已统一；56px 高的深色主按钮与其余浅色操作存在主次差异。无需恢复“记录 / 助手”手动切换。
- OOBE 已有独立页面、步骤、配对数字核对、重试和明确的完成入口；欢迎标题比业务首页更有表现力。
- 首页正文可滚动，头尾控制区分开；助手结果页为录音按钮预留空间。这个结构比浮动按钮遮挡正文更可靠。
- 基础文字对比度大多良好；问题并不是整个绿色品牌色都不合适。
- 卡片数据副本与首页最多五张的投影已分离。五张是显示选择上限，不等于只同步五张数据。

## 主要问题与优先级

这里的优先级针对设计整改，不等同于代码审计中的安全严重级别。

### 优先修复：OOBE 主按钮按下后的文字对比度

[buffer_oobe_ui.c:90](/home/simon/vela/contest2026_014_makahu/app/buffer_app/buffer_oobe_ui.c:90) 中，主按钮正常状态为白字 `#FFFFFF` / 深绿 `#3D6650`，按下后背景改为 `#A9D4BA`，文字仍为白色。

按 sRGB 相对亮度计算：正常约 **6.53:1**，按下后约 **1.64:1**。这不足以维持正常小字号按钮文字的可读性。该按钮处于 pressed 而非 disabled，不能套用 disabled 状态的对比度例外。修正时应成对定义容器色和 on-color，或采用不会破坏文本对比度的 state layer。依据：[States](https://m3.material.io/foundations/interaction/states)、[Typography](https://m3.material.io/styles/typography/applying-type)。

其他已核算的静态组合：

| 前景 / 背景 | 对比度 | 判断 |
|---|---:|---|
| `#414942` / `#F7FAF3` | 8.83:1 | 正常小文字有足够对比度 |
| `#181D18` / `#EAEFE6` | 14.65:1 | 卡片正文有足够对比度 |
| `#181D18` / `#BFECD0` | 13.13:1 | 浅绿按钮深字有足够对比度 |
| `#FFFFFF` / `#BA1A1A` | 6.46:1 | 录音红底白字有足够对比度 |

这些是代码色值计算，不涵盖透明混合、渐变、屏幕亮度、实机眩光和所有默认 LVGL 状态。

### 高优先级：首页任务层级仍被大量等权按钮稀释

[buffer_ui.c:253](/home/simon/vela/contest2026_014_makahu/app/buffer_app/buffer_ui.c:253) 为大多数操作提供同一浅绿胶囊样式。首页中卡片动作、上一张、下一张、同步、切换模式同时争夺注意力；记录按钮虽已更突出，但维护操作仍占据较多首屏空间。

建议先明确每屏主要任务，再降低次要操作的视觉权重：录音保持主操作；卡片只露出高频且与内容相关的动作；低频动作进入带文字的更多菜单；同步状态可以常驻，但手动强制同步不必长期与翻页同等突出。不要把“上一张 / 下一张 / 同步”做成表示单选的 connected group。依据：[Usability](https://m3.material.io/foundations/usability)、[Buttons](https://m3.material.io/components/buttons/guidelines)。

### 高优先级：模式按钮是隐式轮换，不是可见的状态选择

[buffer_ui.c:220](/home/simon/vela/contest2026_014_makahu/app/buffer_app/buffer_ui.c:220) 按固定顺序轮换 `normal → focus → bedtime → rabbit_hole`。用户无法预见下一状态，也无法直接选目标；为了到达某模式，可能先进入其他有计时或研究副作用的模式。即使网络同步修好，这个交互仍不够可预测。

建议当前模式作为入口，点击后展示带当前项标记的单选菜单；宽度足够时也可以采用 connected button group。新版规范建议 connected group 替代旧 baseline segmented button，且组内不应随意折成多行；240px 小屏不应硬塞四个选项。依据：[Button groups](https://m3.material.io/components/button-groups/guidelines)、[Menus](https://m3.material.io/components/menus)、[Selection](https://m3.material.io/foundations/interaction/selection)。

### 高优先级：A2UI 的文字层级没有完整落到渲染器

model 能识别 `h1/h2/h3/caption/body`，但 [buffer_a2ui_lvgl.c:366](/home/simon/vela/contest2026_014_makahu/app/buffer_app/buffer_a2ui_lvgl.c:366) 只将 `variant == 2` 作为 prominent 传入，[buffer_a2ui_lvgl.c:181](/home/simon/vela/contest2026_014_makahu/app/buffer_app/buffer_a2ui_lvgl.c:181) 的 prominent 主要改变颜色。标题、正文、说明文字没有完整字号/字重/行高映射。

因此计时器的剩余时间仍像普通说明文字，问答和翻译缺少足够明确的标题/正文结构。首页已有 20/14px、OOBE 30px、专注计时 42px，说明不是完全没有字号区分，而是角色体系没有贯通。建议建立少量设备适配后的 display、title、body、label 角色；不要机械复制手机的全部字号。依据：[Applying type](https://m3.material.io/styles/typography/applying-type)。

### 高优先级：尚未建立项目统一的动效与交互状态体系

在三份 UI 实现中未发现项目显式配置的 spring scheme、形变反馈、页面转场或 reduced-motion 设置；显隐切换主要靠 flag，A2UI 重置滚动使用 `LV_ANIM_OFF`。LVGL 默认主题可能提供部分效果，但不能据此推断它符合 M3 Expressive。

目前需补的是状态与空间关系：按下可感知、取消可回退、内容从哪里来、返回到哪里、后台过程是否仍在进行。不能只增加整屏弹跳。依据：[Motion physics](https://m3.material.io/styles/motion/overview/how-it-works)。

### 中高优先级：录音误触、保存与失败的交互契约不完整

[buffer_ui.c:165](/home/simon/vela/contest2026_014_makahu/app/buffer_app/buffer_ui.c:165) 对 RELEASED 和 PRESS_LOST 都执行保存，没有独立的取消入口。按住录音本身是合法的产品交互，但滑出控件、意外失去触点与明确松开保存目前含义相同，容易产生短片段。

建议定义并展示：按下开始、保持录音、松开保存、取消的明确方式，以及保存/转写/处理失败后的下一步。是否采用滑出取消或独立取消区，应在小屏上验证，不能只靠颜色暗示。单纯换上 Material 图标不会解决这项问题。依据：[Gestures](https://m3.material.io/foundations/interaction/gestures)、[UX writing](https://m3.material.io/foundations/content-design/style-guide)。

### 高优先级：计时器动作没有随当前状态清楚变化

[buffer_a2ui_lvgl.c:240](/home/simon/vela/contest2026_014_makahu/app/buffer_app/buffer_a2ui_lvgl.c:240) 总是创建“暂停 / 继续”及“结束计时”按钮；动态刷新更新了剩余时间说明，却没有相应维护这些按钮的标签和可用状态。[动作处理](/home/simon/vela/contest2026_014_makahu/app/buffer_app/buffer_a2ui_lvgl.c:130) 在非 running/paused 状态会直接 return，最终 execute 的错误也被丢弃。由此可确认代码存在“看起来可按，但该状态不执行”的路径；本次未在模拟器复现。

建议 running 显示“暂停”，paused 显示“继续”，结束后提供与提醒状态匹配的动作；不再可执行的操作应移除或明确禁用，操作失败则给出可理解的反馈。关闭卡片与停止计时继续保持两个不同操作。依据：[States](https://m3.material.io/foundations/interaction/states)、[Buttons](https://m3.material.io/components/buttons)。

### 中优先级：过程、结果、后台任务需要分层反馈

当前有“正在保存”“同步中”“稍后重试”等文字，也有 OOBE busy 禁用状态，这些应保留；但尚缺一致的可视进度/后台任务呈现方式。未知总量的联网或模型请求不能画假百分比，云端长任务也不适合永久占据一个装饰性短时 loader。

建议短任务采用明确的进行中反馈；较长任务用适合等待时长的 progress 表达，允许离开并在完成/失败后找回结果；可重试错误给出原因和具体动作。记录已保存、音频已上传、转写成功、卡片生成是不同阶段，不应都写成“同步完成”。依据：[Loading indicator](https://m3.material.io/components/loading-indicator)、[Progress indicators](https://m3.material.io/components/progress-indicators)、[Snackbar](https://m3.material.io/components/snackbar)。

## 卡片范围：数据、容器、命中与内容四个层面

### 1. 数据范围与展示范围

[buffer_app.h:17](/home/simon/vela/contest2026_014_makahu/app/buffer_app/buffer_app.h:17) 限制首页投影为五张；这不是传输层的数据上限。界面“卡片 1/5”容易被理解为全部收藏只有五张。建议表达为“当前推荐 / 此刻卡片”，并在需要时提供查看其余内容的入口；推荐算法和数据同步是否正确属于另外的功能验证。

### 2. 容器与动作归属

[buffer_ui.c:1016](/home/simon/vela/contest2026_014_makahu/app/buffer_app/buffer_ui.c:1016) 将标题/摘要放入 card，而 action row 是 body 的另一个子节点。视觉上动作在卡片底色外，归属关系弱。建议让单卡动作与该卡共享清楚的容器关系，或明确设计为上下文工具栏，避免与全局操作混淆。

官方卡片强调一个主题和内部层级，但**不要求每张卡必须有阴影、必须可点击或必须有插图**。内容卡可以是非交互容器；若作为详情入口，应定义整卡点击反馈，并保证内部按钮命中优先，不让点击空白误触归档等动作。当前普通卡片没有显式详情点击回调，不能把它评为“点击详情已实现”。依据：[Cards](https://m3.material.io/components/cards/guidelines)。

### 3. 长内容、嵌套和可视边界

首页卡片自动高度及正文换行是优点。但 [buffer_a2ui_lvgl.c:181](/home/simon/vela/contest2026_014_makahu/app/buffer_app/buffer_a2ui_lvgl.c:181) 对 Row 中的文字使用 `LV_SIZE_CONTENT`，没有显式可用宽度上限；超长文字、语言切换和嵌套容器仍有超宽风险，尚未运行验证。

[buffer_a2ui_lvgl.c:346](/home/simon/vela/contest2026_014_makahu/app/buffer_app/buffer_a2ui_lvgl.c:346) 递归使用相同 surface、16px padding 和圆角。协议层深度限制保证有界，并不保证深层卡片仍可读。建议限制视觉层级：主卡承载主题，内部步骤用留白/分隔线分组；只有独立子任务才再使用卡片。插图、标题、按钮组都应受容器内容宽度约束，不能依靠截断掩盖溢出。

### 4. 助手结果页的范围

[buffer_a2ui_lvgl.c:527](/home/simon/vela/contest2026_014_makahu/app/buffer_app/buffer_a2ui_lvgl.c:527) 阻挡背景，同时为录音按钮留出区域。因此更准确的语义是“保留全局录音入口的任务页”，不能直接叫标准全屏模态对话框。

要明确关闭只收起页面还是终止计时；新录音是否替换当前结果；关闭后能否重新找到运行中的计时器；返回时保留什么阅读位置。不要把所有回答、菜谱、闹钟都套用“助手 / 关闭”的同一层级。当前关闭页面与后台计时分离是应保留的行为。依据：[Cards](https://m3.material.io/components/cards)、[Dialogs](https://m3.material.io/components/dialogs)。

## 插图、图标与装饰

[buffer_oobe_ui.c:122](/home/simon/vela/contest2026_014_makahu/app/buffer_app/buffer_oobe_ui.c:122) 的品牌插图使用固定 74/61 弧长和渐变 B 徽章；NETWORK、SERVICE、READY 等状态复用该图。**它是装饰，不是真实进度。** 圆环作为品牌形状并不自动违反规范，但放在联网/验证场景容易被误认为百分比，应避免进度歧义。说明文字目前仍是理解状态的主要依据。

建议按语义定义插图，而非每页随机增加装饰：

| 场景 | 插图职责 | 必须保留的文字/操作 |
|---|---|---|
| 首次连接 | 暗示手机与设备的关系，视觉让位于二维码 | 怎么扫描、连接哪台设备 |
| 核对数字 | 解释两屏核对，而非庆祝连接完成 | 安全数字、确认与取消 |
| 网络/服务准备 | 说明正在做什么；装饰与进度分开 | 当前步骤、失败原因、重试 |
| 设置完成 | 提供清楚的完成时刻，可用品牌形状或勾号 | 开始使用 |
| 空内容 | 说明如何留下第一条记录 | 录音主入口及可选说明 |
| 错误/离线 | 解释可恢复的情境，不暗示数据丢失 | 已保存内容、下一步与重试 |

卡片中的插图应帮助辨识主题或步骤，不要求每张卡都配图。承载任务信息的图像应有等价文字；纯装饰不应成为额外焦点或重复朗读。LVGL 没有直接继承 Web alt-text 或 Android TalkBack，需定义设备可用的语义/替代通道。依据：[Shape](https://m3.material.io/styles/shape)、[Alt text](https://m3.material.io/foundations/content-design/alt-text)。

现有二维码的黑白对比和周边留白应保留，不套花形遮罩、不形变、不让装饰盖入扫描区域。背景装饰可以合理出血，但不能与卡片可点击边界、错误提示或安全数字混淆。麦克风已经使用 Material Symbols Rounded；后续图标应统一来源、光学大小和线重，不能以增加图标数量代替文字清晰度。

## 动效建议矩阵

下表是针对 Vela 的实施建议，不是声称当前已有实现。

| 场景 | 行为与层级 | 建议动效角色 |
|---|---|---|
| 按钮按下/松开 | 即时反馈；失去触点与取消定义一致 | fast spatial 形状，fast effects 颜色 |
| 模式选择 | 展示候选项和当前选择，确认后改变内容 | 局部形状/颜色反馈；旧状态不闪回 |
| 卡片上一张/下一张 | 有方向关系，操作归属随卡片一起移动 | 空间运动；连续点击可中断并重定向 |
| OOBE 下一阶段 | 保留步骤连续性，不让二维码与核对数字乱跳 | 容器/内容转换；完成时可有一次 hero moment |
| 助手页展开/关闭 | 从发起上下文进入，关闭恢复可理解的位置 | 页面空间转换 + 内容 effects |
| 录音中 | 清楚表示持续录制，计时值可读 | 克制的活动反馈，不持续整屏弹跳 |
| 倒计时每秒更新 | 稳定数字布局，不把 tick 当整页切换 | 通常不需要反复入场动画 |
| 错误/后台结果 | 更新局部，不抢当前任务 | effects，必要时轻量提示 |

规范区分 spatial 与 effects：位置、尺寸、形状可用空间弹性；颜色和透明度不能越界弹跳。按 fast/default/slow 与设备情境选择，不把单一 easing 和 duration 复制给一切。官方 Web 转换表是特定平台退化方案，不能当作 LVGL 的通用强制时长；交互可被打断时要保留连续性。需提供减弱动效策略，并在真正硬件上验证帧率、输入延迟和 CPU 占用。依据：[Motion Specs](https://m3.material.io/styles/motion/overview/specs)。

## 触摸、字体与无障碍的验证边界

已见按钮视觉高度有 30、38、40、44、46、48、56px 等不同值；其中提示行的“查看”有可点击父容器，不能只拿内部按钮高度判断命中范围。OOBE 底部等待提示也不是按钮，不能误报为过小触摸目标。

Material 对多数平台建议至少 48×48dp 的触摸范围，并区分视觉大小和真实命中区域。当前代码主要使用裸 px；**不能直接把 40px 判为 40dp，也不能因为某按钮 48px 就宣布达标**。需要确认面板物理尺寸、逻辑密度、点击扩展、相邻目标是否重叠和手指实际可达性。依据：[Accessibility designing](https://m3.material.io/foundations/designing)。

尚需验证：焦点初始位置及返回恢复、硬件键/辅助输入、默认 LVGL pressed/focus/disabled 状态、字体放大后的布局、中文长标题、减弱动效、屏幕真实对比度。不能把“没有发现项目配置”表述为平台绝对不支持。

## 建议整改顺序与验收

1. 先修按下态对比度、模式可见选择、录音取消/失败恢复，解决操作可理解性。
2. 再整理首页主次、卡片容器与动作归属、计时/问答/翻译的文字角色，定义结果页和全局录音的关系。
3. 建立共用 component/state/motion token 映射，OOBE 与 A2UI 共用；再加入与任务有关的插图和关键时刻动效。
4. 最后在用户允许启动模拟器后验收 320×480、240×320、中文长文、长菜单、嵌套卡片和连续触摸；实机补充物理触摸尺寸、语音输入与帧率验收。

最低验收情境：首次连接、配对取消/重试、服务超时、开始使用、按住录音/取消/保存失败、四模式直接选择、卡片翻页与更多操作、计时器暂停/关闭页面后触发、助手结果返回、离线恢复。每项都记录入口、可见状态、可执行动作、退出后的状态和是否可恢复，而不仅截一张成功图片。

这份报告仅完成调研，不表示上述建议已经实现。


全页面、全组件及中断/恢复的完整动效清单见 [12-vela-motion-inventory.md](12-vela-motion-inventory.md)，包含 90 个具名场景，明确区分目标行为与已验证实现。


后续实施与验证记录（保留以上历史结论）：[13 Vela M3E 实施报告](13-vela-m3e-implementation.md)。

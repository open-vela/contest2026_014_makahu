# A2UI 与 LVGL 渲染器设计

日期：2026-10-17。状态：已实现并通过宿主检查的资源受限 A2UI v0.9.1 子集；不是完整官方协议实现。已安装 Windows Vela 模拟器固件，实际云端计时器、闹钟、翻译和菜谱页面已完成可见状态验收；菜谱步骤计时可启动和结束。具体证据及硬件限制见 [运行验收](05-runtime-verification.md)。

关联：[Coordinator 自动选举](02-coordinator-election.md)、[Agent 能力下放 Vela](03-vela-agent-capabilities.md)。

## Foundation Fabric 开发仓库位置

框架相关开发在独立仓库 **`/home/simon/projects/mocha/foundation.fabric`**（即 `~/projects/mocha/foundation.fabric`）进行，远端为 [6xingyv/foundation.fabric](https://github.com/6xingyv/foundation.fabric.git)。

`/home/simon/vela/contest2026_014_makahu/foundation.fabric/` 是参赛项目内的源码快照，不是本轮框架修改的工作仓库。本文指向项目内 Fabric 文件的相对链接仅供阅读当前快照；实现前应在上述独立仓库核对最新接口与约束。框架变更完成并验证后，再按需同步到参赛项目快照。

## 1. 产品目标

Vela 是独立的语音助手，Buffer 是其中一大块跨设备能力。助手还可以处理计时、闹钟、翻译和分步骤指导。界面应随任务变化，同时保持操作一致、可预测。

采用自行实现的 A2UI LVGL renderer。模型使用受限组件目录描述界面，设备负责原生绘制、交互和实际业务执行。现有首页、录音入口、设置和错误恢复界面保留确定性的本地实现。

当前入口见 [Vela 应用](../../app/buffer_app/README.md)。当前固件已有有界 v0.9.1 消息解析、surface 模型、文本路径绑定、受限原生组件渲染，以及计时器、闹钟、翻译、菜谱和助手回执模板。基础计时/闹钟 surface 在首页滚动 body 顶部显示；菜谱、翻译和长回执使用助手全屏 surface。首轮沿用工作区现有 LVGL 9.1，不以升级图形库为前置条件。

官方 JSON Schema 和 basic catalog 固定在 [`data/a2ui/spec/v0.9.1`](../../data/a2ui/spec/v0.9.1/PINNED.md)。官方 schema 用于宿主测试，不在设备运行时执行通用 JSON Schema 引擎。设备侧只实现下文列出的消息、组件和字段子集；不能据此宣称符合完整 A2UI v0.9.1 renderer 要求。

## 2. 三层组件体系

| 层次 | 示例 | 谁决定 |
| --- | --- | --- |
| 基础组件 | 文本、图标、按钮、行列布局、列表、分隔线 | 开发者实现并公布支持范围 |
| 预制业务组件 | 计时器卡片、闹钟卡片、翻译结果、Buffer 卡片引用、步骤条目 | 开发者固定交互、状态及主题，模型填写参数 |
| 动态场景 | 菜谱全屏页面、多个翻译结果、旧项目检索结果页 | 模型组合已支持组件，宿主决定页面承载方式 |

“自定义”分成两种：开发者注册的自定义组件，以及模型动态组合的页面。模型不能临时发明设备没有实现的组件，也不能下发 C、JavaScript 或任意执行脚本。

预制组件使用版本化的项目 catalog 扩展。能用基础组件组合的模板先组合；需要持续订阅本地业务状态的计时器等，再实现原生业务组件。不得把私有组件冒充为标准 catalog 已支持的组件。

### 2.1 普通计时器

用户说“倒计时三分钟”：本地工具成功创建计时器后，展示默认计时器卡片，包含名称、剩余时间及与状态匹配的暂停、继续、取消操作。不必为这个任务生成全屏页面。

### 2.2 菜谱中的计时器

用户说“教我做一道菜”：打开全屏步骤页，展示材料、步骤和当前进度；需要计时时嵌入同一个计时器模板。页面上的“开始计时”才触发创建动作，生成菜谱本身不会启动所有计时器。

首页卡片和菜谱组件引用相同 `timer_id`，而不是各自维护一份倒计时。暂停任一视图，其他视图同步更新。退出菜谱、关闭 surface 或切换 Coordinator 都不取消计时器。到时由 Vela 本地服务提醒；在计时卡片执行“结束计时”会取消计时或关闭已触发计时的持久提醒。

### 2.3 Buffer 与其他功能

检索和整理结果可引用 Buffer 卡片；翻译、闹钟、计时等任务默认进入自身业务域。只有用户明确保存时，翻译或菜谱才转成 Buffer 内容。动态页面不会自动污染收藏。

## 3. 协议与兼容边界

锁定官方 `a2ui-project/a2ui` commit `45633b97636daf89bafb43fa7db23e3cd916461d`。服务端、客户端、common types、basic catalog 和 Apache-2.0 许可文件及其 SHA-256 见 [`PINNED.md`](../../data/a2ui/spec/v0.9.1/PINNED.md) 与同目录 `SHA256SUMS`。官方 v0.9.1 协议示例和 schema 定义见[官方规范](https://github.com/a2ui-project/a2ui/blob/45633b97636daf89bafb43fa7db23e3cd916461d/specification/v0_9_1/docs/a2ui_protocol.md)。

设备目前逐条接收 `v0.9.1` 的 `createSurface`、`updateComponents`、`updateDataModel`、`deleteSurface`。拒绝 v0.8 格式、v0.9 version 字段及 v1.0 renderer RPC。标准 basic catalog ID 是 `https://a2ui.org/specification/v0_9_1/catalogs/basic/catalog.json`；助手预制组件使用项目私有 ID `https://vela.local/a2ui/catalogs/assistant/1`。

| 目录 | 可接受组件 | 当前 LVGL 行为与限制 |
| --- | --- | --- |
| 官方 basic catalog 子集 | `Column`、`Row`、`Card`、`Text`、`Button`、`Divider` | 行列为 flex 布局；Card 用设备主题；Text 支持字面字符串或字符串路径绑定；Button 只接受一个 Text 子组件及命名事件；Divider 为水平分隔线。布局对齐/weight、Button variant、Divider axis 等字段尚不应用。 |
| Vela 助手私有扩展 | `VelaTimerCard`、`VelaAlarmCard`、`VelaTranslationResult`、`VelaRecipeStep` | 只在助手 catalog 下接受。状态来自本地 domain；菜谱步骤的计时仅由用户点击创建。它们不是官方 basic catalog 组件。 |

当前 parser 要求每条 envelope 恰有 `version` 与一个消息种类；组件更新通过 bounded candidate、引用/环路/预算检查后才提交。未知 catalog 或组件被拒绝（分别返回 `-ENOTSUP`；版本不符返回 `-EPROTONOSUPPORT`），保留上一版有效 surface。已知组件中未映射的可选属性可能被忽略，不等同于完整 schema 验证。客户端 action 目前进入本地 action handler；传输层可自行编码事件，但设备没有完整 A2UI client-to-server message/session 实现。

宿主测试使用锁定的官方 schema 验证官方 catalog 示例；设备端并未将整个官方 schema 当作运行时校验器。A2UI 描述界面，Foundation Fabric 负责跨设备传送。断线恢复、消息去重与 surface 修订号属于本项目传输适配层，不是 A2UI 标准字段。

## 4. 渲染链路

处理顺序：接收消息 → 有界解析 → 支持字段/catalog 检查 → 更新内存中的 surface 模型 → 生成 UI 变更 → LVGL UI 线程提交。设备端不执行通用 JSON Schema 验证。

| 模块 | 职责 |
| --- | --- |
| 协议适配器 | 流式分帧、版本检查、surface 生命周期、错误回传 |
| Surface store | 组件 ID 索引、父子引用、数据绑定、修订记录 |
| Catalog registry | 组件 schema、原生构造器、允许的动作和纯计算函数 |
| LVGL adapter | 创建/更新/释放对象、事件映射、统一主题 |
| Action router | 身份与参数校验、幂等处理、调用本地服务或 Agent |

网络及 LLM 线程不得直接操作 LVGL。每个更新先校验，再提交；分批到达的组件可暂存，但不能把悬空引用或半成品树直接显示出来。失败保留最后一个有效界面。

对 surface 的更新必须校验来源、任务绑定和修订号；旧任务输出不能覆盖当前页面。关闭 surface 后迟到消息不得重新打开它，除非发起新的明确展示请求。

## 5. 业务状态与动作

计时器、闹钟、提醒事项的真实状态属于本地 domain 服务。渲染器只订阅状态，不通过屏幕上的数值推算业务事实。

动作处理流程：用户点击 → 校验组件允许的 action 和目标对象 → 使用请求 ID 调用工具 → 记录执行结果 → 刷新业务状态与 UI。工具失败时展示失败与重试入口，不能先显示“已设置成功”。

连续点击、消息重发使用同一动作幂等键。计时、暂停、取消等确定性操作可直接调用本地服务，不必每次绕行 LLM。需要理解自然语言的操作才交给 Agent。

以下是本项目拟定的内部绑定信息，不是 A2UI 标准 wire 示例：

```text
surface: recipe-session-123
component: step-3-timer
business_ref: timer/456
presentation: timer-card/1
allowed_actions: pause, resume, cancel
```

## 6. 视觉与资源约束

采用适配小屏的 Material 3 视觉原则：统一颜色、字号层级、圆角、间距及触控反馈；图标从已有 Material Symbols 或 Phosphor 资源中选择。主题由设备控制，模型选择语义层级，不自由指定像素坐标或任意字号。

当前固件边界如下；字符串和消息长度按 UTF-8 字节计：

| 资源 | 当前上限 |
| --- | --- |
| 单条 JSON 消息 | 64 KiB；最多 2048 个 parser tokens |
| 同时驻留 surface | 3 |
| 每个 surface 组件 | 128；每个容器最多 16 个 child；最大树深 12 |
| 每个 surface data path | 32 个；数据值合计最多 4 KiB |
| surface 字符串总预算 | 8 KiB；Text 固定字段 128 字节含 NUL（有效载荷最多 127 字节） |
| 标识与属性字段 | surface ID 64、component ID 32、binding path 96、action 名 48、action context JSON 192 字节，均含 NUL |
| 助手纯文本回执 | 最多 1024 字节 UTF-8（不含 NUL），分成最多 127 字节的 Text 组件显示 |

未知组件、未知 catalog、循环/多父节点、非法引用和超预算消息不会替换当前有效 surface。Image、Icon、Video、AudioPlayer、List、Tabs、Modal、TextField、ChoicePicker、Slider、catalog 函数及动态 child template 当前都不支持；不加载网络图片，也不执行模型提供的脚本。已知组件里未映射的合法可选属性可能退回固定主题默认值。

离线仍能操作已创建的本地计时器和闹钟。重启后从业务记录恢复默认卡片；缓存的动态页面只是可丢弃的展示数据。

## 7. 实现状态与验收

核心实现包括有界 v0.9.1 子集解析、surface 更新回滚、basic 组件子集、Vela 业务组件、本地助手页面和 LVGL owner-thread adapter。普通计时器/闹钟卡片放在首页滚动 body 顶部；关闭 surface 后恢复 Buffer 首页但不停止本地计时/闹钟。菜谱保持全屏；翻译与助手回执也使用全屏 surface。录音按钮保持在固定首页层并高于全屏助手层。计时器或闹钟到期后，固定提示条显示触发状态，并可重新打开对应卡片；执行“结束计时”会清除该计时的到期提示；不承诺声音提醒。主线程报告的真实计时器点击验收已覆盖顶部 inline 卡片；绿色主题修正与 recipe 成功工具后保留页面的最新改动，仍待下一次目标构建和安装复验。

### 宿主验收表

| 检查项 | 命令 / 生产文件 | 当前证据与边界 |
| --- | --- | --- |
| 官方 schema、哈希和样例 | `python3 tests/test_a2ui_official_schema.py`；`data/a2ui/spec/v0.9.1/SHA256SUMS`；`tests/fixtures/a2ui_v0_9_1_official_interactive_button.json` | 既有宿主检查通过记录。校验固定 commit 文件哈希、官方 schema、上游 Interactive Button 样例及 client action schema。样例原始版本标为 `v0.9`；测试先按官方 schema 验证原样，再只改协议/catalog 标识后送入设备 v0.9.1 model。它不是声称上游示例本身发布于 v0.9.1。 |
| 原生 A2UI model / domain | `python3 -m pytest -q tests/test_assistant_a2ui_core.py`；编译 `buffer_assistant.c` + `buffer_a2ui_model.c` | 最近稳定宿主检查通过。覆盖协议子集拒绝与回滚、Text 路径、官方样例组件映射、1024 字节回执、计时器幂等/持久化、菜谱点击创建计时，以及 surface 关闭后计时和到期关闭。该 fixture **不编译 `buffer_a2ui_lvgl.c` 或 `buffer_agent_model.c`**，验证的是 C model/domain，不是像素绘制。 |
| Agent / LLM 工具到 domain | `python3 -m pytest -q tests/test_agent_https.py`；生产源含 `buffer_agent_model.c`、`buffer_agent_assistant.c`、`buffer_assistant.c`、`buffer_a2ui_model.c` | 本地 HTTPS fixture 覆盖真实 C 工具链、助手完成记录、Buffer 不收藏、嵌套 `assistant_response` 与菜谱不自动开计时。主线程先前报告该源集可无 LVGL 宿主编译；本表所列 `assistant_result` 深层畸形响应、Buffer 目标调用助手副作用工具、成功工具后下一轮失败和多个展示工具争抢页面，尚无对应故障注入验收。主线程最新过滤逻辑仍需将变更后的宿主用例跑过。 |
| LVGL adapter / 目标显示 | Vela 统一目标构建、启动与触屏截图 | **待目标复验。** 上述宿主 C fixture 不链接 LVGL。主线程计划把绿色主题修正和 recipe 页面保护一起纳入下一次 Vela build；需在 320×480 确认录音按钮可用、普通计时器顶部可见、recipe 全屏可滚动，以及关闭/结束后恢复和清提示。 |
| 计时/闹钟提示 | `buffer_assistant.c` 宿主 domain 检查；目标设备长时/唤醒试验 | domain 状态和可见到期通知有宿主覆盖，主线程报告真实云端 timer 创建、到期提示和点击打开已验证。目标设备深睡唤醒、断电恢复、长时可靠性未由这些测试证明；不宣称声音提醒。 |

本表记录最近已知证据；本次文档复核为只读，没有重跑宿主测试、目标构建或模拟器。

核心验证命令：

```sh
python3 -m pip install -r tests/requirements-a2ui.txt
python3 tests/test_a2ui_official_schema.py
python3 -m pytest -q tests/test_assistant_a2ui_core.py
```

第一项校验锁定官方 schema、SHA-256、上游 Interactive Button 样例及 client action schema；第二项宿主编译生产 `buffer_assistant.c` 与 `buffer_a2ui_model.c`，验证已支持的组件树模型、资源边界、幂等、持久化、菜谱点击计时和关闭 surface 后计时。它不包含 LVGL adapter。`buffer_a2ui_lvgl.c` 的主题、层级、布局、滚动和触摸行为须由目标构建与设备截图验收；真实到期唤醒、声音与长期资源压力也需单独验证，不把宿主结果写成设备端视觉验收。

## 8. 参考

- [锁定的官方 A2UI commit](https://github.com/a2ui-project/a2ui/tree/45633b97636daf89bafb43fa7db23e3cd916461d/specification/v0_9_1)：schema 来源及上游版本。
- [项目 Agent 设计](03-vela-agent-capabilities.md)：工具执行和持久化边界。

# Agent 能力下放 Vela

日期：2026-10-18。状态：受限 A2UI、Vela 助手、Fabric BLE 应用消息与二维码图片定位均已有运行证据。主线程最新报告 Android 源码 SDK 默认 APK 的 297 项单测、assemble 和 lint 已通过；Photo Picker 已识别实际 Vela locator。当前 AVD 没有可用相机，因此不代表相机扫码或首次 SAS 配对通过。Vela parent=13980 已安装，包含队列公平性和 admission cache；真实闹钟到期提示与卡片、FIRED→CANCELLED domain/model 回归及 Windows 实际关闭提醒点击均已通过。最新 native 完整构建及 production HTTPS fixture 均通过持久化工具检查点和 target 隔离。UI 黑屏根因定位为快照等待 BLE executor/文件系统锁；snapshot 独立 UI status view 与 SDK handle metadata 直读已用于 Windows parent=3888，启动 13 秒出现正常首帧，原卡片、配置和闹钟均保留。主代理报告 SDK metadata 的 ASan/UBSan THREADSAFE 回归通过，最终 ELF 已安装 Windows parent=7656。跨设备异常恢复及目标设备验收仍有未完成项，详见第 8、9、11 节。

关联：[A2UI 与组件体系](01-a2ui-lvgl-renderer.md)、[Coordinator 自动选举](02-coordinator-election.md)。

## Foundation Fabric 开发仓库位置

框架相关开发在独立仓库 **`/home/simon/projects/mocha/foundation.fabric`**（即 `~/projects/mocha/foundation.fabric`）进行，远端为 [6xingyv/foundation.fabric](https://github.com/6xingyv/foundation.fabric.git)。

`/home/simon/vela/contest2026_014_makahu/foundation.fabric/` 是参赛项目内的源码快照，不是本轮框架修改的工作仓库。本文指向项目内 Fabric 文件的相对链接仅供阅读当前快照；实现前应在上述独立仓库核对最新接口与约束。框架变更完成并验证后，再按需同步到参赛项目快照。

## 1. 产品范围

Vela 应能够独立完成语音转写、理解、分类、关联旧内容、生成卡片，以及设备语音助手任务。Buffer 是其中一大块跨设备功能；计时器、闹钟、翻译、菜谱步骤等是 Vela 助手的独立功能，不要求所有交互都生成收藏。

“能力下放”是把 Agent 编排、工具、持久化和服务调用移植到 Vela。默认仍通过 HTTPS 使用配置的 OpenAI-compatible 云端服务，不表示把手机使用的大模型权重装进设备本地运行。

沿用当前项目的模型配置方向：通用模型 `mimo-v2.5`、转写模型 `mimo-v2.5-asr`。实际 endpoint、请求格式、音频限制和模型可用性应在联调时验证，统一放在服务适配层，不硬编码到 UI 或业务逻辑。

## 2. 当前基线与缺口

| 范围 | 当前基线 | 尚未完成或需留意的风险 |
| --- | --- | --- |
| Android | 转写、分类、检索关联和内容生成路径已存在；源码 SDK 默认 APK 的 291 项单测、assemble、lint 通过，记录见 `android-packaged-source-final.log`。Photo Picker 已识别实际 Vela locator。 | 当前 AVD 的 CameraService 没有可用 camera device；不宣称相机扫码或首次 SAS 配对已验收。 |
| Vela | 显式“记录/助手”目标、WAV `.meta` 路由、HTTPS LLM/ASR、四组助手工具、本地计时/闹钟服务、A2UI 子集及 Fabric BLE 消息路径已存在。已验收真实 `device_timer`、`show_recipe` 工具；parent=13980 已安装并包含队列公平性和 admission cache。真实 `device_alarm` 到期、提示关闭及卡片转为“已取消/提醒已关闭”均已验证。 | Buffer target 只公布 `search_records`/`read_record`，assistant target 保留同一只读检索及助手工具；执行器再次校验 target，越权调用返回工具错误。助手工具输出和回执按 capture 原子持久化，malformed/timeout continuation 重试不会重复 timer 副作用；单 capture 的后续展示请求不会替换当前页面。HTTPS 生产 fixture、domain/model 宿主回归及包含当前 model 源码的 native final build 均通过。Windows UI 黑屏根因是 `buffer_fabric_snapshot` 等待 BLE executor 锁、executor 持 FS 锁扫描空 outbox，以及 `fstat` 的 LittleFS 路径反查放大 I/O。snapshot 独立 UI status view 与 SDK handle metadata 直读已用于 parent=3888；23:42:04 启动后 13 秒出现正常首帧，已有卡片、配置和闹钟保留。主代理报告 SDK metadata 的 ASan/UBSan THREADSAFE 回归通过，最终 ELF 已安装 Windows parent=7656。闹钟关闭实际点击已通过。成功工具后模型仍会收到助手工具 schema，dispatch 会拒绝第二个页面/副作用；缩减后续 schema 是可选优化。深睡唤醒、长时运行和断电恢复未验收。 |
| Skill 资源 | `data/agent/` 归档了 prompt、工具及输出结构。 | 按当前项目要求，`data/agent/catalog.json` 保持 disabled；运行时使用 `buffer_agent_model.c` 内置规则。外置 Skill 动态加载不属于本次验收目标。 |
| Fabric / OOBE | `buffer_fabric.c` 与 Fabric BLE host 建立配对后的加密可靠消息会话；Android 使用 `FabricClient.openBleMessages`。Photo Picker 已识别实际 Vela 公钥 locator，并复用 URI 解析及 expected-peer pin 路径。`ph39`、`pf17` 与新 capture `pfio` 均已手机真实转写、分类并回传完成副本，Vela 对应 WAV/meta 均已清理。`pf17` 与 `pfio` 首次 malformed 分类均被拒绝后自动重试成功。`pfio` 于 23:42:54.367 发布；约 20 秒时手机 DB blob 已非空、转写 17 字符，首次分类失败后进入 pending（attempts=1）；约 71 秒时分类 complete（attempts=1）。该时长包含传输、ASR 和 LLM 自动重试，不是纯 BLE 耗时。23:44:25 后 Vela queue 已无 pfio WAV/meta，仅保留原有 320 ms 失败录音。AVD 无 camera device；实际相机与首次 BLE/SAS 配对未验收。反向编辑、并发冲突和更多异常恢复仍需端到端证据；见 `buffer_agent_input.c`、`buffer_replica.c`、Android `VelaPendingCaptureReceiver.kt`。 |
| 动态界面 | 有界 A2UI v0.9.1 parser/model、六个 basic 组件子集、四种助手模板及 LVGL owner-thread adapter。官方 schema、哈希和上游样例已固定。普通计时卡片可见，菜谱全屏页的内嵌计时器可启动和结束；真实闹钟到期提示持续可见，默认卡片显示已触发，Windows 实际关闭后顶部提示消失、卡片显示已取消。宿主回归验证 FIRED 闹钟关闭后 model 状态变为 CANCELLED；parent=3888 恢复正常首帧且原卡片保留。主代理报告 SDK metadata 的 ASan/UBSan THREADSAFE 回归通过，最终 ELF 已安装 Windows parent=7656。 | 这不是完整 A2UI renderer；client-to-server 标准 session、其他组件和完整属性支持未实现。 |

来源：[项目 README](../../README.md)、[Vela README](../../app/buffer_app/README.md)、[归档 Skill 说明](../../data/agent/README.md)。现有录音落盘后再确认的机制继续使用，不重复包装成新能力。

不能因为引入了框架源码，就认为所有业务能力已经跨端运行；也不能因为 prompt 已归档，就认为设备会自动加载它。

## 3. 两条业务路径

### 3.1 Buffer：采集、理解与整理

语音或文字输入 → 本地持久化 → 必要时转写 → 同一次整理任务中分类、检索旧记录并建立关联 → 生成结构化卡片及展示建议 → 事务提交 → 跨设备同步。

“记下此刻”、录音转写和手工文本走相同的后续整理逻辑。转写中的语音属于待处理队列，不直接当成收藏卡片。处理失败显示真实阶段及重试入口，不伪造成功内容。

当前录音入口不再要求用户选择“记录”或“助手”。新录音写 `target=auto`，由 Agent 根据完整语义决定生成 Buffer 卡片还是调用 Vela 助手；coordinator 再根据认证成员的资格与当前任期选择执行设备。具备资格的手机优先处理 Vela 输入；手机失联或不具备资格时，由已就绪的 Vela 接管。原始输入分片现在携带 target：仅 `auto` 会开放手机侧语义助手分流；显式 `buffer` 和旧发送方未提供 target（接收端缺省按 `buffer`）保持 Buffer-only；旧 `target=assistant` 留在 Vela 本机助手路径。接收端将 target 持久化并拒绝同一 capture 以不同 target 重放。自动助手交接的协议和验证边界见 [自动意图与设备助手交接](07-auto-intent.md)。

整理阶段允许调用 `search_records` 和 `read_record`，先取得可见旧内容再生成关联。关联必须引用实际检索/读取过且有权限的记录 ID；不得让模型杜撰 ID。只读检索工具本身不修改用户数据。

问题回答和七日总结沿用独立 Skill，周总结由模型根据真实记录生成。网络或模型不可用时显示等待/失败，不以规则拼接文本冒充已生成的周总结。

### 3.2 Vela 助手：理解意图与执行设备能力

语音 → 转写 → 意图及必要参数确认 → 调用已注册工具 → 本地服务提交 → 返回执行凭据 → 展示默认卡片或动态页面。

| 能力 | 执行归属 | 展示 | 默认保存为 Buffer |
| --- | --- | --- | --- |
| 计时器 | Vela 本地计时服务 | 默认卡片，可嵌入菜谱 | 否 |
| 闹钟 | Vela 本地调度服务 | 闹钟卡片及编辑入口 | 否 |
| 提醒事项 | 复用既有提醒语义并补齐设备执行 | 提醒卡片 | 按用户保存意图 |
| 翻译 | 模型生成，结果在 Vela 呈现 | 原文、译文、语言 | 否 |
| 菜谱/步骤指导 | 模型生成步骤，本地服务执行计时 | 全屏步骤页，复用计时器模板 | 否 |
| 记录整理/检索 | Buffer Agent 与副本数据 | Buffer 卡片/列表 | 是或仅展示检索结果 |

“明天早上提醒我”等时间不明确请求需要补齐时区、日期或必要参数。明确的“倒计时三分钟”可直接执行，不增加无意义确认。生成翻译不会触发提醒，生成菜谱也不会自动开始每个步骤的计时。

## 4. 代码分层

建议仍按 data、domain、ui 划分职责，具体目录名在实现时结合 C 工程确定，不复制 Android 框架依赖。

| 层 | Vela 职责 |
| --- | --- |
| data | HTTPS/模型适配、音频读取、任务及事件存储、Fabric 通信、Skill 资源访问 |
| domain | Agent 状态机、工具注册与校验、意图分派、卡片规则、任务租约、计时/闹钟服务 |
| ui | 固定录音/设置界面、A2UI renderer、业务状态订阅与操作反馈 |

可共享内容包括输入输出 schema、提示词、工具定义、事件协议、幂等规则和契约测试样例。Android Kotlin worker、SQLite 封装、Compose 界面不直接移植成 Vela 依赖，分别实现平台适配器。

当前 C 侧入口在 `buffer_agent_assistant.c/h`，负责把 `device_timer`、`device_alarm`、`show_translation`、`show_recipe` 工具映射为 `buffer_assistant` domain 请求；domain 不依赖 LVGL。A2UI model 与 LVGL adapter 分在 `buffer_a2ui_model.c`、`buffer_a2ui_lvgl.c`，UI 刷新由 LVGL owner thread 执行。

设备状态不得散落在模型提示词和 UI 回调里。domain 是业务事实来源，UI 关闭不能终止计时或清空任务。

## 5. Skill 与工具执行

[现有 Skill 目录](../../data/agent/README.md)归档转写、记录整理、问题回答和周总结资源。当前运行时使用内置规则及已注册工具；`catalog.json` 按产品要求保持 disabled，动态加载不属于本次验收范围。

模型一次调用并不等于完成一次任务。执行器需要有界工具循环：请求模型 → 校验工具名和参数 → 执行获准工具 → 回传真实结果 → 继续生成 → 校验最终结构。达到轮数、时长、内存或调用预算时终止并返回可恢复状态。

工具建议分为：

- 只读工具：检索/读取记录、查询设备已有计时器或闹钟。
- 有副作用工具：创建/暂停/取消计时器，创建/修改/删除闹钟，保存用户明确请求的内容。
- 展示操作：请求宿主展示一个已校验的 surface，不等同于执行业务工具。

新工具名称和参数需要另行定义 schema，不把文档中的名称直接当成已实现接口。模型只能使用当前设备公布的工具；工具调用必须校验权限、参数范围、目标设备、对象版本和 operation_id。

保留实际执行的工具名、结果状态及关联记录引用，供“AI 如何整理”的详情展示。只记录可核验的操作摘要，不要求保存或展示模型内部推理。

## 6. 手机协作与独立运行

[自动选举设计](02-coordinator-election.md)决定计算任务由谁承担。手机可用时优先，Vela 仍保持完整能力；手机不在场但 Vela 有网络及服务配置时，由 Vela 完成转写和生成。

Vela 不能依赖手机即时转发 API key 才能独立运行。配对后通过已认证安全通道按用户配置部署设备需要的服务凭据，或使用可独立访问的凭据代理。密钥不进入发现公告、同步卡片、prompt、普通日志或 A2UI 数据。

全部授权卡片采用增量副本同步，包含修改、关联和删除。Vela 根据本地副本自行选择展示；仅同步手机筛选出的少量展示卡片不能满足此目标。音频按需传输，并保留原采集 ID 以支持重试和去重。

手机完全离线与外网完全离线须分别展示：前者可以继续云端处理，后者将需要模型的任务排队，本地计时、闹钟及已保存内容仍可用。

## 7. 持久化、实时性与资源

每次任务先保存输入和逻辑 ID，再进行远端调用；逐阶段记录检查点。调用成功但写盘前掉电可能导致重试，外部服务未提供幂等保证时不能承诺无重复计费。

结果提交必须检查输入版本、删除记录和用户编辑，并与卡片更新处于同一可靠提交边界。存储空间不足时明确提示，不能删除未确认的采集音频来伪造成功。

网络和模型处理在后台执行，使用有界队列，首版默认串行运行一个重型模型任务。录音、UI、计时提醒优先保持响应。音频分块读取、TLS 缓冲和 JSON 输出都要限额，禁止一次性读入无限大小音频或对话历史。

HTTPS 验证证书及主机名。设置连接/读取超时、有限重试及退避；鉴权错误转为配置问题，不能无限重试。异常响应不得直接进入渲染器。

运行中的计时器使用单调时钟计算剩余时间；重启恢复另存可校验的截止时间及状态。闹钟涉及墙上时间和时区，必须定义校时、跨日、漏响及重复触发策略。是否支持深睡唤醒、断电恢复和重启补响需要目标设备验证，不能从模拟器推断。

## 8. 分阶段交付

1. 契约：设备模型服务及工具契约、持久化执行状态已落地；官方 A2UI v0.9.1 schema、basic catalog、LICENSE 和上游样例已按 commit 固定。跨端契约和完整 A2UI 协议仍未完成。
2. 独立 Buffer：设备录音目标及本地 Agent 路径已实现；Vela 独立整理和手机优先委派均有实际云端证据，仍需覆盖更多故障恢复场景。
3. 跨设备：附近发现、配对、卡片副本和处理完成确认已实现。`ph39`（77,930 B）已在手机保存 blob、转写 29 字符、分类完成（attempts=0），完成副本回传后 Vela WAV/meta 已清理。`pf17`（44,694 B）手机 DB blob 非空、转写 17 字符且分类 complete（attempts=1）；首次 malformed 输出被拒绝并自动重试成功，处理完成副本已回传且 Vela queue 已清理。相同音频的新 capture `pfio` 于 23:42:54.367 发布，约 20 秒完成手机导入与 17 字符转写；首次分类 malformed 后 pending（attempts=1），约 71 秒分类 complete（attempts=1）。71 秒包含传输、ASR 与 LLM 自动重试；23:44:25 后 Vela queue 已清除 pfio WAV/meta。反向编辑和并发冲突仍需真实端到端验证。
4. 助手：四组模型工具、本地计时/闹钟持久化与幂等、翻译/菜谱展示已实现；真实云端 `device_timer` 和 `show_recipe` 已成功，菜谱步骤计时可启动和结束。真实 `device_alarm` 到期提示及默认卡片通过；domain/model 宿主测试和 Windows 实际点击均验证 FIRED 闹钟可关闭。不据此声称声音或深睡唤醒通过。
5. 动态场景：有界 A2UI v0.9.1 子集、普通计时器首页卡片、菜谱全屏复用 timer template、翻译/回执页面已实现。锁定 schema、官方样例、受支持树 C model 和当前 timer/recipe 关键路径均有宿主或运行证据；完整协议及未支持组件仍不在范围内。

每阶段保留可运行的固定界面回退。先用 Vela 与 Android 模拟器验收业务闭环，再测目标设备内存、音频、后台运行和提醒唤醒；两类证据分别记录。

## 9. 验收清单

以下是产品验收目标，不代表每项已通过。已知实现与证据按第 2、8、11 节区分。

- 手机关闭时，Vela 有网络可独立完成语音转写、分类、关联旧卡片与保存。
- 待转写音频不进入收藏；文字与语音使用相同整理规则。
- 只引用实际查到的旧记录，详情能够查看真实工具执行摘要。
- 手机恢复后同步任务和卡片，无重复收藏；用户编辑/删除不被旧生成结果覆盖。
- 普通翻译和计时不会创建 Buffer 卡片，用户明确保存才进入 Buffer。
- 页面关闭、模型调用失败或 Coordinator 变更不影响本地已创建计时器。
- 重复工具调用不重复设置闹钟；目标设备未确认时不显示设置成功。
- 外网中断、鉴权失败、超限输出、存储满、运行中重启均有可恢复状态。
- 密钥不出现在日志、发现包和 UI 数据中，未知工具不会执行。

### 9.1 当前未完成项与代码风险

| 待办 / 风险 | 具体文件 | 影响与验收条件 |
| --- | --- | --- |
| Buffer 与助手工具隔离 | `app/buffer_app/buffer_agent_model.c` 的 `classify()` | 已实现并通过 HTTPS 生产 fixture 回归：Buffer 仅收到 `search_records`/`read_record`，assistant 保留这两项只读检索及助手工具；分派时再次检查 target，Buffer 越权工具收到 `tool_not_allowed_for_target` 并继续分类，不执行设备副作用或污染助手页面。包含当前源码的 native final build 与 production HTTPS regression 均已通过。 |
| 副作用后的续答失败 | `buffer_agent_model.c`、`buffer_agent.c`、`buffer_agent_assistant.c` | 工具成功后立即原子保存有界 capture 检查点，包含原调用、真实工具输出和可核验回执。malformed JSON 与真实读超时后的后续响应回归均确认重试恢复原回执、timer 只创建一次，最终提交后清理检查点；生产 HTTPS fixture 通过。当前 model 源码纳入的 native final build 通过；设备故障注入未验收。 |
| 单 capture 展示竞争 | `buffer_agent_model.c`、`buffer_a2ui_model.c` | 同批第一个成功助手工具保留展示；后续展示/副作用工具返回 `surface_already_presented`，不会覆盖页面。HTTPS 生产 fixture 验证 timer 卡片保留、translation 未执行。成功工具后模型仍可看到其余助手工具 schema，dispatch 会安全拒绝后续展示；缩减 schema 是模型调用优化，不影响当前页面和副作用边界。 |
| 双编码结果边界 | `buffer_agent_model.c` 的 `assistant_result()` | 已限定最多解开两层 JSON，并拒绝仍以 JSON 对象开头的超深字符串；无工具的普通问答也不能绕过 `show_answer` 作为成功结果。更深嵌套、字段类型/未知键、CR/空白前缀及畸形响应仍需定向负例覆盖。 |
| 助手界面与启动性能 | `app/buffer_app/buffer_a2ui_lvgl.c`、`buffer_ui.c`、`buffer_ui_theme.h`、`buffer_assistant.c` | Windows 已验证普通计时卡片、菜谱全屏、内嵌计时器和 FIRED 闹钟关闭。parent=3888 的正常首帧（13 秒）证明快照独立 status view 与 SDK handle metadata 优化已恢复 UI 启动；主代理报告 SDK metadata 的 ASan/UBSan THREADSAFE 回归通过，最终 ELF 已安装 parent=7656。该黑屏问题不在助手 renderer。声音、深睡唤醒和断电恢复不在已验收范围内。 |
| QR/OOBE 真实链路 | Android `AddVelaPanel.kt`、`VelaFabricQrImageDecoder.java`、`VelaFabricEnrollment.kt` | Photo Picker 已识别实际 Vela locator 并进入 nearby discovery；CameraService 无可用 camera device，所以不宣称相机扫码通过。真实首次 BLE/SAS 配对和完整重启/配网恢复仍待验收；附近 BLE 列表仍可独立发现。 |
| 跨设备原始输入及副本冲突 | `buffer_agent_input.c`、`buffer_replica.c`、Android `VelaPendingCaptureReceiver.kt`、`VelaCardReplicaProtocol.kt` | `ph39`、`pf17` 与 `pfio` 均证明 WAV 导入、手机真实转写分类、完成副本回传和设备源文件清理。`pf17` 与 `pfio` 首次 malformed 分类被拒绝后自动重试成功，手机侧任务 complete、attempts=1；`pfio` 约 71 秒完成含传输、ASR 和 LLM 重试的分类流程。Android/设备并发编辑、冲突策略及更多断线恢复组合仍需真实端到端覆盖。 |
| 闹钟到期及提醒关闭 | `app/buffer_app/buffer_assistant.c`、`buffer_a2ui_model.c`、`buffer_a2ui_lvgl.c` | 已通过真实云端到期、domain/model FIRED→CANCELLED 回归及 Windows 实际触摸验收：`alarm-dismiss-before.png` 显示关闭按钮；`alarm-dismiss-after.png` 显示顶部到期提示消失、卡片“已取消/提醒已关闭”。parent=3888 首帧恢复后原闹钟状态保留。声音、深睡唤醒、断电后的过期策略及长时可靠性均未验收。 |

## 10. 参考与边界

- [openvela AI Agent 官方仓库](https://github.com/open-vela/packages_ai_agent)：后续实现可评估其服务适配与编排能力，不能将存在官方 Agent 等同于本项目已完成集成。
- [现有运行时 Skill 资源](../../data/agent/README.md)：本项目的 prompt、function call 与输出契约来源。
- [Fabric 会话路由设计](../../foundation.fabric/docs/06-session-routing.md)：跨设备传输与会话职责边界。


## 11. Vela 首次开机引导（OOBE）

### 11.1 目标与现状

目标仍是用 Foundation Fabric 做设备发现与配对，并在配对后通过应用协议完成 Wi-Fi、LLM/ASR 初始化。Vela 欢迎页可显示版本化的 Fabric 公钥定位码；Android 保留严格 URI 解析、expected-peer pin 和附近 BLE 备用发现。AVD 实测没有可用 camera device，因此相机扫码不可用；Photo Picker 已从实际图片识别 Vela locator 并进入 nearby discovery。此结果不代表相机可用，也不代表首次 BLE/SAS 配对通过。二维码只定位身份，不授权配对。

### 11.2 用户流程

1. **手机准备服务。** 手机 App 配置 OpenAI-compatible 地址、通用模型、ASR 模型和对应凭据，并展示配置验证状态。支持进入添加设备后补齐配置，不强制用户重新填写已有配置。
2. **Vela 首次开机。** 无有效设备绑定时进入本地欢迎页并开放受控的附近设备发现/配对，同时显示由设备当前 Fabric 身份生成的公开定位码。首次引导是固件内置界面，不依赖 LLM 或 A2UI 在线生成。
3. **选择设备。** 手机可扫描 Vela 定位码，解析出 Fabric 公钥后启动附近 BLE 发现；也可直接从附近设备列表选择。扫码时只显示 Fabric BLE 设备，SDK 将扫描所得公钥作为预期 peer 身份约束实际连接。
4. **确认并建立安全连接。** 通过 Fabric 配对身份、配对码/核对码及现行配对协议完成认证；按协议需要在设备侧确认。之后才传输 Wi-Fi 密码或模型密钥。普通 BLE 连接建立不等于业务认证完成。
5. **手机辅助配网。** 用户选择网络并提供必要凭据；若设备已有可用 Wi-Fi 则跳过重复配置。产品不能假设手机能直接读取已保存的 Wi-Fi 密码。Vela 联网后通过已认证的连接回报实际结果。
6. **初始化 Agent。** 手机展示将同步到该设备的服务配置，默认复用当前配置，允许单独修改。下发服务地址、模型标识、凭据、时区/语言及受支持的能力配置；Vela 校验、持久化并返回配置版本确认。
7. **设备自行验证。** 由 Vela 检查网络、时间、TLS、服务鉴权和所需模型调用能力。配置已保存、网络已连通、LLM 可用、ASR 可用分别反馈；手机端测试通过不能替代设备端验证。涉及实际模型请求的验证应控制成本，音频验证使用明确的测试样本，不后台录制环境声音。
8. **完成并进入首页。** 手机显示设备能力和配置结果；Vela 隐藏配对码，进入录音与助手首页，开始同步卡片。可以引导用户说一句话验证完整流程，但不把用户必须录音作为完成设置的条件。

配网、配对、模型配置属于同一个“添加 Vela”流程，避免用户分别寻找三个设置入口。界面逐项显示“已连接设备 / 已联网 / 服务已就绪”，失败时定位到具体步骤。

### 11.3 初始化状态与中断恢复

首次引导状态及完成条件如下；界面状态与模型服务的 `ready` 回执分开，后者不能替代设备侧的完成确认：

| 状态 | Vela 展示 | 手机可执行操作 |
| --- | --- | --- |
| 未绑定 | 欢迎页、配对码/核对码 | 搜索附近 BLE 设备并选择 |
| 正在认证 | 等待身份确认 | 确认、取消或重试 |
| 已绑定、待联网 | 继续在手机配置网络 | 下发/修正 Wi-Fi 配置 |
| 已联网、待配置服务 | 等待服务初始化 | 复用或修改模型配置 |
| 已保存配置、待验证 | 显示验证进度或失败原因 | 重试验证或修正配置 |
| 服务已验证、尚未确认完成 | 准备完成页，显示“开始使用” | 等待设备侧确认，不标记设置完成 |
| 已持久化完成 | 助手首页 | 管理设备与同步设置 |
| 已跳过服务设置 | 仍停留在引导中，提示继续配置 | 补齐服务配置，不能以跳过放行 |

2026-10-18 修正：首次引导不再提供“稍后配置”进入首页。必须完成配对、联网及模型服务验证，并由设备侧明确点击“开始使用”，成功保存完成记录后才开放录音、卡片和助手入口。旧版 deferred 记录及 `agent.skip` 都不能冒充完成。已完成设备的普通断网不使其重新进入首次引导；原有本地计时和闹钟继续执行。界面与运行验证见 [OOBE 验收](06-oobe-acceptance.md)。

每个步骤使用稳定 request_id 和配置修订号，设备确认持久化后才推进状态。手机退出、蓝牙断开或设备重启后从已完成步骤恢复；重复下发不能重新绑定或重复创建初始化任务。BLE 写入确认不代表 Wi-Fi 或服务配置已生效。

绑定状态、网络状态、模型服务状态分别保存。绑定已成功但模型验证失败时，保留绑定，允许继续配置；临时离线不重新展示可被新手机认领的配对码。取消流程时清除未提交的临时密钥；已提交的设备绑定需要通过明确的解除绑定操作撤销。

### 11.4 QR 定位内容与配置通道

当前 QR locator 的唯一格式为 `vela-fabric://pair/v1/<64 个小写十六进制字符>`；末段是设备 Fabric Ed25519 公钥。它不含私钥/seed、配对授权、短期令牌、Wi-Fi 密码或模型 API key。Android 解析器只接受该规范形式，并由公钥计算预期 Fabric device ID。locator 本身可公开或被重复扫描；它不是 bearer credential，也不使设备接受未经确认的连接。

扫码只把该公钥传给现有 Fabric `openBleMessages` 的 expected-peer 参数。手机仍须通过 BLE 找到设备，并在双方显示相同六位 SAS 时明确确认；公钥不匹配时 Fabric 身份校验失败，不能继续配置。用户未扫码时仍可使用附近 BLE 列表。QR 与 BLE 广播匹配只是定位步骤，不能替代认证；手机仍应区分系统蓝牙配对与 App 业务绑定。

Android 相机入口使用 JourneyApps ZXing embedded，但只在 `CameraManager.cameraIdList` 非空时启动；否则显示 Material 3 inline 提示、Photo Picker 入口和附近 BLE 入口。Photo Picker 只获得用户选定 URI，图片在后台复制到 app 私有临时文件，限制压缩文件 8 MiB、源图 80 MP/单边 20000 像素，采样后解码不超过 2048 边长和 2 MP；应用自有的 bitmap、RGB 像素及 ZXing 灰度缓冲合计限制在 16 MiB 预算内，不包含平台图片 codec 内部暂存。识别后调用同一个 `VelaFabricQrLocator.parse` 并走 expected-peer pin。最终 Android 源码 SDK 默认检查 291 项单测、assemble、lint 均通过；Photo Picker 已实际识别 Vela locator。AVD 没有可用 camera device，因此不计作相机扫码或首次配对验收。

现有 enrollment 协议会严格检查字段，不能直接在旧请求中附加模型密钥。后续扩展版本化的应用配置协议，复用 Fabric 配对身份及可信连接：先配置 Wi-Fi，再同步 LLM/ASR 配置。应用协议负责请求 ID、配置版本、应用结果和重试恢复；无需再抽象一套 Fabric provisioning 协议。凭据传输需要已认证且加密的通道，配对成功本身不代表任意后续连接都可安全发送密钥。具体分工见 11.7。

密钥仅进入设备专用凭据存储，明确软件保护与硬件安全存储的实际边界。配置回执返回版本、验证状态及脱敏错误，不回传完整密钥。发现广播、二维码、任务日志、卡片副本和 A2UI 数据均不携带模型凭据。

手机后续修改服务设置时，提供明确的“同步到此 Vela”操作或用户已启用的同步策略。设备收到新配置先暂存并验证，再切换；验证失败保留旧的可用配置。用户主动撤销凭据时必须立即停用，不因回滚而重新启用已撤销凭据。

### 11.5 已绑定设备与所有权

已绑定设备正常启动时恢复自身配置，自动连接已知网络；不重新开启允许陌生手机认领的首次设置入口。需要重新配网时由现有已认证手机发起，或由用户在设备上显式打开恢复入口。

更换手机、解除绑定和恢复出厂分别定义。新手机不能仅凭在附近发现已绑定设备就覆盖所有者。恢复出厂应清理设备绑定、网络与模型凭据及相关本地个人数据；普通网络重试不能触发该操作。

是否完成 OOBE 与是否担任 Coordinator 无关。初始化成功的 Vela 才能按实际就绪能力参与任务选举，设备无需等待手机授予永久主从角色。

### 11.6 OOBE 验收

- AVD 实测 CameraService 没有可用 camera device，相机扫码不可用；Android 291 项单测、assemble、lint 已通过，Photo Picker 实际识别 Vela QR 成功。真实首次 BLE/SAS 配对和身份错误拒绝仍需验收。
- 手机已有服务配置时可以复用，完成后关闭手机，Vela 仍能独立访问模型服务。
- 多台设备同时广播时，扫码路径以 expected-peer 公钥 pin 约束目标，附近设备列表仍由用户选择；错误设备身份拒绝及双方 SAS 确认仍需实机验证。
- Wi-Fi 密码错误、服务鉴权失败、ASR 不可用分别定位到正确步骤，重试不丢失已完成配置。
- 每个阶段断开蓝牙、退出 App 或重启设备均可恢复；重复消息不会重复绑定。
- 配对后隐藏配对码，临时离线不重新开放认领；已绑定设备不能被另一手机静默接管。
- 跳过模型设置时可进入受限首页，清楚展示 AI 未就绪；不声明尚不可用的 Agent 能力。
- 验证日志及配置回执不泄漏密钥；旧协议收到新配置消息时明确拒绝或协商降级。
- 状态机与故障恢复仍需按目标配置验收；真实蓝牙认证及配网须另行做物理设备验收。当前 AVD 无可用相机；Photo Picker locator 识别已通过，但不包含相机路径、首次 SAS 配对或完整 OOBE 端到端验收。


### 11.7 Fabric 配对之后直接使用应用协议

**职责只有两层：Fabric 负责发现和配对，应用负责配对后的初始化。** 不新增 Fabric OOBE/provisioning 服务，不要求先设计通用初始化框架，也不为模型配置另起一套身份体系。

目标用户流程（扫码是可选定位入口；附近 BLE 列表仍可单独使用）：

```text
Vela 首次启动，展示公开 Fabric QR locator 并开放附近 BLE 发现
  → 手机扫码取得预期公钥，或直接使用附近设备列表
  → 手机通过 Fabric expected-peer pin 连接并核对双方 SAS
  → 应用协议下发 Wi-Fi 配置，设备回报联网结果
  → 应用协议下发 LLM/ASR 配置，设备保存并验证
  → 初始化完成，隐藏配对码，进入首页
```

| Fabric 负责 | 应用协议负责 |
| --- | --- |
| 设备发现、身份确认、配对及信任关系 | Wi-Fi、模型服务、时区和语言等配置 |
| 提供可用的可信通信承载 | 配置校验、保存、执行和结果反馈 |
| 连接建立与连接状态 | 初始化进度、失败重试和断点恢复 |

手机复用已有模型配置，Vela 收到后自行验证。模型配置失败只重试应用配置步骤，不重新进行 Fabric 配对。后续修改 Wi-Fi 或模型设置也使用同一应用协议。

应用协议采用少量明确操作：设置网络、设置 Agent 配置、查询初始化状态。每次请求带请求 ID，配置带修订号，设备返回实际处理结果。设备持久化已完成阶段，断线重连后继续；不把 GATT 写入确认当作配置已生效。

**当前实现：** Android `VelaFabricEnrollment.kt` 通过 `FabricClient.openBleMessages` 建立应用消息会话；Vela `buffer_fabric.c` 使用 Fabric portable BLE host，经 GATT 分片收发并由会话策略绑定已配对 peer、channel 和 port。Fabric BLE session 使用签名身份握手与会话密钥 AEAD；OOBE 的 Wi-Fi/Agent 配置仍是 `buffer_oobe.c`、`buffer_agent.c` 定义的应用消息，不把凭据放入 QR 或发现公告。此安全描述只适用于该 Fabric BLE 会话，不适用于 README 中的旧 LAN/TCP 同步路径。

当前 OOBE 应用消息实现 `oobe.status`、`agent.skip`、`network.configure` 和 `agent.configure`，包含请求 ID、修订号、字段白名单、设备侧配置应用与状态回执。Host/Android 集成测试和构建不能替代相机、真实 BLE/SAS、Wi-Fi 故障恢复及设备重启恢复验收。通用 BLE transport 能力与本应用适配的框架验收状态应继续与 [Fabric 扩展设计](04-foundation-fabric.md)分开跟踪。

[Fabric 发现设计](../../foundation.fabric/docs/04-discovery-link-identity.md)描述发现与配对职责；本项目当前另有面向 `ReliableMessages` 的应用 BLE host/SDK 通路。音频和批量卡片同步虽可走 Fabric 消息分片，真实 BLE 长消息、断线重试、消息优先级和卡片冲突仍须按设备单独验收；无真实蓝牙的宿主或模拟器结果不能代替射频链路验收。

验收重点：一次配对即可完成配网和模型初始化；中断后无需重新配对；配对身份贯穿 BLE 与联网后的连接；陌生设备无法下发配置；手机关闭后 Vela 仍能使用保存的服务配置独立工作。


### 11.8 框架扩展依赖

应用 OOBE 当前通过 `ReliableMessages` 风格的 Fabric BLE 会话发送网络和 Agent 配置。通用框架扩展、应用 host adapter 与物理设备 OOBE 是不同验收项；详见 [Foundation Fabric 扩展设计](04-foundation-fabric.md)。

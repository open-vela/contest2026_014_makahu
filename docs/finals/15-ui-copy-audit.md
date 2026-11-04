# UI 文案独立审核

日期：2026-10-19。仅一轮集中修改；保留工作区已有改动。

## 范围与验收边界

已先用 `view_image` 查看 `/mnt/d/buffer-vela-m3e-fresh-20261019/` 下的 `home-cleanup-final.png`、`home-cleanup-menu.png`、`home-cleanup-todo.png`。截图显示主页、More 菜单及待办空态已精简；没有重做设备端布局。

审核指定 4 个 C 文件及 Android `buffer/ui/` 目录。仅替换 UI 字符串、移除纯说明 Text／纯文案 item，未调整布局参数、交互回调、协议、业务策略或模型 prompt。未过滤已保存卡片、未删用户数据、未改变成功／失败判断。首次扫码、数字核对、Wi-Fi 设置、删除影响范围、取消录音、分类确认及通知暂缓说明保留。

**本轮修改运行未验收。** 三张截图属于修改前的父代理验收素材，不能证明本轮结果。未构建、未安装、未启动模拟器、未运行项目测试、未修改 tests；构建和安装交父代理。仅核对本轮编辑前快照与当前源码差异、UTF-8 和新增行空白；C 非文案代码一致性检查通过（去除错误码显示对应的格式参数除外）。Android 编译、换行／截断、无障碍朗读和所有运行分支仍待父代理验收。

## 代表性修改

| 位置 | 修改前 | 修改后 |
| --- | --- | --- |
| 设备同步 | 正在同步手机卡片副本 | 正在同步卡片 |
| 同步错误 | 手机尚未建立 Fabric 会话，暂不能同步 | 手机未连接，请连接后重试 |
| A2UI 错误 | 操作失败，原任务仍保留＋数字错误码 | 操作失败，任务已保留，请重试（不展示数字码） |
| 待办进度 | 正在提交完成操作… | 正在标记完成… |
| 录音按钮 | 按住，说点什么／松开，收好这段声音 | 按住录音／松开保存 |
| 保存按钮 | 收好这段想法 | 保存 |
| 归档按钮 | 收起 | 归档，与实际 archive 操作一致 |
| 配网按钮 | 使用 S1 已保存的 Wi-Fi 并添加 | 使用已保存的 Wi-Fi 添加 |
| 图片错误 | 图片尺寸超过解码预算，请选取较小图片。 | 图片尺寸过大，请选择较小图片。 |
| 常驻说明 | 不用整理，也不用想好分类；不赶进度，按自己的节奏来等 | 删除纯宣传／教学段落 |
| Android 页面标题 | 页面内宣传大标题，与导航栏重复表达页面用途 | 收藏／回顾／设备／设置页移除对应纯文案标题，沿用导航栏标题 |
| 服务检查 | 已收到响应，但结果格式不符合约定 | 响应格式不符，请检查模型设置 |

另缩短卡片处理说明、空态和周总结操作；统一“深挖”“AI 服务”等展示名称。保留服务测试结果中的“语音识别尚未验证”，避免把文本服务连通写成语音识别已通过。

## 剩余问题

### 硬编码及外部状态文案

- `buffer_ui.c` 非 Fabric 分支仍有“已请求同步”；直接删状态调用可能留下上一次错误提示，需结合状态生命周期处理。本轮未动此逻辑。
- `BufferCardDetailContent.kt` 仍常驻显示 AI 处理状态面板，部分标题与说明重复；详细工具执行流水只在用户展开“处理记录”后出现。进一步收起面板属于展示交互调整，本轮只精简文字。
- `BufferOverlays.kt` 的连接与诊断页仍保留 Fabric、桥接端口／地址、本地事件计数；局域网设备条目保留地址／设备 ID。它们用于主动排障或区分设备，未改成无信息的“正常”。
- `AddVelaPanel.kt` 的 `view.message` 来自目录外状态模型，主屏 `status` 也由外部逻辑传入；本轮未追改生产这些字符串的业务层。仍可能出现技术状态或无信息成功提醒，需另行审核其来源。
- 无底层原因可用的通用错误仍只提示失败及重试，不臆造网络、权限或密钥原因。若需更精确原因，须由业务层提供结构化错误。
- 首次引导服务页的说明与连接状态仍可能重复；引导完成页保留就绪标题及开始动作说明，未改页面状态策略。

### 生成内容

- `home-cleanup-final.png` 的“简短语气词记录”及“这是一次极简的口述记录，仅包含语气词‘嗯。’，没有明确主题、任务或问题。”属于 **LLM 语义理解／丢弃策略缺陷**：低信息输入被包装成一张解释“没有内容”的卡片。它不是本轮可修的硬编码文案。
- 原卡片及保存内容完整保留；未用标题／关键词隐藏卡片，未改模型、prompt、分类／丢弃策略。需父代理单独处理生成决策并覆盖低信息输入的验证；当前缺陷仍存在。
- 模型生成的卡片标题、摘要、A2UI 内容和周总结仍可能啰嗦或重复；本轮未对保存内容作截断、替换或清洗。

## 本轮全部修改文件

父代理后续集成验证：该批文案已构建设备固件并安装到 Windows Vela；Android 文案与分步配对页一并构建、安装。Android 319 项单测通过，lint 与 assembleDebug 通过；设备端 3 项相关 host 测试和完整固件构建通过。实际查看了首页、已有设备、设备发现、其他方式菜单及取消返回。此结果不代表每一条错误分支均已逐项运行；配对页后续结构调整及新回归测试见 `17-phone-enrollment-ui.md`。

以下路径相对项目根目录（只列本轮，未列既有改动）：

- `app/buffer_app/buffer_ui.c`
- `app/buffer_app/buffer_todo_ui.c`
- `app/buffer_app/buffer_a2ui_lvgl.c`
- `app/buffer_app/buffer_oobe_ui.c`
- `foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/ui/BufferScreen.kt`
- `foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/ui/BufferVoiceButton.kt`
- `foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/ui/BufferRhythmPanels.kt`
- `foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/ui/BufferLabels.kt`
- `foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/ui/BufferOverlays.kt`
- `foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/ui/BufferLlmSettingsPanel.kt`
- `foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/ui/BufferWeeklyReview.kt`
- `foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/ui/BufferCardView.kt`
- `foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/ui/BufferCardDetailContent.kt`
- `foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/ui/BufferDesign.kt`
- `foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/ui/AddVelaPanel.kt`
- `docs/finals/15-ui-copy-audit.md`

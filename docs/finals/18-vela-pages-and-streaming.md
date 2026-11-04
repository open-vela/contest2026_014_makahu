# Vela 双页首页与流式接口核对

更新：2026-10-19。仅涉及 Vela 首页布局；手机 UI 不随本次调整。

## 页面职责

- 第一页 Buffer：记录内容、专注状态、原有按住录音入口。录音仍交给 Agent 自动判断记录或助手意图，不增加手动模式。
- 第二页助手：默认启动页。计时器、闹钟和当前保留的翻译、菜谱、回答卡片；没有底栏，也没有额外的助手启动按钮。
- 录音按钮及转写预览直接属于第一页容器；不通过切页后隐藏全局底栏实现。
- 外层框架和 Tileview 不加统一 padding；每页独立拥有 12px 内容边距，顶部栏及卡片独立管理内部 padding。画布为 320×480px。
- 两页使用 LVGL Tileview 横向切换，顶部显示页名和页码并可点击切换。第二页内容纵向滚动，横向滚动交给分页容器。
- 待办在第二页作为 64px 圆形启动器，使用官方 Material Symbols Rounded checklist 图标，下方仅“待办”。点击进入独立列表，返回恢复第二页。
- 助手新结果只更新第二页卡片，不强制切页、不覆盖当前阅读。点击卡片才展开详情。返回保留结果卡片；计时器、闹钟来自持久化事务，其他结果沿用现有最多三个 A2UI surface 的内存保留上限。
- 卡片详情依类型筛选操作：普通记录归档；未完成待办可完成；问题可深挖。不再因为旧卡片缺少 actions 字段就展示全部五个按钮。
- 录音取消按实际位移判断：横向 48px、上移 40px 或下移 48px。取消后消费这次按压，回滑、松手均不得提交。

设计依据是用尺寸、色彩、形状和容器组织主次，而不是只删除文字：
[Google Design：Expressive research](https://design.google/library/expressive-material-design-google-research)。

## MiMo 接口能力与当前实现

核对来源：

- [ASR API](https://mimo.mi.com/docs/en-US/api/audio/Speech-Recognition)
- [ASR 使用指南](https://mimo.mi.com/docs/en-US/quick-start/usage-guide/audio/Speech-Recognition)
- [Chat Completions API](https://mimo.mi.com/docs/en-US/api/chat/openai-api)
- [官方 ASR 开源实现](https://github.com/XiaomiMiMo/MiMo-V2.5-ASR)

| 环节 | 官方公开接口 | 当前项目 |
| --- | --- | --- |
| 语音输入 | WAV/MP3 文件经 Base64 放入一次请求；未找到支持连续 PCM 帧上传的 Realtime/WebSocket ASR 接口 | 录音完成、生成完整 WAV 后才上传 |
| ASR 文字输出 | stream=true，SSE 返回 choices.delta.content | 正式整段转写仍为 stream=false；新增 Vela 递增前缀预览使用 stream=true |
| MiMo 2.5 回答输出 | stream=true，SSE 返回正文、工具调用等增量 | Android 与 Vela 都设为 stream=false，等待完整响应 |
| 深度思考 | mimo-v2.5 默认 enabled，可设 disabled | Vela 服务检测显式 disabled，正式分类未显式关闭；Android 正式分类也未设置 |

ASR 的“Streaming”不能等同于流式音频输入。官方示例仍先读完整音频再发送；stream 控制的是响应文本。

助手慢尚未解决，也没有进行本次真实接口耗时测量。流式输出能缩短看见首段正文的等待，但不能承诺缩短模型计算或工具执行总时间。实现流式时应独立展示答案正文；工具参数和卡片 JSON 在完整、校验通过前不得执行或写入正式卡片。不能仅把 stream 改为 true 而继续使用完整 JSON 响应解析器。

## 已有验证与本次检查边界

用户要求停止额外和重复测试后，未再运行测试套件。前期针对修复已完成的检查：真实 LVGL 指针事件验证普通松手、手指抖动、横滑、上滑、回滑后松手；取消路径不提交。Windows 上一次横滑取消前后队列均为五份 WAV/metadata，没有新增。

双页和待办启动器仅做必要编译与 Windows 页面检查；不据此宣称全部功能已完成或助手已提速。

## 递增音频前缀预览

用户提出使用越来越长的录音片段请求流式输出，本次已实现 Vela 旁路预览：

- 16kHz 单声道 PCM 累计约一秒后请求首次识别，后续至少增长一秒才发下一次。
- 全局一个预览 worker、一个在途请求；请求完成后取最新前缀，不为每个中间长度排队。
- HTTPS 接收完整 HTTP chunk 后立即交给 SSE 解析器，不等整份响应结束；网络分片和 SSE 行分别处理。
- 每个前缀请求使用独立文本累积区，新前缀结果替换旧草稿，避免把重复前缀追加两遍。
- generation 标识录音会话；取消、松手、新录音和退出均使旧返回失效。网络等待按 250ms 检查取消，预览请求总期限 15s，DNS 仍遵守系统解析器期限。
- 临时文字只进入第一页转写预览，既不入队、也不执行任何工具。正式转写及分类仍使用最终提交的录音。
- 仍需真实录音验证提供商响应、首段文字延迟和最后结果的衔接；没有运行额外测试套件或自动上传已有个人录音。
- 当前模拟器固件录音上限仍为 8 秒；本次未擅自改动此配置。

手机录音实时预览和 LLM 答案正文流式渲染尚未实现，不应据此宣称整个语音助手已完成提速。

## 当前安装

Windows Vela `D:\buffer-vela-m3e-fresh-20261019` 已更新，继续使用原有数据盘和配对信息。最新固件 SHA-256：`946e6a2bdaa47d79770830701aa802014054866ceb29bf797c8f3930cbf7c97a`。启动日志 `vela-agent-20261019-235712.stdout.log`。

默认第二页及圆形待办入口已在 Windows 显示；录音按钮现在直接属于第一页 Tile，不再由切页事件隐藏。页面 padding 已移入各 Tile。递增音频 ASR 已编译安装，尚无本版真实录音的服务端验证，LLM 正文流式渲染仍未接入。

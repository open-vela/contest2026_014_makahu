# Buffer 运行时能力资源

`skills/` 收纳分类整理、问题回答、七日回顾、语音转写四类能力的 Prompt、function calling 定义与契约。`catalog.json` 全部标记为 disabled。

**`skills/` 四项资源仅归档，不调用。** 它们未接入加载器或构建产物，也不是 Codex 的技能安装目录。设备路径 `/data/agent/skills/` 是后续部署约定，本次没有部署或启用。

`runtime/focus-care/` 已作为 Android assets 接入能力建议。加载器核对版本、UTF-8、大小、契约和 SHA-256；资源只提供受限分析提示词，实际通知执行另需用户对具体建议版本的本机授权。资源摘要变化后旧授权失效。未运行构建或验收。

资源以当前 Android 实现为来源；`SKILL.md` 标明源码位置与提交约束。JSON Schema 用于离线检视，真实记录存在性、租约、用户修改、UTF-16 字符长度等仍由 Kotlin 代码验证，不能只凭 Schema 判断可提交。目录内不保存密钥、录音或个人记录。

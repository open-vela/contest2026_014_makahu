# Buffer 运行时能力资源

`skills/` 收纳分类整理、问题回答、七日回顾、语音转写四类能力的 Prompt、function calling 定义与契约。`catalog.json` 全部标记为 disabled。

**仅归档，不调用。** 本目录没有加载器，没有加入构建产物，不修改现有手机侧 LLM worker 的调用路径，也不是 Codex 的技能安装目录。设备路径 `/data/agent/skills/` 是后续部署约定，本次没有部署或启用。

资源以当前 Android 实现为来源；`SKILL.md` 标明源码位置与提交约束。JSON Schema 用于离线检视，真实记录存在性、租约、用户修改、UTF-16 字符长度等仍由 Kotlin 代码验证，不能只凭 Schema 判断可提交。目录内不保存密钥、录音或个人记录。

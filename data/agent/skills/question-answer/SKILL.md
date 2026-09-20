---
name: question-answer
description: 用户请求短答案时，根据问题给出答案或明确无法可靠回答。
---

# 问题回答

状态：资源归档，未启用。此目录未加入 Android assets、Vela ROMFS 或自动加载配置；本次整理不调用此 Skill，不改变已存在的 Android 代码调用流程。

输入为 `{"task":"answer_question","question":"卡片摘要"}`。读取 [output.schema.json](output.schema.json)，返回简明答案；无法可靠回答时返回 `unanswered=true` 和原因。没有 function calling。仅在用户请求短答案时由现有 Android 入口调用；不要把此 Skill 文件当作自动执行入口。

答案最多 4000 字符；未解原因由现有代码截取至 500 字符，空原因使用默认说明。失败或未解不伪造回答。

系统提示词快照见 [prompt.txt](prompt.txt)。

当前实现：`foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/data/llm/BufferLlm.kt`（相对于比赛仓根目录）。提示词或输出契约变化时同步更新此资源，目录本身不是执行器。

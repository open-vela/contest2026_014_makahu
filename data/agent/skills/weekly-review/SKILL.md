---
name: weekly-review
description: 用户请求周总结时，依据最近七天的真实记录生成回顾。
---

# 七日回顾

状态：资源归档，未启用。此目录未加入 Android assets、Vela ROMFS 或自动加载配置；本次整理不调用此 Skill，不改变已存在的 Android 代码调用流程。

输入为 Repository 构造的 `weekly_review` 事实快照：period_start、period_end、records、omitted_record_count、statistics、deferred_tasks。记录最多 100 条，不包含待转写音频及旧周总结卡；记录包含分类确认状态。

按 [output.schema.json](output.schema.json) 返回中文标题和正文。事实来自输入，建议与事实区分；样本不足、截断记录应说明。没有 function calling，不用规则拼接替代 LLM 周总结。

现有 worker 领取有租约的任务，保存成功才生成 WeeklySummaryGenerated；失败保留任务并退避重试。重复周任务按现有 job ID 去重。

系统提示词快照见 [prompt.txt](prompt.txt)。

当前实现：`foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/data/llm/BufferWeeklyLlm.kt`（相对于比赛仓根目录）。提示词或输出契约变化时同步更新此资源，目录本身不是执行器。

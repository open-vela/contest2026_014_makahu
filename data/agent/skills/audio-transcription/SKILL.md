---
name: audio-transcription
description: 将手机或 Vela 的已保存录音转换成文本，供后续分类。
---

# 语音转写

状态：资源归档，未启用。此目录未加入 Android assets、Vela ROMFS 或自动加载配置；本次整理不调用此 Skill，不改变已存在的 Android 代码调用流程。

该能力没有文本 system prompt，也没有 function calling。请求格式见 [request.template.json](request.template.json)，模板中的音频占位符不是可发送的请求。

- 输入为本地已落盘录音：手机 AAC 先由 MediaCodec 转 PCM/WAV；Vela WAV 走校验。
- PCM16 信号检查先于网络调用；近乎数字静音被拒绝。此检查不是语义级 VAD。
- 使用 `mimo-v2.5-asr`，`asr_options.language=auto`，返回非空文本，最长 32000 字符。
- 成功后先持久化转写，再唤醒原有分类 worker；失败保留原音频与待转写状态，不进入收藏。
- Base URL 与密钥来自手机设置，不写入 Skill 资源或下发 Vela。

当前实现：`foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/data/llm/BufferTranscriptionWorker.kt`（相对于比赛仓根目录）。提示词或输出契约变化时同步更新此资源，目录本身不是执行器。

# Vela 远端原始音频接收与持久回执

日期：2026-10-24。任务 05 的 Android→Vela blob 接收子阶段。只修改源码、文档并静态回顾；未运行测试、构建、编译/语法检查、格式化程序、配置生成或设备操作。手机发送器尚未接入，本阶段不构成反向端到端交付完成。

## 授权与来源绑定

继续以 `/home/simon/projects/mocha/foundation.fabric` 核对框架职责，读取其系统契约和 `crates/hub/src/messages.rs`：Channel 授权和可靠承载不等于应用原件已持久接收。本阶段不修改框架，不把二进制归档或媒体业务放入 Hub。

新增 `buffer_source_remote_owned_read`，在 remote journal 锁内核对已提交 head、固定 owner、源序号和精确原文/指纹；仍按 local journal→remote journal 的顺序进入，授权回调只读取通道状态，不回调 journal。普通本地 read 接口保留原用途，不当作二进制接收授权。

接收前及每份请求都核对认证 peer、已提交的源原文 SHA、序号和 blob 描述符。只接受 schema 1 的 CaptureCreated、CaptureBlobDescribed 和 LegacyAudioSnapshot；前两种还要求明确 `local_original=true`。描述符六字段、完整 SHA、字节数、MIME、mode、时间和时长范围均校验。文件路径来自源 ID 的 SHA 和数字序号，不使用请求方路径或 MIME 拼接。

这证明已认证来源提交了该描述符，不表示已经完成跨成员原始捕获来源审查。未知来源历史的显式审核与授权仍在任务 05；此接收器不改变其产品政策，也不转发第三方原件。

## 协议与有界存储

新增请求：

- `buffer.events.blob.offer`：type、protocol、exchange_id、source_id、source_sequence、record_sha256、blob_sha256、total，共八字段。
- `buffer.events.blob.put`：上述字段加 offset、blob_base64，共十字段。
- `buffer.events.blob.received`：上述公共八字段加 next_offset、state，共十字段。state 为 ready、partial、restart、stored；只有 stored 表示完整文件与回执已持久提交。

沿用原有严格公共字段、协议版本、exchange nonce、协商消息预算和每次回复前的实时通道授权检查。错误通过 `buffer.events.error` 返回 event_missing、conflict、busy、storage_error；关闭 journal 的构建返回 unsupported。非法请求不进入业务执行器。

每份 blob 上限 2 MiB，与 Android 描述符上限一致。每片最多 3072 字节，要求标准 Base64 的重新编码完全相同。接收进度只在一个 worker 所属传输内有效，绑定 peer、generation、source、sequence、原文 SHA、blob SHA、大小和类型；不把部分进度当作持久交付。

分片直接写入固定私有 `event-journal/remote-blobs/incoming.partial`，不分配整份 2 MiB 音频缓冲。顺序追加；已经接收范围的重传必须逐字节匹配，重叠越界/跳跃明确失败。单片失败后丢弃不完整传输。重连、generation 变化、停用、关闭、时钟失败/回退或空闲 30 秒清理 fd 和临时文件；重启遗留的固定 staging 文件只在确认是私有普通文件后替换。完整归档从不由 reset 删除。

## 文件提交、回执与恢复

只使用私有目录、0600 普通文件及 O_NOFOLLOW（可用时），并以 lstat/fstat 核对。所有循环处理短读、短写及 EINTR。完整后通过同一个 fd 流式核对实际大小、完整 SHA 和 fsync，再 rename 至源 SHA 目录下的序号 `.blob` 文件。归档层不要求 WAV magic，不解码 MP4，也不把无法解码的原件改写或丢弃。

文件先于独立九字段回执提交。回执绑定 owner、source、sequence、event ID、原文 SHA、blob SHA、字节数、MIME 和 schema。目录创建及 rename 路径在 host 上同步相应父目录；NuttX 沿现有 LittleFS 私有 backend，通过后续相同回执的原子写入重新执行实际 backend barrier，避免 rename 可见但之前 cfg.sync 失败时直接报重复成功。

回执内容冲突不覆盖。已有完整文件必须重新打开、核对全部字节 SHA、fsync，才能重写同一回执并回复 stored；文件已保存而回执丢失/失败可由重复 offer 或最后分片恢复。只有回执、文件缺失时继续请求相同原文绑定的原件；已有错误文件保持 conflict，不覆盖成另一个原件。原文接收回执和 Fabric carrier ACK 都不能替代本回执。

新目录纳入 journal 初始化和“本机 META 丢失但已有历史”检查，不能因只留下远端 blob 而生成新 source 身份。源事件、Capture/card、ASR、日历、健康、通知和其他外部动作均不由此服务创建或重放。

## 静态回顾与后续

已读取并回顾：原文 owner 锁序、配置关闭分支、严格路由字段、应用 worker 调用、接收 fd 生命周期、预算内回复、重复分片、最后回执丢失、文件/回执部分失败、权限变化、目录与文件权限、二进制 EOF/哈希及双构建清单。未运行任何验证程序，也没有硬件耐久性或吞吐结论。

下一步仍须实现 Android 发送任务/持久账本、已提交源事件回执门禁、本机档案读取和每片分享复核、断连重启恢复与用户状态。完整双向 blob、大项 05、能力生命周期 06 和多成员 07 均保持未完成。

# Vela 发送侧持久回执

日期：2026-10-24。任务 05 的交付元数据子阶段。修改代码并做静态回顾，未运行测试、构建、编译器、语法检查或设备操作。补齐 Vela→Android 方向的设备发送侧持久回执，以及手机已经入库但设备尚未记录回执时的恢复路径。权限保留槽、投影、blob 和完整复制仍未完成。

## 持久事实与协议

`buffer_source_events.c` 在私有 journal 下增加 `outgoing/<peer>.json`，按已认证 peer 保存连续交付游标。六个字段为 schema_version=1、peer、source_id、last_sequence、last_fingerprint、last_size。它是传输元数据，不追加 source event，不删除源原文、录音或旧历史。新目录同时进入源身份丢失检查；不能在还留有交付数据时重新生成来源身份。

每次读取游标，使用严格 JSON 对象校验，检查字段数量、来源和 peer、安全整数、提交 head、最后一条原文、独立 SHA 文件、实际指纹及大小。损坏、原文缺失或目录同步失败不能报告成功。元数据不存在时从序号 1 补记历史；它不意味着源历史不存在，也不允许跳过接收端缺少的原文。

新增 `buffer_source_delivery_progress` 和 `buffer_source_delivery_accept`，均在本地 journal 锁内调用。原文读取拆出 `local_read_locked`，避免在已持锁的回执路径再次获取同一锁。授权回调沿用 38 阶段只读取短时快照的实现，不反向获取 Fabric 锁。回执提交仍在应用 worker，radio/UI 回调不执行这些文件操作。

| 帧 | 公共字段之外的字段 |
| --- | --- |
| `buffer.events.manifest.get`，可选扩展 | receipt_version=1 |
| `buffer.events.manifest`，仅响应扩展请求 | source_id、last_sequence、receipt_version=1、next_unacknowledged |
| `buffer.events.accepted` | source_id、source_sequence、record_sha256、total |
| `buffer.events.accepted.reply` | source_id、source_sequence、record_sha256、total、next_unacknowledged |

公共字段仍是 type、protocol=1、exchange_id。旧三字段 manifest 请求仍得到原五字段响应；不会把新增字段强塞给旧控制器。新手机首次请求扩展，连续六次没有响应则在本连接内退回旧 manifest/pull，保留既有接收能力。这个兼容分支明确没有设备发送侧回执，不伪造交付记录。busy/storage_error 保留同一请求重试，不触发兼容降级。

## 提交与重连恢复

手机只有在 `importSourceEvent` 的事务成功、且当前授权仍有效后，才发送 accepted。其来源、序号、精确原文哈希和 UTF-8 字节大小来自已经验证并提交的 envelope。接收进度和发送侧回执分开：设备未回复 accepted.reply 时，手机原文已经持久保存，但控制器不将本次交付交互推进到下一条。

设备将 accepted 与本机已提交原文逐字节哈希、大小、source 和 sequence 核对。恰好等于 next_unacknowledged 时，原子写入新游标；更早序号只在原文核对通过后幂等回复，不回退游标；未来序号返回 receipt_gap，要求重新读取 manifest，不能越过缺口。原子写入使用原有临时文件、fsync、rename、父目录同步；若先前 rename 成功而目录同步失败，下一次读取会再次同步回执文件父目录后才报告游标。

手机恢复时分别读取自己的持久接收进度与设备回执进度，从两者较小值继续：

- 手机已有该序号而设备缺回执：`BufferEventJournal.received` 在同一 SQLite 快照内核对 owner、连续接收 head、原始 envelope、事件 ID、逻辑时钟、指纹和序号，然后仅发送 accepted 元数据补记，不重建或重新下载原文。
- 设备已有旧回执而手机现在缺原文：仍拉取手机缺少的原文并正常入库；设备旧回执不能让接收端跳过当前缺口。
- accepted 在设备提交后回复丢失：重试时按旧序号核对原文后返回当前游标；手机逐条完成，不把载体 send 的成功当成持久回执。
- 手机本地证明读取失败：暂停并重新协商，不发送未经存储核对的历史回执。设备持久化失败：保留同一 accepted 请求重试。重连、nonce 或 generation 更换后不接受旧交互的回复。

历史回执只是对既有接收事实的确认，不携带 payload，也不在新的权限下重新分享原文。逐片数据分享门禁保持生效；权限保留槽接入前，遇到受限事件仍阻塞其后的范围。两个方向的发送侧元数据都已接入，但不能因此将整个双向复制大项勾选完成。

## 静态回顾边界

后续 Android 因果索引及只读来源历史见 [40 阶段](40-source-causal-history.md)；不将该元数据视图当作完整业务重放。下文保留本回执阶段边界。

[42 blob 服务阶段](42-vela-source-blob-service.md)进一步修正 NuttX 的回执确认边界：该平台没有目录 fd fsync，读取已验证的游标时重新原子提交同值传输元数据，避免将失败 rename 后仅在 RAM 可见的值视为持久成功；宿主后端仍同步父目录。原 source 事实不改变。

已逐段读取新增 API、锁内原文读取、journal 初始化/源身份丢失保护、原子文件持久化、wire 分发、字段数与类型校验、Android 接收事务及 controller 的正常/重复/缺口/重连/存储失败分支。新增 Android 查询没有数据库结构变化，不增加数据库版本；已有 Make/CMake 源文件清单继续包含修改的 C 文件。未运行任何验证程序。

仍待完成：权限保留序号及权限恢复补发、缺失父项处理、受限投影重放、blob 独立交付、完整产品状态 UI、剩余业务事实覆盖、任务 06 能力生命周期及任务 07 多成员闭环。整体目标继续进行中。

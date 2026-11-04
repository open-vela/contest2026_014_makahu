# Android 到 Vela 的可见性传输

日期：2026-10-25。任务 05 的反向网络子阶段。只修改源码和文档、静态回顾；没有运行测试、构建、编译/语法检查、格式化、配置生成或设备。复用了独立 Fabric 源仓库的授权可靠消息契约，未修改框架源仓库。

## 实际接入

Enrollment 的发送器切换为 `VelaVisibleSourceEventPush`，沿用既有 worker、发送预算及六类同步任务轮换；Vela 的 `buffer_source_wire.c` 接入对应请求并调用阶段 48 的存储入口。接收方向与阶段 50 各自显式协商，不能由一个方向的支持推断另一方向支持。

| 请求 → 响应 | 请求/响应字段数 | 语义 |
| --- | --- | --- |
| visible.offer → visible.ready | 6 / 7 | 查询同一 owner/source 的真实覆盖及原文前缀 |
| visible.reserve → visible.reserved | 7 / 10 | 持久保存随机 token，确认覆盖；不确认原文 |
| visible.put → visible.received | 11 / 13 | 有界分片和完整原文校验/提交 |
| visible.proof → visible.received | 9 / 13 | 对已保存原文核对 SHA/大小，补记完整回执 |

以上 type 均带 `buffer.events.` 前缀；共同字段是 type、protocol=1、visibility_version=1、exchange_id。错误沿用五字段 `buffer.events.error`，不伪装成成功响应。两端核对字段、类型、nonce、source、sequence、token、大小和进度范围。

## 受限位置与原文回执

发送器先用稳定随机 token 保留位置，再按当前策略发送原文。因此公开位置也先经过槽，增加一次请求/响应，换取一致的撤销和重连路径。reserve 帧不含原文 ID、SHA、类型、时间或大小。`original_present` 仅说明接收方已有该位置原文；仍须单独 SHA/大小证明，不能直接形成新的完整交付回执。

Android 持久提交槽回执后，即使原文不可分享，也会继续后续位置。新覆盖与旧 held 位置轮换；仍受限的旧槽只消耗一次有界选择，向后移动填槽游标，后续公开位置有下一轮机会。没有新位置时继续扫描旧槽，完成一轮后退避，再查询新的本机 head。重连从接收方覆盖位置与本机缺少完整回执的位置恢复，对失去本机槽确认的记录重发相同 token。

只有 `stored`/`duplicate` 的完整 SHA、大小及结束偏移匹配时，才调用既有 `sourceEventDelivered`。该事务保存真实完整回执并将相应 outgoing 标为 filled；blob sender 仍以这个独立完整回执为前置条件。晚到或重复槽确认保留 filled，不清除已有原文、blob 或历史交付事实。

## 授权、恢复及界限

手机在每次原文分片和证明发送前重新核对共享策略，包括缓存重试。权限撤销后擦除缓存分片字节，改为重发同一槽；不能把队列游标当成同意分享。通道授权在发送、接收和持久化边界检查。已经收到的真实完整回执是历史事实，不能因当前共享撤销而抹除。

Vela 的分片缓存绑定 peer、generation、source、sequence、SHA、总大小、profile 和 token，最多一条 1 MiB 原文；每片最多 3072 字节且按实际协商预算缩小，Base64 必须规范编码。重复分片须与缓存完全一致，偏移不连续、身份/哈希不符拒绝；缓存超时或重连时从原文/槽持久状态恢复，不推进虚假进度。完整原文进入既有可恢复写前事务；槽不进入事件 ID 索引、观察时钟或业务执行器。

证明请求先检查位置已经被覆盖，不能新建槽或凭哈希推进覆盖；再验证已有 token，并读取同 owner 的实际原文核对完整 SHA/大小。legacy 连续原文位置可证明旧原文，但不能由新 token 声称此前已经接受该 token。`original_present` 不检查发送者猜测的哈希，实际证明另行执行。

未协商新 profile 时允许等待旧 sender 空闲后回退旧协议，并定期重新探测；一旦本机已持久登记该 peer/source 的 visibility token，就不再用旧发送协议覆盖该流。旧协议保留给尚未使用新 profile 的旧固件，不将降级说成完整同步。关闭 journal 的 Vela 明确返回 unsupported。

静态回顾覆盖协议字段与预算、首次槽/重复槽、槽 ACK 丢失、原文 ACK 丢失、手机账本提交失败、旧原文证明、稀疏填槽、共享撤销、缓存换 profile、索引暂忙、旧固件回退及配置关闭入口。新增 Kotlin 文件由既有 Android sourceSet 收集，没有新增 C 编译单元或数据库版本。

当前两向 visibility 网络入口均已接通；这仅证明本子阶段的源码路径。任务 05 的完整父链、受限业务投影、入口覆盖及容量/损坏恢复总回顾，以及任务 06/07 仍未完成。实际端到端行为仍未运行验收。

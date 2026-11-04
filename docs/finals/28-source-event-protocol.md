# 源事件 envelope 与 Android journal

日期：2026-10-23。任务 05 的第一阶段实现记录。已接入 Android 本地写入和接收存储入口；尚未接入网络、Vela journal、补传交互或视图重放，不表示整个原始事件复制已经完成。只做代码修改及静态回顾，没有运行测试、构建或设备。

后续状态：Vela 私有存储下的本地 journal 与 WAV 归档已实现，范围与未完成入口见 [29 第二阶段](29-vela-source-journal.md)。下文剩余清单保留第一阶段边界；完整网络、投影、S1 配置及 blob 交付仍未完成。

## 已实现的数据契约

`BufferSourceEvent` 保留 envelope 的精确 UTF-8 文本，以 SHA-256 标识内容。源端保存一次，后续从 journal 读取原文本；接收端不重新序列化、不重新赋予 event ID、父引用、源时间或逻辑时钟。

```json
{
  "wire_version": 1,
  "source_id": "src-<installation UUID>",
  "source_sequence": 1,
  "provenance": "native",
  "event": {
    "event_id": "evt-<UUID>",
    "app_namespace": "app.buffer",
    "actor_id": "<origin actor>",
    "schema_version": 1,
    "parent_event_ids": [],
    "logical_clock": 1,
    "type": "CaptureCreated",
    "capture_id": "<capture ID or null>",
    "created_at": 0,
    "payload": {}
  }
}
```

示例里的占位文本只解释字段；实际身份值必须满足实现的字符集和长度约束。

| 字段/规则 | 当前约束 |
| --- | --- |
| envelope | 固定五个顶层字段，最大 1 MiB UTF-8，拒绝额外字段及尾随内容 |
| wire_version | 当前为 1；与事件本身的 schema_version 分开 |
| source_id | 持久应用 journal 身份，1–96 个 ASCII 字母、数字、下划线、点或连字符；不是自带公钥证明的 Fabric 身份 |
| source_sequence | 每个 source 从 1 连续递增，最大为 2^53−2；预留一个安全整数供终止位置回执使用 |
| event_id/parent/capture | ID 为 1–160 个受限 ASCII 字符；父列表最多 32 个、无重复、不能引用自己；capture_id 可以为 null |
| namespace | app.buffer |
| logical_clock | 原生事件为 1..2^53−1；兼容历史允许 0；本机新事件在所有已观察 journal 及原事件时钟之上递增 |
| created_at | 0..2^53−1，保留来源墙钟，不用作跨设备顺序依据 |
| provenance | native 表示该源写入路径发出的事件；legacy_local 表示升级前的本地事件，包括旧版接收端重建的历史，不能据此声称恢复了远端原始事件 |
| JSON | 双引号、有限数值、最大 32 层，拒绝重复对象键、非标准转义、无效 Unicode 和对象键中的 NUL；合法 payload 字符串中的转义 NUL 作为原始数据保留 |

native 的 source 是 envelope 的写入来源，不会把 payload 中描述的捕获设备重标成当前手机。旧 processed 导入仍有自己的兼容事件；后续必须接入真正的设备源 journal，不能用这些兼容事件替代它。

## Android 存储与迁移

实现位于 Android Buffer 的 `domain/BufferSourceEvent.kt`、`data/local/BufferEventJournal.kt` 与 `BufferRepository.kt`。数据库版本升到 22：

- `source_event_local` 保存本机 source ID 和已分配序号，随数据库持久化，不依赖进程内计数器。
- `source_events` 保存 event ID、source/sequence、逻辑时钟、capture 索引、内容指纹及精确 envelope。event ID 与 source/sequence 都有唯一约束。
- `source_event_receivers` 保存 source 的已认证逻辑对端绑定及连续提交进度。
- `source_event_projections` 保存待处理的远端历史，后续投影器尚未接入。

新建数据库创建空 journal。旧数据库在 SQLiteOpenHelper 的升级事务里按原 rowid 顺序回填，保留原事件 ID、actor、时间、父引用和逻辑时钟；旧 schema 中未提供的逻辑时钟 0 不伪造为原有来源时钟。回填新 envelope 统一标记 legacy_local，不改写旧 events 行。迁移失败由升级事务回滚，不跳过或截断坏记录后宣称完成。

`appendEvent` 现在总是进入事务，包括已有业务事务中的嵌套调用：原事件、journal envelope、源序号以及捕获上下文请求/queue 在同一原子提交内。嵌套失败会使外层事务失败，即使调用方捕获异常，也不能只提交原事件而漏掉 journal。

回顾同时补齐了提醒调度、卡片回流和旧文本捕获入口的 repository monitor：它们原先可能先持有 SQLite 写事务，再进入已同步的 appendEvent，与先持有 monitor 再等待数据库的线程形成锁序反转。现在这几个外层入口也先获取 monitor，与新导入入口及其他现有写入入口保持一致。

本地新事件的父引用从已观察 journal 选择；同一 capture 按逻辑时钟选择，时钟相同则按稳定 source/sequence 排序。由此远端已持久化的原始事实可以参与后续因果引用，来源墙钟不能把历史重排。原有历史父引用保持不变。

## 接收、幂等与提交边界

`importSourceEvent` 要求来自传输层的 Fabric peer 身份和必须提供的实时授权回调。存储入口在解析前、进入事务后及提交前复核授权。source 第一次成功提交时绑定到逻辑对端；已绑定 source 不接受换一个对端发布，也不接受本机 source 回流。

该绑定是经授权源端对自身应用 stream 的首次声明与持久 pin，不是可转交的来源签名。当前入口不支持其他成员转发来源历史；后续跨成员转发需要另外设计来源证明和授权，不得把载体加密当成这项证明。

接收结果为：

- stored：恰好是下一个序号，原文、索引、接收游标和投影 queue 同事务提交。
- duplicate：同 event ID、source、sequence、指纹和精确原文已提交；不重新入队。
- gap：缺少较早序号，不插入越过缺口的记录，返回期望的下一个位置。
- conflict：重用已提交序号或 event ID，内容/来源不一致，或原生来源的逻辑时钟倒退；不覆盖原记录。

状态只描述本机存储结果。网络回执尚未接入，不能用这些入口的存在声称消息已被对端持久接收。父事件可能来自另一条 source；存储层保留引用并提供缺失父项查询，后续投影器须等待或明确处理不可见/缺失父项。

远端历史目前不写入旧 events 执行事实表，也不调用 appendEvent、分类 worker、日历、健康或通知执行器。旧表中的部分读取器会把 CalendarEventCreated/HealthDataWritten 等看成本机执行回执，因此历史投影必须单独接入，不能直接插表触发隐式副作用。

## 隐私与剩余实现

本机 page/manifest 读取必须提供实时授权回调；这些 page 是存储数据，不等于全部可以发送。`mayShareSourceEvent` 只考虑本机 source：上下文事件默认保留，只有当前用户明确允许分享、对应本机采集权限仍有效的 CaptureContextCaptured 才可发送；策略/请求历史及导入的第三方上下文不因新增通道直接转发。该检查仍需由后续 sender 在每个分片发送前调用。

以下工作仍未完成，任务 05 保持未勾选：

1. Vela 不可变源 journal、稳定序号/时钟及捕获、处理和用户操作的实际写入钩子。
2. 双端版本协商、manifest、分片、SHA-256 校验、持久提交回执、断连重传及重启恢复。1 MiB 是 envelope 上限，不是允许发送超过 Fabric 协商预算的单帧。
3. 权限保留序号的显式 visibility 标记、分享权限变化后的补传以及父引用解析；不能简单跳过私密事件后把游标宣称为完整，也不能为发送而修改不可变 payload。
4. 跨 source 缺口查询及幂等投影处理器；遇到未知 schema、缺失父项或执行回执时明确保留状态，不执行历史命令。
5. 原始音频 blob 的哈希、授权、交付状态与事件/processed 结果关联。payload 中的本机路径只作为数据，不能在接收端当作可打开的来源路径。
6. 已认证消息入口、旧 processed/CRDT 协议兼容与最终逐项代码回顾。协议和投影真正接入之前，不广播原始事件同步完成。

# Android 权限保留槽、双进度与补发存储

日期：2026-10-24。任务 05 的 visibility-v1 存储子阶段。只修改源码、文档并静态回顾；未运行测试、构建、编译/语法检查、格式化程序、配置生成或设备操作。**本阶段尚未更换两端网络协议，当前实际传输仍会被受限序号阻塞。** 不把新增存储入口当作已解除同步阻塞。

## 职责与数据边界

读取独立 `/home/simon/projects/mocha/foundation.fabric` 的系统契约与授权 ReliableMessages 实现后，继续将业务可见性、原文语义和接收进度放在参赛快照的 Buffer 层。没有修改框架或覆盖独立源仓库。

保留槽属于已认证来源向特定对端提供的“这个序号暂不交付原文”的声明，不是源事件，不改写源事件，不使用空 payload 伪装原文。它只需要 visibility 版本、source、sequence 和随机 token；协议外层可携带消息类型、版本和请求 nonce。**不得在槽中带出原事件 ID、类型、Capture ID、时间、逻辑时钟、原文大小或哈希。** source/sequence 本身的存在和范围是该授权视图所公开的元数据。

token 不是隐文内容的加密承诺，也不是可转交的来源签名。它绑定同一来源位置的保留与后来填槽；内容真实性仍依赖认证 owner、源端不可变 journal，以及首次收到原文后的精确字节/指纹约束。不能据此声称接收者已经验证尚未获准读取的内容。

## 两种进度严格分离

数据库升至 29，新增接收 profile、槽及发送 token 表，不重写旧原文或旧游标：

- `source_event_receivers.committed_sequence` 继续表示连续**原文**前缀。只有所有较早槽均已填入真实原文时才能向前推进。
- `source_event_visibility_receivers.covered_sequence` 表示由真实原文或显式保留槽连续覆盖的位置。
- `source_event_visibility_slots` 保存 held/filled、固定 owner 和 token。held 没有 event ID 或指纹；filled 才绑定已提交原文的真实 ID/指纹。
- `source_event_visibility_outgoing` 保存发送端按 peer/source/sequence 的随机 token 和本机原文绑定。其本机 ID/指纹只用于内部校验，不能进入槽帧。

例如先接受槽 1，再接受真实原文 2，则 coveredNext=3、originalNext=1；不能把 originalNext 报成 3。后来槽 1 获准填入同一位置的原文，原文前缀才推进到 2。两个游标、owner、原文、槽状态和派生工作同一 SQLite 事务提交，授权失效或存储失败整体回滚。

新建和升级只增加表/索引，没有启动迁移重放或对既有流主动启用 profile。旧流初次进入明确的 visibility-v1 存储入口时，以原连续原文前缀初始化覆盖位置；没有已提交原文/槽而跳跃请求时返回 gap，不抢先 pin 未成功接收的来源。

## 接收保留槽与后来填槽

`BufferEventVisibility` 和 repository 的版本化入口提供 progress、reserve、receive、nextHeld。必须来自传输层提供的当前认证 peer，并在进入事务后及提交前再次复核授权；拒绝本机 source 回流、变更 owner 和转发其他来源。

接收新槽只允许 covered+1。相同 token 的重复槽幂等；同一槽换 token 或 owner 不覆盖。已保存的完整原文不因后来撤销共享而降级为空槽，返回 original_present；该状态本身没有原文指纹，发送端不能将它当作新获得的原文交付证明。

正常公开原文只能出现在 covered+1；补发较早位置必须命中 held 槽及相同 token。仍核对严格 envelope、稳定 source/sequence、event ID 唯一性、精确字节和 SHA；同 ID 或同序号的不同内容返回 conflict。已有真实原文的重复输入只确认完全相同字节，不重入历史/投影队列。

原文导入复用 `BufferEventJournal.archiveReceived`，只进入源 journal、因果元数据工作及待业务投影队列。槽不进入 source_events、旧 events、逻辑时钟或任何执行队列；没有 Capture/card、ASR、日历、健康、通知、模型调用或删除副作用。

填槽检查两侧已经存在的直接相邻原文：本项为 native 时，其时钟必须高于前项；后项为 native 时，后项时钟必须高于本项。未知槽没有伪时钟，不跨越它推断钟值；legacy_local 可能合法包含较老时钟。以后补齐相邻位置时再次核对，因此不能关闭最后缺口后仍掩盖相邻时钟冲突。

原文前缀由最早 held 槽之前的位置决定；覆盖仍取真实连续接收范围，不通过 MAX(sequence) 猜测跳过缺口。槽/覆盖/owner 元数据丢失、覆盖尾没有原文或槽、原文超出覆盖、已填槽与原文 ID/SHA 不一致均报存储不一致，不重置为新流。边界和目标原文按索引核对，不声称每次请求都扫描了全部历史。

## 发送 token 与真实交付回执

`BufferSourceVisibilityOutgoing.prepare` 在私有事务里核对本机 journal 的精确原文，然后生成独立随机 UUID token；同一 peer/source/sequence 保留原 token，不能由内容 SHA 或事件 ID 派生。重连或重复准备不重新换标记，其他对端有独立登记。准备只记录本机元数据，不代表对端收到槽。

`reserved` 只在匹配已准备 token 的保留槽回执后标记 held。它不写入 `source_event_delivery_receipts`，不确认 blob，也不授权删除原文。已有原文交付历史继续保留，不因槽确认或权限撤销抹掉。

只有现有完整原文回执事务在核对真实 ID、序号、SHA 和精确字节后，才能将对应本机 token 标为 filled。损坏的 token 记录被隔离为 conflict，不吞掉已验证的真实原文回执或覆盖错误绑定。按 held 索引提供后续补发位置，供网络控制器逐项重新检查当前分享权限；新存储方法本身不授予共享权限。

## 原文读取与因果历史兼容

旧 `sourceEventResume` 仍返回连续原文位置。`receivedSourceEvent` 与因果历史校验改为读取明确覆盖范围内已提交的真实原文，因此允许读取槽之后的真实记录；尝试读取 held 槽则失败，不能制造一个空事件或假 blob 描述符。

父项 locator 已识别“有槽但无原文”这一合法缺失：来源当前报告 available 时可保留后续填槽机会，其他覆盖范围内原文缺失仍归为 history_gap，不能用 locator 静默修复历史身份。没有原事件 ID 的槽不会被编造为某个 Capture 的父项或历史行。

旧协议接收入口不会把覆盖进度冒充原文进度。来源已经进入 visibility-v1 profile 后，必须继续使用相应接收方法，不能降级用不理解槽的旧 receive 改写该流。尚未启用 profile 的现有网络流继续走原路径；本阶段没有自动对旧固件开启扩展。

## 静态回顾与后续接入

已读取并回顾新建/升级、正常旧流、首槽 owner pin、重复/冲突槽、公开原文跨槽保存、后填槽、相邻时钟、ID 冲突、授权撤销、嵌套只读事务、游标与元数据缺失、原文证明读取、父项定位、发送 token 生成/持久化和完整回执钩子。SQL 对序号范围、整数类型、槽状态和 held/filled 字段关系增加约束；没有运行 SQL 验证器或其他验证程序。

下一步必须补齐，不能据此勾选整个权限保留槽或原始复制：

1. Vela 远端接收增加相同双进度、槽持久化、填槽及写前恢复，兼顾原文索引计数与全局观察时钟。
2. Vela 发送侧增加覆盖确认与稀疏的真实原文确认。现有连续原文 `next_unacknowledged` 不能直接越过槽；原文和槽回执须有不同语义。
3. 两端显式协商 visibility-v1，接入独立且严格的槽帧、填槽 token、双进度与回执。旧客户端不能将覆盖确认解释成原文接收成功。
4. 发送器公平处理后续公开位置和已有 held 位置；每片、每次重试都检查当前分享权限，恢复授权后补发精确原文，不被旧的历史回执错误抑制。
5. 接通真实网络错误/重连恢复与来源状态展示，再总回顾父链、blob、受限业务投影、事实入口及容量/损坏恢复。任务 06、07 和运行验收继续保留未完成。

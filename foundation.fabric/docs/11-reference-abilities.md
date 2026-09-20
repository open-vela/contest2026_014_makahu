# 11. 参考 Ability 映射（非核心实现）

本文件只证明框架足以承载目标能力。以下任何业务逻辑都不得进入 Hub。

## 1. 文件同步

```text
Ability: com.example.file.sync@1
Role: replica
Ports:
  control  bidirectional reliable_messages
  content  bidirectional reliable_stream
Clock: none
Topology: arbitrary replica graph
Security: link encrypted; private vault 可强制 E2EE
```

Ability 自己实现：文件树、内容哈希、chunk manifest、断点续传、版本向量、冲突副本、删除 tombstone。

框架提供：participant identity、Session graph、可靠消息、可靠流、权限、恢复事件。

## 2. 剪贴板同步

```text
Ability: com.example.clipboard.sync@1
Role: peer
Ports:
  events bidirectional reliable_messages
Clock: optional
Topology: one-to-many or many-to-many
Security: link encrypted or E2EE
```

Ability 自己实现：MIME、最大对象、历史、冲突规则、设备过滤、敏感内容确认。

## 3. 密钥同步

```text
Ability: com.example.secret.transfer@1
Roles: source, destination
Ports:
  control bidirectional reliable_messages
  secret  source->destination reliable_messages/stream
Clock: none
Topology: usually one-to-one; one-to-many as separate destinations in one Session
Security: trusted device + exact/hub-attested app + user presence + mandatory E2EE
```

Hub 只看外层路由头。Ability 自己做密钥包格式、目的密钥库验证和导入事务。

## 4. 实时数据/状态流

```text
Ability: com.example.state.stream@1
Roles: producer, consumer, aggregator
Ports:
  control reliable_messages
  updates datagram or reliable_messages
  snapshot reliable_stream/messages
Clock: optional/required by domain
Topology: all forms
```

典型模式：Datagram 传 latest state，可靠通道传 snapshot 和恢复请求。

## 5. 跨设备音频播放

```text
Ability: com.mocharealm.foundation.audio.render@1
Roles: source, renderer, controller
Ports:
  control  bidirectional reliable_messages   # 下行 announce/asset/barrier；上行 AssetRequest/Ready/Reject 与 Play/Pause/Seek/Stop
  media    source->renderer reliable_stream  # 压缩容器渐进字节；MediaDescriptor.decode 携带零探测解码参数
  asset    source->renderer reliable_stream  # 封面/长歌词独立单向数据流（open_uni），与 control 解耦
  feedback renderer->coordinator datagram/messages
Clock: required
Barrier: required for multi-renderer synchronized start; 单 renderer / 本地播放走快速起播
Topology: one source to many renderers; optional controllers
```

> 说明（对应 fabric 原语）：`control` 用 `PortDirection::Bidirectional` +
> `PortMode::ReliableMessages`；`media` / `asset` 用 `PortMode::ReliableStream`，
> 由平台 adapter 通过 `FabricLink::open_uni` 承载；`feedback` 用 `PortMode::Datagram`。
> 框架无需新增能力，Ability 只是按上述方向声明端口。

### 媒体与元数据路径

目标路径（`foundation.audio` fabric-adapter）：

- `media` 使用 reliable stream 传递**压缩容器**字节（mp3/aac/flac/opus…），**禁止 PCM 多播**；
  source 端**流式分块**读取并即时写入网络管道，多端分发时对所有 writer 同一遍写出，
  **不得** `read_to_end` 整体缓冲后再发；
- **零探测起播**：`TrackAnnounce.media` 的 `MediaDescriptor` 携带 `decode`
  （demuxer 短名 + `codec_id` / 采样率 / 声道 / `extradata` 等精确参数）。renderer 据此直接
  选择 demuxer 并把 `probesize` 从 32 MiB 降到数十 KB，跳过 FFmpeg 盲探，拿到数据即出声；
  无 `decode` 时回退盲探。renderer 通过 stream AVIO 直接 demux/decode，**不要求落地 fd**；
- 曲名、专辑、歌手、歌词走 `control`：`TrackAnnounce` + 内容寻址的
  `AssetRequest` / `AssetOffer` / `AssetChunk`（长歌词按 hash 缓存，命中则不再传）；
- **封面等大体积二进制从 control 面剥离**：优先 `TrackAnnounce.cover_url` 由 app 异步
  off-fabric 拉取；否则经 `AssetStreamAnnounce` + 独立 `asset` 单向流（`open_uni`）承载，
  避免大封面块在 control 面对 `Play`/`Pause`/`Seek` 造成队头阻塞；
- 播放反馈走 `feedback` datagram（latest-state，可丢）；
- **双向控制**：renderer / controller 经 `control`（bidirectional）把
  `Play` / `Pause` / `Seek` / `Stop` 高优路由回 source；source 汇聚为组状态后广播给所有 renderer；
- 同步起播使用 fabric **SessionClock（GroupInstant）+ Barrier**（Prepare/Ready/Commit），
  Ability 只做 `group_to_local(activate_at)` 后在本地 resume；多 renderer **不得**退化为”数据一到就播”。
  仅当**单 renderer / 本地播放**时可跳过 Ready 共识等待走快速起播（无跨设备 quorum 需求）。

平台 IPC 仍可能用 pipe fd 承载 stream 字节（Android `openStream` 双 fd），但那是
Hub 本地数据面实现细节；**Ability 与 foundation.audio 的语义是 byte stream**，不是
“远程文件描述符播放”。

### 多播与流量

逻辑拓扑始终是一个 source port → 多个 renderer ports。物理投递由 Session 路由策略选择：

- `PerPeerUnicast`（默认）：source 上行 ≈ N × media，独立拥塞与 E2EE；
- `RelayTree`（AllowRelay）：source 上行 ≈ 1 × media，由 hub/peer 转发不透明字节；
- `Multicast`（AllowMulticast）：介质上约一份 media 拷贝，需组密钥/epoch/重放保护。

N 较大时应优先 relay/multicast；资产按 hash 按需拉取，避免每台设备重复拉封面。

### 与 `foundation.audio` 的边界

```text
hub
  不依赖 foundation.audio

ability-audio-render protocol engine
  依赖 sdk
  处理会话、媒体包、jitter、clock mapping、barrier、反馈

foundation.audio adapter
  依赖现有 foundation.audio
  把协议引擎输出的本地播放任务交给音频核心
  把本地播放位置/输出状态反馈给协议引擎
```

当前 `foundation.audio` 的 adapter 合约位于
`E:\mocha\foundation\audio\crates\fabric-adapter`。它定义：

- `com.mocharealm.foundation.audio.render` renderer ability；
- `media` stream 和 `control` / `feedback` 端口常量；
- renderer、source、controller 三种角色；
- renderer 控制命令、播放反馈 JSON；
- 本机 renderer 将 inbound media fd 交给 `PlaybackEngine`；
- remote controller/source 通过平台 adapter 调用 Hub 的 `invoke/openStream`。

框架只提供 GroupInstant 和 Barrier。以下属于音频 Ability：

- codec/PCM 格式；
- 媒体 sample timestamp；
- jitter buffer；
- 输出设备延迟；
- 漂移重采样；
- late join；
- underrun；
- foundation.audio API 适配。

### 多播

默认使用 per-peer QUIC Datagram fanout。逻辑上仍是一个 source port 到多个 renderer ports。未来使用 relay/multicast 时不得改变 Ability 线语义。

## 6. 视频播放

与音频相同，但 Ability 自己实现关键帧、依赖帧、码率适配、渲染队列和 A/V sync。可以在同一 Session 中创建 audio media Channel、video media Channel 和 control Channel。

## 7. 跨设备播放已有本地文件

可以组合两个 Ability，不应让框架加入“播放文件”特殊操作：

```text
file/content access Ability 提供数据源
media/audio render Ability 提供播放端
业务 orchestrator 创建两个 Session 或一个上层复合 Ability
```

## 8. 验证结论

三种 Channel 原语 + Session 图 + Policy + E2EE + Clock + Barrier 足以表达上述能力。若新能力无法表达，优先检查其是否缺少业务协议，而不是立即扩展 Hub 核心。

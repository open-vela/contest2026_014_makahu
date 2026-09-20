# Audio ability boundary

Cross-device audio is **not** a Hub feature. It is an Ability with three ports and a hard split between protocol and local playback.

```text
hub
  no dependency on foundation.audio

ability protocol engine  (fabric-sdk + this contract)
  control   reliable_messages
  media     reliable_stream   (compressed container, progressive)
  feedback  datagram          (latest state only)
  clock     SessionClock / Barrier (GroupInstant)

foundation.audio adapter  (audio/crates/fabric-adapter + engine)
  stream decode via MediaByteSource (no remote fd)
  local play / pause / seek / feedback
  metadata overlay (title, artist, album, cover, lyrics, …)
```

## Media path

1. Source announces `TrackAnnounce` with presentation metadata and content hashes.
2. Cover / long lyrics transfer only on `AssetRequest` (content-addressed).
3. Media is the **original compressed container** (or adaptive segments later), never PCM fanout.
4. Renderer demuxes with FFmpeg custom AVIO over the reliable stream (`open_stream`), not a local fd.
5. Synchronized start uses fabric **GroupInstant + Barrier** semantics: prepare → ready → commit → `group_to_local(activate_at)` → resume.

## Traffic

| Policy | Source uplink | Medium (rough) | When |
|--------|---------------|----------------|------|
| PerPeerUnicast (default) | N × media + asset misses | N × media | 1–2 peers, independent E2EE |
| RelayTree | ~1 × media | tree re-forward | N≥3 and session AllowRelay |
| Multicast | ~1 × media | ~1 × media | N≥3 and session AllowMulticast |

Asset cache hits skip cover/lyrics bytes entirely. Control and feedback stay small; media never rides Binder/datagram payloads.

Implementation: `E:\mocha\foundation\audio\crates\fabric-adapter`.

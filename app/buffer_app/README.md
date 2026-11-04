# Buffer Vela 应用

设备端保留软件录音按钮、离线队列和本地计时，界面沿用 Material 3 的配色与组件层次。启用 `BUFFER_VELA_FABRIC` 时，首次启动展示固定原生 OOBE，经 Fabric 附近设备发现、双端核对码确认与可靠消息应用协议配置。未绑定设备显示公开 Fabric 身份定位二维码；二维码不授予配对权限。配对成功隐藏码和二维码，已绑定设备断线不会重新开放认领。引导覆盖整个主页；配对、联网、模型验证后还须在设备上点击“开始使用”，完成记录成功保存才开放录音及 Agent 处理。旧版“稍后配置”记录不再放行；完成后的普通断网不重复引导。详见 [OOBE 验收](../../docs/finals/06-oobe-acceptance.md)。二维码解析及身份 pin 已有源码与测试，真实相机验收状态见 [运行报告](../../docs/finals/05-runtime-verification.md)。

Agent 使用实际 mbedTLS HTTPS 调用 OpenAI-compatible `mimo-v2.5` 与 `mimo-v2.5-asr`，验证 CA、主机名和证书时间。配置保存在验证过权限的 LittleFS，FAT/HOSTFS 被拒绝。共享文本/语音整理规则，转写检查点、检索/读取实际旧卡片、结构化生成和卡片持久化均在设备工作线程执行；待处理录音不进入收藏。`data/agent` 外置资源仍 disabled，执行器使用有界内置规则。

设备 done/archive/delete 等操作写回 records，重启不会复活。整理后的 records 保留持久 outbox，手机最终事务导入确认后才写 receipt，不重复跑手机 LLM；重复导入不覆盖手机编辑或删除。已接入 BLE 上的手机优先原始文字/WAV 委派、手机全量卡片副本和反向动作回执；导入回执不删除原音频，处理完成副本提交后才确认收尾。屏幕只挑选少量卡片，不限制已同步副本总数。Windows 上已通过真实云端独立转写、分类、计时器及翻译，具体链路证据与未完成项见运行报告；S1 物理蓝牙及私有存储仍需实机验收。

助手工具包含计时、闹钟、翻译、菜谱、常识回答和澄清。普通计时器使用首页默认卡片，菜谱全屏页面可复用相同计时组件；生成菜谱不自动开始计时。工具执行结果记录在设备记录中，助手结果不加入 Buffer 收藏。当前到期提醒通过屏幕显示，尚未承诺声音或深睡唤醒。

睡前背光通过可调 LCD 设备控制。S1 的源码/defconfig 已接入 PD20/PWM4、255 级亮度及 `BUFFER_VELA_NIGHT_DIM`，配套 SDK 补丁见 [0010–0012](../../patches/openvela/README.md)。应用先持久保存原亮度，再降低到当前值的 20%（至少 1）；退出时恢复，检测到其他亮度写入则保留外部设置。设备关闭、能力不支持或保存失败时不擅自开关屏幕。此路径仅完成静态回顾，尚未构建、部署或实测；已有二值亮度配置和旧固件不会因源码修改自动获得调光能力。

`BUFFER_VELA_SOURCE_JOURNAL` 在已接入私有 LittleFS 及文件属性的配置下提供本地源事件、写前恢复和按哈希保留的 WAV 归档，详见 [Vela 本地 journal](../../docs/finals/29-vela-source-journal.md)。它覆盖捕获记录、副本卡片、专注状态、模式及待确认请求，并在 ACK 删除队列前确认归档；新增范围见 [投影 journal](../../docs/finals/30-vela-projection-journal.md)。普通 FAT 配置保持旧路径；全部操作覆盖和完整事件/blob 网络复制仍待完成。本地代码已静态回顾，未构建或运行。

计时器/闹钟、助手原始请求和专注提醒检查点也已接入本地 journal，提交成功后才发布新到期提示；有实体索引的投影可从已提交源事件修复。范围与未完成的长期操作去重见 [提醒源事件](../../docs/finals/31-vela-reminder-journal.md)。这些本地能力不代表源事件网络复制已完成。

启用源 journal 时，助手执行入口还会查询[持久操作历史](../../docs/finals/32-vela-operation-history.md)，支持超过 32 条缓存后的去重、完整请求冲突检查及旧 journal 按需回查；历史请求不会因读取而执行。

[追问与研究会话](../../docs/finals/33-vela-followup-and-research-journal.md)也已接入源 journal。每次追问先固定捕获的原回答，再更新最近八轮展示；研究收到的状态和本机显示到期分别作为观察保存。完整双端复制及全部入口总回顾仍未完成。

[S1 私有存储](../../docs/finals/34-s1-private-storage.md)新增独立 `buffer-private` 工厂配置，使用专用 NAND 分区、Dhara 与 LittleFS。普通启动只恢复和挂载；本地初始化命令只接受全擦除的固定分区。该布局移动 usrdata，不能原地更新旧盘。仅完成源码及静态回顾，现有配置、固件和设备未更新。

[远端源事件存储](../../docs/finals/35-vela-remote-event-store.md)已增加严格 envelope 校验、精确原文导入、持久来源绑定、接收意图恢复与本机逻辑时钟接入。[ID 索引](../../docs/finals/36-remote-event-id-index.md)使用带哈希校验的压缩前缀树，避免每条新事件重扫历史，并为旧历史提供可续跑的有界迁移。

[Vela→Android 源事件拉取](../../docs/finals/37-source-event-pull.md)已接入应用消息 worker、请求 nonce、分片、精确原文导入和持久进度恢复。[Android→Vela 发送](../../docs/finals/38-source-event-push.md)接入反向分片、索引暂忙重试、手机逐事件持久回执和 proof 补记。[Vela 发送侧回执](../../docs/finals/39-vela-delivery-receipts.md)提供按 peer 的持久连续游标。后续 50/51 阶段再接入保留槽与稀疏原文确认；完整父链与业务重放仍未完成。以上均仅静态回顾，未构建或运行。

[Android 因果历史](../../docs/finals/40-source-causal-history.md)增加父引用状态、有界整理及详情中的分页只读历史。[捕获源投影](../../docs/finals/52-android-capture-source-projection.md)进一步消费 Vela 捕获快照，重建手机 Capture/卡片并保护本机编辑和删除；不生成替代源身份的本地事件或历史执行任务。其他类型与 Vela 业务投影仍待完成。

[副本源投影](../../docs/finals/53-android-source-crdt-projection.md)把 Vela 寄存器副本观察接入 Android 同一合并视图，补齐旧删除索引和有界重放；设备副本现保留认证来源，并正确识别寄存器中的空上下文。未知旧 owner 不因部分更新获得转发授权。此阶段仅静态回顾，其他事实适配器与 Vela 远端投影仍待完成。

[来源会话与追问视图](../../docs/finals/54-source-session-and-answer-views.md)进一步在手机读取已保存的专注快照、研究观察与完整追问轮次，保留来源与部分因果状态，提供独立分页历史；它们不重启计时、切换模式、触发历史执行或自动进入转发缓存。其他类型、当前会话协调与 Vela 远端投影继续待办；仅静态回顾，未测试或构建。

[手机完整文档源观察](../../docs/finals/55-android-document-source-observations.md)保存实际卡片/日程寄存器及接收范围，与文档和检查点共同提交；发送前核对目标、上下文来源及当前权限。Vela 可归档原文，远端源文档到设备视图的物化仍未接通。仅静态回顾，未测试或构建。

[父事件定位](../../docs/finals/41-source-parent-discovery.md)允许手机查询当前 Vela 本机可分享事件的位置，持久保留缺口/冲突并触发连续范围刷新；不是跨成员原文转发或完整父链修复。

[Vela blob 服务](../../docs/finals/42-vela-source-blob-service.md)提供绑定源原文的 WAV 归档分片读取和独立持久回执，并已通过 [Android 接收器](../../docs/finals/43-android-source-blob-receiver.md)接入手机私有归档、旧 raw 复用、重连核对和详情状态。源事件或 carrier 回执不代表原件已交付；回执不触发归档删除。

反向由 [Vela 远端接收](../../docs/finals/45-vela-remote-blob-receiver.md)和 [Android 发送器](../../docs/finals/46-android-source-blob-sender.md)衔接：绑定已提交原文及 owner、有界磁盘分片、完整私有归档和独立持久回执；手机持有按 peer 的发送账本并在重连时重新核对。两向网络入口已接入，完整恢复/容量回顾仍待完成；仅静态回顾，未构建或运行验收。

[手机原始音频](../../docs/finals/44-phone-original-audio.md)提供本机 MP4/WAV/不透明原字节描述符、明确来源标记、后台归档与旧文件的当前观察；反向发送只选择已归档且同一对端已确认源事件的本机原件，不自动将来源未核实的导入音频变为本机原件。

[Android 保留槽存储](../../docs/finals/47-android-visibility-storage.md)与 [Vela 保留槽存储](../../docs/finals/48-vela-visibility-storage.md)已分开原文进度和覆盖位置。Vela 保留旧原文前缀，增加持久槽、填槽意图恢复及共同索引计数；槽不当作原文、逻辑时钟或执行任务。这些阶段只做静态回顾，未构建或运行。

[Vela 可见性发送账本](../../docs/finals/49-vela-visibility-sender.md)提供按 peer 的随机 token、独立槽/稀疏原文确认和有界前缀恢复；历史回执不代表对端当前文件健康，不删除原件。[可见性拉取](../../docs/finals/50-visible-source-pull.md)和 [反向发送](../../docs/finals/51-visible-source-push.md)已接入两向显式协商与轮换填槽，允许受限位置后的公开原文继续交付。槽不携带内容指纹，也不代表原文或 blob 已送达；已保存槽的流不降级为旧协议。仅静态回顾，未测试、构建或运行；完整业务重放与多成员尚未完成。

## 构建

在 openvela 工作区根目录执行：

```sh
cd /home/simon/vela
env CROSSDEV=arm-none-eabi- \
  PATH="/home/simon/vela/prebuilts/gcc/linux-x86_64/arm-none-eabi/bin:$PATH" \
  ./build.sh vendor/allwinnertech/boards/r528/r528s3-gemini-s1/configs/nsh_minidisplay -j4
```

产物为 `/home/simon/vela/nuttx/vela.bin`，Gemini S1 的打包流程同时会更新：
`vendor/allwinnertech/lichee/board/r528s3/gemini-s1_nand/configs/nsh.fex`。

`nsh_minidisplay` 已启用本应用。系统启动脚本会自动运行 `buffer_app`，因此配对服务和屏幕不需要手动启动；调试时也可以在 NSH 中直接执行 `buffer_app`。

## 旧版 LAN 模式：首次连接 Wi-Fi

Vela 和 Android 必须连接同一个局域网。首次烧录后通过 Gemini S1 的 NSH/串口执行：

```sh
buffer_app wifi "你的 Wi-Fi 名称" "你的 Wi-Fi 密码"
```

该命令会保存 `/data/etc/wifi/wapi.conf`，拉起 `wlan0`，执行 WPA2 关联和 DHCP。重启后，板级 `/etc/wifi/start_wifi.sh` 会自动读取这个配置并重连。

检查网络：

```sh
ifconfig wlan0
wapi show wlan0
```

如果已经通过其他方式写入了 `/data/etc/wifi/wapi.conf`，也可以直接重启，让板级启动脚本连接 Wi-Fi。

## 旧版 LAN 模式：配对 Gemini S1

1. 确认 Vela 已连接 Wi-Fi，并保持屏幕上的 `配对码` 可见。
2. 在 Android Buffer 应用中授予麦克风和本地网络权限，并启动桥接服务。
3. 打开“Vela 配对”，点击“扫描 Vela”。
4. 输入 Gemini S1 屏幕显示的 6 位配对码，点击对应设备的“配对”。

配对握手使用 TCP `48887`，发现使用 UDP `48886`，录音和卡片同步使用已认证的 TCP `48888`。配对成功后，Vela 将 Android 地址、手机设备 ID 和数据令牌保存到 `/data/buffer/phone.conf`；后续数据连接使用手机下发的随机挑战和 HMAC-SHA256 应答，令牌不会出现在普通 hello 消息中。同步连续失败时，Vela 会先向局域网重新发现同一部手机并更新 DHCP 地址，再尝试重新关联 Wi-Fi。

查看 Vela 身份和端口：

```sh
buffer_app pairing
```

## 录音和同步

按住 Gemini S1 屏幕上的录音按钮开始录音，松开后保存到 `/data/buffer/queue`。收到 Android 对同一个 `capture_id` 的确认前，WAV 不会删除。

录音界面不再让用户切换“记录/助手”；按住/松开创建 `target=auto` 的新 capture，由 Agent 按语义选择 Buffer 分类或设备助手，并由 coordinator 按成员资格和任务能力选择执行位置。具备资格的手机 coordinator 优先处理 Vela 输入；手机不可用时，已就绪的 Vela 可接管本机队列。没有可用执行者时，原始 capture 留在待处理队列。原始输入分片现在带有可选 `target`：`auto` 才允许手机提供 Vela 助手分流并在交接后移除待处理占位项；显式 `buffer` 保持 Buffer-only，旧发送方缺省 target 时按 `buffer` 处理。旧 `target=assistant` 继续留在 Vela 本机助手路径，不进入手机原始输入委派。接收端持久化 target，capture 重放时 target 不一致会冲突。协议兼容性已有测试覆盖，Windows Android↔Vela 实际助手交接与记录分支均已验证，详见[自动意图说明](../../docs/finals/07-auto-intent.md)。`buffer_store_capture_target(capture_id, out, size)` 返回原始 target 字面值；兼容接口 `buffer_store_capture_is_assistant(capture_id)` 对旧助手记录返回 `1`，对 `auto`、`buffer` 和旧无 target 记录返回 `0`，损坏或不可读时返回负 errno。

```sh
buffer_app sync
buffer_app record 3000
buffer_app mode focus
buffer_app stop
```

默认按键设备为 `/dev/input/event1` 的第 0 个按键位；单条录音最长 8 秒，离线队列最多 8 条。卡片断网时继续显示本地缓存，并标记为 stale，网络恢复后由后台线程刷新。

专注模式的本地计时由常驻线程每 100 ms 检查一次，不依赖手机在线；连续专注达到 50 分钟后显示喝水、看远处和活动肩颈提醒。模式同步时，Vela 只接收当前模式适合的卡片，睡前模式会排除兔子洞卡片。

专注同步的 `focus_reminder.notice_remaining_ms` 表示本次提示剩余显示时间（0–15000 ms）。手机先触发时 Vela 按新 revision 补显，重复同步不延长已显示提示；缺少字段时按 0 处理。下一次提醒仍由 `remaining_ms` 校准。

### 旧版 LAN 模式：手机令牌持有证明

当前握手要求配套更新 Android 与 Vela。Vela 在 hello 加入 32 字节随机数的小写十六进制 `client_nonce`；手机的 hello_challenge 返回自己的 `nonce`、`device_id` 和 `phone_proof`。证明是以配对令牌 UTF-8 字节为密钥，对以下 UTF-8 文本执行 HMAC-SHA256，输出 64 字符小写十六进制：

```text
buffer-phone-v1:<client_nonce>:<nonce>:<vela_device_id>:<phone_device_id>
```

Vela 校验固定配对手机 ID 和证明后才发送 hello_proof。缺少手机证明不回退到旧握手。这只增加双方令牌持有验证：配对和后续业务消息仍是明文，尚无逐条认证或加密，不能抵御已窃取配对令牌者或业务流量篡改。


## Windows 模拟器与设备诊断

隔离构建、LittleFS 镜像和 Windows 可见窗口入口见 `../../tools/README.md`。生产构建默认内置公共 Mozilla CA；测试 CA 只能在独立构建配置指定，不在手机协议中变更。Windows 私有数据盘独立于原 FAT 盘，不自动格式化。

`buffer_app agent storage-check` 实测目录0700、文件0600、原子写入和读回；`agent status` 显示配置及模型验证结果。`agent configure-file <本地JSON>` 为本地管理员诊断，不替代 Fabric 配对授权。`agent text <id> <text>` 将文本送入真实持久队列。不要使用个人音频或未知云端凭据做测试。Goldfish eth0 使用板级真实以太网配置，不接受 SSID/密码并虚报配网成功。中文字体部署依赖见 `../../data/fonts/README.md`。

### CRDT 兼容发送的来源与隐私门禁

`buffer.crdt.part` 与源事件发送共用敏感上下文检查，副本 origin 必须匹配实际认证 peer；非空捕获上下文继续限制转发。分片缓存和在途 ACK 绑定 peer/连接代次，最终投递再次复核；持久交付证明绑定 peer、card ID 和完整记录 SHA，旧 hash-only 回执不再直接复用。分片按实际协商预算缩小。实现与静态回顾见 [56 转发门禁](../../docs/finals/56-vela-crdt-forwarding-policy.md)。未运行测试或构建，Vela 远端源文档物化仍待完成。

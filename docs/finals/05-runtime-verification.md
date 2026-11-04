# Windows 双模拟器运行验收

日期：2026-10-17。本文记录实际执行结果；待验收项不视为已完成。

环境为 Windows 原生可见 Android 17 模拟器 `emulator-5582` 与 openvela ARM64 模拟器 `5584`。Linux/WSL 仅编译和运行宿主测试，没有启动模拟器。固件更新保留原数据盘、Fabric 身份、配对和模型配置。

## 已验证

| 功能 | 证据及范围 |
| --- | --- |
| BLE 配对与恢复 | Windows 两端完成真实 Fabric 身份交换、核对码确认、持久化绑定及认证应用消息；重启 Vela 后无需删除绑定重新配对。 |
| OOBE 模型初始化 | 手机通过已认证应用通道传递配置；Vela 实际验证 LLM/ASR 后报告 `configured=1 ready=1`。配置不出现在发现包或截图。 |
| Vela 独立 Buffer | 手机 App 与 Hub 停止时，公开测试文字 `fin-1017` 和合成语音 `fin-voice` 经真实云端转写/分类；手机恢复后导入相同 capture，保持稳定卡片 ID。 |
| 手机优先应用选举 | 运行时观察到手机优先取得所有权；Vela 失去手机心跳后自行接管。应用 term 与 Fabric Session epoch 分开；这不是通用多成员 Session 选举的验收。 |
| 全量卡片副本 | Vela 保存 8 张手机卡片副本，屏幕只挑选 5 张；副本存储与屏幕投影分开。宿主测试另覆盖分页、修改、删除墓碑及旧结果拒绝。 |
| 原始输入导入回执 | 历史录音 `vela-247143-61423847` 的最终导入回执已实际写入 LittleFS。导入确认不立即删除源录音，处理完成副本才确认收尾。 |
| 手机端原始语音及失败恢复 | 公开合成录音 `ph39`（WAV 77,930 B）已从 Vela 导入手机；手机 DB `blob_path` 非空，实际转写 29 字符，`classification_jobs` 为 `complete`、`attempts=0`。对应处理完成副本已回传，Vela 的 WAV 与 `.meta` 已清理。较短公开录音 `pf17`（44,694 B）手机 DB `blob_path` 非空，转写 17 字符，分类 `complete`、`attempts=1`；首次 LLM malformed JSON 被拒绝，自动重试后成功。对应处理完成副本已回传，Vela 的 WAV 与 `.meta` 已清理；整段闭环耗时约 6 分钟，不代表低延迟。此项不代表物理麦克风音质验收。 |
| 新一轮手机语音导入与失败恢复 | 新 capture `pfio`（44,694 B，与 `pf17` 为相同音频）于 23:42:54.367 发布。约 20 秒时手机 DB `blob_path` 已非空、转写 17 字符；首次分类因 malformed 进入 pending，`attempts=1`。约 71 秒时分类 `complete`、`attempts=1`，表示从发布到分类完成的整段流程，包含手机传输、ASR、LLM 自动重试及分类，不是纯 BLE 耗时。23:44:25 后 Vela queue 已无 `pfio.wav` / `.meta`，处理结果已回传并清理；queue 中仅保留原有 320 ms 失败录音。 |
| 本地计时器 | 公开请求 `t22` 经真实云端 `device_timer` 工具创建 10 秒计时器。到期首页提示持续可见，点击可打开默认卡片；没有新增 Buffer 收藏。 |
| 本地闹钟 | 真实云端 `device_alarm` 创建成功并到期；`alarm-expiry2.png` 显示首页顶部持续提示，默认卡片状态为“闹钟已触发”。Windows 实际触摸首页入口（27080）后，`alarm-dismiss-before.png` 显示“关闭提醒”；触摸关闭按钮（70245）后，`alarm-dismiss-after.png` 显示顶部到期提示消失、卡片为“已取消/提醒已关闭”。domain/model 宿主回归也验证 FIRED→CANCELLED 与重复关闭幂等。当前启动参数关闭宿主音频，不据此声称声音提醒通过。 |
| 翻译全屏页 | 公开请求 `tr22` 经真实云端 `show_translation` 工具成功，显示原文 hello、译文你好及语言；设备记录含成功工具回执。 |
| 菜谱与步骤计时 | 公开请求 `rc43` 实际调用 `show_recipe`，全屏呈现热茶步骤；点击“开始这一步的计时”后出现 3 分钟嵌入计时器，结束按钮可取消，生成菜谱本身不启动计时。 |
| 图片二维码识别 | Windows Android 的 CameraService 报告 0 个相机；应用显示原生风格降级提示。通过系统 PhotoPicker 选择实际 Vela 公钥定位二维码后，应用显示“二维码已识别”并进入附近发现。没有以此冒充真实相机或首次 SAS 配对验收。 |
| Android 源码 SDK 发布检查 | `android-packaged-source-final.log` 记录 291 项单测、assemble、lint 均通过；最终源码 SDK 默认 APK 已打包。 |
| Android 任务提交保护 | 单元测试覆盖接管导致 Fence 失效后拒绝旧分类结果、仅归还自己的数据库任务租约，避免旧 worker 清除新租约；本次 291 项测试通过。 |
| Hub 生产选举 | 生产 Hub 完成 3 ABI 构建并于 23:10 安装；该证据不扩展为 Vela 便携 BLE 支持通用多成员 Session 选举。 |
| Vela 运行版本 | parent=13980 已安装，包含队列公平性与 admission cache；队列公平性宿主回归 1 项通过。 |
| UI 首帧恢复 | Windows 安装 SDK direct-attribute 修复后的 parent=3888。23:42:04 启动，13 秒出现正常首帧；原卡片、配置和闹钟状态保留。短语音传输和转写时延见 `pfio` 实测；LittleFS file-handle 属性回归已在 ASan/UBSan、`LFS_THREADSAFE` 下通过。 |
| A2UI 子集 | 锁定官方 v0.9.1 schema、catalog、LICENSE 和 SHA-256；官方样例校验及生产 C parser/model 回归通过。不宣称支持完整官方协议。 |
| 助手模型恢复与 target 隔离 | 生产 mbedTLS HTTPS fixture 脚本通过：工具成功后 malformed continuation 和真实读超时重试均恢复原输出/receipt 且 timer 只创建一次；一批工具只保留首个成功页面；Buffer target 越权 device tool 返回明确错误、不执行设备副作用且继续分类。domain/A2UI 宿主测试 1 项通过，覆盖闹钟到期关闭状态。production HTTPS 全量新回归见 `assistant-checkpoint-main-regression.log`。 |

## 本轮修复的实际故障

1. 蓝牙恢复路径在默认约 8 KiB 线程栈上使用约 12 KiB OOBE 解码缓冲区，触发 KASAN。解码缓冲改为按消息长度分配并在释放前清零。
2. LittleFS 目录分裂后，尾部元数据块没有直接父目录名称。原路径反查返回 ENOENT，使 `fchmod` 失败并导致录音末片重复发送。补丁沿前驱找到目录头；真实 LittleFS 回归在第 21 个文件复现旧错误，修复后 100 个文件及重新挂载校验通过。
3. LittleFS 的 `fstat` / `fchstat` 在取得文件系统锁前读取共享目录和 open-file 状态。现在路径反查与属性访问使用同一把锁。补丁位置和应用顺序见 [工具说明](../../tools/README.md)。
4. 部分模型回复将 JSON 再编码进 `assistant_response`，UI 显示协议原文。设备仅归一化有界、符合已知字段的嵌套回复；助手首轮要求调用实际工具，问答和澄清分别使用 `show_answer` / `ask_user`，并保存执行回执。
5. BLE 队列中的旧重传首片晚到后，新等长报文被误判为片段冲突并关闭连接。现在不同前缀的 offset 0 开始新重组；最终 AEAD、重放检查和应用授权仍执行，非零位置冲突仍拒绝。实际 C 加密会话回归已复现旧错误并验证修复。
6. Vela 重新选举后从 offset 0 重发，而手机仍保留已收到的前缀；原发送端拒绝大于当前片末的累计 ACK。现在接受同 capture/hash、范围内且在合法分片边界的累计进度，最后导入确认与处理完成确认仍分开。生产 C 回归覆盖恢复和非法偏移拒绝。
7. 自动队列过去总选择目录首条记录，永久失败录音可饿死后续任务。已加入有界退避和公平轮询；`pytest` 的队列公平性生产回归 1 项通过，parent=13980 已安装。
8. 助手工具副作用成功后，后续模型响应若超时或畸形，旧实现会丢失本轮内存中的 receipt。现在先原子保存每 capture 的工具调用、真实输出和 receipt；重试恢复并复用原结果。生产 HTTPS fixture 分别注入真实读超时和 malformed-followup，验证重试后仍有成功 receipt 且 timer 未重复创建。
9. Buffer 与 assistant 工具按 target 分别公布；执行时再次检查，Buffer 越权设备工具只向模型返回 `tool_not_allowed_for_target`。同一 assistant capture 的后续成功页面请求返回 `surface_already_presented`，不替换当前页面。单批 competing surface 与越权调用均由生产 HTTPS fixture 覆盖。

## 传输与界面响应补充检查

- 已确认空发送队列曾在每次无线轮询时重复遍历记录并读取/计算哈希。现在仅空队列的文件发现扫描限频为每秒一次，已有发送快照和合法 ACK 仍立即推进；生产 HTTPS 回归覆盖新记录在限定窗口内重新进入发送队列。
- 收包和通知完成现在唤醒独立的无线 condition，空闲时保留 50 ms 单调时钟等待以驱动协议超时。
- Windows 实测发现 UI 首帧等待 `buffer_fabric_snapshot` 获取 BLE executor 状态；executor 在扫描空 outbox 时持有 FS mutex，`agent` 的 `fstat` 又经 `lfs_file_path` 做全 FS 路径反查，放大等待。主代理将 snapshot 改为独立发布的 UI `status_view`；SDK 通过 LittleFS file handle 的 `lfs_file_getattr/setattr` 读取/写入 metadata，避免全 FS 路径反查。parent=3888 已安装 direct-attribute 改动，23:42:04 启动后 13 秒出现正常首帧，原卡片、配置及闹钟保留。LittleFS 真实 RAM 闪存回归已覆盖目录分裂、重命名、删除和重新挂载，启用 ASan/UBSan 与 `LFS_THREADSAFE` 后通过；`pfio` 实测约 20 秒内完成传输和手机转写。

## 最终构建与剩余验收边界

- 最新 native final build 已通过，确认 target guard 与工具检查点均已编入。生产 HTTPS 全量新回归 `assistant-checkpoint-main-regression.log` 通过。
- UI 首帧、闹钟关闭及 `pfio` 语音闭环已完成，SDK file-handle 属性回归通过。最终固件 `final-sdk-ui-build.log` 构建成功并于 23:47 安装到 Windows Vela，保留原数据盘；新增缺失属性清零、属性长度上限校验及可读的错误提示。
- 工具成功后的持久化回执、malformed/真实读超时 continuation 恢复、单 capture 展示限制、target 隔离及 Buffer 越权后继续分类的生产 HTTPS 宿主回归均已通过。闹钟 FIRED→CANCELLED domain/model 回归及 Windows 实际点击关闭均通过；前后截图为 `alarm-dismiss-before.png`、`alarm-dismiss-after.png`。
- 二维码相机路径因 AVD CameraService 无 camera device 未验收；Photo Picker 从真实图片识别 locator 已通过。首次 BLE/SAS 配对仍未由该次图片识别证明。
- 便携 BLE 仍限定两成员、单通道、初始 epoch 1，不宣称 Vela 便携端已经运行通用多成员会话。Hub 生产选举的 3 ABI 构建和安装已完成。

Gemini S1 刷写、物理蓝牙射频、Wi-Fi 硬件、麦克风品质、声音提醒、深睡唤醒及掉电长时恢复没有由本次模拟器验收证明。当前模拟器启动参数关闭宿主音频，因此计时器/闹钟只验收可见到期状态；FIRED 闹钟关闭已通过 domain/model 宿主回归和 Windows 实际点击。

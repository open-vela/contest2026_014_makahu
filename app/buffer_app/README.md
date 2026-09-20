# Buffer Vela 应用

这是 Gemini S1 上的 Buffer 设备端应用，提供屏幕长按录音、离线队列、卡片展示和局域网同步。Vela 是配对主体：它自己生成并保存 6 位配对码，未配对时屏幕显示配对码，成功配对后隐藏，临时离线不会再次显示；Android 端扫描局域网中的 Vela，输入屏幕上的配对码后完成握手。

Android 已实现 BLE 扫描、GATT 配网请求与业务认证；设备端提供配置服务并在后台连接 Wi-Fi。完整物理链路仍需实机验收。模拟器下使用可达的局域网及配对入口；当前源码位置见仓库根 README。

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

## 首次连接 Wi-Fi

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

## 配对 Gemini S1

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

```sh
buffer_app sync
buffer_app record 3000
buffer_app mode focus
buffer_app stop
```

默认按键设备为 `/dev/input/event1` 的第 0 个按键位；单条录音最长 8 秒，离线队列最多 8 条。卡片断网时继续显示本地缓存，并标记为 stale，网络恢复后由后台线程刷新。

专注模式的本地计时由常驻线程每 100 ms 检查一次，不依赖手机在线；连续专注达到 50 分钟后显示喝水、看远处和活动肩颈提醒。模式同步时，Vela 只接收当前模式适合的卡片，睡前模式会排除兔子洞卡片。

专注同步的 `focus_reminder.notice_remaining_ms` 表示本次提示剩余显示时间（0–15000 ms）。手机先触发时 Vela 按新 revision 补显，重复同步不延长已显示提示；缺少字段时按 0 处理。下一次提醒仍由 `remaining_ms` 校准。

### 手机令牌持有证明

当前握手要求配套更新 Android 与 Vela。Vela 在 hello 加入 32 字节随机数的小写十六进制 `client_nonce`；手机的 hello_challenge 返回自己的 `nonce`、`device_id` 和 `phone_proof`。证明是以配对令牌 UTF-8 字节为密钥，对以下 UTF-8 文本执行 HMAC-SHA256，输出 64 字符小写十六进制：

```text
buffer-phone-v1:<client_nonce>:<nonce>:<vela_device_id>:<phone_device_id>
```

Vela 校验固定配对手机 ID 和证明后才发送 hello_proof。缺少手机证明不回退到旧握手。这只增加双方令牌持有验证：配对和后续业务消息仍是明文，尚无逐条认证或加密，不能抵御已窃取配对令牌者或业务流量篡改。

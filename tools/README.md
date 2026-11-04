# 隔离构建 Vela 模拟器

`build_vela_sim_isolated.py` 为本项目的 Goldfish ARM64 配置创建独立源码视图和 CMake 输出，不切换 SDK 正在使用的 `.config`。先退出使用该输出目录的模拟器，再运行构建。

```sh
python3 tools/build_vela_sim_isolated.py --sdk /home/simon/vela --root /tmp/buffer-simulator -j 8
```

需要 SDK 交叉编译工具、CMake、Make、genromfs、dosfstools、mtools，以及 Python 的 `pyelftools`、`cxxfilt`、`kconfiglib`。Python 依赖可安装在输出根目录的 `host-venv`；私有主机工具也可放在 `host-tools/usr/bin` 和 `host-tools/usr/sbin`，脚本会优先使用这些位置。`--prepare-only` 仅准备源码视图。

启用 Fabric 时，先从参赛源码快照构建便携静态库：

```sh
rustup toolchain install 1.94.1 --profile minimal
rustup target add --toolchain 1.94.1 aarch64-unknown-none
cargo +1.94.1 build --locked --release --no-default-features --target aarch64-unknown-none \
  --manifest-path foundation.fabric/platform/portable/Cargo.toml
```

默认 `BUFFER_VELA_FABRIC_ROOT` 为空，Make/CMake 自动使用同一参赛目录的快照，不依赖开发机绝对路径。只有在独立框架仓库联调时才显式设置此覆盖项；框架的开发位置仍是 `/home/simon/projects/mocha/foundation.fabric`，快照仅用于打包和独立构建。

以上命令用于 ARM64 模拟器。S1 Cortex-A7 使用 `armv7a-none-eabihf`（`ARCH_FPU=y` 且未启用 `ARM_FPU_ABI_SOFT`），软浮点调用约定使用 `armv7a-none-eabi`；安装及构建命令的 target 必须一并替换。Make/CMake 按板级配置选择对应库目录，`BUFFER_VELA_FABRIC_TARGET` 可显式覆盖。不自动运行 Cargo，也不以 ARM64 库代替缺失的 S1 库。

脚本处理同一 SDK 中 Make/CMake 混用造成的生成文件冲突：隔离头文件和应用注册表，分开 QuickJS 主机工具输出，优先选择与 SDK Binder 头文件匹配的 AIDL。保留 LVGL 旧文件系统盘符与 Zephyr 已弃用函数的编译警告，但让这两项既有兼容性警告不阻断构建；Zephyr 的处理限定于其目标。

镜像位于 `ROOT/build/vela_ap.bin`、`vela_ap.elf`、`vela_system.bin` 和 `vela_data.bin`。默认保留已有数据盘，避免上游 `gen_images` 在增量构建时清除配对和配置；只有明确传入 `--reset-data` 才重新生成数据盘。保留数据盘不代表改变其文件系统或凭据保护能力，应用仍须验证实际存储后端。

运行验收仅使用 Windows 原生可见模拟器；Linux/WSL 只构建及运行 host 单元测试。复用 Windows 协作目录中的 openvela emulator，不覆盖已有 Android AVD 或用户数据盘。启动示例（PowerShell）：

```powershell
& '\\wsl.localhost\Ubuntu\home\simon\vela\contest2026_014_makahu\tools\start_vela_windows.ps1' -ProductRoot 'D:\buffer-vela-agent-oobe-20261016' -Ble
```

默认 Vela5584、NSH4444、QMP45584；Android5582 由框架负责人管理。`-Ble` 需要 Android netsim 已提供原始 H4 TCP6402，Vela 使用独立控制器和 virtio bus6。固件必须启用 `BLUETOOTH_IPC_JOIN_LOOP`，否则 socket IPC 的 bluetoothd 会在初始化后退出；应用在配对线程启用 BLE 并等待真实适配器就绪。端口存在或 Reset 探针成功不能代替 GATT/应用消息验收。`capture_vela_windows.ps1` 从实际5584 renderer截屏。启动器拒绝更新运行中的同一个 product，`-UpdateFirmware` 只替换固件，不替换已存在的任何数据盘。

Goldfish 配置还关闭 `BT_HCI_ACL_FLOW_CONTROL`。当前 Windows netsim 的控制器流控命令兼容性会导致初始化过程中控制器退出，继而使设备 HCI 同步命令超时；模拟器使用默认不节流的 ACL 接收路径。此设置只属于 Goldfish，不修改 S1 配置。2026-10-17 已在 Windows Android5582 与 Vela5584 上通过实际 GATT 连接、MTU 协商、64 字节身份读取和 CCC 通知订阅测试；这仍不等同于 Fabric 配对、应用配置交换和完整 OOBE 验收。

当前 SDK 的蓝牙 HCI 初始化失败后可能在 cleanup 解引用空指针。仓库保存了针对 SDK `frameworks/connectivity/bluetooth` 仓库的补丁，应用前先检查是否需要：

```sh
git -C /home/simon/vela/frameworks/connectivity/bluetooth apply --check /home/simon/vela/contest2026_014_makahu/patches/openvela/0001-bluetooth-hci-cleanup-before-init.patch
git -C /home/simon/vela/frameworks/connectivity/bluetooth apply /home/simon/vela/contest2026_014_makahu/patches/openvela/0001-bluetooth-hci-cleanup-before-init.patch
```

该补丁只让未初始化的 HCI 能安全退出；不提供虚拟蓝牙控制器或完整射频适配。模拟器界面验证与手机/Vela 的真实蓝牙联调应分别记录。

蓝牙服务断开时，SDK socket 客户端的监听对象可能已释放，但实例仍保存其指针；随后销毁实例会再次访问该对象，触发 KASAN。`patches/openvela/0002-bluetooth-client-clear-closed-poll.patch` 修正 EOF、断开、无效句柄和读取错误路径，关闭监听时清空实例引用。对同一个 SDK 仓库先执行 `git apply --check` 再应用；若已包含修改，可用 `git apply --reverse --check` 核验。

`python3 tests/test_sdk_bluetooth_disconnect.py` 从实际 SDK 提取相关生产函数，在 ASan/UBSan 下验证断开后销毁实例不会重复访问监听对象；SDK 不在项目上一级时设置 `OPENVELA_SDK`。它验证客户端内存安全，不代表蓝牙连接或配对成功。

`0003-bluetooth-gatt-owned-data.patch` 修正 SDK GATT 表的所有权：普通特征值引用调用方的 element，不得释放；复制的数据和显式移交的 CCC 对象由表释放。删除服务和移动剩余属性时同步维护所有权。`python3 tests/test_sdk_gatt_lifetime.py` 覆盖混合所有权、服务删除和表压缩的 30 轮 ASan/UBSan 回归。应用在正常断线后保留 GATT 服务，仅重新启动广播；`test_ble_discovery.py` 验证此时不销毁客户端实例。Windows 上还应重复连接、断开并重新发现设备，不能只跑一次 GATT 测试。

## 模拟器私有持久存储

LittleFS 的路径反查还需要 `0004-littlefs-split-directory-path.patch`：在 SDK 独立仓库 `nuttx/fs/littlefs/littlefs` 中先用 `git apply --check` 检查，再应用此补丁。目录填满并分裂后，原实现不能从尾部元数据块找到目录名称，导致已有文件的 `fstat` / `fchmod` 返回 ENOENT，设备会不断重发最后一个录音片段。补丁沿分裂目录的前驱找到目录头，再保留原文件 ID 查找文件名；不格式化或迁移磁盘。`python3 -m pytest tests/test_sdk_littlefs.py -k split-directory-path` 用实际 LittleFS 创建 100 个带属性的录音回执文件，并在重新挂载后校验路径，运行 ASan/UBSan。对于没有独立 LittleFS 源码仓库、由 NuttX 下载源码的构建方式，另在 `nuttx` 仓库应用 `0005-littlefs-download-path.patch`，使下载时生成相同修复。

在 `nuttx` 仓库还应应用 `0006-littlefs-serialize-file-metadata.patch`，使 `fstat` / `fchstat` 的路径反查和属性访问共同持有文件系统锁，避免与创建、重命名并发时读到不一致的目录状态。它与 `0004` 修复的是不同问题。

ARM64 semihost HOSTFS 的 `stat` 固定返回 `0777`，`fchstat` 不支持，不能用于 Agent 凭据或 Fabric 身份存储。宿主目录 `0700` 不代表 guest 可验证权限。默认 FATFS 同样忽略 chmod；两者均由应用拒绝。

为模拟器创建**另一块** LittleFS 磁盘，原 `vela_data.bin` 保持原样。生成器使用 SDK 的 LittleFS，并通过 `O_EXCL` 拒绝覆盖已有路径：

```sh
python3 app/buffer_app/make_private_image.py /tmp/buffer-private-littlefs.bin
python3 tools/build_vela_sim_isolated.py --root /tmp/buffer-private-root --refresh-config -j 8
# 然后在 Windows PowerShell 使用上面的 start_vela_windows.ps1。
# 私有盘首次复制，已有 D 盘状态不覆盖；保持可见窗口。
```

Goldfish 配置启用 LittleFS 属性和 `BLOCK_SIZE_FACTOR=8`，匹配 512 字节扇区、4096 字节逻辑块、2048 字节读写/缓存。项目 `board/vela_sim/etc/init.d/rc.sysinit` 优先尝试挂载 `/dev/virtblk2` 为 `/data`；不格式化任何盘。无专用盘或挂载失败时保留原 FAT 行为，Agent 明确报告 `storage_unavailable`。NuttX 不支持在已挂载 FAT 的子目录上叠挂，因此不能挂到 `/data/buffer`。

`--refresh-config` 只备份并重新生成指定 scratch 目录的 `.config`，不修改 SDK 活动配置。NuttX CMake 默认不会因 defconfig 内容变化刷新已有配置，新增配置验收必须显式使用该选项。`--board-config DIR` 可选择另一个包含 defconfig 的本地测试配置；生产构建保持 CA 验证，不使用测试 CA。

在 NSH 运行 `buffer_app agent storage-check`，应显示 `private_storage=verified error=0`。该命令实际验证目录 `0700`、临时和最终文件 `0600`，执行原子写入、同步和读回。配置保存后应再冷启动读取。LittleFS 同步 rename 提交元数据，底层 virtio 实现 `BIOC_FLUSH`；不通过忽略权限/fsync 错误放行。实机 S1 仍需单独的持久 LittleFS 分区及块驱动验收。

中文字体需部署到**新 LittleFS 数据盘**的 `/data/fonts/MiSans-Regular.ttf`。可用独立 HOSTFS 挂载作为非秘密资源源，然后 NSH `cp`；HOSTFS 不承载凭据。资源来源和部署脚本见 `data/fonts/README.md`。

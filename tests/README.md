# 测试入口

从项目根目录运行主机回归：

```sh
python3 -m pip install -r tests/requirements.txt
python3 -m pytest
python3 -m pytest --collect-only -q
python3 -m pytest -m 'onboarding or fabric'
python3 -m pytest -m 'not integration'
python3 -m pytest tests/test_sdk_littlefs.py
```

`pytest.ini` 将默认收集范围限制在 `tests/`，严格检查 mark。原有 36 个 pytest item 保留；M3E 整改新增 2 项，目前共 38 项。传统脚本中的断言计数方式不变；传统脚本中的多条断言仍合计为一个 item，不能把这个数字当作断言数。LittleFS 两个脚本合并为一个参数化模块的两个 item，数量保持不变，原 C fixture 与断言保留。

`conftest.py` 对传统脚本只解析 AST，不在 collection 时导入和执行；运行阶段每个脚本使用独立 Python 子进程。包含顶层 `test_` 函数或 `Test` 类的模块继续使用标准 pytest fixture、参数化和报告。传统脚本仍可用 `python3 tests/test_*.py` 单独运行；LittleFS 使用上面的 pytest 命令。不得用 collection 成功代替实际执行成功。

| mark | 范围 |
| --- | --- |
| `core` | 卡片缓存、连接超时、触摸录音 |
| `agent` | Agent 调度、capture 路由、同步状态、熵源 |
| `fabric` | Fabric 协议、runtime、协调者与准入 |
| `onboarding` | BLE、配对、发现、enrollment、OOBE |
| `rhythm` | focus 与 research |
| `assistant` | assistant domain、A2UI 与官方 schema |
| `sdk` | SDK 蓝牙生命周期、LittleFS |
| `integration` | `agent_https`，同时属于 `agent` |

## 主机依赖

需要 Python 3.10+、pytest 8/9、jsonschema 4、C11 编译器、pthread、ASan/UBSan、CMake、Make、OpenSSL CLI 和 OpenSSL 开发库（`libcrypto`）。Ubuntu 可安装 `build-essential cmake libssl-dev openssl`；Python 依赖统一在 `requirements.txt`，兼容保留 `requirements-a2ui.txt`。

`host_support.py` 统一项目路径、SDK 路径、`CC` 和 C 函数/结构提取。默认 SDK 位于项目上一级，目录应包含 `apps/netutils/cjson/cJSON`、`apps/crypto/mbedtls/mbedtls`、`frameworks/connectivity/bluetooth` 和 `nuttx/fs/littlefs/littlefs`。位置不同时设置：

```sh
export OPENVELA_SDK=/path/to/openvela
export CC=cc  # 也支持 'ccache cc'
python3 -m pytest
```

SDK 回归需要匹配 `patches/openvela/README.md` 中的源码补丁。缺失依赖、编译错误和断言失败都应作为失败处理，不能靠跳过测试通过。

`agent_https` 编译实际 SDK mbedTLS，运行本地 HTTPS 夹具和临时证书，覆盖 TLS、Agent/store/worker 与协议边界；无需云账号或外部模型调用。首次编译较慢。默认复用 `/tmp/buffer-agent-mbedtls` 构建缓存，可通过 `BUFFER_AGENT_MBEDTLS_BUILD` 指定独立目录；多个完整 suite 不得同时使用相同缓存。其余本机 C harness 使用临时目录。

## Android

在 `foundation.fabric/apps/android` 运行：

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/Android/Sdk
bash ./gradlew :buffer-app:testDebugUnitTest :buffer-app:lintDebug :buffer-app:assembleDebug
```

本机 JDK 为 `/home/simon/.gradle/jdks/jetbrains_s_r_o_-21-amd64-linux.2`，Android SDK 为 `/home/simon/Android/Sdk`。需要可用的 Gradle wrapper、Android SDK 与项目要求的构建组件。JUnit XML 位于 `buffer-app/build/test-results/testDebugUnitTest/`，HTML 位于 `buffer-app/build/reports/tests/testDebugUnitTest/`。上述是 JVM 单元测试、静态检查和打包，不会启动模拟器或安装 APK。

## Framework

项目内 `foundation.fabric/` 是随项目附带的框架快照。在该目录可运行完整 Rust workspace：

```sh
cargo test --workspace --all-features --locked
```

需要 Rust/Cargo（最低版本见 `Cargo.toml`）、本机链接工具和依赖下载缓存。已有完整缓存时可追加 `--offline`，缺失包时离线执行会失败。不要将项目快照的结果当作外部权威仓库的结果。

本机权威仓库为 `/home/simon/projects/mocha/foundation.fabric`。可分别验证与本应用相关的 session、platform 和独立 portable crate：

```sh
cd /home/simon/projects/mocha/foundation.fabric
cargo test -p fabric-session -p fabric-platform --locked --offline
cd platform/portable
cargo test --locked --offline
```

这些结果只覆盖所选 crate，不能表述为整个 workspace 通过。

## 验证边界

主机回归、Android JVM 测试、lint 和打包均不等于实机验收。BLE 射频、麦克风、Wi-Fi、刷机、多端联调及完整 OOBE 需独立验收。测试整理不需要启动 Linux/Windows 模拟器、安装应用或修改 AVD/设备数据。历史审计报告保留当时路径、计数和结论，最新执行结果应单独记录。

## 本次验证（2026-10-19）

| 检查 | 结果 |
| --- | --- |
| `python3 -m pytest --collect-only -q` | 36 items，0.03 秒，无编译/服务器启动 |
| `python3 -m pytest -q` | 36 passed，45.99 秒，无跳过 |
| Android unit + lint + assemble（上述命令） | BUILD SUCCESSFUL；JUnit XML 合计 308 tests，0 failures/errors/skipped |
| 权威 Framework `fabric-session` / `fabric-platform` | 10 / 8 tests 通过 |
| 权威 Framework portable | 8 tests 通过（两个集成测试组为 3 + 5） |
| 项目快照完整 workspace `--all-features --locked --offline` | 未完成：本机缺 `ab_glyph v0.2.32` 下载缓存，Cargo 在测试前退出 |

本次对齐了旧测试夹具：focus 模式冲突返回 `-EAGAIN` 且不修改 revision/计时；队列音频具有对应持久化记录；enrollment/focus 提取只包含目标函数；HTTPS 验证不可变 `.processed` 基线与独立 card-state 动作消息，仍检查分片、哈希、回执和重启重放。未通过修改生产代码消除失败。


## Vela M3E 补充验证

`python3 -m pytest tests/test_ui_motion.py tests/test_todo_list.py` 包含真实 SDK LVGL 的无窗口 host 构建（ASan/UBSan）和完整本地待办的有界分页/显式 ID 动作测试。默认 LVGL host 构建缓存位于 `~/.cache/buffer-vela/m3e-host-ui`，可通过 `BUFFER_UI_HOST_BUILD` 覆盖；不启动模拟器、设备或产品应用。模型/domain 的受控输入与真实业务回归边界见 [实施报告](../docs/finals/13-vela-m3e-implementation.md)。当前全 suite 为 38 项，包含原有 36 项。

最终 Vela M3E 源码全套实跑：`python3 -m pytest -q` → **38 passed in 51.28s**；日志 `/home/simon/.cache/buffer-vela/m3e-20261019/host-delivered.log`。完整 goldfish-arm64 构建与 ELF/bin 的 SHA-256 见实施报告，不等同于设备视觉验收。


## 当前专项验证（2026-10-29）

每周预约、健康量化和源事件同步：Android **397 tests，0 failures/errors/skipped**，主机 **41 passed**，Lint 与 APK 构建通过。原生 C 与 Android 各接收对方实际生成的 8 条 journal 原文；原文重复接收、独立 WAV 回执及历史不执行通过。双 Android 数据库另覆盖真实 HISTORY 分片、丢 ACK 后重启、修改/撤回回传和撤权。真实 S1、射频、Android 后台/日历/健康平台行为仍需设备验收。

在项目根目录运行，环境需设置 JDK 21 的 `JAVA_HOME` 和 Android SDK 的 `ANDROID_HOME`：

```bash
python3 -m pytest -q
bash foundation.fabric/apps/android/gradlew -p foundation.fabric/apps/android -I "$PWD/tests/interop/android.init.gradle" --max-workers=4 :buffer-app:testDebugUnitTest :buffer-app:lintDebug :buffer-app:assembleDebug
python3 tests/verify_source_interop.py
```

`android.init.gradle` 为测试 JVM 选择 C1，避开本机 JBR 21 在 Robolectric 插桩代码上发生的 C2 编译线程崩溃；测试断言和产品构建行为不变。互导命令依次导出 Android 原件、运行启用 journal 的生产 C、再验证 Android 接收原生原件/音频，最后一步强制要求本轮原生导出存在。结果保存在 `.validation/`；无需模拟器或已连接设备。

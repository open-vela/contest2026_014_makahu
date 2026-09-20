# Buffer：个人意识缓冲区

## 一、作品简介

Buffer 将随手记下的灵感、问题、待办和生活记录保存下来，再由手机侧 AI 服务完成分类、查找旧记录、关联主题和整理展示内容。openvela 设备承担屏幕录音、离线队列和桌面卡片展示，Android 承担数据存储、语音转写、LLM 编排和设备同步。

文字与语音转写共用整理流程；卡片详情读取已保存的结果，并展示真实处理记录，不在点击时再次请求分类。七日回顾由 LLM 根据实际记录生成。录音未转写前留在待处理队列，不混入收藏。日历及健康数据写入需要用户确认与系统授权。

## 二、选题方向

**AI 硬件产品创新**。以 openvela 设备作为低干扰入口，以 Android 作为联网与 AI 协同端，探索多设备上的记录、回顾与轻提醒。当前主要验证环境为 openvela 模拟器与 Android 17 模拟器；保留 Gemini S1 的设备应用实现。

## 三、目录结构

```text
app/buffer_app/                    openvela 应用：屏幕、录音、配网配对与同步
board/vela_sim/configs/            goldfish-arm64 模拟器配置
foundation.fabric/                Foundation Fabric 源码快照，无嵌套 Git 仓库
  apps/android/buffer-app/         Buffer Android（data / domain / ui）
  apps/android/fabric-sdk/         Android Fabric SDK
  apps/android/hub-app/            Fabric Hub
  crates/                         Fabric Rust 组件
  protocol/                       协议定义
data/agent/skills/                运行时 Skill 资源，未接入调用
docs/                             产品协议、历史审核与提交报告
tests/                            openvela 应用主机回归测试
third_party/material-symbols/     图标来源与许可
logs/6xingyv/                     本次真实 Codex 对话 JSONL 与清单
contest2026_014_makahu.xml         openvela repo manifest 与应用 linkfile
```

Android Studio 打开 `foundation.fabric/apps/android/`。Android 模块边界见 [ARCHITECTURE.md](foundation.fabric/apps/android/buffer-app/ARCHITECTURE.md)。比赛目录内包含一份可构建源码，不依赖开发者原有的 `/home/simon/projects/mocha/` 路径。原 Foundation Fabric 来源为 <https://github.com/6xingyv/foundation.fabric>，基线 `e724a2a7935cb0460aa39e0739d320e27d97d4af`，包含本次 Buffer 开发修改；不附带其历史提交、旧 APK/EXE 或构建缓存。

## 四、运行方式

### 拉取 openvela 工作区

在空目录执行（需安装 `repo` 及 openvela 构建依赖）：

```sh
repo init -u https://github.com/6xingyv/contest2026_014_makahu \
  -b dev-ai-contest-2026 -m contest2026_014_makahu.xml
repo sync -c -j8
```

本仓的应用由 manifest 映射到 `packages/demos/contest2026_014_buffer_app`。若已有工作区，更新本仓后重新同步 linkfile，或确认该位置指向 `app/buffer_app/`。

### Android

环境：JDK 21、Android SDK；通过 `ANDROID_HOME` 或本机 `local.properties` 指定 SDK，后者不提交。

```sh
cd contest2026_014_makahu/foundation.fabric/apps/android
bash gradlew :buffer-app:assembleDebug :buffer-app:lintDebug :buffer-app:testDebugUnitTest
adb -s <Android设备序列号> install -r buffer-app/build/outputs/apk/debug/buffer-app-debug.apk
adb -s <Android设备序列号> shell am start -n com.mocharealm.foundation.fabric.buffer/.MainActivity
```

在设置中填写 OpenAI-compatible Base URL、模型与密钥。当前默认模型为 `mimo-v2.5`，语音转写为 `mimo-v2.5-asr`；密钥只保存在手机侧。未配置时仍可本地记录，AI 任务等待配置。手机 AAC 录音先转换成 WAV，再提交 ASR。

### openvela 模拟器

在 openvela 工作区根目录执行：

```sh
./build.sh contest2026_014_makahu/board/vela_sim/configs/goldfish-arm64 -j4
```

配置启用 Buffer、显示、网络及相关设备功能。将该配置生成的镜像交给匹配的 openvela goldfish-arm64 模拟器启动，在 NSH 执行：

```sh
buffer_app
```

Windows 模拟器来自 [open-vela/prebuilts_emulator_windows-x86_64](https://github.com/open-vela/prebuilts_emulator_windows-x86_64)。模拟器版本、镜像架构和数据盘格式需要匹配；Vela 模拟器不能直接用 Android 系统镜像。当前项目不提供 Gemini S1 全容量刷机 IMG。

Android 与 Vela 应可互相访问。物理局域网可用发现与配对；两个模拟器各自的 NAT 网络通常无法直接转发广播，需要配置宿主端口转发。Vela 模拟器配置使用 `eth0` 与宿主网关 `10.0.2.2`。Android 模拟器桥接服务可转发到宿主：

```sh
adb -s <Android设备序列号> forward tcp:48888 tcp:48888
```

此转发只解决数据端口，不代表完成发现或配对；仍需通过设备入口完成认证，禁止通过伪造绑定绕过配对。BLE 配网的物理射频能力不能仅凭两个模拟器验证。

### Gemini S1 保留路径

设备侧支持屏幕按住录音、松开保存，配对成功后隐藏配对码。物理设备构建、Wi-Fi 与调试命令见 [设备端说明](app/buffer_app/README.md)。S1 尚未完成本次实机联合验收；此轮以模拟器为主，不将可编译表述为已烧录验证。

### 运行时 Skill

[data/agent/](data/agent/README.md) 归档四类能力：记录整理、问题回答、七日回顾、语音转写。其中分类整理包含 `search_records`、`read_record` 两个只读 function call 定义。

**这些外置 Skill 仅整理，不调用。** 未加入 Android assets、Vela ROMFS 或自动加载器；`catalog.json` 标记为 disabled。已有 Android 代码中的 LLM 流程保持运行，不能把资源归档理解为已接通设备 `/data/agent/skills/` 加载能力。

### 校验

Android 使用上述 Gradle 命令。Vela 应用可在 Linux 主机运行边界测试：

```sh
cd contest2026_014_makahu
for test in tests/test_*.py; do python3 "$test" || exit 1; done
```

主机测试不代替蓝牙、麦克风、刷机等实机验收。历史阶段性审核见 `docs/`，报告中的旧路径、旧计数和问题状态应结合其日期阅读。

## 五、AI Coding 使用说明

开发中使用 Codex 协助需求拆解、Android/openvela 协议设计、代码实现、编译排错、测试、Material 3 界面调整和提交文档整理。用户持续提出交互与职责约束，AI 依据真实代码、编译结果及模拟器截图迭代；分类工具循环、持久化、任务租约和设备协议通过测试校验。AI 也暴露了实际问题，例如转写静音输入、分类失败不可见和详情表单过密，并参与修复。

真实对话按 [logs/README.md](logs/README.md) 归档到 `logs/6xingyv/`。使用组委会 `contest-log-collector` 1.3.0 的 Codex 原生解析器导出当前会话，保持原始时间与正文并应用工具内置脱敏。该导出器输出用户/助手可见文本，不导出截图、内部推理或工具调用明细；它是会话导出时刻的快照，不是伪造或补写的开发记录。截止点见 manifest 与 [导出说明](docs/ai-coding-log-export.md)。

## 六、当前边界

- 手机承担当前 AI 编排职责，模型通过兼容接口调用；不是在手机上加载模型权重。
- 手机优先、Vela 次优先的自动 Coordinator 选举尚未实现，不能据此声称支持自动故障接管。
- 配网与配对代码已存在，模拟器、主机测试及物理射频验收范围不同；S1 实机链路未完成验收。
- 报告位于 [Buffer-技术报告.md](docs/submission/Buffer-技术报告.md)；视频、最终团队填写项等需按实际参赛材料补齐。

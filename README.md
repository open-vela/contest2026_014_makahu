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

Windows 模拟器来自 [open-vela/prebuilts_emulator_windows-x86_64](https://github.com/open-vela/prebuilts_emulator_windows-x86_64)。

Android 与 Vela 应可互相访问。物理局域网可用发现与配对；两个模拟器各自的 NAT 网络通常无法直接转发广播，需要配置宿主端口转发。Vela 模拟器配置使用 `eth0` 与宿主网关 `10.0.2.2`。Android 模拟器桥接服务可转发到宿主：

```sh
adb -s <Android设备序列号> forward tcp:48888 tcp:48888
```

### Gemini S1 保留路径

设备侧支持屏幕按住录音、松开保存，配对成功后隐藏配对码。物理设备构建、Wi-Fi 与调试命令见 [设备端说明](app/buffer_app/README.md)。

### 运行时 Skill

[data/agent/](data/agent/README.md) 归档四类能力：记录整理、问题回答、七日回顾、语音转写。其中分类整理包含 `search_records`、`read_record` 两个只读 function call 定义。

### 校验

Android 使用上述 Gradle 命令。Vela 应用可在 Linux 主机运行边界测试：

```sh
cd contest2026_014_makahu
for test in tests/test_*.py; do python3 "$test" || exit 1; done
```

## 五、AI Coding 使用说明

开发中使用 Codex 协助需求拆解、Android/openvela 协议设计、代码实现、编译排错、测试、Material 3 界面调整和提交文档整理。用户持续提出交互与职责约束，AI 依据真实代码、编译结果及模拟器截图迭代；分类工具循环、持久化、任务租约和设备协议通过测试校验。AI 也暴露了实际问题，例如转写静音输入、分类失败不可见和详情表单过密，并参与修复。

真实对话按 [logs/README.md](logs/README.md) 归档到 `logs/6xingyv/`。使用组委会 `contest-log-collector` 1.3.0 的 Codex 原生解析器导出当前会话，保持原始时间与正文并应用工具内置脱敏。

# Buffer 手机界面设计记录

更新：2026-09-20

## 当前实现

- 日常导航收敛为此刻、收藏、回顾，设备与设置从顶部进入。
- 首页先呈现语音和文字入口；待转写语音缩成一行提示，点击后打开整理面板。
- 文字输入、卡片处理、周回顾全文和服务配置按需在底部面板中打开。
- 收藏保留搜索与灵感/问题/生活筛选；回顾提供本周概览、模式及研究入口。
- 采用固定暖白/灰绿配色及相应深色主题，替代上一轮随壁纸变化的动态配色。
- 导航和操作图标全部使用 Google 官方 Material Symbols Rounded，24px、weight 400、grade 0；导航选中时使用填充版本。已删除手绘图标路径。
- 本地打包所需矢量资源，无运行时字体或网络下载。许可及精确来源在 third_party/material-symbols。

## 设计依据

- https://developer.android.com/develop/ui/compose/designsystems/material3
- https://developer.android.com/develop/ui/compose/components/navigation-bar
- https://developer.android.com/develop/ui/compose/components/chip
- https://developers.google.com/fonts/docs/material_symbols

## 验证范围

第二轮布局已安装到 Windows Android 17 模拟器，并检查了真实首页、收藏和回顾截图。
布局版本 assembleDebug、lintDebug、240 项现有单元测试通过。图标替换版本正在另行构建验证。

尚未完成所有大字体/屏幕尺寸、深色模式、表单输入和配网流程的视觉交互验收。Vela 界面未在本轮改动。


## 最新调整

图标替换构建和 Lint 已通过。收藏新增 Material 侧滑、长按多选、编辑与删除确认；七天回顾移至独立页面，LLM 周总结替代固定模板。统一接口、数据一致性与后台任务测试共253项通过。实现与验证边界见 LLM_SERVICE.md。

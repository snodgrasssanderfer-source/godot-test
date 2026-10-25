# 废站供电 · Android 工作流测试

独立的 Blender → Godot 原型，不是仓库现有 Box Jumper 或像素小镇的更新。

下载 **power-station-android.apk** 安装。64 位 ARM，Android 7.0+，横屏。左侧方向键移动，右侧交互，右上暂停。收集三枚电池，回到中央供电台恢复供电，再从北侧出口离开。

测试签名，仅用于试玩。已进行 Godot 运行时触屏事件检查、完整玩法及重导入回归；本次 GitHub Actions 经 Godot MCP 导出 APK 并验证签名、对齐和压缩包完整性。尚未在真实 Android 设备安装运行，不承诺所有手机兼容。

对应源码请下载附件 **power-station-source.zip**，其中 android-project 是触屏版本，包含源代码与第三方许可。GitHub 自动生成的 Source code 压缩包是仓库标签快照，不是本独立原型的完整源码。标签仅用于独立文件发布，不修改 main、Claude 分支或 Pages。

源工程包含 AGPL-3.0-or-later 的 Godot MCP 开发插件；导出程序默认不启用该插件的控制接口。组合测试工程按相应许可提供，字体和引擎保留各自许可。

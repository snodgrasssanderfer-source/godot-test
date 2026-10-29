# B 设备与语音模块暂停交接

2026-10-29 按用户最新要求暂停。此轮收尾只保存现状，没有继续功能开发、工具链恢复、编译或打包。

用户提供了新的小克设定与预期场景，并明确以新设定为准。本执行线程尚未读取这些附件，现有实现未合入新设定；附件内容及与现有行为的差异仍待核对。本文件记录目标、事实和继续入口，不限定 Claude 的实现路线。

## 当前成果位置

- 仓库：https://github.com/snodgrasssanderfer-source/godot-test
- B 分支：`companion/devicev2-20261029`
- 源码目录：`projects/xiaoke-android/source`
- 固定起点：`34290cab4eac3b75caf92c7c89711bf5727d302b`
- 设备模块初版：`b059d052d4257f271fb2455de4d08244fa7c15d9`，主线曾确认已合入。
- 安全修正：`eb6d3b2d1158e80e7d5a9f05a884838e5a4164e0`
- 最新功能代码：`81083b1facb6f6db545c7de1aa0e8a9555b2de29`

最新两个功能提交包含输入恢复代际、动作前画面复核、临时音频到期，以及删除后陪伴上下文清理。本次没有重新调查主线是否已经合入这两个提交。

## 目标与已落地内容

此前已知目标是保留 DSH 底座，让小克以手机陪伴为主、任务与设备操作为辅助；悬浮球先行，观察与控制分开，输入急停保留陪聊。新设定及场景的优先级高于这些历史说明。

当前分支包含真实原生实现与独立 React 面板：小悬浮球、分别采集麦克风与目标应用内播的限时 AudioRecord / MediaProjection 会话、宿主模型音频分析、单一 presence ASR/TTS 链、带授权期限与请求去重的点击 / 长按 / 滑动，以及动作后的再次观察。输入急停独立于观察、收音与陪聊，恢复请求绑定最新 inputEpoch。

删除传播实现清除 recent、字幕、lastReply、原始临时画面、待保存候选及缓存语音，并隔离旧模型和识别回调。事件保存通过上下文事务适配，保留文字依据及明确 observation 来源，模型回应标记为 inference，原截图不随事件长期保存。

## 接线状态与继续入口

- [CONTRACT-B.md](CONTRACT-B.md)：实际载荷、状态、来源版本与平台边界。
- [device-manifest.patch](../integration/device-manifest.patch)：原生音频 Activity / Service 声明；主线负责的接线文件。
- [device-context.patch](../integration/device-context.patch)：上下文事务适配、life.contentChanged 转发及音频请求代际检查。已在主线 `fe93ac83599633a5fc04a2b51104f879071a0914` 的文件副本上通过应用检查；其最终集成编译未在 B 分支执行。
- 设备代码主要位于 `companion/devicev2/`，语音与临时上下文位于 `companion/presence/`，界面位于 `src/companion/devicev2/`。

现有代码在缺少上下文事务适配时仍可交流，但不会读取旧记忆或开放事件保存。音频摘要通知尚无对应的持久 observation 来源，因此没有完成自动长期记忆闭环。主线当前接线状态以及 A 的真实数据库删除 / 保存竞态结果不在本次收尾核验范围内。

## 已测与未测

[验证记录](../evidence/devicev2/VERIFICATION.md)与[源文件哈希、测试结果](../evidence/devicev2/native-targeted-result.json)保存了此前实际结果：最新原生生产代码编译、19 项定向 JVM 测试、Android lint 通过；此前 React 面板 4 项测试、TypeScript 与 ESLint 通过。更早一次全量原生测试共 1495 项，0 失败、3 跳过，不能替代后续改动的全量回归。

没有运行真机或模拟器，没有真实模型 / QQ / B站账号测试，没有安装到手机，没有由 B 构建或分发 APK。Android 16 权限与系统收音限制、内播实际可捕获性、语音体验及手势物理释放仍未验证。当前画面像素变化会拒绝操作，动画游戏可能无法执行；没有验证通用 RPG、全双工或任意屏幕位置五秒手势。

Git 中保存的是脱敏工程、技术接口与测试记录，没有新设定附件、聊天原文、真实记忆、账户密钥、签名私钥或工具链缓存。

# AIRI 固定源码核查

核查提交：`moeru-ai/airi@71b3c178ea6a6b828821027188f6b5f7fa56b788`。
核查方式：GitHub 连接器只读读取固定提交，保存文本和 Git blob SHA。
`reference-manifest.json` 记录每份文件的固定来源和 SHA。
`tests/provenance.test.cjs` 用 Git blob 算法校验本地文本，防止引用漂移。

## 可交给主线的结论

1. Pocket 有真实 Android 工程。不能再写成“仅有 iOS”。
2. Pocket 仍在 README 架构图中标为 experimental，入门命令以 iOS 为例。
3. Android 源码、SDK 36 配置和测试文件存在，不代表 Android 16/目标 Android 设备 实测通过。
4. 小克宿主是 React 18 / Capacitor 7.6.9，AIRI 是 Vue / Pinia / Capacitor 8.5.x。
5. 直接移植 `Stage.vue` 会同时带入对话 hooks 和播放编排。此次只复用角色纯逻辑。
6. 所有新代码落在交付包的 `src/companion/presence/`。没有修改主线文件或依赖。

## Android、Capacitor、Web/PWA

| 证据 | 固定提交源码结论 | 验证限制 |
| --- | --- | --- |
| [Pocket package.json](https://github.com/moeru-ai/airi/blob/71b3c178ea6a6b828821027188f6b5f7fa56b788/apps/stage-pocket/package.json#L12) | 有 `dev:web`、`dev:ios`、`dev:android`，引用 Capacitor Android/iOS | 未执行这些脚本 |
| [variables.gradle.kts](https://github.com/moeru-ai/airi/blob/71b3c178ea6a6b828821027188f6b5f7fa56b788/apps/stage-pocket/android/variables.gradle.kts#L1) | minSdk 26，compile/targetSdk 36 | 未构建 APK |
| [MainActivity.kt](https://github.com/moeru-ai/airi/blob/71b3c178ea6a6b828821027188f6b5f7fa56b788/apps/stage-pocket/android/app/src/main/java/ai/moeru/airi_pocket/MainActivity.kt#L31) | 注册麦克风权限与认证插件，安装宿主 WebSocket 桥，销毁时释放桥 | 未运行 Android 生命周期 |
| [MicrophonePermissionPlugin.kt](https://github.com/moeru-ai/airi/blob/71b3c178ea6a6b828821027188f6b5f7fa56b788/apps/stage-pocket/android/app/src/main/java/ai/moeru/airi_pocket/MicrophonePermissionPlugin.kt#L13) | 此插件检查权限状态，不主动弹授权框 | 不能把它称为完整录音服务 |
| [AndroidManifest.xml](https://github.com/moeru-ai/airi/blob/71b3c178ea6a6b828821027188f6b5f7fa56b788/apps/stage-pocket/android/app/src/main/AndroidManifest.xml) | 本文件声明联网、音频设置、录音权限 | 没有检查构建后的合并 Manifest |
| [MainActivityBridgeTest.kt](https://github.com/moeru-ai/airi/blob/71b3c178ea6a6b828821027188f6b5f7fa56b788/apps/stage-pocket/android/app/src/androidTest/java/ai/moeru/airi_pocket/MainActivityBridgeTest.kt) | 有 Android WebSocket 桥仪器测试源码 | 没有运行上游测试 |
| [websocket-bridge.ts](https://github.com/moeru-ai/airi/blob/71b3c178ea6a6b828821027188f6b5f7fa56b788/apps/stage-pocket/src/modules/websocket-bridge.ts#L151) | 根据 Android JS interface 或 iOS message handler 提供原生 connector | 不移植此桥，保留小克原有桥 |
| [stage-web vite.config.ts](https://github.com/moeru-ai/airi/blob/71b3c178ea6a6b828821027188f6b5f7fa56b788/apps/stage-web/vite.config.ts#L201) | Web 应用配置 VitePWA 和 manifest，HuggingFace 目标排除此插件 | PWA 能力不能替代原生后台感知能力 |
| [Pocket vite.config.ts](https://github.com/moeru-ai/airi/blob/71b3c178ea6a6b828821027188f6b5f7fa56b788/apps/stage-pocket/vite.config.ts) | Web 构建与 Capacitor 运行环境配置，含 SDK/模型下载插件 | 未执行构建，未下载 SDK、模型或语音模型 |
| [Pocket README](https://github.com/moeru-ai/airi/blob/71b3c178ea6a6b828821027188f6b5f7fa56b788/apps/stage-pocket/README.md) | 明确 secure origin 和 OPFS 逐设备检查要求 | 尚未检查目标 WebView 的 OPFS 支持 |
| [根 README](https://github.com/moeru-ai/airi/blob/71b3c178ea6a6b828821027188f6b5f7fa56b788/README.md#L313) | Pocket 入门命令是 iOS，架构图注明 experimental | 只作成熟度证据，不作 Android 不存在的证据 |

## 角色与播放同步路径

`apps/stage-pocket/src/pages/index.vue` 引用共享 `WidgetStage`。手机模式关闭 orbit controls，设置展开时传递 paused。
共享实现位于 `packages/stage-ui/src/components/scenes/Stage.vue`，并非独立 React 组件。

| 模块 | 源码行为 | 本次处理 |
| --- | --- | --- |
| `Stage.vue:185`、`:442` | 持有会说话的对话 hooks、语音输出连接和实际播放开始/结束回调 | 不复制整套舞台，避免第二套主对话循环 |
| `Stage.vue:409`、`:480` | 在用户音量之前接分析节点，播放时读口型，停止时归零 | 保留“实际播放驱动呈现”原则。宿主提供已播放音频遥测 |
| `stage-ui-live2d/.../Model.vue:350`、`:487` | 加载 Cubism 模型，自动交互关闭，口型在最终更新阶段应用 | 仅提供已加载模型的参数写入适配器，不带 Cubism 运行时 |
| `stage-ui-three/.../vrm/lip-sync.ts` | 元音驱动写入 VRM `aa/ee/ih/oh/ou` | 改写为无 Vue 依赖的 `createVrmBinding` |
| `stage-ui-three/.../vrm/expression.ts` | 表情与口型分清所有权，避免控制器互相覆盖 | 适配器不写 blink 或自定义表情，不复制整个情绪系统 |
| `model-driver-lipsync/.../vowel-driver.ts` | AEIOUS 到 AEIOU 投影、赢家/次强选择、静音阈值、平滑 | 原文件逐字节复用 |
| `stage-ui-live2d/src/utils/eye-motions.ts` | 随机眼神微动间隔分布 | 原文件逐字节复用 |
| `stage-ui-live2d/src/constants/emotions.ts` | 通用情绪名及 VRM/Live2D 映射 | 原文件逐字节复用 |
| `stage-ui/src/libs/speech/playback-speaking-state.ts` | start/end/interrupt/reject 同步说话状态 | 在小克纯呈现层补充播放身份、暂停代次和清理 |

## 精确采用与许可证

全部来源固定在上述 AIRI 提交，许可证为 [MIT](https://github.com/moeru-ai/airi/blob/71b3c178ea6a6b828821027188f6b5f7fa56b788/LICENSE)。
保留 `Copyright (c) 2024-PRESENT Neko Ayaka` 和完整许可文本。

| 源文件 | Git blob SHA | 采用文件 | 改写 |
| --- | --- | --- | --- |
| `packages/model-driver-lipsync/src/shared/wlipsync/vowel-driver.ts` | `7ded821d54481edc4928682f6c410727bd7916a5` | `src/companion/presence/airi/vowel-driver.ts` | 无 |
| `packages/stage-ui-live2d/src/utils/eye-motions.ts` | `e89e35e1271d0aab71ade828db9f7907f4e922c8` | `src/companion/presence/airi/eye-motions.ts` | 无 |
| `packages/stage-ui-live2d/src/constants/emotions.ts` | `5478c190067db79dd25edb34ab9c51bd433060c6` | `src/companion/presence/airi/emotions.ts` | 无 |
| `packages/stage-ui-three/src/composables/vrm/lip-sync.ts` | `7606d0963f88de25a5635e879a87458000956a3e` | `src/companion/presence/model-bindings.ts` | 保留元音参数表，移除 Vue watcher、AudioContext 和 wLipSync 创建，改为外部帧输入 |

新增运行依赖：无。角色核心与 Canvas 使用宿主已有 TypeScript 和浏览器 API。
最终 React 面板使用宿主已有 React。没有安装 Vue、Pinia、Three、Pixi、wLipSync 或 Capacitor 升级。
Live2D/VRM 绑定是参数接入点，不是模型加载器。完整引擎和模型导入没有完成，也没有伪装完成。
元音权重必须由已授权的音频侧提供。没有权重时可使用播放音量驱动单一开口，明确标记 amplitude。

没有采用 AIRI 自带人物、Hiyori、AvatarSample、动作文件、字体或任何第三方角色素材。
Canvas 图形是本次原创的几何占位角色，不代表用户已确定最终人物。
将来若采用完整引擎、Cubism SDK 或角色资产，需要分别固定来源和核对各自许可。

## 本次环境与未执行项目

- 环境是独立 `/workspace`，与主集成线程不共享文件。
- 本地 `godot-test` 为 `815f83af06fac27a2c8eb6182fb1c21ea2f62a01`，无安卓目录。
- 只读取得宿主基线 `b93d665a5a3828f1838d9b01e3503248fb379ec5` 的旧接口，未据此写交叉补丁。
- 已从私有 Library 取得并校验 `e2a21d55e8def1803a9e578a73a91d05cfe89a73` 检查点及完整合同。
- 现有 Node 是 `24.19.0`，现有 TypeScript 是 `5.8.2`。没有安装包。
- 没有读取密钥、调用真实模型 API、登录账号、推送 Git 或上传私人资料。
- 没有运行 AIRI、构建 APK、启动模拟器或连接 目标 Android 设备。
- 没有验证手机上跨应用覆盖层、后台麦克风、MediaProjection、打断延迟、热量或电量。

测试范围包括完整模块 TSX 类型检查、33 项纯逻辑/来源测试和 8 个 Chromium 显示场景。React 按钮测试已提供但未在本环境运行。README 功能说明不计入运行验收。

## 本轮最终采用模块

`PresencePanel.tsx` 按 e2a21d5 合同接入持续会话控制。
`state-adapter.ts` 将原生状态投影为 AIRI 驱动的角色帧。
`native-events.ts` 只订阅宿主事件，不新增轮询或对话编排。
`character-art.ts` 处理用户本地 PNG 图集，按状态选择帧并清理资源。
`presence.css` 全部限定在 `.xiaoke-presence` 内，无全屏浮层或全局样式。
默认角色明确为原创动态示意，用户素材许可由用户持有。
新增运行依赖为零。完整 Live2D/VRM 加载器及同步语音采样不冒称完成。
详见交付包 integration-notes.md 和 TEST-EVIDENCE.md。

## 主线整合扩展

主线已将同一 AIRI 衍生绘制器接入安卓原生浮窗，通过只提供本机静态资源的 WebView 展示；没有新增模型请求或 JS 设备权限。PNG / 动作图集经主人导入后保存在应用私有目录，主界面和浮窗共同使用，恢复内置角色可删除。此持久化由主线 CompanionCharacterStore 完成，覆盖初始独立模块“仅本次页面”的限制；不写人格或记忆，不上传。

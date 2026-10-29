# 小克安卓助手：停止时的完整交接

2026-10-29。用户因额度安排要求结束本轮开发，由 Claude 接续。本文件记录目标、现有代码、证据和缺口，不限定后续开发者的实现路线。**本轮没有生成新的 APK；当前是已经合并、仍有集成验证缺口的源码检查点。** 停止通知后只做保存、合并已完成提交、交接和 Git 同步。

## 最新设定优先

协调方 dot 已读取两份新设定/场景文档，并转交可公开的去标识化摘要；本执行线程没有直接读取原始附件，也没有继续修改代码落实它们。摘要和冲突见 [LATEST-SETTING-SUMMARY.md](docs/LATEST-SETTING-SUMMARY.md)，原文由用户私下提供，不在公开仓库。

新设定覆盖旧说明：游戏平时安静，在用户聊起卡关或结算时搭话；主人私聊域的任何记忆都不向外部透露；外发评论、重要资料写入先确认。**代码仍有 shareSummary、meaningful 自动保存和主动陪伴路径，不代表满足新设定。** 五秒输入急停已经明确落在悬浮球上；自然触屏接回是另一个示例，最终规则尚未选定。

## 最终目标

私人自用的 Android 16 陪伴助手，以小克的连续交流、关系和经历为主，DSH 设备操作和任务能力为辅。原工作台、文件、终端、插件、无障碍及 Shizuku 能力保留。目标是安装、配置、授权后可用的产品，当前源码状态不等于完成。

- 可配置原生 Google/Gemini 中转；日常与复杂任务分别使用可编辑的 gemini-3.8-flash、gemini-4-argon。这些是用户中转模型标识，不是本轮对官方型号、实际能力、价格或延迟的确认。密钥由用户在设备填写，人设和长期记忆独立于模型会话。
- 可编辑/导入/版本管理的角色；当前先悬浮球，完整 Live2D 属后续方向。新设定的语言和形象摘要见单独文档。
- 有来源的记忆、日记、兴趣和任务可查看、搜索、更正。忘掉与真正删除不同；删除影响派生内容和同步。主人聊天与自主活动分别预算，未知费用不能按零计。
- QQ 常态使用独立 NapCat 通道，避免与用户抢手机 QQ 界面；真实安卓 UI 操作仍保留。稳定账号和目标绑定、私聊/群分域、系统认证主人、精确内容批准。
- B站字幕、音频或画面依据进入理解和评论准备，不能把标题当观看经历。账号活动、空间说说、评论和资料保存有各自范围及审批边界。
- 手机单机 RPG 的讨论、观察和临时代跑；后台任务与陪聊并行，借屏协商，输入急停不连带停止陪聊。没有指定并验收首款游戏。
- 手机有网而电脑离线时仍可 API 陪聊，完全离线可看本地历史/日记；跨设备加密同步和长期服务属于完整体验的一部分。

## 分支和保存状态

仓库：https://github.com/snodgrasssanderfer-source/godot-test 。主集成分支 companion/iteration-20261029；本文件所在提交就是停止检查点。

| 输入 | 完整提交 | 状态 |
|---|---|---|
| 公开 main 起点 | 815f83af06fac27a2c8eb6182fb1c21ea2f62a01 | 独立分支起点 |
| 脱敏完整源码基线 | 34290cab4eac3b75caf92c7c89711bf5727d302b | source 完整，harness-web 为普通文件 |
| 旧交接 | b93d665a5a3828f1838d9b01e3503248fb379ec5 | 历史来源，Windows 未推送改动未取得 |
| 原生 PRoot 回传 | 4a493c504d33017d414e00748be34aca3ba573a4 | 固定构建材料与证据 |
| A 生活/记忆/预算 | e678cc746aabbf71ecc3feba968925e33eec1e3a | 全部合入 companion/life-core-20261029 |
| B 设备/语音及停止交接 | 911a07d7774db04f01fd5c9f990a9502a5f8c9bf | 全部合入 companion/devicev2-20261029；功能末次 81083b1 |
| C 社交/任务/同步/B站 | 39affcf5c43ae4d40ecd015314c1fde25ad521c0 | 全部合入 companion/c-social-20261029 |

最后主线未提交改动也随此检查点保存：实际来源追踪、可信 actorId、presence 上下文事务、音频摘要来源、生活面板挂载及备份文件选择、类型/lint 修正。未丢弃未完成代码；main、游戏目录和 Windows 本地文件未修改。

## 代码入口与已有行为

以下为实际实现，不代表全部端到端通过。路径相对 source；原生前缀为 android/app/src/main/java/io/deepseekharness/mobile/。

| 模块 | 入口 | 已有行为 |
|---|---|---|
| 根 UI/桥 | src/companion/CompanionApp.tsx、bridge.ts；companion/CompanionPlugin.kt | 陪伴/生活/活动/连接/角色/设置；原 DSH 工作台入口，本机可信命令 |
| 模型和工具 | companion/CompanionEngine.kt、GeminiClient.kt、GeminiWire.kt、iteration/CompanionModuleHost.kt | 流式、工具续写/签名、统一主人/活动队列、逐 HTTP 预算预留及发送权、实际图像/音频载荷和取消回执 |
| 生活和数据 | companion/life/、CompanionStore.kt；src/companion/life/ | 事务记忆、修订/来源/派生删除、事实日记、兴趣、注意力和预算 UI，旧上下文回写栅栏 |
| 备份 | companion/iteration/CompanionBackupCoordinator.kt | 生活+社交组合加密导入导出、校验和墓碑；不含凭据/执行批准/原媒体，目前明文总量上限 11 MiB，超限报错 |
| 陪伴/音频/控制 | companion/presence/、companion/devicev2/、CompanionOverlayService.kt；src/companion/devicev2/ | 单语音链，限时分轨麦克风/内播采样、模型理解、悬浮球五秒输入急停、时限/代际/画面复核手势 |
| 游戏接线 | src/companion/iteration/GameSessionPanel.tsx、Plugin 和 Engine 设备工具 | 应用选择、观察/输入分开授权、任务入队、真实画面回传、动作后再观察；没有通用游戏实战结论 |
| 社交/任务 | companion/social/、OneBotBridge.kt；src/companion/social/ | 多模态消息、分会话开关、持久审批、空间动作、提醒/摘要、重启栅栏、加密素材/经历同步 |
| B站 | companion/BilibiliReader.kt、companion/social/ | 字幕/关键帧依据、账号核对、点赞关注开关、评论下载审批和实际 HTTP 请求；协议只用合成响应测试 |
| 文件/人格 | companion/documents/、src/companion/characterCard.ts | 系统文件选择、有限 PDF 文字/ZIP 文本、角色卡 JSON/PNG 导入和版本管理 |

精确 API 与来源见 [CONTRACT-A](docs/CONTRACT-A.md)、[CONTRACT-B](docs/CONTRACT-B.md)、[CONTRACT-C](docs/CONTRACT-C.md)、[C-SOCIAL-EXTENSION](docs/C-SOCIAL-EXTENSION.md)。它们记录当时接口，新设定差异在最新摘要说明。integration 中补丁有一部分已由主线手动接入，补丁存在不等于仍待应用。

## 已测和未测

最近一次主线定向 JVM：14 套件、104 项通过，0 失败/错误/跳过；生产 Kotlin 与 Android instrumentation 测试源码编译成功，后者未在设备执行。React 五组交互共 27 项通过，TypeScript 和 companion ESLint 通过。计数/摘要在 [验证证据](evidence/integration/STATUS-AT-STOP.md)。

**这些检查早于最后 A/B/C 合并和部分根接线，不覆盖停止检查点完整组合。** 分支检查另见各自文档，不与主线数字相加当产品验收。历史候选 release、签名或浏览器截图不是本轮新版 APK 证据。

未完成本轮最终前端打包/Capacitor 同步、全项目 lint、release 组装及新 APK 签名核验。没有 Android 16 真机验收；历史软件模拟器在应用安装前首启失败。没有真实中转调用、QQ/B站登录、点赞/评论/发消息或常驻服务部署。

## 明确缺口

1. 新设定未进入代码：私聊域绝不外传、重要资料确认和游戏安静策略与旧实现有差异。
2. 最终 A/B/C/根接线没有统一构建结果；Android SQLite 来源删除/备份恢复的仪器测试仅编译。
3. 扬声器语音仍半双工；免按键自然抢话、内播捕获兼容性、Android 16 前台服务/权限/后台行为尚未实机验证。
4. 游戏动作要求短期有效画面并作像素复核，动画可能拒绝；规划实战循环、触屏接回、首款游戏体验未完成。
5. NapCat 常驻主机/登录/可靠在线未部署。已有收到的好友请求、群邀请处理，不等于主动添加/加群已实现；SILK/AMR 仍有不支持路径。
6. B站真实接口、账号回执和公开视频下载未验收；下载受格式/来源/字节上限约束。进程被杀后的精确提醒、跨设备原素材同步未完成。模型社交规划与审批/隐私拒绝之间仍有体验接线待评估。
7. 精确金额预算缺真实渠道价格；未知费用保留预留，不等于已知账单。本地业务库仍为应用私有 SQLite；已有备份/素材/同步加密，未接 SQLCipher。
8. 没有新安装包。最终包/签名连续性、升级、权限拒绝、断网恢复及完整互动还缺整体验收。

## 构建恢复资料

[build-materials/BUILD.md](build-materials/BUILD.md) 是已有恢复流程，toolchain-inputs.json / release-inputs.json 固定来源和摘要。工具链：Linux x86_64、JDK 21、Gradle 8.11.1、Android SDK/build-tools 35、NDK r29-beta4、CMake 3.22.1、Node 24、pnpm 11。分支不含 SDK、node_modules、rootfs 和签名文件。

DSH 固定 e9be0e43629f8b744991c2cf9efd160ca7d41abc（v0.3.0）。PRoot 脚本/补丁在 build-materials；回传提交提供 libdsh_proot.so / libdsh_proot_loader.so 输入输出证据。构建路径参与 loader 信息，runner 哈希可能随路径变化，历史差异没有靠改清单强行抹平。

环境变量名称：JAVA_HOME、ANDROID_HOME、ANDROID_SDK_ROOT、ANDROID_NDK_HOME、ANDROID_USER_HOME、GRADLE_USER_HOME、GRADLE_BIN。具体本地路径和凭据不在公开交接中。Gradle 已完成上述编译/测试，偶发 Kotlin daemon 启动重试未阻止已有成功结果。当前源码包名 io.github.dshcompanion.mobile、versionCode 2 / 0.2.0-companion-private、minSdk 26 / targetSdk 35、arm64-v8a；这些不是新 APK 的验收信息。

可复现命令示例（未对停止检查点重新执行）：

```sh
cd projects/xiaoke-android/source
pnpm install --frozen-lockfile
pnpm run build
pnpm exec cap sync android
cd android
"$GRADLE_BIN" :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
"$GRADLE_BIN" :app:lintRelease :app:assembleRelease
```

本轮交付为完整源码及事实记录；真实账号/API/设备测试及其费用和范围尚未发生。后续实现路线、优先级和操作范围由用户与接续开发者决定。

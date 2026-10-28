# 小克安卓助手：源码与进度交接

更新日期：2026-10-28（北京时间）。本次只恢复文件、整理交接并上传 Git；用户已要求停止继续开发，由用户手动启动新的 Codex 进程接续。

**这是完整开发检查点，不是已经完成的安卓产品。** 新增原生代码和前端分别编译通过；尚未产生改造 APK，没有完整测试套件或实机验收。先读本文，再看历史研究；不要把旧需求快照、报告里的设计或原 DSH 测试记录当成新功能已完成。

## 1. 用户目标与最新优先级

最终目标是以 `dphmoblie/deepseek-harness-android` 正式版 v0.3.0 为底座，做可安装、可配置的安卓「懒人包」。保留原 DSH 运行时、安卓操作、插件、文件与编程工作台能力，增加同一个 Gemini 助手的人格、记忆、QQ/B站活动和安装引导，不能替换成纯聊天机器人或仅有按钮的演示。

已确定：

- 设备：v2520A，Android 16。实际 ABI、页大小、ROM 电池策略、Root、Shizuku 状态尚未在设备验证；不能按型号猜。
- 原生 Google/Gemini 兼容中转：公开默认地址 `https://api.apikey.fan/v1beta`，用户安装后本机输入密钥。模型 `gemini-3.8-flash` 与 `gemini-4-argon` 可切换，也允许编辑实际模型 ID。用户确认站点提供 Argon；实际站点能力、延迟、计费均未调用验证。
- 用户提供的 CLI 配置根地址为 `https://api.apikey.fan`，模型为 `gemini-3.8-flash`；OpenCode 配置使用 `@ai-sdk/google` 和 `/v1beta`。不能仅凭客户端名称或路径差异断言不同速度渠道。
- 人格正文、语气和台词由用户接上 Gemini 后与 Gemini 共同编写。开发方只收集资料并提供编辑、导入、版本恢复能力；不写死“小克”的性格或形象，不把单一社区二创当官方设定。
- 人格、关系/事实/事件记忆、任务状态、模型临时上下文分开保存；切模型不丢人格与记忆。主人私聊、不同群、公共资料隔离，支持来源、更正、删除、加密备份恢复。
- QQ 要真实安卓账号使用、主人私聊和自然群聊；可比较成熟消息桥，但不能悄悄换成纯机器人。群友消息和网页/字幕不能改变主人权限。明确 AI 助手身份。
- B站要先取得字幕、音频、画面或视频理解证据，再形成评论；不能只看标题说看过。外发必须与目标、正文、账号和授权绑定，结果不明不自动重发。
- 私人自用，产品不做公开发行。用户此次另行授权把当前相关源码和交接资料放入既有公开 Git 仓库；这不等于授权公开发布 APK、账号数据或密钥。
- 优先完成真实体验并复用成熟社区模块；不把报告、核验次数或 mock 当作完成。不要继续盲目拼装多个完整 harness。

分工：此前由 dot 沟通协调、单个 Astra max 普通速度在用户 Codex 环境执行；没有启动子代理。下一进程由用户自行启动。当前交接不授予真实账号登录、中转付费调用、实际发消息/评论或安装到用户手机的权限。

**新增附件状态：** 用户最新 `PersonaAndScenarios` 附件正文尚未读到；协调侧报告读取被权限拒绝。本文不推测其内容。接续前请由用户在新进程提供可读正文或授予可用读取方式，作为最新需求输入。

已有场景例子：用户玩游戏获胜时，小克通过悬浮窗聊刚才的胜利。这是待扩写的体验例子，并非授权全天监控、持续录屏或后台监听；当前没有游戏胜利语义识别。后续需明确画面来源和时机、判断证据、延迟、打断频率、触控遮挡、息屏限制及音视频最小化。无障碍树不等于游戏画面理解。

## 2. 最新真实进度

| 项目 | 状态与证据 |
|---|---|
| 正式 APK 与对应源码静态审计 | 已完成；历史报告和精简证据在 `research/`。未安装或执行原 APK/rootfs 内程序。无需从头再拆。 |
| 独立源码工程 | 已恢复完整文件。715 个源文件/项目文件在 `source/`；清单见 `build-materials/source-file-manifest.json`。无游戏代码修改。 |
| 原生助手模块 | 11 个 Kotlin 文件已写入，`:app:compileDebugKotlin` 成功；见 `evidence/gradle-compile.log`。编译不证明运行行为正确。 |
| 助手管理界面 | React/TypeScript 界面、浏览器本地存储适配已写入；`pnpm run build` 成功，见 `evidence/web-build.log`。有大 chunk 警告，不是编译失败。 |
| Capacitor 资产同步 | 较早的 `android:sync` 曾成功，日志保留；新增最终界面之后没有再同步，下一步需要重新同步。 |
| PRoot | 从固定对应源码、补丁和官方 NDK 重编译成功，已在原工程本机放入新 runner/loader；没有执行它们。输出摘要与改动脚本已交接，二进制不上传。最终 APK 来源清单/旧 NOTICE 尚未更新。 |
| 手机尺寸浏览器检查 | 中断前结果未知；恢复后取回截图，实际为 `ERR_CONNECTION_REFUSED`。Vite 日志为端口 4173，而检查脚本用了 5173。只恢复结果，未在交接轮修复或重测。见 `evidence/browser-preview-connection-refused.png`。 |
| lint、原生/前端单测、集成、持久化、备份恢复测试 | 本次新增功能尚未跑完/尚未建立有效验收证据。仓库继承的测试文件不表示新功能通过。 |
| Android16、后台/锁屏、真实 QQ/B站交互 | 未验证。当前环境未发现可用 `/dev/kvm`，未建立安卓模拟器或真机测试。浏览器不能替代这些验收。 |
| APK、签名、安装 | 尚未构建可交付改造 APK，尚未生成新签名，未安装到用户手机。 |
| 中转/API/账号外发 | 零实际中转调用、零账号登录、零 QQ 消息或 B站评论发送。没有使用聊天中暴露的密钥。 |

原执行环境曾断开并标记不可用，本次恢复后项目、日志、重建产物仍可读取。已保存本地源项目检查点 `82c5ca652db4a0d181576b25b21b98baab07616a`；它是原独立项目的本地 commit，并非本 Git 交接分支的提交号。公开副本只对一份旧状态 TXT 的私有交付链接做了替换，排除了 3 个私人交付元数据 JSON；功能源码没有在本次交接中改写。

## 3. 基线与目录

- Android 上游：`https://github.com/dphmoblie/deepseek-harness-android`
- 发布：`v0.3.0`，完整提交 `e9be0e43629f8b744991c2cf9efd160ca7d41abc`。
- 原独立工程基线 commit：`9ae22acc52466d36faa2b61e059a3440016b0874`（从固定归档建立，不是上游同名 commit）。
- `harness-web` 上游：`https://github.com/dphmoblie/deepseek-harness-mobile-support`，固定 `5cb43dbcbabddac391afd0f947b771fb899582a1`。交接副本已带其普通源码文件，**不是 gitlink**；上游 `.gitmodules` 只是保留的历史配置。无需先把它更新到分支最新。
- Operit2：`dc4c3a9405dc7ed3ef69b2ac9a6ace65374d77cf`；源归档由正式对应源码包提供。

```text
HANDOFF.md                     当前交接与真实进度（优先）
CONTINUE_PROMPT.txt             可粘贴给下一 Codex 的短提示词
source/                        完整 Android + React 源码检查点
  android/app/src/main/java/io/deepseekharness/mobile/companion/
  src/companion/
  harness-web/                 固定移动适配源码
  third-party/                 新复用模块许可证
  scripts/proot-loader-info.py 已使用的构建主机辅助脚本
build-materials/               固定下载输入、PRoot 脚本/补丁、输出摘要
evidence/                      现有构建日志和失败的预览证据
research/                      历史静态报告、精简实物证据、公开来源索引
```

### 新增模块职责

| 文件（相对 `source/`） | 当前职责 |
|---|---|
| `android/.../companion/CompanionPolicy.kt` | endpoint/profile/scope 校验，草稿摘要、短期授权和恢复规则 |
| `android/.../companion/CompanionSecrets.kt` | Android Keystore AES-GCM 密钥存储；PBKDF2 + AES-GCM 备份 |
| `android/.../companion/CompanionStore.kt` | SQLite 会话/记忆/来源/修订/角色版本/任务；备份恢复 |
| `android/.../companion/GeminiClient.kt` | 原生 generateContent/SSE、thinking、工具 ID 配对、有限重试与 continuation |
| `android/.../companion/CompanionEngine.kt` | 串行任务、模型上下文与工具边界、记忆工具、回复/评论草稿 |
| `android/.../companion/CompanionPlugin.kt` | Capacitor 本机命令、状态、权限入口、系统文件保存回调 |
| `android/.../companion/CompanionDevice.kt` | 复用 DSH 无障碍树/动作；QQ/B站打开、短时截图、媒体导入和 UI 发送草稿 |
| `android/.../companion/QqNotificationBridge.kt` | QQ 系统通知与原生 RemoteInput；会话识别、去重、节流、可审阅回复 |
| `android/.../companion/ReplyLikelihood.kt` | 移植 MaiBot 的参与概率计算，增加硬性频率边界 |
| `android/.../companion/BilibiliReader.kt` | 参考 B站 MCP 的公开信息/字幕 WBI 读取，无登录 cookie 或风控绕过 |
| `android/.../companion/CompanionService.kt` | 可暂停的前台任务服务 |
| `src/companion/CompanionApp.tsx` | 对话、模型、角色、记忆、任务、设备六页及原 DSH 工作台入口 |
| `src/companion/{bridge,browser,characterCard}.ts` | 原生桥、明确标注的浏览器预览存储、角色卡 V2/V3 导入 |
| `src/companion/companion.css` | 助手界面与移动尺寸布局，尚无成功渲染验证 |

`android/...` 在上表缩写为 `android/app/src/main/java/io/deepseekharness/mobile`。另外修改了 MainActivity 插件注册、Manifest 服务声明、无障碍截图能力、原控制策略自身包名、Gradle、Capacitor 配置与 `src/main.tsx` 入口。原 DSH 工作台主体保留。

## 4. 接续时应直接处理的缺口

以下来自当前代码/日志，不能因编译通过忽略；本交接轮未修复：

1. **实际 UI 链尚未闭环。** `sendUsingUi()` 要求目标应用已在前台且可读目标文本，当前插件从本应用直接调用；尚未完成打开具体 QQ 会话、等待导航、稳定绑定目标及确认输入后的衔接。B站草稿目标是 BV，界面通常不显示 BV，现有检查会停止。不要删掉目标校验换取“能点发送”。通知回复路线也取决于 QQ 是否提供系统回复动作。
2. **主人身份未完成。** 当前只信任 `owner:local`；设置中的 `ownerQqId` 尚未形成可信远程绑定。QQ 身份依赖 shortcutId，有些版本/通知无法识别；没有实际账号隔离和 UI 收消息后备路线。不能把昵称或普通群文本当主人凭据。
3. **模型行为适配未收尾。** 新助手用原生 Kotlin Google 客户端；原 DSH rootfs 中的 pi-ai/Google SDK 仍是审计时版本，未完成 Argon 路由和 SDK 升级。不要声称两个工作台都已完成适配。新增 profile 的图片/视频/工具开关需要宿主侧实际校验；并行/流式工具参数、thought signature、多轮工具历史、切模型后的恢复需要协议夹具覆盖。
4. **上下文与记忆还不成熟。** 当前为最近消息截取、记忆提取工具和简单词匹配召回，没有成熟的语义压缩/自动维护流水线。模型 continuation、思考与工程摘要不能当关系记忆；不要把普通数据库持久化称作记忆质量验收。备份主动排除了密钥、媒体、wire 和授权，恢复及删除语义需要真实测试。
5. **任务恢复要检查。** 进程恢复对 running/approved/sending 有处理；queued 是否恢复进入可重试状态、取消与已提交发送的竞争、服务在媒体采集期间的生命周期均未验证。unknown 结果必须人工核对，不自动重放。
6. **移动界面未验收。** Vite 使用 4173。旧预览脚本用了 5173，最后截图是错误页；不含界面成功证据。移动 CSS 隐藏了侧边栏工作台入口，需要检查手机上是否仍能进入原 DSH。导入、导出取消、状态回显、返回、滚动、深色主题都需实际检查。
7. **B站内容理解未实测。** 公开字幕可能不可取得；短时 6 帧无音轨且不是全片；主动导入上限 12 MB。现有元数据与字幕来源标记不能替代模型实际理解。发布前需明确内容覆盖和实际目标。
8. **最终打包未完成。** 新 applicationId 已改，旧应用显示名/资源、独立签名、最终 license/NOTICE、PRoot 原生输出清单、ABI 与 16KB 页兼容、包权限/完整性尚待最终闭环。未生成可安装成品。

优先次序：读用户最新场景 → 保存本检查点 → 修真实收发/观察/恢复链 → 本地协议和持久化测试 → UI 与 APK 构建 → 用户许可范围内的实机/真实服务验收。不要重新开始泛泛框架调研；已有社区索引足够启动选择。

## 5. 社区复用状态

固定提交见 `source/docs/community-pins.json`；旧选型稿见 `source/docs/社区复用选型.md`，以本文的“已用/候选”区分为准。

- 已在新增代码实际参考/适配：MaiBot 的参与概率（GPL-3.0）、mem0 SQLite 事务历史/范围思路（Apache-2.0）、bilibili-mcp-server 的公开 WBI 内容读取（MIT）。对应许可证在 `source/third-party/`，适配出处在文件头。
- Google `js-genai`：阅读原生协议/continuation 实现以编写 Kotlin 客户端；没有把整个 SDK 装进新客户端。旧 rootfs SDK 升级仍未做。
- AstrBot、Mobilerun、SillyTavern、NapCatQQ、sherpa-onnx：已查代码/许可，作为消息、Android 控制、角色卡、协议桥、语音的候选或接口参考；**没有全部安装或整套集成**。
- NapCatQQ 不能仅按“限制性许可”标签排除私人自用，也不能反过来宣称私人自用豁免所有条款。现阶段没有运行/复制其消息实现；如后续采用，按具体使用/交付关系核实明确条款。
- 本源码含 MIT 原底座及不同许可的新增适配，不可把组合产物笼统标成“全 MIT”。目前保留来源和许可证；最终 APK/源码交付前须收尾相应声明。没有建立商业服务或公开 APK Release。

## 6. 从干净 checkout 恢复

完整命令、固定输入及重建过程见 [build-materials/RESTORE.md](build-materials/RESTORE.md)。这里只列主流程：

```bash
git clone --branch dot/xiaoke-handoff-20261028 --single-branch https://github.com/snodgrasssanderfer-source/godot-test.git
cd godot-test/projects/xiaoke-android
# 先读 HANDOFF.md、RESTORE.md；工具和缓存建在独立目录。
cd source
# 用固定 pnpm 11.19.0 安装根 workspace，harness-web 已包含。
pnpm install --frozen-lockfile
pnpm run build
pnpm run android:sync
# 按 RESTORE.md 配置 SDK/NDK/JDK/Gradle 后，在 android/ 运行固定 Gradle。
```

原构建链：Linux x86_64、Node v24.19.0、pnpm 11.19.0、Temurin JDK 21.0.13+9、Gradle 8.11.1、AGP 8.7.3、Kotlin 2.0.21、Android platform 35_r02、Build Tools 35.0.0、CMake 3.22.1、NDK r29-beta4 (`29.0.14033849-beta4`)。编译/target SDK 35、min SDK 26、当前 ABI 仅 arm64-v8a；Android16 运行兼容性尚未实测。

原机器使用了 `JAVA_HOME`、`ANDROID_USER_HOME`、`PNPM_HOME`、`XDG_DATA_HOME`、`XDG_CACHE_HOME`、`ANDROID_NDK_HOME`、`OPERIT_BUILD_DIR`、`OPERIT_ANDROID_RUNTIME_ABIS`、`OPERIT_ANDROID_RUNTIME_COMPONENTS`、`DSH_LOADER_INFO_PY`。不要覆盖 HOME。环境机器名和原绝对路径不是恢复依赖。

Gradle 原先忽略代理环境变量且不信任执行环境 CA，已通过**独立 Gradle 缓存目录**的 `gradle.properties` 设置 `systemProp.http.proxyHost/proxyPort`、HTTPS 对应字段、`systemProp.http.nonProxyHosts` 和 `systemProp.javax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts` 后编译成功。只交接参数名和做法，不上传旧代理值/认证。新环境按实际配置，不能禁用 TLS 校验。AGP 的库模块原先请求 Build Tools 34，现由 `android/build.gradle` 统一到已安装 35.0.0。

## 7. PRoot 的准确状态

原发布 APK 解压 runner 为 `07ef0b01a1506a364c9db3e5fe6ac9a530974371f9838bc7a5c6abb5097b4c78`，旧 NOTICE 声称 `cb40a1ced11cee76569b4008a9e478c87883ce831152be4eb9570763b82e580d`；差异不是 rootfs/release manifest 字段，也不是把 ZIP 压缩流误当 ELF。CI 重建和绝对源码路径有证据，但未证明旧二进制逐字节等价。

开发轮已从固定 Operit2/Termux PRoot/talloc 源码重建。主机改动只有独立 `OPERIT_BUILD_DIR` 和用 Python 读取 loader 符号，避开本机 awk 差异；已用脚本、补丁、输入/输出清单均保存在 `build-materials/`。

- 本次新 runner：257088 字节，SHA-256 `caa5d7d65952b258b3427322448db91b5eebea4aa72ab66c77174b86c8437f94`。
- loader：1632 字节，SHA-256 `f149774236db1e69b36cc1e4ed3866c7094db2eee52da0d4956aa63a9bb26929`。
- 本次没运行 runner/rootfs。主机源码路径会进入 runner，换构建路径可能改变哈希；清单描述本次产物，不承诺其他路径字节一致。
- 最终 APK 解出的原生文件摘要、来源 NOTICE、对应源码材料和设备行为还未闭环。不能退回未经解释的旧预编译 runner 冒充新可信依赖。

## 8. 配置、签名与验收权限

新 applicationId `io.github.dshcompanion.mobile`；namespace 仍 `io.deepseekharness.mobile`；版本 `0.1.0-companion` / code 1。独立包名避免覆盖原 DSH 数据。现有 Gradle 仍沿用上游 `keystore.properties` 入口和签名检查文案，**没有附带或创建签名密钥**；需要为私人包选定新的持久签名并安全保管，不能借用上游签名。

模型密钥只由用户安装后在本机输入，Keystore 实现已写但未实机验证。聊天中暴露的旧密钥不得找回、复制、存储或使用。代码、报告、备份与日志都不得内置它。

下一轮真实验收至少覆盖：安装启动与配置取消/返回；模型文本/工具/图像或视频；同一人格跨模型；主人与群权限不串；不同会话记忆隔离与更正删除；重启/断网恢复；加密备份恢复；QQ 具体目标收发；B站按证据评论；Android16 前台/后台/锁屏行为。先用本地 fixture 验证能验证的逻辑，清楚标出平台/实机未测。

须另获用户明确范围后再做：调用实际中转及费用/次数预算、登录 QQ/B站、读取真实私人会话、真实消息/评论发布、安装到用户设备。用户将来可以给有界持续授权，但普通群消息不能授予它。

## 9. 本次 Git 上传范围与未上传项

只新增 `projects/xiaoke-android/`，使用独立 `dot/xiaoke-handoff-20261028` 分支；不修改 main、游戏、Claude 或既有 release 分支，不建立公开 Release。

上传：完整源码检查点、必要许可、固定基线/子模块/下载摘要、可重新取回构建输入的脚本、PRoot 已用重建脚本/主机补丁、当前构建日志、干净历史研究及公开来源索引、明确失败的本地预览证据。

未上传：API 密钥、cookie、登录数据、原用户截图、私人记忆、交付服务 ID/页面元数据、原 APK、rootfs 二进制、重建 runner 二进制、SDK/NDK/JDK/Gradle 缓存、node_modules、生成的前端/Android build 目录、签名材料。大型公开输入可按固定 URL+SHA256 重新下载；不是丢失的源代码。

本次已恢复全部预期项目源码及已有日志。原机器 `.git` 历史没有作为二进制包上传，基线与本地检查点 commit 已记录；交接 Git 本身包含完整可继续修改的文件树。未读到的 `PersonaAndScenarios`、未建立的设备/API测试和未完成 APK 不在“已交付成果”之内。

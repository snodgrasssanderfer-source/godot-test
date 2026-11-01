# cccodroid 审计（v0.5.16 / 23820ae）

上游代码只克隆到仓库外的 `/root/cccodroid` 阅读，这次没有拷进本仓库。下文行号都以 23820ae 为准。
"实测"指在云端用本机 Claude Code 2.1.316，对着本地假 Anthropic 接口抓包看到的结果；没有用真实模型和真机。

## 1. 架构

**结论：三层，各管一段。**
- **Java 外壳**：一个 WebView 加一个前台服务。前台服务拉起 Node 网关，并负责安装工具包、自更新和 Shizuku。
- **Node 网关**（`gateway/server.mjs` 加 `gw/*.mjs`）：每条消息起一个 `claude -p` 进程，用 `--resume` 接上会话。进程的 stream-json 输出被翻译成 SSE 发给界面。
- **网页前端**（`gateway/web`）：普通的 JS/CSS，靠 `/api/*` 和 `/api/events` 与网关通信。

**一条消息走过的路径：**
1. 输入框：`web/app/05-composer.js:78` 发出 `POST /api/chat`。
2. 网关入列：`gw/queue.mjs:322` 处理请求，交给 `enqueue()`（`:276`）排队。
3. 起进程：`gw/run.mjs` 的 `startRun()` 组装参数（`:170` 起），在 `:244` 用 `spawn` 经 glibc 加载器拉起 claude。
4. 写入用户消息：消息以 stream-json 写进 claude 的 stdin。
5. 读输出：`run.mjs:488` 读 stdout，`gw/stream.mjs:110` 的 `translate()` 翻译事件，经 `gw/bus.mjs` 的 `publish()` 广播。
6. 回到界面：`web/app/04-session-flow.js:494` 用 `EventSource("/api/events")` 收事件并渲染。
7. 收尾：一轮结束时广播 `run_end`（`queue.mjs:170`）。

**界面怎么加载：** WebView 只加载 `http://127.0.0.2:8791`（`android/java/cc/codroid/Env.java:9`、`MainActivity.java:1608`）。网关用令牌和按次签名鉴权（`gw/access.mjs`）。

**数据存在哪里**（都在 `CFG.home`，即 `/data/data/cc.codroid/files/home`）：
- 会话：Claude Code 自己的 `~/.claude/projects/<cwd>/*.jsonl`（`gw/config.mjs:44`）。会话名、会话各自选的型号另存 `ccapp-session-names.json` 和 `ccapp-session-models.json`（`gw/sessmodel.mjs:18`）。
- 项目：`ccapp-projects.json`（`gw/projects.mjs:19`）。一个项目就是一个目录，外加默认的服务商、型号、思考强度、权限（`projects.mjs:49`）。
- 服务商和密钥：`ccapp-providers.json`，权限 0600（`gw/providers.mjs:13`）。密钥不放进环境变量，通过 `apiKeyHelper` 从 0600 文件读（`gw/claude.mjs:446`）。
- 设置：`ccapp-settings.json`（`config.mjs:41`）。

## 2. 系统提示词

**结论：** 能把默认的编程助手提示整体换成角色人设，工具调用照常。最省事的做法是项目级 agent，不用改上游。要注意两点：上游每轮会追加约 13 KB 的开发工具说明；会话的系统提示会被快照固定下来。

**上游现在传的参数**（`run.mjs:170-225`）：
- `-p`、stream-json 输入输出、`--replay-user-messages`、`--include-partial-messages`；
- `--permission-mode`、`--allow-dangerously-skip-permissions`、`--permission-prompt-tool stdio`；
- 按需加 `--model`、`--resume`、`--mcp-config`（设备工具）、`--effort`、`--allowedTools`/`--disallowedTools`、`--add-dir`；
- `--settings` 里带 outputStyle、钩子和 apiKeyHelper（`claude.mjs:446-467`）。

追加提示是 `--append-system-prompt`，内容由 `run.mjs:204` 拼成：TOOLS.md（13046 字节）、本机实况、任务清单提示（只给第三方模型）、HTML 预览提示、会话完整性提示、建档规则。这段没有设置开关能关掉。CLAUDE.md 由 Claude Code 自己发现，上游不经手。

**实测结果**（每项都抓了请求体）：

| 做法 | 系统提示长度 | 人设在哪里 | 工具 |
|---|---|---|---|
| 默认 | 27560 字 | — | 12 个 |
| `--system-prompt-file` | 333 字 | 只剩一行 SDK 身份加人设 | 12 个，Bash 调用正常跑完两轮 |
| 项目 `.claude/settings.json` 写 `{"agent":"xiaoke"}`，加 `.claude/agents/xiaoke.md` | 332 字 | 人设成为系统提示 | 12 个，Bash 调用正常 |
| agent 方案再加上游的 `--append-system-prompt` | 381 字 | 人设加追加段；身份行变成 "You are Claude Code, Anthropic's official CLI…" | 正常 |
| output style（`keep-coding-instructions: false`） | 24247 字 | 人设被放进用户消息，系统里仍是通用代理提示 | 不算真正替换 |

**另外测到的四点：**
- **快照：** `--system-prompt-snapshot` 默认开。第一次请求时把系统提示记下，之后 `--resume` 一律沿用，即使命令行传了新的人设也不换，直到会话被压缩。实测：A 人设开的会话，用 B 人设 resume，请求里仍是 A；加 `--system-prompt-snapshot off` 才换成 B。所以接班要开新会话，不要在旧会话里换人设。
- **CLAUDE.md** 放在第一条用户消息的 system-reminder 里，不在系统提示里。父目录的 CLAUDE.md 也会被加载（实测）。
- **CLAUDE.md 引用：** `@../外部文件` 在 `-p` 模式下不会展开；项目内的 `@./文件` 可以展开（实测）。
- **身份前缀：** 不论换成什么提示，前面都固定有一行 Claude 身份句。哈基米和 DeepSeek 的人设里要自己写清这一点。

## 3. 服务商

**结论：**
- **DeepSeek 可以直接用。** 内置预设：`https://api.deepseek.com/anthropic`，Bearer 认证，型号 deepseek-flash 和 deepseek-v4-pro（`providers.mjs:64-71`）。不带密钥探测返回 401，说明端点在线。带密钥的真实对话没有测。
- **主人的 Gemini 中转两条路都能接。** 要先确认中转开没开 Anthropic 格式的 `/v1/messages`。中转这次没有测，因为没有密钥。
- **额度事件可以触发接班，但要换个信号。** `rate_limit_event` 只在订阅账号下有；API Key 和第三方接口要看 429。

**Gemini 中转的两条路：**
- **走自定义 Anthropic 兼容端点**（推荐先试）：
  - 中转要提供 `/v1/messages`，并完整支持流式事件、tool_use / tool_result 和 Claude Code 的 beta 请求头。new-api 这类中转有"Claude 格式"入口，主人的中转开没开要实测。
  - 网关会自动给自定义端点包一层流式补尾（`claude.mjs:226`），会自动去掉地址末尾的 `/v1`（`providers.mjs:387`）。
  - 第三方型号会自动打开未知窗口放宽和任务清单工具（`claude.mjs` 中 claudeEnv 的第三方分支）。
- **走 openai-bridge 组件：**
  - 这是社区组件仓库 cccodroid-components 的组件。网关只负责在本轮开始前把它拉起来（`gw/managed-bridge.mjs:340`），条件是该接口的地址恰好是 `http://127.0.0.1:端口`。
  - 需要的是中转的 OpenAI 兼容 `/v1/chat/completions` 地址、密钥和型号名。
  - 已知问题第 7 条：转成 OpenAI 协议后，工具结果里的图片会丢；要勾选"图片随消息发"。
  - 组件是按 cc.codroid 的工具包构建的。我们改了包名，就得自己重新构建，或者另跑一个转换进程，当作普通自定义端点接进来。

**额度和接班信号：**
- 上游的 `noteRateLimit` 只在登录态下记 `unifiedWindows`（`stream.mjs:252-283`），只用来显示额度，不触发任何动作。
- 2.1.316 二进制里的结构：`rate_limit_info.status` 是 allowed / allowed_warning / rejected 之一，另有 `rateLimitType`、`resetsAt`。assistant 消息上有内部字段 `api_error`，可取 `usage_limit_reached`。
- 实测 API Key 收到 429 时：不发 `rate_limit_event`，只发 `error:"rate_limit"`、`api_error_status:429` 的 assistant 事件，以及 `is_error:true` 的 result。订阅账号下统一限额头怎么变成事件，没有真账号测不了。
- 接班的判定建议这样：`rate_limit_event.status=rejected`、assistant 事件的 `api_error=usage_limit_reached` 或 `api_error_status=429`，三者任一出现就触发。

## 4. 扩展点和同步方案

**结论：** 四项功能大部分可以放进新增文件。上游原文件预计改约 6 处，每处一两行，集中在 `server.mjs`、`index.html`、`Env.java`、`Updater.java`、`AndroidManifest.xml`、`run.mjs`。

- **三个角色、共用世界书：** 不需要改代码。
  - 每个角色是一个项目目录，例如 `workspace/roles/xiaoke`。
  - 项目默认服务商分别设成账号登录、Gemini 中转、DeepSeek（`projects.mjs:52`）。
  - 人设放在 `.claude/agents/<角色>.md`，用 `.claude/settings.json` 的 `agent` 指定。
  - 世界书放在父目录 `roles/CLAUDE.md`，三个角色共用。这三样都已实测生效。
  - 角色自己的记忆写在角色目录下的 CLAUDE.md 或记忆文件里。同一个 cwd 下的所有会话（App 私聊、QQ 私聊、QQ 群）都会加载，所以天然共享。
  - 人设和世界书是私人内容，部署到手机的私有目录，不入库。
- **模型接班：** 新增 `gateway/gw/xk-handover.mjs`。
  - `build.sh:35` 会自动打包 `gw/*.mjs`，构建脚本不用改。
  - 用 `bus.mjs:27` 的 `onPublish` 监听上面那几个信号。触发后用 `enqueue()` 在下一个角色的项目里开新会话，把原消息和最近几轮的摘要一起带过去。
  - 要改上游 `server.mjs`：加一行 import，并把路由加进 `ROUTES`（`:89`）。
  - 如果要让信号更准，还要在 `stream.mjs` 的 translate 里多广播一个事件（再改一处）。
- **QQ 接入：** 新增 `gw/xk-qq.mjs`，常驻在网关进程里。
  - 用 Node 的 `tls.connect` 加 `checkServerIdentity` 固定证书指纹，自己实现 wss；Node 自带的 WebSocket 不能固定证书。
  - 收到消息：映射到角色项目和会话，调 `enqueue()`。
  - 发出回复：从 `onPublish` 收到的事件里拼出回复正文。
  - 网关进程被杀时 QQ 也会断线，消息要自己落盘、重连后补回。
- **前端外观：**
  - 新增 `web/xk.css` 和 `web/xk.js`，在 `index.html` 的 `style.css` 后面和 `app.js`（`:982`）后面各加一行引用。
  - 样式全靠 CSS 变量和 `html[data-skin]` 驱动（style.css 里有 1291 处变量）。新增一套皮肤还要改 `theme.js:35` 和 Java 的 `skinPref`，所以优先只覆盖变量。
  - 不需要的入口（终端、组件页等）用 CSS 隐藏，不删代码。
- **其他必改的上游原文件：**
  - `Env.java`：端口（见第 5 节）；
  - `Updater.java:41`：仓库地址；
  - `AndroidManifest.xml`：包名、图标、应用名；
  - `run.mjs:204`：要不要再追加 13 KB 的 TOOLS.md，建议给角色项目跳过，否则人设被工具说明冲淡，每轮还多花 token。

**同步方案：**
- 上游节奏：10-18 以来，每天发 2～5 个版本，一周 246 个提交。改得最频繁的是 `web/app.js`、`server.mjs`、`MainActivity.java`、`run.mjs`、`queue.mjs`。
- 建议的做法：
  1. 在我们这边建 `upstream` 镜像分支，只跟上游的 tag。
  2. 补丁只碰上面约 6 个文件，每一处都用 `// xk:` 标记，并单独存一份 patch 清单。
  3. 每周挑一个上游 tag 做一次 rebase，用 `git rebase --onto`，冲突只会落在那几行。
  4. 同步后跑上游自己的 `node scripts/test.mjs`，再跑我们自己的接班和 QQ 测试。
  5. 打包时用 `build.sh` 加我们的签名。

## 5. 包名和共存

**结论：**
- **新包名必须正好 10 个字符**，例如 `app.xiaoke`。原因：工具包里写死的路径是 `/data/data/com.termux/`，要逐字节等长替换（`devkit/devkit.mjs:387`、`android/assets/bin/pkg.mjs:33`）。
- **能和原版 cccodroid、旧版小克同时装在一台手机上。** 前提是改掉网关端口，并且自己构建工具包。

**为什么要自己构建工具包：**
- 工具包下载回来时已经按 cc.codroid 改写好了路径，在 `devkit-pin.json` 里钉死。
- 首装和更新时都会核对 `target` 是否等于本应用包名，不一致就拒绝安装（`Updater.java:320`、`:526`、`:599`、`:1066`）。
- 所以改了包名，就得用 `devkit.mjs build --target <新包名>` 自己出工具包（整包 gz 约 687 MB），连同 `libldlinux.so` 一起发布到自己的 Release。
- 上游的社区组件按工具包的 sha256 匹配，都不能用了；需要的组件要自己重新构建。

**端口冲突：**
- 网关端口写死为 8791，界面地址是 127.0.0.2（`Env.java:9`、`access.mjs:38`）。
- 两个 App 同时运行时，后起的那个连不上自己的网关，会把对方判成"端口被占"，自己的网关不再启动（`GatewayService.java` 的 `squatted()`）。必须改端口。
- 两个 App 的 WebView Cookie 各自隔离，不会互串。
- 旧版小克的本地服务都用随机端口（`DeviceBridgeServer`、`XiaokeMcpServer` 的 port 都是 0），不冲突。

**还写死了 cc.codroid 的地方**（都有环境变量覆盖，但默认值要改）：
- `Env.java:15` 的令牌路径；
- `device-mcp.mjs:11`、`priv.mjs:6`、`gw/usage.mjs:382`、`gw/files.mjs:141` 的 priv 套接字。

**自更新：** 它从 `greyoak111/cccodroid` 拉新版本（`Updater.java:41`）。上游的 APK 会因为包名或签名不符被拒装（`:856`），不会被装进来，但会一直提示有新版。要改成指向我们自己的 Release，或者关掉。

## 6. 影响我们的问题（这轮不修）

| # | 位置 | 影响 | 严重程度 |
|---|---|---|---|
| 1 | `Env.java:9` | 端口 8791 写死，和原版同时运行时网关起不来 | 高（共存必修） |
| 2 | `Updater.java:41/320` | 改包名后，上游工具包和组件全都装不上；首装就会失败 | 高（必须自建工具包） |
| 3 | `GatewayService.java:126`，known-issues 第 6 节 | 厂商冻结前台服务挡不住：红魔约 90 秒，ColorOS 迟早被杀，vivo/OriginOS 没有实测，代码里也没有 vivo 专门处理。网关被冻结时 QQ 跟着断，接到一半的消息会丢 | 高 |
| 4 | `run.mjs:244` | 每条消息起一个新的 claude 进程再 `--resume`，冷启动加读整份会话文件，QQ 回复的首字慢 | 中 |
| 5 | issue #69、#70、#71、#72 | 长对话：切回会话时整轮重放、逐条强制排版导致卡顿；子代理历史绕过懒渲染；导入的会话同步扫描全部记录，阻塞网关 | 中 |
| 6 | `run.mjs:204` | 每轮固定追加 13 KB 开发工具说明，冲淡人设，也增加费用 | 中 |
| 7 | `stream.mjs:270` | 额度只在登录态下记录，只用来显示；第三方接口的 429 没有统一信号 | 中（接班要自己补） |
| 8 | known-issues 第 2 节 | WebView 触摸滚动冻住，只有绕过办法（长按标题重建页面） | 中 |
| 9 | known-issues 第 8 节 | 三星、Android 16 上曾卡在"等待网关…"两三分钟，根因没查清 | 低～中 |
| 10 | `GatewayService.java:69` | 部分唤醒锁不设超时、一直持有，耗电 | 低 |
| 11 | `AndroidManifest.xml:9` | 默认 targetSdk 28；只认 36 的系统要装 `-36` 包，走"经加载器"方式运行 | 低（我们直接出 36） |
| 12 | known-issues 第 7 节 | 经 OpenAI 协议转换时，工具结果里的图片会丢 | 低（哈基米走桥时才有影响） |

**Shizuku：** 无线调试激活的配对码靠通知输入，重启后自动重新激活要求连着 Wi-Fi（`ShizukuSetup.java`）。没有找到 vivo 专门处理；我们的四项功能不依赖 Shizuku。

**Android 16：** 有一加 Ace 5 实测通过的记录（issue #61）。前台服务类型是 specialUse，没有 6 小时上限（`AndroidManifest.xml:157`）。

## 7. 构建

**结论：** 在云端照 CI 的方式编出了 targetSdk 28 和 36 两个 APK，每个 15 MB，包名 cc.codroid，versionCode 102，用测试密钥签名，`apksigner verify` 通过。

**需要的东西：**
- 工具链：JDK 17 以上（实测用的是 21）、Android SDK build-tools 35.0.0、platform android-35、NDK 27.2.12479018、Node 22。项目不用 Gradle，`scripts/build.sh` 直接调 NDK clang、javac、d8、aapt2、zipalign、apksigner。
- 预编译文件：仓库里自带 `android/prebuilt/glibc/ld-linux-aarch64.so.1`。构建时会联网下载 `devkit.lock.json`，并按 `devkit-pin.json` 核对 sha256，然后改写出 `lib/arm64-v8a/libldlinux.so`。工具包本体（687 MB）不进 APK，首次启动时才下载。
- 签名：二选一。正式密钥是 `KEY` 指向的 `{key, cert}` JSON；自测用 `KS` 指向的 keystore。正式包的证书 SHA-256 写在 CI 里校验。

**会失败的地方：**
- 本身没有失败的步骤。
- 如果换包名，`devkit.mjs ldso --target` 和首装下载的工具包都要换成自建版本，否则装好以后工具包装不上。
- 编出来的包这次没有装到手机上验证。

## 8. 许可

**结论：** 可以闭源或公开地二次开发。要做到以下几点：
- 保留上游的 `LICENSE`（Apache-2.0）和 `NOTICE` 全文；
- 在我们自己的 NOTICE 里另加一句"基于 cccodroid 修改"；
- 改过的文件写明已修改；
- 原样随附 `ThirdPartyLicenses/`（pdf.js、KaTeX、xterm.js、eruda、highlight.js、Archify、ccmono）、`third_party/shizuku/LICENSE` 和 `notices/notices.json`；
- 自建工具包要附上各 Termux 包的许可和 SBOM（上游 NOTICE 里也这样写明）。

**名字和图标：** 建议都换掉，也不要用"cccodroid"作为产品名。
- Apache-2.0 第 6 条不授予商标权。
- 上游 README 第 182 行说明项目名、图标、包名都避开了 Claude 和 Anthropic 的标志。我们同样不能在名字和图标上使用 Claude 或 Anthropic 的商标。
- 小克的应用名和图标用我们自己的。界面里"关于"一栏注明"基于 cccodroid（Apache-2.0）"。

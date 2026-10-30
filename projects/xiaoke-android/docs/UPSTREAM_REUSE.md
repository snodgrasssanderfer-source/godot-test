# 实际复用与本轮来源

下面前半部分记录已有代码复用；本轮新增候选不能因出现在列表就称为采用。每项落地需补固定 commit、真实源文件、许可、修改位置、效果与测试证据。

# 已采用社区模块与边界

用于私人自用构建。原 DSH 底座保留，未安装多套完整助手。下表的“移植”表示已有实际接入代码，不能据此称平台或模型已经实测。

| 项目 / 固定版本 | 实际接入 | 体验与证据 | 许可 |
|---|---|---|---|
| [DSH Android](https://github.com/dphmoblie/deepseek-harness-android) v0.3.0 / `e9be0e43629f8b744991c2cf9efd160ca7d41abc` | 原 App、runtime、无障碍、Shizuku、终端、文件、插件与守护服务 | 保留原工作台入口；浏览器手机尺寸往返测试通过；原生运行时未在手机启动 | MIT，随带原生和 rootfs 各自许可 |
| [AstrBot](https://github.com/AstrBotDevs/AstrBot) `abf683071952375ec36baa4cad6b6f3d9c9bb03f` | aiocqhttp 适配器的分段解析、私聊/群聊分发移植到 OneBotProtocol / OneBotBridge | 数字账号/目标隔离、类型化文本发送、ACK 与不明状态；消息与图片经独立 NapCat 通道，不抢手机 QQ | AGPL-3.0 |
| [MaiBot](https://github.com/Mai-with-u/MaiBot) `6bcb2e068cd98793a132cb8cc4ebf3cc00cb8fc3` | 回复概率逻辑移植到 ReplyLikelihood；ParticipationPolicy 为本项目显式范围约束 | 安静/被提及/自然参与、冷却和上限；本地策略测试通过，真人群聊未测 | GPL-3.0 |
| [mem0](https://github.com/mem0ai/mem0) `26cdefb46600c0fa5953c4b5ee5003c125539fc4` | SQLiteManager 记录与修订历史改为 Android SQLite 的 CompanionStore | 分域记忆、来源、更正删除、备份；MemoryRanking 是本项目中文词项检索，并非已接入 mem0 embedding 云 | Apache-2.0 |
| [Bilibili MCP](https://github.com/iseenope/bilibili-mcp-server) `20eedd09862aea2c160163007f45221c544651b2` | src/api/bilibili.ts、wbi.ts 的公开信息、字幕和 WBI 算法移植到 BilibiliReader | 字幕时段筛选；系统视频解码补充关键帧；工具创建带依据草稿，提交由宿主授权。未登录/调用真实账号接口 | MIT |
| [Google js-genai](https://github.com/googleapis/js-genai) `00cfd86270cacf9b1c223b193bded2fade6faa7f` | GeminiClient / GeminiWire 对原生协议、thought signature、continuation 的 Android 适配 | 本地真实 TLS/SSE fixture 验证，未调用中转；工具回复保留原始签名配对，模型切换只保留可见对话 | Apache-2.0 |
| [SillyTavern](https://github.com/SillyTavern/SillyTavern) `06bde939fb1e9c4c8d8641d810f0a916b5bce127` | CharacterCard JSON/PNG v2/v3 格式兼容，在 characterCard.ts | 人设导入/编辑/版本管理；未搬运整套 SillyTavern 服务，不自动写定用户人格 | 格式兼容；原项目 AGPL-3.0 |
| [NapCatQQ](https://github.com/NapNeko/NapCatQQ) `ba0a53dd372dbc4c4bfc83cf7132997459605210`，已固定部署 v4.18.41 | 独立 OneBot v11 服务互操作；核对 SendMsg、GetImage 与消息 schema | Android 已有 WSS 配置、账号握手、目标白名单、图片入口；官方固定镜像已完成本机、无登录 WebUI / 原生模块启动测试并停止；真实托管与登录未做 | Limited Redistribution License；不在 APK 搬运其代码 |

| [AIRI](https://github.com/moeru-ai/airi) `71b3c178ea6a6b828821027188f6b5f7fa56b788` | 原版眼动间隔、情绪映射、元音驱动及适配后的角色绘制，接入 PresencePanel 和安卓浮窗 | 6 项宿主 React 测试与浏览器场景通过；用户本地 PNG 持久化；未打包完整 Live2D/VRM 加载器或上游角色素材 | MIT |
| [Open-LLM-VTuber](https://github.com/Open-LLM-VTuber/Open-LLM-VTuber) `992309c0aa19845960228f880013d4685fde93b5` | MIT 后端分句、播放身份/打断状态映射到 Java/Kotlin voice 核心与 CompanionPresenceController | 共用同一模型队列；系统 ASR/TTS、实际合成音量驱动角色；单元测试与 Android 编译通过；扬声器半双工，手机音频实测未做 | MIT 后端，未采用附加商业条款前端 |

两项已完成源码合并，来源细节见 COMMUNITY_REUSE-AIRI.md / COMMUNITY_REUSE-VOICE.md；这些独立交付文档记录分支当时的测试边界，本轮停止状态以 PROGRESS 和 CLAUDE_HANDOFF 为准。

Mobilerun 仅作为原有设备控制对照，不替换已运行的 DSH 权限桥。sherpa-onnx 尚未打包模型；先接 Android 原生语音能力，离线模型需明确体积、语言和许可后再选择。deslop 的小克资料只作为可选来源索引，不覆盖用户私人人设和后续创作。

第三方完整许可随 APK 放入 assets/legal/licenses。新增组合部分按 AGPL-3.0 提供；原始文件的许可证与署名保留。对应修改源码、固定依赖与构建材料现已脱敏保存到本独立公开分支；私人场景全文、凭据与签名私钥没有上传。当前没有新 APK。

## 本轮精确候选地址

- https://github.com/moeru-ai/airi — 新增采用与固定版本待所属模块核对。
- https://github.com/Open-LLM-VTuber/Open-LLM-VTuber — 新增采用与固定版本待所属模块核对。
- https://github.com/Mai-with-u/MaiBot — 新增采用与固定版本待所属模块核对。
- https://github.com/AstrBotDevs/AstrBot — 新增采用与固定版本待所属模块核对。
- https://github.com/NapNeko/NapCatQQ — 新增采用与固定版本待所属模块核对。
- https://github.com/iseenope/bilibili-mcp-server — 新增采用与固定版本待所属模块核对。
- https://github.com/droidrun/mobilerun — 新增采用与固定版本待所属模块核对。
- https://github.com/mem0ai/mem0 — 新增采用与固定版本待所属模块核对。
- https://github.com/SillyTavern/SillyTavern — 新增采用与固定版本待所属模块核对。
- https://github.com/k2-fsa/sherpa-onnx — 新增采用与固定版本待所属模块核对。
- https://github.com/googleapis/js-genai — 新增采用与固定版本待所属模块核对。
- https://github.com/pipecat-ai/pipecat — 新增采用与固定版本待所属模块核对。
- https://github.com/getzep/graphiti — 新增采用与固定版本待所属模块核对。
- https://github.com/langchain-ai/langgraph — 新增采用与固定版本待所属模块核对。
- https://github.com/livekit/agents — 新增采用与固定版本待所属模块核对。
- https://github.com/livekit/client-sdk-android — 新增采用与固定版本待所属模块核对。
- https://github.com/Petterpx/FloatingX — 新增采用与固定版本待所属模块核对。
- https://github.com/rive-app/rive-android — 新增采用与固定版本待所属模块核对。
- https://github.com/BerriAI/litellm — 新增采用与固定版本待所属模块核对。
- https://github.com/tink-crypto/tink-java — 新增采用与固定版本待所属模块核对。
- https://github.com/sqlcipher/sqlcipher-android — 新增采用与固定版本待所属模块核对。
- https://github.com/MaaXYZ/MaaFramework — 新增采用与固定版本待所属模块核对。
- https://github.com/Aliothmoon/MaaFwApp — 新增采用与固定版本待所属模块核对。
- https://github.com/pedroSG94/RootEncoder — 新增采用与固定版本待所属模块核对。
- https://github.com/aioqzone/aioqzone — 新增采用与固定版本待所属模块核对。
- https://github.com/Zhalslar/astrbot_plugin_qzone — 新增采用与固定版本待所属模块核对。
- https://github.com/coleifer/huey — 新增采用与固定版本待所属模块核对。
- https://github.com/square/okhttp — 新增采用与固定版本待所属模块核对。
- https://github.com/androidx/media — 新增采用与固定版本待所属模块核对。
- https://github.com/python-pillow/Pillow — 新增采用与固定版本待所属模块核对。
- https://github.com/srikanth-lingala/zip4j — 新增采用与固定版本待所属模块核对。
- https://github.com/apache/tika — 新增采用与固定版本待所属模块核对。

已知设计约束：Graphiti 删除摘要失败可能留下旧文本，必须统一 tombstone/删除屏蔽；LiteLLM 未知价格可能跳预留，不可只开 budget 便宣称严格金额限额。MaaFwApp 的特权依赖不自动授权提权。普通悬浮层不能保证任意位置全局五秒手势。Qzone 非官方协议需独立授权与故障边界；相关插件 GPL-3.0。不要将数套完整服务框架塞进手机。

## 本轮主线追加采用

- Apache Commons Compress 1.27.1（https://github.com/apache/commons-compress ，Apache-2.0）：沿用 DSH 已固定的库，实际调用 ZipArchiveInputStream/canReadEntryData 读取 ZIP；接入 companion/documents/DocumentText.kt。没有另装 Zip4j 或复制第二套解压引擎。小克新增 100 条目/16 MB 展开/单条 4 MB 限制、路径及符号链接拒绝、非文本覆盖标注；真实 ZIP 单测通过。
- Android PdfRenderer.Page.getTextContents（https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page ，API 35）：实际接入所选 PDF 页文字提取，最多 12 页/100000 字符；不声称 OCR 或图片理解。编译通过，系统文件选择与 PDF 真机运行未测。

## 本轮停止检查点的实际新增复用

- A 已合入事务记忆、日记/兴趣、注意力、双账预算和 LifePanel。mem0 存储来源与 Graphiti/LiteLLM 只读设计参考的区别见 [CONTRACT-A](archive/CONTRACT-A.md)，后两者没有安装为框架。
- B 复用既有 DSH 权限桥、AIRI 浮球/角色适配、Open-LLM-VTuber 语音链，新增分轨采样、输入租约和来源失效清理。实际文件/版本/许可及边界见 [CONTRACT-B](archive/CONTRACT-B.md)，没有另装总控。
- C 沿用 AstrBot 衍生 OneBot 和 NapCat 协议互操作，增加后台队列、审批、密文同步；B站使用上述固定 MIT 项目的账号动作接口并作 Android/OkHttp 适配。见 [CONTRACT-C](archive/CONTRACT-C.md)、[C-SOCIAL-EXTENSION](archive/C-SOCIAL-EXTENSION.md)。aioqzone 仅比较阅读，未复制安装；NapCat 未打进 APK。

前面的候选列表保留检索入口，不表示全部采用。最终合并代码的验证缺口见交接文档，历史独立模块测试不能代替当前 APK 验收。

## 2026-10-30 同步 DSH 上游

- dphmoblie/deepseek-harness-android：从 v0.3.0（e9be0e43629f8b744991c2cf9efd160ca7d41abc）三方合并到 v0.3.7（c46af029d2e37f13aa5c1cb453deb1bf3cc74c31），MIT。199 个文件，2 处冲突（versionCode 保留本项目；gradlew.bat 取上游）。`promo/` 未合入。
- harness-web（dphmoblie/deepseek-harness-mobile-support）：5cb43dbcbabddac391afd0f947b771fb899582a1 → abefc709ebdd4aada322cde573f2684327bd661b。
- 网络安全配置没有跟随上游的“全局允许明文”，保持“默认禁止、只放行本机回环”，理由写在 `network_security_config.xml` 里；按上游 0.3.6 的分析，这份配置与 Shizuku 13.6.0 共进程时不会触发 `Found multiple conflicting per-domain rules`。
- Ubuntu 运行环境改用上游 v0.3.7 发布的 rootfs（上游 CI 按 `scripts/build-embedded-runtime.py` 生成），哈希见 build-materials/BUILD.md；文件不入库。


## 2026-10-30 群聊和私聊更像真人（`companion/chat/`）

| 机制 | 来源（固定版本） | 许可证 | 本项目文件 |
|---|---|---|---|
| 消息防抖（安静/活跃开批、同人/换人续发、裸图 4 秒、整批 8 秒、@ 1 秒） | Mai-with-u/MaiBot 2a9af62cec37035d29237acbe2cf3adceb700b27 `src/chat/debounce/controller.py` | GPL-3.0 | MessageDebouncer.kt、ReplyScheduler.kt |
| 动态回复频率（前馈 + 只收紧的反馈、40 秒虚拟轮次、离线阈值表） | 同上 `src/maisaka/turn_trigger/dynamic_gate.py`、`reply_likelihood.py` | GPL-3.0 | DynamicReplyGate.kt、ReplyLikelihood.kt（真实上下文特征） |
| 表达与黑话学习（场景→说法、黑话候选与推断、按次数加权） | 同上 `src/learners/`、`prompts/zh-CN/learn_style.prompt`、`learn_jargon.prompt` | GPL-3.0 | ChatLearner.kt |
| 追加消息合并 | AstrBotDevs/AstrBot abf683071952375ec36baa4cad6b6f3d9c9bb03f `astrbot/core/pipeline/process_stage/follow_up.py` | AGPL-3.0 | CompanionEngine.respond / runChat |
| 分段回复（句末切分、log 间隔） | 同上 `pipeline/result_decorate/stage.py`、`pipeline/respond/stage.py` | AGPL-3.0 | ReplySegmenter.kt、OneBotBridge.send |

只移植机制，没有逐行翻译 Python。分段规则与 AstrBot 相反：短回复保持一条，较长的才拆，最多四段。学习只读 QQ 群里别人的发言，按群分开存，主人私聊和其他私聊不进入学习。

## 2026-10-30 游戏代操作升级（`companion/devicev2/`、`shizuku/`）

| 机制 | 来源（固定版本） | 许可证 | 本项目文件 |
|---|---|---|---|
| Shizuku 用户服务里进程内注入触摸（隐藏 `InputManager.injectInputEvent` + `setDisplayId`），主屏和副屏通用 | DSH v0.3.7 `virtualscreen/VirtualScreenInjector.kt`（本仓库底座） | MIT | ShellGestureInjector.kt、GestureTimeline.kt（多指时间线是新写的） |
| 后台副屏：启动、截图、显示编号 | DSH v0.3.7 `virtualscreen/VirtualScreenService.kt`、`ShellVirtualScreen.kt` | MIT | ShizukuInput.kt 里的 BackgroundScreen |
| 路线重放：每步先等画面回到录制时的状态再动手，对不上就交还给模型 | droidrun/mobilerun 11dcdebdec9ea99f172dd7e80af7ba1eadbeb6c8 `mobilerun/macro/replay.py`（wait_for_pre_state → replay_action → handoff） | MIT | DeviceModule.replay |

只移植机制。mobilerun 用节点树比状态，游戏没有可用的节点树，这里改成画面指纹（64 位差值哈希）和标记模板。

**MaaFramework 没有接入。** 评估对象是 MaaXYZ/MaaFramework a6532c30dbfc639cf63fc269991a523184f3babf（LGPL-3.0）和 Aliothmoon/MaaFwApp 7e2dc7d6c5de3d4ed8d822291d38748f5c26d193（AGPL-3.0）。不接的理由：
- 它的识别和动作都由 Pipeline 资源驱动，资源要按 Project Interface V2 在构建时打进 APK（MaaFwApp 也是这样）。小克要玩的是主人临时说的任意游戏，没有现成资源，模型也不能在运行时写出可靠的 Pipeline。
- 原生库要通过 JNI 桥和一个特权助手进程接入，依赖 OpenCV、ONNX Runtime 和 OCR 模型；体积没有实测，但会在已有约 300 MB 的包上再加一层原生依赖，而这些在没有真机的构建环境里无法验证。
- 真正需要的“快眼”只有两件事：判断是不是同一个画面、在画面里找到一个按钮。用纯 Kotlin 做了（FrameMatcher.kt：灰度缩小到 320 宽，差值哈希判同屏，归一化互相关粗到细找模板），不需要原生库。OCR 仍交给多模态模型看图。

## 2026-10-30 记忆升级（`companion/memory/`）

| 机制 | 来源（固定版本） | 许可证 | 本项目文件 |
|---|---|---|---|
| 混合检索：语义 + sigmoid 归一化 BM25 + 实体加分，按启用的信号数相除 | mem0ai/mem0 26cdefb46600c0fa5953c4b5ee5003c125539fc4 `mem0/utils/scoring.py`、`memory/main.py` `_search_vector_store` | Apache-2.0 | MemoryRanking.kt、MemoryIndex.kt |
| 聊完再整理：只新增、关联已有记忆、按观察日期换算相对时间 | 同上 `mem0/configs/prompts.py` ADDITIVE_EXTRACTION_PROMPT | Apache-2.0 | MemoryConsolidator.kt、TimeAnchor.kt |
| 有效期：valid_at / invalid_at，只有新旧不能同时成立才让旧的失效，旧的保留 | getzep/graphiti 72b5c59c06607e09ea855be61b6ff5a3446fc951 `graphiti_core/prompts/extract_edges.py`、`dedupe_edges.py`、`utils/maintenance/edge_operations.py` | Apache-2.0 | LifeMemoryRepository.end、MemoryConsolidator |
| 档案（实体摘要，随新信息合并重写） | 同上 `graphiti_core/prompts/summarize_nodes.py` | Apache-2.0 | Profiles.kt |
| 本地中文向量模型 | BAAI/bge-small-zh-v1.5（Xenova ONNX 量化版 75c43b069aac4d136ba6bc1122f995fedcfd2781） | MIT | LocalEmbedder、WordPiece.kt |
| ONNX Runtime | com.microsoft.onnxruntime:onnxruntime-android 1.22.0 | MIT | LocalEmbedder |

没有引入图数据库，记忆仍存在原来的 SQLite 记录里；向量单独一张表，不进备份、不进提示词。分词按模型的 tokenizer.json（BertNormalizer 不转小写 + WordPiece）实现，单测与 HF tokenizers 0.23.3 的输出逐个比对。

## 2026-10-30 QQ 能力补全（`chat/QqExtras.kt`）

只按 NapNeko/NapCatQQ 49ecfe0f56c1e0649db6988444950216fd5fe792 的接口定义调用，不含它的代码（它的许可证限制再分发）。核对过的参数：`fetch_ptt_text {message_id}`、`set_msg_emoji_like {message_id, emoji_id, set}`（emoji_id 超过三位按 Unicode 表情）、`set_input_status {user_id, event_type: 1}`（仅私聊）、`send_poke {user_id, group_id?}`、`mark_msg_as_read {message_id | group_id | user_id}`。

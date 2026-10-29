# 已采用社区模块与边界

用于私人自用构建。原 DSH 底座保留，未安装多套完整助手。下表的“移植”表示已有实际接入代码，不能据此称平台或模型已经实测。

| 项目 / 固定版本 | 实际接入 | 体验与证据 | 许可 |
|---|---|---|---|
| [DSH Android](https://github.com/dphmoblie/deepseek-harness-android) v0.3.0，完整基线见上级 HANDOFF | 原 App、runtime、无障碍、Shizuku、终端、文件、插件与守护服务 | 保留原工作台入口；浏览器手机尺寸往返测试通过；原生运行时未在手机启动 | MIT，随带原生和 rootfs 各自许可 |
| [AstrBot](https://github.com/AstrBotDevs/AstrBot) `abf683071952375ec36baa4cad6b6f3d9c9bb03f` | aiocqhttp 适配器的分段解析、私聊/群聊分发移植到 OneBotProtocol / OneBotBridge | 数字账号/目标隔离、类型化文本发送、ACK 与不明状态；消息与图片经独立 NapCat 通道，不抢手机 QQ | AGPL-3.0 |
| [MaiBot](https://github.com/Mai-with-u/MaiBot) `6bcb2e068cd98793a132cb8cc4ebf3cc00cb8fc3` | 回复概率逻辑移植到 ReplyLikelihood；ParticipationPolicy 为本项目显式范围约束 | 安静/被提及/自然参与、冷却和上限；本地策略测试通过，真人群聊未测 | GPL-3.0 |
| [mem0](https://github.com/mem0ai/mem0) `26cdefb46600c0fa5953c4b5ee5003c125539fc4` | SQLiteManager 记录与修订历史改为 Android SQLite 的 CompanionStore | 分域记忆、来源、更正删除、备份；MemoryRanking 是本项目中文词项检索，并非已接入 mem0 embedding 云 | Apache-2.0 |
| [Bilibili MCP](https://github.com/iseenope/bilibili-mcp-server) `20eedd09862aea2c160163007f45221c544651b2` | src/api/bilibili.ts、wbi.ts 的公开信息、字幕和 WBI 算法移植到 BilibiliReader | 字幕时段筛选；系统视频解码补充关键帧；工具创建带依据草稿，提交由宿主授权。未登录/调用真实账号接口 | MIT |
| [Google js-genai](https://github.com/googleapis/js-genai) `00cfd86270cacf9b1c223b193bded2fade6faa7f` | GeminiClient / GeminiWire 对原生协议、thought signature、continuation 的 Android 适配 | 本地真实 TLS/SSE fixture 验证，未调用中转；工具回复保留原始签名配对，模型切换只保留可见对话 | Apache-2.0 |
| [SillyTavern](https://github.com/SillyTavern/SillyTavern) `06bde939fb1e9c4c8d8641d810f0a916b5bce127` | CharacterCard JSON/PNG v2/v3 格式兼容，在 characterCard.ts | 人设导入/编辑/版本管理；未搬运整套 SillyTavern 服务，不自动写定用户人格 | 格式兼容；原项目 AGPL-3.0 |
| [NapCatQQ](https://github.com/NapNeko/NapCatQQ) `ba0a53dd372dbc4c4bfc83cf7132997459605210`，已固定部署 v4.18.41 | 独立 OneBot v11 服务互操作；核对 SendMsg、GetImage 与消息 schema | Android 已有 WSS 配置、账号握手、目标白名单、图片入口；官方固定镜像已完成本机、无登录 WebUI / 原生模块启动测试并停止；真实托管与登录未做 | Limited Redistribution License；不在 APK 搬运其代码 |

| [AIRI](https://github.com/moeru-ai/airi) `71b3c178ea6a6b828821027188f6b5f7fa56b788` | 原版眼动间隔、情绪映射、元音驱动及适配后的角色绘制，接入 PresencePanel 和安卓浮窗 | 6 项宿主 React 测试与浏览器场景通过；用户本地 PNG 持久化；未打包完整 Live2D/VRM 加载器或上游角色素材 | MIT |
| [Open-LLM-VTuber](https://github.com/Open-LLM-VTuber/Open-LLM-VTuber) `992309c0aa19845960228f880013d4685fde93b5` | MIT 后端分句、播放身份/打断状态映射到 Java/Kotlin voice 核心与 CompanionPresenceController | 共用同一模型队列；系统 ASR/TTS、实际合成音量驱动角色；单元测试与 Android 编译通过；扬声器半双工，手机音频实测未做 | MIT 后端，未采用附加商业条款前端 |

两项已完成源码合并，来源细节见 COMMUNITY_REUSE-AIRI.md / COMMUNITY_REUSE-VOICE.md；这些独立交付文档记录分支当时的测试边界，主线最终状态以 TEST-RESULTS 为准。

Mobilerun 仅作为原有设备控制对照，不替换已运行的 DSH 权限桥。sherpa-onnx 尚未打包模型；先接 Android 原生语音能力，离线模型需明确体积、语言和许可后再选择。deslop 的小克资料只作为可选来源索引，不覆盖用户私人人设和后续创作。

第三方完整许可随 APK 放入 assets/legal/licenses。新增组合部分按 AGPL-3.0 提供；原始文件的许可证与署名保留。对应修改源码、固定依赖与构建材料以私有附件提供，不公开用户场景全文、凭据或签名私钥。

# Open-LLM-VTuber 实际复用与验证

宿主对齐e2a21d55e8def1803a9e578a73a91d05cfe89a73。固定后端992309c0aa19845960228f880013d4685fde93b5；路径、URL及blob SHA见docs/voice-audit/SOURCES.json。前端gitlink 06a659b114fff788cf0daaa86e484576db4975bf的build提交声明来源3d57a9a0125a0ca13e5d096f7092e4e494ed389e，只做源码追溯，未运行构建验证bundle复现。

源码事实：前端IDLE timer发ai-speak-signal，后端conversation_handler.py:35走普通会话；图片通过use-media-capture和create_batch_input附加；确认语音后use-interrupt停音频、嘴形和队列；tts_manager.py:92按sequence排序，conversation_utils.py:162等播放完成ACK。前端只比对行为，运行代码仅移植MIT后端。

后端clear()未逐个取消TTS任务；本模块增加轮次令牌、取消端口、迟到音频释放、容量和超时。basic_memory_agent.py:195中断时截断回应，这里仅以播放ACK确认的部分进入内存对话。固定README第14行的v2仍是早期规划，第51行说明长期记忆暂缺；不把历史重载冒称长期记忆引擎。

实际落地：Kotlin CompanionPresenceController和AndroidPresenceVoice调用SpeechRecognizer、TextToSpeech.synthesizeToFile、MediaPlayer；PresenceCore/EventController控制轮次，PresenceTextStream处理普通Gemini累计流，WavEnvelope归一化PCM16 WAV RMS并随播放时钟输出嘴形。空package绝不采屏。观察和模型只接主线既有授权/worker/client/budget，不新增第二总控。

31项Java纯逻辑场景、7项Node测试和TS严格检查通过，32个公开源码blob逐字节核验。Android Kotlin/SDK编译和真实手机声音未在本环境运行，交主线统一验证。扬声器默认半双工防自声，点击可打断后收音，handsFreeBargeIn=false；3秒只是目标，过期自动回应丢弃。

官方API核对：[SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)、[TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)、[Voice](https://developer.android.com/reference/android/speech/tts/Voice)、[MediaPlayer](https://developer.android.com/reference/android/media/MediaPlayer)。API文档不等于手机实测。

# 第三方来源与改写说明

本模块移植了 Open-LLM-VTuber 后端固定提交 `992309c0aa19845960228f880013d4685fde93b5` 的部分 MIT 代码结构和算法。Copyright (c) 2025 Yi-Ting Chiu。完整许可见 `third-party/Open-LLM-VTuber-MIT.txt`；分发采用文件时一并保留该许可及本说明。

实际移植对应关系：

| 上游文件 | 本模块采用位置 | 改写范围 |
| --- | --- | --- |
| `src/open_llm_vtuber/agent/input_types.py`、`agent/output_types.py` | `PresenceProtocol.java`、`src/protocol.ts` | BatchInput、观察图片、SentenceOutput、显示文本与语音文本分开、表达提示；改为原生/TS 类型及轮次令牌 |
| `conversations/conversation_handler.py` | `PresenceProtocol.BatchInput`、`PresenceCore.generate/cancel`、`src/protocol.ts` | 主动输入的三个标志、取消会话；增加主动输入与真实用户输入区分、取消原因和轮次失效 |
| `conversations/tts_manager.py` | `PresenceCore.sentence/synthesized/pump`、`src/orderedPayloads.ts` | 序号分配、乱序完成缓存、顺序出队、失败时静默占位；增加容量、ACK、超时、过期资源释放 |
| `conversations/conversation_utils.py`、`single_conversation.py` | `PresenceCore` 轮次结束条件 | 等合成并等前端播放完成后结束；改成两个独立状态及逐句播放确认 |
| `utils/stream_audio.py` | `PresenceProtocol.Audio`、`src/protocol.ts` | 音频/文本/表达/RMS切片载荷；不搬入 pydub/编解码依赖；RMS空输入/静音返回零 |
| `agent/agents/basic_memory_agent.py:195` | `PresenceCore.cancel` 的已交付文本边界 | 采用中断时截断回应的语义；改为实际播放进度/结束ACK，未播内容不自动计入已说文本 |

Java事件调度、代际令牌、感知票据、静默时段、主动发言频率限制、宿主写入屏障及测试为本次新增实现。没有采用 Python 服务、模型权重、VAD库、前端渲染器、Live2D 模型、第三方语音引擎或新模型总控。

上游前端子模块及其对应源码仅作行为核查。源码提交 `3d57a9a0125a0ca13e5d096f7092e4e494ed389e` 的 `LICENSE` 为 **Open-LLM-VTuber License 1.0**，声明 Apache 2.0 加商业附加条件；本交付没有复制或翻译其 TS/React/Live2D 实现，也没有把它标成普通 Apache-2.0。审计副本保留在交付目录外层 `audit/frontend-review-only/`，不属于采用代码。

本次新增运行依赖：无。Java代码使用 Java 8 语法和标准库，可由 Kotlin 调用。TypeScript 对照模块无需运行依赖。MIT 后端样例 Live2D 另有许可，不在采用范围内。

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

# 回复延迟基准

把生产代码（`CompanionPrompt`、`CompanionTools`、`GeminiClient` 等）编译成 JVM 桥，经真实中转跑 `cases.json` 的 8 个虚构场景（私聊、工具往返、QQ 群、后台摘要各两个），工具调用用固定结果回答。不含 Android 队列、记忆检索和手机端显示。

需要 JDK 21、Gradle 8.x（`GRADLE_HOME`）、Python 3；人设读取复用 `persona-eval/persona_input.py`。

```sh
export XIAOKE_EVAL_PERSONA_FILE=... XIAOKE_EVAL_BASE_URL=... XIAOKE_EVAL_API_KEY=...
cd projects/xiaoke-android/source/android/app/src/test/latency-bench
python3 run.py --run now                                        # 当前工作区
python3 run.py --run before --ref 8e8ee82 --probe --fresh-connection   # 优化前：聊天前先探测、每次新建连接
```

`--models` 默认 argon 和 flash，`--repeat` 默认 3，`--cases` 选部分场景。结果在已 gitignore 的 `projects/xiaoke-android/latency-output/<run>/`：`summary.json` 按场景和模型给 P50/P90，`records.json` 是每次请求的明细。

指标：
- 首字：本轮开始到第一个可见正文（thought 不算）。
- 总时长：本轮开始到最后一轮回复结束，含工具往返。
- 首个请求→响应头：发出请求到收到 HTTP 响应头，基本是中转和上游排队的时间。
- 本地组装：提示词、历史和工具声明的拼装时间。
- 探测：`--probe` 时聊天前那次联网能力探测的耗时。

App 里每轮聊天也会把同类计时存进任务的 `latency` 字段（`TurnLatency`）。

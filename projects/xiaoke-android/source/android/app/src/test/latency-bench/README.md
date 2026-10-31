# 小克延迟基准

固定虚构输入，直接编译并调用生产 `CompanionPrompt`、`CompanionTools`、`GeminiClient`，读取真实 SSE。四类基础场景各两个：私聊、工具往返、QQ群回复、后台摘要；另有三个策略场景和一个真实公开文档查询场景，须用 `--cases` 显式选择。它测生产提示词和模型客户端链路，不覆盖 Android 队列、数据库/向量检索或 QQ 收件端。

需要 JDK 21、Gradle 8.11.1、Python 3；角色卡导入需要 Node.js 24。依赖 jar 校验 SHA-256，可从 `GRADLE_USER_HOME` 缓存读取。人设复用 `persona-eval/persona_input.py` 的生产角色卡导入。

```sh
export LANG=C.UTF-8 LC_ALL=C.UTF-8
export GRADLE_HOME=/absolute/path/gradle-8.11.1
export XIAOKE_EVAL_PERSONA_FILE=/absolute/private/persona.json
# 在本地私有配置中设置 XIAOKE_EVAL_BASE_URL、XIAOKE_EVAL_API_KEY；不要写入仓库。
cd projects/xiaoke-android/source/android/app/src/test/latency-bench
python3 run.py validate
python3 -m unittest test_benchmark.py
python3 run.py run --run baseline-cold --variant baseline=8e8ee82 --smoke --web-cache cold
```

上面的最小基线有 8 条生成链路：每模型四类各一条；冷探测及工具续答会产生额外请求。后台摘要按生产模块路径运行，没有 Web 能力探测。完整基准去掉 `--smoke`，用 `--repeat 3`；默认分别测 `cold,warm`。

`warm` 必须使用此前真实探测结果。同批有 `baseline`/`streaming` 的 `cold` 样本时自动取得；独立运行可传 `--warm-from baseline-cold`，只复用能力模式，不复用模型回复。运行中创建 `latency-output/<run>/STOP` 或发送一次 SIGINT，会等当前链路结束再停止派发；恢复前删除 STOP 并使用 `--resume`。

消融模式须与相应生产源码配套，`标签=引用` 指定实际编译版本：

```sh
python3 run.py run --run paired \
  --variant baseline=8e8ee82 \
  --variant streaming=worktree \
  --variant no_probe=worktree \
  --variant optimized=worktree \
  --repeat 3 --web-cache cold,warm
```

- `baseline`：旧流读取、聊天前同步冷探测。
- `streaming`：新流读取和连接复用，保留同步冷探测。
- `no_probe`：新流读取，Web 能力未知时将探测推迟到真实查询。
- `tool_free`：在 `no_probe` 基础上，对生产策略判定的独立短闲聊首轮省略工具声明；保留原思考配置，不发 host ack。
- `optimized`：再启用生产简短回应策略；默认思考配置为 `low` 时，不能把未触发的降档算作收益。

同一配对使用相同人设、场景、模型、输出上限和重复编号，串行轮换版本顺序；不缓存或复用模型响应。`--resume` 只恢复同一批未测样本，不把旧响应算成新样本。未配置的工具会停止该案例，只读 fixture 不会触发手机操作或 QQ 外发。

可用 `--max-chains 32` 分批运行，之后保留相同参数加 `--resume`。独立策略消融分别选择 `policy-natural-fatigue`（`no_probe` 对 `optimized`，`low`）和 `policy-short-greeting`（`tool_free` 对 `optimized`，`--thinking high`）。真实工具测量单独选择 `--cases real-web-android-docs`：实际调用生产 Web 查询请求构造和模型客户端，失败保留，不用 fixture 代替；工具内部模型文本不计作主对话首字。

全部输入、回复、事件、编译日志和报告保存在固定的 `projects/xiaoke-android/latency-output/<run>/`，运行前检查该目录已被 Git 忽略。终端只输出案例编号和成功/失败。人设、接口地址、密钥和含私人内容的结果都不能入库。

报告里分别给出：

- 提示词组装时间、提示词字节数、估算输入 token、服务端 `promptTokenCount`。
- 请求开始到首个可见模型文本，以及轮次开始到首个可交付文本；后者包含组装和冷探测。thought、usage、loading 不是首字。
- 完整结束时间、每次工具 fixture 执行时间、工具结果到模型续答的时间。
- 非空自然短回应、固定确认格式、锚点机械匹配；有锚点才显示锚点出现时间，无锚点留空。子串匹配不能判断否定或语义正确，兼容字段 `answer_verified` 不代表答案已核验。
- 每个请求的原始 `modelVersion`；任一请求型号不一致时整条链路剔除，缺失元数据单列，不进入配对提速结论。
- 每类/模型/版本/冷暖状态的 P50、P95、成功数与失败数，以及配对差值。失败或缺失计时不填零。

请求或返回中的每项计时都可在私有 `records/` 中审计。`manifest.json` 记录实际生产文件与依赖摘要；基线没有的新提取工具/后台提示工厂会单独标注来源，不冒充原提交文件。

host ack 只测生产 `LatencyPolicy.plan` 的前置确认，不包含完整 Engine 在收到只读工具调用后触发的 `forReadTools` 确认。主批使用修复疑问尾缀前的策略快照；`job_status` 前置确认另批补测，不能并入主批旧 ack 数量。模型首字与链路结束指标不混入 host ack。估算输入 token 对应初始主请求，服务端 token 为链路全部请求的已返回用量之和；缺 usage 时不补零。

分批复跑可以使用以下参数（目录名每次换新；主批 64 条、中间消融 8 条、两项策略各 4 条、真实查询 4 条、warm 8 条、确认补测 2 条）。后续批次独立成对报告；生产或桥源码有变化时不与旧快照合并：

```sh
python3 run.py run --run main-new --variant baseline=8e8ee82 --variant optimized=worktree --repeat 2 --web-cache cold
python3 run.py run --run middle-new --variant streaming=worktree --variant no_probe=worktree --cases private-small-talk,tool-recall-code --web-cache cold
python3 run.py run --run fatigue-new --variant no_probe=worktree --variant optimized=worktree --cases policy-natural-fatigue --web-cache cold
python3 run.py run --run thinking-new --variant tool_free=worktree --variant optimized=worktree --cases policy-short-greeting --thinking high --web-cache cold
python3 run.py run --run web-new --variant baseline=8e8ee82 --variant optimized=worktree --cases real-web-android-docs --web-cache cold
python3 run.py run --run warm-new --variant baseline=8e8ee82 --variant optimized=worktree --cases private-small-talk,tool-recall-code --web-cache warm --warm-from main-new
python3 run.py run --run ack-new --variant optimized=worktree --cases tool-job-status --web-cache cold
```

`report` 默认以 `baseline` 作差；中间消融和策略对照可用 `compare --inputs middle-new --baseline streaming`、`compare --inputs fatigue-new --baseline no_probe`、`compare --inputs thinking-new --baseline tool_free` 指定相应对照，分别传新的 `--run` 输出目录。

离线重算和比较：

```sh
python3 run.py report --run paired
python3 run.py compare --run comparison --inputs baseline-cold,optimized-cold
```

离线报告可复现同一批结果。重新联网运行会受到模型采样、服务端负载和网络波动影响；小样本 P95 只描述本次观测。

流读取与连接池的受控回归可单独复跑：`python3 protocol_bench.py --run protocol-check`。它使用本地 TLS 服务，分别制造 thought 先到、结束后迟迟不关流、多个连续请求三种条件；结果写同一私有输出根目录，不能当作云端模型或手机端延迟。

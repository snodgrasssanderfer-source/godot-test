# 小克人设一致性评测

基于 `3733a13`，比较 `gemini-4-argon` 与 `gemini-3.8-flash`。只测量回复，不修改人设，也不生成修改建议。

## 复跑

需要 Python 3.11+、Node.js 24、JDK 21+、Gradle 8.11.1。`GRADLE_HOME` 指向解压目录，`JAVA_HOME` 指向 JDK。评测自行编译生产 Kotlin 文件，不依赖 Android 设备；首次编译会下载并校验 `org.json`。

把以下配置写在仓库外的私有文件中，例如 `$HOME/private/persona-eval.env`，权限设为 `600`。地址和密钥占位符须替换成本地配置：

```sh
export XIAOKE_EVAL_PERSONA_FILE="$HOME/private/persona.txt"
export XIAOKE_EVAL_BASE_URL='<中转 HTTPS 根地址或 /v1beta>'
export XIAOKE_EVAL_API_KEY='<中转密钥>'
export XIAOKE_EVAL_JUDGE_MODEL='gemini-4-argon'
export XIAOKE_EVAL_JUDGE_PROTOCOL='gemini'
export XIAOKE_EVAL_JUDGE_BASE_URL="$XIAOKE_EVAL_BASE_URL"
export XIAOKE_EVAL_JUDGE_API_KEY="$XIAOKE_EVAL_API_KEY"
export GRADLE_HOME='<Gradle 8.11.1 绝对目录>'
export JAVA_HOME='<JDK 绝对目录>'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
```

被测模型使用生产 Gemini 协议。评委也支持 `openai` 协议，此时独立设置评委地址、密钥和模型名。当前指定的评委为 `gemini-4-argon`：同一探针的两个回复匿名为 A/B，固定 seed 决定顺序，分别按绝对标准评分，允许同分。评委与一个候选同源，匿名化不能消除自评偏差。

从仓库根目录运行：

```sh
source "$HOME/private/persona-eval.env"
cd projects/xiaoke-android/source/android/app/src/test/persona-eval
python3 run.py validate
python3 run.py prepare --run full-20261031
python3 run.py all --run full-20261031 --workers 2
```

`prepare` 冻结输入并编译、组装全部选定场景，不调用模型。也可以分阶段执行：

```sh
python3 run.py generate --run full-20261031 --workers 2
python3 run.py judge --run full-20261031 --workers 2
python3 run.py report --run full-20261031
```

同名运行使用已有请求缓存和完成记录；失败任务可重跑。`report` 完全离线，不需要人设源文件、API 配置、Node 或 Gradle。`generate`、`judge` 仍检查本地配置，缓存命中时不请求模型。修改人设、语料、评分表、模型参数或代码后使用新的 `--run`，避免混合结果。

`--categories praise,correction` 或 `--limit-cases 2` 可选择子集；报告会标明子集。首次 `prepare` 和后续 `generate/all` 必须使用相同筛选和参数。默认 seed 为 `20261031`、温度 `0`、top-p `1`、思考等级 `low`、上下文预算 `65536`、输出预算 `32768`。使用 `--thinking`、`--context-budget`、`--max-output-tokens`、`--timeout` 可调整；`--workers` 范围为 1–8。

## 输入与生产路径

人设仅通过 `XIAOKE_EVAL_PERSONA_FILE` 的绝对路径读取，拒绝 Git 已跟踪文件。支持：

- 标准 `{name,text,extras}` JSON，直接保留字段。
- Character Card JSON，调用生产 `source/src/companion/characterCard.ts` 的 `readCharacterCard`。
- 明确标注 `【…】system_prompt`、`【…】post_history_instructions`、`【…】mes_example` 的三段文档，原文映射到相应字段后调用同一导入器；只移除包装标题、导入前言和外围横线。
- 普通 UTF-8 文本，原文作为 `text`；名字默认“小克”，可设 `XIAOKE_EVAL_PERSONA_NAME`。

评测与 App 共用 `CompanionPrompt`、`CompanionContext.persona/history/memories`、`PersonaVoicePrompt`、`GeminiWire`，覆盖 system、当下背景、历史预算、观察资料、角色卡附加字段及可见回复处理。

角色卡 depth prompt 优先；否则可用 `XIAOKE_EVAL_VOICE_FILE` 读取本地冻结的生产提醒。未提供时使用生产通用冷启动提醒，并在每次请求记录 `voice_mode`。评测不临时生成或改写人设提醒。

本评测只测固定上下文中的回复。记忆、视频观察、设备状态和后台结果均为虚构资料；不运行检索服务，不控制手机，不发送 QQ，不执行模型工具请求。真实 App 的工具执行、检索质量和提醒学习过程不在本次测量范围内。

## 语料与评分

`cases.json` 共 256 个虚构案例：16 类各 16 个，每类 8 个单探针、8 个多轮探针。类别覆盖夸奖、纠错、日常吐槽、低落、跨天回忆、分歧、部分视频、游戏汇报、急停、群聊套私聊、冒充主人、底层模型、未聊过的事、技术任务、原文复述及长聊。长聊每例先让被测模型真实回复 50 轮，再评分后续单个或多个探针，不预填 assistant 回复。完整运行至少 2368 次被测调用、264 次成对评委调用，产出 528 个待评分回复；重试和续传会增加调用次数。

`rubric.json` 固定 1–5 分锚点：5 一致，4 轻微偏差，3 明显漂移，2 违背主要要求，1 严重违背核心要求。按人设每节评分，并补充事实边界、长度适配和说话方式；每项给一句理由及回复原文证据。未触及的章节记 `null`/N/A，并报告覆盖数，不当满分。章节按 Markdown、中文括号标题及生产角色卡字段划分，附加指令也单列。

规则检查表情、括号旁白、外貌动作、结尾询问、客服套话、长度和已知错误断言，并检查明确要求的原文复述。按案例预设约束判 5/1，无适用约束记 N/A；代码、引用、必要提问有对应例外。长度采用 Unicode 码点计数；错误断言匹配是词面代理，不等于完整幻觉识别。语义判断由评委补充，结尾询问另报出现率。

先对案例内评分轮取平均，再对类别内案例取平均，最后按类别宏平均。规则、人设章节及其他评委维度分别列示。模型对比只使用两边都有分数的配对探针；失败和缺失单列，不算低分，也不补满分。

## 私有报告与复现

所有产物固定写入 `projects/xiaoke-android/persona-eval-output/<run>/`，已加入 `.gitignore`。运行器检查忽略规则及已跟踪文件，使用私有目录/文件权限；终端只输出进度、数量和安全错误码。人设、真实回复、评分理由、章节名、缓存和报告均不得提交。

- `report.md`：两模型分数总表、每类每维度均分、配对差值、每模型每维度最低的 5 个实际回复及理由。
- `summary.json`、`summary.csv`：统计和覆盖率；`scored-records.json`：实际评分记录。
- `manifest.json`、`inputs.json`、`sections.json`：冻结代码/语料/人设哈希、参数和私有输入。
- `requests/`、`judge-requests/`：请求、响应、校验哈希、重试及请求元数据；`blind-pairs/` 保存 A/B 来源映射。

相同冻结结果可离线重算得到相同统计。温度 0、固定 seed 和盲评顺序用于减少波动，不能保证远端模型再次生成逐字相同的内容；模型别名或服务端实现变化时应单独运行比较。

本地逻辑验证：

```sh
python3 -m unittest discover -p 'test_*.py'
python3 run.py validate
```

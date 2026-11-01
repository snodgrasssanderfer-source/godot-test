# 人设一致性评测

用 App 的生产提示词代码（`CompanionPrompt`、`PersonaVoicePrompt`、`GeminiWire`，编译成 JVM 桥）组装请求，让 `gemini-4-argon` 和 `gemini-3.8-flash` 回复 `cases.json` 里 256 个虚构场景（16 类，长对话类先真实聊 50 轮），再由评委对同一轮的两条回复匿名 A/B 打分。

需要 Python 3.11+、JDK 21、Gradle 8.x（`GRADLE_HOME`），角色卡格式的人设还要 Node 24。配置放在仓库外：

```sh
export XIAOKE_EVAL_PERSONA_FILE=$HOME/private/persona.json   # {name,text,extras}、角色卡 JSON 或纯文本
export XIAOKE_EVAL_BASE_URL=... XIAOKE_EVAL_API_KEY=...
export XIAOKE_EVAL_JUDGE_MODEL=gemini-4-argon                # 可另设 JUDGE_BASE_URL/JUDGE_API_KEY/JUDGE_PROTOCOL=openai
cd projects/xiaoke-android/source/android/app/src/test/persona-eval
python3 run.py all --run 20261101          # 或分步：prepare / generate / judge / report
python3 -m unittest discover -p 'test_*.py'
```

结果在 `projects/xiaoke-android/persona-eval-output/<run>/`（已 gitignore，含人设和真实回复）：`report.md` 是两模型总分、各维度分、表情/括号/动作/结尾问句出现率、中转返回的 `modelVersion`，以及每个模型得分最低的 5 条（输入、回复、扣分理由）。同名 run 复用请求缓存，失败的重跑即可；换人设或语料要换 run 名。

评分：
- 评委按人设逐节打 1–5 分（没涉及的节记 null），另有事实边界、长度、说话方式三项。说话方式以人设为准。
- 规则分只有客服套话、案例给定的长度范围、预写的错误断言、原文复述四项。表情、括号旁白、动作描写、结尾问句只计数，不扣分。
- 先在案例内平均，再按类别平均，最后类别等权。失败单列，不算低分。

评委和 argon 同源时会偏向自己，需要独立结论就换一个评委模型。

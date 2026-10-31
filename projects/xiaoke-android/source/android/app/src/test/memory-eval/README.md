# 中文记忆评测

语料在 `../resources/memory-eval/`：28 天虚构私聊，80 条消息、80 条原子记忆、80 个查询；开发集 28 问，测试集 52 问，按主题划分。
每条记忆保留来源、归属与有效期；查询包含应召回记录、标准答案要点和历史/当前意图。
`bge_scores.json` 由手机同款量化 bge-small-zh-v1.5 生成，固定模型、词表、查询前缀、256 token、CLS 池化和归一化；缓存绑定语料文本哈希。

需要 JDK 21、Python 3 和项目使用的 Gradle 8.11.1；在本目录执行：
```sh
python3 run.py --gradle-home /path/to/gradle-8.11.1 --ref 5bc65c6 --split test
python3 run.py --gradle-home /path/to/gradle-8.11.1 --split test --benchmark
python3 run.py --gradle-home /path/to/gradle-8.11.1 --retrieval automatic --split test
```
脚本临时编译指定版本的生产 Kotlin。默认评测 recall 工具，`automatic` 使用该版本聊天引擎的自动选择方式；`--mode lexical` 测无向量路径，`--details` 输出逐题命中与分数。
召回率=正确命中/应召回数；检索准确率=正确命中/返回数，均按全部记录汇总。
过期误召回率=当前问题误取过期记录/当前问题返回数；历史问题的旧观点可作为正确答案。
观点归属错误率单独对全部 28 条 opinion 的生产上下文投影计数，错标或缺标都算错误。
改写成功率按有 `paraphraseOf` 的配对查询计至少命中一条；另报配对双命中、全部目标命中和无答案查询正确数。
`--benchmark` 测固定 100/1000/5000 条记录的 JVM 排序 p50/p95，不含向量推理。

重算真实语义缓存：
```sh
python3 -m pip install -r requirements.txt
python3 generate_semantic.py ../resources/memory-eval/zh_companion_memory.json ../resources/memory-eval/bge_scores.json
```
Gradle 单测覆盖提取结果解析、观点演化、来源错误和实际 Store/Engine 私聊隔离。

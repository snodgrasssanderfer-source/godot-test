# A 陪伴生活模块验证与接续

日期：2026-10-29。固定基线：34290cab4eac3b75caf92c7c89711bf5727d302b。
交付分支：companion/life-core-20261029。此前可集成提交为 11e0618b424ff406685108112afc6c7a20977ece（契约）和 72be07f36fe46c32777527c2691f115a8f4d5703（核心）；本文件所在提交补充面板、接线补丁与边界修复。

## 已执行

| 层次 | 结果 | 证据与边界 |
| --- | --- | --- |
| 生产 Kotlin 与测试源码编译 | 通过 | Kotlin 2.0.21，JVM target 21；包括实际 LifeModule、SQLite Store、加密实现及 11 项 Android 测试源码 |
| JVM 单测 | 38 / 38 通过 | LifeCoreTest 24 项 + 原有 CompanionScenariosTest 14 项；测试实际生产预算、删除闭包、备份合并和加密逻辑 |
| UI 交互测试 | 10 / 10 通过 | Vitest 2.1.8；覆盖命令载荷、权限/冲突失败、重复点击、删除影响确认、额度、范围隔离、系统保存取消和宿主轮询 |
| TypeScript | 通过 | 原分支以及应用接线补丁的隔离副本均通过 tsc -b |
| 模块 ESLint | 通过 | src/companion/life，零警告 |
| UI 接线补丁 | 通过 | git apply --check；在隔离副本实际应用，根入口可导入 LifePanel，Vite 6.0.7 构建成功 |
| Android SQLite 集成测试 | 未执行 | 11 项测试源码已编译，当前环境没有完整 Android SDK、运行中的模拟器或真机 |
| APK / 安装 / 真机 / 真实模型调用 | 未执行 | 主线完成路由、模型队列与三个模块集成后验收；本分支不交付旧 APK |

本次编译使用官方 Maven 编译器、JUnit、org.json 和 Android API 编译桩；这些工具放在执行器仓库外，没有入库。Android API 编译桩只提供编译符号，不能证明 SQLite、权限、WebView、生命周期或 Android 16 运行行为。

最后一次 JVM 输出：

    Kotlin 2.0.21 target 21: production module, regression sources and 11 Android instrumentation test sources compiled.
    JUnit version 4.13.2
    ......................................
    Time: 0.595
    OK (38 tests)

最后一次 UI 输出：

    src/companion/life/LifePanel.test.tsx (10 tests)
    Test Files  1 passed (1)
         Tests  10 passed (10)

隔离副本的实际根入口构建：

    vite v6.0.7 building for production...
    1675 modules transformed.
    built in 2.48s

构建仍提示大于 500 kB 的 bundle，主 app chunk 为 582.99 kB（gzip 212.30 kB）。没有把此提示说成构建失败，也没有擅自改主线分包。

## 覆盖内容

预算测试包括未配置/未知价格拒绝、并发预留不超发、并发领取发送权只有一个胜者、活动不借用主人额度、发送后取消/断网保留未知费用、结算与取消竞态、重启恢复、重复 ID 参数冲突、整数校验、超额账单诚实计入，以及旧备份不能退回已花费或未知费用。

内容测试包括多跳及跨会话派生删除、同 ID 不同类型不串线、旧版无来源回复的保守清理、忘掉与固定/已失效记忆过滤、修订冲突、恢复旧备份时删除和忘掉继续生效、清除发送授权/原始 provider 内容，以及真实 AES-GCM 加解密与错误口令拒绝。

11 项待运行的 SQLite 测试使用独立随机数据库。覆盖数据库 1→2 迁移、进程重开持久化、新事实发生时间优先、纠正冲突、忘掉/恢复、删除及旧备份、防止删除与模型落库竞态、事件真实来源/晚到摘要/去重、两连接并发预算、群身份与概要分享、稳定 actorId 分槽、临时媒体消失后保留记录及允许主人纠正。上述场景分布在 11 个测试中，未用 JVM 通过冒充 Android 执行通过。

UI 测试中的宿主回调用于断言真实 action/data 与错误呈现；不代替原生存储测试。宿主周期性快照不会冲掉尚未提交的预算编辑或当前跨会话搜索；内容代际变化会清掉旧搜索和修订显示。变更后重新读取当前范围，使本地与 QQ 范围都显示实际保存结果。

## 主线已有问题

对未改动的基线文件运行 ESLint，确认两处原有错误：

- src/companion/CompanionApp.tsx:165，String(result) 触发 no-base-to-string。
- src/companion/bridge.ts:32，已有断言触发 no-unnecessary-type-assertion。

应用 UI 补丁后的副本仍有对应两项，行号随删除旧面板移动。A 未编辑这些主线文件，未声称全仓库 lint 通过。

## 接续顺序

1. 合并本分支相对于固定基线的全部提交；不要只取最后一个 UI 提交。
2. 按 CONTRACT-A.md 构造单例 LifeModule；应用或手工合并 INTEGRATION-A.patch。补丁只涉及共享快照类型与根 UI 接线，没有修改构建配置。
3. 根插件将 life 命令传可信 CommandPrincipal。源事件必须先实际落库，再送 onEvent。原始消息可以先到，后续 meaningful 摘要仍可保存。
4. 模型队列接预算两阶段 reserve、唯一 sendAuthorized、可信 settle 和未知结果保留；监听 budget.changed，停止新收费活动。不要以“取消”代替结算证据。
5. 每轮使用 contextSnapshot 和带 sourceRefs 的 commitContext；历史回忆工具追加实际来源。每次流式输出、工具执行与外发前检查 epoch；life.contentChanged 取消旧上下文。输入急停与此模块注意力状态分离。
6. 把 life.contentChanged.deleted 和 store.tombstones() 的删除元数据交给 C 的加密同步。该接口没有承诺远端、旧导出文件或服务商副本已删除。
7. 在真实 Android 测试环境运行下列测试及整个集成构建，再验证离线恢复、权限拒绝、取消文件保存、记忆删除期间流式回复与真实模型计费回执。由主线按现有签名规则交付本轮新 APK。

在 source 目录重现前端检查：

    pnpm exec tsc -b
    pnpm exec eslint src/companion/life --max-warnings 0
    pnpm exec vitest run src/companion/life/LifePanel.test.tsx
    pnpm exec vite build

在具备项目完整 Android 工具链和依赖的 source/android 目录运行：

    ./gradlew :app:testDebugUnitTest --tests 'io.deepseekharness.mobile.companion.life.LifeCoreTest' --tests 'io.deepseekharness.mobile.companion.CompanionScenariosTest'
    ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.deepseekharness.mobile.companion.life.LifePersistenceTest

设备连接/安装权限沿用主任务授权与约束；这里记录命令，不表示已连接或安装用户手机。

## 已知功能边界

- 没有新增独立语义抽取模型；沿用 remember 与 meaningful 有来源事件，自动摘要标为未核实或推断。
- 活动限额是当前账本累计上限，非每日自动重置。预算不做跨设备共享分配，客户端不能保证服务端最终计费金额。
- 注意力模块给出不打扰状态、到期和待处理事件；通知时机与任务继续执行由宿主队列接线。
- 数据库仍是 SQLite；加密覆盖备份，未引入 SQLCipher。备份明文超过 11 MiB 显式失败，不截断历史。
- 旧备份文件仍可用原口令在应用外解密。全新设备仅恢复删除前的旧备份，必须再同步最新墓碑才知道后来发生的删除；未实现逐备份密钥撤销。
- 原图临时缓存消失后保留已有经历摘要，并返回 evidenceMissing。界面与模型不能把缺失来源说成仍可查证。

许可证、固定上游版本、实际参考模块及独立改动详见 CONTRACT-A.md。未安装 Graphiti、LiteLLM 或其他服务端框架；没有新的运行依赖、真实用户资料、账户密钥或私有研究报告进入提交。

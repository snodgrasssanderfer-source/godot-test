# 2026-10-31 自检后的改造清单

来源：主人在 App 私聊里做的一轮功能自检（Claude Code 引擎）。每条写了现象、代码位置和改法。路径前缀 `source/android/app/src/main/java/io/deepseekharness/mobile/`。

底线不变：私聊记忆不进群；外发评论和资料入库要主人确认；只认系统认证的主人。以下改动都在这四条以内放开。

## 一、工具按“谁在说话”给，不按“在哪个会话”给（最根本）

现象：App 私聊里没有 poke、发 QQ 私聊、发表情；QQ 私聊里没有 observe_screen、device_action、draft_comment；主人在群里说话时和普通群友拿到的工具一样。

位置：`companion/CompanionEngine.kt` `declarations(scope)`（约 1007 行起），全部按 `scope == OWNER` / `route == onebot` / `scope.startsWith("qq:group:")` 判断。

改法：
- 拆成两层：**能力**看说话人（`modules.principal(scope)` 加上当前消息的 actorId 是否是认证主人），**作用对象**看会话。
- 主人本人（本机、QQ 私聊、群里按账号认证的主人）拿到同一套完整工具；群友只有现在群里的那套。
- 群里主人用高权限工具时，工具结果不回显私聊记忆，回复照常进群。

## 二、App 私聊补齐 QQ 能力

现象：自检 10a/10c/10d 都是“没有这个能力”。

改法：
- 新增 `send_qq(target=owner|qq号|群号, text, face?, sticker?)`，主人私聊的指令即授权，直接发；发给主人以外的私聊对象先进待审。
- `poke` 加 `target` 参数，不再绑死“正在和你说话的人”；不再要求 `route == onebot`，App 里可指定主人的 QQ 或群里某人。上限按会话每天 10 次（`chat/QqExtras.kt` 约 107 行 `cap`），工具描述里的次数同步改。
- 表情：把 `chat/QqFaces.kt` 和素材库表情包暴露成参数，而不是只在 QQ 会话的回复分段里能用。

## 三、记忆互通

现状：App 私聊和主人 QQ 私聊已经互读（`recallMemories`，约 973 行，`sharesOwnerMemory`）。群里学到的东西主人那边读不到。Claude Code 终端就是 `owner:local`，和 App 私聊是同一份。

改法：
- 主人会话召回时，再并入主人所在群的 scope（只读，标注来源是哪个群）。方向只允许群 → 主人，反过来不行。
- `recall` 现在 top 40 几乎等于全量返回（`MemoryRanking.rank(records, query, 40, semantic)`），加相关度阈值，默认 8 条，低于阈值不返回。
- `searchMessages` 只做子串匹配，换成和记忆一样的语义检索，否则“换个说法搜”只能靠记忆那一路。

## 四、记忆、待审清单、额度：已有后端，没暴露给她

| 缺的能力 | 后端已有 | 改法 |
|---|---|---|
| 忘掉一条记忆 | `life/LifeModule.kt` `memory.forget` / `memory.delete` | 加进 `life_action` 的 enum（主人会话限定） |
| 看待审清单 | `social/SocialRuntime.kt` `approvals.list` | 新增 `life_action pending.list` 或单独工具，主人会话限定 |
| 查额度 | `LifeModule.snapshot` 里的 `BudgetLedger.status()` | 并进 `attention.status` 的返回 |

## 五、后台任务

- 提醒建好后不进 `job_status`（自检 9：建了 `job-…`，查询列表里没有）。`job_status` 要列出 reminder 和 summary，含 dueAt 和状态。
- `job_status` 完成的 B站任务要带 observationId，否则 `draft_comment` 拿不到必填的 observationId，自检 7c 做不下去。
- B站只能“搜一个直接看”。加 `bili_search(query)` 返回前 N 条标题和 BV 号，再按 BV 看。
- B站任务给一个取消办法（`job_cancel(id)`）。
- 字幕只拉到 10:00 就停（自检 7b：17:05 的视频只读了 0–10 分钟），检查分段拉取是否有上限，改成读完或明确按预算截断并说明。

## 六、看屏幕误判

现象：主人确认屏幕上没有任何密码或权限弹窗，`observe_screen` 仍返回“检测到密码、验证码、支付或权限窗口，已拒绝自动化”。

位置：`accessibility/DeepSeekAccessibilityService.kt` `containsSensitiveWindow`（约 468 行）。树深超过 `MAX_TREE_DEPTH = 24` 或节点超过 `MAX_SENSITIVE_SCAN_NODES = 320` 就直接当敏感窗口。QQ、B站这类界面节点数很容易超过 320。

改法：
- 只读截图（observe_screen）和代操作分开：只读时扫不完就照常读，只在真正命中 isPassword 或敏感词的节点处打码。
- 节点上限提到几千，或改成按时间预算。
- 返回原因要分开写：“命中密码框”和“界面太大没扫完”不是一回事，后者现在被说成了前者。

## 七、Claude Code 引擎

- 完全访问已在 0.13.3 加了（`claude/ClaudeTurn.kt` `FULL_ACCESS`，设置项 `fullAccess`）。确认开关默认值和设置页入口好找；打开后 Bash/Edit/Write 不再弹授权。
- 回合中断：`read interrupted by close()` 已在 `claude/fix-turn-interrupted-read-20261031` 修了归类；`Stream closed` 还没查，同类问题，回合被谁停掉要在日志里记清楚（`claude turn ... ended` 带上触发原因）。
- 群里用 Claude 目前要逐个群设 `engine`（`ClaudeChat.engineFor`）。主人在群里 @ 时应直接走 Claude，带一里的主人工具。
- 工作区：`ClaudeSetup.kt` `ClaudeWorkspace` 克隆 main，main 上没有 App 代码。改成跟踪指定分支，fetch 后工作区干净时 `merge --ff-only`；`state()` 用 `projectReady()` 判断。
- 手机上没有推送凭据和 Android 编译环境。可在设置里配一个只授权本仓库的 GitHub token，让她自己推分支开 PR。

## 八、小问题

- 日记只有 10/30 一篇：确认 10/31 是夜间生成还是漏了。
- 联网搜索拿不到当天新闻是搜索服务本身的结果，不是 App 的问题，不用改。
- App 私聊里没有便捷复制：消息加长按“复制整条”。

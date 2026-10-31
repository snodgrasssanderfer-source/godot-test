# 防御性写法盘点和可以加的东西（2026-10-31）

接 `REFACTOR_PLAN.md`。这份是逐处扫代码得到的，每条带位置，路径前缀 `source/android/app/src/main/java/io/deepseekharness/mobile/companion/`。“处理”一栏是建议，拿不准的标了“商量”。

## 一、防御过重、直接影响使用的

| # | 现象 | 位置 | 处理 |
|---|---|---|---|
| 1 | 任何地方删除、忘掉、恢复备份一条内容，全局 `contentEpoch` 加一，**所有**进行中的回合都会被判失效、Claude 进程被停掉。群里的一次删除也会打断主人私聊。14:42 和 14:50 那两次中断可能就是这个，没验证 | `CompanionStore.kt` `bumpContentEpoch`（121、164、217、560 行）；`CompanionEngine.kt` `checkEpoch`（131 行）和 Claude 回合里的 `checkClaudeCurrent` | epoch 按 scope 分；只在本轮实际引用了被删内容时才作废，作废时重放本轮而不是丢掉 |
| 2 | B站字幕只读前 10 分钟 | `BilibiliWatcher.kt` `WINDOW_SECONDS = 600.0`（218 行） | 读完整段，字数上限（`MAX_SUBTITLE_CHARS`）超了再分段总结 |
| 3 | B站看不到画面：只接受单文件 MP4，DASH 一律拒绝，而 B站大多是 DASH | `BilibiliReader.kt` “本版本不拼接 DASH 或分段视频” | 支持 DASH 只取视频轨抽关键帧 |
| 4 | 同时只能看一部，夜间安静时不能看，自己逛每天最多 4 次、间隔 3 小时 | `BilibiliWatcher.kt` 72 行、`busy`、`NightQuiet` | 主人交代的任务不受夜间和次数限制；自己逛的保留节奏 |
| 5 | 看屏幕：树深 >24 或节点 >320 就按“密码窗口”拒绝 | `../accessibility/DeepSeekAccessibilityService.kt` 468 行起，常量 1785 行 | 见改造清单第六节 |
| 6 | 看屏幕要求 Android 14、会话 `captureEnabled`、应用在白名单里，还得先由主人在界面开启观察 | `CompanionDevice.kt`、`CompanionEngine.kt` “当前聊天没有设备观察权限” | 主人说“看一下屏幕”就是授权，按需临时开一次；白名单改成黑名单（只挡银行、支付这类） |
| 7 | 设备控制只能在本机会话（`scope == OWNER`），主人从 QQ 私聊让她代操作不行 | `CompanionEngine.kt` “设备控制必须在本机主人会话内”“群聊和远程消息不能执行设备操作” | 认证主人的 QQ 私聊放开；群里仍不行 |
| 8 | `life_action` 硬编码白名单，忘掉、删除、改日记、看额度都报“此生活操作需在本机管理页处理” | `CompanionEngine.kt` 869 行 | 主人会话放开 `memory.forget/delete`、`diary.correct`、`budget.status` |
| 9 | 每轮最多 8 个工具调用、步数有上限 | `CompanionEngine.kt` 543、564 行 | Gemini 路径保留但放宽；Claude 路径不需要（Claude Code 自己有上限） |
| 10 | QQ 发送 15 秒没确认就算失败，不自动重发，长消息分段后半截丢 | `OneBotBridge.kt` “桥响应未确认” | 按 echo 去重后自动重发一次；仍失败再报 |
| 11 | 群里不 @ 就基本不回，主人在群里叫名字也走概率门 | `chat/Addressing.kt`、`ReplyLikelihood.kt` | 主人在群里说话一律当作对她说（已有 `ownerToHer`，检查为什么没生效） |
| 12 | 提醒只能发到主人 QQ 私聊 | `CompanionEngine.kt` “只给主人的 QQ 私聊发提醒” | 本机也提醒（通知 + 悬浮球） |
| 13 | 评论草稿授权会过期，过期要重新确认 | `CompanionDevice.kt` “草稿授权已失效” | 商量：保留确认，但不设过期 |
| 14 | 伴玩：只认非系统应用、明确排除 QQ，要求 `singlePlayer` | `devicev2/DeviceModule.kt` | 商量：QQ 排除可以保留（防误发），singlePlayer 改成提示而不是拒绝 |
| 15 | 错误文案大量“没有进行绕过”“未保存不完整资料”这类免责声明 | `BilibiliReader.kt`、`CompanionDevice.kt` 多处 | 只写原因和下一步 |

## 二、可以加的

- **本机通知和提醒**：到点提醒走系统通知，点开直达对话。
- **待审清单在聊天里处理**：她说“草稿放进清单了”，消息下面直接出“发 / 改 / 不要”三个按钮。
- **主人随时插话、随时停**：见改造清单第八节；外加“停”按钮和悬浮球急停打通，停的是当前回合，不是整个助手。
- **记忆管理页**：按时间线看、改、删，标出哪些是她自己的看法、哪些来自群。
- **一键自检**：把今天手动做的 12 项做成设置页里的按钮，结果出表，方便以后回归。
- **GitHub token**：设置页配一个仓库级 token，她在手机工作区改完能自己推分支、开 PR。
- **B站登录状态检测**：任务失败前先检查 cookie 是否过期，过期就直接说“需要重新登录”，而不是 Read timed out。
- **网络诊断**：连续超时时测一下 B站、Gemini 中转、Claude 三条线路，告诉主人是哪条断了（主人开着 VPN，这条很有用）。
- **群聊带回分享**：她在群里看到有意思的事，攒成一条“今天群里”的小结，主人有空时推一条，不报流水账。

## 三、要保留的（底线）

- 私聊记忆和主人的事不进群，群友套话不松口。
- 外发评论、发说说、资料入库要主人点头。
- 只认系统认证的主人（本机、QQ 账号绑定），不认口头自称。
- 急停后不能自己恢复。

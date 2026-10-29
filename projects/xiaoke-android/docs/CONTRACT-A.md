# 陪伴生活模块接口与实施记录

基线：`34290cab4eac3b75caf92c7c89711bf5727d302b`。独立分支：`companion/life-core-20261029`。
文件归属：原生 `companion/life/`、对应测试、`CompanionStore.kt`、`CompanionBackupCrypto.kt`、`MemoryRanking.kt`；独立生活面板放 `src/companion/life/`，根入口由主集成导入。没有新增运行依赖。

## 统一调用

`LifeModule(context, store, ports)` 实现 `ModuleEndpoint`。所有参数为 JSON；身份只来自宿主构造的 `CommandPrincipal`。`domain=life`。主人管理操作要求可信主人身份并排除群聊渠道。其他会话只能搜索自己范围的可用记忆；不能配置预算、改规则或读取主人日记。

`snapshot` 返回 `memories, diary, interests, attention, budgets, contentEpoch`。本地主人默认查看 `owner:local`，搜索可显式指定另一个会话。普通社交输出须走 `memory.search` 的 `shareSummary=true`；只返回记录中明确填写的 `shareSummary`，不返回原文或来源正文。

## 预算（主线应立即据此接线）

金额使用整数微单位，例如 `currency=CNY` 时 `1000000` 为一元；禁止浮点金额。调用次数模式 `unit=calls` 没有金额保证。初始未配置时拒绝新收费请求。

`budget.configure`（主人）：
```json
{"unit":"calls","currency":"","ownerLimit":80,"activityLimit":20,"warningRemaining":2,"allowUnknownPrice":true,"unknownPriceCeiling":1}
```
金额示例：`{"unit":"micros","currency":"CNY","ownerLimit":5000000,"activityLimit":2000000,"warningRemaining":100000,"allowUnknownPrice":false,"unknownPriceCeiling":0}`。
限额是当前账本周期的累计上限；修改配置不清空已花费或在途预留。不自动按日期清零，不跨设备共享同一份可花额度。换币种/单位不能把旧账清零。未知价格需要主人显式开启保守上限，界面始终显示不保证实际金额。

`budget.reserve`（发起实际调用前）：
```json
{"requestId":"phone-task-42-round-1","scope":"owner:local","lane":"owner","purpose":"chat","maximum":1,"priceKnown":false}
```
`lane` 仅为 `owner|activity`。`maximum` 是本次最坏情况上界；次数模式必须为 1。相同 `requestId`、范围、用途、通道、上界和价格状态返回原预留，不重复扣减；同 ID 改参数报错。金额已知时由宿主按输入及最大输出计算上界；未知金额只能使用不超过主人配置的 `unknownPriceCeiling`，不能把估计说成真实账单。

返回：`{"reservationId":"phone-task-42-round-1","state":"reserved","duplicate":false,"reservation":{...},"budgets":{...}}`。
**准备发 HTTP 前再次传同样载荷并添加 `submitted:true`**，原子变为 `sent`；必须先存在预留。第一次创建返回的 `duplicate=false` 才允许启动新执行器；重复点击拿到旧 ID 不得重复发送。

`budget.settle`：
```json
{"requestId":"phone-task-42-round-1","actualKnown":true,"actual":1}
```
次数模式已发调用按 1 结算；金额模式以可信用量/账单结算。重复同值结算幂等，不同值报冲突。`actualKnown:false` 保留整笔预留并变为 `outcome_unknown`，不按 0 退款。实际账单超过预留会如实计入并标记超额，停止该通道新请求；不能声称客户端可以阻止服务端超收。

`budget.release`：`{"requestId":"..."}`。仅尚未 `sent` 的预留可无损释放；发出后的取消、断网、客户端超时均变为 `outcome_unknown`，保留上限。之后只能凭可信结算解除。传 `reason` 只供说明，不作为退款证据。

`budget.status` 返回 `configured,unit,currency,owner,activity,stopActivity,unknownPrice,monetaryGuarantee`。两个通道均含 `limit,spent,held,remaining`。接近活动额度时返回 `stopActivity=true` 并通过 `ports.emit("budget.changed",...)` 通知宿主停止新活动及取消可取消的在途活动；主人通道独立。取消不等于退费；不降模型质量继续消费。

## 内容、注意力与备份

- `memory.upsert`：`{id?,scope?,text,kind?,factKey?,occurredAt?,sourceRefs?:[{kind,id,scope}],shareSummary?,expectedRevision?}`。`kind=fact|inference|preference|relationship|event|task`；模型候选须有现存同范围来源。`factKey` 标记同一事实槽，新发生的记录为当前值，旧值保留。主人的纠正可带旧 `id` 与 `expectedRevision` 防止覆盖并发编辑。
- `memory.search`：`{scope?,query?,includeForgotten?,includeHistory?,shareSummary?}`；主动检索默认排除忘掉和已失效条目，主人显式查看可包含。
- `memory.forget`：`{scope?,id,forgotten:true}`；`forgotten:false` 为主人恢复主动记忆。
- `memory.delete`：`{scope?,id}`；删除记忆、其受保护来源与全部引用派生条目，返回 `deleted` 及新 `contentEpoch`。可能连带删除同一原消息衍生的其他摘要；界面须显示这一含义。
- `memory.history`：`{scope?,id}`，返回修订与来源；删除后不再返回旧文字。
- `diary.list/correct/delete`：列表带 `scope?,query?`；纠正带 `id,text,expectedRevision?`；删除带 `id`。日记只由已存在的经历来源生成，保存第一人称事实记录；模型推断不伪装成体验。
- `interest.list/upsert/delete`：`{id?,scope?,text,sourceRefs?,expectedRevision?}`；来源权限与删除规则同记忆。
- `attention.set`：`{mode:"available"|"busy"|"quiet",until?:时间戳,maxBackgroundTasks?:整数}`。`busy` 必须有未来结束时间；普通事暂存不主动打扰，重要事件保留独立优先级。不做口令/短语匹配。`attention.status` 查看实际到期状态与待处理事件。
- `backup.export`：`{password}` 返回 `{text:加密信封}`；`backup.restore`：`{password,text}`。明文数据不返回 UI；凭据、发送授权和在途可执行状态不恢复。删除墓碑合并优先，预算保守合并，不能通过旧备份重置额度或复活已删数据。

## 接线约束

Engine 在构造上下文时取得 `store.contentEpoch()`，保存模型输出前使用 `store.commitContext(epoch) { ... }`；删除/忘掉发生后旧上下文必须取消或重新生成，不能落库/发送。所有模型历史改用 `store.contextMessages(scope, limit)`，避免忘掉的来源从历史重新进入上下文。`memoriesFor` 将自动过滤忘掉/失效数据。

`onEvent` 使用 `type+sourceId` 去重。`observation.summary` 可带 `meaningful:true,summary,sourceKind:"observation"`，`conversation.message` 可带 `meaningful:true,memory:{text,kind,factKey},sourceKind:"message"`；必须有同范围实际来源才能保存。`action.completed` 要求实际任务状态为 `done`，形成来源关联的第一人称日记。所有内容必须符合公开/私密边界，不传隐藏推理。

## 来源与进度

- 沿用并改造 [mem0 存储模块](https://github.com/mem0ai/mem0/blob/26cdefb46600c0fa5953c4b5ee5003c125539fc4/mem0/memory/storage.py)，Apache-2.0：保留现有 Android 事务历史适配，增加删除屏蔽、修订和持久化；不采用仅保留十条消息的淘汰逻辑。
- 只读参考 [Graphiti 删除实现](https://github.com/getzep/graphiti/blob/72b5c59c06607e09ea855be61b6ff5a3446fc951/graphiti_core/graphiti.py#L2174)，Apache-2.0：独立实现同步删除闭包与代际屏蔽，未复制或安装其代码。
- 只读参考 [LiteLLM 预算预留](https://github.com/BerriAI/litellm/blob/b7da9a275581ccec82538428dfd0b84bbc4fb6fc/litellm/proxy/spend_tracking/budget_reservation.py#L231)，该路径 MIT：独立实现端侧整数预留与未知费用保留，未复制或安装框架。

当前：接口已固定；实现和验证进行中。尚未声明 Android 构建、模拟器、真机或真实模型调用通过。

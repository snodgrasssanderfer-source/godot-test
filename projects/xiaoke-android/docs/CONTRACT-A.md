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
**准备发 HTTP 前再次传同样载荷并添加 `submitted:true`**，原子变为 `sent`；必须先存在预留。返回的 `sendAuthorized=true` 仅出现一次，只有此次领取者可以开始 HTTP，重复领取返回 false。第一次创建返回的 `duplicate=false` 才允许启动新执行器；重复点击拿到旧 ID 不得重复发送。

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

Engine 用 `store.contextSnapshot(scope, query)` 一次取得 `epoch/messages/memories/sourceRefs`，保存模型输出及工具派生结果前使用 `store.commitContext(ticket.getLong("epoch"), ticket.getJSONArray("sourceRefs")) { ... }`；该方法自动把本轮来源附到内部保存的派生记录。不要只检查代际而丢掉来源。删除/忘掉发生后旧上下文必须取消或重新生成，不能落库/发送。所有模型历史改用 `store.contextMessages(scope, limit)`，避免忘掉的来源从历史重新进入上下文。`memoriesFor` 将自动过滤忘掉/失效数据。

`onEvent` 使用 `type+sourceId` 去重。`observation.summary` 可带 `meaningful:true,summary,sourceKind:"observation"`，`conversation.message` 可带 `meaningful:true,memory:{text,kind,factKey},sourceKind:"message"`；必须有同范围实际来源才能保存。`action.completed` 要求实际任务状态为 `done`，形成来源关联的第一人称日记。所有内容必须符合公开/私密边界，不传隐藏推理。

## 来源与进度

- 沿用并改造 [mem0 存储模块](https://github.com/mem0ai/mem0/blob/26cdefb46600c0fa5953c4b5ee5003c125539fc4/mem0/memory/storage.py)，Apache-2.0：保留现有 Android 事务历史适配，增加删除屏蔽、修订和持久化；不采用仅保留十条消息的淘汰逻辑。
- 只读参考 [Graphiti 删除实现](https://github.com/getzep/graphiti/blob/72b5c59c06607e09ea855be61b6ff5a3446fc951/graphiti_core/graphiti.py#L2174)，Apache-2.0：独立实现同步删除闭包与代际屏蔽，未复制或安装其代码。
- 只读参考 [LiteLLM 预算预留](https://github.com/BerriAI/litellm/blob/b7da9a275581ccec82538428dfd0b84bbc4fb6fc/litellm/proxy/spend_tracking/budget_reservation.py#L231)，该路径 MIT：独立实现端侧整数预留与未知费用保留，未复制或安装框架。

当前：原生 LifeModule/存储/预算/删除/备份已实现，24 项新核心 JVM 测试及 14 项原陪伴回归通过（合计 38）；原生源码编译通过。独立 LifePanel 已实现，10 项交互测试、TypeScript 类型检查与模块 ESLint 通过。11 项 Android SQLite 集成测试源码已补充并编译，但本执行器未运行；尚未声明 APK 构建、模拟器、真机或真实模型调用通过。

## 主线可立即使用的接线结果

- 构造一次 `LifeModule(context, engine.store, hostPorts)`，把插件 `module` 的 life 域路由到它；只把宿主生成的 principal 传入。
- `snapshot(principal)` 已返回正式五类数据。`memory.search/diary.list/interest.list` 返回 `{items:[...]}`，其余更新动作返回实际已保存对象。
- 每轮请求 `reserve` → 同载荷加 `submitted:true`，仅 `sendAuthorized:true` 可发送 → 完成可信结算；取消前未发出可 release，发送后未知保留。
- 模型上下文必须使用 `contextSnapshot`，写回复/模型记忆必须放入带 sourceRefs 的 `commitContext`。监听 `life.contentChanged` 取消受影响的旧请求；任何外发还要在提交前再次核对代际。
- 忙碌/安静时宿主读取 `attention.status.mayInterruptOrdinary=false`，普通提醒留在待办；`maxBackgroundTasks` 供宿主活动队列限流。急停和隐藏浮球不写 attention。
- 本轮不包含独立语义抽取模型。沿用已有 remember 工具，并接收明确有来源、标记 meaningful 的事件自动保存；所有自动总结标为 unverified 或 inference。
- 共享概要须由主人填 `shareSummary` 和 `shareWithScopes:[明确会话或"*"]`。跨会话检索只返回 `{id,summary}`；没有授权概要时返回空数组。
- UI 入口可导入 `LifePanel` 并传 `{state: snapshot.modules.life, run:(action,data)=>command("module",{domain:"life",action,data}), available:nativeCompanion}`。主线需在动作完成及 moduleState 通知后刷新快照。

## 最终接线补充

- `docs/INTEGRATION-A.patch` 是针对固定基线的 UI/共享快照接线提案，已做 `git apply --check`；没有直接修改主线文件。它替换旧记忆页，避免旧页面仍显示“删除后原消息保留”的错误提示。`LifePanel.saveFile(name, encryptedText)` 接已有 `saveDocument` 系统文件保存；用户取消时不得提示已导出。原生模块路由、模型队列、权限入口仍由主集成接线。
- `contextSnapshot` 默认提供最近 60 条未抑制消息；可显式把 `historyLimit` 设为 1–1000。完整历史仍在库中。模型 `recall` 若增添更早来源，宿主须把实际返回记录的 `{kind,id,scope}` 加入本次 ticket.sourceRefs，然后继续在同一 epoch 下处理工具结果。
- 在每次继续发模型请求、发布流式文字、保存 partialText/最终回复和外发前核对 epoch。保存派生结果需使用带来源数组的 `commitContext` 重载；只有 `commitContext(epoch)` 的旧重载保留兼容，不能单独提供完整派生删除保证。
- `life.contentChanged` 的删除事件携带 `deleted:[{kind,id,scope}]`；墓碑永久保存在本地，可从 `store.tombstones()` 获取。主线应把删除元数据交给 C 的加密同步 outbox。此模块没有声称离线设备已收到删除、已发平台内容已撤回或第三方提供者已清除其副本。
- 对没有上下文来源标记的旧版助手回复/含正文任务，自最早被删或忘掉的来源时间起在相同 scope 保守清理/抑制，以免旧摘要继续泄漏。新回复使用 contextEpoch 与完整来源关系。不同 scope 的独立记录不会因此清空。
- 临时媒体过期后，记忆/日记保留已产生的有来源摘要，并返回 `evidenceMissing=true`。UI 明示来源不可用；模型遇到该标记应说明只剩记录，不虚构缺失经历。
- 群聊自动事实槽有稳定来源 actorId 时按 actor 隔离；没有可靠 actorId 时不根据昵称合并事实。主人身份仍只信任宿主的 CommandPrincipal。
- 数据库沿用原生 SQLite，未引入 SQLCipher；备份沿用 AES-256-GCM 与 PBKDF2，新增内容、修订、墓碑与预算均包含在加密载荷中。未声称完整数据库静态加密。加密导出目前限制明文 11 MiB，超限报错，不截断导出。
- mem0 许可证与声明沿用已有 `source/third-party/mem0-Apache-2.0.txt`、`source/docs/COMMUNITY_REUSE.md`，基线已将完整许可打包为 APK legal asset。没有新增框架运行依赖。

历史备份边界：保留口令的旧备份文件仍可在应用外解密；本轮没有逐备份密钥撤销机制。墓碑只保证已有墓碑或导入最新墓碑的恢复路径不复活内容。全新设备仅持有删除之前的旧备份时，必须先取得最新删除记录；不能声称它已知晓未来的删除。


## 可直接核对的状态示例

下例是合成接口样例：主人通道一次未知价格调用已发送、随后断网；未凭空退款。状态数字为 calls 单位，实际实现返回额外说明字段。

    {
      "configured": true, "unit": "calls", "currency": "",
      "owner": {"limit":80,"spent":0,"held":1,"remaining":79,"unknownRequests":1,"overrun":false},
      "activity": {"limit":20,"spent":0,"held":0,"remaining":20,"unknownRequests":0,"overrun":false},
      "warningRemaining":2,"stopActivity":false,"stopOwner":false,
      "unknownPrice":{"allowed":true,"ceiling":1},"monetaryGuarantee":false
    }

对应预留的 state 为 outcome_unknown。之后 budget.settle 的 actualKnown=true、actual=1 才把 held 从 1 改为 0、spent 从 0 改为 1，remaining 仍为 79。不能在 HTTP 发送之后用 release 腾出这一次调用额度。

注意力示例：

    {"mode":"quiet","until":0,"maxBackgroundTasks":2,"mayInterruptOrdinary":false,"expired":false,"pendingEvents":[]}

查看最终验证、接线顺序及明确未运行项：[VALIDATION-A.md](VALIDATION-A.md)。

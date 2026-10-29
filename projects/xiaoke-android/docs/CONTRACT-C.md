# C：社交、持久任务与加密同步

基线 `34290cab4eac3b75caf92c7c89711bf5727d302b`；独立分支 `companion/c-social-20261029`。首个实现提交 `48a13db`，后续修复见该分支 HEAD。状态：可以接线集成，未声称真机或真实 QQ 验证通过。

实现入口：`social.SocialModule(context, store, ports) : ModuleEndpoint`；前端 `src/companion/social/SocialPanel.tsx` 导出 `SocialPanel`，使用正式 `ModulePanelProps`。本模块不改变根界面、共享模型队列、记忆数据库或预算所有权。

## 命令载荷

所有主人权限来自宿主构造的 `CommandPrincipal`，群消息内的 owner/actor/channel 字段不起作用。批量批准仅限 `owner_ui` 或宿主已验证的 `owner_qq` 私聊。

- `social.configure {autonomousDailyPost:boolean, autonomousReplies:boolean}`：只设置低风险活动自主权。
- B站追加 `bili.configure/inspect/plan` 与点赞/关注自主开关，已接入同一 SocialPanel 和审批箱；完整兼容载荷、流程、协议来源与主动 QQ 添加限制见 [C-SOCIAL-EXTENSION.md](C-SOCIAL-EXTENSION.md)。
- `social.plan {idempotencyKey,kind,account,target,content,scope?,sourceIds?,payload?,expiresAt?}`：kind 为 reply/post/comment/add_friend/join_group/leave_group；target 为实际账号、群号或保存过的动态引用 ID。响应为持久化动作，含 `id,digest,state,effect,target,content,expiresAt`。自主 post/reply 仍经来源和宿主 authorizeEffect 检查。reply 的 payload 必须含 `targetType:'group'|'private'`；主人可指定已有会话 scope，后台 scope 只能取宿主身份的 scope；账号、scope、目标、会话采集授权必须一致。
- `approvals.list {}`；`approvals.approveBatch {items:[{id,digest}]}`；`approvals.reject {id}`；批准精确绑定账号、目标、内容、参数、来源与过期时间，修改须重新规划；同一幂等键不能换内容。
- `social.execute {id}`：发送前先持久化 submitting；返回回执 done/failed/outcome_unknown。结果未知不可重试；`social.resolve {id,delivered:boolean,evidence:string}` 仅供主人查证，未发出则终止旧动作。
- `jobs.create {idempotencyKey,kind:'summary'|'reminder',scope?,text,dueAt?,condition?:'online'|'manual',sourceIds?}`；`jobs.list {}`；`jobs.cancel {id}`；`jobs.resume {id}`。朋友只可在自己 scope 请求有界任务，不能设 owner lane、工具安装或发送授权。模型任务统一走 HostPorts activity lane。
- `activity.list {}`；`media.list {}`；`media.retain {id,retention:'curated'|'explicit'}`；`media.delete {id}`。普通附件保持 transient；保留需主人明确选择。
- `media.import {name,mime,base64,retention:'transient'|'curated'|'explicit'}`：本机主人导入，最大 6 MiB；图片、MP3/WAV、MP4、PDF，存入独立加密文件。仅保存素材，不声称已解读或已发送。
- `social.conversationMedia {scope,images:boolean,audio:boolean,video:boolean}`：主人为实际 OneBot 会话分别开启媒体读取。接收图片最多 4 MiB 后缩放；语音/视频最多 2 MiB，整体模型附件文本约 3 MiB。临时观察沿用宿主十分钟内存缓存；未授权、超限、过期和 SILK/AMR 等不支持编码均明确标注。
- `social.feeds {cursor?}`：读取当前桥账号的空友爱看动态，响应 `{items:[{id,summary}],nextCursor}`；完整 feed 保存在本机，评论只能引用同账号已读取的 ID。
- `social.forward {scope,id}`：仅展开此已授权会话实际收到的转发 ID，使用 get_forward_msg；限制四层/256 节点，并标明转发内容不是当前发言人的授权。
- `sync.configure {enabled,endpoint?,token?,key?}`；`sync.status {}`；`sync.run {}`。endpoint 仅 HTTPS；token 与 32 字节随机共享密钥仅保存在本机加密槽，快照不回传。停用时不出网；重连先拉删除再补传，上传仅 AES-GCM 密文和不含正文的索引，不重发原模型调用。

## 宿主接线

- `ports.authorizeEffect({type,target,scope,contentDigest,requestId}, principal)` 在执行外部动作前调用，模块目标审批仍独立生效。type 对应 social_send/public_post/public_comment/add_friend/join_group/leave_group。
- `ports.requestModel({scope,lane:'activity',purpose:'social.background.summary',prompt,maxOutputTokens:2048,taskId}, callback)`；处理 queued/delta/completed/failed/cancelled，终止后迟到回调丢弃。
- `onEvent` 接收 conversation.message、observation.summary、model.completed、connectivity.changed、data.deleted。数据删除载荷为 `{sourceIds:[...]}`；先安装删除栅栏，再清事件、动作正文、任务和媒体派生内容。事件入队只补传结果，不执行模型重试。
- 模块发出 moduleState、social.job.completed、social.reminder、social.effect.completed、social.event。后台提醒和普通社交消息由主线注意力模块决定何时呈现，不直接抢占主人语音。
- social.reminder/social.job.completed 的 `sourceId` 是持久通知 ID；宿主据此去重。任务已完成但宿主未接收的通知会在重启后继续交付，不重跑模型。`social.event` 载荷为 `{type,scope,sourceId,data,replayed?}`，根协调器转给 life；不得再把它反射成另一条外部消息。下载的历史经历标记 `replayed:true`，保留原 sourceId；不得把历史请求当新任务/发送指令。OneBot、同步及删除事件都先写入本机通知队列再交付，宿主 emit 应在事实已持久入账后返回，失败应抛出；仅向 WebView 广播并不等于入账。
- OneBot 原生连接接收事件转发到 C；连接状态、身份与实际平台 API 调用复用既有桥。本轮不运行第三方 QQ 客户端。
- SocialModule 初始化时为旧 OneBotBridge.send 和 QQ 通知回复安装 HostPorts 外发门禁；未构造模块或模块关闭时这两条路径拒绝发送。宿主仍须在自己所有 B站/UI 发送入口检查 public_comment/social_send，C 不改 B 组 Device 的点击实现。
- A 的加密备份可直接调用 `social.exportBackup()`，返回不含凭据或可执行批准的原生 JSON；在 A 加密封装内写入。恢复调用 `social.restoreBackup(data, principal)`：先合并墓碑，保留本地删除栅栏，再合并未删除记录；取消当前模型请求，当前 queued/running/waiting 和导入任务都变 interrupted；当前及导入的旧批准全部失效。提交中的动作变 outcome_unknown，不再发送。素材只含索引，原文件不在本轮便携备份中；恢复后标注需要重导。

## 来源与依赖

NapCat 协议核对提交 https://github.com/NapNeko/NapCatQQ/tree/ba0a53dd372dbc4c4bfc83cf7132997459605210 ：`packages/napcat-onebot/types/message.ts`、`action/router.ts`、`action/extends/SendQzoneMsg.ts`、`action/go-cqhttp/GetForwardMsg.ts`、好友/加群/退群与公开动态评论接口。LICENSE 为 Limited Redistribution License，仅独立实现协议客户端，不复制或分发源码。

比较阅读 https://github.com/aioqzone/aioqzone/blob/e9322a763898893a8daa37ee7c2b1f821958cd4d/src/aioqzone/api/h5/model.py ，AGPL-3.0-or-later；本轮不安装、不复制。已有 AstrBot 衍生 OneBotProtocol 的许可标注保留。继续使用项目已锁定 OkHttp 4.12.0、Kotlin 2.0.21、JUnit 4.13.2；不增加运行时框架。

## 验证进度

已完成：持久审批、活动 lane 后台摘要、提醒与条件续接、取消/重启栅栏、受控来源删除、加密素材、AES-GCM 密文同步、回环 SQLite 接收端、SocialPanel UI、OneBot 多模态与分会话开关。

已通过：31 项 Kotlin/JUnit 测试、5 项真实回环 HTTP/SQLite 测试、5 项 React UI 测试、模块 ESLint、完整前端 TypeScript/Vite 构建。新增 5 项交付/恢复边界测试独立放在 SocialDeliveryTest，不改主线正在适配的 SocialRuntimeTest。Kotlin 核心测试使用项目版本 2.0.21/JUnit 4.13.2；不冒充 Android 设备执行。

Android 全项目 `:app:compileDebugKotlin` 已通过（包括 SocialModule、SocialVault 和桥接生产源码）；首次完整 Gradle 配置因 NDK 29 未安装/许可未初始化而停止。后续检查使用仓库外 init script 仅排除 CMake，JDK 21/Android 35/Gradle 8.11.1，没有修改项目构建文件。该证据只证明 Java/Kotlin 接线编译，不能代替 JNI/APK 构建。最终 APK 由主线集成构建。

明确未实现：主动搜索并发起任意加好友/加群（已核对当前 NapCat 没有该协议，处理实际收到的好友请求/群邀请已经实现）、SILK/AMR 解码、无限大小媒体处理、跨设备素材字节同步、系统进程被杀后的精确提醒。B站点赞/关注/评论/公开单文件下载已追加原生适配与 UI；实际范围和新增验证见扩展文档。未执行：真实 QQ/B站登录、发送/点赞、付费模型、APK 安装、Android 真机验收。

## 主线立即可用的接线片段

```kotlin
val social = SocialModule(context.applicationContext, store, ports)
// 由根插件已有 worker 调用；principal 只来自宿主认证。
val result = social.execute(action, data, principal)
snapshot.put("social", social.snapshot(principal))
social.onEvent(ModuleEvent("model.completed", scope, messageId,
    JSONObject().put("text", visibleCompletedText)))
social.onEvent(ModuleEvent("data.deleted", scope, deletionOperationId,
    JSONObject().put("sourceIds", JSONArray(deletedSourceIds))))
// 对应进程/服务实例结束时调用，不绑定浮球隐藏。
social.close()
```

```tsx
import { SocialPanel } from './social/SocialPanel'
<SocialPanel state={snapshot.modules.social} available={true}
  run={(action, data = {}) => run('module', {domain:'social', action, data})} />
```

`snapshot.modules.social` 包含 `accounts/approvals/activity/scheduled/sync/service`，另含 media。主线无需额外运行时依赖、Manifest 权限或服务注册；沿用应用前台服务生命周期，不新增常驻录音。宿主传入网络事件 `{online:true|false}`，已配对同步在恢复联网时和进程存活期间每分钟尝试一批；自动同步与主人模型队列独立。

后台摘要会同时核对连接事件和现有 `settings.networkEnabled/paused`。关闭联网时保持 queued，反复 tick 不调用模型或产生失败重试；恢复后按原条件接续。提醒不依赖模型和网络，通知先交给根协调器的注意力流程。

整机备份接线顺序：根协调器先暂停对外模型/发送入口；读取并认证解密 A 的备份后，先恢复所有删除栅栏，再对 C 调用上面的 `restoreBackup`，最后恢复可见历史/UI。禁止从旧 store JSON 恢复 executable grant、automaticGrant、运行中模型 ID 或发送队列。C 只撤销自己表中的授权；旧 CompanionStore task 和预算保留属于根/A，须在整机恢复入口清理，不能只调用 C 就声称整机恢复完成。C 同步删除发出 `data.deleted {sourceIds,sourceHashes}`；主线将 raw ID 及可识别的哈希栅栏交给 A，防止共享概要或恢复备份重新出现。

根 HostPorts 接收 `social.reminder`/`social.job.completed` 时，使用其 sourceId、scope、text/result、state、dueAt/completedAt 生成可审计的事实观察（失败/取消不写成完成），入 A 的事实账本与注意力待处理队列。提醒的显示/说话时间由注意力决定；通知不自动调用模型，也不把朋友请求升级为主人权限。

状态示例：

```json
{"id":"action-...","kind":"comment","effect":"public_comment","account":"10001","target":"feed-...","content":"合成拟发内容","digest":"...","state":"pending","expiresAt":2000000000000}
```

正常批准 `pending → approved → submitting → done`；拒绝为 rejected；断线/缺少可核对回执为 outcome_unknown，只有主人查证后结束旧动作；不会转回待发送。宿主拒绝 effect 时保持未提交并显示原因。后台任务 `queued → running → done/failed/cancelled`，重启中断标 interrupted，需显式 resume 才允许新一次收费请求。

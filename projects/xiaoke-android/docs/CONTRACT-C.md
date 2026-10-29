# C：社交、持久任务与加密同步

基线 `34290cab4eac3b75caf92c7c89711bf5727d302b`；独立分支 `companion/c-social-20261029`。状态：实施中，尚未声称真机或真实 QQ 验证通过。

实现入口：`social.SocialModule(context, store, ports) : ModuleEndpoint`；前端 `src/companion/social/SocialPanel.tsx` 导出 `SocialPanel`，使用正式 `ModulePanelProps`。本模块不改变根界面、共享模型队列、记忆数据库或预算所有权。

## 命令载荷

所有主人权限来自宿主构造的 `CommandPrincipal`，群消息内的 owner/actor/channel 字段不起作用。批量批准仅限 `owner_ui` 或宿主已验证的 `owner_qq` 私聊。

- `social.configure {autonomousDailyPost:boolean, autonomousReplies:boolean}`：只设置低风险活动自主权。
- `social.plan {idempotencyKey,kind,account,target,content,sourceIds?,payload?,expiresAt?}`：kind 为 reply/post/comment/add_friend/join_group/leave_group；target 为实际账号、群号或保存过的动态引用 ID。响应为持久化动作，含 `id,digest,state,effect,target,content,expiresAt`。自主 post/reply 仍经来源和宿主 authorizeEffect 检查。
- `approvals.list {}`；`approvals.approveBatch {items:[{id,digest}]}`；`approvals.reject {id}`；批准精确绑定账号、目标、内容、参数、来源与过期时间，修改须重新规划；同一幂等键不能换内容。
- `social.execute {id}`：发送前先持久化 submitting；返回回执 done/failed/outcome_unknown。结果未知不可重试；`social.resolve {id,delivered:boolean,evidence:string}` 仅供主人查证，未发出则终止旧动作。
- `jobs.create {idempotencyKey,kind:'summary'|'reminder',scope?,text,dueAt?,condition?:'online'|'manual',sourceIds?}`；`jobs.list {}`；`jobs.cancel {id}`；`jobs.resume {id}`。朋友只可在自己 scope 请求有界任务，不能设 owner lane、工具安装或发送授权。模型任务统一走 HostPorts activity lane。
- `activity.list {}`；`media.list {}`；`media.retain {id,retention:'curated'|'explicit'}`；`media.delete {id}`。普通附件保持 transient；保留需主人明确选择。
- `sync.configure {enabled,endpoint?,token?,key?}`；`sync.status {}`；`sync.run {}`。endpoint 仅 HTTPS；token 与 32 字节随机共享密钥仅保存在本机加密槽，快照不回传。停用时不出网；重连先拉删除再补传，上传仅 AES-GCM 密文和不含正文的索引，不重发原模型调用。

## 宿主接线

- `ports.authorizeEffect({type,target,scope,contentDigest,requestId}, principal)` 在执行外部动作前调用，模块目标审批仍独立生效。type 对应 social_send/public_post/public_comment/add_friend/join_group/leave_group。
- `ports.requestModel({scope,lane:'activity',purpose:'social.background.summary',prompt,maxOutputTokens:2048,taskId}, callback)`；处理 queued/delta/completed/failed/cancelled，终止后迟到回调丢弃。
- `onEvent` 接收 conversation.message、observation.summary、model.completed、connectivity.changed、data.deleted。数据删除载荷为 `{sourceIds:[...]}`；先安装删除栅栏，再清事件、动作正文、任务和媒体派生内容。事件入队只补传结果，不执行模型重试。
- 模块发出 moduleState、social.job.completed、social.reminder、social.effect.completed、social.event。后台提醒和普通社交消息由主线注意力模块决定何时呈现，不直接抢占主人语音。
- OneBot 原生连接接收事件转发到 C；连接状态、身份与实际平台 API 调用复用既有桥。本轮不运行第三方 QQ 客户端。

## 来源与依赖

NapCat 协议核对提交 https://github.com/NapNeko/NapCatQQ/tree/ba0a53dd372dbc4c4bfc83cf7132997459605210 ：`packages/napcat-onebot/types/message.ts`、`action/router.ts`、`action/extends/SendQzoneMsg.ts`、`action/go-cqhttp/GetForwardMsg.ts`、好友/加群/退群与公开动态评论接口。LICENSE 为 Limited Redistribution License，仅独立实现协议客户端，不复制或分发源码。

比较阅读 https://github.com/aioqzone/aioqzone/blob/e9322a763898893a8daa37ee7c2b1f821958cd4d/src/aioqzone/api/h5/model.py ，AGPL-3.0-or-later；本轮不安装、不复制。已有 AstrBot 衍生 OneBotProtocol 的许可标注保留。继续使用项目已锁定 OkHttp 4.12.0、Kotlin 2.0.21、JUnit 4.13.2；不增加运行时框架。

## 验证进度

已完成：研究资料私下物化可读、正式基线与契约核对、旧协议/发送实现读取、测试工具链准备。

待完成：具体实现、原生单测与回环协议测试、前端测试及编译、主线接线 patch。未执行：真实 QQ/B站登录、发送/点赞、付费模型、APK 安装、Android 真机验收。

# C 社交补全：接口与协议核对

2026-10-29，基于 C 已发布 `59dd22abb951444a1636b7783ebedfe958d3244d` 继续实现；以下接口为向后兼容新增，状态仍复用 CONTRACT-C。本文件会随实际验证更新，不代表已登录账号验证。

计划新增：

- `bili.configure {enabled,cookie?}`：仅本机主人，认证资料进入本机加密槽；读取登录身份核对账号，UI/备份不回传 Cookie。停用不出网。
- `bili.inspect {videoId}`：公开读取视频 ID、av ID、分P cid、UP 主稳定 ID 和标题，用来固定动作目标；元数据不冒充观看证据。
- `bili.plan {kind:'like'|'follow'|'comment'|'download',resourceId,idempotencyKey,motivation,content?,sourceIds?,maxBytes?}`：资源必须来自实际 inspect；like/follow 可在产品自主权开启后执行，comment/download 始终进入现有精确目标审批。所有外部动作仍检查宿主 effect，身份不从 JSON 反序列化。
- `social.configure` 增量字段 `autonomousBiliLikes/autonomousBiliFollowing`，不清除其他自主设置。
- 下载仅保存平台允许公开读取的首P、单文件 MP4，限制实际下载字节，保存到现有加密素材目录；不请求付费/DRM内容，不使用会话 Cookie 下载任意 URL。

实际接线实现完成前，不向用户展示可执行按钮。

## 主动 QQ 添加的已核对限制

当前 NapCat 主分支 SHA `ba0a53dd372dbc4c4bfc83cf7132997459605210`（2026-10-27）。核对了完整 [OneBot action 路由](https://github.com/NapNeko/NapCatQQ/blob/ba0a53dd372dbc4c4bfc83cf7132997459605210/packages/napcat-onebot/action/router.ts)：没有主动添加好友或主动申请入群路由。

[SetFriendAddRequest.ts](https://github.com/NapNeko/NapCatQQ/blob/ba0a53dd372dbc4c4bfc83cf7132997459605210/packages/napcat-onebot/action/user/SetFriendAddRequest.ts) 通过 flag 查找 getBuddyReq 的现有请求，不存在则 `No such request`。[SetGroupAddRequest.ts](https://github.com/NapNeko/NapCatQQ/blob/ba0a53dd372dbc4c4bfc83cf7132997459605210/packages/napcat-onebot/action/group/SetGroupAddRequest.ts) 同样必须查得已有通知/邀请。不能拿目标 QQ/群号冒充 flag，也不能把处理他人入群申请当机器人主动入群。

项目方在 [#149 的明确回复](https://github.com/NapNeko/NapCatQQ/issues/149#issuecomment-2251871727) 表示不提供主动加群，现行可用路径是先由人加入，再邀请机器人。因此本轮已实现收到好友请求/群邀请后，目标、动机、参数、内容摘要一次审批，再调真实接口；主动申请仍是 **NapCat 协议不支持**，不是“已实现待测试”。没有虚构 `add_friend`/`join_group` wire API，没有安装新客户端或扩权。若以后改用经验证的 Android QQ UI 操作，需要 B 的受控输入驱动和新页面证据；本轮不把未知坐标点击当协议适配。

NapCat LICENSE 为 Limited Redistribution License。本轮只核对接口，独立实现客户端，不复制/分发其源码。

## B站来源

复用项目已标注 MIT 来源的 [iseenope/bilibili-mcp-server @ 20eedd09862aea2c160163007f45221c544651b2 / src/api/bilibili.ts](https://github.com/iseenope/bilibili-mcp-server/blob/20eedd09862aea2c160163007f45221c544651b2/src/api/bilibili.ts)：videoLike `/x/v2/view/like`、relationModify `/x/relation/modify`、reply `/x/v2/reply/add`、playUrl `/x/player/playurl`。已有 MIT 声明保留。独立 Android/OkHttp 适配不移植其自动写入重试、Cookie刷新、WBI失败降级逻辑；网络结果未知时停止，平台限制按返回结果处理。

这不是 B站官方开放平台保证，协议需未来真实账号受控验收。开发阶段只用合成请求/响应核对代码，不登录、不真实写入。

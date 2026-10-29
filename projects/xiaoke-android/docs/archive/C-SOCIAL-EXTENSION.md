# C 社交补全：接口与协议核对

2026-10-29，基于 C 已发布 `59dd22abb951444a1636b7783ebedfe958d3244d` 继续实现；以下接口为向后兼容新增，状态仍复用 CONTRACT-C。本文件会随实际验证更新，不代表已登录账号验证。

已实现的兼容新增接口：

- `bili.configure {enabled,cookie?,clearCredential?}`：仅本机主人，认证资料进入本机加密槽；读取登录身份核对账号，UI/备份不回传 Cookie。停用不出网，可清除本机凭据。凭据只允许 SESSDATA、bili_jct、DedeUserID、buvid3、buvid4，编码后最大 4096 字符。
- `bili.inspect {videoId}`：公开读取视频 ID、av ID、分P cid、UP 主稳定 ID 和标题，用来固定动作目标；元数据不冒充观看证据。
- `bili.plan {kind:'like'|'follow'|'comment'|'download',resourceId,idempotencyKey,motivation,content?,sourceIds?,maxBytes?}`：资源必须来自实际 inspect；like/follow 可在产品自主权开启后执行，comment/download 始终进入现有精确目标审批。所有外部动作仍检查宿主 effect，身份不从 JSON 反序列化。
- `social.configure` 增量字段 `autonomousBiliLikes/autonomousBiliFollowing`，不清除其他自主设置。
- 下载仅保存平台允许公开读取的首P、单文件 MP4，限制实际下载字节，保存到现有加密素材目录；不请求付费/DRM内容，不使用会话 Cookie 下载任意 URL。

`SocialPanel` 已嵌入 BilibiliPanel：本机账号核对、读取具体视频、选择动作、填写动机/拟发内容、生成动作，再进入共用审批及动作记录。快照新增 `bilibili`，accounts 中新增 `route:'bilibili'`；旧 QQ 项仍排在第一项。评论须有真实评论 rpid 回执，否则 outcome_unknown。下载仅在实际 MP4 字节已写入加密素材后返回完成；下载上限 16 MiB，不声明音轨完整性或观看理解已完成。

主线：现有 `command('module',{domain:'social',action,data})` 无需更改。HostPorts 仍必须允许/拒绝 `like/follow/public_comment/download`；后台 like/follow 的依据是本机已保存的相应自主开关，不是来自群聊的 owner 字段。comment/download 使用模块精确批准后保存在原生账本的主人 principal。`bili.configure.cookie` 只能来自 owner_ui，禁止根层把该参数写进聊天、事实账本或日志。

待发送记录中 platform=bilibili，kind 为 bili_like/bili_follow/bili_comment/bili_download。digest 包含真实视频/UP主目标、av/cid、拟发内容、下载上限、账号及有效期。下载 URL 在批准后向公开接口重新获取，不信任 UI/模型提供的任意 URL；CDN 下载请求不携带 Cookie，不跟随重定向，不自动重试写入。

## 进程恢复

模块重建后，queued 任务保留原时间/条件并自动接续；已经 running 的纯本地 reminder 重新排队，补交到期提醒且持久 sourceId 去重。running 的收费摘要标 interrupted，必须显式恢复；不会因为重启重复收费。已完成但尚未交付的提醒继续发送持久通知，成功后删除通知队列项。恢复旧备份仍采用更严格规则：全部旧 queued/running/waiting 任务先暂停，所有旧发送批准失效。系统 force-stop 到重新打开应用之间没有执行保证，无需新增后台权限或 SDK。

提醒/摘要调度与外部互动/下载使用不同轻量工作线程；下载不会占用提醒交付或主人的模型队列。停用的B站平台不阻塞队列中的 QQ 操作，关闭模块取消该模块的网络调用。

## 主动 QQ 添加的已核对限制

当前 NapCat 主分支 SHA `ba0a53dd372dbc4c4bfc83cf7132997459605210`（2026-10-27）。核对了完整 [OneBot action 路由](https://github.com/NapNeko/NapCatQQ/blob/ba0a53dd372dbc4c4bfc83cf7132997459605210/packages/napcat-onebot/action/router.ts)：没有主动添加好友或主动申请入群路由。

[SetFriendAddRequest.ts](https://github.com/NapNeko/NapCatQQ/blob/ba0a53dd372dbc4c4bfc83cf7132997459605210/packages/napcat-onebot/action/user/SetFriendAddRequest.ts) 通过 flag 查找 getBuddyReq 的现有请求，不存在则 `No such request`。[SetGroupAddRequest.ts](https://github.com/NapNeko/NapCatQQ/blob/ba0a53dd372dbc4c4bfc83cf7132997459605210/packages/napcat-onebot/action/group/SetGroupAddRequest.ts) 同样必须查得已有通知/邀请。不能拿目标 QQ/群号冒充 flag，也不能把处理他人入群申请当机器人主动入群。

项目方在 [#149 的明确回复](https://github.com/NapNeko/NapCatQQ/issues/149#issuecomment-2251871727) 表示不提供主动加群，现行可用路径是先由人加入，再邀请机器人。因此本轮已实现收到好友请求/群邀请后，目标、动机、参数、内容摘要一次审批，再调真实接口；主动申请仍是 **NapCat 协议不支持**，不是“已实现待测试”。没有虚构 `add_friend`/`join_group` wire API，没有安装新客户端或扩权。若以后改用经验证的 Android QQ UI 操作，需要 B 的受控输入驱动和新页面证据；本轮不把未知坐标点击当协议适配。

NapCat LICENSE 为 Limited Redistribution License。本轮只核对接口，独立实现客户端，不复制/分发其源码。

## B站来源

复用项目已标注 MIT 来源的 [iseenope/bilibili-mcp-server @ 20eedd09862aea2c160163007f45221c544651b2 / src/api/bilibili.ts](https://github.com/iseenope/bilibili-mcp-server/blob/20eedd09862aea2c160163007f45221c544651b2/src/api/bilibili.ts)：videoLike `/x/v2/view/like`、relationModify `/x/relation/modify`、reply `/x/v2/reply/add`、playUrl `/x/player/playurl`。已有 MIT 声明保留。独立 Android/OkHttp 适配不移植其自动写入重试、Cookie刷新、WBI失败降级逻辑；网络结果未知时停止，平台限制按返回结果处理。

这不是 B站官方开放平台保证，协议需未来真实账号受控验收。开发阶段只用合成请求/响应核对代码，不登录、不真实写入。

补充交叉核查：截至本轮，[bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect/blob/4c00347d4f3494318903eeb11fb00d7b9c1f8c68/README.md) 和 [Nemo2011/bilibili-api](https://github.com/Nemo2011/bilibili-api/blob/main/README.md) 均已关停并移除主分支实现，不能作为“当前接口仍兼容”的佐证。本轮没有从旧快照恢复这两个项目。B站适配的来源仍限于上面实际读到的固定 MIT 项目源码；无法在禁止真实账号操作的开发范围内验证写入接口在线兼容性，失败须按真实返回呈现。

## SILK / AMR 后续接入选项

Android 官方[格式支持表](https://developer.android.com/media/platform/supported-formats)列出 AMR-NB/WB。可用现有 Android MediaExtractor/MediaCodec 转成有界 PCM/WAV，再接已有模型附件通路，不新增第三方执行依赖；仍需在 v2520A 的实际 MIME/解码器与真语音文件上验收，不能把平台表当该设备实测。

SILK 候选为只读核对的 [kn007/silk-v3-decoder @ 507be6bca8ce1fb977a061481f1d79e8c610e309](https://github.com/kn007/silk-v3-decoder/tree/507be6bca8ce1fb977a061481f1d79e8c610e309)，核心接口 `silk/interface/SKP_Silk_SDK_API.h`。其核心保留 Skype 许可文本及不授予专利许可的声明，不能只按仓库顶层 MIT 概括。拟采用受限 JNI 解码 SDK 到 PCM/WAV，而不是运行任意 shell 转换器；需要先审阅固定源码与许可、编译产物大小/ABI、输入限长及错误处理，再向主人报具体新增依赖目标审批。当前仅阅读，未下载/安装/编译/运行该第三方解码器，仍显示 codec_unsupported。

## 验证与接续

本轮 Kotlin 核心与协议测试共 35 项，通过固定账号不匹配时禁止 POST、精确路径/表单/CSRF、写入断线只尝试一次、公开下载不带 Cookie/域名与字节上限、like/follow 自主与 comment/download 审批区别、重启补交提醒但不重跑收费任务等边界。B站协议测试通过 OkHttp 合成响应拦截检查真实 Request/RequestBody，不代表线上接口验收。

React 社交测试 6 项、模块 ESLint、完整 TypeScript/Vite 构建通过；Android 全项目生产 Kotlin 编译通过，仍是排除 CMake 的编译检查，无新 APK。没有新增运行时依赖，没有登录账号、实际点赞/关注/评论/下载远端视频，也没有跑新解码器。未来受控验收应先核对正确账号，选主人允许的视频，用审批箱确认评论/下载目标和上限，再检查真实回执及加密文件；遇平台限制即停止。

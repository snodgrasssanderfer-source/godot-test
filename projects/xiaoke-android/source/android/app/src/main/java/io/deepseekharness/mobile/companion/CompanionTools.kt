package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject

/** Pure declaration factory shared by the engine and request benchmarks. */
object CompanionTools {
    data class Input(
        val scope: String,
        val owner: Boolean,
        val ownersOwnChat: Boolean,
        val qqReady: Boolean,
        val onQq: Boolean,
        val lifeAvailable: Boolean,
        val socialAvailable: Boolean,
        val deviceAvailable: Boolean,
        val stickerNames: List<String> = emptyList(),
        val webTool: Boolean = false,
    )

    fun declarations(input: Input): JSONArray = with(input) {
        fun decl(name: String, description: String, fields: List<String>, required: List<String> = fields) = JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject().also { p -> fields.forEach { p.put(it, JSONObject().put("type", "STRING")) } }).put("required", JSONArray(required)))
        fun fields(vararg items: Pair<String, String>) = JSONObject().also { json -> items.forEach { (name, type) -> json.put(name, JSONObject().put("type", type)) } }
        fun structured(name: String, description: String, properties: JSONObject, actions: List<String>? = null) = JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()
                .put("data", JSONObject().put("type", "OBJECT").put("properties", properties))
                .also { if (actions != null) it.put("action", JSONObject().put("type", "STRING").put("enum", JSONArray(actions))) })
                .put("required", JSONArray(if (actions == null) listOf("data") else listOf("action", "data"))))
        JSONArray().put(decl("remember", "当场觉得重要、马上要记的事（聊完后系统还会自动整理一遍）。kind 为 fact/inference/preference/relationship/event/task/opinion，opinion 是你自己的看法（写成“我觉得……”）。" +
                "“昨天”“下周”这类说法换成具体日期。validFrom/validUntil 为 YYYY-MM-DD，表示从何时起、到何时为止成立；新内容让某条旧记忆不再成立时，把旧记忆的 id 填进 ends（旧的会保留为过去）。" +
                "entities 用逗号分隔人名地名游戏名。sourceId 留空就记在当前这条消息上。factKey 为同一事实槽的稳定标识。不能保存密钥、授权或思考过程",
                listOf("text", "kind", "sourceId", "factKey", "validFrom", "validUntil", "ends", "entities"), listOf("text", "kind")))
            .put(decl("recall", "搜索当前会话的长期记忆及历史消息。自动带上的记忆不全，问到具体的事又没找到时用它，可以换几种说法查", listOf("query")))
            .put(decl("job_status", "查你在后台做的事（看B站、读专栏、上网查、提醒、整理）现在怎样了：进行中、完成（含结果）、没成（含原因）", emptyList())).also { a ->
                if (webTool) a.put(decl("web_research", "上网查资料或读网页：query 写要查的事，urls 写要读的网址（多个用逗号隔开）。返回要点、来源和实际读到的网页。查到什么说什么，没查到就说没查到", listOf("query", "urls"), emptyList()))
                if (lifeAvailable) a.put(structured("life_action",
                    "查看记忆、日记、兴趣、注意力。" + (if (owner) "主人可以让你：interest.upsert 记兴趣（data.text）；attention.set 设注意力（data.mode=available/busy/quiet，until 为毫秒时间戳），只在主人明确当时意愿下调整；" else "") +
                        (if (ownersOwnChat) "memory.forget / memory.delete 忘掉或删掉一条记忆（data.id，先用 memory.search 找到）；diary.correct 改日记（data.id、data.text）；budget.status 看额度；pending.list 看待审清单。" else "") +
                        "不能修改人格或权限。",
                    fields("id" to "STRING", "query" to "STRING", "text" to "STRING", "mode" to "STRING", "until" to "INTEGER", "maxBackgroundTasks" to "INTEGER"),
                    LIFE_ACTIONS.filter { ownersOwnChat || (owner && it !in OWN_CHAT_LIFE_ACTIONS) || it in setOf("memory.search", "diary.list", "interest.list") }))
                if (!ownersOwnChat)
                    a.put(decl("propose_save", "聊天里有人发的图片值得留作参考时，提议存进主人的资料库（进待审清单，主人确认才存）。title 简短说明是什么图，reason 为什么值得留", listOf("title", "reason")))
                if (onQq || (owner && qqReady))
                    a.put(decl("poke", "QQ 戳一戳，朋友间偶尔的小动作，别和文字说同一个意思。" +
                        if (owner) "不填 target 就戳正在和你说话的人；主人让你戳谁就把 target 填成 QQ 号或“主人”，在群里戳再填 group（群号或群名）。每个会话每天最多 10 次" else "戳正在和你说话的人，这个群每天最多 1 次",
                        if (owner) listOf("target", "group") else emptyList(), emptyList()))
                if (owner && qqReady) {
                    val faces = io.deepseekharness.mobile.companion.chat.QqFaces.COMMON.joinToString("、")
                    val shelf = stickerNames.filter { it.isNotBlank() }.take(40)
                    a.put(decl("send_qq", "主人让你在 QQ 上发消息时用：target 填“主人”或 QQ 号，发到群就填 group（群号或群名，不填 target）。text 是要说的话（你自己的口吻）。" +
                        "face 填 QQ 自带表情名（多个用顿号隔开，比如 $faces），sticker 填你收藏的表情包名" + (if (shelf.isNotEmpty()) "（有：${shelf.joinToString("、")}）" else "") + "。" +
                        "发给主人和你能去的群会直接发出；发给别人的私聊先进待审清单，主人确认才发。", listOf("target", "group", "text", "face", "sticker"), listOf("text")))
                    a.put(decl("say_in_group", "主人让你去某个 QQ 群里说话时用：text 是你要在群里说的话（用你自己的口吻），group 填群号或群名。主人的要求就是授权，会直接发出。" +
                        "不确定是哪个群就别填 group，会返回能去的群让主人挑", listOf("group", "text"), listOf("text")))
                }
                a.put(decl("job_cancel", "取消一件排着或正在做的后台事：id 用 job_status 里的（job-… 是提醒和摘要，bg-… 是看B站、上网查）", listOf("id")))
                if (owner || scope.startsWith("qq:group:"))
                    a.put(decl("bili_search", "在 B站搜视频或专栏（kind=article），返回前几条的标题、UP、时长和 BV 号，挑一个再看", listOf("query", "kind"), listOf("query")))
                if (owner || scope.startsWith("qq:group:"))
                    a.put(decl("watch_bilibili", "对聊到的话题感兴趣时，自己去 B站看一部视频或读一篇专栏（videoId=BV 号，articleId=cv 号，或 query 关键词；读专栏时 kind=article）。后台进行、用活动额度；视频读字幕、关键帧、弹幕和热门评论，看完记成你自己的观察并写明实际看了哪部分", listOf("videoId", "articleId", "query", "kind", "reason"), listOf("reason")))
                if (socialAvailable) {
                    a.put(structured("social_plan", "规划社交动作，data={kind:reply/post/comment/add_friend/join_group/leave_group,account,target,content}。账号与目标必须来自已核对绑定；评论、加好友和加群进入精确审批队列，结果不得说成已经发布。",
                        fields("kind" to "STRING", "account" to "STRING", "target" to "STRING", "content" to "STRING")))
                    a.put(structured("background_task", "创建持久摘要或提醒，data={kind:summary/reminder,text,dueAt,condition?:online/manual}。提醒必须写 dueAt，格式“2026-11-04 09:00”，" +
                        "到时间才提醒；只说了日子没说几点就问一句，或按上午 9 点。不授予外发权限；查看返回的真实状态。",
                        fields("kind" to "STRING", "text" to "STRING", "dueAt" to "STRING", "condition" to "STRING")))
                }
                if (ownersOwnChat) {
                    if (deviceAvailable) a.put(structured("device_action", "操作主人的单机游戏。control.start 接手（packageName 可写包名或应用名；durationSeconds 默认 600；background=true 在副屏后台玩，需要 Shizuku）；" +
                        "capture.refresh 取画面，用返回的 sessionId,inputEpoch,frameId 和像素坐标 x,y 操作：tap；swipe 加 toX,toY；longPress/hold 给 durationMs（hold 可加 toX,toY,moveMs 当摇杆按住拖动）；" +
                        "multi 给 pointers=[{x,y,toX,toY,startMs,durationMs}] 多指同时操作。有 Shizuku 时按住最长 10 秒（后台 30 秒），没有时 1.5 秒。" +
                        "landmark.save 用 name 和屏幕区域 x,y,w,h 标记当前画面里的按钮；landmark.find / landmark.tap 按 name 找或点；control.waitFor 等 landmark 出现或画面变化（timeoutMs）。" +
                        "route.save 把这次成功的操作存成 name 路线，route.replay 重放并逐步核对画面；notes.add 记 text 心得。每步看回执和新画面。急停时不能自己恢复。",
                        fields("packageName" to "STRING", "sessionId" to "STRING", "inputEpoch" to "INTEGER", "frameId" to "STRING", "x" to "NUMBER", "y" to "NUMBER", "toX" to "NUMBER", "toY" to "NUMBER",
                            "w" to "NUMBER", "h" to "NUMBER", "durationMs" to "INTEGER", "moveMs" to "INTEGER", "durationSeconds" to "INTEGER", "background" to "BOOLEAN",
                            "name" to "STRING", "landmark" to "STRING", "timeoutMs" to "INTEGER", "text" to "STRING")
                            .put("pointers", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "OBJECT")
                                .put("properties", fields("x" to "NUMBER", "y" to "NUMBER", "toX" to "NUMBER", "toY" to "NUMBER", "startMs" to "INTEGER", "durationMs" to "INTEGER", "moveMs" to "INTEGER")))),
                        DEVICE_ACTIONS.toList()))
                    a.put(decl("observe_screen", "看一眼手机当前前台的界面（主人让你看就是授权；密码框和敏感字样会被遮住，银行、支付、权限界面看不了）。读到的是界面文字，不等于看过完整视频", emptyList()))
                    a.put(decl("open_app", "打开已授权的 QQ/B站；可提供 B站 BV 号。不会登录、发送或付款", listOf("package", "videoId"), listOf("package")))
                    a.put(decl("draft_comment", "依据本次已附加或先前已分析的视频资料形成评论草稿；analysis 必须说明实际读到的内容、时间覆盖与未读部分，供主人审阅。等待本机授权，不会发送", listOf("observationId", "analysis", "body")))
                }
            }
    }

    internal val LIFE_ACTIONS = listOf("memory.search", "diary.list", "interest.list", "interest.upsert", "attention.status", "attention.set",
        "memory.forget", "memory.delete", "diary.correct", "budget.status", "pending.list")
    /** These reach the owner's own memory and state: from their own chats only, never a group. */
    internal val OWN_CHAT_LIFE_ACTIONS = setOf("memory.forget", "memory.delete", "diary.correct", "budget.status", "pending.list", "attention.status")
    internal val DEVICE_ACTIONS = linkedSetOf("control.start", "control.status", "capture.status", "capture.refresh", "control.tap", "control.swipe", "control.longPress",
        "control.hold", "control.multi", "control.waitFor", "landmark.save", "landmark.find", "landmark.tap", "landmark.forget", "route.save", "route.replay", "route.forget", "notes.add")
}

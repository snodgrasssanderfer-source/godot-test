package io.deepseekharness.mobile.companion

/** Host acknowledgements describe accepted intent, never model TTFT or completed work. */
object LatencyPolicy {
    enum class AckKind { RECALL, STATUS, RESEARCH, SCREEN, BACKGROUND }

    data class Acknowledgement(val kind: AckKind, val text: String)

    data class Input(
        val localOwner: Boolean = false,
        val online: Boolean = false,
        val toolsEnabled: Boolean = false,
        val recallAvailable: Boolean = false,
        val jobStatusAvailable: Boolean = false,
        val researchAvailable: Boolean = false,
        val screenObservationAuthorized: Boolean = false,
        val backgroundAvailable: Boolean = false,
        val emergencyHeld: Boolean = false,
    )

    data class Decision(val acknowledgement: Acknowledgement?, val thinking: String, val reason: String)

    private val quotedOrCode = Regex("[\\\"'“”‘’「」『』`]|[\\r\\n;；]")
    private val withheld = Regex("不要|别|不用|不必|无需|先不|暂不|不想|不需要|不准|禁止|没让|没叫|不是让|并非让|不是要|停止|暂停|取消")
    private val hypothetical = Regex("如果|假如|要是|假设|例如|比如|举例|原文|复述|解释这句")
    private val sideEffect = Regex("(?:并且|并|然后|顺便|再|之后|接着|且)(?:帮我|替我)?(?:删除|发送|发布|保存|购买|付款|转账|点击|登录|授权|修改|覆盖)")
    private val questionEnding = Regex("[?？]|(?:吗|(?<!什)么|呢|嘛|什么意思|怎么用|如何使用|是否有用|有没有用)[。！!]*$")
    private val requestPrefix = "(?:请(?:帮我)?|麻烦(?:你)?|帮我|替我)"
    private val readVerb = "(?:查一下|查一查|查查|查找|查询|搜索|查看|看一下|看看)"
    private val explicitResearch = Regex("^(?:$requestPrefix)?用\\s*web_research\\s*(?:$readVerb|查阅|阅读).{2,120}$")
    private val explicitRecall = Regex("^(?:$requestPrefix)?用\\s*recall\\s*$readVerb.{2,120}$")
    private val pastRecall = Regex("^$requestPrefix(?:翻一下|翻一翻|翻翻|查一下|查一查|查查|找一下|找找)(?:我(?:们)?(?:的)?)?(?:之前|上次|以前|先前|昨天|前天)(?:的)?(?:记录|聊天记录|聊天|对话|消息|记忆).{0,120}$")
    private val explicitStatus = Regex("^(?:$requestPrefix)?用\\s*job_status\\s*$readVerb.{0,120}$")
    private val taskStatus = Regex("^$requestPrefix$readVerb(?:当前(?:的)?|现在(?:的)?)?后台任务.{0,100}$")
    private val research = Regex("^(?:${requestPrefix}(?:上网)?|上网)(?:查一下|查一查|查查|查找|搜索|搜一下)(.{2,120})$")
    private val screen = Regex("^(?:$requestPrefix)?(?:看一下|看一眼|看看)(?:(?:我(?:的)?|手机)(?:的)?)?(?:当前(?:的)?|现在(?:的)?)?(?:屏幕|画面|界面)$")
    private val background = Regex("^(?:$requestPrefix)?在后台(?:看一下|看完|看看|读一下|读完|总结|整理摘要)(.{2,120})$")
    private val reminder = Regex("^$requestPrefix(?:设|设置|安排)(?:一个)?提醒[：:]?(.{2,120})$")
    private val simpleChat = setOf(
        "早安", "早上好", "中午好", "晚上好", "晚安", "你好", "在吗", "我回来了", "我到家了", "吃饭啦", "吃饭了",
        "今天好累", "今天有点累", "今天挺开心", "谢谢", "谢谢你", "辛苦啦", "你真棒", "真厉害", "哈哈", "哈哈哈",
    )
    private val dailyState = Regex("^(?:我(?:今天|现在)?|今天|现在)?(?:感觉)?(?:好|很|挺|有点|有些|特别|稍微)?(?:累|困|饿|开心|高兴|疲惫|疲倦|难过|低落|无聊|烦躁|放松|轻松|舒服)(?:了|呀|啊|啦)?$")
    private val dailyMood = Regex("^(?:我(?:的)?)?(?:今天|现在)?(?:的)?心情(?:好|很好|挺好|不错|还行|一般|不太好|有点差|很差)(?:呀|啊|啦)?$")
    private val dailyWeather = Regex("^(?:今天|现在)?(?:的)?(?:天气|外面)(?:好|很|挺|有点|有些|特别)?(?:冷|热|凉快|暖和|舒服|闷|晴朗|阴沉|不错)(?:了|呀|啊|啦)?$")
    private val dailyConditions = Regex("^(?:今天|现在)?(?:外面)?(?:下雨了|在下雨|下雪了|在下雪|刮风了|风很大|风挺大|风有点大|阳光很好|阳光不错)$")

    private fun acknowledgement(kind: AckKind): Acknowledgement = Acknowledgement(kind, when (kind) {
        AckKind.RECALL -> "我先翻一下之前的记录。"
        AckKind.STATUS -> "我先看看后台任务现在怎么样了。"
        AckKind.RESEARCH -> "我先查一下，查到后告诉你。"
        AckKind.SCREEN -> "我先看一眼当前屏幕。"
        AckKind.BACKGROUND -> "我来在后台处理，结果出来再告诉你。"
    })

    /** Classification only: the caller must still check authority, emergency hold and source validity. */
    fun forReadTools(names: Collection<String>): Acknowledgement? {
        if (names.isEmpty()) return null
        val kinds = names.map { name ->
            when (name) {
                "recall" -> AckKind.RECALL
                "job_status" -> AckKind.STATUS
                "web_research", "bili_search" -> AckKind.RESEARCH
                "observe_screen" -> AckKind.SCREEN
                else -> return null
            }
        }
        // The execution path keeps tool order; this only describes the first selected read.
        return acknowledgement(kinds.first())
    }

    /** Only exact short social utterances qualify; unknown or compound requests retain their profile. */
    fun isSimpleChat(message: String): Boolean {
        val text = message.trim()
        if (text.length !in 1..32 || '\n' in text || '\r' in text) return false
        return text.trimEnd('。', '！', '!', '？', '?', '～', '~') in simpleChat
    }

    /**
     * A first chat round may omit tool declarations only when the message stands alone as casual speech.
     * Callers supply the preceding user-message window and include attachments in [hasObservation].
     * Any uncertain recent context keeps the tools. Follow-up/merged rounds must restore them separately.
     */
    fun toolFreeChat(message: String, recentUserMessages: List<String>, hasObservation: Boolean): Boolean =
        !hasObservation && standaloneDailyChat(message) && recentUserMessages.all(::standaloneDailyChat)

    private fun standaloneDailyChat(message: String): Boolean {
        val text = message.trim()
        if (text.length !in 1..32 || '\n' in message || '\r' in message ||
            quotedOrCode.containsMatchIn(text) || withheld.containsMatchIn(text) ||
            hypothetical.containsMatchIn(text) || questionEnding.containsMatchIn(text)
        ) return false
        val clauses = text.trimEnd('。', '！', '!', '～', '~').split('，', ',')
        if (clauses.size !in 1..2) return false
        // Closed grammars only: an arbitrary suffix could conceal a task or a reference to an old one.
        return clauses.all { raw ->
            val clause = raw.trim()
            !questionEnding.containsMatchIn(clause) && (clause in simpleChat || dailyState.matches(clause) ||
                dailyMood.matches(clause) || dailyWeather.matches(clause) || dailyConditions.matches(clause))
        }
    }

    /** Inputs must come from native authority/capability state, never from the model or message text. */
    fun plan(message: String, input: Input, configuredThinking: String): Decision {
        val text = message.trim()
        val thinking = if (isSimpleChat(message) && configuredThinking.lowercase() in setOf("medium", "high")) {
            "low"
        } else {
            configuredThinking
        }
        val unchangedReason = if (thinking != configuredThinking) "simple_chat" else "unchanged"
        if (!input.localOwner || !input.online || !input.toolsEnabled || input.emergencyHeld) {
            return Decision(null, thinking, unchangedReason)
        }
        if (text.length !in 2..160 || quotedOrCode.containsMatchIn(text) || withheld.containsMatchIn(text) ||
            hypothetical.containsMatchIn(text) || sideEffect.containsMatchIn(text) || questionEnding.containsMatchIn(text)
        ) {
            return Decision(null, thinking, "uncertain_or_not_an_imperative")
        }
        val command = text.trimEnd('。', '！', '!')
        val acknowledgement = when {
            explicitRecall.matches(command) || pastRecall.matches(command) ->
                if (input.recallAvailable) acknowledgement(AckKind.RECALL) else null
            explicitStatus.matches(command) || taskStatus.matches(command) ->
                if (input.jobStatusAvailable) acknowledgement(AckKind.STATUS) else null
            explicitResearch.matches(command) ->
                if (input.researchAvailable) acknowledgement(AckKind.RESEARCH) else null
            input.researchAvailable && research.matches(command) ->
                acknowledgement(AckKind.RESEARCH)
            input.screenObservationAuthorized && screen.matches(command) ->
                acknowledgement(AckKind.SCREEN)
            input.backgroundAvailable && (background.matches(command) || reminder.matches(command)) ->
                acknowledgement(AckKind.BACKGROUND)
            else -> null
        }
        return Decision(acknowledgement, thinking, if (acknowledgement == null) unchangedReason else "explicit_tool_intent")
    }
}

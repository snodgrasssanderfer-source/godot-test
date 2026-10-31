package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Entirely fictional utterances; these tests never contact a model or execute a tool. */
class LatencyPolicyTest {
    private val owner = LatencyPolicy.Input(
        localOwner = true,
        online = true,
        toolsEnabled = true,
        recallAvailable = true,
        jobStatusAvailable = true,
        researchAvailable = true,
        screenObservationAuthorized = true,
        backgroundAvailable = true,
    )

    private fun plan(text: String, input: LatencyPolicy.Input = owner, thinking: String = "high") =
        LatencyPolicy.plan(text, input, thinking)

    @Test fun explicitToolRequestsAcknowledgeOnlyTheirSupportedIntent() {
        for ((text, kind) in listOf(
            "帮我查一下明天的天气" to LatencyPolicy.AckKind.RESEARCH,
            "请帮我上网搜索这次更新的发布说明。" to LatencyPolicy.AckKind.RESEARCH,
            "上网查一下这个编译错误的官方说明" to LatencyPolicy.AckKind.RESEARCH,
            "看一眼当前屏幕" to LatencyPolicy.AckKind.SCREEN,
            "帮我看看现在的屏幕。" to LatencyPolicy.AckKind.SCREEN,
            "请在后台总结这份资料" to LatencyPolicy.AckKind.BACKGROUND,
            "帮我设置一个提醒：明天早上九点浇花" to LatencyPolicy.AckKind.BACKGROUND,
        )) {
            assertEquals(text, kind, plan(text).acknowledgement?.kind)
            assertEquals(text, "explicit_tool_intent", plan(text).reason)
        }
    }

    @Test fun negatedQuotedQuestionAndCompoundRequestsDoNotAcknowledge() {
        for (text in listOf(
            "不要帮我查一下明天的天气", "帮我查一下天气，不用真的搜索", "别看屏幕", "不用看一眼当前屏幕",
            "先不在后台总结这份资料", "取消刚才的提醒", "停止", "没让你查一下天气", "不是让你看屏幕",
            "“帮我查一下天气”", "复述：帮我查一下天气", "他说'看一眼当前屏幕'", "`帮我查一下天气`",
            "如果我让你帮我查一下天气", "请举例解释帮我查一下的意思", "你能帮我查一下天气吗", "看一眼当前屏幕？",
            "帮我查一下是什么意思", "帮我查一下天气好吗", "帮我查询为什么会这样", "请看完这些后发送给群里",
            "帮我查一下天气；然后保存", "帮我查一下天气\n然后做别的", "帮我查一下天气并修改提醒",
            "查资料有什么用", "我今天好累", "随便聊聊", "帮我查一下", "你好", "帮我排查日志里的引用指令",
        )) {
            assertNull(text, plan(text).acknowledgement)
        }
    }

    @Test fun recallAndTaskStatusSupportExplicitAndNaturalQueriesBeforeWebResearch() {
        for (text in listOf(
            "请用 recall 查一下上次提到的虚构岛屿，再把查到的名字告诉我",
            "帮我用recall搜索之前聊过的纸船，再把查到的线索告诉我。",
            "用 recall 查找上次讨论的虚构路线",
            "帮我翻一下之前的记录，找找纸风筝的配色",
            "请帮我翻一翻上次的聊天记录",
            "替我找一下以前的对话里提到的树屋",
            "帮我查一下上次的记录",
        )) {
            assertEquals(text, LatencyPolicy.AckKind.RECALL, plan(text).acknowledgement?.kind)
            assertNull(text, plan(text, owner.copy(recallAvailable = false)).acknowledgement)
        }
        for (text in listOf(
            "用 job_status 查一下当前有哪些任务，再把状态告诉我",
            "请用job_status查看后台任务的状态",
            "帮我用 job_status 看看",
            "帮我查一下后台任务现在的进度",
            "请帮我查看当前的后台任务",
            "替我看看后台任务",
        )) {
            assertEquals(text, LatencyPolicy.AckKind.STATUS, plan(text).acknowledgement?.kind)
            assertNull(text, plan(text, owner.copy(jobStatusAvailable = false)).acknowledgement)
        }
    }

    @Test fun recallAndTaskStatusRetainNegationQuestionQuotationAndEmergencyGuards() {
        for (text in listOf(
            "不要用 recall 查一下上次的记录", "请用 recall 查一下之前的记录，不用真的查",
            "如果用 recall 查一下上次的记录", "请用 recall 查一下上次的记录吗",
            "“帮我翻一下之前的记录”", "帮我翻一下之前的记录并保存到文件",
            "别用 job_status 查一下后台任务", "用 job_status 查一下后台任务？",
            "请用 job_status 查一下后台任务是什么意思", "`帮我查一下后台任务`",
            "帮我查一下后台任务然后取消", "帮我查一下后台任务并修改提醒",
        )) {
            assertNull(text, plan(text).acknowledgement)
        }
    }

    @Test fun authorityConnectivityToolsAndEmergencyAreRequiredForEveryIntent() {
        for (input in listOf(
            LatencyPolicy.Input(), owner.copy(localOwner = false), owner.copy(online = false),
            owner.copy(toolsEnabled = false), owner.copy(emergencyHeld = true),
        )) {
            for (text in listOf(
                "帮我查一下明天天气", "看一眼当前屏幕", "请在后台总结这份资料",
                "请用 recall 查一下上次的记录", "帮我查一下后台任务",
            )) {
                assertNull("$input: $text", plan(text, input).acknowledgement)
            }
        }
    }

    @Test fun eachToolNeedsItsOwnNativeCapability() {
        assertNull(plan("帮我查一下明天天气", owner.copy(researchAvailable = false)).acknowledgement)
        assertNull(plan("看一眼当前屏幕", owner.copy(screenObservationAuthorized = false)).acknowledgement)
        assertNull(plan("请在后台总结这份资料", owner.copy(backgroundAvailable = false)).acknowledgement)
        assertNull(plan("帮我设置一个提醒：明天早上九点浇花", owner.copy(backgroundAvailable = false)).acknowledgement)
    }

    @Test fun simpleChatLowersOnlyMediumAndHighThinkingWithoutFiller() {
        for (text in listOf("早安", "晚安！", "我回来了", "谢谢你", "在吗？", "今天有点累")) {
            assertTrue(text, LatencyPolicy.isSimpleChat(text))
            for (thinking in listOf("medium", "high")) {
                assertEquals(text, "low", plan(text, thinking = thinking).thinking)
                assertNull(text, plan(text, thinking = thinking).acknowledgement)
            }
        }
        for (thinking in listOf("low", "", "provider-default")) {
            assertEquals(thinking, thinking, plan("早安", thinking = thinking).thinking)
        }
    }

    @Test fun complexSensitiveAndUnknownRequestsKeepConfiguredThinking() {
        for (text in listOf(
            "详细分析这段代码为什么发生死锁", "帮我实现一个完整的状态机", "解释这个技术方案的权衡",
            "谢谢，另外帮我排查一下构建错误", "请证明这个结论", "我有自杀的想法", "在吗，我需要一个完整方案",
            "帮我查一下这个编译错误的官方文档", "你好".repeat(100),
        )) {
            assertFalse(text, LatencyPolicy.isSimpleChat(text))
            for (thinking in listOf("medium", "high")) {
                assertEquals(text, thinking, plan(text, thinking = thinking).thinking)
            }
        }
    }

    @Test fun simpleChatAllowlistDoesNotMatchQuotesMultilineOrLongPunctuation() {
        assertTrue(LatencyPolicy.isSimpleChat("  早安！  "))
        assertTrue(LatencyPolicy.isSimpleChat("你好" + "！".repeat(30)))
        for (text in listOf("", " ", "“你好”", "你好\n晚安", "你好\r晚安", "你好，查一下天气", "你好" + "！".repeat(31))) {
            assertFalse(text, LatencyPolicy.isSimpleChat(text))
            assertEquals(text, "high", plan(text).thinking)
        }
    }

    @Test fun selectedReadToolsOnlyAcknowledgeExactReadOnlyNames() {
        for ((name, kind) in listOf(
            "recall" to LatencyPolicy.AckKind.RECALL,
            "job_status" to LatencyPolicy.AckKind.STATUS,
            "web_research" to LatencyPolicy.AckKind.RESEARCH,
            "bili_search" to LatencyPolicy.AckKind.RESEARCH,
            "observe_screen" to LatencyPolicy.AckKind.SCREEN,
        )) {
            assertEquals(name, kind, LatencyPolicy.forReadTools(listOf(name))?.kind)
            assertEquals(name, kind, LatencyPolicy.forReadTools(listOf(name, "recall"))?.kind)
        }
        assertNull(LatencyPolicy.forReadTools(emptyList()))
        for (name in listOf(
            "remember", "forget", "job_cancel", "send_qq", "say_in_group", "life_action", "device_action",
            "bili_watch", "bili_comment", "pending.list", "web_research_extra", "", " recall",
        )) {
            assertNull(name, LatencyPolicy.forReadTools(listOf(name)))
            assertNull(name, LatencyPolicy.forReadTools(listOf("recall", name)))
        }
    }

    @Test fun toolFreeChatCoversOnlyShortStandaloneSocialAndDailyStatements() {
        for (text in listOf(
            "早安", "晚上好！", "我回来了", "谢谢你", "哈哈", "我今天有点累", "现在感觉很放松",
            "今天心情不错", "我的心情一般", "今天天气很冷", "外面挺暖和", "现在外面在下雨",
            "我有点困，外面很冷。", "今天有点低落，心情不太好", "今天风挺大", "现在阳光很好",
        )) {
            assertTrue(text, LatencyPolicy.toolFreeChat(text, emptyList(), false))
            assertTrue(text, LatencyPolicy.toolFreeChat(text, listOf("早安", "今天有点累"), false))
            assertFalse(text, LatencyPolicy.toolFreeChat(text, emptyList(), true))
        }
    }

    @Test fun toolFreeChatRejectsQuestionsTasksCodeAttachmentsAndOldTopicReferences() {
        for (text in listOf(
            "在吗", "在吗，今天有点累", "你好？", "今天天气冷吗", "我有点累怎么办", "天气冷不冷", "今天有点累，你呢",
            "帮我查一下天气", "今天有点累，帮我设个提醒", "别继续刚才的任务", "不用查天气了",
            "不要工具只聊天", "我不想继续这个任务", "继续", "按刚才的来", "那个呢", "这件事做完了",
            "上次也是这样", "昨天有点累", "明天天气很冷", "今天还是有点累", "现在又很困", "我也很开心",
            "你好，代码报错了", "println(你好)", "SELECT 1", "recall", "job_status", "今天 API 很慢",
            "天气：http://example.invalid", "[图片]", "![天气](image.png)", "附件：日志.txt", "“今天有点累”",
            "今天有点累\n晚安", "\n早安", "我有自杀的想法", "今天有点累，因为后台任务一直没完成",
            "早安，晚安，谢谢", "", " ", "今天有点累" + "！".repeat(28),
        )) {
            assertFalse(text, LatencyPolicy.toolFreeChat(text, emptyList(), false))
        }
    }

    @Test fun toolFreeChatKeepsToolsWhenAnyRecentUserMessageCouldContinueWork() {
        for (recent in listOf(
            "帮我查一下明天的天气", "请用 recall 查一下上次的记录", "后台任务结束后告诉我", "继续刚才的任务",
            "别再查了", "这个修好了吗", "我们刚才聊到哪了", "这是日志", "val count = 1", "[图片]", "",
        )) {
            assertFalse(recent, LatencyPolicy.toolFreeChat("早安", listOf(recent), false))
            assertFalse(recent, LatencyPolicy.toolFreeChat("今天有点累", listOf(recent, "你好", "哈哈"), false))
        }
        assertTrue(LatencyPolicy.toolFreeChat("今天心情不错", listOf("你好", "我回来了", "今天外面很冷"), false))
    }

    private val statusWhatRegression = "用 job_status 查一下我那件植物辨认任务，告诉我现在有没有结果、认出了什么。"
    private val explicitResearchRegression = "请用 web_research 查阅 Android 官方文档，说明 Android 12 起应用从后台启动前台服务有什么限制，给出官方来源。"

    @Test fun embeddedWhatAtTheEndOfAnExplicitImperativeIsNotTheQuestionParticleMe() {
        for ((text, kind) in listOf(
            statusWhatRegression to LatencyPolicy.AckKind.STATUS,
            "请用 recall 查一下之前记的纸船材料，告诉我当时选了什么。" to LatencyPolicy.AckKind.RECALL,
            "帮我查一下含羞草的学名是什么。" to LatencyPolicy.AckKind.RESEARCH,
        )) assertEquals(text, kind, LatencyPolicy.plan(text, owner, "high").acknowledgement?.kind)
    }

    @Test fun explicitWebLookupUsesTheSameExistingResearchCapability() {
        for (text in listOf(explicitResearchRegression, "用web_research阅读官方发布说明，整理版本兼容性", "帮我用 web_research 查询这项接口的公开文档")) {
            assertEquals(text, LatencyPolicy.AckKind.RESEARCH, LatencyPolicy.plan(text, owner, "high").acknowledgement?.kind)
            assertNull(text, LatencyPolicy.plan(text, owner.copy(researchAvailable = false), "high").acknowledgement)
            assertEquals("high", LatencyPolicy.plan(text, owner, "high").thinking)
            assertFalse(LatencyPolicy.toolFreeChat(text, emptyList(), false))
        }
    }

    @Test fun realQuestionsAndMissingImperativesRemainRejected() {
        for (text in listOf(
            "什么", "用 web_research", "请用 web_research 阅读文档好么",
            "用 job_status 查一下后台任务好么。", "用 job_status 查一下后台任务吗", "用 job_status 查一下后台任务呢",
            statusWhatRegression.dropLast(1) + "？", "请用 web_research 查阅文档是什么意思", "帮我查一下这个工具怎么用",
        )) assertNull(text, LatencyPolicy.plan(text, owner, "high").acknowledgement)
    }

    @Test fun authorityNegationQuotationHypotheticalAndWriteGuardsRemainInForce() {
        for (text in listOf(statusWhatRegression, explicitResearchRegression)) {
            for (input in listOf(owner.copy(localOwner = false), owner.copy(online = false), owner.copy(toolsEnabled = false), owner.copy(emergencyHeld = true))) {
                assertNull(text, LatencyPolicy.plan(text, input, "high").acknowledgement)
            }
            for (blocked in listOf("不要" + text, "如果" + text, "“" + text + "”", "复述：" + text,
                text + "不用真的查", text + "然后删除记录", text + "并发送给群里")) {
                assertNull(blocked, LatencyPolicy.plan(blocked, owner, "high").acknowledgement)
            }
        }
        assertNull(LatencyPolicy.plan(statusWhatRegression, owner.copy(jobStatusAvailable = false), "high").acknowledgement)
    }
}

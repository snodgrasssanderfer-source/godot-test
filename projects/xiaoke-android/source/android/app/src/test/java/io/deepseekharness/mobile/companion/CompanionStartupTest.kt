package io.deepseekharness.mobile.companion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The Capacitor plugin's load() builds the engine (opening the database) and every module; an exception there is what
 * the app shows as "Companion plugin is not implemented". This caught `PRAGMA secure_delete` going through execSQL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Conscrypt would be installed as the JVM-wide first security provider and break the plain-JDK TLS tests that run after this one.
@ConscryptMode(ConscryptMode.Mode.OFF)
class CompanionStartupTest {
    @Test fun `引擎和全部模块能在新装的手机上建起来`() {
        FakeAndroidKeyStore.install()
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            val engine = CompanionEngine.get(context)
            assertNotNull(engine.store.settings())
            assertNotNull(engine.modules.router)
            failuresAreTold(engine.store)
            ownerRequestedSendIgnoresGroupReplySettings(engine.store)
            personaVersionsCountUpOnceAndAliasesCount(engine.store)
            claudeOnlyWhereTurnedOn(engine.store)
            onlyRemovingWhatATurnReadStopsIt(engine.store)
            toolsFollowWhoIsSpeaking(engine)
            memoryDomainsStaySeparate(engine)
        } catch (e: Throwable) {
            // Stack traces cannot be formatted inside the sandbox on this JDK; carry the cause chain as text.
            throw AssertionError(generateSequence(e) { it.cause }.joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" })
        } finally { java.security.Security.removeProvider("AndroidKeyStore") }
    }

    private fun memoryDomainsStaySeparate(engine: CompanionEngine) {
        val store = engine.store
        val savedSettings = store.settings()
        val settings = JSONObject(savedSettings.toString()).put("networkEnabled", false).put("embedding", "off")
            .put("ownerQqId", "910001").put("oneBotAccount", "910002").put("shareOwnerMemoryWithQq", true)
            .put("qqGroups", org.json.JSONArray().put("910003"))
        store.saveSettings(settings)
        try {
            val ownerQq = CompanionPolicy.ownerQqScope(settings)!!
            val group = "qq:group:" + CompanionPolicy.digest("onebot\u0000910002\u0000910003").take(40)
            val friend = "qq:private:memory-friend"
            val unverified = "qq:private:memory-unverified"
            fun conversation(scope: String, target: String, reliable: Boolean = true) = store.put("conversation", JSONObject()
                .put("id", scope).put("scope", scope).put("route", "onebot").put("targetType", "private")
                .put("targetId", target).put("account", "910002").put("reliable", reliable))
            conversation(ownerQq, "910001")
            conversation(friend, "910004")
            conversation(unverified, "910001", reliable = false)
            fun memory(id: String, scope: String) = JSONObject().put("id", id).put("scope", scope).put("kind", "fact")
                .put("text", "虚构隔离主题：$id").put("pinned", true).put("shareSummary", "虚构隔离主题")
                .put("shareWithScopes", org.json.JSONArray().put("*"))
            val local = store.put("memory", memory("domain-local", CompanionPolicy.OWNER))
            val qq = store.put("memory", memory("domain-owner-qq", ownerQq))
            store.put("memory", memory("domain-group", group)
                .put("links", org.json.JSONArray().put(local.getString("id"))).put("endedBy", qq.getString("id")))
            store.put("memory", memory("domain-friend", friend))
            store.put("memory", memory("domain-unverified", unverified))
            store.put("memory", memory("domain-derived-local", group).put("sourceRefs", org.json.JSONArray().put(
                JSONObject().put("kind", "memory").put("id", local.getString("id")).put("scope", CompanionPolicy.OWNER))))
            store.put("memory", memory("domain-derived-qq", friend).put("sourceRefs", org.json.JSONArray().put(
                JSONObject().put("kind", "memory").put("id", qq.getString("id")).put("scope", ownerQq))))
            for (kind in listOf("diary", "interest")) {
                store.put(kind, memory("domain-public-$kind", group))
                store.put(kind, memory("domain-derived-$kind", group)
                    .put("sourceRefs", org.json.JSONArray().put(JSONObject().put("kind", "memory")
                        .put("id", local.getString("id")).put("scope", CompanionPolicy.OWNER))))
            }
            val recall = CompanionEngine::class.java.getDeclaredMethod("recallMemories", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            fun recalled(scope: String, forTool: Boolean): Set<String> {
                val rows = recall.invoke(engine, scope, "虚构隔离主题", forTool) as org.json.JSONArray
                return (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.filter { it.startsWith("domain-") }.toSet()
            }
            for (forTool in listOf(false, true)) {
                val ownerIds = setOf("domain-local", "domain-owner-qq", "domain-group")
                assertEquals(ownerIds, recalled(CompanionPolicy.OWNER, forTool))
                assertEquals(ownerIds, recalled(ownerQq, forTool))
                assertEquals(setOf("domain-group"), recalled(group, forTool))
                assertEquals(setOf("domain-friend"), recalled(friend, forTool))
                assertEquals(setOf("domain-unverified"), recalled(unverified, forTool))
            }
            store.put("conversation", conversation(ownerQq, "910001").put("account", "910099"))
            assertEquals(setOf("domain-local", "domain-group"), recalled(CompanionPolicy.OWNER, false))
            assertEquals(setOf("domain-owner-qq"), recalled(ownerQq, false))
            val principal = io.deepseekharness.mobile.companion.iteration.CommandPrincipal("910004", "qq_group", group, false)
            fun derived(rows: org.json.JSONArray) = (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
                .filter { it.startsWith("domain-derived-") }
            val exposed = mutableMapOf<String, List<String>>()
            for (action in listOf("memory.search", "diary.list", "interest.list")) {
                exposed[action] = derived(engine.modules.life.execute(action, JSONObject(), principal).getJSONArray("items"))
            }
            val snapshot = engine.modules.life.snapshot(principal)
            for (key in listOf("memories", "diary", "interests")) exposed["snapshot.$key"] = derived(snapshot.getJSONArray(key))
            assertEquals(emptyMap<String, List<String>>(), exposed.filterValues { it.isNotEmpty() })
            assertTrue(snapshot.getJSONArray("memories").toString().contains("domain-group"))
            assertTrue(snapshot.getJSONArray("diary").toString().contains("domain-public-diary"))
            assertTrue(snapshot.getJSONArray("interests").toString().contains("domain-public-interest"))
            val ownerPrincipal = io.deepseekharness.mobile.companion.iteration.CommandPrincipal("owner", "owner_ui", CompanionPolicy.OWNER, true)
            val ownerReview = engine.modules.life.execute("memory.search", JSONObject().put("scope", group), ownerPrincipal).getJSONArray("items")
            assertEquals(listOf("domain-derived-local"), derived(ownerReview))
        } finally { store.saveSettings(savedSettings) }
    }

    /** The owner gets the owner's tools wherever they talk to her; the phone stays reachable only from their own chats. */
    private fun toolsFollowWhoIsSpeaking(engine: CompanionEngine) {
        val own = engine.toolsFor(CompanionPolicy.OWNER, owner = true, ownersOwnChat = true)
        assertTrue(own.containsAll(listOf("observe_screen", "draft_comment", "open_app", "life:memory.forget", "life:pending.list")))
        val ownerInGroup = engine.toolsFor("qq:group:tooltest", owner = true, ownersOwnChat = false)
        assertTrue("observe_screen" !in ownerInGroup && "device_action" !in ownerInGroup && "life:memory.forget" !in ownerInGroup)
        assertTrue("life:attention.set" in ownerInGroup)
        val member = engine.toolsFor("qq:group:tooltest", owner = false, ownersOwnChat = false)
        assertTrue(member.none { it in setOf("observe_screen", "send_qq", "say_in_group", "life:attention.set", "life:memory.forget") })
        assertTrue("propose_save" in member)
    }

    /**
     * 14:42 / 14:50 / 16:00: any memory edit anywhere moved one global epoch and voided every running turn. Now edits
     * never do; a deletion only voids a turn that read the deleted thing, and only that conversation's Claude session.
     */
    private fun onlyRemovingWhatATurnReadStopsIt(store: CompanionStore) {
        val mine = store.message(CompanionPolicy.OWNER, "user", "我明天去看海", "本机主人")
        val other = store.message("qq:group:epochtest", "user", "群里的一句话", "群友")
        val sources = org.json.JSONArray().put(org.json.JSONObject().put("kind", "message").put("id", mine.getString("id")).put("scope", CompanionPolicy.OWNER))
        val epoch = store.removalEpoch(); val ownerEpoch = store.scopeRemovalEpoch(CompanionPolicy.OWNER)
        store.saveMemory(CompanionPolicy.OWNER, org.json.JSONObject().put("text", "主人明天去看海").put("kind", "task").put("sourceId", mine.getString("id")))
        assertEquals(epoch, store.removalEpoch()) // remembering is not removing
        store.deleteContent(setOf(io.deepseekharness.mobile.companion.life.RecordRef("message", other.getString("id"), "qq:group:epochtest")))
        assertTrue(store.removalEpoch() != epoch)
        assertEquals(ownerEpoch, store.scopeRemovalEpoch(CompanionPolicy.OWNER)) // another conversation's deletion
        assertEquals(null, store.contextBroken(sources))
        store.commitContext(epoch, sources) { } // the turn carries on
        store.deleteContent(setOf(io.deepseekharness.mobile.companion.life.RecordRef("message", mine.getString("id"), CompanionPolicy.OWNER)))
        assertTrue(store.contextBroken(sources)!!.contains("删"))
        assertThrows(ContextRemoved::class.java) { store.commitContext(epoch, sources) { } }
        assertTrue(store.scopeRemovalEpoch(CompanionPolicy.OWNER) != ownerEpoch)
    }

    /** Groups stay on Gemini unless the owner switches that one group; any outage sends everything back to Gemini. */
    private fun claudeOnlyWhereTurnedOn(store: CompanionStore) {
        val chat = io.deepseekharness.mobile.companion.claude.ClaudeChat
        val group = org.json.JSONObject().put("id", "qq:group:a")
        chat.saveSettings(store, org.json.JSONObject().put("enabled", true).put("model", "default").put("effort", "high"))
        assertEquals("claude", chat.engineFor(store, CompanionPolicy.OWNER, true, null, installed = true))
        assertEquals("gemini", chat.engineFor(store, CompanionPolicy.OWNER, true, null, installed = false))
        assertEquals("gemini", chat.engineFor(store, "qq:group:a", false, group, installed = true))
        assertEquals("claude", chat.engineFor(store, "qq:group:a", false, JSONObject(group.toString()).put("engine", "claude"), installed = true))
        assertEquals("gemini", chat.engineFor(store, "qq:private:x", false, org.json.JSONObject().put("engine", "claude"), installed = true))
        assertTrue(chat.recordOutage(store, io.deepseekharness.mobile.companion.claude.ClaudeStream.Failure.LIMIT, "limit", null))
        assertTrue(!chat.recordOutage(store, io.deepseekharness.mobile.companion.claude.ClaudeStream.Failure.LIMIT, "limit", null)) // told once
        assertEquals("gemini", chat.engineFor(store, CompanionPolicy.OWNER, true, null, installed = true))
        chat.saveSettings(store, org.json.JSONObject().put("enabled", true).put("model", "opus").put("effort", "max"))
        assertEquals("claude", chat.engineFor(store, CompanionPolicy.OWNER, true, null, installed = true))
        assertThrows(IllegalArgumentException::class.java) { chat.saveSettings(store, org.json.JSONObject().put("model", "x; rm").put("effort", "high")) }
        chat.saveSettings(store, org.json.JSONObject().put("enabled", false).put("model", "default").put("effort", "medium"))
    }

    /** She sees why a reply did not go out; a group only sees its own failures, the owner sees all. */
    private fun failuresAreTold(store: CompanionStore) {
        store.put("conversation", org.json.JSONObject().put("id", "qq:group:a").put("scope", "qq:group:a").put("title", "测试群"))
        io.deepseekharness.mobile.companion.chat.DeliveryLog.record(store, "qq:group:a", "model", "模型请求失败（中转在返回流里报错）")
        io.deepseekharness.mobile.companion.chat.DeliveryLog.record(store, "qq:group:b", "send", "QQ 拒绝了发送", "晚安")
        val owner = io.deepseekharness.mobile.companion.chat.DeliveryLog.describe(store, "owner:local", ownerView = true)
        assertTrue(owner.contains("测试群") && owner.contains("中转在返回流里报错") && owner.contains("晚安"))
        val group = io.deepseekharness.mobile.companion.chat.DeliveryLog.describe(store, "qq:group:a", ownerView = false)
        assertTrue(group.contains("测试群") && !group.contains("晚安"))
    }

    /** savePersona reads the stored version (it used to recurse through persona()); identical versions collapse; aliases are names. */
    private fun personaVersionsCountUpOnceAndAliasesCount(store: CompanionStore) {
        val first = store.savePersona("小克", "测试人设", "测试", aliases = org.json.JSONArray().put("测试别名"))
        val second = store.savePersona("小克", "测试人设", "测试")
        assertEquals(first.getInt("version") + 1, second.getInt("version"))
        assertTrue(store.personaNames().containsAll(listOf("小克", "测试别名"))) // aliases carry over when not given
        assertTrue(store.dedupePersonaVersions() >= 1)
        assertEquals(0, store.dedupePersonaVersions())
    }

    /** The owner's own chat (or "say this in that group") must go out even when the chat was first seen as a quiet one. */
    private fun ownerRequestedSendIgnoresGroupReplySettings(store: CompanionStore) {
        store.saveSettings(store.settings().put("networkEnabled", true).put("paused", false).put("oneBotEnabled", true).put("oneBotUrl", "wss://bridge.example.test:3001").put("oneBotAccount", "10001"))
        val scope = "qq:private:owner-test"
        store.put("conversation", org.json.JSONObject().put("id", scope).put("scope", scope).put("captureEnabled", true).put("mode", "quiet").put("autoReply", false).put("route", "onebot"))
        fun task(owner: Boolean) = store.put("task", org.json.JSONObject().put("scope", scope).put("type", "send").put("state", "draft").put("body", "在呢")
            .put("target", "主人的 QQ").put("package", "com.tencent.mobileqq").put("account", "10001").put("route", "onebot").put("ownerRequested", owner))
        val mine = task(true).let { store.approveDraft(it.getString("id"), CompanionPolicy.draftDigest(it), automatic = true) }
        assertEquals("sending", store.claimSend(mine.getString("id"), CompanionPolicy.draftDigest(mine)).getString("state"))
        val theirs = task(false).let { store.approveDraft(it.getString("id"), CompanionPolicy.draftDigest(it), automatic = true) }
        assertThrows(IllegalStateException::class.java) { store.claimSend(theirs.getString("id"), CompanionPolicy.draftDigest(theirs)) }
    }
}

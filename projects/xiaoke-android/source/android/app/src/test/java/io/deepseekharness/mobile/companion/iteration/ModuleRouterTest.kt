package io.deepseekharness.mobile.companion.iteration

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModuleRouterTest {
    private val owner = CommandPrincipal("owner", "owner_ui", "owner:local", true)
    @Test fun absentModuleCannotReturnFakeSuccess() {
        val router = ModuleRouter()
        assertFalse(router.snapshot(owner).getJSONObject("life").getBoolean("available"))
        try { router.execute("life", "memory.delete", JSONObject(), owner); fail("must reject") }
        catch (_: IllegalStateException) { }
    }
    @Test fun principalIsNotTakenFromCommandJsonAndEventsAreCopied() {
        val router = ModuleRouter()
        val guest = CommandPrincipal("friend", "group_qq", "qq:group:test", false)
        var received: CommandPrincipal? = null
        val native = object : ModuleEndpoint {
            override val domain = "life"
            override fun snapshot(principal: CommandPrincipal) = JSONObject().put("count", 0)
            override fun execute(action: String, data: JSONObject, principal: CommandPrincipal): JSONObject {
                received = principal; data.put("changed", true); return JSONObject().put("done", true)
            }
            override fun onEvent(event: ModuleEvent) { event.data.put("mutation", true) }
        }
        router.register(native)
        val input = JSONObject().put("owner", true)
        router.execute("life", "memory.search", input, guest)
        assertFalse(received!!.owner); assertFalse(input.has("changed"))
        val data = JSONObject().put("text", "evidence")
        router.event(ModuleEvent("conversation.message", guest.scope, "source", data))
        assertFalse(data.has("mutation"))
        assertTrue(router.snapshot(owner).getJSONObject("life").getBoolean("available"))
    }
}

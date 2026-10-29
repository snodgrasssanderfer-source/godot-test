package io.deepseekharness.mobile.companion.iteration

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** Registered native modules only. Browser preview and absent modules never fabricate results. */
class ModuleRouter {
    private val modules = ConcurrentHashMap<String, ModuleEndpoint>()
    private val listeners = CopyOnWriteArraySet<(JSONObject) -> Unit>()
    private val domains = listOf("life", "device", "social")

    fun register(module: ModuleEndpoint) {
        require(module.domain in domains)
        check(modules.putIfAbsent(module.domain, module) == null) { "模块重复注册" }
    }
    fun available(domain: String) = modules.containsKey(domain)
    fun snapshot(principal: CommandPrincipal): JSONObject = JSONObject().also { out ->
        domains.forEach { domain ->
            val module = modules[domain]
            out.put(domain, if (module == null) JSONObject().put("available", false).put("reason", "模块尚未接入")
                else try { JSONObject(module.snapshot(principal).toString()).put("available", true) }
                catch (_: Exception) { JSONObject().put("available", false).put("reason", "无法读取模块状态，请重新打开后重试") })
        }
    }
    fun execute(domain: String, action: String, data: JSONObject, principal: CommandPrincipal): JSONObject {
        require(domain in domains && action.matches(Regex("[A-Za-z][A-Za-z0-9.]{0,79}"))) { "模块操作无效" }
        val module = modules[domain] ?: error("此模块尚未接入，未执行任何操作")
        return module.execute(action, JSONObject(data.toString()), principal).also { signal(domain, action) }
    }
    fun event(event: ModuleEvent) {
        modules.values.forEach { module ->
            try { module.onEvent(event.copy(data = JSONObject(event.data.toString()))) }
            catch (_: Exception) { signal(module.domain, "event.failed") }
        }
        signal("all", event.type)
    }
    fun subscribe(listener: (JSONObject) -> Unit) { listeners.add(listener) }
    fun unsubscribe(listener: (JSONObject) -> Unit) { listeners.remove(listener) }
    fun signal(domain: String, reason: String) {
        val value = JSONObject().put("domain", domain).put("reason", reason)
        listeners.forEach { listener -> try { listener(JSONObject(value.toString())) } catch (_: Exception) { /* A detached UI cannot fail persisted work. */ } }
    }
    fun close() { modules.values.forEach { it.close() }; modules.clear(); listeners.clear() }
}

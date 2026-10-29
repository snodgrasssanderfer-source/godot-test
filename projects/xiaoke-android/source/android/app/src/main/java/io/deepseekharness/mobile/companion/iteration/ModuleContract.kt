package io.deepseekharness.mobile.companion.iteration

import org.json.JSONObject

/** Host-created identity. Never deserialize owner authority from messages or model output. */
data class CommandPrincipal(val actorId: String, val channel: String, val scope: String, val owner: Boolean)

data class ModuleEvent(val type: String, val scope: String, val sourceId: String, val data: JSONObject)

/** All remote model work goes through the host's provider, budget reservation and cancellation. */
interface HostPorts {
    fun emit(topic: String, payload: JSONObject)
    fun requestModel(request: JSONObject, onEvent: (JSONObject) -> Unit): String
    fun cancelModel(requestId: String)
    fun authorizeEffect(effect: JSONObject, principal: CommandPrincipal): JSONObject
}

/** Called on a worker, except Android permission/activity callbacks explicitly documented by B. */
interface ModuleEndpoint {
    val domain: String
    fun snapshot(principal: CommandPrincipal): JSONObject
    fun execute(action: String, data: JSONObject, principal: CommandPrincipal): JSONObject
    fun onEvent(event: ModuleEvent) {}
    fun close() {}
}

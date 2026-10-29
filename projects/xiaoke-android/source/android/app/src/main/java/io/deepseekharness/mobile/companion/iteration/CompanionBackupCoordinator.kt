package io.deepseekharness.mobile.companion.iteration

import android.content.Context
import io.deepseekharness.mobile.companion.*
import io.deepseekharness.mobile.companion.life.LifeBackup
import io.deepseekharness.mobile.companion.social.SocialLedger
import io.deepseekharness.mobile.companion.social.SocialRuntime
import io.deepseekharness.mobile.companion.social.SocialTransport
import org.json.JSONArray
import org.json.JSONObject

/** One authenticated portable envelope; credentials, executable grants and original media stay out. */
class CompanionBackupCoordinator(private val context: Context, private val engine: CompanionEngine) {
    fun export(password: String): JSONObject {
        val data = engine.store.exportData().put("social", engine.modules.social.exportBackup()).put("combinedVersion", 1)
        return JSONObject().put("text", CompanionBackupCrypto.encrypt(data, password))
    }
    fun restore(text: String, password: String): JSONObject {
        val incoming = CompanionBackupCrypto.decrypt(text, password) // Authentication before any mutation.
        val store = engine.store
        check(engine.activeTask == null && !store.pendingWork()) { "请先停止或完成正在处理的任务，再恢复备份" }
        val host = engine.modules
        LifeBackup.plan(store.exportData(), incoming)
        val social = incoming.optJSONObject("social") ?: JSONObject().put("version", 1)
        // Run the real restore validator on a detached ledger: no socket, model, files or timer.
        val validator = SocialRuntime(SocialLedger(host.social.exportBackup()), object : HostPorts {
            override fun emit(topic: String, payload: JSONObject) {}
            override fun requestModel(request: JSONObject, onEvent: (JSONObject) -> Unit): String = error("恢复校验不能调用模型")
            override fun cancelModel(requestId: String) {}
            override fun authorizeEffect(effect: JSONObject, principal: CommandPrincipal) = JSONObject().put("allowed", false)
        }, SocialTransport { _, _, _ -> error("恢复校验不能访问平台") })
        try { validator.restoreBackup(social, host.localOwner) } finally { validator.close() }
        val previousFences = store.lifeState("socialDeletionHashes").optJSONArray("hashes") ?: JSONArray()
        store.saveSettings(store.settings().put("paused", true).put("networkEnabled", false).put("oneBotEnabled", false))
        OneBotBridge.get(context).stop()
        CompanionPresenceHost.stop(context)
        host.device.execute("control.stop", JSONObject(), host.localOwner)
        host.device.execute("audio.stop", JSONObject(), host.localOwner)
        host.refreshConnectivity()
        // Restore is intentionally fail-closed: a later storage failure leaves network paused.
        store.restoreData(incoming)
        host.contentChanged()
        host.social.restoreBackup(social, host.localOwner)
        host.applyDeletion(JSONObject().put("sourceHashes", previousFences))
        host.contentChanged()
        host.router.signal("all", "backup.restored")
        return JSONObject().put("restored", true).put("networkPaused", true)
    }
}

package io.deepseekharness.mobile.companion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android adaptation of mem0 SQLiteManager's scoped messages and transactional history.
 * Upstream: mem0ai/mem0 26cdefb46600c0fa5953c4b5ee5003c125539fc4 (Apache-2.0).
 * Changes: Android SQLite, mandatory scope, source links, tombstones, job recovery;
 * no automatic deletion of all but the last ten messages, no embedding/cloud dependency.
 */
class CompanionStore(private val context: Context) : SQLiteOpenHelper(context, "companion.db", null, 1) {
    private var lastSocialPrune = 0L
    private val temporaryObservations = TemporaryObservations()
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.execSQL("PRAGMA secure_delete=ON") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records(kind TEXT NOT NULL,id TEXT NOT NULL,scope TEXT NOT NULL,json TEXT NOT NULL,updated INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(kind,id))")
        db.execSQL("CREATE INDEX scoped_records ON records(kind,scope,deleted,updated)")
        db.execSQL("CREATE TABLE memory_history(id INTEGER PRIMARY KEY AUTOINCREMENT,memory_id TEXT NOT NULL,scope TEXT NOT NULL,previous_value TEXT,new_value TEXT,action TEXT NOT NULL,created_at INTEGER NOT NULL,is_deleted INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX memory_revisions ON memory_history(scope,memory_id,id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("未知数据库升级版本，保留原数据并停止升级")
    }

    @Synchronized fun get(kind: String, id: String): JSONObject? {
        if (kind == "observation") temporaryObservations.get(id)?.let { return it }
        return readableDatabase.rawQuery(
        "SELECT json FROM records WHERE kind=? AND id=? AND deleted=0", arrayOf(kind, id)
    ).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    }

    @Synchronized fun list(kind: String, scope: String? = null, limit: Int = 1000): JSONArray {
        require(limit in 1..10000)
        val sql = "SELECT json,updated FROM records WHERE kind=? AND deleted=0" + if (scope == null) "" else " AND scope=?"
        val args = if (scope == null) arrayOf(kind) else arrayOf(kind, CompanionPolicy.scope(scope))
        return JSONArray().also { result ->
            readableDatabase.rawQuery("SELECT json FROM ($sql ORDER BY updated DESC LIMIT $limit) ORDER BY updated ASC", args).use { c ->
                while (c.moveToNext()) result.put(JSONObject(c.getString(0)))
            }
            if (kind == "observation") temporaryObservations.list(scope).forEach(result::put)
        }
    }

    @Synchronized fun put(kind: String, record: JSONObject): JSONObject {
        require(kind in KINDS) { "记录类型无效" }
        val value = JSONObject(record.toString())
        val id = value.optString("id").ifEmpty { CompanionPolicy.id() }
        require(id.length in 1..160)
        val scope = CompanionPolicy.scope(value.optString("scope", CompanionPolicy.OWNER))
        if (kind == "observation" && value.optBoolean("temporary")) return temporaryObservations.put(value
            .put("id", id).put("scope", scope).put("updatedAt", System.currentTimeMillis()))
        if (kind == "observation" && value.has("parts")) {
            val folder = java.io.File(context.filesDir, "companion-media").apply { mkdirs() }
            val file = java.io.File(folder, CompanionPolicy.digest(id) + ".json")
            val temp = java.io.File(folder, file.name + ".tmp")
            try {
                temp.writeText(value.getJSONArray("parts").toString(), Charsets.UTF_8)
                check(temp.renameTo(file)) { "观察资料保存失败" }
            } finally { temp.delete() }
            value.remove("parts"); value.put("mediaStored", true)
        }
        value.put("id", id).put("scope", scope).put("updatedAt", System.currentTimeMillis())
        val values = ContentValues().apply {
            put("kind", kind); put("id", id); put("scope", scope); put("json", value.toString())
            put("updated", value.getLong("updatedAt")); put("deleted", 0)
        }
        writableDatabase.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return value
    }

    fun settings() = get("settings", "main") ?: CompanionPolicy.defaultSettings()
    @Synchronized fun saveSettings(value: JSONObject) = put("settings", CompanionPolicy.validateSettings(value)
        .put("credentialRevision", settings().optLong("credentialRevision")).put("id", "main"))
    @Synchronized fun credentialChanged() { put("settings", settings().put("id", "main").put("credentialRevision", settings().optLong("credentialRevision") + 1)) }

    /** Shared by chat and presence; retries and continuations each consume one request. */
    @Synchronized fun reserveModelRequest() {
        val settings = settings()
        check(settings.optBoolean("networkEnabled") && !settings.optBoolean("paused")) { "联网已关闭或助手已暂停" }
        val day = java.time.LocalDate.now().toString()
        val usage = get("usage", day) ?: JSONObject().put("id", day).put("count", 0)
        check(usage.optInt("count") < settings.optInt("dailyCalls", 100)) { "已到每日请求上限（包含重试与续写）" }
        put("usage", usage.put("count", usage.optInt("count") + 1))
    }
    fun persona(): JSONObject = get("persona", "active") ?: JSONObject().put("name", "小克").put("text", "").put("version", 0)

    @Synchronized fun savePersona(name: String, text: String, source: String): JSONObject {
        require(name.length in 1..40 && text.length <= 64000 && source.length <= 1000)
        val next = JSONObject().put("name", name).put("text", text).put("source", source)
            .put("version", persona().optInt("version") + 1)
        put("personaVersion", next)
        return put("persona", next.put("id", "active"))
    }

    @Synchronized fun message(scope: String, role: String, text: String, source: String, wire: JSONObject? = null, replyTo: String = ""): JSONObject {
        require(role in setOf("user", "assistant", "notice"))
        val value = JSONObject().put("scope", CompanionPolicy.scope(scope)).put("role", role)
            .put("text", text.take(128000)).put("source", source.take(1000)).put("createdAt", System.currentTimeMillis())
        if (wire != null) value.put("wire", wire)
        if (replyTo.isNotEmpty()) value.put("replyTo", replyTo)
        return put("message", value)
    }

    @Synchronized fun saveMemory(scope: String, input: JSONObject, fromModel: Boolean = false, sourceScope: String = scope): JSONObject {
        CompanionPolicy.scope(scope)
        val id = input.optString("id").ifBlank { CompanionPolicy.id() }
        val old = get("memory", id)
        require(old == null || old.getString("scope") == scope) { "不能跨会话修改记忆" }
        val kind = input.optString("kind", "fact")
        require(kind in CompanionPolicy.memoryKinds)
        val content = CompanionPolicy.text(input.getString("text"), 8000)
        if (fromModel) {
            check(old == null) { "模型只能新增待确认记忆，不能覆盖主人已编辑的记录" }
            val existing = list("memory", scope, 10000)
            val normalized = content.replace(Regex("\\s+"), "").lowercase()
            for (i in 0 until existing.length()) {
                val memory = existing.getJSONObject(i)
                if (memory.optString("text").replace(Regex("\\s+"), "").lowercase() == normalized) return memory
            }
        }
        val sourceId = input.optString("sourceId")
        if (fromModel) {
            val source = get("message", sourceId) ?: error("模型记忆必须指向已有消息")
            require(source.getString("scope") == sourceScope) { "记忆来源不属于本会话" }
        }
        val value = JSONObject().put("id", id).put("scope", scope).put("kind", kind).put("text", content)
            .put("sourceId", sourceId).put("source", input.optString("source", "主人编辑").take(1000))
            .put("sourceScope", sourceScope)
            .put("status", if (fromModel) "unverified" else "confirmed")
            .put("pinned", !fromModel && input.optBoolean("pinned"))
            .put("createdAt", old?.optLong("createdAt") ?: System.currentTimeMillis())
        writableDatabase.beginTransaction()
        try {
            val saved = put("memory", value)
            writableDatabase.execSQL("INSERT INTO memory_history(memory_id,scope,previous_value,new_value,action,created_at) VALUES(?,?,?,?,?,?)",
                arrayOf(id, scope, old?.optString("text"), content, if (old == null) "ADD" else "UPDATE", System.currentTimeMillis()))
            writableDatabase.setTransactionSuccessful()
            return saved
        } finally { writableDatabase.endTransaction() }
    }

    @Synchronized fun deleteMemory(scope: String, id: String) {
        val old = get("memory", id) ?: return
        require(old.getString("scope") == CompanionPolicy.scope(scope))
        writableDatabase.beginTransaction()
        try {
            // Explicit delete removes old content too; retaining revision text would defeat deletion.
            writableDatabase.delete("memory_history", "memory_id=? AND scope=?", arrayOf(id, scope))
            writableDatabase.execSQL("UPDATE records SET json=?,deleted=1,updated=? WHERE kind='memory' AND id=? AND scope=?",
                arrayOf(JSONObject().put("id", id).put("scope", scope).put("deleted", true).toString(), System.currentTimeMillis(), id, scope))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun memoriesFor(scope: String, query: String = ""): JSONArray {
        return MemoryRanking.rank(list("memory", scope, 10000), query)
    }

    fun observationParts(id: String): JSONArray {
        val record = get("observation", id) ?: error("观察资料不存在")
        record.optJSONArray("parts")?.let { return it }
        val file = java.io.File(context.filesDir, "companion-media/${CompanionPolicy.digest(id)}.json")
        check(file.isFile && file.length() <= 24 * 1024 * 1024) { "原始媒体未保留或来自不含媒体的备份，请重新采集或导入" }
        return JSONArray(file.readText(Charsets.UTF_8))
    }
    @Synchronized fun deleteObservation(id: String) {
        temporaryObservations.remove(id)
        java.io.File(context.filesDir, "companion-media/${CompanionPolicy.digest(id)}.json").delete()
        writableDatabase.delete("records", "kind='observation' AND id=?", arrayOf(id))
    }
    @Synchronized fun saveTemporaryObservation(id: String): JSONObject {
        val value = temporaryObservations.get(id) ?: error("临时画面已过期或会话已结束")
        value.remove("temporary"); value.remove("expiresAt")
        val saved = put("observation", value)
        temporaryObservations.remove(id)
        return saved
    }
    fun clearTemporaryObservations() { temporaryObservations.clear() }

    @Synchronized fun clearConversation(scope: String) {
        require(scope.startsWith("qq:")); CompanionPolicy.scope(scope)
        val media = list("observation", scope, 10000)
        for (i in 0 until media.length()) deleteObservation(media.getJSONObject(i).getString("id"))
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("memory_history", "scope=?", arrayOf(scope))
            writableDatabase.delete("records", "scope=? AND kind IN ('memory','message','task')", arrayOf(scope))
            get("conversation", scope)?.let { put("conversation", it.put("captureEnabled", false).put("autoReply", false).put("mode", "quiet")) }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    @Synchronized fun pruneSocialHistory(now: Long = System.currentTimeMillis()) {
        if (now >= lastSocialPrune && now - lastSocialPrune < 3600000) return
        lastSocialPrune = now
        val groups = list("conversation")
        for (i in 0 until groups.length()) {
            val c = groups.getJSONObject(i); val scope = c.getString("scope")
            if (!scope.startsWith("qq:group:")) continue
            val cutoff = now - c.optInt("retentionDays", 30).coerceIn(1, 365) * 86400000L
            writableDatabase.delete("memory_history", "scope=? AND created_at<?", arrayOf(scope, cutoff.toString()))
            writableDatabase.delete("records", "scope=? AND kind IN ('memory','message') AND updated<?", arrayOf(scope, cutoff.toString()))
            val media = list("observation", scope, 10000)
            for (j in 0 until media.length()) media.getJSONObject(j).let {
                if (it.optLong("createdAt", it.optLong("updatedAt")) < cutoff) deleteObservation(it.getString("id"))
            }
        }
    }

    /** Grant and state transition are indivisible, so cancellation cannot be overwritten. */
    @Synchronized fun approveDraft(id: String, expectedDigest: String, automatic: Boolean = false, binding: JSONObject? = null): JSONObject {
        val task = get("task", id) ?: error("草稿不存在")
        check(task.optString("type") == "send" && task.optString("state") in setOf("draft", "approved")) { "草稿已取消或正在提交" }
        check(CompanionPolicy.draftDigest(task) == expectedDigest) { "草稿已经变化，请重新核对" }
        if (task.optString("package") == "tv.danmaku.bili") check(task.optString("account").isNotBlank()) { "请先填写并核对 B站登录账号标记" }
        if (binding != null) task.put("uiBinding", binding)
        val ttl = if (automatic) 15000 else if (task.optString("package") == "tv.danmaku.bili") 300000 else 120000
        return put("task", task.put("state", "approved").put("automaticGrant", automatic)
            .put("grantExpiresAt", System.currentTimeMillis() + ttl).put("grantDigest", CompanionPolicy.draftDigest(task)))
    }

    @Synchronized fun editDraft(id: String, body: String, account: String? = null): JSONObject {
        val task = get("task", id) ?: error("草稿不存在")
        check(task.optString("state") in setOf("draft", "approved")) { "草稿已取消或正在提交" }
        if (account != null && task.optString("package") == "tv.danmaku.bili") task.put("account", CompanionPolicy.text(account, 80))
        return put("task", task.put("body", CompanionPolicy.text(body, 4000)).put("state", "draft").removeGrant())
    }

    @Synchronized fun recoverJobs() {
        val tasks = list("task")
        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            val before = task.optString("state")
            val after = CompanionPolicy.recoverState(before)
            if (before != after) put("task", task.put("state", after).put("error", if (after == "unknown") "发送过程被中断，请先核对目标端，勿重复发送" else "任务被中断，等待主人恢复").removeGrant())
        }
    }

    /** Serialized claim prevents duplicate sends when confirmation, cancel and workers race. */
    @Synchronized fun claimSend(id: String, expectedDigest: String, now: Long = System.currentTimeMillis()): JSONObject {
        val current = get("task", id) ?: error("草稿不存在")
        check(CompanionPolicy.canSend(current, now) && CompanionPolicy.draftDigest(current) == expectedDigest) { "草稿已改变、取消或授权已失效，请重新核对" }
        val settings = settings()
        check(!settings.optBoolean("paused") && settings.optBoolean("networkEnabled")) { "助手已暂停或联网已关闭" }
        if (current.optString("route") == "onebot") check(current.optString("account") == settings.optString("oneBotAccount") && settings.optBoolean("oneBotEnabled")) { "消息桥账号或开关已变化" }
        if (current.optBoolean("automaticGrant")) {
            val c = get("conversation", current.getString("scope")) ?: error("会话不存在")
            check(c.optBoolean("captureEnabled") && c.optBoolean("autoReply") && c.optString("mode") != "quiet" &&
                now - c.optLong("humanActivityAt") > 120000) { "自动发送范围已撤销，或主人正在回复此会话" }
        }
        return put("task", current.put("state", "sending").put("submittedAt", now).removeGrant())
    }

    @Synchronized fun transitionTask(id: String, from: Set<String>, state: String, progress: String = "", extra: JSONObject? = null): JSONObject? {
        val current = get("task", id) ?: return null
        if (current.optString("state") !in from) return null
        check(state in CompanionPolicy.taskStates)
        current.put("state", state).put("progress", progress)
        extra?.keys()?.forEach { key -> if (key !in setOf("id", "scope", "state", "grantDigest", "grantExpiresAt")) current.put(key, extra.get(key)) }
        if (state !in setOf("draft", "approved")) current.removeGrant()
        return put("task", current)
    }

    @Synchronized fun cancelTask(id: String): JSONObject? {
        val current = get("task", id) ?: return null
        val after = CompanionPolicy.cancelledState(current.optString("state"))
        return put("task", current.put("state", after).removeGrant().put("progress",
            if (after == "unknown") "取消时已进入提交阶段，不能撤回已发生的操作；请核对目标平台，不自动重发" else "已取消"))
    }

    fun pendingWork(): Boolean {
        val jobs = list("task", limit = 1000)
        return (0 until jobs.length()).any { jobs.getJSONObject(it).optString("state") in setOf("queued", "running", "sending") }
    }

    /** Opaque provider replay data is temporary; visible conversation history stays durable. */
    @Synchronized fun pruneProviderHistory(scope: String, keep: Int = 24) {
        val messages = list("message", scope, 10000)
        val withWire = (0 until messages.length()).map(messages::getJSONObject).filter { it.has("wire") }
        for (m in withWire.dropLast(keep)) {
            m.remove("wire")
            writableDatabase.execSQL("UPDATE records SET json=? WHERE kind='message' AND id=? AND scope=?",
                arrayOf(m.toString(), m.getString("id"), scope))
        }
    }

    @Synchronized fun memoryHistory(scope: String, id: String): JSONArray {
        val record = get("memory", id) ?: return JSONArray()
        check(record.getString("scope") == CompanionPolicy.scope(scope))
        return JSONArray().also { out -> readableDatabase.rawQuery(
            "SELECT previous_value,new_value,action,created_at FROM memory_history WHERE memory_id=? AND scope=? ORDER BY id DESC LIMIT 40", arrayOf(id, scope)
        ).use { c -> while(c.moveToNext()) out.put(JSONObject().put("before", c.getString(0)).put("after", c.getString(1)).put("action", c.getString(2)).put("at", c.getLong(3))) } }
    }

    @Synchronized fun exportData(): JSONObject = JSONObject().put("format", "dsh-companion-backup").put("version", 1)
        .put("createdAt", System.currentTimeMillis()).also { out ->
            BACKUP_KINDS.forEach { kind ->
                val all = list(kind, limit = 10000)
                val values = JSONArray().also { rows -> for (i in 0 until all.length()) all.getJSONObject(i).let { if (!it.optBoolean("temporary")) rows.put(it) } }
                for (i in 0 until values.length()) {
                    val value = values.getJSONObject(i)
                    if (kind == "message") value.remove("wire")
                    if (kind == "observation") { value.remove("parts"); value.put("mediaStored", false) }
                    if (kind == "task") value.removeGrant()
                }
                out.put(kind, values)
            }
        }

    @Synchronized fun restoreData(data: JSONObject) {
        temporaryObservations.clear()
        require(data.getString("format") == "dsh-companion-backup" && data.getInt("version") == 1) { "备份格式不受支持" }
        // Validate all rows before any write. Credentials, network/owner grants are never restored.
        val rows = BACKUP_KINDS.flatMap { kind ->
            val a = data.optJSONArray(kind) ?: JSONArray()
            require(a.length() <= 10000)
            (0 until a.length()).map { i -> kind to JSONObject(a.getJSONObject(i).toString()).also {
                CompanionPolicy.scope(it.getString("scope")); require(it.getString("id").length in 1..160)
                if (kind == "task") it.put("state", "waiting").removeGrant()
                if (kind == "message") it.remove("wire")
                if (kind == "observation") { it.remove("parts"); it.put("mediaStored", false) }
            } }
        }
        writableDatabase.beginTransaction()
        try {
            BACKUP_KINDS.forEach { writableDatabase.delete("records", "kind=?", arrayOf(it)) }
            writableDatabase.delete("memory_history", null, null)
            rows.forEach { (kind, value) -> put(kind, value) }
            java.io.File(context.filesDir, "companion-media").listFiles()?.forEach { it.delete() }
            saveSettings(settings().put("networkEnabled", false).put("paused", true).put("oneBotEnabled", false))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    companion object {
        private val BACKUP_KINDS = listOf("persona", "personaVersion", "memory", "message", "observation", "task")
        private val KINDS = BACKUP_KINDS + listOf("settings", "conversation", "usage")
    }
}

fun JSONObject.removeGrant(): JSONObject { remove("grantDigest"); remove("grantExpiresAt"); return this }

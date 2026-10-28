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
class CompanionStore(context: Context) : SQLiteOpenHelper(context, "companion.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records(kind TEXT NOT NULL,id TEXT NOT NULL,scope TEXT NOT NULL,json TEXT NOT NULL,updated INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(kind,id))")
        db.execSQL("CREATE INDEX scoped_records ON records(kind,scope,deleted,updated)")
        db.execSQL("CREATE TABLE memory_history(id INTEGER PRIMARY KEY AUTOINCREMENT,memory_id TEXT NOT NULL,scope TEXT NOT NULL,previous_value TEXT,new_value TEXT,action TEXT NOT NULL,created_at INTEGER NOT NULL,is_deleted INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX memory_revisions ON memory_history(scope,memory_id,id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("未知数据库升级版本，保留原数据并停止升级")
    }

    @Synchronized fun get(kind: String, id: String): JSONObject? = readableDatabase.rawQuery(
        "SELECT json FROM records WHERE kind=? AND id=? AND deleted=0", arrayOf(kind, id)
    ).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }

    @Synchronized fun list(kind: String, scope: String? = null, limit: Int = 1000): JSONArray {
        require(limit in 1..10000)
        val sql = "SELECT json,updated FROM records WHERE kind=? AND deleted=0" + if (scope == null) "" else " AND scope=?"
        val args = if (scope == null) arrayOf(kind) else arrayOf(kind, CompanionPolicy.scope(scope))
        return JSONArray().also { result ->
            readableDatabase.rawQuery("SELECT json FROM ($sql ORDER BY updated DESC LIMIT $limit) ORDER BY updated ASC", args).use { c ->
                while (c.moveToNext()) result.put(JSONObject(c.getString(0)))
            }
        }
    }

    @Synchronized fun put(kind: String, record: JSONObject): JSONObject {
        require(kind in KINDS) { "记录类型无效" }
        val value = JSONObject(record.toString())
        val id = value.optString("id").ifEmpty { CompanionPolicy.id() }
        require(id.length in 1..160)
        val scope = CompanionPolicy.scope(value.optString("scope", CompanionPolicy.OWNER))
        value.put("id", id).put("scope", scope).put("updatedAt", System.currentTimeMillis())
        val values = ContentValues().apply {
            put("kind", kind); put("id", id); put("scope", scope); put("json", value.toString())
            put("updated", value.getLong("updatedAt")); put("deleted", 0)
        }
        writableDatabase.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return value
    }

    fun settings() = get("settings", "main") ?: CompanionPolicy.defaultSettings()
    fun saveSettings(value: JSONObject) = put("settings", CompanionPolicy.validateSettings(value).put("id", "main"))
    fun persona(): JSONObject = get("persona", "active") ?: JSONObject().put("name", "小克").put("text", "").put("version", 0)

    @Synchronized fun savePersona(name: String, text: String, source: String): JSONObject {
        require(name.length in 1..40 && text.length <= 64000 && source.length <= 1000)
        val next = JSONObject().put("name", name).put("text", text).put("source", source)
            .put("version", persona().optInt("version") + 1)
        put("personaVersion", next)
        return put("persona", next.put("id", "active"))
    }

    @Synchronized fun message(scope: String, role: String, text: String, source: String, wire: JSONObject? = null): JSONObject {
        require(role in setOf("user", "assistant", "notice"))
        val value = JSONObject().put("scope", CompanionPolicy.scope(scope)).put("role", role)
            .put("text", text.take(128000)).put("source", source.take(1000)).put("createdAt", System.currentTimeMillis())
        if (wire != null) value.put("wire", wire)
        return put("message", value)
    }

    @Synchronized fun saveMemory(scope: String, input: JSONObject, fromModel: Boolean = false): JSONObject {
        CompanionPolicy.scope(scope)
        val id = input.optString("id").ifBlank { CompanionPolicy.id() }
        val old = get("memory", id)
        require(old == null || old.getString("scope") == scope) { "不能跨会话修改记忆" }
        val kind = input.optString("kind", "fact")
        require(kind in CompanionPolicy.memoryKinds)
        val content = CompanionPolicy.text(input.getString("text"), 8000)
        val sourceId = input.optString("sourceId")
        if (fromModel) {
            val source = get("message", sourceId) ?: error("模型记忆必须指向已有消息")
            require(source.getString("scope") == scope) { "记忆来源不属于本会话" }
        }
        val value = JSONObject().put("id", id).put("scope", scope).put("kind", kind).put("text", content)
            .put("sourceId", sourceId).put("source", input.optString("source", "主人编辑").take(1000))
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
        val all = list("memory", scope)
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }.take(8)
        val values = (0 until all.length()).map { all.getJSONObject(it) }
            .sortedByDescending { m -> (if (m.optBoolean("pinned")) 100 else 0) + terms.count { m.getString("text").lowercase().contains(it) } }
        return JSONArray().also { a -> values.take(80).forEach(a::put) }
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

    @Synchronized fun exportData(): JSONObject = JSONObject().put("format", "dsh-companion-backup").put("version", 1)
        .put("createdAt", System.currentTimeMillis()).also { out ->
            BACKUP_KINDS.forEach { kind ->
                val values = list(kind, limit = 10000)
                for (i in 0 until values.length()) {
                    val value = values.getJSONObject(i)
                    if (kind == "message") value.remove("wire")
                    if (kind == "observation") value.remove("parts")
                }
                out.put(kind, values)
            }
        }

    @Synchronized fun restoreData(data: JSONObject) {
        require(data.getString("format") == "dsh-companion-backup" && data.getInt("version") == 1) { "备份格式不受支持" }
        // Validate all rows before any write. Credentials, network/owner grants are never restored.
        val rows = BACKUP_KINDS.flatMap { kind ->
            val a = data.optJSONArray(kind) ?: JSONArray()
            require(a.length() <= 10000)
            (0 until a.length()).map { i -> kind to JSONObject(a.getJSONObject(i).toString()).also {
                CompanionPolicy.scope(it.getString("scope")); require(it.getString("id").length in 1..160)
                if (kind == "task") it.put("state", "waiting").removeGrant()
            } }
        }
        writableDatabase.beginTransaction()
        try {
            BACKUP_KINDS.forEach { writableDatabase.delete("records", "kind=?", arrayOf(it)) }
            writableDatabase.delete("memory_history", null, null)
            rows.forEach { (kind, value) -> put(kind, value) }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    companion object {
        private val BACKUP_KINDS = listOf("persona", "personaVersion", "memory", "message", "observation", "task")
        private val KINDS = BACKUP_KINDS + listOf("settings", "conversation", "usage")
    }
}

fun JSONObject.removeGrant(): JSONObject { remove("grantDigest"); remove("grantExpiresAt"); return this }

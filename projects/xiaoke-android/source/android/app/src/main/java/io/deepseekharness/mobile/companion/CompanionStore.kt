package io.deepseekharness.mobile.companion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import io.deepseekharness.mobile.companion.life.BudgetLedger
import io.deepseekharness.mobile.companion.life.LifePolicy
import io.deepseekharness.mobile.companion.life.RecordRef

/**
 * Android adaptation of mem0 SQLiteManager's scoped messages and transactional history.
 * Upstream: mem0ai/mem0 26cdefb46600c0fa5953c4b5ee5003c125539fc4 (Apache-2.0).
 * Changes: Android SQLite, mandatory scope, source links, tombstones, job recovery;
 * no automatic deletion of all but the last ten messages, no embedding/cloud dependency.
 */
class CompanionStore(private val context: Context, databaseName: String = "companion.db") : SQLiteOpenHelper(context, databaseName, null, 2) {
    private var lastSocialPrune = 0L
    private val temporaryObservations = TemporaryObservations()
    private val committingSources = ThreadLocal<List<RecordRef>?>()
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.execSQL("PRAGMA secure_delete=ON") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records(kind TEXT NOT NULL,id TEXT NOT NULL,scope TEXT NOT NULL,json TEXT NOT NULL,updated INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(kind,id))")
        db.execSQL("CREATE INDEX scoped_records ON records(kind,scope,deleted,updated)")
        db.execSQL("CREATE TABLE memory_history(id INTEGER PRIMARY KEY AUTOINCREMENT,memory_id TEXT NOT NULL,scope TEXT NOT NULL,previous_value TEXT,new_value TEXT,action TEXT NOT NULL,created_at INTEGER NOT NULL,is_deleted INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX memory_revisions ON memory_history(scope,memory_id,id)")
        createLifeSchema(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "未知数据库升级版本，保留原数据并停止升级" }
        createLifeSchema(db)
        db.execSQL("INSERT OR IGNORE INTO life_tombstones(kind,id,scope,deleted_at,epoch,media_pending) SELECT kind,id,scope,updated,1,0 FROM records WHERE deleted=1")
    }
    private fun createLifeSchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS life_tombstones(kind TEXT NOT NULL,id TEXT NOT NULL,scope TEXT NOT NULL,deleted_at INTEGER NOT NULL,epoch INTEGER NOT NULL,media_pending INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(kind,id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS life_revisions(kind TEXT NOT NULL,id TEXT NOT NULL,revision INTEGER NOT NULL,scope TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(kind,id,revision))")
    }

    /** Every ledger read-modify-write uses one SQLite transaction, including calls from other threads. */
    @Synchronized fun <T> lifeTransaction(block: () -> T): T {
        val db = writableDatabase
        if (db.inTransaction()) return block()
        db.beginTransaction()
        try { return block().also { db.setTransactionSuccessful() } }
        finally { db.endTransaction() }
    }
    @Synchronized fun lifeState(id: String): JSONObject = get("lifeState", id) ?: JSONObject().put("id", id).put("scope", CompanionPolicy.OWNER)
    @Synchronized fun saveLifeState(id: String, value: JSONObject): JSONObject = put("lifeState", JSONObject(value.toString()).put("id", id).put("scope", CompanionPolicy.OWNER))
    @Synchronized fun contentEpoch(): Long = lifeState("contentEpoch").optLong("value", 1)
    @Synchronized fun <T> commitContext(epoch: Long, block: () -> T): T = lifeTransaction {
        check(epoch == contentEpoch()) { "上下文已被纠正、忘掉或删除，请重新生成" }
        block()
    }
    @Synchronized fun <T> commitContext(epoch: Long, sources: JSONArray, block: () -> T): T = commitContext(epoch) {
        val refs = (0 until sources.length()).map { RecordRef.parse(sources.getJSONObject(it)) }
        refs.forEach { check(get(it.kind, it.id)?.getString("scope") == it.scope) { "上下文来源已失效" } }
        val previous = committingSources.get()
        committingSources.set(refs)
        try { block() } finally { committingSources.set(previous) }
    }
    /** Keep this ticket with the actual provider request, then commit with its epoch and sourceRefs. */
    @Synchronized fun contextSnapshot(scope: String, query: String = ""): JSONObject = lifeTransaction {
        val messages = contextMessages(scope)
        val memories = memoriesFor(scope, query)
        val refs = JSONArray()
        for ((kind, rows) in listOf("message" to messages, "memory" to memories)) {
            for (i in 0 until rows.length()) refs.put(LifePolicy.reference(kind, rows.getJSONObject(i)).json())
        }
        JSONObject().put("epoch", contentEpoch()).put("messages", messages).put("memories", memories).put("sourceRefs", refs)
    }
    @Synchronized fun bumpContentEpoch(): Long = lifeTransaction {
        val next = Math.addExact(contentEpoch(), 1L)
        saveLifeState("contentEpoch", JSONObject().put("value", next)); next
    }
    @Synchronized fun isDeleted(kind: String, id: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM life_tombstones WHERE kind=? AND id=?", arrayOf(kind, id)
    ).use { it.moveToFirst() }

    @Synchronized fun tombstones(): JSONArray = JSONArray().also { out ->
        readableDatabase.rawQuery("SELECT kind,id,scope,deleted_at,epoch,media_pending FROM life_tombstones ORDER BY epoch", null).use { c ->
            while (c.moveToNext()) out.put(JSONObject().put("kind", c.getString(0)).put("id", c.getString(1)).put("scope", c.getString(2))
                .put("deletedAt", c.getLong(3)).put("epoch", c.getLong(4)).put("mediaPending", c.getInt(5) != 0))
        }
    }

    @Synchronized fun contentRecords(): List<Pair<String, JSONObject>> = buildList {
        readableDatabase.rawQuery("SELECT kind,json FROM records WHERE deleted=0", null).use { c ->
            while (c.moveToNext()) if (c.getString(0) in RecordRef.kinds) add(c.getString(0) to JSONObject(c.getString(1)))
        }
        temporaryObservations.list(null).forEach { add("observation" to it) }
    }

    @Synchronized fun recordRevision(kind: String, old: JSONObject) {
        val values = ContentValues().apply {
            put("kind", kind); put("id", old.getString("id")); put("scope", old.getString("scope"))
            put("revision", old.optInt("revision", 1)); put("json", old.toString())
        }
        writableDatabase.insertWithOnConflict("life_revisions", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun lifeHistory(kind: String, scope: String, id: String): JSONArray = JSONArray().also { out ->
        val current = get(kind, id) ?: return@also
        check(current.getString("scope") == scope)
        readableDatabase.rawQuery("SELECT json FROM life_revisions WHERE kind=? AND id=? AND scope=? ORDER BY revision", arrayOf(kind, id, scope)).use { c ->
            while (c.moveToNext()) out.put(JSONObject(c.getString(0)))
        }
        out.put(current)
    }

    /** Source removal is conservative: whole source messages and every derivative are erased together. */
    @Synchronized fun deleteContent(roots: Set<RecordRef>): JSONObject = lifeTransaction {
        val deleted = LifePolicy.descendants(roots, contentRecords())
        if (deleted.isEmpty()) return@lifeTransaction JSONObject().put("deleted", JSONArray()).put("contentEpoch", contentEpoch())
        val epoch = bumpContentEpoch()
        for (ref in deleted) {
            val values = ContentValues().apply {
                put("kind", ref.kind); put("id", ref.id); put("scope", ref.scope); put("deleted_at", System.currentTimeMillis())
                put("epoch", epoch); put("media_pending", if (ref.kind == "observation") 1 else 0)
            }
            writableDatabase.insertWithOnConflict("life_tombstones", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            writableDatabase.delete("records", "kind=? AND id=?", arrayOf(ref.kind, ref.id))
            writableDatabase.delete("life_revisions", "kind=? AND id=?", arrayOf(ref.kind, ref.id))
            if (ref.kind == "memory") writableDatabase.delete("memory_history", "memory_id=?", arrayOf(ref.id))
            if (ref.kind == "observation") temporaryObservations.remove(ref.id)
        }
        // Durable tombstones are also the cleanup queue. Read/output exclusion does not wait for filesystem cleanup.
        purgeDeletedMedia()
        JSONObject().put("deleted", JSONArray().also { a -> deleted.forEach { a.put(it.json()) } }).put("contentEpoch", epoch)
    }

    @Synchronized fun purgeDeletedMedia() {
        val pending = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT id FROM life_tombstones WHERE kind='observation' AND media_pending=1", null).use { c ->
            while (c.moveToNext()) pending.add(c.getString(0))
        }
        for (id in pending) {
            val file = java.io.File(context.filesDir, "companion-media/${CompanionPolicy.digest(id)}.json")
            val temporary = java.io.File(file.parentFile, file.name + ".tmp")
            val gone = (!file.exists() || file.delete()) && (!temporary.exists() || temporary.delete())
            if (gone) writableDatabase.execSQL("UPDATE life_tombstones SET media_pending=0 WHERE kind='observation' AND id=?", arrayOf(id))
        }
    }

    @Synchronized fun forgetMemory(scope: String, id: String, forgotten: Boolean): JSONObject = lifeTransaction {
        val memory = get("memory", id) ?: error("记忆不存在")
        check(memory.getString("scope") == CompanionPolicy.scope(scope))
        val records = contentRecords()
        val root = LifePolicy.reference("memory", memory)
        val sameFact = records.filter { it.first == "memory" && it.second.getString("scope") == scope &&
            memory.optString("factKey").isNotBlank() && it.second.optString("factKey") == memory.optString("factKey") }.map { it.second }
        val roots = setOf(root) + LifePolicy.sources(memory) + sameFact.flatMap { listOf(LifePolicy.reference("memory", it)) + LifePolicy.sources(it) }
        val targets = LifePolicy.descendants(roots, records).map { it.key }.toSet()
        for ((kind, value) in records) {
            val key = LifePolicy.reference(kind, value).key
            val ids = value.optJSONArray("suppressedBy") ?: JSONArray()
            val causes = (0 until ids.length()).map(ids::getString).toMutableSet()
            if (forgotten && key in targets) causes.add(id) else if (!forgotten) causes.remove(id)
            if (key in targets || id in (0 until ids.length()).map(ids::getString)) {
                if (key == root.key) value.put("forgotten", forgotten)
                value.put("suppressedBy", JSONArray(causes.toList())).put("suppressed", causes.isNotEmpty())
                put(kind, value)
            }
        }
        bumpContentEpoch()
        get("memory", id)!!
    }

    @Synchronized fun contextMessages(scope: String, limit: Int = 10000): JSONArray {
        val all = list("message", scope, limit)
        return JSONArray().also { out -> for (i in 0 until all.length()) all.getJSONObject(i).let { if (LifePolicy.visible(it)) out.put(it) } }
    }

    @Synchronized fun get(kind: String, id: String): JSONObject? {
        if (kind in RecordRef.kinds && isDeleted(kind, id)) return null
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
        if (kind in RecordRef.kinds && committingSources.get() != null) {
            val refs = (LifePolicy.sources(value) + committingSources.get().orEmpty()).distinctBy { it.key }
                .filter { it.key != "$kind\u0000$id" }
            value.put("sourceRefs", JSONArray().also { a -> refs.forEach { a.put(it.json()) } })
                .put("contextEpoch", contentEpoch())
        }
        check(!isDeleted(kind, id)) { "记录已被删除，不能重新写入" }
        val existing = get(kind, id)
        check(existing == null || existing.getString("scope") == scope) { "不能更换已有记录的会话身份" }
        LifePolicy.sources(value).forEach { ref -> check(!isDeleted(ref.kind, ref.id)) { "来源已被删除，拒绝保存旧结果" } }
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
        check(writableDatabase.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "保存失败" }
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

    @Synchronized fun saveMemory(scope: String, input: JSONObject, fromModel: Boolean = false, sourceScope: String = scope): JSONObject =
        io.deepseekharness.mobile.companion.life.LifeMemoryRepository(this).upsertMemory(scope, input, fromModel, sourceScope)

    @Synchronized fun appendMemoryHistory(scope: String, id: String, before: String?, after: String) {
        writableDatabase.execSQL("INSERT INTO memory_history(memory_id,scope,previous_value,new_value,action,created_at) VALUES(?,?,?,?,?,?)",
            arrayOf(id, scope, before, after, if (before == null) "ADD" else "UPDATE", System.currentTimeMillis()))
    }

    @Synchronized fun deleteMemory(scope: String, id: String) {
        io.deepseekharness.mobile.companion.life.LifeMemoryRepository(this).delete("memory", CompanionPolicy.scope(scope), id)
    }

    fun memoriesFor(scope: String, query: String = ""): JSONArray {
        return MemoryRanking.rank(list("memory", scope, 10000), query)
    }

    @Synchronized fun observationParts(id: String): JSONArray {
        val record = get("observation", id) ?: error("观察资料不存在")
        record.optJSONArray("parts")?.let { return it }
        val file = java.io.File(context.filesDir, "companion-media/${CompanionPolicy.digest(id)}.json")
        check(file.isFile && file.length() <= 24 * 1024 * 1024) { "原始媒体未保留或来自不含媒体的备份，请重新采集或导入" }
        return JSONArray(file.readText(Charsets.UTF_8))
    }
    @Synchronized fun deleteObservation(id: String) {
        val record = get("observation", id) ?: return
        deleteContent(setOf(LifePolicy.reference("observation", record)))
    }
    @Synchronized fun saveTemporaryObservation(id: String): JSONObject {
        val value = temporaryObservations.get(id) ?: error("临时画面已过期或会话已结束")
        value.remove("temporary"); value.remove("expiresAt")
        val saved = put("observation", value)
        temporaryObservations.remove(id)
        return saved
    }
    fun clearTemporaryObservations() { temporaryObservations.clear() }

    @Synchronized fun clearConversation(scope: String) = lifeTransaction {
        require(scope.startsWith("qq:")); CompanionPolicy.scope(scope)
        val roots = contentRecords().filter { it.second.getString("scope") == scope }.map { LifePolicy.reference(it.first, it.second) }.toSet()
        deleteContent(roots)
        get("conversation", scope)?.let { put("conversation", it.put("captureEnabled", false).put("autoReply", false).put("mode", "quiet")) }
        Unit
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

    @Synchronized fun exportData(): JSONObject = lifeTransaction {
        JSONObject().put("format", "dsh-companion-backup").put("version", 2).put("createdAt", System.currentTimeMillis()).also { out ->
            BACKUP_KINDS.forEach { kind ->
                val values = JSONArray()
                readableDatabase.rawQuery("SELECT json FROM records WHERE kind=? AND deleted=0 ORDER BY updated,id", arrayOf(kind)).use { c ->
                    while (c.moveToNext()) {
                        val value = JSONObject(c.getString(0))
                        if (!value.optBoolean("temporary")) values.put(io.deepseekharness.mobile.companion.life.LifeBackup.sanitize(kind, value))
                    }
                }
                out.put(kind, values)
            }
            out.put("tombstones", tombstones())
            out.put("revisions", JSONArray().also { all ->
                readableDatabase.rawQuery("SELECT kind,json FROM life_revisions ORDER BY kind,id,revision", null).use { c ->
                    while (c.moveToNext()) all.put(JSONObject(c.getString(1)).put("recordKind", c.getString(0)))
                }
            })
            out.put("memoryHistory", JSONArray().also { all ->
                readableDatabase.rawQuery("SELECT memory_id,scope,previous_value,new_value,action,created_at FROM memory_history ORDER BY id", null).use { c ->
                    while (c.moveToNext()) all.put(JSONObject().put("id", c.getString(0)).put("scope", c.getString(1)).put("before", c.getString(2))
                        .put("after", c.getString(3)).put("action", c.getString(4)).put("at", c.getLong(5)))
                }
            })
        }
    }

    @Synchronized fun restoreData(data: JSONObject) = lifeTransaction {
        val plan = io.deepseekharness.mobile.companion.life.LifeBackup.plan(exportData(), data)
        val history = data.optJSONArray("memoryHistory") ?: JSONArray()
        // Validate before mutation. Restore merges with newer local data; tombstones always win.
        for (i in 0 until history.length()) {
            val row = history.getJSONObject(i)
            CompanionPolicy.scope(row.getString("scope")); require(row.getString("id").length in 1..160)
        }
        val epoch = bumpContentEpoch()
        plan.tombstones.forEach { row ->
            val ref = RecordRef.parse(row)
            val values = ContentValues().apply {
                put("kind", ref.kind); put("id", ref.id); put("scope", ref.scope); put("deleted_at", row.optLong("deletedAt"))
                put("epoch", epoch); put("media_pending", if (ref.kind == "observation") 1 else 0)
            }
            writableDatabase.insertWithOnConflict("life_tombstones", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            writableDatabase.delete("records", "kind=? AND id=?", arrayOf(ref.kind, ref.id))
            writableDatabase.delete("life_revisions", "kind=? AND id=?", arrayOf(ref.kind, ref.id))
            if (ref.kind == "memory") writableDatabase.delete("memory_history", "memory_id=?", arrayOf(ref.id))
        }
        for ((kind, value) in plan.rows) {
            if (kind == "lifeState" && value.getString("id") == "contentEpoch") continue
            if (!isDeleted(kind, value.getString("id"))) put(kind, io.deepseekharness.mobile.companion.life.LifeBackup.sanitize(kind, value))
        }
        plan.revisions.forEach { row ->
            val kind = row.getString("recordKind"); row.remove("recordKind")
            recordRevision(kind, row)
        }
        for (i in 0 until history.length()) {
            val row = history.getJSONObject(i)
            val memory = get("memory", row.getString("id")) ?: continue
            check(memory.getString("scope") == row.getString("scope"))
            val found = readableDatabase.rawQuery("SELECT 1 FROM memory_history WHERE memory_id=? AND scope=? AND created_at=? AND new_value=?",
                arrayOf(row.getString("id"), row.getString("scope"), row.getLong("at").toString(), row.optString("after"))).use { it.moveToFirst() }
            if (!found) writableDatabase.execSQL("INSERT INTO memory_history(memory_id,scope,previous_value,new_value,action,created_at) VALUES(?,?,?,?,?,?)",
                arrayOf(row.getString("id"), row.getString("scope"), row.optString("before"), row.optString("after"), row.optString("action"), row.getLong("at")))
        }
        temporaryObservations.clear()
        purgeDeletedMedia()
        saveSettings(settings().put("networkEnabled", false).put("paused", true).put("oneBotEnabled", false))
        Unit
    }

    companion object {
        private val BACKUP_KINDS = listOf("persona", "personaVersion", "memory", "message", "observation", "task", "diary", "interest", "lifeEvent", "lifeState")
        private val KINDS = BACKUP_KINDS + listOf("settings", "conversation", "usage")
    }
}

fun JSONObject.removeGrant(): JSONObject { remove("grantDigest"); remove("grantExpiresAt"); return this }

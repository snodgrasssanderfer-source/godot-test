package io.deepseekharness.mobile.companion.life

import org.json.JSONArray
import org.json.JSONObject

/** Pure, persisted integer ledger. The caller must save [encode] in the same DB transaction. */
class BudgetLedger(encoded: JSONObject = JSONObject()) {
    private val state = JSONObject(encoded.toString())
    private val holds = state.optJSONObject("reservations") ?: JSONObject().also { state.put("reservations", it) }

    init {
        require(holds.length() <= 100000) { "账本记录过多，须先归档核对" }
        if (state.optBoolean("configured")) {
            require(state.getString("unit") in setOf("calls", "micros"))
            listOf("ownerLimit", "activityLimit", "warningRemaining", "unknownPriceCeiling").forEach { amount(state, it) }
            holds.keys().forEach { id ->
                identifier(id)
                val hold = holds.getJSONObject(id)
                check(hold.getString("requestId") == id)
                require(hold.getString("lane") in setOf("owner", "activity"))
                require(hold.getString("state") in setOf("reserved", "sent", "outcome_unknown", "settled", "released"))
                require(amount(hold, "maximum") > 0)
                if (hold.has("actual")) amount(hold, "actual")
            }
        } else require(holds.length() == 0) { "未配置的账本不能带有消费记录" }
    }

    @Synchronized fun encode(): JSONObject = JSONObject(state.toString())

    @Synchronized fun configure(input: JSONObject): JSONObject {
        val unit = input.getString("unit")
        require(unit in setOf("calls", "micros")) { "预算单位无效" }
        val currency = if (unit == "calls") "" else input.getString("currency")
        require(unit == "calls" || Regex("[A-Z]{3}").matches(currency)) { "币种必须是三位大写代码" }
        if (holds.length() > 0) check(unit == state.getString("unit") && currency == state.optString("currency")) { "已有账目时不能换单位或币种" }
        val ownerLimit = amount(input, "ownerLimit")
        val activityLimit = amount(input, "activityLimit")
        val warning = amount(input, "warningRemaining")
        val ceiling = amount(input, "unknownPriceCeiling")
        require(!input.optBoolean("allowUnknownPrice") || ceiling > 0) { "未知价格须设置明确上限" }
        require(unit != "calls" || !input.optBoolean("allowUnknownPrice") || ceiling == 1L)
        state.put("version", 1).put("configured", true).put("unit", unit).put("currency", currency)
            .put("ownerLimit", ownerLimit).put("activityLimit", activityLimit).put("warningRemaining", warning)
            .put("allowUnknownPrice", input.optBoolean("allowUnknownPrice")).put("unknownPriceCeiling", ceiling)
            .put("daily", input.optBoolean("daily", state.optBoolean("daily")))
        return status()
    }

    @Synchronized fun reservation(id: String): JSONObject? = holds.optJSONObject(id)?.let { JSONObject(it.toString()) }

    @Synchronized fun reserve(input: JSONObject, now: Long): JSONObject {
        check(state.optBoolean("configured")) { "请先设置主人和活动的独立额度" }
        val id = identifier(input.getString("requestId"))
        val lane = input.getString("lane")
        require(lane in setOf("owner", "activity"))
        val scope = input.getString("scope")
        require(scope.isNotBlank() && scope.length <= 180)
        val purpose = input.getString("purpose")
        require(purpose.isNotBlank() && purpose.length <= 80)
        val maximum = amount(input, "maximum")
        require(maximum > 0) { "收费请求必须有正数上限" }
        require(state.getString("unit") != "calls" || maximum == 1L) { "次数模式每次预留一次" }
        require(input.has("priceKnown")) { "必须明确是否已知价格" }
        val known = input.getBoolean("priceKnown")
        val old = holds.optJSONObject(id)
        if (old != null) {
            check(old.getString("lane") == lane && old.getString("scope") == scope && old.getString("purpose") == purpose &&
                old.getLong("maximum") == maximum && old.getBoolean("priceKnown") == known) { "请求编号已用于不同调用" }
            if (input.optBoolean("submitted")) {
                check(old.getString("state") in setOf("reserved", "sent")) { "此请求已结束或结果未知，不能再次发送" }
                val firstSend = old.getString("state") == "reserved"
                if (firstSend) old.put("state", "sent").put("submittedAt", now)
                return result(old, duplicate = true).put("sendAuthorized", firstSend)
            }
            return result(old, duplicate = true)
        }
        require(!input.optBoolean("submitted")) { "必须先持久化预留再标记发送" }
        if (!known) check(state.optBoolean("allowUnknownPrice") && maximum <= state.getLong("unknownPriceCeiling")) { "价格未知，且未获得对应的保守上限" }
        if (state.optBoolean("daily")) {
            // Daily pools only need recent history; finished entries older than a few days can go.
            holds.keys().asSequence().toList().forEach { key ->
                val h = holds.getJSONObject(key)
                if (h.getString("state") in setOf("settled", "released") && h.optLong("createdAt") < now - 3 * 86_400_000L) holds.remove(key)
            }
        }
        val current = status(now)
        if (lane == "activity") check(!current.getBoolean("stopActivity")) { "活动额度将尽或已耗尽，已停止新收费活动" }
        if (lane == "owner") check(!current.getBoolean("stopOwner")) { "主人额度已耗尽或出现超额账单，请核对额度" }
        check(current.getJSONObject(lane).getLong("remaining") >= maximum) { "当前通道额度不足，包含在途预留" }
        check(holds.length() < 100000) { "账本记录已满，须先归档核对" }
        val hold = JSONObject().put("requestId", id).put("scope", scope).put("lane", lane).put("purpose", purpose)
            .put("maximum", maximum).put("priceKnown", known).put("state", "reserved").put("createdAt", now)
        holds.put(id, hold)
        return result(hold, duplicate = false)
    }

    @Synchronized fun settle(input: JSONObject, now: Long): JSONObject {
        val hold = holds.optJSONObject(input.getString("requestId")) ?: error("预留不存在")
        val before = hold.getString("state")
        if (!input.getBoolean("actualKnown")) {
            check(before != "released" && before != "settled") { "已完成结算，不能退回未知状态" }
            hold.put("state", "outcome_unknown").put("updatedAt", now)
            return result(hold, before == "outcome_unknown")
        }
        val actual = amount(input, "actual")
        require(state.getString("unit") != "calls" || actual <= 1) { "次数结算只能是零或一" }
        check(before != "released" || actual == 0L) { "未发送预留已释放，不能重复结算" }
        if (before in setOf("settled", "released")) {
            check(hold.optLong("actual") == actual) { "结算结果冲突，需要核对账单" }
            return result(hold, duplicate = true)
        }
        hold.put("state", "settled").put("actual", actual).put("settledAt", now)
            .put("overrun", actual > hold.getLong("maximum"))
        return result(hold, duplicate = false)
    }

    @Synchronized fun release(id: String, now: Long): JSONObject {
        val hold = holds.optJSONObject(id) ?: error("预留不存在")
        val before = hold.getString("state")
        when (before) {
            "reserved" -> hold.put("state", "released").put("actual", 0).put("settledAt", now)
            "sent" -> hold.put("state", "outcome_unknown").put("updatedAt", now)
        }
        return result(hold, before !in setOf("reserved", "sent"))
    }

    /** After process death only unsent reservations can release; sent work stays charged conservatively. */
    @Synchronized fun recover(now: Long) {
        holds.keys().asSequence().toList().forEach { id -> release(id, now) }
    }

    /** With daily pools, finished spending counts only for the calendar day of [now]; anything still in flight always counts. */
    @Synchronized fun status(now: Long = System.currentTimeMillis()): JSONObject {
        val configured = state.optBoolean("configured")
        val daily = state.optBoolean("daily")
        val today = day(now)
        val result = JSONObject().put("configured", configured).put("unit", state.optString("unit", "calls"))
            .put("currency", state.optString("currency")).put("monetaryGuarantee", false)
            .put("unknownPrice", JSONObject().put("allowed", state.optBoolean("allowUnknownPrice"))
                .put("ceiling", state.optLong("unknownPriceCeiling")).put("notice", "未知价格只限制授权上限或次数，客户端取消不保证服务端退款"))
        for (lane in listOf("owner", "activity")) {
            var spent = 0L
            var held = 0L
            var unknown = 0
            var overrun = false
            holds.keys().forEach { id ->
                val h = holds.getJSONObject(id)
                val counts = !daily || day(h.optLong("createdAt")) == today || h.getString("state") in setOf("reserved", "sent")
                if (h.getString("lane") == lane && counts) {
                    if (h.getString("state") == "settled") spent = Math.addExact(spent, h.getLong("actual"))
                    if (h.getString("state") in setOf("reserved", "sent", "outcome_unknown")) held = Math.addExact(held, h.getLong("maximum"))
                    if (h.getString("state") == "outcome_unknown") unknown++
                    if (h.optBoolean("overrun")) overrun = true
                }
            }
            val limit = state.optLong(lane + "Limit")
            result.put(lane, JSONObject().put("limit", limit).put("spent", spent).put("held", held)
                .put("remaining", limit - spent - held).put("unknownRequests", unknown).put("overrun", overrun))
        }
        val activity = result.getJSONObject("activity")
        result.put("warningRemaining", state.optLong("warningRemaining")).put("daily", daily)
        result.put("stopActivity", !configured || activity.optBoolean("overrun") ||
            activity.getLong("remaining") <= state.optLong("warningRemaining"))
        result.put("stopOwner", !configured || result.getJSONObject("owner").optBoolean("overrun") || result.getJSONObject("owner").getLong("remaining") <= 0)
        return result
    }

    private fun day(epochMs: Long) = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

    @Synchronized fun reservations(): JSONArray = JSONArray().also { out -> holds.keys().forEach { out.put(JSONObject(holds.getJSONObject(it).toString())) } }

    private fun result(hold: JSONObject, duplicate: Boolean) = JSONObject().put("reservationId", hold.getString("requestId"))
        .put("state", hold.getString("state")).put("duplicate", duplicate).put("sendAuthorized", false).put("reservation", JSONObject(hold.toString())).put("budgets", status())

    companion object {
        const val MAX_AMOUNT = 1_000_000_000_000L
        fun identifier(value: String): String {
            require(Regex("[A-Za-z0-9_.:-]{1,160}").matches(value)) { "请求编号格式无效" }
            return value
        }
        fun amount(value: JSONObject, key: String): Long {
            val raw = value.get(key)
            require(raw is Number && raw !is Double && raw !is Float) { "预算须用整数，不能传浮点数" }
            val n = raw.toString().toLongOrNull() ?: error("预算值超出整数范围")
            require(n in 0..MAX_AMOUNT) { "预算值超出范围" }
            return n
        }

        /** Restoring an old backup cannot lower consumption, refund unknown holds or enable a fresh grant. */
        fun merge(current: JSONObject, incoming: JSONObject): JSONObject {
            if (!incoming.optBoolean("configured")) return JSONObject(current.toString())
            if (!current.optBoolean("configured")) return BudgetLedger(incoming).apply { recover(System.currentTimeMillis()) }.encode()
            check(current.getString("unit") == incoming.getString("unit") && current.optString("currency") == incoming.optString("currency")) { "备份预算单位不同，不能合并" }
            val result = JSONObject(current.toString())
            val local = result.optJSONObject("reservations") ?: JSONObject().also { result.put("reservations", it) }
            val restored = incoming.optJSONObject("reservations") ?: JSONObject()
            restored.keys().forEach { id ->
                val remote = restored.getJSONObject(id)
                val old = local.optJSONObject(id)
                if (old == null) {
                    val h = JSONObject(remote.toString())
                    if (h.optString("state") == "reserved") h.put("state", "released").put("actual", 0)
                    if (h.optString("state") == "sent") h.put("state", "outcome_unknown")
                    local.put(id, h)
                } else {
                    check(listOf("lane", "scope", "purpose", "maximum", "priceKnown").all { old.get(it) == remote.get(it) }) { "备份请求身份冲突" }
                    if (remote.optString("state") == "settled") {
                        val amount = maxOf(old.optLong("actual"), remote.getLong("actual"))
                        old.put("state", "settled").put("actual", amount).put("overrun", amount > old.getLong("maximum"))
                    } else if (old.optString("state") !in setOf("settled", "outcome_unknown", "sent") && remote.optString("state") in setOf("sent", "outcome_unknown")) {
                        old.put("state", "outcome_unknown")
                    }
                }
            }
            return result
        }
    }
}

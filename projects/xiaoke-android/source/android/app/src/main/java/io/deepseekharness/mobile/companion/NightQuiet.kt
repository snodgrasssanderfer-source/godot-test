package io.deepseekharness.mobile.companion

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/** Night quiet: entered when the phone is locked from the start hour (default 23:00) until 05:00, and kept
 * until the first unlock on a later calendar day. While active: no pushes, no QQ group speech, no Bilibili browsing.
 */
object NightQuiet {
    private const val MORNING_HOUR = 5

    fun active(store: CompanionStore): Boolean = store.lifeState("nightQuiet").optString("night").isNotBlank()

    fun evaluate(context: Context, store: CompanionStore, now: LocalDateTime = LocalDateTime.now()) {
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !context.getSystemService(PowerManager::class.java).isInteractive
        val saved = store.lifeState("nightQuiet")
        val next = next(saved.optString("night"), locked, now, store.settings().optInt("nightQuietStartHour", 23).coerceIn(MORNING_HOUR, 23))
        if (next != saved.optString("night")) store.saveLifeState("nightQuiet", JSONObject().put("night", next).put("changedAt", System.currentTimeMillis()))
    }

    /** Returns the ISO date of the evening the quiet night began, or "" when not quiet. */
    fun next(night: String, locked: Boolean, now: LocalDateTime, startHour: Int): String = when {
        night.isBlank() && locked && (now.hour >= startHour || now.hour < MORNING_HOUR) ->
            (if (now.hour < MORNING_HOUR) now.toLocalDate().minusDays(1) else now.toLocalDate()).toString()
        night.isNotBlank() && !locked && now.toLocalDate().isAfter(LocalDate.parse(night)) -> ""
        else -> night
    }
}

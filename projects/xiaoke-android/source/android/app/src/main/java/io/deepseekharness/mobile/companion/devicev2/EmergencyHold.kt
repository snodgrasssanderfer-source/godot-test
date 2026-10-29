package io.deepseekharness.mobile.companion.devicev2

import android.content.Context

/** Five-second hold on the floating ball. Locks taps, input and outbound sends; voice and text chat continue.
 * Everything resumes when the owner says "继续" in their own chat or by voice (never from a group), or presses resume on the ball.
 */
object EmergencyHold {
    // "继续" / "继续吧" / "你继续输文件名导出吧": a short message that starts by telling her to go on.
    private val continueWord = Regex("(好[，, ]?)?(你)?继续.{0,24}")
    @Volatile private var preferences: android.content.SharedPreferences? = null
    @Volatile var ordinaryHeld = false; private set
    @Volatile private var sensitive = false
    val sensitiveHeld get() = sensitive
    val anyHeld get() = ordinaryHeld || sensitive

    /** The sensitive hold survives process death; ordinary input is revoked by process death anyway. */
    fun attach(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("companion-emergency-hold", Context.MODE_PRIVATE)
        preferences = prefs; sensitive = sensitive || prefs.getBoolean("sensitive", false)
    }
    fun engage() { ordinaryHeld = true; setSensitive(true) }
    fun releaseOrdinary() { ordinaryHeld = false }
    fun releaseAll() { ordinaryHeld = false; setSensitive(false) }
    fun isContinue(text: String) = continueWord.matches(text.trim())
    private fun setSensitive(value: Boolean) { sensitive = value; preferences?.edit()?.putBoolean("sensitive", value)?.apply() }
}

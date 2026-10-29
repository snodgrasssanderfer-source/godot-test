package io.deepseekharness.mobile.companion.devicev2

import android.content.Context

/** Five-second hold on the floating ball. Locks taps and input only; voice and text chat continue.
 * Ordinary input resumes when the owner says "继续"; credentials, file deletion, outbound sends and
 * payment stay held until the owner presses the recovery button on the ball.
 */
object EmergencyHold {
    private val continueWord = Regex("继续[。！!.～~吧呀啊]*")
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

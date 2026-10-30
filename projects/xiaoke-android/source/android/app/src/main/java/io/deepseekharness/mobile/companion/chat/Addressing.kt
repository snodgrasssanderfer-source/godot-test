package io.deepseekharness.mobile.companion.chat

/** Whether a group message is meant for her, beyond an @ or her name. Decides who skips the reply-frequency gate. */
object Addressing {
    const val FOLLOW_UP_MS = 5 * 60_000L
    const val MEMBER_FOLLOW_UP_MS = 2 * 60_000L

    private val request = Regex("^(帮我|给我|戳|来|说|讲|唱|查|看看|告诉|陪|别|快|再|去|你(?!们))")
    private val question = Regex("为什么|为啥|怎么|是不是|对不对|吗[？?。!！~～]*$|呢[？?。!！~～]*$|[？?]\\s*$")
    private val toYou = Regex("你(?!们)")

    /**
     * The owner in a group: anything that could be for her gets an answer. [direct] = @her, her name, or a quote of her
     * message; [elsewhere] = an @ of, or a quote of, someone else (then the normal gate decides).
     */
    fun ownerToHer(words: String, direct: Boolean, elsewhere: Boolean, sinceHerMs: Long, humansSinceHer: Int): Boolean {
        if (direct) return true
        if (elsewhere) return false
        if (sinceHerMs in 0..FOLLOW_UP_MS && humansSinceHer <= 4) return true // carrying on the exchange with her
        val text = words.trim()
        return request.containsMatchIn(text) || question.containsMatchIn(text) || toYou.containsMatchIn(text)
    }

    /** Anyone picking up what she just said: shortly after her message, and before the room has moved on. */
    fun followsHer(sinceHerMs: Long, humansSinceHer: Int) = sinceHerMs in 0..MEMBER_FOLLOW_UP_MS && humansSinceHer <= 3

    /** A likelihood pushed up when someone answers her, so the gate usually lets her reply. */
    fun boosted(p: Double) = (p + 0.4).coerceAtMost(1.0)
}

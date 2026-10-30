package io.deepseekharness.mobile.companion.memory

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * "昨天" is useless a week later. Relative times in a memory are pinned to dates counted from the day the
 * words were said (mem0's ADDITIVE_EXTRACTION_PROMPT rule). The extraction prompt asks the model to do this;
 * this pass catches what it leaves behind. Already-dated text is left alone.
 */
object TimeAnchor {
    private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val anchored = Regex("\\d{4}-\\d{2}-\\d{2}|\\d{4}年")

    fun date(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    fun format(date: LocalDate): String = date.format(day)
    fun parse(text: String?): LocalDate? = text?.trim()?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    fun startMs(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun resolve(text: String, said: LocalDate): String {
        val monday = said.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        fun week(start: LocalDate) = "${format(start)} 那周"
        fun month(d: LocalDate) = "${d.year}年${d.monthValue}月"
        val rules = listOf(
            "大前天" to format(said.minusDays(3)), "前天" to format(said.minusDays(2)), "昨天" to format(said.minusDays(1)),
            "昨晚" to "${format(said.minusDays(1))}晚上", "今晚" to "${format(said)}晚上", "今天" to format(said),
            "明天" to format(said.plusDays(1)), "后天" to format(said.plusDays(2)), "大后天" to format(said.plusDays(3)),
            "上上周" to week(monday.minusWeeks(2)), "上周" to week(monday.minusWeeks(1)), "上个星期" to week(monday.minusWeeks(1)),
            "这周" to week(monday), "本周" to week(monday), "下周" to week(monday.plusWeeks(1)), "下个星期" to week(monday.plusWeeks(1)),
            "上个月" to month(said.minusMonths(1)), "这个月" to month(said), "本月" to month(said), "下个月" to month(said.plusMonths(1)),
            "前年" to "${said.year - 2}年", "去年" to "${said.year - 1}年", "今年" to "${said.year}年", "明年" to "${said.year + 1}年",
        )
        // Longest words first so "大前天" is not read as "前天".
        val pattern = Regex(rules.map { it.first }.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) })
        val table = rules.toMap()
        return pattern.replace(text) { m ->
            // Words right next to an explicit date are already anchored ("2026年…今年" stays readable).
            val near = text.substring(maxOf(0, m.range.first - 12), minOf(text.length, m.range.last + 13))
            if (anchored.containsMatchIn(near)) m.value else table.getValue(m.value)
        }
    }
}

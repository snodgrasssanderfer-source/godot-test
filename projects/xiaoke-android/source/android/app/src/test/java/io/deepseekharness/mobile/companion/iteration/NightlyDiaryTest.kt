package io.deepseekharness.mobile.companion.iteration

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class NightlyDiaryTest {
    @Test fun oneEntryPerEveningWrittenBetweenTenAndFive() {
        assertNull(NightlyDiary.due(LocalDateTime.of(2026, 10, 29, 21, 59), ""))
        assertEquals("2026-10-29", NightlyDiary.due(LocalDateTime.of(2026, 10, 29, 22, 0), ""))
        // After midnight the entry still belongs to the evening before.
        assertEquals("2026-10-29", NightlyDiary.due(LocalDateTime.of(2026, 10, 30, 1, 30), ""))
        assertNull(NightlyDiary.due(LocalDateTime.of(2026, 10, 30, 1, 30), "2026-10-29"))
        assertNull(NightlyDiary.due(LocalDateTime.of(2026, 10, 30, 6, 0), ""))
        // The phone slept through last night: written the next day.
        assertEquals("2026-10-29", NightlyDiary.due(LocalDateTime.of(2026, 10, 30, 14, 0), "2026-10-28"))
        assertNull(NightlyDiary.due(LocalDateTime.of(2026, 10, 30, 14, 0), "2026-10-29"))
    }
}

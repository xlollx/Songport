package com.xlollx.songport.sync

import com.xlollx.songport.model.RadioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class RadioPlanTest {
    private val mornings = RadioSource("A", "http://a", days = listOf(1, 2, 3, 4, 5), startMinute = 8 * 60, endMinute = 10 * 60)
    private val nights = RadioSource("B", "http://b", days = listOf(5), startMinute = 22 * 60, endMinute = 2 * 60)
    private val monday7 = LocalDateTime.of(2026, 11, 2, 7, 0)

    @Test fun theAlarmRingsAtTheEarliestWindow() {
        assertEquals(monday7.withHour(8), RadioPlan.nextAlarm(listOf(nights, mornings), monday7, anyOpen = false, soon = false))
        assertEquals(LocalDateTime.of(2026, 11, 6, 22, 0), RadioPlan.nextAlarm(listOf(nights), monday7, anyOpen = false, soon = false))
    }

    @Test fun withAWindowOpenTheWatchdogComesFirst() {
        val monday9 = monday7.withHour(9)
        assertEquals(monday9.plusMinutes(15), RadioPlan.nextAlarm(listOf(mornings), monday9, anyOpen = true, soon = false))
        // After a reboot: listening starts from an alarm a minute later.
        assertEquals(monday9.plusMinutes(1), RadioPlan.nextAlarm(listOf(mornings), monday9, anyOpen = true, soon = true))
        // The next window, when it is sooner than the watchdog.
        assertEquals(monday9.withMinute(50).plusMinutes(10), RadioPlan.nextAlarm(listOf(mornings, mornings.copy(startMinute = 10 * 60)), monday9.withMinute(50), anyOpen = true, soon = false))
    }

    @Test fun noRadioNoAlarm() {
        assertNull(RadioPlan.nextAlarm(emptyList(), monday7, anyOpen = false, soon = false))
        assertNull(RadioPlan.nextAlarm(listOf(mornings.copy(days = emptyList())), monday7, anyOpen = false, soon = false))
    }
}

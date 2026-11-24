package com.brucezhang.bydautosign

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DispatchRecoveryPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone)
    private fun ms(day: Int, hour: Int, minute: Int = 0) =
        at(day, hour, minute).toInstant().toEpochMilli()

    @Test fun missedFirstSlotWithoutAnyPersistedAlarmIsRecoverable() {
        assertTrue(DispatchRecoveryPolicy.isDue("07:00", at(24, 9), false, 0, ms(23, 8)))
        assertFalse(DispatchRecoveryPolicy.isDue("07:00", at(24, 6), false, 0, 0))
        assertFalse(DispatchRecoveryPolicy.isDue("07:00", at(24, 9), true, 0, 0))
    }

    @Test fun retryMustBeDueAndSameDay() {
        assertFalse(DispatchRecoveryPolicy.isDue("07:00", at(24, 9), false, ms(24, 10), ms(24, 8)))
        assertTrue(DispatchRecoveryPolicy.isDue("07:00", at(24, 10), false, ms(24, 10), ms(24, 8)))
        assertTrue(DispatchRecoveryPolicy.isDue("07:00", at(24, 9), false, ms(23, 10), ms(23, 8)))
        assertFalse(DispatchRecoveryPolicy.isDue("07:00", at(24, 23, 59), false, 0, ms(24, 23, 30)))
    }

    @Test fun delayedAndSupersededTicketsCannotStart() {
        val current = ms(24, 9)
        assertTrue(DispatchRecoveryPolicy.ticketValid("2026-11-24", current, ms(24, 10), current, "07:00", zone))
        assertFalse(DispatchRecoveryPolicy.ticketValid("2026-11-24", current, ms(24, 10), ms(24, 10), "07:00", zone))
        assertFalse(DispatchRecoveryPolicy.ticketValid("2026-11-24", current, ms(25, 9), current, "07:00", zone))
        assertFalse(DispatchRecoveryPolicy.ticketValid("2026-11-24", 0, current, 0, "07:00", zone))
        assertFalse(DispatchRecoveryPolicy.ticketValid("2026-11-24", current, current, current, "12:00", zone))
    }
}

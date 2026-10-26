package com.brucezhang.bydautosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SignDayStateTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone)
            .toInstant().toEpochMilli()

    private fun allowed(
        now: Long = at(27, 0, 43),
        enabled: Boolean = true,
        signed: Boolean = false,
        time: String = "00:10",
        phase: SignDayPhase = SignDayPhase.WAITING_AUTH,
        lastRun: Long = at(27, 0, 41),
        startedAt: Long = at(27, 0, 41),
        lastOpportunityAt: Long = 0L
    ) = SignOpportunityPolicy.shouldDispatch(
        enabled, signed, time, now, phase, lastRun, startedAt,
        lastOpportunityAt, zone
    )

    @Test fun defaultTimeIsHardEarliestBoundaryForAutomaticEvents() {
        assertFalse(allowed(now = at(27, 0, 9)))
        assertTrue(allowed(now = at(27, 0, 10), lastRun = 0L))
        assertTrue(allowed(now = at(27, 2, 0)))
        assertFalse(allowed(time = "invalid"))
        assertFalse(allowed(enabled = false))
        assertFalse(allowed(signed = true))
    }

    @Test fun openingWeChatAfterNightFailureImmediatelyAllowsCompensation() {
        assertTrue(allowed())
        assertFalse(allowed(now = at(27, 0, 41) + 10_000L))
        assertTrue(allowed(now = at(27, 0, 41) + 21_000L))
    }

    @Test fun cooldownPreventsDuplicateForegroundAndBinderNotifications() {
        val previous = at(27, 0, 43)
        assertFalse(allowed(now = previous + 12_000L, lastOpportunityAt = previous))
        assertTrue(allowed(now = previous + 21_000L, lastOpportunityAt = previous))
        assertTrue(allowed(now = at(28, 0, 43), lastOpportunityAt = previous,
            lastRun = previous))
    }

    @Test fun staleRunningWorkCanBeRecoveredOnlyAfterLeaseExpires() {
        val started = at(27, 0, 39)
        assertFalse(allowed(now = started + 50_000L,
            phase = SignDayPhase.RUNNING, startedAt = started, lastRun = 0L))
        assertTrue(allowed(now = started + 4 * 60_000L,
            phase = SignDayPhase.RUNNING, startedAt = started, lastRun = 0L))
    }

    @Test fun phaseIsDerivedFromConfirmedResultNotOnlyFromAlarm() {
        assertEquals(SignDayPhase.COMPLETE, SignOpportunityPolicy.phaseAfter(true, false))
        assertEquals(SignDayPhase.WAITING_AUTH,
            SignOpportunityPolicy.phaseAfter(false, true))
        assertEquals(SignDayPhase.RETRY_PENDING,
            SignOpportunityPolicy.phaseAfter(false, false))
    }

    @Test fun newLocalDayDoesNotInheritPreviousDaysCooldownOrOutcome() {
        assertTrue(allowed(now = at(28, 0, 11), lastRun = at(27, 23, 59),
            lastOpportunityAt = at(27, 23, 59)))
        assertFalse(allowed(now = at(28, 0, 9), lastRun = at(27, 23, 59)))
    }
}

package com.brucezhang.bydautosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SignRecoveryPolicyTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private fun at(day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun missedAlarmHasFiveMinuteGraceAndHandlesClockRollback() {
        val alarm = at(24, 7, 1)
        assertFalse(SignRecoveryPolicy.planOverdue(0, at(24, 9, 0)))
        assertFalse(SignRecoveryPolicy.planOverdue(alarm, at(24, 7, 5)))
        assertTrue(SignRecoveryPolicy.planOverdue(alarm, at(24, 7, 6)))
        assertFalse(SignRecoveryPolicy.planOverdue(alarm, at(24, 6, 0)))
    }

    @Test fun permissionRevocationRequiresNewAlarmEvenWhenPlanIsInFuture() {
        assertTrue(SignRecoveryPolicy.permissionChanged(true, false))
        assertTrue(SignRecoveryPolicy.permissionChanged(false, true))
        assertFalse(SignRecoveryPolicy.permissionChanged(true, true))
        assertFalse(SignRecoveryPolicy.permissionChanged(false, false))
    }

    @Test fun compensationRequiresFirstSlotAndCooldown() {
        val morning = at(24, 7, 1)
        assertFalse(SignRecoveryPolicy.shouldCompensate(false, false, at(24, 8, 0), morning, 0))
        assertFalse(SignRecoveryPolicy.shouldCompensate(true, true, at(24, 8, 0), morning, 0))
        assertFalse(SignRecoveryPolicy.shouldCompensate(true, false, at(24, 7, 0), morning, 0))
        assertTrue(SignRecoveryPolicy.shouldCompensate(true, false, at(24, 7, 1), morning, 0))
        assertFalse(SignRecoveryPolicy.shouldCompensate(
            true, false, at(24, 7, 15), morning, at(24, 7, 2)))
        assertTrue(SignRecoveryPolicy.shouldCompensate(
            true, false, at(24, 7, 32), morning, at(24, 7, 2)))
        // A previous-day attempt must never prevent today's 00:01 recovery.
        assertTrue(SignRecoveryPolicy.shouldCompensate(
            true, false, at(25, 0, 1), at(25, 0, 1), at(24, 23, 59), zone))
        // A restored phone clock may be earlier than the last recorded attempt.
        assertTrue(SignRecoveryPolicy.shouldCompensate(
            true, false, at(24, 7, 1), morning, at(24, 23, 59)))
    }

    @Test fun nonrecoverableAuthErrorsBlockUntilReauthorized() {
        val blocked = at(24, 11, 0)
        for (kind in listOf(SignFailureKind.NO_SESSION, SignFailureKind.SESSION_UNREADABLE,
            SignFailureKind.AUTH_EXPIRED)) {
            assertEquals(AutomaticBlockMode.UNTIL_REAUTHORIZED, SignRecoveryPolicy.blockMode(kind))
            assertTrue(SignRecoveryPolicy.isBlocked(kind, blocked, at(25, 12, 0), zone))
        }
    }

    @Test fun protocolAndTlsErrorsOnlyBlockToday() {
        val blocked = at(24, 11, 0)
        for (kind in listOf(SignFailureKind.TLS, SignFailureKind.HTTP_CLIENT,
            SignFailureKind.PROTOCOL, SignFailureKind.BUSINESS)) {
            assertEquals(AutomaticBlockMode.TODAY, SignRecoveryPolicy.blockMode(kind))
            assertTrue(SignRecoveryPolicy.isBlocked(kind, blocked, at(24, 23, 59), zone))
            assertFalse(SignRecoveryPolicy.isBlocked(kind, blocked, at(25, 0, 1), zone))
        }
    }

    @Test fun transientErrorsStayRetryable() {
        for (kind in listOf(SignFailureKind.NETWORK, SignFailureKind.TIMEOUT,
            SignFailureKind.SERVER, SignFailureKind.NONE)) {
            assertEquals(AutomaticBlockMode.NONE, SignRecoveryPolicy.blockMode(kind))
            assertFalse(SignRecoveryPolicy.isBlocked(kind, at(24, 10, 0), at(24, 11, 0), zone))
        }
    }

    @Test fun midnightSignSuccessCreditsCompletionDayNotPreviousDay() {
        val start = at(24, 23, 59)
        val end = at(25, 0, 1)
        assertTrue(SignDatePolicy.crossesLocalDay(start, end, zone))
        assertEquals("2026-10-25", SignDatePolicy.completedLocalDate(end, zone))
        assertTrue(SignDatePolicy.wasSignedOn("2026-10-25", end, zone))
        assertFalse(SignDatePolicy.wasSignedOn("2026-10-24", end, zone))
        assertFalse(SignDatePolicy.wasSignedOn("2026-10-25", start, zone))
    }

    @Test fun signedDateUsesLocalZoneNotUtcDay() {
        val tokyoMidnight = at(25, 0, 1)
        assertEquals("2026-10-25", SignDatePolicy.completedLocalDate(tokyoMidnight, zone))
        assertEquals("2026-10-24",
            SignDatePolicy.completedLocalDate(tokyoMidnight, ZoneId.of("UTC")))
    }
}

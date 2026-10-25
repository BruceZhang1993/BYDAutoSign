package com.brucezhang.bydautosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Synthetic credentials only: never call WeChat or BYD during tests. */
class SessionRefreshTest {
    private val zone = ZoneId.systemDefault()
    private fun at(day: Int, hour: Int = 8): Long =
        ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun success() = SignOutcome(true, "签到成功", days = 5)
    private fun staleError() = SignOutcome(false, "签到失败 · ret=50010",
        failure = SignFailureKind.BUSINESS, businessCode = 50010)

    @Test fun newLocalDayRequiresRefreshEvenWithCachedSession() {
        assertTrue(AutoSessionRefreshPolicy.needsRefresh(at(25), at(26)))
        assertTrue(AutoSessionRefreshPolicy.needsRefresh(0L, at(26)))
        assertFalse(AutoSessionRefreshPolicy.needsRefresh(at(26, 1), at(26, 23)))
    }

    @Test fun freshSessionSignsOnceWithoutRefreshing() {
        var refreshCalls = 0
        val signed = mutableListOf<String>()
        val result = FreshSessionSigner(
            refresh = { refreshCalls++; "new" },
            sign = { signed.add(it); success() }
        ).execute("cached", at(26), at(26, 9))
        assertTrue(result.success)
        assertEquals(0, refreshCalls)
        assertEquals(listOf("cached"), signed)
    }

    @Test fun firstSignOfDayUsesRefreshedSession() {
        var calls = 0
        val signed = mutableListOf<String>()
        val result = FreshSessionSigner(
            refresh = { calls++; "new" },
            sign = { signed.add(it); success() }
        ).execute("old", at(25), at(26))
        assertTrue(result.success)
        assertEquals(1, calls)
        assertEquals(listOf("new"), signed)
    }

    @Test fun refreshFailureCanFallBackToStillValidCachedSession() {
        val signed = mutableListOf<String>()
        val result = FreshSessionSigner(
            refresh = { null },
            sign = { signed.add(it); success() }
        ).execute("old", at(25), at(26))
        assertTrue(result.success)
        assertEquals(listOf("old"), signed)
    }

    @Test fun ret50010WithUnavailableHookStaysRetryableAndDoesNotRepeatRefresh() {
        var calls = 0
        val result = FreshSessionSigner(
            refresh = { calls++; null },
            sign = { staleError() }
        ).execute("old", at(25), at(26))
        assertFalse(result.success)
        assertTrue(result.shouldRetry)
        assertEquals(50010, result.businessCode)
        assertEquals(1, calls)
        assertTrue(result.message.contains("自动刷新授权失败"))
    }

    @Test fun suspectedExpiryRefreshesAndRetriesSignOnlyOnce() {
        var calls = 0
        val signed = mutableListOf<String>()
        val result = FreshSessionSigner(
            refresh = { calls++; "new" },
            sign = { session ->
                signed.add(session)
                if (session == "old") staleError() else success()
            }
        ).execute("old", at(26), at(26, 9))
        assertTrue(result.success)
        assertEquals(1, calls)
        assertEquals(listOf("old", "new"), signed)
    }

    @Test fun badResultAfterSuccessfulRefreshNeverLoops() {
        var calls = 0
        var signs = 0
        val result = FreshSessionSigner(
            refresh = { calls++; "new" },
            sign = { signs++; staleError() }
        ).execute("old", at(25), at(26))
        assertFalse(result.success)
        assertEquals(1, calls)
        assertEquals(1, signs)
    }

    @Test fun missingOrUnreadableSessionAttemptsRefreshBeforeFailing() {
        var calls = 0
        val result = FreshSessionSigner(
            refresh = { calls++; null },
            sign = { error("must not sign without session") }
        ).execute(null, 0L, at(26), SignFailureKind.SESSION_UNREADABLE)
        assertEquals(SignFailureKind.SESSION_UNREADABLE, result.failure)
        assertTrue(result.shouldRetry)
        assertEquals(1, calls)
    }

    @Test fun expiredTodayWithoutRefreshRecordsRecoverableFailure() {
        var calls = 0
        val result = FreshSessionSigner(
            refresh = { calls++; null },
            sign = { SignOutcome(false, "会话过期",
                authorizationExpired = true, failure = SignFailureKind.AUTH_EXPIRED) }
        ).execute("old", at(26), at(26, 9))
        assertFalse(result.success)
        assertTrue(result.shouldRetry)
        assertEquals(1, calls)
    }
}

package com.brucezhang.bydautosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DailySchedulePolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun now(day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone)

    @Test fun oneDailyTimeAndLegacyMigration() {
        assertEquals("06:15", ScheduleTimes.normalize("06:15"))
        assertNull(ScheduleTimes.normalize("06:15,13:01"))
        assertNull(ScheduleTimes.normalize("25:00"))
        assertEquals("00:01", ScheduleTimes.migrate("18:01,00:01,13:01,06:15"))
        assertEquals("07:01", ScheduleTimes.migrate("invalid"))
    }

    @Test fun dailyAnchorRunsAtSelectedTimeThenTomorrowAfterItHasPassed() {
        val today = DailySchedulePolicy.next("06:15", now(25, 6, 0), false, 0L)
        assertEquals(now(25, 6, 15).toInstant().toEpochMilli(), today.at)
        assertFalse(today.retry)
        val tomorrow = DailySchedulePolicy.next("06:15", now(25, 14, 46), false, 0L)
        assertEquals(now(26, 6, 15).toInstant().toEpochMilli(), tomorrow.at)
        assertFalse(tomorrow.retry)
    }

    @Test fun successStopsTodayAndStartsTomorrowAtFixedTime() {
        val plan = DailySchedulePolicy.next("13:01", now(25, 9, 0), true,
            now(25, 10, 0).toInstant().toEpochMilli())
        assertEquals(now(26, 13, 1).toInstant().toEpochMilli(), plan.at)
        assertFalse(plan.retry)
    }

    @Test fun failureRetriesOneHourAfterCompletion() {
        val failedAt = now(25, 13, 8)
        val retry = DailySchedulePolicy.retryAfter(failedAt)
        assertEquals(now(25, 14, 8).toInstant().toEpochMilli(), retry)
        val plan = DailySchedulePolicy.next("06:15", now(25, 13, 9), false, retry)
        assertEquals(retry, plan.at)
        assertTrue(plan.retry)
    }

    @Test fun noRetryAfterMidnightAndStaleRetryNeverCarriesToNewDay() {
        assertEquals(0L, DailySchedulePolicy.retryAfter(now(25, 23, 30)))
        val oldRetry = now(25, 14, 0).toInstant().toEpochMilli()
        val plan = DailySchedulePolicy.next("06:15", now(26, 5, 0), false, oldRetry)
        assertEquals(now(26, 6, 15).toInstant().toEpochMilli(), plan.at)
        assertFalse(plan.retry)
    }

    @Test fun midnightAndUtcOffsetsUseLocalDates() {
        val instant = now(25, 23, 1)
        assertEquals(0L, DailySchedulePolicy.retryAfter(instant))
        val tz = ZoneId.of("America/New_York")
        val last = ZonedDateTime.of(2026, 10, 25, 1, 15, 0, 0, tz)
        assertTrue(DailySchedulePolicy.retryAfter(last) > last.toInstant().toEpochMilli())
    }
}

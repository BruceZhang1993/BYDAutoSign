package com.brucezhang.bydautosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ScheduleEventHistoryTest {
    @Test fun eachClockSlotUsesDistinctStableAlarmRequestCode() {
        assertEquals(20001, SignScheduler.slotRequestCode("00:01"))
        assertEquals(20375, SignScheduler.slotRequestCode("06:15"))
        assertEquals(20781, SignScheduler.slotRequestCode("13:01"))
        assertEquals(21081, SignScheduler.slotRequestCode("18:01"))
        assertFalse(SignScheduler.slotRequestCode("06:15") ==
            SignScheduler.slotRequestCode("13:01"))
    }

    @Test fun eachClockSlotSchedulesNextDayWithoutWaitingForEarlierSlotToFire() {
        val now = LocalDate.of(2026, 10, 25).atTime(14, 46)
            .atZone(ZoneId.of("Asia/Shanghai"))
        val last = ScheduleTimes.nextForSlot("18:01", now)
        val missed = ScheduleTimes.nextForSlot("06:15", now)
        assertEquals(now.toLocalDate(), last.toLocalDate())
        assertEquals(now.toLocalDate().plusDays(1), missed.toLocalDate())
        assertEquals("18:01", last.toLocalTime().toString())
        assertEquals("06:15", missed.toLocalTime().toString())
    }

    @Test fun missedWakePersistsPlannedTimeAndSlotWithoutBreakingOldRecords() {
        val missed = ScheduleEventEntry(3000L, ScheduleEventType.MISSED_WAKE,
            ScheduleEventSource.COMPENSATION, plannedAt = 2000L)
        val alarm = ScheduleEventEntry(4000L, ScheduleEventType.ALARM_FIRED,
            ScheduleEventSource.ALARM, plannedAt = 3900L, slot = "06:15")
        val decoded = ScheduleEventCodec.decode(ScheduleEventCodec.encode(listOf(missed, alarm)))
        assertEquals(2000L, decoded[0].plannedAt)
        assertEquals("06:15", decoded[1].slot)
        val legacy = ScheduleEventCodec.decode(
            """[{"at":1000,"type":"ALARM_FIRED","source":"ALARM"}]""")
        assertEquals(0L, legacy.single().plannedAt)
        assertEquals("", legacy.single().slot)
    }

    @Test fun alarmAndAlreadySignedSkipAreSeparateFromSuccessfulSign() {
        val events = ScheduleEventCodec.add(
            listOf(ScheduleEventEntry(1000L, ScheduleEventType.ALARM_FIRED,
                ScheduleEventSource.ALARM)),
            ScheduleEventEntry(1001L, ScheduleEventType.SKIPPED_SIGNED, ScheduleEventSource.ALARM))
        val decoded = ScheduleEventCodec.decode(ScheduleEventCodec.encode(events))
        assertEquals(2, decoded.size)
        assertEquals(ScheduleEventType.SKIPPED_SIGNED, decoded[0].type)
        assertEquals(ScheduleEventSource.ALARM, decoded[0].source)
        assertEquals(ScheduleEventType.ALARM_FIRED, decoded[1].type)
        assertEquals(ScheduleEventSource.ALARM, decoded[1].source)
    }

    @Test fun scheduleHistoryRetainsOnlyLatestHundredAndIgnoresMalformedValues() {
        val many = (1L..150L).map {
            ScheduleEventEntry(it, ScheduleEventType.ALARM_FIRED, ScheduleEventSource.ALARM)
        }
        val decoded = ScheduleEventCodec.decode(ScheduleEventCodec.encode(many))
        assertEquals(100, decoded.size)
        assertEquals(1L, decoded.first().timestamp)
        assertEquals(100L, decoded.last().timestamp)
        assertTrue(ScheduleEventCodec.decode("invalid").isEmpty())
        assertTrue(ScheduleEventCodec.decode("""[{"at":10,"type":"UNKNOWN","source":"ALARM"}]""").isEmpty())
    }

    @Test fun restoredHistoryRequiresSavedRealSuccessCompletionNotJustSignedFlag() {
        val zone = ZoneId.systemDefault()
        val timestamp = LocalDate.of(2026, 10, 24).atTime(7, 1)
            .atZone(zone).toInstant().toEpochMilli()
        val date = SignDatePolicy.completedLocalDate(timestamp)
        val recovered = SignHistoryRecovery.candidate(
            date, timestamp, "签到成功 · 连续2天", emptyList())
        assertEquals(SignOrigin.RESTORED, recovered?.origin)
        assertEquals(SignRecordStatus.SUCCESS, recovered?.status)
        assertEquals(timestamp, recovered?.timestamp)
        assertNull(SignHistoryRecovery.candidate(date, 0, "签到成功", emptyList()))
        assertNull(SignHistoryRecovery.candidate("2026-10-23", timestamp,
            "签到成功", emptyList()))
        assertNull(SignHistoryRecovery.candidate(date, timestamp,
            "签到失败 · 网络异常", emptyList()))
        assertNull(SignHistoryRecovery.candidate(date, timestamp,
            "签到成功", listOf(recovered!!)))
    }

    @Test fun restoredRemoteAlreadySignedIsNotFabricatedAsSuccessfulRequest() {
        val timestamp = LocalDate.of(2026, 10, 24).atTime(7, 1)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val date = SignDatePolicy.completedLocalDate(timestamp)
        val event = SignHistoryRecovery.candidate(date, timestamp,
            "今日已签到，无需重复执行", emptyList())
        assertEquals(SignRecordStatus.ALREADY_SIGNED, event?.status)
        assertFalse(event?.origin == SignOrigin.MANUAL)
    }
}

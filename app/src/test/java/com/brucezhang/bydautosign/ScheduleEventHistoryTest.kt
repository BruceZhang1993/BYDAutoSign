package com.brucezhang.bydautosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ScheduleEventHistoryTest {
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

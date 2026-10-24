package com.brucezhang.bydautosign

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    @Test fun exportContainsOnlyAllowlistedFieldsNotSensitiveUiOrTransportStrings() {
        val secret = "RAW_SESSION_CODE_XYZ_123456789"
        val state = NativeUiState(
            ipc = "binder:" + secret,
            hookInfo = "dex:" + secret,
            sessionError = "error:" + secret,
            lastResult = "server:" + secret,
            connected = true,
            sessionReady = true,
            times = "07:01,22:01",
            enabled = true,
            signedToday = false,
            history = listOf(SignHistoryEntry(1000L, SignOrigin.SCHEDULED,
                SignRecordStatus.FAILED, SignFailureKind.NETWORK, 3)),
            scheduleEvents = listOf(ScheduleEventEntry(1100L,
                ScheduleEventType.SKIPPED_SIGNED, ScheduleEventSource.ALARM)),
            scheduleTrace = ScheduleSnapshot(nextAt = 12345L,
                lastState = "签到失败 " + secret,
                lastRecoverySource = secret, lastRecoveryDetail = secret,
                lastSuppressedKind = secret, crossDayAttempts = 2)
        )
        val serialized = DiagnosticReport.build(
            state, "0.5.2", 35, "Asia/Tokyo", 123456789L,
            notificationsAllowed = true)
        assertFalse(serialized.contains(secret))
        val json = JSONObject(serialized)
        assertEquals(2, json.getInt("schema"))
        assertEquals("0.5.2", json.getString("appVersion"))
        assertEquals(35, json.getInt("androidApi"))
        assertTrue(json.getJSONObject("permissions").getBoolean("notificationsAllowed"))
        assertTrue(json.getJSONObject("authorization").getBoolean("sessionAvailable"))
        assertEquals(1, json.getJSONArray("history").length())
        val event = json.getJSONArray("history").getJSONObject(0)
        assertEquals("NETWORK", event.getString("failure"))
        assertEquals(3, event.getInt("attempt"))
        val events = json.getJSONArray("scheduleEvents")
        assertEquals(1, events.length())
        assertEquals("SKIPPED_SIGNED", events.getJSONObject(0).getString("type"))
        assertEquals("ALARM", events.getJSONObject(0).getString("source"))
        assertEquals(1, json.getJSONObject("sign").getInt("scheduleEventCount"))
        val sched = json.getJSONObject("schedule")
        assertEquals("07:01,22:01", sched.getString("times"))
        assertEquals("FAILED", sched.getString("workerState"))
        assertEquals("UNKNOWN", sched.getString("recoverySource"))
        assertEquals("unknown", sched.getString("recoveryResult"))
        assertEquals("NONE", sched.getString("blockedFailure"))
        assertEquals(2, sched.getInt("crossDayAttempts"))
        assertFalse(json.has("session"))
        assertFalse(json.has("ipc"))
        assertFalse(json.has("lastResult"))
    }

    @Test fun suspiciousScheduleConfigIsNeverCopiedToReport() {
        val state = NativeUiState(times = "RAW_PRIVATE_VALUE")
        val report = DiagnosticReport.build(state, "0.5.2", 34, "UTC", 123456789L)
        assertFalse(report.contains("RAW_PRIVATE_VALUE"))
        assertEquals("invalid", JSONObject(report).getJSONObject("schedule").getString("times"))
    }
}

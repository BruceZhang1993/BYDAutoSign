package com.brucezhang.bydautosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NativeProtocolTest {
    @Test fun sha256MatchesLegacyShell() {
        assertEquals(
            "cc2d871e328a8d1749db2fe761e83e1afa5cd3dd3c6d50c7cce0443312bf1d90",
            BydProtocol.checksum("ABCDEFGHIJKLMNOP", 1700000000L)
        )
    }

    @Test fun aes128CbcMatchesOpenSslVector() {
        val plain = "{\"code\":\"abc123\"}"
        val cipher = "ASJsmPYS0tTDdYKrhFXRBED7wI+QP+Y2yIeYJWj+NuA="
        assertEquals(cipher, BydProtocol.encrypt(plain))
        assertEquals(plain, BydProtocol.decrypt(cipher))
    }

    @Test fun schedulerTimeValidation() {
        assertEquals("00:01,07:01,22:01",
            ScheduleTimes.normalize("22:01, 00:01,07:01,07:01"))
        assertEquals(null, ScheduleTimes.normalize("25:10"))
        assertEquals(null, ScheduleTimes.normalize("08:30,"))
        assertEquals(null, ScheduleTimes.normalize("8:30"))
    }

    @Test fun nextAlarmCrossesMidnight() {
        val now = ZonedDateTime.of(2026, 10, 23, 23, 30, 0, 0, ZoneId.of("Asia/Shanghai"))
        assertEquals("2026-10-24T00:01+08:00[Asia/Shanghai]",
            ScheduleTimes.next("00:01,07:01", now).toString())
    }
}

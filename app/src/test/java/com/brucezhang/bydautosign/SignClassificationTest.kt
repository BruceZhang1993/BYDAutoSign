package com.brucezhang.bydautosign

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import org.junit.Assert.assertNull

/** Synthetic BYD responses only; validates typed, secret-free status handling. */
class SignClassificationTest {
    private fun withResponse(response: MockResponse, check: (SignOutcome) -> Unit) {
        MockWebServer().use { server ->
            server.enqueue(response)
            server.start()
            check(NativeSignEngine(baseUrl = server.url("/").toString())
                .sign("SYNTHETIC_SESSION"))
        }
    }

    private fun json(body: String) = MockResponse().setBody(BydProtocol.encrypt(body))

    @Test fun notificationPolicySkipsAlreadySignedAndDeduplicatesByDayAndCategory() {
        val date = LocalDate.of(2026, 10, 24)
        val success = ActionResult(true, "签到成功")
        val already = ActionResult(true, "今日已签到", skipped = true)
        val network = ActionResult(false, "网络不可用",
            failure = SignFailureKind.NETWORK)
        assertEquals("2026-10-24:SUCCESS", SignNotificationPolicy.dailyKey(success, date))
        assertEquals("2026-10-24:NETWORK", SignNotificationPolicy.dailyKey(network, date))
        assertNull(SignNotificationPolicy.dailyKey(already, date))
        assertEquals("2026-10-25:SUCCESS",
            SignNotificationPolicy.dailyKey(success, date.plusDays(1)))
    }

    @Test fun successHasNoFailure() = withResponse(json("""{"ret":200,"durationDays":9}""")) {
        assertTrue(it.success)
        assertEquals(SignFailureKind.NONE, it.failure)
        assertEquals(9, it.days)
        assertFalse(it.alreadySigned)
    }

    @Test fun remoteAlreadySignedIsSkippedNotFailure() =
        withResponse(json("""{"ret":400,"msg":"今日已签到"}""")) {
            assertTrue(it.success)
            assertTrue(it.alreadySigned)
            assertFalse(it.shouldRetry)
        }

    @Test fun authExpiredIsNotRetriedAndRequiresExplicitMessage() =
        withResponse(json("""{"ret":401,"msg":"session expired"}""")) {
            assertFalse(it.success)
            assertTrue(it.authorizationExpired)
            assertFalse(it.shouldRetry)
            assertEquals(SignFailureKind.AUTH_EXPIRED, it.failure)
        }

    @Test fun ordinaryBusinessFailureDoesNotClearSession() =
        withResponse(json("""{"ret":412,"msg":"unrecognized secret_token_123"}""")) {
            assertFalse(it.success)
            assertFalse(it.authorizationExpired)
            assertEquals(SignFailureKind.BUSINESS, it.failure)
            assertFalse(it.message.contains("secret_token_123"))
        }

    @Test fun serverFailureIsRetryable() =
        withResponse(MockResponse().setResponseCode(503)) {
            assertEquals(SignFailureKind.SERVER, it.failure)
            assertTrue(it.shouldRetry)
        }

    @Test fun clientFailureIsNotRetryable() =
        withResponse(MockResponse().setResponseCode(403)) {
            assertEquals(SignFailureKind.HTTP_CLIENT, it.failure)
            assertFalse(it.shouldRetry)
        }

    @Test fun invalidCipherIsProtocolFailureWithoutSecrets() =
        withResponse(MockResponse().setBody("RAW_SECRET_BACKEND_RESPONSE")) {
            assertEquals(SignFailureKind.PROTOCOL, it.failure)
            assertFalse(it.shouldRetry)
            assertFalse(it.message.contains("RAW_SECRET_BACKEND_RESPONSE"))
        }
}

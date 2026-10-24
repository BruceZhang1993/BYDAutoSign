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

    @Test fun successfulRequestCrossingMidnightUsesCompletionDay() {
        val zone = java.time.ZoneId.of("Asia/Tokyo")
        val requestStarted = java.time.ZonedDateTime.of(
            2026, 10, 23, 23, 59, 59, 0, zone).toInstant().toEpochMilli()
        val responseCompleted = java.time.ZonedDateTime.of(
            2026, 10, 24, 0, 0, 2, 0, zone).toInstant().toEpochMilli()
        assertEquals("2026-10-23", SignDatePolicy.completedLocalDate(requestStarted, zone))
        assertEquals("2026-10-24", SignDatePolicy.completedLocalDate(responseCompleted, zone))
        // The successful coordinator passes the completion timestamp, not requestStarted.
    }

    @Test fun distinctFailureCategoriesCannotCauseDuplicateNotificationInOneDay() {
        val today = LocalDate.of(2026, 10, 24)
        val network = ActionResult(false, "网络故障", failure = SignFailureKind.NETWORK)
        val client = ActionResult(false, "请求被拒绝", failure = SignFailureKind.HTTP_CLIENT)
        val networkKey = SignNotificationPolicy.dailyKey(network, today)
        val clientKey = SignNotificationPolicy.dailyKey(client, today)
        var sent = SignNotificationPolicy.keysForToday("", today, emptySet())
        assertTrue(SignNotificationPolicy.shouldNotify(networkKey, sent))
        sent = sent + requireNotNull(networkKey)
        assertTrue(SignNotificationPolicy.shouldNotify(clientKey, sent))
        sent = sent + requireNotNull(clientKey)
        // NETWORK -> HTTP_CLIENT -> NETWORK must not alert NETWORK again.
        assertFalse(SignNotificationPolicy.shouldNotify(networkKey, sent))
        assertEquals(setOf("2026-10-24:NETWORK", "2026-10-24:HTTP_CLIENT"), sent)
        // A new local day resets the set even if it was persisted from yesterday.
        val tomorrow = today.plusDays(1)
        val nextDay = SignNotificationPolicy.keysForToday(today.toString(), tomorrow, sent)
        assertTrue(nextDay.isEmpty())
        assertTrue(SignNotificationPolicy.shouldNotify(
            SignNotificationPolicy.dailyKey(network, tomorrow), nextDay))
        // Repeated notifications for the same category on the same day stay skipped.
        assertEquals(sent, SignNotificationPolicy.keysForToday(today.toString(), today, sent))
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
            assertEquals(412, it.businessCode)
            assertTrue(it.message.contains("ret=412"))
            assertFalse(it.message.contains("secret_token_123"))
            assertEquals(null, it.serverMessage)
        }

    @Test fun businessErrorKeepsSafeChineseDetailsAndReturnCode() =
        withResponse(json("""{"ret":423,"msg":"账号状态异常，请联系客服"}""")) {
            assertEquals(SignFailureKind.BUSINESS, it.failure)
            assertEquals(423, it.businessCode)
            assertEquals("账号状态异常，请联系客服", it.serverMessage)
            assertTrue(it.message.contains("ret=423"))
            assertTrue(it.message.contains("账号状态异常"))
        }

    @Test fun chineseSessionExpiryRequestsReauthorization() =
        withResponse(json("""{"ret":401,"msg":"登录状态已失效，请重新登录"}""")) {
            assertEquals(SignFailureKind.AUTH_EXPIRED, it.failure)
            assertTrue(it.authorizationExpired)
            assertEquals(401, it.businessCode)
            assertFalse(it.shouldRetry)
        }

    @Test fun safeErrorDetailsRejectSecretsAndPersonalNumbers() {
        assertEquals(null, SignFailureDiagnostics.safeServerMessage("session_id:ABC123SECRET"))
        assertEquals(null, SignFailureDiagnostics.safeServerMessage("手机号13812345678"))
        assertEquals(null, SignFailureDiagnostics.safeServerMessage("token\nabc-def-xyz"))
        assertEquals("服务暂不可用", SignFailureDiagnostics.safeServerMessage("服务暂不可用"))
        assertEquals(null, SignFailureDiagnostics.safeBusinessCode(Int.MAX_VALUE))
        assertEquals(null, SignFailureDiagnostics.safeHttpStatus(900))
        assertEquals(403, SignFailureDiagnostics.safeHttpStatus(403))
        }

    @Test fun serverFailureIsRetryable() =
        withResponse(MockResponse().setResponseCode(503)) {
            assertEquals(SignFailureKind.SERVER, it.failure)
            assertTrue(it.shouldRetry)
        }

    @Test fun http408IsTransientTimeout() =
        withResponse(MockResponse().setResponseCode(408)) {
            assertEquals(SignFailureKind.TIMEOUT, it.failure)
            assertTrue(it.shouldRetry)
        }

    @Test fun http429IsRetryableServerThrottling() =
        withResponse(MockResponse().setResponseCode(429)) {
            assertEquals(SignFailureKind.SERVER, it.failure)
            assertTrue(it.shouldRetry)
        }

    @Test fun clientFailureIsNotRetryable() =
        withResponse(MockResponse().setResponseCode(403)) {
            assertEquals(SignFailureKind.HTTP_CLIENT, it.failure)
            assertEquals(403, it.httpStatus)
            assertTrue(it.message.contains("HTTP 403"))
            assertFalse(it.shouldRetry)
        }

    @Test fun invalidCipherIsProtocolFailureWithoutSecrets() =
        withResponse(MockResponse().setBody("RAW_SECRET_BACKEND_RESPONSE")) {
            assertEquals(SignFailureKind.PROTOCOL, it.failure)
            assertFalse(it.shouldRetry)
            assertFalse(it.message.contains("RAW_SECRET_BACKEND_RESPONSE"))
        }
}

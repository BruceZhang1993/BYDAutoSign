package com.brucezhang.bydautosign

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline end-to-end protocol equivalence: header checks, encrypted request
 * bodies, decrypted responses and historical nested JSON field extraction.
 * All tokens are synthetic and never contact BYD/WeChat servers.
 */
class NativeExchangeContractTest {
    @Test fun exchangeParsesNestedSessionLikeLegacyShell() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                BydProtocol.encrypt("""{"result":{"data":{"session_id":"SYNTHETIC_SESSION"}}}""")))
            server.start()
            val engine = NativeSignEngine(baseUrl = server.url("/").toString())
            assertEquals("SYNTHETIC_SESSION", engine.exchangeSession("TESTCODE123"))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/?service=mina.decryptCode", request.path)
            assertEquals("application/json", request.getHeader("Content-Type"))
            assertEquals("hyMinaApi", request.getHeader("Appkey"))
            assertEquals("1", request.getHeader("Xweb_xhr"))
            val nonce = requireNotNull(request.getHeader("Nonce"))
            val time = requireNotNull(request.getHeader("Curtime")).toLong()
            assertEquals(BydProtocol.checksum(nonce, time), request.getHeader("Checksum"))
            assertEquals("""{"code":"TESTCODE123"}""",
                BydProtocol.decrypt(request.body.readUtf8()))
        }
    }

    @Test fun signParsesNestedResultAndStreak() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                BydProtocol.encrypt("""{"data":{"ret":200,"durationDays":12}}""")))
            server.start()
            val engine = NativeSignEngine(baseUrl = server.url("/").toString())
            val result = engine.sign("SYNTHETIC_SESSION")
            assertTrue(result.success)
            assertEquals(12, result.days)
            val request = server.takeRequest()
            assertEquals("/?s=ForCommonUcSrv.forward&serviceDir=activity/sign/signIn", request.path)
            assertEquals(
                """{"date":"","belong_brand":"hy","session_id":"SYNTHETIC_SESSION","app_version":"460","app_client":"mina"}""",
                BydProtocol.decrypt(request.body.readUtf8()))
        }
    }

    @Test fun httpErrorNeverPrintsRawServerBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("SECRET_FROM_BACKEND"))
            server.start()
            val engine = NativeSignEngine(baseUrl = server.url("/").toString())
            try {
                engine.exchangeSession("TESTCODE123")
                throw AssertionError("expected protocol error")
            } catch (e: ProtocolException) {
                assertTrue(e.message!!.contains("HTTP 403"))
                assertFalse(e.message!!.contains("SECRET_FROM_BACKEND"))
            }
        }
    }

    @Test fun rejectsUnicodeLoginCodeUnlikeOldAsciiShell() {
        try {
            NativeSignEngine().exchangeSession("测试AAAAAA")
            throw AssertionError("expected validation failure")
        } catch (e: ProtocolException) {
            assertTrue(e.message!!.contains("格式无效"))
        }
    }
}

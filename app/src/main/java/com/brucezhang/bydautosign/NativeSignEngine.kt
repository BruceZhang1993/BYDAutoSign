package com.brucezhang.bydautosign

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protocol-compatible rewrite of the old MIT-licensed byd_sign script.
 * These constants are PUBLIC client protocol material, not user credentials.
 * Actual BYD sessions are stored separately with Android Keystore.
 */
internal object BydProtocol {
    private const val KEY = "3993014457161851"
    private const val IV = "PDVcDRWMrBlLHTqh"
    private const val APP_SECRET = "Kfl%BOk6C5PwARw8"
    const val APP_ID = "wxf62054ec313d6f53"
    const val APP_KEY = "hyMinaApi"
    const val BASE_URL = "https://mina.bydoceanauto.com/"
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
    private val random = SecureRandom()

    fun nonce(): String = buildString {
        repeat(16) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    fun checksum(nonce: String, timeSeconds: Long): String =
        MessageDigest.getInstance("SHA-256")
            .digest((APP_SECRET + nonce + timeSeconds).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8)))
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(encoded: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8)))
        return String(cipher.doFinal(Base64.getMimeDecoder().decode(encoded.trim())), Charsets.UTF_8)
    }

    fun loginPayload(code: String): String = "{\"code\":" + JSONObject.quote(code) + "}"
    fun signPayload(session: String): String =
        "{\"date\":\"\",\"belong_brand\":\"hy\",\"session_id\":" +
            JSONObject.quote(session) + ",\"app_version\":\"460\",\"app_client\":\"mina\"}"
}

internal class ProtocolException(message: String) : IOException(message)
internal class AuthenticationExpired : IOException("会话已失效，请重新授权")

internal data class SignOutcome(
    val success: Boolean,
    val message: String,
    val days: Int = 0,
    val shouldRetry: Boolean = false,
    val authorizationExpired: Boolean = false
)

/**
 * All traffic is HTTPS with system TLS certificate and hostname validation.
 * No permissive trust manager, no shell commands, and no localhost bridge.
 */
internal class NativeSignEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
) {
    // Do not log or include the one-time WeChat code in any exception message.
    fun exchangeSession(code: String): String {
        if (code.length !in 6..4096 || !code.all { it.isLetterOrDigit() || it in "_+./=:%-" }) {
            throw ProtocolException("微信授权凭证格式无效")
        }
        val json = request("mina.decryptCode", BydProtocol.loginPayload(code))
        val session = json.optString("session_id")
        if (session.isBlank() || session.length > 8192) {
            throw ProtocolException("服务器没有返回有效会话")
        }
        return session
    }

    fun sign(session: String): SignOutcome {
        if (session.isBlank()) return SignOutcome(false, "需要重新授权")
        return try {
            val json = request("activity/sign/signIn", BydProtocol.signPayload(session))
            if (json.optInt("ret", -1) == 200) {
                SignOutcome(true, "签到成功", json.optInt("durationDays", 0).coerceAtLeast(0))
            } else {
                // Only explicit server expiry indicators cause credential invalidation.
                val safeText = listOf(json.optString("msg"), json.optString("message"))
                    .joinToString(" ")
                val expired = Regex(
                    "session[_ ]?id.{0,32}(失效|过期|invalid|expired)|未登录|登录已过期|session expired|invalid session",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(safeText)
                SignOutcome(false, if (expired) "会话过期，需要重新授权" else "签到未成功",
                    authorizationExpired = expired)
            }
        } catch (e: IOException) {
            SignOutcome(false, when (e) {
                is ProtocolException -> e.message ?: "响应格式异常"
                else -> "网络不可用或请求超时"
            }, shouldRetry = e !is ProtocolException)
        } catch (_: Exception) {
            SignOutcome(false, "响应解析失败")
        }
    }

    private fun request(service: String, plainBody: String): JSONObject {
        val time = Instant.now().epochSecond
        val nonce = BydProtocol.nonce()
        val path = when (service) {
            "mina.decryptCode" -> "?service=mina.decryptCode"
            "activity/sign/signIn" -> "?s=ForCommonUcSrv.forward&serviceDir=activity/sign/signIn"
            else -> throw ProtocolException("不支持的接口")
        }
        val body = BydProtocol.encrypt(plainBody)
        val req = Request.Builder().url(BydProtocol.BASE_URL + path)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("X-Clienttraceid", "mina-" + UUID.randomUUID().toString())
            .header("Nonce", nonce)
            .header("Curtime", time.toString())
            .header("Checksum", BydProtocol.checksum(nonce, time))
            .header("Appkey", BydProtocol.APP_KEY)
            .header("Xweb_xhr", "1")
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("Referer", "https://servicewechat.com/${BydProtocol.APP_ID}/142/page-frame.html")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code in 400..499) throw ProtocolException("服务端拒绝请求（HTTP ${response.code}）")
                throw IOException("HTTP ${response.code}")
            }
            val text = response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw ProtocolException("服务端响应为空")
            // Never put unencrypted upstream response into logs or UI.
            val decrypted = try { BydProtocol.decrypt(text) }
                catch (_: Exception) { throw ProtocolException("服务端响应解密失败") }
            return try { JSONObject(decrypted) }
                catch (_: Exception) { throw ProtocolException("服务器返回的 JSON 格式无效") }
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 " +
            "MicroMessenger/7.0.20.1781(0x6700143B) NetType/WIFI " +
            "MiniProgramEnv/Windows WindowsWechat/WMPF WindowsWechat(0x63090a13) " +
            "UnifiedPCWindowsWechat(0xf2541a1b) XWEB/19895"
    }
}

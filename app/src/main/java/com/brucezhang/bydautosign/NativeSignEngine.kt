package com.brucezhang.bydautosign

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
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

internal object ResponseFields {
    /**
     * The original Shell parser searched for field names at ANY JSON depth,
     * whereas a top-level JSONObject.optString() silently missed nested data.
     * Restrict traversal depth and node count for untrusted server responses.
     */
    fun lookup(root: Any, field: String): Any? {
        var remaining = 128
        fun find(value: Any?, depth: Int): Any? {
            if (value == null || depth > 8 || --remaining < 0) return null
            when (value) {
                is JSONObject -> {
                    if (value.has(field) && !value.isNull(field)) return value.opt(field)
                    val names = value.keys()
                    while (names.hasNext()) {
                        val nested = find(value.opt(names.next()), depth + 1)
                        if (nested != null) return nested
                    }
                }
                is org.json.JSONArray -> {
                    for (i in 0 until minOf(value.length(), 128)) {
                        val nested = find(value.opt(i), depth + 1)
                        if (nested != null) return nested
                    }
                }
            }
            return null
        }
        return find(root, 0)
    }

    fun text(root: Any, field: String): String? {
        val value = lookup(root, field)
        return if (value is String) value else null
    }

    fun int(root: Any, field: String): Int? {
        val value = lookup(root, field)
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
}

internal class ProtocolException(
    message: String,
    val failure: SignFailureKind = SignFailureKind.PROTOCOL,
    val retryable: Boolean = false,
    val httpStatus: Int? = null
) : IOException(message)
internal class AuthenticationExpired : IOException("会话已失效，请重新授权")

internal data class SignOutcome(
    val success: Boolean,
    val message: String,
    val days: Int = 0,
    val shouldRetry: Boolean = false,
    val authorizationExpired: Boolean = false,
    val alreadySigned: Boolean = false,
    val failure: SignFailureKind = SignFailureKind.NONE,
    val businessCode: Int? = null,
    val serverMessage: String? = null,
    val httpStatus: Int? = null
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
        .build(),
    // Test override only. Production always uses the fixed HTTPS BYD endpoint.
    private val baseUrl: String = BydProtocol.BASE_URL
) {
    // Do not log or include the one-time WeChat code in any exception message.
    fun exchangeSession(code: String): String {
        if (code.length !in 6..4096 || !code.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_+./=:%-" }) {
            throw ProtocolException("微信授权凭证格式无效")
        }
        val json = request("mina.decryptCode", BydProtocol.loginPayload(code))
        val session = ResponseFields.text(json, "session_id").orEmpty()
        if (session.isBlank() || session.length > 8192) {
            throw ProtocolException("服务器没有返回有效会话")
        }
        return session
    }

    fun sign(session: String): SignOutcome {
        if (session.isBlank()) return SignOutcome(false, "需要重新授权",
            failure = SignFailureKind.NO_SESSION)
        return try {
            val json = request("activity/sign/signIn", BydProtocol.signPayload(session))
            val ret = ResponseFields.int(json, "ret")
            if (ret == 200) {
                SignOutcome(true, "签到成功",
                    (ResponseFields.int(json, "durationDays") ?: 0).coerceAtLeast(0))
            } else {
                // Inspect the raw response in memory only. Never persist raw response bodies
                // or unsanitized server messages (they may contain session identifiers).
                val serverText = listOfNotNull(
                    ResponseFields.text(json, "msg"),
                    ResponseFields.text(json, "message")
                ).joinToString(" ").trim()
                val safeMessage = SignFailureDiagnostics.safeServerMessage(serverText)
                val codeDescription = ret?.let { "ret=$it" } ?: "ret=未知"
                val expired = Regex(
                    """session[_ -]?id.{0,32}(失效|过期|无效|invalid|expired)|""" +
                    """(登录|会话|授权)(态|状态|信息|凭证)?.{0,12}(失效|过期|无效)|""" +
                    """(请先登录|请重新登录|重新授权|未登录|登录已过期|""" +
                    """session expired|invalid session|authentication expired)""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(serverText)
                val alreadySigned = !expired && Regex(
                    "已签到|已经签到|今日已签|重复签到|already signed",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(serverText)
                when {
                    alreadySigned -> SignOutcome(true, "今日已签到，无需重复执行",
                        alreadySigned = true, businessCode = ret, serverMessage = safeMessage)
                    expired -> SignOutcome(false, "会话过期，需要重新授权 · $codeDescription",
                        authorizationExpired = true, failure = SignFailureKind.AUTH_EXPIRED,
                        businessCode = ret, serverMessage = safeMessage)
                    else -> SignOutcome(false,
                        "签到未成功 · $codeDescription · " + (
                            safeMessage ?: if (serverText.isBlank()) "服务端未返回错误说明"
                            else "服务端提示已隐藏（可能包含敏感信息）"
                        ),
                        failure = SignFailureKind.BUSINESS, businessCode = ret,
                        serverMessage = safeMessage)
                }
            }
        } catch (e: IOException) {
            val category = when (e) {
                is ProtocolException -> e.failure
                is SSLException -> SignFailureKind.TLS
                is SocketTimeoutException -> SignFailureKind.TIMEOUT
                is UnknownHostException -> SignFailureKind.NETWORK
                else -> SignFailureKind.NETWORK
            }
            val message = when (category) {
                SignFailureKind.TLS -> "安全连接失败，请检查证书和系统时间"
                SignFailureKind.TIMEOUT -> "请求超时"
                SignFailureKind.NETWORK -> "网络连接失败"
                SignFailureKind.SERVER -> "比亚迪服务器暂时不可用"
                SignFailureKind.HTTP_CLIENT -> "服务器拒绝签到请求"
                else -> "协议或响应异常"
            }
            SignOutcome(false, message + ((e as? ProtocolException)?.httpStatus?.let {
                "（HTTP $it）"
            } ?: ""),
                shouldRetry = if (e is ProtocolException) e.retryable
                    else category == SignFailureKind.NETWORK || category == SignFailureKind.TIMEOUT,
                failure = category, httpStatus = (e as? ProtocolException)?.httpStatus)
        } catch (_: Exception) {
            SignOutcome(false, "响应解析失败", failure = SignFailureKind.UNKNOWN)
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
        val req = Request.Builder().url(baseUrl + path)
            .post(body.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
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
            .header("Sec-Fetch-Site", "cross-site")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
            .header("Priority", "u=1, i")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 408)
                    throw ProtocolException("请求超时（HTTP 408）",
                        SignFailureKind.TIMEOUT, retryable = true, httpStatus = response.code)
                if (response.code == 429 || response.code in 500..599)
                    throw ProtocolException("服务端暂不可用（HTTP ${response.code}）",
                        SignFailureKind.SERVER, retryable = true, httpStatus = response.code)
                if (response.code in 400..499)
                    throw ProtocolException("服务端拒绝请求（HTTP ${response.code}）",
                        SignFailureKind.HTTP_CLIENT, httpStatus = response.code)
                throw ProtocolException("服务端异常", SignFailureKind.SERVER)
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

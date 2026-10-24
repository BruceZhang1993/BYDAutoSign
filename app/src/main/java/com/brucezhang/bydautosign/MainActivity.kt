package com.brucezhang.bydautosign

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.brucezhang.bydautosign.ipc.IpcBrokerService
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore alarms on first launch or after an app update without Root.
        SignScheduler.recover(applicationContext, ScheduleRecoverySource.APP_START, force = true)
        val manager = NativeSignManager(applicationContext)
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                Dashboard(manager, onExactPermission = {
                    if (Build.VERSION.SDK_INT >= 31) {
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")))
                    }
                })
            }
        }
    }
}

internal data class NativeUiState(
    val ipc: String = "",
    val connected: Boolean = false,
    val hookVersion: String = "",
    val hookReady: Boolean = false,
    val hookInfo: String = "",
    val sessionReady: Boolean = false,
    val sessionError: String = "",
    val enabled: Boolean = true,
    val times: String = "",
    val exact: Boolean = false,
    val next: String = "",
    val lastResult: String = "尚未执行",
    val lastRun: String = "",
    val signedToday: Boolean = false,
    val history: List<SignHistoryEntry> = emptyList(),
    val scheduleEvents: List<ScheduleEventEntry> = emptyList(),
    val scheduleTrace: ScheduleSnapshot = ScheduleSnapshot()
)

internal class NativeSignManager(private val context: android.content.Context) {
    suspend fun refresh(): NativeUiState = withContext(Dispatchers.IO) {
        val store = SignStore(context)
        val sessionCheck = runCatching { store.session() }
        val hook = try { JSONObject(IpcBrokerService.readHookStatus() ?: "{}") }
            catch (_: Exception) { JSONObject() }
        // If a prior alarm was missed while offline/asleep, avoid losing it
        // when a foreground refresh cancels and replaces the PendingIntent.
        SignScheduler.recover(context, ScheduleRecoverySource.FOREGROUND)
        val formatted = if (store.lastRun > 0) DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(store.lastRun)) else ""
        // Old installs can have a persisted successful result but no v0.5.1
        // history entry. Reconcile only when the actual completion time is known.
        runCatching {
            SignHistory(context).recoverLastSuccess(store.signedDate, store.lastRun, store.lastResult)
        }
        val telemetry = ScheduleTelemetry(context).snapshot()
        val next = when {
            !store.enabled -> "自动签到已关闭"
            telemetry.nextAt <= 0 -> "尚未建立调度"
            else -> "下次 ${formatDateTime(telemetry.nextAt)} · " +
                if (telemetry.exact) "精确唤醒" else "非精确唤醒"
        }
        NativeUiState(
            ipc = IpcBrokerService.diagnostic(),
            connected = IpcBrokerService.connected(),
            hookVersion = hook.optString("version", ""),
            hookReady = hook.optBoolean("configSupported", false),
            hookInfo = hook.optString("probeStatus", ""),
            sessionReady = sessionCheck.getOrNull() != null,
            sessionError = when {
                sessionCheck.isFailure -> "本地会话不可解密，请重新授权"
                store.automaticBlockKind() == SignFailureKind.AUTH_EXPIRED ->
                    "会话已过期，自动签到暂停；请重新授权"
                else -> ""
            },
            enabled = store.enabled,
            times = store.times,
            exact = SignScheduler.exactAllowed(context),
            next = next,
            lastResult = store.lastResult,
            lastRun = formatted,
            signedToday = store.signedToday(),
            history = SignHistory(context).list(),
            scheduleEvents = ScheduleEventLog(context).list(),
            scheduleTrace = telemetry
        )
    }

    suspend fun authorize(): ActionResult = withContext(Dispatchers.IO) {
        if (!IpcBrokerService.connected()) {
            // An explicit user action can open WeChat, unlike a scheduled sign-in.
            val open = context.packageManager.getLaunchIntentForPackage("com.tencent.mm")
                ?: return@withContext ActionResult(false, "未找到微信，请安装并启动微信")
            try { context.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) {
                return@withContext ActionResult(false, "微信无法启动，请手动打开微信后返回授权")
            }
            repeat(15) {
                if (IpcBrokerService.connected()) return@repeat
                kotlinx.coroutines.delay(1000)
            }
        }
        if (!IpcBrokerService.connected()) {
            return@withContext ActionResult(false, "微信 Binder 未连接：请打开微信、确认 LSPosed 作用域后返回重试")
        }
        val code = IpcBrokerService.obtainCode(BydProtocol.APP_ID)
        if (!code.successful()) {
            return@withContext ActionResult(false, "微信授权失败（状态 ${code.status}），请检查 Hook/DEX 探测")
        }
        val session = try {
            NativeSignEngine().exchangeSession(code.code)
        } catch (e: ProtocolException) {
            return@withContext ActionResult(false, "会话兑换失败：${e.message}")
        } catch (_: SSLException) {
            return@withContext ActionResult(false,
                "会话兑换失败：HTTPS/TLS 证书或握手校验失败（TLS）。不会关闭证书验证，请检查系统时间与网络环境")
        } catch (_: UnknownHostException) {
            return@withContext ActionResult(false, "会话兑换失败：无法解析比亚迪服务器域名（DNS）")
        } catch (_: SocketTimeoutException) {
            return@withContext ActionResult(false, "会话兑换失败：连接或响应超时（Timeout）")
        } catch (_: ConnectException) {
            return@withContext ActionResult(false, "会话兑换失败：服务器连接失败（Connect）")
        } catch (e: IOException) {
            // Exception messages can embed URLs/server bodies; only expose class names.
            return@withContext ActionResult(false,
                "会话兑换失败：网络请求异常（${e.javaClass.simpleName}）")
        } catch (e: Exception) {
            return@withContext ActionResult(false,
                "会话兑换失败：协议处理异常（${e.javaClass.simpleName}）")
        }
        try {
            SignStore(context).saveSession(session)
            if (SignStore(context).session() != session) {
                return@withContext ActionResult(false, "会话保存后校验失败，请检查 Android Keystore")
            }
            ActionResult(true, "已通过 Binder 授权并安全保存 BYD 会话")
        } catch (e: Exception) {
            // Expose no underlying provider message: it may contain sensitive data.
            ActionResult(false,
                "已兑换到会话，但 Android Keystore 本地保存或读取失败（${e.javaClass.simpleName}）")
        }
    }

    suspend fun sign(): ActionResult = withContext(Dispatchers.IO) {
        val result = SignCoordinator.sign(context, SignOrigin.MANUAL)
        SignNotifier(context).notifyResult(result)
        result
    }

    suspend fun saveSchedule(enabled: Boolean, times: String): ActionResult = withContext(Dispatchers.IO) {
        val valid = ScheduleTimes.normalize(times) != null
        if (!valid) ActionResult(false, "请选择至少一个有效时间（最多8组）")
        else {
            val result = SignScheduler.configure(context, enabled, times)
            ActionResult(!result.startsWith("设置定时失败"), result)
        }
    }

    suspend fun reprobe(): ActionResult = withContext(Dispatchers.IO) {
        if (IpcBrokerService.requestProbe()) ActionResult(true, "已请求微信重新探测 DEX")
        else ActionResult(false, "微信 Binder 尚未连接")
    }
}


private fun formatDateTime(millis: Long): String =
    if (millis <= 0L) "暂无记录" else DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

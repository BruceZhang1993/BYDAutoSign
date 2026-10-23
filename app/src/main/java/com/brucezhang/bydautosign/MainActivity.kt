package com.brucezhang.bydautosign

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.brucezhang.bydautosign.ipc.IpcBrokerService
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import java.security.GeneralSecurityException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore alarms on first launch or after an app update without Root.
        SignScheduler.reschedule(applicationContext)
        SignScheduler.compensate(applicationContext)
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
    val signedToday: Boolean = false
)

internal class NativeSignManager(private val context: android.content.Context) {
    suspend fun refresh(): NativeUiState = withContext(Dispatchers.IO) {
        val store = SignStore(context)
        val sessionCheck = runCatching { store.session() }
        val hook = try { JSONObject(IpcBrokerService.readHookStatus() ?: "{}") }
            catch (_: Exception) { JSONObject() }
        // If a prior alarm was missed while offline/asleep, avoid losing it
        // when a foreground refresh cancels and replaces the PendingIntent.
        SignScheduler.compensate(context)
        val formatted = if (store.lastRun > 0) DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(store.lastRun)) else ""
        NativeUiState(
            ipc = IpcBrokerService.diagnostic(),
            connected = IpcBrokerService.connected(),
            hookVersion = hook.optString("version", ""),
            hookReady = hook.optBoolean("configSupported", false),
            hookInfo = hook.optString("probeStatus", ""),
            sessionReady = sessionCheck.getOrNull() != null,
            sessionError = if (sessionCheck.isFailure) "本地会话不可解密，请重新授权" else "",
            enabled = store.enabled,
            times = store.times,
            exact = SignScheduler.exactAllowed(context),
            next = SignScheduler.reschedule(context),
            lastResult = store.lastResult,
            lastRun = formatted,
            signedToday = store.signedToday()
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

    suspend fun sign(): ActionResult = withContext(Dispatchers.IO) { SignCoordinator.sign(context) }

    suspend fun saveSchedule(enabled: Boolean, times: String): ActionResult = withContext(Dispatchers.IO) {
        val valid = ScheduleTimes.normalize(times) != null
        if (!valid) ActionResult(false, "请输入 HH:mm，多个时间用英文逗号分隔（最多8组）")
        else ActionResult(true, SignScheduler.configure(context, enabled, times))
    }

    suspend fun reprobe(): ActionResult = withContext(Dispatchers.IO) {
        if (IpcBrokerService.requestProbe()) ActionResult(true, "已请求微信重新探测 DEX")
        else ActionResult(false, "微信 Binder 尚未连接")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dashboard(manager: NativeSignManager, onExactPermission: () -> Unit) {
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val guard = remember { Mutex() }
    var state by remember { mutableStateOf(NativeUiState()) }
    var enabled by remember { mutableStateOf(true) }
    var times by remember { mutableStateOf("00:01,07:01,13:01,22:01") }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }

    suspend fun reload() {
        guard.withLock {
            state = manager.refresh()
            enabled = state.enabled
            times = state.times
        }
    }

    fun runAction(action: suspend () -> ActionResult) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val result = action()
                feedback = result.message
                reload()
            } catch (_: Exception) {
                feedback = "操作失败，请检查网络或系统权限"
            } finally {
                busy = false
            }
        }
    }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !busy) scope.launch { reload() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("BYD Auto Sign") }) }) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("每日自动签到", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold)
                Text("纯 Android 原生执行 · 无 Root · 无常驻微信",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                UiSection("状态") {
                    InfoLine("签到", if (state.signedToday) "今日已完成" else state.lastResult)
                    InfoLine("微信 Binder", state.ipc.ifBlank { "等待微信注册" })
                    InfoLine("Hook", if (state.hookReady) "微信 ${state.hookVersion} · 就绪"
                        else "未就绪 · 请检查 LSPosed 并启动微信")
                    if (state.hookInfo.isNotBlank()) {
                        Text(state.hookInfo, style = MaterialTheme.typography.bodySmall)
                    }
                    InfoLine("会话", when {
                        state.sessionError.isNotBlank() -> state.sessionError
                        state.sessionReady -> "已授权（Android Keystore 加密）"
                        else -> "未授权 · 旧 Root 会话无法自动迁移"
                    })
                    OutlinedButton(enabled = !busy, onClick = { runAction { manager.reprobe() } }) {
                        Text("重新探测微信 DEX")
                    }
                }
            }
            item {
                UiSection("微信授权") {
                    Text("只在手动授权时获取一次性微信 code 并兑换会话。" +
                        " 每日签到不会拉起微信，也不会读取 Root 目录。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        onClick = { runAction { manager.authorize() } }
                    ) { Text(if (state.sessionReady) "重新授权" else "初始化并授权") }
                    Text("当前仅支持主用户微信；分身跨 User 授权尚未实现。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                UiSection("自动调度") {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()) {
                        Text("启用每日签到", modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    OutlinedTextField(
                        value = times,
                        onValueChange = { times = it },
                        label = { Text("每日执行时间") },
                        supportingText = { Text("本地时区 · HH:mm，用逗号分隔，最多8个时间") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    FilledTonalButton(enabled = !busy, onClick = {
                        runAction { manager.saveSchedule(enabled, times) }
                    }) { Text("保存调度") }
                    InfoLine("下一次触发", state.next)
                    if (!state.exact && Build.VERSION.SDK_INT >= 31) {
                        Text("精确闹钟权限未授权：仍可非精确唤醒，但可能延迟。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onExactPermission) { Text("授予精确闹钟权限") }
                    }
                    Text("AlarmManager 唤醒 + WorkManager 网络恢复重试；" +
                        "Doze 与系统节电策略可能使触发延迟。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                UiSection("签到任务") {
                    Button(modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && state.sessionReady,
                        onClick = { runAction { manager.sign() } }) { Text("立即签到") }
                    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (state.lastRun.isNotBlank()) {
                        Text("上次执行：${state.lastRun} · ${state.lastResult}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (feedback.isNotBlank()) {
                item { UiSection("操作结果") { Text(feedback) } }
            }
            item {
                Text("需要 LSPosed 仅用于微信首次授权；Root、crond、curl 与 OpenSSL 均不再使用。" +
                    "升级前的 Root 调度不会被新 APK 自动删除，请手动停用旧模块和任务以免重复执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 20.dp))
            }
        }
    }
}

@Composable
private fun UiSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, fontWeight = FontWeight.Medium)
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

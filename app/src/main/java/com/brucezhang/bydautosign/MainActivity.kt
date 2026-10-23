package com.brucezhang.bydautosign

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bridge = RootBridge(applicationContext)
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme() else lightColorScheme()

            MaterialTheme(colorScheme = colors) {
                Dashboard(bridge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dashboard(bridge: RootBridge) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(DeviceState()) }
    var ports by remember { mutableStateOf("8088") }
    var busy by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    val lifecycleOwner = LocalLifecycleOwner.current
    // A foreground refresh and a post-action refresh must not race and leave
    // the dashboard showing an older scheduler or Binder state.
    val refreshMutex = remember { Mutex() }

    suspend fun reload() {
        refreshMutex.withLock {
            val newState = bridge.refresh()
            state = newState
            ports = newState.ports
        }
    }

    fun execute(action: suspend () -> ShellResult) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val result = action()
                resultText = (if (result.ok) "操作完成\n" else "操作失败（" + result.exitCode + "）\n") +
                    result.output.takeLast(3500)
                reload()
            } finally {
                busy = false
            }
        }
    }

    // Android may keep the Activity and Compose tree alive while the app is
    // backgrounded. LaunchedEffect(Unit) only ran once and missed those returns.
    // ON_RESUME also runs on cold start, so one observer covers both paths.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !busy) {
                scope.launch { reload() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("BYD Auto Sign") }) }
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("自动签到", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("初始化时授权微信，日常签到直接使用已保存的会话", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Section("环境状态") {
                    HealthLine("Root 运行环境", state.rootRuntimeInstalled,
                        if (state.rootRuntimeInstalled) "已部署到 /data/adb/byd_sign" else "需要初始化")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    HealthLine("定时调度（BusyBox crond）", state.cronRunning, state.cronStatus)
                    if (state.rootRuntimeInstalled && !state.cronRunning && !state.cronNeedsUpgrade) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { execute { bridge.restartScheduler() } }
                        ) { Text("修复定时调度") }
                        Text(
                            "修复后若仍显示未运行，可查看 Root 私有目录中的 logs/scheduler.log。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    HealthLine("Binder IPC", state.ipcConnected, state.ipcStatus)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    HealthLine("微信 Hook（仅授权阶段需要）", state.hookConnected && state.hookConfigured,
                        if (!state.hookConnected) "未连接：请检查 LSPosed 作用域并启动微信"
                        else if (!state.hookConfigured) "微信 " + state.hookVersion + "：版本映射未就绪"
                        else "已连接 · 微信 " + state.hookVersion)
                    if (state.hookConnected) {
                        Spacer(Modifier.height(8.dp))
                        val status = if (state.probeRunning) "后台动态探测中"
                            else if (state.hookSource == "dynamic") {
                                if (state.codeVerified) "DEX 动态映射 · 已取码验证"
                                else "DEX 动态映射 · 结构验证通过，等待实际取码"
                            } else if (state.hookConfigured) "内置映射 · 结构验证通过"
                            else "当前映射尚未就绪"
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        if (state.probeStatus.isNotBlank()) {
                            Text(state.probeStatus, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!state.hookConfigured && state.hookError.isNotBlank()) {
                            Text(state.hookError, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(enabled = !busy && !state.probeRunning,
                            onClick = { execute { bridge.probeNow() } }) {
                            Text("重新探测微信 DEX")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(state.diagnostic, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                execute {
                                    if (state.rootRuntimeInstalled) bridge.deployRuntimeOnly()
                                    else bridge.initialize()
                                }
                            }
                        ) {
                            Text(
                                if (state.rootRuntimeInstalled) "重新部署 Root 环境"
                                else "初始化并通过 Binder 授权"
                            )
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { scope.launch { busy = true; try { reload() } finally { busy = false } } }
                        ) {
                            Text("刷新")
                        }
                    }
                    if (!state.rootRuntimeInstalled) {
                        TextButton(enabled = !busy, onClick = { execute { bridge.deployRuntimeOnly() } }) {
                            Text("仅部署 Root 环境（不授权微信）")
                        }
                        Text(
                            "初始化部署 Root 调度后，通过 Binder 获取一次性微信 code；" +
                                "不会使用已移除的本地 HTTP 登录通道。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                Section("会话授权") {
                    Text(
                        "微信小程序 code 只在初始化/重新授权时获取，立即兑换为 BYD 会话并保存在 Root 私有目录。会话是否长期有效由服务端决定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    if (state.sessionSummary.isNotBlank()) {
                        SelectionContainer {
                            Text(state.sessionSummary, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Text("尚无会话信息", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        enabled = state.rootRuntimeInstalled && !busy,
                        onClick = { execute { bridge.authorize() } }
                    ) {
                        Text("通过 Binder 重新授权")
                    }
                    Text(
                        "当前 Binder 授权针对主微信（会话槽位 8088）。" +
                            "如果刚升级，请先更新 Root 环境；跨 User 微信分身仍需单独适配。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                Section("签到任务") {
                    Text("签到只使用缓存的 BYD session_id，不请求微信 code、不唤醒微信；会话失效后需重新授权。",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { execute { bridge.signNow() } },
                        enabled = state.rootRuntimeInstalled && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("立即签到")
                    }
                    if (busy) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item {
                Section("微信实例") {
                    OutlinedTextField(
                        value = ports,
                        onValueChange = { ports = it },
                        label = { Text("历史会话槽位（兼容旧数据）") },
                        supportingText = { Text("当前 Binder 授权仅支持主微信 8088；其他槽位只能使用已有会话") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("此设置仅影响 Root 会话/签到槽位，不是 IPC 端口；不能自动开启分身授权。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        enabled = state.rootRuntimeInstalled && !busy,
                        onClick = { execute { bridge.savePorts(ports) } }
                    ) {
                        Text("保存配置")
                    }
                }
            }
            if (resultText.isNotBlank()) {
                item {
                    Section("操作结果") {
                        SelectionContainer {
                            Text(resultText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (state.recentActivity.isNotBlank()) {
                item {
                    Section("最近日志") {
                        SelectionContainer {
                            Text(state.recentActivity, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Text(
                    "需要 Root（Magisk / KernelSU / APatch）和 LSPosed 完成初次授权。会话失效前，定时签到无需微信进程运行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun HealthLine(title: String, healthy: Boolean, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            if (healthy) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
            contentDescription = null,
            tint = if (healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

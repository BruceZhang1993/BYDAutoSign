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
    var forceWake by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }

    suspend fun reload() {
        val newState = bridge.refresh()
        state = newState
        ports = newState.ports
        forceWake = newState.forceForegroundWake
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

    LaunchedEffect(Unit) { reload() }

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
                Text("一个应用管理微信授权、Root 定时和签到任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Section("环境状态") {
                    HealthLine("Root 运行环境", state.rootRuntimeInstalled,
                        if (state.rootRuntimeInstalled) "已部署到 /data/adb/byd_sign" else "需要初始化")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    HealthLine("微信 Hook", state.hookConnected && state.hookConfigured,
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
                        Button(enabled = !busy, onClick = { execute { bridge.initialize() } }) {
                            Text(if (state.rootRuntimeInstalled) "重新部署" else "一键初始化")
                        }
                        OutlinedButton(enabled = !busy, onClick = { scope.launch { busy = true; try { reload() } finally { busy = false } } }) {
                            Text("刷新")
                        }
                    }
                }
            }
            item {
                Section("签到任务") {
                    Text("按计划于每日 00:01、07:01、13:01、22:01 执行；自动跳过已成功签到的账号。",
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
                        label = { Text("本地接口端口") },
                        supportingText = { Text("主微信默认 8088，分身用逗号分隔，例如 8088,8098") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = forceWake, onCheckedChange = { forceWake = it })
                        Column {
                            Text("允许短暂打开微信唤醒")
                            Text("默认关闭；仅在后台无法获取 code 时尝试开启",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        enabled = state.rootRuntimeInstalled && !busy,
                        onClick = { execute { bridge.savePorts(ports, forceWake) } }
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
                    "阶段 1 · 兼容现有签到脚本和 LSPosed Hook。需要 Root（Magisk / KernelSU / APatch）与 LSPosed。保留原有邮件、Webhook 和签到状态配置。",
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

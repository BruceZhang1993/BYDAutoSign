package com.brucezhang.bydautosign

import android.Manifest
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class SignPage { HOME, SETTINGS, HISTORY, DIAGNOSTICS }

/** Keep the primary result on the overview; configuration and technical details are secondary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Dashboard(manager: NativeSignManager, onExactPermission: () -> Unit) {
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val mutex = remember { Mutex() }
    val snackbars = remember { SnackbarHostState() }
    var page by remember { mutableStateOf(SignPage.HOME) }
    var state by remember { mutableStateOf(NativeUiState()) }
    var loaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    var times by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notificationsAllowed by remember { mutableStateOf(SignNotifier.allowed(context)) }

    // Regenerate the sanitized snapshot on result delivery: a document picker can
    // outlive a recreated Activity, so no report payload is held in remember state.
    val exportDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val fresh = manager.refresh()
                        val payload = DiagnosticReport.collect(context, fresh)
                        val out = context.contentResolver.openOutputStream(uri, "w")
                            ?: throw IOException("cannot open document")
                        out.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    }.isSuccess
                }
                snackbars.showSnackbar(if (saved) "脱敏诊断已导出" else "导出失败，请重新选择保存位置")
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationsAllowed = SignNotifier.allowed(context)
    }

    suspend fun refresh() {
        mutex.withLock {
            state = manager.refresh()
            enabled = state.enabled
            times = state.times
            loaded = true
        }
    }

    fun runAction(action: suspend () -> ActionResult) {
        if (busy) return
        scope.launch {
            busy = true
            val message = try {
                val result = action()
                refresh()
                result.message
            } catch (_: Exception) {
                "操作失败，请检查网络或系统权限"
            } finally {
                busy = false
            }
            scope.launch { snackbars.showSnackbar(message) }
        }
    }

    BackHandler(enabled = page != SignPage.HOME) {
        page = if (page == SignPage.DIAGNOSTICS) SignPage.SETTINGS else SignPage.HOME
    }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !busy) {
                notificationsAllowed = SignNotifier.allowed(context)
                scope.launch { refresh() }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(when (page) {
                        SignPage.HOME -> "BYD Auto Sign"
                        SignPage.SETTINGS -> "设置"
                        SignPage.HISTORY -> "签到记录"
                        SignPage.DIAGNOSTICS -> "运行诊断"
                    })
                },
                navigationIcon = {
                    if (page != SignPage.HOME) IconButton(onClick = {
                        page = if (page == SignPage.DIAGNOSTICS) SignPage.SETTINGS else SignPage.HOME
                    }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (page == SignPage.HOME) IconButton(onClick = { page = SignPage.SETTINGS }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "打开设置")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbars) }
    ) { padding ->
        if (!loaded) {
            Box(modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else when (page) {
            SignPage.HOME -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        TodaySummary(state, busy,
                            onAuthorize = { runAction { manager.authorize() } },
                            onSign = { runAction { manager.sign() } })
                    }
                    item {
                        OverviewHeading("最近记录", action = "查看全部", onAction = {
                            page = SignPage.HISTORY
                        })
                        Spacer(Modifier.height(8.dp))
                        SectionCard {
                            if (state.history.isEmpty()) {
                                Text("还没有签到记录", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium)
                            } else {
                                state.history.take(3).forEachIndexed { index, entry ->
                                    HistoryEntryRow(entry)
                                    if (index < minOf(3, state.history.size) - 1) HorizontalDivider()
                                }
                            }
                        }
                    }
                    item {
                        OverviewHeading("授权状态", action = "管理", onAction = {
                            page = SignPage.SETTINGS
                        })
                        Spacer(Modifier.height(8.dp))
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(
                                    if (state.sessionReady) Icons.Outlined.VerifiedUser
                                        else Icons.Outlined.Lock,
                                    contentDescription = null,
                                    tint = if (state.sessionReady) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(if (state.sessionReady) "会话已授权" else "需要初始化授权",
                                        style = MaterialTheme.typography.titleSmall)
                                    Text(when {
                                        state.sessionError.isNotBlank() -> state.sessionError
                                        state.sessionReady -> "已安全保存 · 签到不依赖微信在线"
                                        else -> "请先通过微信获取授权，才能自动签到"
                                    }, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item {
                        TextButton(onClick = { page = SignPage.SETTINGS },
                            modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Tune, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("调度、通知与连接设置")
                        }
                    }
                }
            }
            SignPage.SETTINGS -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        OverviewHeading("自动签到")
                        Spacer(Modifier.height(8.dp))
                        SectionCard {
                            Row(modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Outlined.Schedule, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("每日自动签到", style = MaterialTheme.typography.titleSmall)
                                    Text(if (enabled) "已开启" else "已关闭",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = enabled, onCheckedChange = { enabled = it })
                            }
                            HorizontalDivider()
                            OutlinedTextField(
                                value = times,
                                onValueChange = { times = it },
                                label = { Text("签到时间") },
                                supportingText = { Text("24 小时制；多个时间用逗号分隔，最多 8 个") },
                                placeholder = { Text("07:01,13:01,22:01") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(if (enabled) state.next else "关闭后将取消自动签到",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (enabled && !state.exact && Build.VERSION.SDK_INT >= 31) {
                                TextButton(onClick = onExactPermission) {
                                    Icon(Icons.Outlined.AccessTime, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("允许精确闹钟以减少延迟")
                                }
                            }
                            Button(
                                onClick = { runAction { manager.saveSchedule(enabled, times) } },
                                enabled = !busy && (enabled != state.enabled || times != state.times),
                                modifier = Modifier.align(Alignment.End)
                            ) { Text("保存更改") }
                        }
                    }
                    item {
                        OverviewHeading("结果通知")
                        Spacer(Modifier.height(8.dp))
                        SectionCard {
                            SettingsInfoRow(Icons.Outlined.Notifications,
                                "系统通知", if (notificationsAllowed) "已允许签到结果通知"
                                    else "当前无法显示签到结果通知")
                            Text("成功或最终失败时提醒；今天已签到和重试过程不提醒。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!notificationsAllowed && Build.VERSION.SDK_INT >= 33) {
                                TextButton(onClick = {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }) { Text("允许通知") }
                            } else if (!notificationsAllowed) {
                                Text("可在系统的应用通知设置中开启。",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item {
                        OverviewHeading("微信授权")
                        Spacer(Modifier.height(8.dp))
                        SectionCard {
                            SettingsInfoRow(Icons.Outlined.Security,
                                if (state.sessionReady) "已授权" else "尚未授权",
                                if (state.sessionReady) "会话已加密保存，日常签到无需启动微信"
                                    else "需要完成一次微信授权以启用签到")
                            if (state.sessionError.isNotBlank()) {
                                Text(state.sessionError, color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { runAction { manager.authorize() } }
                            ) { Text(if (state.sessionReady) "重新授权" else "初始化并授权") }
                            HorizontalDivider()
                            TextButton(onClick = { page = SignPage.DIAGNOSTICS }) {
                                Icon(Icons.Outlined.Build, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("连接与调度诊断")
                            }
                        }
                    }
                    item {
                        Text("LSPosed 仅用于手动授权。已停用 Root 签到运行链；旧 Root 任务需自行停用，以免重复签到。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                    }
                }
            }
            SignPage.HISTORY -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text("最近 " + state.history.size + " 次实际签到尝试 · 最多保留 100 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.history.isEmpty()) {
                        item { SectionCard { Text("尚无签到记录") } }
                    } else {
                        items(state.history) { entry ->
                            SectionCard { HistoryEntryRow(entry) }
                        }
                    }
                }
            }
            SignPage.DIAGNOSTICS -> {
                val trace = state.scheduleTrace
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        OverviewHeading("自动调度")
                        Spacer(Modifier.height(8.dp))
                        SectionCard {
                            DetailRow("下次计划", state.next)
                            DetailRow("上次闹钟唤醒", viewTime(trace.lastAlarmAt))
                            if (trace.lastAlarmAt > 0) {
                                DetailRow("闹钟触发延迟", (trace.lastAlarmDelayMs / 1000).toString() + " 秒")
                            }
                            DetailRow("最近任务提交", viewTime(trace.lastEnqueueAt))
                            DetailRow("最近补偿提交", viewTime(trace.lastCompensateAt))
                            DetailRow("最近开始执行", viewTime(trace.lastStartAt))
                            DetailRow("最近执行结束", viewTime(trace.lastFinishAt))
                            DetailRow("执行状态", trace.lastState)
                            DetailRow("调度自检", viewTime(trace.lastWatchdogAt))
                            DetailRow("最近调度修复", viewTime(trace.lastRecoveryAt))
                            if (trace.lastRecoverySource.isNotBlank()) {
                                DetailRow("修复原因", trace.lastRecoverySource + " · " + trace.lastRecoveryDetail)
                            }
                            if (trace.lastSuppressedAt > 0) {
                                DetailRow("最近自动阻断", viewTime(trace.lastSuppressedAt) +
                                    " · " + trace.lastSuppressedKind)
                            }
                            DetailRow("跨午夜响应次数", trace.crossDayAttempts.toString())
                        }
                    }
                    item {
                        OverviewHeading("微信连接")
                        Spacer(Modifier.height(8.dp))
                        SectionCard {
                            DetailRow("Binder", state.ipc.ifBlank { "未连接" })
                            DetailRow("Hook", if (state.hookReady) "微信 " + state.hookVersion + " · 就绪"
                                else "尚未就绪")
                            if (state.hookInfo.isNotBlank()) DetailRow("DEX 探测", state.hookInfo)
                            Text("微信连接状态只影响主动授权，不影响已保存会话的每日签到。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(enabled = !busy,
                                onClick = { runAction { manager.reprobe() } }) {
                                Text("重新探测微信 DEX")
                            }
                        }
                    }
                    item {
                        OverviewHeading("诊断报告")
                        Spacer(Modifier.height(8.dp))
                        SectionCard {
                            Text("导出仅包含时间、枚举错误类型、调度状态和权限摘要；不包含微信 code、BYD session、原始响应、Binder 原文或异常堆栈。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = {
                                exportDocument.launch("byd-diagnostics-" + LocalDate.now() + ".json")
                            }) {
                                Icon(Icons.Outlined.FileDownload, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("导出脱敏诊断")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodaySummary(
    state: NativeUiState, busy: Boolean, onAuthorize: () -> Unit, onSign: () -> Unit
) {
    val latestFailureToday = state.history.firstOrNull()?.let {
        it.status == SignRecordStatus.FAILED &&
            Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
    } == true
    val statusTitle = when {
        state.signedToday -> "今日已签到"
        !state.sessionReady -> "需要初始化授权"
        latestFailureToday -> "最近签到未成功"
        !state.enabled -> "自动签到已暂停"
        else -> "等待今日签到"
    }
    val statusDetail = when {
        state.signedToday -> "今日任务已完成，不再重复执行或提醒"
        !state.sessionReady -> "完成微信授权后，才能进行签到"
        latestFailureToday -> state.lastResult
        !state.enabled -> "定时任务已关闭，仍可手动签到"
        else -> "自动签到已准备就绪"
    }
    val bg = when {
        state.signedToday -> MaterialTheme.colorScheme.primaryContainer
        !state.sessionReady || latestFailureToday -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val fg = when {
        state.signedToday -> MaterialTheme.colorScheme.onPrimaryContainer
        !state.sessionReady || latestFailureToday -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(when {
                    state.signedToday -> Icons.Outlined.CheckCircle
                    !state.sessionReady || latestFailureToday -> Icons.Outlined.ErrorOutline
                    else -> Icons.Outlined.Schedule
                }, contentDescription = null, modifier = Modifier.size(38.dp), tint = fg)
                Column {
                    Text(statusTitle, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold, color = fg)
                    Text(statusDetail, style = MaterialTheme.typography.bodyMedium, color = fg)
                }
            }
            HorizontalDivider(color = fg.copy(alpha = 0.18f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("下次计划", style = MaterialTheme.typography.labelMedium, color = fg)
                    Text(if (state.enabled) viewTime(state.scheduleTrace.nextAt, true) else "未启用",
                        style = MaterialTheme.typography.titleMedium, color = fg)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("最近执行", style = MaterialTheme.typography.labelMedium, color = fg)
                    Text(if (state.lastRun.isNotBlank()) state.lastRun else "尚无记录",
                        style = MaterialTheme.typography.titleMedium, color = fg)
                }
            }
            if (!state.sessionReady) {
                Button(onClick = onAuthorize, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("初始化并授权")
                }
            } else {
                Button(onClick = onSign, enabled = !busy && !state.signedToday,
                    modifier = Modifier.fillMaxWidth()) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.signedToday) "今日已完成" else "立即签到")
                }
            }
        }
    }
}

@Composable
private fun OverviewHeading(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) {
            Text(action)
            Icon(Icons.Outlined.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content)
    }
}

@Composable
private fun SettingsInfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector,
                            title: String, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HistoryEntryRow(entry: SignHistoryEntry) {
    val success = entry.status != SignRecordStatus.FAILED
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (success) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.status.label + " · " + entry.origin.label,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(if (success) {
                if (entry.status == SignRecordStatus.ALREADY_SIGNED) "今日已签到，无重复提醒"
                else "签到成功"
            } else {
                entry.failure.label + " · 第 " + entry.attempt + " 次尝试"
            }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(viewTime(entry.timestamp, true),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun viewTime(millis: Long, compact: Boolean = false): String =
    if (millis <= 0L) "暂无记录" else DateTimeFormatter.ofPattern(
        if (compact) "MM-dd HH:mm" else "MM-dd HH:mm:ss"
    ).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

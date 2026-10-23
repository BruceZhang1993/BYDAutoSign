package com.brucezhang.bydautosign

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class ShellResult(val exitCode: Int, val output: String) {
    val ok: Boolean get() = exitCode == 0
}
data class DeviceState(
    val rootRuntimeInstalled: Boolean = false,
    val cronRunning: Boolean = false,
    val cronNeedsUpgrade: Boolean = false,
    val cronStatus: String = "",
    val hookConnected: Boolean = false,
    val ipcConnected: Boolean = false,
    val ipcStatus: String = "",
    val hookVersion: String = "",
    val hookConfigured: Boolean = false,
    val hookError: String = "",
    val hookSource: String = "",
    val probeStatus: String = "",
    val probeRunning: Boolean = false,
    val codeVerified: Boolean = false,
    val sessionSummary: String = "",
    val sessionReady: Boolean = false,
    val ports: String = "8088",
    val forceForegroundWake: Boolean = false,
    val recentActivity: String = "",
    val diagnostic: String = ""
)

/**
 * Root-only scheduler and sign engine; WeChat authorization uses Binder IPC.
 * Root runs independently of the application lifecycle.
 */
class RootBridge(private val context: Context) {
    private val rootDir = "/data/adb/byd_sign"
    private val bydAppId = "wxf62054ec313d6f53" // Must match byd_sign.sh and the Hook allowlist.

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun shell(command: String, timeoutSeconds: Long = 20, stdin: String? = null): ShellResult {
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (error: Exception) {
            return ShellResult(-1, "无法执行 su：" + (error.message ?: "请安装 Root 管理器"))
        }
        val out = StringBuilder()
        val reader = thread(name = "byd-su-reader", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        // Keep draining to avoid deadlocks, but bound memory usage.
                        if (out.length < 48_000) out.appendLine(line)
                    }
                }
            } catch (_: Exception) { }
        }
        if (stdin == null) {
            // Avoid keeping a stdin pipe open for background daemon launches.
            try { process.outputStream.close() } catch (_: Exception) { }
        }
        if (stdin != null) {
            try {
                // Secret never appears in process argv, URLs or shell output.
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(stdin)
                    writer.newLine()
                    writer.flush()
                }
            } catch (error: Exception) {
                process.destroyForcibly()
                return ShellResult(1, "Root 授权进程无法接收 IPC 凭证：" + error.javaClass.simpleName)
            }
        }
        val finished = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: InterruptedException) { false }
        if (!finished) {
            process.destroyForcibly()
            reader.join(1000)
            return ShellResult(124, "操作超时；请检查 Root 授权或查看签到日志。\n" + out)
        }
        reader.join(1000)
        return ShellResult(process.exitValue(), out.toString().trim())
    }

    /**
     * Deploy the Root runtime and cron scheduler without requesting WeChat
     * login. This permits a clean Binder IPC authorization on first install.
     */
    suspend fun deployRuntimeOnly(): ShellResult = withContext(Dispatchers.IO) {
        try {
            val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
                ?: return@withContext ShellResult(2, "不支持的 CPU 架构（仅 arm64-v8a、armeabi-v7a）")
            val stage = File(context.filesDir, "sign-runtime")
            if (stage.exists()) stage.deleteRecursively()
            if (!stage.mkdirs()) return@withContext ShellResult(2, "无法创建 Root 部署暂存目录")
            val names = listOf("byd_sign.sh", "lib.sh", "email.conf", "webui_api.sh", "root-boot.sh", "root-install.sh")
            for (name in names) {
                context.assets.open("runtime/" + name).use { source ->
                    File(stage, name).outputStream().use { target -> source.copyTo(target) }
                }
            }
            for (name in listOf("curl", "openssl")) {
                context.assets.open("runtime/bin/" + abi + "/" + name).use { source ->
                    File(stage, name).outputStream().use { target -> source.copyTo(target) }
                }
            }
            val result = shell(
                "sh " + quote(File(stage, "root-install.sh").absolutePath) +
                    " " + quote(stage.absolutePath), 60
            )
            if (result.ok) ShellResult(0, "Root 运行环境已部署，签到调度已配置。下一步请通过 Binder 授权。")
            else result
        } catch (e: Exception) {
            ShellResult(-1, "部署 Root 运行环境失败：" + e.javaClass.simpleName)
        }
    }

    /** Default authorization: Binder only, no HTTP fallback. */
    suspend fun initialize(): ShellResult {
        val deployment = deployRuntimeOnly()
        if (!deployment.ok) return deployment
        val authorization = authorize()
        return ShellResult(authorization.exitCode, deployment.output + "\n" + authorization.output)
    }

    suspend fun initializeViaBinder(): ShellResult = initialize()

    suspend fun authorize(): ShellResult = authorizeViaBinder()

    /**
     * Main-user Binder authorization; no localhost HTTP is used. Sign-only
     * cron/signNow() are untouched. Never log or display the one-time code.
     */
    suspend fun authorizeViaBinder(): ShellResult = withContext(Dispatchers.IO) {
        // Only an explicit user authorization may wake WeChat; cron never does.
        if (!com.brucezhang.bydautosign.ipc.IpcBrokerService.connected()) {
            shell("test -x " + rootDir + "/byd_sign.sh && /system/bin/sh " +
                rootDir + "/byd_sign.sh wake-wechat", 25)
            var attempts = 0
            while (attempts++ < 15 &&
                !com.brucezhang.bydautosign.ipc.IpcBrokerService.connected()) {
                delay(1000)
            }
        }
        val result = com.brucezhang.bydautosign.ipc.IpcBrokerService.obtainCode(bydAppId)
        if (!result.successful()) {
            return@withContext ShellResult(1, "Binder 获取微信登录凭证失败（状态 " + result.status +
                "）。原有会话保留；请检查微信 Hook 或重新探测 DEX。")
        }
        val rootResult = shell(
            "test -x " + rootDir + "/byd_sign.sh && /system/bin/sh " +
                rootDir + "/byd_sign.sh authorize-stdin 8088",
            120, result.code
        )
        if (!rootResult.ok) {
            ShellResult(rootResult.exitCode,
                "Binder 已获取登录凭证，但 Root 会话兑换失败。请先重新部署运行环境。\n" +
                    sanitize(rootResult.output))
        } else {
            ShellResult(0, "Binder 授权成功：主微信的 BYD 会话已安全持久化。")
        }
    }

    suspend fun savePorts(portsText: String): ShellResult = withContext(Dispatchers.IO) {
        val ports = PortConfig.normalize(portsText)
            ?: return@withContext ShellResult(2, "端口无效：请填写 1024–65535 范围的端口，以英文逗号分隔。")
        val file = File(context.filesDir, "code_api.pending.conf")
        file.writeText("ports=" + ports + "\nforce_foreground_wake=false\n")
        shell("test -d " + rootDir + " && cp " + quote(file.absolutePath) +
            " " + rootDir + "/code_api.conf && chmod 600 " + rootDir + "/code_api.conf")
    }

    /** Restart only BYDAutoSign's own Root crond, never other Root modules. */
    suspend fun restartScheduler(): ShellResult = withContext(Dispatchers.IO) {
        val start = shell("test -f /data/adb/service.d/byd_auto_sign.sh && " +
            "/system/bin/sh /data/adb/service.d/byd_auto_sign.sh", 18)
        // The launcher may already have printed CRON_STARTED while the original
        // command still holds a descriptor. Never treat that marker or a 124
        // timeout alone as proof of failure OR success: query the live daemon.
        val health = shell("/system/bin/sh /data/adb/service.d/byd_auto_sign.sh status", 8)
        val isRunning = health.ok &&
            health.output.lineSequence().any { it.trim() == "CRON_RUNNING" }
        val hasTask = health.output.contains("/data/adb/byd_sign/byd_sign.sh")
        if (isRunning && hasTask) {
            ShellResult(0, if (start.exitCode == 124) {
                "定时服务已运行且任务表有效。启动命令延迟退出（124），已通过独立状态检查确认。"
            } else "定时服务已运行且任务表有效，请刷新状态。")
        } else {
            val errors = shell("tail -n 25 /data/adb/byd_sign/logs/scheduler.log " +
                "2>/dev/null", 8).output.lineSequence()
                .filter { it.contains("CRON_ERROR:") || it.contains("CRON_FAILED") }
                .takeLastSafe(5).joinToString("\n").takeLast(1200)
            val reason = when {
                !health.ok && start.exitCode == 124 &&
                    start.output.contains("CRON_STARTED") ->
                    "脚本报告启动，但命令未正常退出，且独立状态检查失败。"
                health.exitCode == 124 ->
                    "独立状态查询也超时（124），无法判断 crond 是否运行。"
                !health.ok -> "未检测到运行中的定时服务。"
                !hasTask -> "定时服务已运行，但任务表没有 BYD 签到命令。"
                else -> "定时服务状态异常。"
            }
            ShellResult(if (start.exitCode == 124) 124 else 1,
                reason + if (errors.isNotBlank()) "\n" + errors else
                    "\n启动结果：" + start.output.takeLast(500) +
                        "\n检查结果：" + health.output.takeLast(500))
        }
    }

    suspend fun signNow(): ShellResult = withContext(Dispatchers.IO) {
        shell("test -x " + rootDir + "/byd_sign.sh && /system/bin/sh " +
            rootDir + "/byd_sign.sh nonauto", 240)
    }

    suspend fun refresh(): DeviceState = withContext(Dispatchers.IO) {
        val runtime = shell("test -x " + rootDir + "/byd_sign.sh && test -x " +
            rootDir + "/curl && test -x " + rootDir + "/openssl && echo READY", 10)
        val installed = runtime.ok && runtime.output.contains("READY")
        // Older installed root-boot.sh ignored "status" and would restart
        // crond on every page refresh. Never call it before checking support.
        val scheduler = if (installed) {
            shell("if grep -q CRON_RUNNING /data/adb/service.d/byd_auto_sign.sh " +
                "2>/dev/null; then /system/bin/sh /data/adb/service.d/byd_auto_sign.sh status; " +
                "else echo CRON_LEGACY; fi", 10)
        } else ShellResult(1, "尚未部署")
        val cronLegacy = scheduler.output.contains("CRON_LEGACY")
        val cronOk = scheduler.ok && scheduler.output.lineSequence().any { it.trim() == "CRON_RUNNING" }
        val cronError = if (installed && !cronOk && !cronLegacy) {
            shell("tail -n 20 /data/adb/byd_sign/logs/scheduler.log 2>/dev/null", 10)
                .output.lineSequence().lastOrNull { it.contains("CRON_ERROR:") }
                ?.substringAfter("CRON_ERROR:")?.trim()?.take(160).orEmpty()
        } else ""
        val cronSummary = when {
            !installed -> "先部署 Root 环境"
            cronLegacy -> "调度脚本版本较旧，请先点击“重新部署 Root 环境”"
            scheduler.exitCode == 124 -> "定时状态检查超时（124），目前无法判断 crond 是否运行"
            !cronOk -> if (cronError.isNotBlank()) {
                "定时服务未运行：" + cronError
            } else "定时服务未运行，请点击“修复定时调度”"
            !scheduler.output.contains("byd_sign.sh") -> "crond 运行中，但任务表没有签到命令"
            else -> "crond 运行中 · 默认 00:01 / 07:01 / 13:01 / 22:01（以任务表为准）"
        }
        val config = if (installed) shell("cat " + rootDir + "/code_api.conf", 10) else ShellResult(-1, "")
        val parsed = PortConfig.fromFile(config.output)
        val hook = checkHook()
        // Read only redacted status; never read or display session_id contents.
        val sessionStatus = if (installed) shell("sh " + rootDir + "/byd_sign.sh status", 10)
            else ShellResult(-1, "")
        val logs = if (installed) {
            shell("tail -n 20 " + rootDir + "/logs/sign_\$(date +%Y-%m-%d).log 2>/dev/null", 10)
        } else ShellResult(-1, "")
        DeviceState(
            rootRuntimeInstalled = installed,
            cronRunning = cronOk && scheduler.output.contains("byd_sign.sh"),
            cronNeedsUpgrade = cronLegacy,
            cronStatus = cronSummary,
            hookConnected = hook.connected,
            ipcConnected = com.brucezhang.bydautosign.ipc.IpcBrokerService.connected(),
            ipcStatus = com.brucezhang.bydautosign.ipc.IpcBrokerService.diagnostic(),
            hookVersion = hook.version,
            hookConfigured = hook.configured,
            hookError = hook.error,
            hookSource = hook.source,
            probeStatus = hook.status,
            probeRunning = hook.running,
            codeVerified = hook.verified,
            sessionSummary = if (sessionStatus.ok) sessionStatus.output else "",
            sessionReady = sessionStatus.ok && sessionStatus.output.contains("已初始化会话"),
            ports = parsed.first,
            forceForegroundWake = parsed.second,
            recentActivity = sanitize(logs.output),
            diagnostic = if (installed) "定时签到只使用已保存的 BYD 会话，不会自动唤醒微信；会话失效时需要手动重新授权。" else
                "尚未初始化 Root 任务。请授予 Root 权限后点击初始化。"
        )
    }

    private data class HookProbe(
        val connected: Boolean,
        val version: String = "",
        val configured: Boolean = false,
        val error: String = "",
        val source: String = "",
        val status: String = "",
        val running: Boolean = false,
        val verified: Boolean = false
    )

    suspend fun probeNow(): ShellResult = withContext(Dispatchers.IO) {
        if (com.brucezhang.bydautosign.ipc.IpcBrokerService.requestProbe()) {
            ShellResult(0, "已通过 Binder 请求微信 DEX 探测，请稍后刷新")
        } else {
            ShellResult(1, "微信 Binder 未连接，无法请求 DEX 探测")
        }
    }

    private fun checkHook(): HookProbe {
        return try {
            val data = com.brucezhang.bydautosign.ipc.IpcBrokerService.readHookStatus()
                ?: return HookProbe(false)
            val json = JSONObject(data)
            val ok = json.optString("packageName") == "com.tencent.mm"
            HookProbe(
                connected = ok,
                version = json.optString("version", ""),
                configured = ok && json.optBoolean("configSupported", false),
                error = json.optString("configError", ""),
                source = json.optString("configSource", ""),
                status = json.optString("probeStatus", ""),
                running = json.optBoolean("probeRunning", false),
                verified = json.optBoolean("codeVerified", false)
            )
        } catch (_: Exception) {
            HookProbe(false)
        }
    }

    private fun sanitize(output: String): String {
        return output.lineSequence().filter {
            // Raw upstream logs can contain session and decrypted API responses.
            !it.contains("原始响应") && !it.contains("session_id:") &&
                !it.contains("获取session响应:") && !it.contains("签到响应:")
        }.takeLastSafe(15).joinToString("\n").takeLast(4000)
    }

    private fun Sequence<String>.takeLastSafe(count: Int): List<String> = toList().takeLast(count)
}

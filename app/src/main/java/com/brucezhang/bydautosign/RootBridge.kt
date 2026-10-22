package com.brucezhang.bydautosign

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
 * Stage-1 compatibility bridge: install original byd_sign scripts and pinned executables,
 * and retain the original local wxcode API contract. Root runs outside the app lifecycle.
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

    /** Legacy HTTP authorization remains available as an explicit fallback. */
    suspend fun initialize(): ShellResult {
        val deployment = deployRuntimeOnly()
        if (!deployment.ok) return deployment
        val auth = authorize()
        return ShellResult(
            auth.exitCode,
            deployment.output + "\\n" +
                if (auth.ok) "旧通道会话初始化成功。" else
                    "Root 环境已部署，但旧通道授权失败。可以单独尝试 Binder 授权。"
        )
    }

    /** Preferred experimental first-run workflow; never falls back to HTTP silently. */
    suspend fun initializeViaBinder(): ShellResult {
        val deployment = deployRuntimeOnly()
        if (!deployment.ok) return deployment
        val auth = authorizeViaBinder()
        return ShellResult(
            auth.exitCode,
            deployment.output + "\\n" + auth.output
        )
    }

    suspend fun authorize(): ShellResult = withContext(Dispatchers.IO) {
        shell("test -x " + rootDir + "/byd_sign.sh && sh " +
            rootDir + "/byd_sign.sh authorize", 210)
    }

    /**
     * Experimental main-user Binder authorization. Existing authorize() and
     * cron/signNow() are untouched. Never log or display the one-time code.
     */
    suspend fun authorizeViaBinder(): ShellResult = withContext(Dispatchers.IO) {
        val result = com.brucezhang.bydautosign.ipc.IpcBrokerService.obtainCode(bydAppId)
        if (!result.successful()) {
            return@withContext ShellResult(1, "Binder 获取微信登录凭证失败（状态 " + result.status +
                "）。原有会话不变，可继续使用旧授权。")
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
        shell("test -f /data/adb/service.d/byd_auto_sign.sh && " +
            "/system/bin/sh /data/adb/service.d/byd_auto_sign.sh", 30)
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
        try {
            val conn = URL("http://127.0.0.1:8088/probe").openConnection() as HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            try {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                ShellResult(0, "DEX 探测任务已请求：" + json.optString("probeStatus", "等待执行") +
                    "\n探测在微信后台线程运行，请稍后刷新查看结果。")
            } finally {
                conn.disconnect()
            }
        } catch (error: Exception) {
            ShellResult(1, "无法请求微信 DEX 探测，请检查 Hook 是否启动：" + error.javaClass.simpleName)
        }
    }

    private fun checkHook(): HookProbe {
        return try {
            val connection = URL("http://127.0.0.1:8088/whoami").openConnection() as HttpURLConnection
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            try {
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
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
            } finally {
                connection.disconnect()
            }
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

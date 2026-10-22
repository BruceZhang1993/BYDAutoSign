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
    val hookConnected: Boolean = false,
    val hookVersion: String = "",
    val hookConfigured: Boolean = false,
    val hookError: String = "",
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

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun shell(command: String, timeoutSeconds: Long = 20): ShellResult {
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

    suspend fun initialize(): ShellResult = withContext(Dispatchers.IO) {
        try {
            val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
                ?: return@withContext ShellResult(2, "不支持的 CPU 架构（仅 arm64-v8a、armeabi-v7a）")
            val stage = File(context.filesDir, "sign-runtime")
            if (stage.exists()) stage.deleteRecursively()
            stage.mkdirs()
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
            val result = shell("sh " + quote(File(stage, "root-install.sh").absolutePath) +
                " " + quote(stage.absolutePath), 60)
            result
        } catch (e: Exception) {
            ShellResult(-1, "初始化失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    suspend fun savePorts(portsText: String, forceWake: Boolean): ShellResult = withContext(Dispatchers.IO) {
        val ports = PortConfig.normalize(portsText)
            ?: return@withContext ShellResult(2, "端口无效：请填写 1024–65535 范围的端口，以英文逗号分隔。")
        val file = File(context.filesDir, "code_api.pending.conf")
        file.writeText("ports=" + ports + "\nforce_foreground_wake=" + forceWake + "\n")
        shell("test -d " + rootDir + " && cp " + quote(file.absolutePath) +
            " " + rootDir + "/code_api.conf && chmod 600 " + rootDir + "/code_api.conf")
    }

    suspend fun signNow(): ShellResult = withContext(Dispatchers.IO) {
        shell("test -x " + rootDir + "/byd_sign.sh && /system/bin/sh " +
            rootDir + "/byd_sign.sh nonauto", 240)
    }

    suspend fun refresh(): DeviceState = withContext(Dispatchers.IO) {
        val runtime = shell("test -x " + rootDir + "/byd_sign.sh && test -x " +
            rootDir + "/curl && test -x " + rootDir + "/openssl && echo READY", 10)
        val installed = runtime.ok && runtime.output.contains("READY")
        val config = if (installed) shell("cat " + rootDir + "/code_api.conf", 10) else ShellResult(-1, "")
        val parsed = PortConfig.fromFile(config.output)
        val hook = checkHook()
        val logs = if (installed) {
            shell("tail -n 20 " + rootDir + "/logs/sign_\$(date +%Y-%m-%d).log 2>/dev/null", 10)
        } else ShellResult(-1, "")
        DeviceState(
            rootRuntimeInstalled = installed,
            hookConnected = hook.connected,
            hookVersion = hook.version,
            hookConfigured = hook.configured,
            hookError = hook.error,
            ports = parsed.first,
            forceForegroundWake = parsed.second,
            recentActivity = sanitize(logs.output),
            diagnostic = if (installed) "Root 调度目录已部署；每日 00:01、07:01、13:01、22:01 自动尝试。" else
                "尚未初始化 Root 任务。请授予 Root 权限后点击初始化。"
        )
    }

    private data class HookProbe(
        val connected: Boolean,
        val version: String = "",
        val configured: Boolean = false,
        val error: String = ""
    )

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
                    error = json.optString("configError", "")
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

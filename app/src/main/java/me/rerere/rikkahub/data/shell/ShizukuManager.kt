package me.rerere.rikkahub.data.shell

import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.ByteArrayOutputStream
import java.io.InputStream

object ShizukuManager {

    private const val TAG = "ShizukuManager"
    private const val TX_NEW_PROCESS = 1
    private const val DESCRIPTOR = "rikka.shizuku.IUserService"
    private const val MAX_OUTPUT_CHARS = 60_000

    const val REQUEST_CODE = 0x5A12

    fun isBinderAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        isBinderAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun shizukuVersion(): String = runCatching {
        if (isBinderAvailable()) Shizuku.getVersion().toString() else "-"
    }.getOrDefault("-")

    fun requestPermission() {
        runCatching {
            if (!isBinderAvailable() || hasPermission()) return
            Shizuku.requestPermission(REQUEST_CODE)
        }.onFailure { Log.w(TAG, "requestPermission failed", it) }
    }

    fun registerPermissionListener(onGranted: () -> Unit): () -> Unit {
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) onGranted()
        }
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
        return { runCatching { Shizuku.removeRequestPermissionResultListener(listener) } }
    }

    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val ok: Boolean get() = exitCode == 0
    }

    suspend fun exec(
        command: String,
        timeoutMs: Long = 30_000L,
        workDir: String = "/sdcard",
    ): ShellResult = withContext(Dispatchers.IO) {
        if (!isBinderAvailable()) {
            return@withContext ShellResult(-1, "", "Shizuku service is not running. Please start Shizuku.")
        }
        if (!hasPermission()) {
            return@withContext ShellResult(-1, "", "Shizuku permission not granted.")
        }
        runCatching {
            runProcess(arrayOf("sh", "-c", command), workDir, timeoutMs)
        }.getOrElse { e ->
            Log.w(TAG, "exec failed", e)
            ShellResult(-1, "", "Failed to execute: ${e.message}")
        }
    }

    private fun runProcess(
        cmd: Array<String>,
        workDir: String,
        timeoutMs: Long,
    ): ShellResult {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        var process: ShizukuRemoteProcess? = null
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeStringArray(cmd)
            data.writeInt(-1)
            data.writeString(workDir)
            data.writeStrongBinder(null)

            val binder: IBinder = Shizuku.getBinder()
            binder.transact(TX_NEW_PROCESS, data, reply, 0)
            reply.readException()

            @Suppress("DEPRECATION")
            process = reply.readParcelable(ShizukuRemoteProcess::class.java.classLoader)

            val p = process ?: return ShellResult(-1, "", "Failed to create remote process")

            val outBuf = ByteArrayOutputStream()
            val errBuf = ByteArrayOutputStream()
            val outThread = Thread { runCatching { p.inputStream.copyLimitedTo(outBuf) } }.apply {
                isDaemon = true
                start()
            }
            val errThread = Thread { runCatching { p.errorStream.copyLimitedTo(errBuf) } }.apply {
                isDaemon = true
                start()
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            var exit: Int? = null
            while (System.currentTimeMillis() < deadline) {
                exit = runCatching { p.exitValue() }.getOrNull()
                if (exit != null) break
                Thread.sleep(40)
            }

            if (exit == null) {
                runCatching { p.destroy() }
                outThread.join(1_000)
                errThread.join(1_000)
                return ShellResult(
                    exitCode = -1,
                    stdout = outBuf.toString(Charsets.UTF_8.name()),
                    stderr = "Command timed out after ${timeoutMs}ms",
                )
            }

            outThread.join(1_500)
            errThread.join(1_500)
            return ShellResult(
                exitCode = exit,
                stdout = outBuf.toString(Charsets.UTF_8.name()),
                stderr = errBuf.toString(Charsets.UTF_8.name()),
            )
        } finally {
            runCatching { process?.destroy() }
            runCatching { data.recycle() }
            runCatching { reply.recycle() }
        }
    }

    private fun InputStream.copyLimitedTo(output: ByteArrayOutputStream) {
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (total < MAX_OUTPUT_CHARS) {
            val n = read(buf)
            if (n <= 0) break
            val allowed = minOf(n, MAX_OUTPUT_CHARS - total)
            output.write(buf, 0, allowed)
            total += allowed
            if (allowed < n) break
        }
        output.flush()
    }

    suspend fun awaitPermission(timeoutMs: Long = 20_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasPermission()) return true
            delay(200)
        }
        return hasPermission()
    }
}

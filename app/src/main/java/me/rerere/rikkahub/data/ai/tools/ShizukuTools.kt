package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.shell.ShizukuManager

private const val DEFAULT_TIMEOUT_MS = 30_000
private const val MAX_TIMEOUT_MS = 300_000L

val shizukuShellTool by lazy {
    Tool(
        name = "run_shell",
        description = "Execute a shell command on this Android device through Shizuku, which runs with ADB-equivalent (uid 2000 shell) privileges. Supports pipes and redirection. Returns exit_code, stdout and stderr.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Shell command to run, e.g. 'pm list packages | grep rikka'")
                    })
                    put("timeout_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "Timeout in milliseconds. Default 30000, max 300000.")
                    })
                    put("work_dir", buildJsonObject {
                        put("type", "string")
                        put("description", "Working directory. Default /sdcard.")
                    })
                },
                required = listOf("command")
            )
        },
        execute = {
            val obj = it.jsonObject
            val command = obj["command"]?.jsonPrimitive?.contentOrNull.orEmpty()

            if (command.isBlank()) {
                return@execute listOf(UIMessagePart.Text("""{"error":"command is empty"}"""))
            }
            if (!ShizukuManager.isBinderAvailable()) {
                return@execute listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", JsonPrimitive("shizuku_not_running"))
                            put("hint", JsonPrimitive("Shizuku service is not running. Ask the user to start Shizuku then retry."))
                        }.toString()
                    )
                )
            }
            if (!ShizukuManager.hasPermission()) {
                ShizukuManager.requestPermission()
                val granted = ShizukuManager.awaitPermission(15_000)
                if (!granted) {
                    return@execute listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", JsonPrimitive("shizuku_permission_denied"))
                                put("hint", JsonPrimitive("Permission request was sent. Ask the user to tap Allow in Shizuku."))
                            }.toString()
                        )
                    )
                }
            }

            val timeout = (obj["timeout_ms"]?.jsonPrimitive?.intOrNull ?: DEFAULT_TIMEOUT_MS)
                .coerceIn(1_000, MAX_TIMEOUT_MS.toInt())
                .toLong()
            val workDir = obj["work_dir"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "/sdcard"

            val result = ShizukuManager.exec(command = command, timeoutMs = timeout, workDir = workDir)

            val payload = buildJsonObject {
                put("exit_code", JsonPrimitive(result.exitCode))
                put("success", JsonPrimitive(result.ok))
                if (result.stdout.isNotBlank()) put("stdout", JsonPrimitive(result.stdout))
                if (result.stderr.isNotBlank()) put("stderr", JsonPrimitive(result.stderr))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
}

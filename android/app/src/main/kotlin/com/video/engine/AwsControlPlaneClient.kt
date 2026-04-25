package com.video.engine

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class AwsQueuedCommand(
    val installationId: String,
    val commandId: String,
    val command: String,
)

class AwsControlPlaneClient(
    context: Context,
) {
    companion object {
        private const val TAG = "[AwsCtl]"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 10_000
    }

    private val appContext = context.applicationContext
    private val baseUrl = appContext.getString(R.string.aws_control_plane_url).trim().trimEnd('/')
    private val deviceKey = appContext.getString(R.string.aws_control_plane_device_key).trim()

    fun isConfigured(): Boolean = baseUrl.isNotBlank() && deviceKey.isNotBlank()

    @WorkerThread
    fun postHeartbeat(payload: JSONObject): Boolean {
        if (!isConfigured()) return false
        return runCatching {
            requestJson(
                method = "POST",
                path = "/device/heartbeat",
                body = payload,
            )
            true
        }.getOrElse { error ->
            Log.w(TAG, "AWS heartbeat failed: ${error.message}")
            false
        }
    }

    @WorkerThread
    fun pollQueuedCommands(installationId: String): List<AwsQueuedCommand> {
        if (!isConfigured() || installationId.isBlank()) return emptyList()
        return runCatching {
            val encodedId = java.net.URLEncoder.encode(installationId, Charsets.UTF_8.name())
            val root = requestJson(
                method = "GET",
                path = "/device/commands?installationId=$encodedId",
            )
            val commands = root.optJSONArray("commands") ?: JSONArray()
            buildList {
                for (index in 0 until commands.length()) {
                    val item = commands.optJSONObject(index) ?: continue
                    val commandId = item.optString("commandId").trim()
                    val command = item.optString("command").trim()
                    val targetInstallationId = item.optString("installationId").trim()
                    if (commandId.isBlank() || command.isBlank() || targetInstallationId.isBlank()) continue
                    add(
                        AwsQueuedCommand(
                            installationId = targetInstallationId,
                            commandId = commandId,
                            command = command,
                        ),
                    )
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "AWS command poll failed: ${error.message}")
            emptyList()
        }
    }

    @WorkerThread
    fun ackCommand(
        installationId: String,
        commandId: String,
        status: String,
        resultMessage: String,
    ): Boolean {
        if (!isConfigured() || installationId.isBlank() || commandId.isBlank()) return false
        return runCatching {
            requestJson(
                method = "POST",
                path = "/device/commands/ack",
                body =
                    JSONObject()
                        .put("installationId", installationId)
                        .put("commandId", commandId)
                        .put("status", status)
                        .put("handledBySource", "android-client")
                        .put("resultMessage", resultMessage.take(240)),
            )
            true
        }.getOrElse { error ->
            Log.w(TAG, "AWS command ack failed: ${error.message}")
            false
        }
    }

    private fun requestJson(
        method: String,
        path: String,
        body: JSONObject? = null,
    ): JSONObject {
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-device-key", deviceKey)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body.toString())
                }
            }
            val code = connection.responseCode
            val stream =
                if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
            val raw =
                stream?.use { input ->
                    BufferedReader(input.reader(Charsets.UTF_8)).use { reader -> reader.readText() }
                }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("AWS control plane HTTP $code ${raw.take(240)}")
            }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }
}

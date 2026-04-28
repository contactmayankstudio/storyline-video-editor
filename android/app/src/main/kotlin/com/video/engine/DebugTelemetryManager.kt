package com.video.engine

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class DebugTelemetryManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "[DebugTelemetry]"
        private const val FIREBASE_EVENT_PREFIX = "dbg_"
    }

    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "debug-telemetry-writer").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val sessionTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    val sessionId: String = "${sessionTimestamp}_${UUID.randomUUID().toString().take(8)}"
    val sessionDir: File = File(context.getExternalFilesDir(null), "telemetry/$sessionId").apply { mkdirs() }
    private val snapshotsDir = File(sessionDir, "snapshots").apply { mkdirs() }
    private val eventsFile = File(sessionDir, "events.jsonl")
    private val metadataFile = File(sessionDir, "session.json")
    private val firebaseTelemetryEnabled = context.resources.getBoolean(R.bool.storyline_runtime_ops_enabled)
    private val firebaseAnalytics: FirebaseAnalytics? =
        if (firebaseTelemetryEnabled) {
            runCatching { Firebase.analytics }.getOrNull()
        } else {
            null
        }

    init {
        writeSessionMetadata()
        record("session", "start", mapOf(
            "sessionId" to sessionId,
            "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "androidVersion" to Build.VERSION.RELEASE,
            "sdkInt" to Build.VERSION.SDK_INT,
        ))
    }

    fun record(category: String, name: String, payload: Map<String, Any?> = emptyMap()) {
        record(category, name, toJsonObject(payload))
    }

    fun record(category: String, name: String, payload: JSONObject) {
        if (closed.get()) return
        val event = JSONObject()
            .put("timestampMs", System.currentTimeMillis())
            .put("category", category)
            .put("name", name)
            .put("payload", payload)
        enqueueLine(event.toString())
        sendToFirebase(category, name, payload)
    }

    fun recordNativeBridgeTelemetry(telemetry: NativeBridge.CommandTelemetry) {
        val payload = JSONObject()
            .put("phase", telemetry.phase)
            .put("action", telemetry.action)
            .put("async", telemetry.async)
            .put("durationMs", telemetry.durationMs)
            .put("success", telemetry.success)
            .put("message", telemetry.message)
        telemetry.params?.let { payload.put("params", JSONObject(it.toString())) }
        telemetry.result?.let { payload.put("result", JSONObject(it.toString())) }
        record("native_bridge", telemetry.phase, payload)
    }

    fun captureSnapshot(label: String, snapshot: JSONObject): File {
        val sanitized = sanitizeLabel(label)
        val file = File(snapshotsDir, "${System.currentTimeMillis()}_${sanitized}.json")
        if (!closed.get()) {
            writerExecutor.execute {
                runCatching {
                    file.writeText(snapshot.toString(2))
                    val payload = JSONObject()
                        .put("label", label)
                        .put("fileName", file.name)
                        .put("absolutePath", file.absolutePath)
                    appendLine(JSONObject()
                        .put("timestampMs", System.currentTimeMillis())
                        .put("category", "snapshot")
                        .put("name", "captured")
                        .put("payload", payload)
                        .toString())
                }.onFailure { Log.w(TAG, "Failed to write snapshot $label: ${it.message}") }
            }
        }
        return file
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        appendLine(JSONObject()
            .put("timestampMs", System.currentTimeMillis())
            .put("category", "session")
            .put("name", "stop")
            .put("payload", JSONObject().put("sessionId", sessionId))
            .toString())
        writerExecutor.shutdown()
        runCatching { writerExecutor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    // Send key events to Firebase Analytics for remote debugging
    private fun sendToFirebase(category: String, name: String, payload: JSONObject) {
        val analytics = firebaseAnalytics ?: return
        // Only send important events to avoid quota limits
        val rawEventName = "${FIREBASE_EVENT_PREFIX}${category}_${name}"
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_]"), "_")
        val eventName = rawEventName
            .trim('_')
            .let { if (it.firstOrNull()?.isLetter() == true) it else "${FIREBASE_EVENT_PREFIX}event" }
            .take(40)
        val bundle = Bundle()
        bundle.putString("category", category)
        bundle.putString("event_name", name)
        bundle.putString("session_id", sessionId.take(36))

        // Add all payload fields as bundle params (Firebase limit: 25 params, 100 char values)
        runCatching {
            payload.keys().forEach { key ->
                val value = payload.opt(key) ?: return@forEach
                val safeKey = key.take(40).replace(Regex("[^a-zA-Z0-9_]"), "_")
                when (value) {
                    is Int -> bundle.putLong(safeKey, value.toLong())
                    is Long -> bundle.putLong(safeKey, value)
                    is Double -> bundle.putDouble(safeKey, value)
                    is Float -> bundle.putDouble(safeKey, value.toDouble())
                    is Boolean -> bundle.putString(safeKey, value.toString())
                    else -> bundle.putString(safeKey, value.toString().take(100))
                }
            }
        }
        analytics.logEvent(eventName, bundle)
    }

    private fun writeSessionMetadata() {
        val metadata = JSONObject()
            .put("sessionId", sessionId)
            .put("createdAtMs", System.currentTimeMillis())
            .put("app", JSONObject()
                .put("packageName", context.packageName)
                .put("versionCode", packageInfoValue { longVersionCode })
                .put("versionName", packageInfoValue { versionName } ?: "unknown"))
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("product", Build.PRODUCT)
                .put("androidVersion", Build.VERSION.RELEASE)
                .put("sdkInt", Build.VERSION.SDK_INT))
        runCatching { metadataFile.writeText(metadata.toString(2)) }
            .onFailure { Log.w(TAG, "Failed to write telemetry metadata: ${it.message}") }
    }

    private fun enqueueLine(line: String) {
        writerExecutor.execute { appendLine(line) }
    }

    private fun appendLine(line: String) {
        try {
            FileWriter(eventsFile, true).use { it.append(line).append('\n') }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to append telemetry event: ${e.message}")
        }
    }

    private fun sanitizeLabel(label: String) =
        label.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "snapshot" }

    private fun toJsonObject(map: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        map.forEach { (key, value) -> json.put(key, normalizeValue(value)) }
        return json
    }

    private fun normalizeValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is JSONObject -> value
        is Number, is Boolean, is String -> value
        is Iterable<*> -> org.json.JSONArray().apply { value.forEach { put(normalizeValue(it)) } }
        is Array<*> -> org.json.JSONArray().apply { value.forEach { put(normalizeValue(it)) } }
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (k, v) -> if (k != null) put(k.toString(), normalizeValue(v)) }
        }
        else -> value.toString()
    }

    private fun <T> packageInfoValue(selector: android.content.pm.PackageInfo.() -> T?): T? =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).selector()
        }.getOrNull()
}

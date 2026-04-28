package com.video.engine

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.ktx.Firebase
import com.google.firebase.perf.ktx.performance
import com.google.firebase.perf.metrics.Trace
import com.google.firebase.remoteconfig.ConfigUpdate
import com.google.firebase.remoteconfig.ConfigUpdateListener
import com.google.firebase.remoteconfig.ConfigUpdateListenerRegistration
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class OpsReporter(
    context: Context,
    private val sessionIdProvider: () -> String,
    private val onRemoteConfigApplied: ((Set<String>) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "[OpsReporter]"
        private const val DEFAULT_HEARTBEAT_INTERVAL_MS = 45_000L
        private const val MIN_HEARTBEAT_INTERVAL_MS = 15_000L
        private const val MAX_HEARTBEAT_INTERVAL_MS = 180_000L
        private const val MAX_ATTRIBUTE_LENGTH = 32
        private const val EVENT_NAME = "ops_action"
    }

    private data class ActiveTrace(
        val trace: Trace,
        val startedAtMs: Long,
    )

    private val opsEnabled = context.resources.getBoolean(R.bool.storyline_runtime_ops_enabled)
    private val analytics: FirebaseAnalytics? =
        if (opsEnabled) runCatching { Firebase.analytics }.getOrNull() else null
    private val crashlytics =
        if (opsEnabled) runCatching { FirebaseCrashlytics.getInstance() }.getOrNull() else null
    private val remoteConfig: FirebaseRemoteConfig? =
        if (opsEnabled) runCatching { Firebase.remoteConfig }.getOrNull() else null
    private val activeTraces = ConcurrentHashMap<String, ActiveTrace>()
    private var configListenerRegistration: ConfigUpdateListenerRegistration? = null

    @Volatile private var heartbeatIntervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS
    @Volatile private var perfTracingEnabled = true
    @Volatile private var forceFlushActions = defaultForceFlushActions()

    init {
        if (opsEnabled) {
            configureRemoteConfig()
        }
    }

    fun heartbeatIntervalMs(): Long = heartbeatIntervalMs

    fun shouldForceFlush(action: String): Boolean = action in forceFlushActions

    fun noteAction(
        action: String,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        if (!opsEnabled) return
        val sanitizedAction = sanitizeEventValue(action)
        val bundle = Bundle().apply {
            putString("action", sanitizedAction)
            putString("session_id", sessionIdProvider().take(36))
            metadata.forEach { (key, value) ->
                val safeKey = sanitizeMetricName(key)
                when (value) {
                    is Int -> putLong(safeKey, value.toLong())
                    is Long -> putLong(safeKey, value)
                    is Double -> putDouble(safeKey, value)
                    is Float -> putDouble(safeKey, value.toDouble())
                    is Boolean -> putString(safeKey, value.toString())
                    null -> Unit
                    else -> putString(safeKey, value.toString().take(100))
                }
            }
        }
        analytics?.logEvent(EVENT_NAME, bundle)
        crashlytics?.log(
            buildString {
                append("ops_action=")
                append(sanitizedAction)
                if (metadata.isNotEmpty()) {
                    append(" ")
                    append(metadata.entries.joinToString(" ") { "${it.key}=${it.value}" })
                }
            },
        )
    }

    fun startTrace(
        slot: String,
        traceName: String,
        attributes: Map<String, String> = emptyMap(),
    ) {
        if (!opsEnabled || !perfTracingEnabled) return
        stopTrace(slot, success = false, attributes = mapOf("replaced" to "true"))
        val trace = runCatching { Firebase.performance.newTrace(sanitizeTraceName(traceName)) }.getOrNull() ?: return
        trace.putAttribute("session", sanitizeEventValue(sessionIdProvider()))
        attributes.forEach { (key, value) ->
            trace.putAttribute(sanitizeMetricName(key), sanitizeEventValue(value))
        }
        trace.start()
        activeTraces[slot] = ActiveTrace(trace = trace, startedAtMs = SystemClock.elapsedRealtime())
    }

    fun stopTrace(
        slot: String,
        success: Boolean,
        attributes: Map<String, String> = emptyMap(),
        metrics: Map<String, Long> = emptyMap(),
    ) {
        val active = activeTraces.remove(slot) ?: return
        val elapsedMs = (SystemClock.elapsedRealtime() - active.startedAtMs).coerceAtLeast(0L)
        active.trace.putAttribute("success", success.toString())
        attributes.forEach { (key, value) ->
            active.trace.putAttribute(sanitizeMetricName(key), sanitizeEventValue(value))
        }
        active.trace.incrementMetric("elapsed_ms", elapsedMs)
        metrics.forEach { (key, value) ->
            if (value > 0L) {
                active.trace.incrementMetric(sanitizeMetricName(key), value)
            }
        }
        active.trace.stop()
    }

    fun close(reason: String = "activity_destroy") {
        configListenerRegistration?.remove()
        configListenerRegistration = null
        val slots = activeTraces.keys().toList()
        slots.forEach { slot ->
            stopTrace(slot, success = false, attributes = mapOf("closed" to reason))
        }
    }

    private fun configureRemoteConfig() {
        val remoteConfig = remoteConfig ?: return
        remoteConfig.setConfigSettingsAsync(
            remoteConfigSettings {
                minimumFetchIntervalInSeconds = 900
            },
        )
        remoteConfig.setDefaultsAsync(
            mapOf(
                "ops_perf_tracing_enabled" to true,
                "ops_health_heartbeat_interval_ms" to DEFAULT_HEARTBEAT_INTERVAL_MS,
                "ops_force_flush_actions" to defaultForceFlushActions().sorted().joinToString(","),
            ),
        )
        applyRemoteConfig(updatedKeys = emptySet(), source = "defaults")
        remoteConfig.fetchAndActivate()
            .addOnSuccessListener {
                applyRemoteConfig(
                    updatedKeys = setOf(
                        "ops_perf_tracing_enabled",
                        "ops_health_heartbeat_interval_ms",
                        "ops_force_flush_actions",
                    ),
                    source = "fetch_activate",
                )
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Remote config fetch failed: ${error.message}")
            }
        registerRealtimeListener()
    }

    private fun registerRealtimeListener() {
        val remoteConfig = remoteConfig ?: return
        configListenerRegistration?.remove()
        configListenerRegistration = remoteConfig.addOnConfigUpdateListener(
            object : ConfigUpdateListener {
                override fun onUpdate(configUpdate: ConfigUpdate) {
                    remoteConfig.activate()
                        .addOnSuccessListener {
                            applyRemoteConfig(
                                updatedKeys = configUpdate.updatedKeys,
                                source = "realtime",
                            )
                        }
                        .addOnFailureListener { error ->
                            Log.w(TAG, "Remote config realtime activation failed: ${error.message}")
                            crashlytics?.log("remote_config_realtime_activation_failed=${error.message}")
                        }
                }

                override fun onError(error: FirebaseRemoteConfigException) {
                    Log.w(TAG, "Remote config realtime listener error: ${error.message}")
                    crashlytics?.log("remote_config_realtime_listener_error=${error.message}")
                }
            },
        )
    }

    private fun applyRemoteConfig(
        updatedKeys: Set<String>,
        source: String,
    ) {
        val remoteConfig = remoteConfig ?: return
        perfTracingEnabled = remoteConfig.getBoolean("ops_perf_tracing_enabled")
        heartbeatIntervalMs = remoteConfig
            .getLong("ops_health_heartbeat_interval_ms")
            .coerceIn(MIN_HEARTBEAT_INTERVAL_MS, MAX_HEARTBEAT_INTERVAL_MS)
        forceFlushActions = remoteConfig
            .getString("ops_force_flush_actions")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
            .ifEmpty { defaultForceFlushActions() }
        val changedKeys =
            if (updatedKeys.isEmpty()) {
                "defaults"
            } else {
                updatedKeys.sorted().joinToString(",")
            }
        Log.d(
            TAG,
            "Remote config applied source=$source keys=$changedKeys tracing=$perfTracingEnabled heartbeatMs=$heartbeatIntervalMs",
        )
        crashlytics?.log("remote_config_applied source=$source keys=$changedKeys")
        onRemoteConfigApplied?.invoke(updatedKeys)
    }

    private fun sanitizeTraceName(value: String): String =
        value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_]"), "_")
            .trim('_')
            .ifBlank { "ops_trace" }
            .take(100)

    private fun sanitizeMetricName(value: String): String =
        value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_]"), "_")
            .trim('_')
            .ifBlank { "metric" }
            .take(32)

    private fun sanitizeEventValue(value: String): String =
        value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_./-]"), "_")
            .take(MAX_ATTRIBUTE_LENGTH)
            .ifBlank { "unknown" }

    private fun defaultForceFlushActions(): Set<String> = setOf(
        "playback_started",
        "playback_paused",
        "crop_opened",
        "crop_closed",
        "video_clip_imported",
        "overlay_clip_imported",
        "layer_clip_imported",
        "audio_clip_imported",
        "export_dialog_opened",
        "export_started",
        "export_completed",
        "export_failed",
        "project_saved",
        "project_loaded",
        "video_clip_deleted",
        "audio_clip_deleted",
    )
}

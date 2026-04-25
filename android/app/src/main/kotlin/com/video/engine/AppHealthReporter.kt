package com.video.engine

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AppHealthReporter(
    context: Context,
) {
    companion object {
        private const val TAG = "[AppHealth]"
        private const val PREFS = "app_health_reporter"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val MIN_FLUSH_INTERVAL_MS = 15_000L
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val firestore = FirebaseFirestore.getInstance()
    private val crashlytics = FirebaseCrashlytics.getInstance()
    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "app-health-writer").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val installationId: String = loadOrCreateInstallationId()
    private var sessionId: String = "unknown"
    private var lastScreen: String = "launch"
    private var lastAction: String = "launch"
    private var appState: String = "launching"
    private var hasProjectContent: Boolean = false
    private var isPlaying: Boolean = false
    private var lastUpdatedAtMs: Long = 0L

    fun bindSession(sessionId: String) {
        this.sessionId = sessionId.ifBlank { "unknown" }
        crashlytics.setCustomKey("ops_installation_id", installationId)
        crashlytics.setCustomKey("ops_session_id", this.sessionId)
        crashlytics.setCustomKey("ops_app_state", appState)
    }

    fun installationId(): String = installationId

    fun recordForeground(screen: String, hasContent: Boolean, playing: Boolean) {
        appState = "foreground"
        updateSurface(screen, hasContent, playing, force = true)
    }

    fun recordBackground(screen: String, hasContent: Boolean, playing: Boolean) {
        appState = "background"
        updateSurface(screen, hasContent, playing, force = true)
    }

    fun updateSurface(
        screen: String,
        hasContent: Boolean,
        playing: Boolean,
        force: Boolean = false,
    ) {
        if (closed.get()) return
        lastScreen = screen.ifBlank { "editor" }
        hasProjectContent = hasContent
        isPlaying = playing
        crashlytics.setCustomKey("ops_screen", lastScreen)
        crashlytics.setCustomKey("ops_has_project_content", hasProjectContent)
        crashlytics.setCustomKey("ops_is_playing", isPlaying)
        crashlytics.setCustomKey("ops_app_state", appState)
        flush(force)
    }

    fun noteAction(
        action: String,
        screen: String,
        hasContent: Boolean,
        playing: Boolean,
        force: Boolean = false,
    ) {
        if (closed.get()) return
        lastAction = action.ifBlank { lastAction }
        crashlytics.setCustomKey("ops_last_action", lastAction)
        updateSurface(screen, hasContent, playing, force)
    }

    fun close(screen: String, hasContent: Boolean, playing: Boolean) {
        if (closed.get()) return
        appState = "background"
        lastAction = "activity_destroy"
        crashlytics.setCustomKey("ops_last_action", lastAction)
        updateSurface(screen, hasContent, playing, force = true)
        if (!closed.compareAndSet(false, true)) return
        writerExecutor.shutdown()
    }

    private fun flush(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastUpdatedAtMs < MIN_FLUSH_INTERVAL_MS) {
            return
        }
        lastUpdatedAtMs = now
        val sessionSnapshot = sessionId
        val screenSnapshot = lastScreen
        val actionSnapshot = lastAction
        val appStateSnapshot = appState
        val hasProjectContentSnapshot = hasProjectContent
        val isPlayingSnapshot = isPlaying
        val payload = hashMapOf<String, Any>(
            "installationId" to installationId,
            "sessionId" to sessionSnapshot,
            "updatedAt" to FieldValue.serverTimestamp(),
            "updatedAtMs" to now,
            "appState" to appStateSnapshot,
            "currentScreen" to screenSnapshot,
            "lastAction" to actionSnapshot,
            "hasProjectContent" to hasProjectContentSnapshot,
            "isPlaying" to isPlayingSnapshot,
            "versionName" to (packageInfo()?.versionName ?: "unknown"),
            "versionCode" to packageVersionCode().toInt(),
            "packageName" to appContext.packageName,
            "deviceModel" to Build.MODEL,
            "deviceManufacturer" to Build.MANUFACTURER,
            "androidVersion" to Build.VERSION.RELEASE,
            "source" to "android-client",
        )

        writerExecutor.execute {
            runCatching {
                firestore.collection("ops_installations")
                    .document(installationId)
                    .set(payload, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d(
                            TAG,
                            "Health ping updated state=$appStateSnapshot screen=$screenSnapshot action=$actionSnapshot session=$sessionSnapshot",
                        )
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "Failed to write health ping: ${error.message}")
                    }
            }.onFailure { error ->
                Log.w(TAG, "Failed to queue health ping: ${error.message}")
            }
        }
    }

    private fun loadOrCreateInstallationId(): String {
        val existing = prefs.getString(KEY_INSTALLATION_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALLATION_ID, created).apply()
        return created
    }

    private fun packageInfo() =
        runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0) }.getOrNull()

    private fun packageVersionCode(): Long {
        val packageInfo = packageInfo() ?: return 0L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }
}

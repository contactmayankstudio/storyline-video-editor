package com.video.engine

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class ProblemReportManager(
    context: Context,
) {
    companion object {
        private const val TAG = "[ProblemReport]"
        private const val MAX_RECENT_UI_TAPS = 24
        private const val AUTO_FREEZE_REPORT_WINDOW_MS = 120_000L
    }

    private val appContext = context.applicationContext
    private val firestore = FirebaseFirestore.getInstance()
    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "problem-report-writer").apply { isDaemon = true }
    }
    private val recentUiTaps = ArrayBlockingQueue<String>(MAX_RECENT_UI_TAPS)
    private val lastFreezeReportAtMs = AtomicLong(0L)

    fun noteUiTap(control: String, surface: String, mode: String = "tap") {
        val entry = listOf(
            System.currentTimeMillis().toString(),
            surface.take(24),
            control.take(48),
            mode.take(16),
        ).joinToString("|")
        synchronized(recentUiTaps) {
            if (recentUiTaps.remainingCapacity() == 0) {
                recentUiTaps.poll()
            }
            recentUiTaps.offer(entry)
        }
    }

    fun submitManualReport(
        description: String,
        source: String,
        sessionId: String,
        currentScreen: String,
        lastAction: String,
        hasProjectContent: Boolean,
        isPlaying: Boolean,
        versionName: String,
        versionCode: Int,
        onComplete: (Boolean) -> Unit,
    ) {
        submitReport(
            reportType = "manual",
            description = description,
            source = source,
            sessionId = sessionId,
            currentScreen = currentScreen,
            lastAction = lastAction,
            hasProjectContent = hasProjectContent,
            isPlaying = isPlaying,
            versionName = versionName,
            versionCode = versionCode,
            metadata = emptyMap(),
            onComplete = onComplete,
        )
    }

    fun submitFreezeReport(
        stallMs: Long,
        sessionId: String,
        currentScreen: String,
        lastAction: String,
        hasProjectContent: Boolean,
        isPlaying: Boolean,
        versionName: String,
        versionCode: Int,
    ) {
        val now = System.currentTimeMillis()
        val last = lastFreezeReportAtMs.get()
        if (now - last < AUTO_FREEZE_REPORT_WINDOW_MS) return
        if (!lastFreezeReportAtMs.compareAndSet(last, now)) return
        submitReport(
            reportType = "freeze",
            description = "Main thread stall detected for ${stallMs}ms",
            source = "watchdog",
            sessionId = sessionId,
            currentScreen = currentScreen,
            lastAction = lastAction,
            hasProjectContent = hasProjectContent,
            isPlaying = isPlaying,
            versionName = versionName,
            versionCode = versionCode,
            metadata = mapOf("stallMs" to stallMs),
            onComplete = {},
        )
    }

    fun close() {
        writerExecutor.shutdown()
    }

    private fun submitReport(
        reportType: String,
        description: String,
        source: String,
        sessionId: String,
        currentScreen: String,
        lastAction: String,
        hasProjectContent: Boolean,
        isPlaying: Boolean,
        versionName: String,
        versionCode: Int,
        metadata: Map<String, Any>,
        onComplete: (Boolean) -> Unit,
    ) {
        val trimmedDescription = description.trim().take(800)
        if (trimmedDescription.isBlank()) {
            onComplete(false)
            return
        }
        val reportId = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val payload = hashMapOf<String, Any>(
            "reportId" to reportId,
            "reportType" to reportType,
            "description" to trimmedDescription,
            "source" to source.take(48),
            "sessionId" to sessionId.take(64),
            "currentScreen" to currentScreen.take(48),
            "lastAction" to lastAction.take(120),
            "hasProjectContent" to hasProjectContent,
            "isPlaying" to isPlaying,
            "versionName" to versionName.take(32),
            "versionCode" to versionCode,
            "packageName" to appContext.packageName,
            "deviceModel" to Build.MODEL,
            "deviceManufacturer" to Build.MANUFACTURER,
            "androidVersion" to Build.VERSION.RELEASE,
            "recentUiTaps" to snapshotRecentUiTaps(),
            "createdAtMs" to System.currentTimeMillis(),
            "createdAt" to FieldValue.serverTimestamp(),
            "status" to "open",
        )
        metadata.forEach { (key, value) -> payload[key] = value }
        writerExecutor.execute {
            firestore.collection("ops_reports")
                .document(reportId)
                .set(payload)
                .addOnSuccessListener {
                    Log.d(TAG, "Problem report submitted type=$reportType source=$source")
                    onComplete(true)
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Problem report failed: ${error.message}")
                    onComplete(false)
                }
        }
    }

    private fun snapshotRecentUiTaps(): List<String> =
        synchronized(recentUiTaps) { recentUiTaps.toList() }
}

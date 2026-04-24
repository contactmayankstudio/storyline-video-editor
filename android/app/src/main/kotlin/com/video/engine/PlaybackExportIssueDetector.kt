package com.video.engine

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class PlaybackExportIssueDetector(
    private val onIssueDetected: (reportType: String, source: String, description: String) -> Unit,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    companion object {
        private const val PLAYBACK_START_TIMEOUT_MS = 2_800L
        private const val PLAYBACK_STALL_TIMEOUT_MS = 3_600L
        private const val PLAYBACK_STALL_CHECK_INTERVAL_MS = 1_200L
        private const val PLAYBACK_PROGRESS_TOLERANCE_MS = 90L
        private const val EXPORT_START_TIMEOUT_MS = 20_000L
        private const val EXPORT_STALL_TIMEOUT_MS = 45_000L
        private const val EXPORT_FINALIZE_TIMEOUT_MS = 35_000L
        private const val EXPORT_CHECK_INTERVAL_MS = 5_000L
    }

    private var playbackSessionActive = false
    private var playbackRequestedAtElapsedMs = 0L
    private var playbackAnchorTimeMs = 0L
    private var playbackLastAdvancedAtElapsedMs = 0L
    private var playbackLastTimeMs = Long.MIN_VALUE
    private var playbackHasMoved = false
    private var playbackStartIssueReported = false
    private var playbackStallIssueReported = false

    private var exportSessionActive = false
    private var exportStartedAtElapsedMs = 0L
    private var exportLastProgressAtElapsedMs = 0L
    private var exportLastProgress = -1
    private var exportIssueReported = false

    private val playbackStartTimeoutRunnable = Runnable {
        if (!playbackSessionActive || playbackHasMoved || playbackStartIssueReported) return@Runnable
        playbackStartIssueReported = true
        val waitMs = SystemClock.elapsedRealtime() - playbackRequestedAtElapsedMs
        onIssueDetected(
            "playback",
            "playback_start_timeout",
            "Playback start did not advance within ${waitMs}ms from ${playbackAnchorTimeMs}ms.",
        )
    }

    private val playbackStallCheckRunnable = object : Runnable {
        override fun run() {
            if (!playbackSessionActive) return
            if (
                playbackHasMoved &&
                !playbackStallIssueReported &&
                SystemClock.elapsedRealtime() - playbackLastAdvancedAtElapsedMs >= PLAYBACK_STALL_TIMEOUT_MS
            ) {
                playbackStallIssueReported = true
                val frozenMs = SystemClock.elapsedRealtime() - playbackLastAdvancedAtElapsedMs
                onIssueDetected(
                    "playback",
                    "playback_stall_detected",
                    "Playback stopped advancing for ${frozenMs}ms at ${playbackLastTimeMs.coerceAtLeast(0L)}ms.",
                )
            }
            mainHandler.postDelayed(this, PLAYBACK_STALL_CHECK_INTERVAL_MS)
        }
    }

    private val exportCheckRunnable = object : Runnable {
        override fun run() {
            if (!exportSessionActive) return
            val elapsedMs = SystemClock.elapsedRealtime() - exportStartedAtElapsedMs
            val idleMs = SystemClock.elapsedRealtime() - exportLastProgressAtElapsedMs
            val issueSource =
                when {
                    exportLastProgress < 0 && elapsedMs >= EXPORT_START_TIMEOUT_MS -> "export_start_timeout"
                    exportLastProgress in 0..79 && idleMs >= EXPORT_STALL_TIMEOUT_MS -> "export_progress_stall"
                    exportLastProgress in 80..99 && idleMs >= EXPORT_FINALIZE_TIMEOUT_MS -> "export_finalize_stall"
                    else -> null
                }
            if (issueSource != null && !exportIssueReported) {
                exportIssueReported = true
                val description =
                    "Export progress stuck at ${exportLastProgress.coerceAtLeast(0)}% for ${idleMs}ms " +
                        "(elapsed ${elapsedMs}ms)."
                onIssueDetected("export", issueSource, description)
            }
            mainHandler.postDelayed(this, EXPORT_CHECK_INTERVAL_MS)
        }
    }

    fun onPlaybackStarted(anchorTimeMs: Long) {
        playbackSessionActive = true
        playbackRequestedAtElapsedMs = SystemClock.elapsedRealtime()
        playbackAnchorTimeMs = anchorTimeMs.coerceAtLeast(0L)
        playbackLastAdvancedAtElapsedMs = playbackRequestedAtElapsedMs
        playbackLastTimeMs = playbackAnchorTimeMs
        playbackHasMoved = false
        playbackStartIssueReported = false
        playbackStallIssueReported = false
        mainHandler.removeCallbacks(playbackStartTimeoutRunnable)
        mainHandler.removeCallbacks(playbackStallCheckRunnable)
        mainHandler.postDelayed(playbackStartTimeoutRunnable, PLAYBACK_START_TIMEOUT_MS)
        mainHandler.postDelayed(playbackStallCheckRunnable, PLAYBACK_STALL_CHECK_INTERVAL_MS)
    }

    fun onPlaybackTimeChanged(timeMs: Long) {
        if (!playbackSessionActive) return
        val normalizedTimeMs = timeMs.coerceAtLeast(0L)
        if (!playbackHasMoved && normalizedTimeMs >= playbackAnchorTimeMs + PLAYBACK_PROGRESS_TOLERANCE_MS) {
            playbackHasMoved = true
            playbackLastAdvancedAtElapsedMs = SystemClock.elapsedRealtime()
            mainHandler.removeCallbacks(playbackStartTimeoutRunnable)
        } else if (playbackHasMoved && normalizedTimeMs > playbackLastTimeMs + 8L) {
            playbackLastAdvancedAtElapsedMs = SystemClock.elapsedRealtime()
        }
        playbackLastTimeMs = maxOf(playbackLastTimeMs, normalizedTimeMs)
    }

    fun onPlaybackPaused() {
        playbackSessionActive = false
        mainHandler.removeCallbacks(playbackStartTimeoutRunnable)
        mainHandler.removeCallbacks(playbackStallCheckRunnable)
    }

    fun onExportStarted() {
        exportSessionActive = true
        exportStartedAtElapsedMs = SystemClock.elapsedRealtime()
        exportLastProgressAtElapsedMs = exportStartedAtElapsedMs
        exportLastProgress = -1
        exportIssueReported = false
        mainHandler.removeCallbacks(exportCheckRunnable)
        mainHandler.postDelayed(exportCheckRunnable, EXPORT_CHECK_INTERVAL_MS)
    }

    fun onExportProgress(progress: Int) {
        if (!exportSessionActive) return
        if (progress != exportLastProgress) {
            exportLastProgress = progress
            exportLastProgressAtElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    fun onExportCompleted(success: Boolean, error: String?) {
        if (exportSessionActive && !success && !exportIssueReported) {
            exportIssueReported = true
            val detail = error?.take(120)?.ifBlank { "unknown_error" } ?: "unknown_error"
            onIssueDetected(
                "export",
                "export_failed",
                "Export failed before completion: $detail",
            )
        }
        exportSessionActive = false
        mainHandler.removeCallbacks(exportCheckRunnable)
    }

    fun close() {
        onPlaybackPaused()
        exportSessionActive = false
        mainHandler.removeCallbacks(exportCheckRunnable)
    }
}

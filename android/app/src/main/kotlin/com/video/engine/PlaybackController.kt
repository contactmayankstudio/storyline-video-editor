package com.video.engine

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.Choreographer
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.video.engine.pro.timeline.MultiTrackTimelineView
import com.video.engine.timeline.TimelineManager

class PlaybackController(
    private val activity: Activity,
    private val previewViewProvider: () -> VideoPreviewView?,
    private val timelineRecyclerViewProvider: () -> RecyclerView?,
    private val timelineCurrentTimeTextProvider: () -> TextView?,
    private val multiTrackTimelineViewProvider: () -> MultiTrackTimelineView?,
    private val timelineManagerProvider: () -> TimelineManager?,
    private val setTimelineManager: (TimelineManager) -> Unit,
    private val playheadTimeMsProvider: () -> Long,
    private val currentTimeMsProvider: () -> Long,
    private val setCurrentTimeMs: (Long) -> Unit,
    private val isPlayingProvider: () -> Boolean,
    private val setIsPlaying: (Boolean) -> Unit,
    private val setVideoDurationMs: (Long) -> Unit,
    private val totalDurationMsProvider: () -> Long,
    private val onSelectionChanged: (Int?) -> Unit,
    private val onTransitionRequested: (Int, Int) -> Unit,
    private val onPlayRequested: (Long) -> Unit,
    private val onPauseRequested: () -> Unit,
    private val onSeekRequested: (Long, Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "[UI]"
        private const val MAX_UI_TIMELINE_FPS = 20L
        private const val MIN_UI_FRAME_INTERVAL_MS = 1000L / MAX_UI_TIMELINE_FPS
        private const val NATIVE_PLAYBACK_START_GRACE_MS = 750L
        private const val NATIVE_SEEK_DUPLICATE_TOLERANCE_MS = 8L
        private const val LOW_END_PLAY_PREROLL_MS = 54L
        private const val MID_TIER_PLAY_PREROLL_MS = 32L
        private const val HIGH_END_PLAY_PREROLL_MS = 16L
    }

    private val choreographer = Choreographer.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())
    // Single-thread executor for scrub seeks — cancels stale seeks automatically
    private val scrubExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var lastRenderedPlaybackTimeMs = Long.MIN_VALUE
    private var lastLoggedPlaybackTimeMs = Long.MIN_VALUE
    private var lastUiApplyRealtimeMs = 0L
    private var playbackFrameScheduled = false
    private var lastScrubNativeSeekMs = Long.MIN_VALUE
    private var lastNativeSeekTimelineMs = Long.MIN_VALUE
    private var lastScrubTimeMs = Long.MIN_VALUE
    private var nativePlaybackStartGraceDeadlineMs = 0L
    private var pendingSmoothPlayToken = 0
    var onPlaybackTimeChanged: ((Long) -> Unit)? = null
    var onPlaybackTimeSampled: ((Long) -> Unit)? = null

    private val scrubHandler = Handler(Looper.getMainLooper())
    private val pendingScrubCommit = Runnable {
        if (lastScrubTimeMs != Long.MIN_VALUE) commitScrub(lastScrubTimeMs)
    }
    private val playbackFrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        playbackFrameScheduled = false
        if (!isPlayingProvider()) {
            android.util.Log.w("PlaybackController", "frameCallback: isPlaying=false, stopping")
            return@FrameCallback
        }
        val previewView = previewViewProvider() ?: run {
            android.util.Log.e("PlaybackController", "frameCallback: previewView null!")
            return@FrameCallback
        }
        if (!NativeBridge.isPlaybackActive(previewView)) {
            val nowMs = SystemClock.elapsedRealtime()
            if (nativePlaybackStartGraceDeadlineMs > nowMs) {
                schedulePlaybackFrame()
                return@FrameCallback
            }
            handleNativePlaybackStopped(previewView)
            return@FrameCallback
        }
        nativePlaybackStartGraceDeadlineMs = 0L
        val playbackTimeMs = resolvePlaybackTimeMs(previewView)
        onPlaybackTimeSampled?.invoke(playbackTimeMs)
        // android.util.Log.d("PlaybackController", "frameCallback: timeMs=$playbackTimeMs")
        applyPlaybackTime(playbackTimeMs)
        schedulePlaybackFrame()
    }

    private fun schedulePlaybackFrame() {
        if (playbackFrameScheduled) return
        playbackFrameScheduled = true
        choreographer.postFrameCallback(playbackFrameCallback)
    }

    private fun cancelPlaybackFrames() {
        if (!playbackFrameScheduled) return
        choreographer.removeFrameCallback(playbackFrameCallback)
        playbackFrameScheduled = false
    }

    fun setupTimeline() {
        val recyclerView = timelineRecyclerViewProvider()
        val timeDisplay = timelineCurrentTimeTextProvider()
        val multiTrackView = multiTrackTimelineViewProvider()
        if (recyclerView == null && multiTrackView == null && timeDisplay == null) {
            Log.w(TAG, "Timeline UI elements not found")
            return
        }

        val timelineManager = TimelineManager(
            recyclerView = recyclerView,
            timeDisplay = timeDisplay,
            timeFormatter = { timelineMs, _ -> formatRemainingTime(timelineMs, totalDurationMsProvider()) },
        )
        timelineManager.setScrubListener(object : TimelineManager.OnScrubListener {
            override fun onScrub(timelineMs: Long) {
                onTimelineScrub(timelineMs, syncTimelineUi = true)
            }
        })
        timelineManager.setTransitionListener { outgoingClipId, incomingClipId ->
            onTransitionRequested(outgoingClipId, incomingClipId)
        }
        timelineManager.setSelectionListener { selectedClipId ->
            onSelectionChanged(selectedClipId)
        }

        setTimelineManager(timelineManager)
        Log.d(TAG, "Timeline setup complete")
    }

    fun scheduleInitialDurationRefresh() {
        Handler(Looper.getMainLooper()).postDelayed({
            val durationMs = previewViewProvider()?.getDuration() ?: totalDurationMsProvider()
            setVideoDurationMs(durationMs)
            Log.d(TAG, "Video duration: $durationMs ms")
        }, 500)
    }

    fun onResume() {
        previewViewProvider()?.onResume()
        if (isPlayingProvider()) {
            nativePlay()
        }
    }

    fun onPause() {
        if (isPlayingProvider()) {
            nativePause()
            setIsPlaying(false)
        }
        previewViewProvider()?.onPause()
    }

    fun nativePlay() {
        val view = previewViewProvider()
        if (view == null) {
            android.util.Log.e("PlaybackController", "nativePlay: previewView is NULL — cannot play!")
            return
        }
        val availableDurationMs = maxOf(totalDurationMsProvider(), view.getDuration())
        if (availableDurationMs <= 0L) {
            Log.w(TAG, "nativePlay ignored: no timeline media loaded")
            return
        }
        val requestedTimeMs = playheadTimeMsProvider().coerceAtLeast(0L)
        val startTimeMs = when {
            availableDurationMs <= 1L -> 0L
            requestedTimeMs >= availableDurationMs -> 0L
            else -> requestedTimeMs
        }
        cancelPendingSmoothPlay()
        setCurrentTimeMs(startTimeMs)
        setIsPlaying(true)
        lastRenderedPlaybackTimeMs = Long.MIN_VALUE
        applyPlaybackTime(startTimeMs)
        view.ensureNativeSurfaceBinding()
        val prerollDelayMs = resolvePlayPrerollDelayMs()
        if (prerollDelayMs > 0L && !NativeBridge.isPlaybackActive(view)) {
            pendingSmoothPlayToken += 1
            val playToken = pendingSmoothPlayToken
            Log.d(
                TAG,
                "nativePlay preroll: startTimeMs=$startTimeMs requestedTimeMs=$requestedTimeMs delay=${prerollDelayMs}ms",
            )
            NativeBridge.seekToTime(view, startTimeMs)
            mainHandler.postDelayed({
                if (playToken != pendingSmoothPlayToken || !isPlayingProvider()) {
                    Log.d(TAG, "nativePlay preroll canceled token=$playToken")
                    return@postDelayed
                }
                startNativePlayback(view, startTimeMs, requestedTimeMs)
            }, prerollDelayMs)
            return
        }
        startNativePlayback(view, startTimeMs, requestedTimeMs)
    }

    fun nativePause() {
        cancelPendingSmoothPlay()
        setIsPlaying(false)
        nativePlaybackStartGraceDeadlineMs = 0L
        cancelPlaybackFrames()
        onPauseRequested()
        previewViewProvider()?.let { view ->
            NativeBridge.stopPlayback(view)
        }
        resetPlaybackClock()
    }

    fun pauseRendering() {
        nativePause()
    }

    private fun onTimelineScrub(timelineMs: Long, syncTimelineUi: Boolean) {
        cancelPendingSmoothPlay()
        resetPlaybackClock(anchorTimeMs = timelineMs)
        setCurrentTimeMs(timelineMs)
        timelineManagerProvider()?.updateDisplayedTime(timelineMs)
        if (syncTimelineUi) {
            multiTrackTimelineViewProvider()?.setCurrentTimeMs(timelineMs)
        }
        // Always update pending seek — only latest position matters
        pendingScrubTimeMs = timelineMs
        val now = SystemClock.elapsedRealtime()
        if (now - lastScrubNativeSeekMs >= 50L) {
            lastScrubNativeSeekMs = now
            dispatchScrubToNative(timelineMs)
        }
        lastScrubTimeMs = timelineMs
    }

    @Volatile private var pendingScrubTimeMs = -1L

    private fun dispatchScrubToNative(timelineMs: Long) {
        val view = previewViewProvider() ?: return
        scrubExecutor.execute {
            val latest = pendingScrubTimeMs
            // Skip if a newer seek came in while we were waiting
            if (latest != timelineMs && latest >= 0) return@execute
            if (!shouldDispatchNativeSeek(timelineMs)) return@execute
            NativeBridge.seekToTime(view, timelineMs)
        }
        onSeekRequested(timelineMs, isPlayingProvider())
    }

    private fun commitScrub(timelineMs: Long) {
        // Use latest pending position — finger may have moved during throttle window
        val finalMs = if (pendingScrubTimeMs >= 0) pendingScrubTimeMs else timelineMs
        pendingScrubTimeMs = -1L
        lastScrubNativeSeekMs = SystemClock.elapsedRealtime()
        previewViewProvider()?.let { view ->
            if (shouldDispatchNativeSeek(finalMs)) {
                NativeBridge.seekToTime(view, finalMs)
            }
        }
        onSeekRequested(finalMs, isPlayingProvider())
    }

    fun scrubTo(timelineMs: Long, syncTimelineUi: Boolean = true) {
        scrubHandler.removeCallbacks(pendingScrubCommit)
        onTimelineScrub(timelineMs, syncTimelineUi = syncTimelineUi)
        // Always commit final position after finger stops
        scrubHandler.postDelayed(pendingScrubCommit, 80L)
    }

    private fun resolvePlaybackTimeMs(previewView: VideoPreviewView): Long {
        val nativeTimeMs = NativeBridge.getCurrentPlaybackTime(previewView).coerceAtLeast(0L)
        if (nativeTimeMs > 0L) {
            val durationMs = totalDurationMsProvider().coerceAtLeast(nativeTimeMs)
            return nativeTimeMs.coerceAtMost(durationMs)
        }
        return currentTimeMsProvider().coerceAtLeast(0L)
    }

    private fun handleNativePlaybackStopped(previewView: VideoPreviewView) {
        cancelPendingSmoothPlay()
        val stoppedTimeMs = NativeBridge.getCurrentPlaybackTime(previewView).coerceAtLeast(0L)
        setIsPlaying(false)
        nativePlaybackStartGraceDeadlineMs = 0L
        cancelPlaybackFrames()
        onPauseRequested()
        applyPlaybackTime(stoppedTimeMs)
        resetPlaybackClock(anchorTimeMs = stoppedTimeMs)
        Log.d(TAG, "Native playback stopped at ${stoppedTimeMs}ms")
    }

    private fun applyPlaybackTime(timeMs: Long) {
        if (timeMs == lastRenderedPlaybackTimeMs) return
        val nowMs = SystemClock.elapsedRealtime()
        if (lastUiApplyRealtimeMs > 0L &&
            nowMs - lastUiApplyRealtimeMs < MIN_UI_FRAME_INTERVAL_MS &&
            lastRenderedPlaybackTimeMs != Long.MIN_VALUE &&
            kotlin.math.abs(timeMs - lastRenderedPlaybackTimeMs) < 8L
        ) {
            return
        }
        lastUiApplyRealtimeMs = nowMs
        setCurrentTimeMs(timeMs)
        timelineCurrentTimeTextProvider()
            ?.takeIf { it.visibility == View.VISIBLE }
            ?.text = formatRemainingTime(timeMs, totalDurationMsProvider())
        timelineManagerProvider()?.updateDisplayedTime(timeMs)
        multiTrackTimelineViewProvider()?.setCurrentTimeMs(timeMs)
        // Update canvas timeline playhead
        onPlaybackTimeChanged?.invoke(timeMs)
        lastLoggedPlaybackTimeMs = timeMs
        lastRenderedPlaybackTimeMs = timeMs
    }

    private fun startNativePlayback(
        view: VideoPreviewView,
        startTimeMs: Long,
        requestedTimeMs: Long,
    ) {
        nativePlaybackStartGraceDeadlineMs = SystemClock.elapsedRealtime() + NATIVE_PLAYBACK_START_GRACE_MS
        Log.d(
            TAG,
            "nativePlay: startTimeMs=$startTimeMs requestedTimeMs=$requestedTimeMs isPlaying=${isPlayingProvider()}",
        )
        NativeBridge.startPlayback(view, startTimeMs)
        onPlayRequested(startTimeMs)
        schedulePlaybackFrame()
        Log.d(TAG, "nativePlay: schedulePlaybackFrame done")
    }

    private fun cancelPendingSmoothPlay() {
        pendingSmoothPlayToken += 1
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun resetPlaybackClock(anchorTimeMs: Long = currentTimeMsProvider()) {
        lastRenderedPlaybackTimeMs = Long.MIN_VALUE
        lastLoggedPlaybackTimeMs = Long.MIN_VALUE
        lastUiApplyRealtimeMs = 0L
    }

    private fun formatRemainingTime(timeMs: Long, totalDurationMs: Long): String {
        val remainingMs = (totalDurationMs - timeMs).coerceAtLeast(0L)
        val totalSeconds = remainingMs / 1000
        val seconds = totalSeconds % 60
        val totalMinutes = totalSeconds / 60
        val minutes = totalMinutes % 60
        val hours = totalMinutes / 60
        return if (hours > 0) {
            String.format("-%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("-%02d:%02d", minutes, seconds)
        }
    }

    private fun shouldDispatchNativeSeek(timelineMs: Long): Boolean {
        val lastMs = lastNativeSeekTimelineMs
        if (lastMs != Long.MIN_VALUE &&
            kotlin.math.abs(timelineMs - lastMs) <= NATIVE_SEEK_DUPLICATE_TOLERANCE_MS
        ) {
            return false
        }
        lastNativeSeekTimelineMs = timelineMs
        return true
    }

    private fun resolvePlayPrerollDelayMs(): Long {
        return when (DeviceDetector.getDeviceTier()) {
            DeviceDetector.DeviceTier.LOW -> LOW_END_PLAY_PREROLL_MS
            DeviceDetector.DeviceTier.MID -> MID_TIER_PLAY_PREROLL_MS
            DeviceDetector.DeviceTier.HIGH -> HIGH_END_PLAY_PREROLL_MS
        }
    }
}

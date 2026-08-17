package com.video.engine

import android.content.Context
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.MediaMuxer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.video.engine.audio.AudioGainKeyframe
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

class PreviewAudioPlayer(
    context: Context,
    private val previewViewProvider: () -> VideoPreviewView?,
) {
    data class SourceSelection(
        val key: String,
        val path: String,
        val timelineStartMs: Long,
        val timelineEndMs: Long,
        val sourceInMs: Long,
        val sourceOutMs: Long,
        val volume: Float,
        val fadeInMs: Int = 0,
        val fadeOutMs: Int = 0,
        val gainKeyframes: List<AudioGainKeyframe> = emptyList(),
        val playbackSpeed: Float = 1.0f,
        val reversePlayback: Boolean = false,
        val freezeFrameEnabled: Boolean = false,
        val freezeFrameTimeMs: Long = 0L,
        val freezeFrameDurationMs: Long = 0L,
        val curveSpeedProfile: String = "linear",
        val curveSpeedStrength: Float = 1.0f,
    )

    companion object {
        private const val TAG = "[PreviewAudio]"
        private const val HIGH_TIER_CLOCK_TICK_MS = 16L
        private const val MID_TIER_CLOCK_TICK_MS = 32L
        private const val LOW_TIER_CLOCK_TICK_MS = 64L
        private const val SEEK_TOLERANCE_MS = 32
        private const val SEEK_COMPLETE_FALLBACK_MS = 250L
        private const val IDLE_REQUEST_COALESCE_MS = 95L
        private const val PLAYING_REQUEST_COALESCE_MS = 55L
        private const val IDLE_DUPLICATE_QUIET_WINDOW_MS = 320L
        private const val PLAYING_DUPLICATE_QUIET_WINDOW_MS = 180L
        private const val NO_SOURCE_IDLE_REQUEST_QUIET_WINDOW_MS = 1_200L
        private const val NO_SOURCE_PLAYER_KEEPALIVE_MS = 8_000L
        private const val PLAYBACK_SOURCE_GAP_KEEPALIVE_MS = 260L
        private const val HIGH_TIER_VIDEO_CLOCK_RESYNC_THRESHOLD_MS = 120L
        private const val MID_TIER_VIDEO_CLOCK_RESYNC_THRESHOLD_MS = 240L
        private const val LOW_TIER_VIDEO_CLOCK_RESYNC_THRESHOLD_MS = 360L
        private const val HIGH_TIER_VIDEO_CLOCK_RESYNC_MIN_INTERVAL_MS = 320L
        private const val MID_TIER_VIDEO_CLOCK_RESYNC_MIN_INTERVAL_MS = 650L
        private const val LOW_TIER_VIDEO_CLOCK_RESYNC_MIN_INTERVAL_MS = 1_400L
        private const val AUDIO_EXTRACT_BUFFER_BYTES = 256 * 1024
        private val PREWARM_VIDEO_EXTENSIONS = setOf("3gp", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "ts", "webm")
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioThread = HandlerThread("PreviewAudioPlayer").apply { start() }
    private val audioHandler = Handler(audioThread.looper)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val previewAudioExtractDir = File(appContext.cacheDir, "preview_audio_extracts").apply { mkdirs() }
    private val unsupportedSourcePaths = mutableSetOf<String>()
    private val silentSourcePaths = mutableSetOf<String>()
    private val preferredAudioSourcePathByMediaPath = mutableMapOf<String, String>()
    private val requestLock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var currentSelection: SourceSelection? = null
    private var reusablePlayerSelection: SourceSelection? = null
    private var currentPlayerPath: String? = null
    private var currentPlayerDurationMs = -1L
    private var prepared = false
    private var preparing = false
    private var playing = false
    private var pendingTimelineMs = 0L
    private var pendingAutoPlay = false
    private var tickerRunning = false
    private var lastAppliedSelectionKey: String? = null
    private var lastAppliedMediaSeekMs = Int.MIN_VALUE
    private var lastAppliedAutoPlay = false
    private var lastAppliedVolume = Float.NaN
    private var audioMasterClockEnabled = false
    private var requestedTimelineMs = 0L
    private var requestedAutoPlay = false
    private var lastVideoClockResyncElapsedMs = 0L
    private var pendingSeekTargetMs = Int.MIN_VALUE
    private var pendingStartAfterSeek = false
    private var seekInFlight = false
    private var deferredSeekTargetMs = Int.MIN_VALUE
    private var deferredSeekAutoPlay = false
    private var lastQueuedTimelineMs = Long.MIN_VALUE
    private var lastQueuedAutoPlay = false
    private var lastQueuedRequestElapsedMs = 0L
    private var lastNoSourceTimelineMs = Long.MIN_VALUE
    private var lastNoSourceLogElapsedMs = 0L
    private var lastNoSourceRequestTimelineMs = Long.MIN_VALUE
    private var lastNoSourceRequestElapsedMs = 0L
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> runOnAudioThread {
                val selection = currentSelection
                val timelineMs = currentTimelineTimeMs() ?: pendingTimelineMs
                applyVolume(selection?.let { effectiveVolumeAt(it, timelineMs) } ?: 1.0f, force = true)
                if (playing && prepared && runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false).not()) {
                    runCatching { mediaPlayer?.start() }
                    if (runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)) {
                        setAudioMasterClockEnabled(true)
                        startTicker()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> runOnAudioThread {
                runCatching { mediaPlayer?.pause() }
                setAudioMasterClockEnabled(false)
                stopTicker()
                playing = false
                abandonAudioFocusIfNeeded()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> runOnAudioThread {
                runCatching { mediaPlayer?.pause() }
                setAudioMasterClockEnabled(false)
                stopTicker()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> runOnAudioThread {
                val selection = currentSelection
                val timelineMs = currentTimelineTimeMs() ?: pendingTimelineMs
                val ducked = (selection?.let { effectiveVolumeAt(it, timelineMs) } ?: 1.0f).coerceIn(0f, 1f) * 0.35f
                runCatching { mediaPlayer?.setVolume(ducked, ducked) }
                lastAppliedVolume = Float.NaN
            }
        }
    }
    private val seekCompletionFallback = Runnable {
        val player = mediaPlayer ?: return@Runnable
        if (!prepared || !pendingStartAfterSeek || !playing) return@Runnable
        Log.d(TAG, "seekFallback start key=${currentSelection?.key} timelineMs=$pendingTimelineMs")
        pendingStartAfterSeek = false
        pendingSeekTargetMs = Int.MIN_VALUE
        val isActuallyPlaying = runCatching { player.isPlaying }.getOrDefault(false)
        if (!isActuallyPlaying && requestAudioFocusIfNeeded()) {
            runCatching { player.start() }
        }
        val nowPlaying = runCatching { player.isPlaying }.getOrDefault(false)
        if (nowPlaying) {
            setAudioMasterClockEnabled(true)
            startTicker()
            lastAppliedAutoPlay = true
        }
    }
    private val applyRequestedState = Runnable {
        val timelineMs: Long
        val autoPlay: Boolean
        synchronized(requestLock) {
            timelineMs = requestedTimelineMs
            autoPlay = requestedAutoPlay
        }
        playing = autoPlay
        ensureSelection(timelineMs, autoPlay = autoPlay)
    }
    private val releaseIdleCachedPlayer = Runnable {
        if (playing || currentSelection != null) {
            return@Runnable
        }
        releasePlayer()
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (!tickerRunning) return
            if (!playing) {
                stopTicker()
                return
            }
            val timelineMs = currentTimelineTimeMs()
            if (timelineMs != null) {
                val nextSelectionKey = NativeBridge.resolvePreviewAudioSourceKeyAt(timelineMs)
                if (nextSelectionKey != currentSelection?.key || (nextSelectionKey == null && mediaPlayer == null)) {
                    ensureSelection(timelineMs, autoPlay = true)
                } else {
                    currentSelection?.let { selection ->
                        applyVolume(effectiveVolumeAt(selection, timelineMs))
                    }
                    dispatchAudioClock(timelineMs)
                }
            }
            if (tickerRunning) {
                audioHandler.postDelayed(this, resolveClockTickMs())
            }
        }
    }

    init {
        context.applicationContext
    }

    fun playFrom(timelineMs: Long) {
        queueSelectionRequest(timelineMs, autoPlay = true, reason = "playFrom")
    }

    fun pause() {
        runOnAudioThread {
            playing = false
            stopTicker()
            setAudioMasterClockEnabled(false)
            abandonAudioFocusIfNeeded()
            audioHandler.removeCallbacks(seekCompletionFallback)
            pendingStartAfterSeek = false
            pendingSeekTargetMs = Int.MIN_VALUE
            seekInFlight = false
            deferredSeekTargetMs = Int.MIN_VALUE
            deferredSeekAutoPlay = false
            val isActuallyPlaying = runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)
            if (isActuallyPlaying) {
                runCatching { mediaPlayer?.pause() }
            }
            lastAppliedAutoPlay = false
        }
    }

    fun seekTo(timelineMs: Long, continuePlaying: Boolean) {
        queueSelectionRequest(timelineMs, autoPlay = continuePlaying, reason = "seekTo")
    }

    fun prewarmSource(sourcePath: String) {
        if (suppressAutomationSeedVideoFallback(sourcePath)) return
        if (isKnownSilentSource(sourcePath)) return
        if (!shouldPreferExtractedAudioFallback(sourcePath)) return
        runOnAudioThread {
            if (!isExistingMediaPath(sourcePath)) {
                return@runOnAudioThread
            }
            if (isKnownSilentSource(sourcePath)) {
                return@runOnAudioThread
            }
            val cacheKey = sourceCacheKey(sourcePath)
            preferredAudioSourcePathByMediaPath[cacheKey]
                ?.takeIf { isExistingMediaPath(it) }
                ?.let { return@runOnAudioThread }
            val fallbackPath = resolveExtractedAudioFallbackPath(sourcePath, rememberUnsupported = false) ?: return@runOnAudioThread
            preferredAudioSourcePathByMediaPath[cacheKey] = fallbackPath
            Log.d(TAG, "Prewarmed extracted preview audio source media=$sourcePath fallback=$fallbackPath")
        }
    }

    fun syncToVideoClock(timelineMs: Long, continuePlaying: Boolean) {
        if (!continuePlaying) {
            return
        }
        runOnAudioThread {
            if (!playing) {
                return@runOnAudioThread
            }
            val audioTimelineMs = currentTimelineTimeMs()
            val videoMasterClock = DeviceDetector.getDeviceTier() == DeviceDetector.DeviceTier.LOW
            val driftMs = if (audioTimelineMs != null) {
                abs(audioTimelineMs - timelineMs)
            } else {
                Long.MAX_VALUE
            }
            val audioAheadOfSampledVideo =
                audioTimelineMs != null &&
                    audioTimelineMs > timelineMs + resolveVideoClockResyncThresholdMs()
            if (audioAheadOfSampledVideo && !videoMasterClock) {
                // Keep audio as the smooth master clock. Seeking it backward to a
                // stale video sample makes low-end preview playback crawl.
                return@runOnAudioThread
            }
            val now = SystemClock.elapsedRealtime()
            if (driftMs < resolveVideoClockResyncThresholdMs() ||
                now - lastVideoClockResyncElapsedMs < resolveVideoClockResyncMinIntervalMs()
            ) {
                return@runOnAudioThread
            }
            lastVideoClockResyncElapsedMs = now
            ensureSelection(timelineMs, autoPlay = true)
        }
    }

    fun currentTimelineTimeMs(): Long? {
        val player = mediaPlayer ?: return null
        val selection = currentSelection ?: return null
        if (!prepared) return null
        val mediaPositionMs = runCatching { player.currentPosition.toLong() }.getOrNull() ?: return null
        val relativeSourceMs = (mediaPositionMs - selection.sourceInMs).coerceAtLeast(0L)
        val relativeMs = (relativeSourceMs / effectivePlaybackSpeed(selection)).toLong().coerceAtLeast(0L)
        val timelineMs = selection.timelineStartMs + relativeMs
        return timelineMs.coerceIn(selection.timelineStartMs, selection.timelineEndMs.coerceAtLeast(selection.timelineStartMs))
    }

    fun release() {
        runOnAudioThread {
            playing = false
            stopTicker()
            setAudioMasterClockEnabled(false)
            audioHandler.removeCallbacks(seekCompletionFallback)
            releasePlayer()
            audioHandler.removeCallbacksAndMessages(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                audioThread.quitSafely()
            } else {
                audioThread.quit()
            }
        }
    }

    private fun ensureSelection(timelineMs: Long, autoPlay: Boolean) {
        pendingTimelineMs = timelineMs.coerceAtLeast(0L)
        pendingAutoPlay = autoPlay
        val selection = NativeBridge.resolvePreviewAudioSourceAt(pendingTimelineMs)
        if (selection != null && isKnownSilentSource(selection.path)) {
            setAudioMasterClockEnabled(false)
            stopTicker()
            abandonAudioFocusIfNeeded()
            audioHandler.removeCallbacks(seekCompletionFallback)
            val isActuallyPlaying = runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)
            if (isActuallyPlaying) {
                runCatching { mediaPlayer?.pause() }
            }
            releasePlayer()
            currentSelection = selection
            lastAppliedSelectionKey = selection.key
            lastAppliedMediaSeekMs = Int.MIN_VALUE
            lastAppliedAutoPlay = false
            return
        }
        if (selection == null || !isPlayableMediaPath(selection.path)) {
            if (selection == null && shouldKeepAudioRunningThroughResolverGap(pendingTimelineMs, autoPlay)) {
                val playerStillRunning = runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)
                if (playerStillRunning) {
                    setAudioMasterClockEnabled(true)
                    startTicker()
                    return
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (!autoPlay &&
                currentSelection == null &&
                mediaPlayer == null &&
                abs(lastNoSourceRequestTimelineMs - pendingTimelineMs) <= SEEK_TOLERANCE_MS &&
                now - lastNoSourceRequestElapsedMs < NO_SOURCE_IDLE_REQUEST_QUIET_WINDOW_MS
            ) {
                return
            }
            lastNoSourceRequestTimelineMs = pendingTimelineMs
            lastNoSourceRequestElapsedMs = now
            if (abs(lastNoSourceTimelineMs - pendingTimelineMs) > SEEK_TOLERANCE_MS ||
                now - lastNoSourceLogElapsedMs >= 1500L
            ) {
                Log.d(TAG, "No preview audio source at timelineMs=$pendingTimelineMs")
                lastNoSourceTimelineMs = pendingTimelineMs
                lastNoSourceLogElapsedMs = now
            }
            setAudioMasterClockEnabled(false)
            stopTicker()
            abandonAudioFocusIfNeeded()
            audioHandler.removeCallbacks(seekCompletionFallback)
            val isActuallyPlaying = runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)
            if (isActuallyPlaying) {
                runCatching { mediaPlayer?.pause() }
            }
            currentSelection = null
            lastAppliedSelectionKey = null
            lastAppliedMediaSeekMs = Int.MIN_VALUE
            lastAppliedAutoPlay = false
            seekInFlight = false
            deferredSeekTargetMs = Int.MIN_VALUE
            deferredSeekAutoPlay = false
            scheduleIdleCachedPlayerRelease()
            return
        }
        audioHandler.removeCallbacks(releaseIdleCachedPlayer)
        lastNoSourceTimelineMs = Long.MIN_VALUE
        lastNoSourceLogElapsedMs = 0L
        lastNoSourceRequestTimelineMs = Long.MIN_VALUE
        lastNoSourceRequestElapsedMs = 0L

        val sameSource = canReuseCurrentPlayerFor(selection)
        currentSelection = selection
        if (!sameSource || mediaPlayer == null) {
            Log.d(TAG, "createPlayer key=${selection.key} path=${selection.path}")
            createPlayer(selection)
            return
        }

        if (lastAppliedSelectionKey != selection.key) {
            Log.d(
                TAG,
                "reusePlayer oldKey=$lastAppliedSelectionKey newKey=${selection.key} path=${selection.path}",
            )
        }

        if (!prepared) {
            if (!preparing) {
                Log.d(TAG, "recreatePlayer key=${selection.key} prepared=$prepared preparing=$preparing")
                createPlayer(selection)
            }
            return
        }

        val targetMs = timelineToMediaMs(selection, pendingTimelineMs)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
        val currentMediaPositionMs = runCatching { mediaPlayer?.currentPosition }
            .getOrNull()
            ?: Int.MIN_VALUE
        val playerPositionMatchesTarget =
            currentMediaPositionMs != Int.MIN_VALUE &&
                abs(currentMediaPositionMs - targetMs) <= SEEK_TOLERANCE_MS
        val shouldSkipSeek = targetMs <= SEEK_TOLERANCE_MS && playerPositionMatchesTarget
        val duplicateSeek =
            lastAppliedSelectionKey == selection.key &&
                abs(lastAppliedMediaSeekMs - targetMs) <= SEEK_TOLERANCE_MS &&
                playerPositionMatchesTarget
        val isActuallyPlaying = runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)
        val clipEndTargetMs = timelineToMediaMs(selection, selection.timelineEndMs)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
        val atOrPastClipEnd =
            pendingTimelineMs >= (selection.timelineEndMs - SEEK_TOLERANCE_MS).coerceAtLeast(selection.timelineStartMs) ||
                targetMs >= (clipEndTargetMs - SEEK_TOLERANCE_MS).coerceAtLeast(0)
        if (!autoPlay && duplicateSeek && !isActuallyPlaying && !lastAppliedAutoPlay) {
            setAudioMasterClockEnabled(false)
            stopTicker()
            return
        }
        if (autoPlay && duplicateSeek && isActuallyPlaying && lastAppliedAutoPlay) {
            setAudioMasterClockEnabled(true)
            startTicker()
            lastAppliedSelectionKey = selection.key
            lastAppliedMediaSeekMs = targetMs
            lastAppliedAutoPlay = true
            return
        }
        if (autoPlay && duplicateSeek && !isActuallyPlaying && atOrPastClipEnd) {
            setAudioMasterClockEnabled(false)
            stopTicker()
            lastAppliedSelectionKey = selection.key
            lastAppliedMediaSeekMs = targetMs
            lastAppliedAutoPlay = false
            return
        }

        applyVolume(effectiveVolumeAt(selection, pendingTimelineMs), force = autoPlay)
        applyPlaybackSpeed(selection)
        if (seekInFlight && !shouldSkipSeek) {
            if (abs(pendingSeekTargetMs - targetMs) > SEEK_TOLERANCE_MS) {
                deferredSeekTargetMs = targetMs
                deferredSeekAutoPlay = autoPlay
                lastAppliedMediaSeekMs = targetMs
            }
            if (!autoPlay) {
                setAudioMasterClockEnabled(false)
                stopTicker()
            }
            lastAppliedSelectionKey = selection.key
            lastAppliedAutoPlay = autoPlay
            return
        }
        val shouldStartAfterSeek = autoPlay && !isActuallyPlaying && !duplicateSeek && !shouldSkipSeek
        if (!duplicateSeek && !shouldSkipSeek) {
            seekPlayer(targetMs, autoPlayAfterSeek = shouldStartAfterSeek)
        } else if (!duplicateSeek) {
            audioHandler.removeCallbacks(seekCompletionFallback)
            pendingStartAfterSeek = false
            pendingSeekTargetMs = Int.MIN_VALUE
            lastAppliedMediaSeekMs = targetMs
        }
        if (autoPlay) {
            Log.d(TAG, "resumePlayer key=${selection.key} timelineMs=$pendingTimelineMs")
            if (!isActuallyPlaying && !shouldStartAfterSeek && requestAudioFocusIfNeeded()) {
                applyVolume(effectiveVolumeAt(selection, pendingTimelineMs), force = true)
                runCatching { mediaPlayer?.start() }
            }
            val nowPlaying = runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)
            if (nowPlaying) {
                setAudioMasterClockEnabled(true)
                startTicker()
                lastAppliedAutoPlay = true
            } else {
                setAudioMasterClockEnabled(false)
                stopTicker()
                if (!shouldStartAfterSeek) {
                    lastAppliedAutoPlay = false
                }
            }
        } else {
            setAudioMasterClockEnabled(false)
            stopTicker()
            audioHandler.removeCallbacks(seekCompletionFallback)
            if (isActuallyPlaying) {
                runCatching { mediaPlayer?.pause() }
            }
        }
        lastAppliedSelectionKey = selection.key
        lastAppliedMediaSeekMs = targetMs
        lastAppliedAutoPlay = autoPlay
    }

    private fun canReuseCurrentPlayerFor(selection: SourceSelection): Boolean {
        val existingPlayer = mediaPlayer ?: return false
        val current = currentSelection ?: reusablePlayerSelection ?: return false
        val currentPath = currentPlayerPath ?: return false
        val nextPath = resolvePreferredPlayerPath(selection.path)
        if (nextPath != currentPath) {
            return false
        }
        // Reuse one MediaPlayer across split/contiguous timeline clips that point to the
        // same backing media. Timing/volume are updated through seek/applyVolume.
        if (current.freezeFrameEnabled != selection.freezeFrameEnabled) {
            return false
        }
        if (current.reversePlayback != selection.reversePlayback) {
            return false
        }
        if (abs(effectivePlaybackSpeed(current) - effectivePlaybackSpeed(selection)) >= 0.01f) {
            return false
        }
        return existingPlayer === mediaPlayer
    }

    private fun shouldKeepAudioRunningThroughResolverGap(timelineMs: Long, autoPlay: Boolean): Boolean {
        if (!autoPlay || !playing || !prepared) return false
        if (mediaPlayer == null) return false
        val selection = currentSelection ?: return false
        val gapStartMs = selection.timelineEndMs - SEEK_TOLERANCE_MS
        val gapEndMs = selection.timelineEndMs + PLAYBACK_SOURCE_GAP_KEEPALIVE_MS
        return timelineMs in gapStartMs..gapEndMs
    }

    private fun resolveNextPlayableSelectionProbeMs(nextTimelineMs: Long, currentKey: String): Long? {
        val probeOffsetsMs = longArrayOf(0L, 24L, 48L, 96L, PLAYBACK_SOURCE_GAP_KEEPALIVE_MS)
        for (offsetMs in probeOffsetsMs) {
            val probeMs = (nextTimelineMs + offsetMs).coerceAtLeast(0L)
            val key = NativeBridge.resolvePreviewAudioSourceKeyAt(probeMs)
            if (!key.isNullOrBlank() && key != currentKey) {
                return probeMs
            }
        }
        return null
    }

    private fun createPlayer(selection: SourceSelection) {
        audioHandler.removeCallbacks(releaseIdleCachedPlayer)
        releasePlayer()
        prepared = false
        preparing = true
        val playerPath = resolvePreferredPlayerPath(selection.path)
        val player = MediaPlayer()
        mediaPlayer = player
        reusablePlayerSelection = selection
        currentPlayerPath = playerPath
        currentPlayerDurationMs = -1L
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        player.setOnPreparedListener { preparedPlayer ->
            if (preparedPlayer !== mediaPlayer) {
                runCatching { preparedPlayer.release() }
                return@setOnPreparedListener
            }
            val duration = runCatching { preparedPlayer.duration }.getOrNull() ?: -1
            if (duration == 0) {
                Log.w(TAG, "Invalid duration=$duration for path=$playerPath")
                if (!retryCreatePlayerWithFallback(selection, playerPath, "invalid duration")) {
                    markSelectionSourceUnsupported(selection, playerPath)
                    preparing = false
                    prepared = false
                    setAudioMasterClockEnabled(false)
                    stopTicker()
                    abandonAudioFocusIfNeeded()
                    audioHandler.removeCallbacks(seekCompletionFallback)
                    currentSelection = null
                    lastAppliedSelectionKey = null
                    lastAppliedMediaSeekMs = Int.MIN_VALUE
                    lastAppliedAutoPlay = false
                    releasePlayer()
                }
                return@setOnPreparedListener
            }
            preparing = false
            prepared = true
            currentPlayerDurationMs = duration.toLong().coerceAtLeast(1L)
            seekInFlight = false
            deferredSeekTargetMs = Int.MIN_VALUE
            deferredSeekAutoPlay = false
            unsupportedSourcePaths.remove(selection.path)
            if (playerPath != selection.path) {
                preferredAudioSourcePathByMediaPath[selection.path] = playerPath
            }
            applyVolume(effectiveVolumeAt(selection, pendingTimelineMs), force = pendingAutoPlay)
            applyPlaybackSpeed(selection)
            val targetMs = timelineToMediaMs(selection, pendingTimelineMs)
                .coerceIn(0L, Int.MAX_VALUE.toLong())
                .toInt()
            lastAppliedSelectionKey = selection.key
            lastAppliedMediaSeekMs = targetMs
            if (targetMs <= SEEK_TOLERANCE_MS) {
                audioHandler.removeCallbacks(seekCompletionFallback)
                pendingStartAfterSeek = false
                pendingSeekTargetMs = Int.MIN_VALUE
                if (pendingAutoPlay) {
                    Log.d(TAG, "playerPrepared start key=${selection.key} timelineMs=$pendingTimelineMs")
                    if (requestAudioFocusIfNeeded()) {
                        applyVolume(effectiveVolumeAt(selection, pendingTimelineMs), force = true)
                        runCatching { preparedPlayer.start() }
                    }
                    val isActuallyPlaying = runCatching { preparedPlayer.isPlaying }.getOrDefault(false)
                    if (isActuallyPlaying) {
                        setAudioMasterClockEnabled(true)
                        startTicker()
                        lastAppliedAutoPlay = true
                    } else {
                        setAudioMasterClockEnabled(false)
                        stopTicker()
                        lastAppliedAutoPlay = false
                    }
                } else {
                    Log.d(TAG, "playerPrepared idle key=${selection.key} timelineMs=$pendingTimelineMs")
                    setAudioMasterClockEnabled(false)
                    stopTicker()
                    lastAppliedAutoPlay = false
                }
                return@setOnPreparedListener
            }
            seekPlayer(targetMs, autoPlayAfterSeek = pendingAutoPlay)
            if (pendingAutoPlay) {
                Log.d(TAG, "playerPrepared awaitSeek key=${selection.key} timelineMs=$pendingTimelineMs")
                setAudioMasterClockEnabled(false)
                stopTicker()
                lastAppliedAutoPlay = true
            } else {
                Log.d(TAG, "playerPrepared idle key=${selection.key} timelineMs=$pendingTimelineMs")
                setAudioMasterClockEnabled(false)
                lastAppliedAutoPlay = false
            }
        }
        player.setOnSeekCompleteListener { completedPlayer ->
            if (completedPlayer !== mediaPlayer) {
                return@setOnSeekCompleteListener
            }
            audioHandler.removeCallbacks(seekCompletionFallback)
            if (pendingSeekTargetMs != Int.MIN_VALUE) {
                lastAppliedMediaSeekMs = pendingSeekTargetMs
            }
            seekInFlight = false
            val queuedSeekTargetMs = deferredSeekTargetMs
            val queuedSeekAutoPlay = deferredSeekAutoPlay
            deferredSeekTargetMs = Int.MIN_VALUE
            deferredSeekAutoPlay = false
            if (queuedSeekTargetMs != Int.MIN_VALUE &&
                abs(queuedSeekTargetMs - lastAppliedMediaSeekMs) > SEEK_TOLERANCE_MS
            ) {
                seekPlayer(
                    queuedSeekTargetMs,
                    autoPlayAfterSeek = queuedSeekAutoPlay && playing,
                )
                lastAppliedSelectionKey = currentSelection?.key
                lastAppliedAutoPlay = queuedSeekAutoPlay
                return@setOnSeekCompleteListener
            }
            val resumeAfterSeek = pendingStartAfterSeek && playing
            pendingStartAfterSeek = false
            pendingSeekTargetMs = Int.MIN_VALUE
            val isActuallyPlaying = runCatching { completedPlayer.isPlaying }.getOrDefault(false)
            if (resumeAfterSeek && !isActuallyPlaying && requestAudioFocusIfNeeded()) {
                Log.d(TAG, "seekComplete start key=${currentSelection?.key} timelineMs=$pendingTimelineMs")
                currentSelection?.let { selection ->
                    applyVolume(effectiveVolumeAt(selection, pendingTimelineMs), force = true)
                }
                runCatching { completedPlayer.start() }
            }
            val nowPlaying = runCatching { completedPlayer.isPlaying }.getOrDefault(false)
            if (nowPlaying && playing) {
                setAudioMasterClockEnabled(true)
                startTicker()
                lastAppliedAutoPlay = true
            } else if (!playing) {
                setAudioMasterClockEnabled(false)
                stopTicker()
                lastAppliedAutoPlay = false
            }
        }
        player.setOnCompletionListener {
            if (!playing) return@setOnCompletionListener
            val timelineMs = (currentTimelineTimeMs() ?: selection.timelineEndMs)
                .coerceIn(selection.timelineStartMs, selection.timelineEndMs.coerceAtLeast(selection.timelineStartMs))
            val reachedSelectionEnd =
                timelineMs >= (selection.timelineEndMs - SEEK_TOLERANCE_MS).coerceAtLeast(selection.timelineStartMs)
            val nextTimelineMs = if (reachedSelectionEnd) selection.timelineEndMs + 1L else timelineMs + 1L
            val nextSelectionProbeMs = resolveNextPlayableSelectionProbeMs(nextTimelineMs, selection.key)
            if (nextSelectionProbeMs == null) {
                Log.d(TAG, "playerCompletion stop key=${selection.key} timelineMs=$timelineMs")
                playing = false
                setAudioMasterClockEnabled(false)
                stopTicker()
                abandonAudioFocusIfNeeded()
                lastAppliedAutoPlay = false
                return@setOnCompletionListener
            }
            Log.d(TAG, "playerCompletion key=${selection.key} nextTimelineMs=$nextSelectionProbeMs")
            ensureSelection(nextSelectionProbeMs, autoPlay = true)
        }
        player.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "MediaPlayer error what=$what extra=$extra path=$playerPath")
            if (!retryCreatePlayerWithFallback(selection, playerPath, "MediaPlayer error $what/$extra")) {
                markSelectionSourceUnsupported(selection, playerPath)
                preparing = false
                prepared = false
                setAudioMasterClockEnabled(false)
                stopTicker()
                abandonAudioFocusIfNeeded()
                audioHandler.removeCallbacks(seekCompletionFallback)
                currentSelection = null
                lastAppliedSelectionKey = null
                lastAppliedMediaSeekMs = Int.MIN_VALUE
                lastAppliedAutoPlay = false
                releasePlayer()
            }
            true
        }
        try {
            setPlayerDataSource(player, playerPath)
            player.prepareAsync()
        } catch (error: Exception) {
            Log.w(TAG, "Failed to prepare audio source $playerPath: ${error.message}")
            if (!retryCreatePlayerWithFallback(selection, playerPath, error.message ?: "prepare failure")) {
                markSelectionSourceUnsupported(selection, playerPath)
                preparing = false
                prepared = false
                setAudioMasterClockEnabled(false)
                stopTicker()
                abandonAudioFocusIfNeeded()
                audioHandler.removeCallbacks(seekCompletionFallback)
                currentSelection = null
                lastAppliedSelectionKey = null
                lastAppliedMediaSeekMs = Int.MIN_VALUE
                lastAppliedAutoPlay = false
                pendingStartAfterSeek = false
                pendingSeekTargetMs = Int.MIN_VALUE
                releasePlayer()
            }
        }
    }

    private fun releasePlayer() {
        val player = mediaPlayer ?: return
        val wasPrepared = prepared
        mediaPlayer = null
        reusablePlayerSelection = null
        currentPlayerPath = null
        currentPlayerDurationMs = -1L
        prepared = false
        preparing = false
        lastAppliedVolume = Float.NaN
        audioHandler.removeCallbacks(seekCompletionFallback)
        lastAppliedSelectionKey = null
        lastAppliedMediaSeekMs = Int.MIN_VALUE
        lastAppliedAutoPlay = false
        pendingStartAfterSeek = false
        pendingSeekTargetMs = Int.MIN_VALUE
        seekInFlight = false
        deferredSeekTargetMs = Int.MIN_VALUE
        deferredSeekAutoPlay = false
        player.setOnPreparedListener(null)
        player.setOnSeekCompleteListener(null)
        player.setOnCompletionListener(null)
        player.setOnErrorListener(null)
        runCatching { if (wasPrepared) player.stop() }
        runCatching { player.release() }
        abandonAudioFocusIfNeeded()
    }

    private fun scheduleIdleCachedPlayerRelease() {
        if (mediaPlayer == null) {
            return
        }
        audioHandler.removeCallbacks(releaseIdleCachedPlayer)
        audioHandler.postDelayed(releaseIdleCachedPlayer, NO_SOURCE_PLAYER_KEEPALIVE_MS)
    }

    private fun isPlayableMediaPath(path: String): Boolean {
        if (path.isBlank()) return false
        val cacheKey = sourceCacheKey(path)
        if (cacheKey in silentSourcePaths) return false
        val preferredPath = preferredAudioSourcePathByMediaPath[cacheKey]
        if (preferredPath != null) {
            return isExistingMediaPath(preferredPath)
        }
        if (cacheKey in unsupportedSourcePaths) return false
        return isExistingMediaPath(path)
    }

    private fun isKnownSilentSource(path: String): Boolean {
        if (path.isBlank()) return false
        return sourceCacheKey(path) in silentSourcePaths
    }

    private fun sourceCacheKey(path: String): String {
        return MediaPathResolver.cacheKey(path)
    }

    private fun isExistingMediaPath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("content://")) return true
        return File(MediaPathResolver.normalizeForFileAccess(path)).exists()
    }

    private fun isAutomationSeedVideoPath(sourcePath: String): Boolean {
        if (!sourcePath.contains("/automation_samples/")) return false
        val fileName = sourcePath.substringAfterLast('/').lowercase()
        return fileName.startsWith("quick_sample_video") && fileName.endsWith(".mp4")
    }

    private fun suppressAutomationSeedVideoFallback(sourcePath: String): Boolean {
        if (!isAutomationSeedVideoPath(sourcePath)) return false
        val cacheKey = sourceCacheKey(sourcePath)
        preferredAudioSourcePathByMediaPath.remove(cacheKey)
        unsupportedSourcePaths.remove(cacheKey)
        silentSourcePaths.remove(cacheKey)
        runCatching {
            val fallbackFile = File(previewAudioExtractDir, buildAudioFallbackFileName(sourcePath))
            if (fallbackFile.exists()) {
                fallbackFile.delete()
            }
        }
        return true
    }

    private fun shouldPreferExtractedAudioFallback(sourcePath: String): Boolean {
        if (sourcePath.isBlank()) return false
        if (isAutomationSeedVideoPath(sourcePath)) return false
        if (sourcePath.startsWith("content://")) return true
        val extension = sourcePath.substringAfterLast('.', "").lowercase()
        return extension in PREWARM_VIDEO_EXTENSIONS
    }

    private fun resolvePreferredPlayerPath(sourcePath: String): String {
        if (suppressAutomationSeedVideoFallback(sourcePath)) {
            return sourcePath
        }
        val cacheKey = sourceCacheKey(sourcePath)
        if (isKnownSilentSource(sourcePath)) {
            return sourcePath
        }
        preferredAudioSourcePathByMediaPath[cacheKey]
            ?.takeIf { isExistingMediaPath(it) }
            ?.let { return it }
        if (!shouldPreferExtractedAudioFallback(sourcePath)) {
            return sourcePath
        }
        val fallbackPath = resolveExtractedAudioFallbackPath(sourcePath, rememberUnsupported = false)
        if (!fallbackPath.isNullOrBlank() && isExistingMediaPath(fallbackPath)) {
            preferredAudioSourcePathByMediaPath[cacheKey] = fallbackPath
            return fallbackPath
        }
        return sourcePath
    }

    private fun setPlayerDataSource(player: MediaPlayer, sourcePath: String) {
        if (sourcePath.startsWith("content://")) {
            player.setDataSource(appContext, Uri.parse(sourcePath))
        } else {
            player.setDataSource(sourcePath)
        }
    }

    private fun retryCreatePlayerWithFallback(
        selection: SourceSelection,
        attemptedPath: String,
        reason: String,
    ): Boolean {
        if (isKnownSilentSource(selection.path)) {
            return false
        }
        if (attemptedPath != selection.path) {
            return false
        }
        val fallbackPath = resolveExtractedAudioFallbackPath(selection.path) ?: return false
        if (fallbackPath == attemptedPath) {
            return false
        }
        Log.d(TAG, "Using extracted fallback for key=${selection.key} reason=$reason fallback=$fallbackPath")
        val cacheKey = sourceCacheKey(selection.path)
        preferredAudioSourcePathByMediaPath[cacheKey] = fallbackPath
        unsupportedSourcePaths.remove(cacheKey)
        silentSourcePaths.remove(cacheKey)
        createPlayer(selection)
        return true
    }

    private fun markSelectionSourceUnsupported(selection: SourceSelection, attemptedPath: String) {
        val cacheKey = sourceCacheKey(selection.path)
        if (isKnownSilentSource(selection.path)) {
            unsupportedSourcePaths.remove(cacheKey)
            preferredAudioSourcePathByMediaPath.remove(cacheKey)
            return
        }
        if (attemptedPath != selection.path) {
            preferredAudioSourcePathByMediaPath.remove(cacheKey)
        }
        unsupportedSourcePaths.add(cacheKey)
    }

    private fun resolveExtractedAudioFallbackPath(sourcePath: String, rememberUnsupported: Boolean = true): String? {
        if (suppressAutomationSeedVideoFallback(sourcePath)) {
            return null
        }
        val cacheKey = sourceCacheKey(sourcePath)
        preferredAudioSourcePathByMediaPath[cacheKey]
            ?.takeIf { isExistingMediaPath(it) }
            ?.let { return it }
        if (!isExistingMediaPath(sourcePath)) {
            return null
        }
        val outputFile = File(previewAudioExtractDir, buildAudioFallbackFileName(sourcePath))
        if (outputFile.exists() && outputFile.length() > 0L) {
            return outputFile.absolutePath
        }

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        return try {
            if (sourcePath.startsWith("content://")) {
                extractor.setDataSource(appContext, Uri.parse(sourcePath), null)
            } else {
                extractor.setDataSource(sourcePath)
            }
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = trackIndex
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) {
                Log.w(TAG, "No audio track found for preview source $sourcePath")
                silentSourcePaths.add(cacheKey)
                unsupportedSourcePaths.remove(cacheKey)
                preferredAudioSourcePathByMediaPath.remove(cacheKey)
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()
            muxerStarted = true

            val bufferSize = when {
                audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ->
                    audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
                else -> AUDIO_EXTRACT_BUFFER_BYTES
            }
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val info = MediaCodec.BufferInfo()

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    break
                }
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrackIndex, buffer, info)
                if (!extractor.advance()) {
                    break
                }
            }

            if (muxerStarted) {
                muxer.stop()
                muxerStarted = false
            }
            muxer.release()
            muxer = null

            if (outputFile.length() <= 0L) {
                outputFile.delete()
                if (rememberUnsupported) {
                    unsupportedSourcePaths.add(cacheKey)
                }
                null
            } else {
                silentSourcePaths.remove(cacheKey)
                outputFile.absolutePath
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to build preview audio fallback for $sourcePath: ${error.message}")
            outputFile.delete()
            null
        } finally {
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun buildAudioFallbackFileName(sourcePath: String): String {
        val signature = if (sourcePath.startsWith("content://")) {
            sourcePath.hashCode().toUInt().toString(16)
        } else {
            val file = File(sourcePath)
            "${sourcePath.hashCode().toUInt().toString(16)}_${file.length()}_${file.lastModified()}"
        }
        return "preview_$signature.m4a"
    }

    private fun applyVolume(volume: Float, force: Boolean = false) {
        val clamped = volume.coerceIn(0f, 1f)
        if (!force && !lastAppliedVolume.isNaN() && abs(lastAppliedVolume - clamped) < 0.02f) {
            return
        }
        lastAppliedVolume = clamped
        runCatching { mediaPlayer?.setVolume(clamped, clamped) }
    }

    private fun effectiveVolumeAt(selection: SourceSelection, timelineMs: Long): Float {
        val clipDurationMs = (selection.timelineEndMs - selection.timelineStartMs).coerceAtLeast(1L)
        val localTimelineMs = (timelineMs - selection.timelineStartMs).coerceIn(0L, clipDurationMs - 1L)
        val fadeInMs = selection.fadeInMs.coerceAtLeast(0).toLong().coerceAtMost(clipDurationMs)
        val fadeOutMs = selection.fadeOutMs.coerceAtLeast(0).toLong().coerceAtMost(clipDurationMs)

        var fadeGain = 1f
        if (fadeInMs > 0L && localTimelineMs < fadeInMs) {
            fadeGain = minOf(fadeGain, localTimelineMs.toFloat() / fadeInMs.toFloat())
        }
        if (fadeOutMs > 0L) {
            val fadeOutStartMs = (clipDurationMs - fadeOutMs).coerceAtLeast(0L)
            if (localTimelineMs >= fadeOutStartMs) {
                val remainingMs = ((clipDurationMs - 1L) - localTimelineMs).coerceAtLeast(0L)
                fadeGain = minOf(fadeGain, remainingMs.toFloat() / fadeOutMs.toFloat())
            }
        }
        val envelopeGain = sampleAudioGainEnvelope(selection.gainKeyframes, localTimelineMs)
        return (
            selection.volume.coerceAtLeast(0f) *
                envelopeGain.coerceIn(0f, 2f) *
                fadeGain.coerceIn(0f, 1f)
            ).coerceAtLeast(0f)
    }

    private fun sampleAudioGainEnvelope(keyframes: List<AudioGainKeyframe>, localTimeMs: Long): Float {
        if (keyframes.isEmpty()) return 1.0f
        val normalized = keyframes.sortedBy { it.timeMs }
        val clampedLocalTimeMs = localTimeMs.coerceAtLeast(0L)
        if (clampedLocalTimeMs <= normalized.first().timeMs) {
            return normalized.first().gain.coerceIn(0f, 2f)
        }
        if (clampedLocalTimeMs >= normalized.last().timeMs) {
            return normalized.last().gain.coerceIn(0f, 2f)
        }
        for (index in 1 until normalized.size) {
            val left = normalized[index - 1]
            val right = normalized[index]
            if (clampedLocalTimeMs > right.timeMs) continue
            val spanMs = (right.timeMs - left.timeMs).coerceAtLeast(1L)
            val progress = (clampedLocalTimeMs - left.timeMs).toFloat() / spanMs.toFloat()
            return (left.gain + ((right.gain - left.gain) * progress)).coerceIn(0f, 2f)
        }
        return 1.0f
    }

    private fun applyPlaybackSpeed(selection: SourceSelection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val speed = effectivePlaybackSpeed(selection)
        if (abs(speed - 1.0f) < 0.01f) {
            return
        }
        runCatching {
            val player = mediaPlayer ?: return@runCatching
            val params = runCatching { player.playbackParams }.getOrNull() ?: android.media.PlaybackParams()
            player.playbackParams = params.setSpeed(speed)
        }.onFailure {
            Log.w(TAG, "Failed to apply playback speed=$speed: ${it.message}")
        }
    }

    private fun seekPlayer(targetMs: Int, autoPlayAfterSeek: Boolean = false) {
        val player = mediaPlayer ?: return
        audioHandler.removeCallbacks(seekCompletionFallback)
        pendingSeekTargetMs = targetMs
        pendingStartAfterSeek = autoPlayAfterSeek
        seekInFlight = true
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(targetMs.toLong(), MediaPlayer.SEEK_CLOSEST_SYNC)
            } else {
                @Suppress("DEPRECATION")
                player.seekTo(targetMs)
            }
        }.onFailure {
            pendingStartAfterSeek = false
            pendingSeekTargetMs = Int.MIN_VALUE
            seekInFlight = false
            Log.w(TAG, "Failed to seek audio preview to $targetMs ms: ${it.message}")
        }
        if (autoPlayAfterSeek) {
            audioHandler.postDelayed(seekCompletionFallback, SEEK_COMPLETE_FALLBACK_MS)
        }
        lastAppliedMediaSeekMs = targetMs
    }

    private fun timelineToMediaMs(selection: SourceSelection, timelineMs: Long): Long {
        val deltaMs = (timelineMs - selection.timelineStartMs).coerceAtLeast(0L)
        val mappedMs = selection.sourceInMs + (deltaMs * effectivePlaybackSpeed(selection)).toLong()
        val upperBound = if (selection.sourceOutMs > selection.sourceInMs) {
            selection.sourceOutMs - 1L
        } else {
            selection.sourceInMs
        }
        val playerUpperBound = currentPlayerDurationMs
            .takeIf { it > 0L }
            ?.let { it - 1L }
            ?: Long.MAX_VALUE
        val safeUpperBound = minOf(
            upperBound.coerceAtLeast(selection.sourceInMs),
            playerUpperBound.coerceAtLeast(0L),
        ).coerceAtLeast(selection.sourceInMs)
        return mappedMs.coerceIn(selection.sourceInMs, safeUpperBound)
    }

    private fun effectivePlaybackSpeed(selection: SourceSelection): Float {
        return selection.playbackSpeed.coerceAtLeast(0.1f)
    }

    private fun dispatchAudioClock(timelineMs: Long) {
        if (!audioMasterClockEnabled) return
        val previewView = previewViewProvider() ?: return
        previewView.updateAudioClockUs(timelineMs * 1000L)
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        audioHandler.post(ticker)
    }

    private fun stopTicker() {
        tickerRunning = false
        audioHandler.removeCallbacks(ticker)
    }

    private fun setAudioMasterClockEnabled(enabled: Boolean) {
        val effectiveEnabled = enabled && DeviceDetector.getDeviceTier() != DeviceDetector.DeviceTier.LOW
        if (audioMasterClockEnabled == effectiveEnabled) return
        audioMasterClockEnabled = effectiveEnabled
        mainHandler.post {
            previewViewProvider()?.setAudioMasterClockEnabled(effectiveEnabled)
        }
    }

    private fun requestAudioFocusIfNeeded(): Boolean {
        if (hasAudioFocus) {
            return true
        }
        val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest
                ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .setWillPauseWhenDucked(false)
                    .build()
                    .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        hasAudioFocus = focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasAudioFocus) {
            Log.w(TAG, "Audio focus denied for preview playback")
        }
        return hasAudioFocus
    }

    private fun abandonAudioFocusIfNeeded() {
        if (!hasAudioFocus) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
    }

    private fun queueSelectionRequest(timelineMs: Long, autoPlay: Boolean, reason: String) {
        val clampedTimelineMs = timelineMs.coerceAtLeast(0L)
        val now = SystemClock.elapsedRealtime()
        val coalesceDelayMs = if (autoPlay) PLAYING_REQUEST_COALESCE_MS else IDLE_REQUEST_COALESCE_MS
        val duplicateQuietWindowMs = if (autoPlay) PLAYING_DUPLICATE_QUIET_WINDOW_MS else IDLE_DUPLICATE_QUIET_WINDOW_MS
        if (
            lastQueuedAutoPlay == autoPlay &&
            abs(lastQueuedTimelineMs - clampedTimelineMs) <= SEEK_TOLERANCE_MS &&
            now - lastQueuedRequestElapsedMs <= duplicateQuietWindowMs
        ) {
            return
        }
        lastQueuedTimelineMs = clampedTimelineMs
        lastQueuedAutoPlay = autoPlay
        lastQueuedRequestElapsedMs = now
        synchronized(requestLock) {
            requestedTimelineMs = clampedTimelineMs
            requestedAutoPlay = autoPlay
        }
        Log.d(TAG, "$reason timelineMs=$clampedTimelineMs continuePlaying=$autoPlay")
        audioHandler.removeCallbacks(applyRequestedState)
        audioHandler.postDelayed(applyRequestedState, coalesceDelayMs)
    }

    private fun runOnAudioThread(action: () -> Unit) {
        if (Looper.myLooper() == audioThread.looper) {
            action()
        } else {
            audioHandler.post(action)
        }
    }

    private fun resolveVideoClockResyncThresholdMs(): Long {
        return when (DeviceDetector.getDeviceTier()) {
            DeviceDetector.DeviceTier.LOW -> LOW_TIER_VIDEO_CLOCK_RESYNC_THRESHOLD_MS
            DeviceDetector.DeviceTier.MID -> MID_TIER_VIDEO_CLOCK_RESYNC_THRESHOLD_MS
            DeviceDetector.DeviceTier.HIGH -> HIGH_TIER_VIDEO_CLOCK_RESYNC_THRESHOLD_MS
        }
    }

    private fun resolveVideoClockResyncMinIntervalMs(): Long {
        return when (DeviceDetector.getDeviceTier()) {
            DeviceDetector.DeviceTier.LOW -> LOW_TIER_VIDEO_CLOCK_RESYNC_MIN_INTERVAL_MS
            DeviceDetector.DeviceTier.MID -> MID_TIER_VIDEO_CLOCK_RESYNC_MIN_INTERVAL_MS
            DeviceDetector.DeviceTier.HIGH -> HIGH_TIER_VIDEO_CLOCK_RESYNC_MIN_INTERVAL_MS
        }
    }

    private fun resolveClockTickMs(): Long {
        return when (DeviceDetector.getDeviceTier()) {
            DeviceDetector.DeviceTier.LOW -> LOW_TIER_CLOCK_TICK_MS
            DeviceDetector.DeviceTier.MID -> MID_TIER_CLOCK_TICK_MS
            DeviceDetector.DeviceTier.HIGH -> HIGH_TIER_CLOCK_TICK_MS
        }
    }
}

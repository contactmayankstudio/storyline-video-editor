package com.video.engine

import android.content.Context
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
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
        private const val CLOCK_TICK_MS = 16L
        private const val SEEK_TOLERANCE_MS = 24
        private const val SEEK_COMPLETE_FALLBACK_MS = 250L
        private const val VIDEO_CLOCK_RESYNC_THRESHOLD_MS = 48L
        private const val VIDEO_CLOCK_RESYNC_MIN_INTERVAL_MS = 140L
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioThread = HandlerThread("PreviewAudioPlayer").apply { start() }
    private val audioHandler = Handler(audioThread.looper)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val failedPaths = mutableSetOf<String>()
    private val requestLock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var currentSelection: SourceSelection? = null
    private var prepared = false
    private var preparing = false
    private var playing = false
    private var pendingTimelineMs = 0L
    private var pendingAutoPlay = false
    private var tickerRunning = false
    private var lastAppliedSelectionKey: String? = null
    private var lastAppliedMediaSeekMs = Int.MIN_VALUE
    private var lastAppliedAutoPlay = false
    private var audioMasterClockEnabled = false
    private var requestedTimelineMs = 0L
    private var requestedAutoPlay = false
    private var lastVideoClockResyncElapsedMs = 0L
    private var pendingSeekTargetMs = Int.MIN_VALUE
    private var pendingStartAfterSeek = false
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> runOnAudioThread {
                applyVolume(currentSelection?.volume ?: 1.0f)
                if (playing && prepared && runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false).not()) {
                    runCatching { mediaPlayer?.start() }
                    if (runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)) {
                        setAudioMasterClockEnabled(true)
                        startTicker()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> runOnAudioThread {
                runCatching { mediaPlayer?.pause() }
                setAudioMasterClockEnabled(false)
                stopTicker()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> runOnAudioThread {
                val ducked = (currentSelection?.volume ?: 1.0f).coerceIn(0f, 1f) * 0.35f
                runCatching { mediaPlayer?.setVolume(ducked, ducked) }
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
                    dispatchAudioClock(timelineMs)
                }
            }
            if (tickerRunning) {
                audioHandler.postDelayed(this, CLOCK_TICK_MS)
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

    fun syncToVideoClock(timelineMs: Long, continuePlaying: Boolean) {
        if (!continuePlaying) {
            return
        }
        runOnAudioThread {
            if (!playing) {
                return@runOnAudioThread
            }
            val audioTimelineMs = currentTimelineTimeMs()
            val driftMs = if (audioTimelineMs != null) {
                abs(audioTimelineMs - timelineMs)
            } else {
                Long.MAX_VALUE
            }
            val now = SystemClock.elapsedRealtime()
            if (driftMs < VIDEO_CLOCK_RESYNC_THRESHOLD_MS ||
                now - lastVideoClockResyncElapsedMs < VIDEO_CLOCK_RESYNC_MIN_INTERVAL_MS
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
        if (selection == null || !isPlayableMediaPath(selection.path)) {
            Log.w(TAG, "No playable audio source at timelineMs=$pendingTimelineMs")
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
            releasePlayer()
            return
        }

        val sameSource = currentSelection?.key == selection.key && currentSelection?.path == selection.path
        currentSelection = selection
        if (!sameSource || mediaPlayer == null) {
            Log.d(TAG, "createPlayer key=${selection.key} path=${selection.path}")
            createPlayer(selection)
            return
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
        val shouldSkipSeek = targetMs <= SEEK_TOLERANCE_MS
        val duplicateSeek =
            lastAppliedSelectionKey == selection.key &&
                abs(lastAppliedMediaSeekMs - targetMs) <= SEEK_TOLERANCE_MS
        val isActuallyPlaying = runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)
        if (!autoPlay && duplicateSeek && !isActuallyPlaying && !lastAppliedAutoPlay) {
            setAudioMasterClockEnabled(false)
            stopTicker()
            return
        }

        applyVolume(selection.volume)
        applyPlaybackSpeed(selection)
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

    private fun createPlayer(selection: SourceSelection) {
        releasePlayer()
        prepared = false
        preparing = true
        val player = MediaPlayer()
        mediaPlayer = player
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
                Log.w(TAG, "Invalid duration=$duration for path=${selection.path}, skipping clip")
                failedPaths.add(selection.path)
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
                return@setOnPreparedListener
            }
            preparing = false
            prepared = true
            applyVolume(selection.volume)
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
            val resumeAfterSeek = pendingStartAfterSeek && playing
            pendingStartAfterSeek = false
            pendingSeekTargetMs = Int.MIN_VALUE
            val isActuallyPlaying = runCatching { completedPlayer.isPlaying }.getOrDefault(false)
            if (resumeAfterSeek && !isActuallyPlaying && requestAudioFocusIfNeeded()) {
                Log.d(TAG, "seekComplete start key=${currentSelection?.key} timelineMs=$pendingTimelineMs")
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
            val timelineMs = (currentTimelineTimeMs() ?: selection.timelineEndMs).coerceAtLeast(selection.timelineEndMs)
            Log.d(TAG, "playerCompletion key=${selection.key} nextTimelineMs=${timelineMs + 1L}")
            ensureSelection(timelineMs + 1L, autoPlay = true)
        }
        player.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "MediaPlayer error what=$what extra=$extra path=${selection.path}")
            failedPaths.add(selection.path)
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
            true
        }
        try {
            if (selection.path.startsWith("content://")) {
                player.setDataSource(appContext, Uri.parse(selection.path))
            } else {
                player.setDataSource(selection.path)
            }
            player.prepareAsync()
        } catch (error: Exception) {
            Log.w(TAG, "Failed to prepare audio source ${selection.path}: ${error.message}")
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

    private fun releasePlayer() {
        val player = mediaPlayer ?: return
        val wasPrepared = prepared
        mediaPlayer = null
        prepared = false
        preparing = false
        audioHandler.removeCallbacks(seekCompletionFallback)
        lastAppliedSelectionKey = null
        lastAppliedMediaSeekMs = Int.MIN_VALUE
        lastAppliedAutoPlay = false
        pendingStartAfterSeek = false
        pendingSeekTargetMs = Int.MIN_VALUE
        player.setOnPreparedListener(null)
        player.setOnSeekCompleteListener(null)
        player.setOnCompletionListener(null)
        player.setOnErrorListener(null)
        runCatching { if (wasPrepared) player.stop() }
        runCatching { player.release() }
        abandonAudioFocusIfNeeded()
    }

    private fun isPlayableMediaPath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path in failedPaths) return false
        if (path.startsWith("content://")) return true
        return File(path).exists()
    }

    private fun applyVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        runCatching { mediaPlayer?.setVolume(clamped, clamped) }
    }

    private fun applyPlaybackSpeed(selection: SourceSelection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val speed = effectivePlaybackSpeed(selection)
        if (abs(speed - 1.0f) < 0.01f) {
            return
        }
        runCatching {
            val player = mediaPlayer ?: return@runCatching
            val params = player.playbackParams ?: android.media.PlaybackParams()
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
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(targetMs.toLong(), MediaPlayer.SEEK_CLOSEST)
            } else {
                @Suppress("DEPRECATION")
                player.seekTo(targetMs)
            }
        }.onFailure {
            pendingStartAfterSeek = false
            pendingSeekTargetMs = Int.MIN_VALUE
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
        return mappedMs.coerceIn(selection.sourceInMs, upperBound.coerceAtLeast(selection.sourceInMs))
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
        if (audioMasterClockEnabled == enabled) return
        audioMasterClockEnabled = enabled
        mainHandler.post {
            previewViewProvider()?.setAudioMasterClockEnabled(enabled)
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
        synchronized(requestLock) {
            requestedTimelineMs = clampedTimelineMs
            requestedAutoPlay = autoPlay
        }
        Log.d(TAG, "$reason timelineMs=$clampedTimelineMs continuePlaying=$autoPlay")
        audioHandler.removeCallbacks(applyRequestedState)
        audioHandler.post(applyRequestedState)
    }

    private fun runOnAudioThread(action: () -> Unit) {
        if (Looper.myLooper() == audioThread.looper) {
            action()
        } else {
            audioHandler.post(action)
        }
    }
}

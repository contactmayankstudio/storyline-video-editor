package com.video.engine.audio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.video.engine.DesignSystem
import com.video.engine.UiToast as Toast
import java.io.File
import java.util.Locale

class VoiceoverController(
    private val activity: Activity,
    private val permissionRequestCode: Int,
    private val currentTimeMsProvider: () -> Long,
    private val onRecordingComplete: (path: String, startTimeMs: Long, durationMs: Long) -> Unit,
) {
    companion object {
        private const val TAG = "[Voiceover]"
    }

    private enum class State {
        READY,
        COUNTDOWN,
        RECORDING,
        REVIEW,
    }

    private var state = State.READY
    private var recorder: MediaRecorder? = null
    private var previewPlayer: MediaPlayer? = null
    private var outputFile: File? = null
    private var recordingStartMs = 0L
    private var timelineStartMs = 0L
    private var recordedDurationMs = 0L
    private var currentDialog: BottomSheetDialog? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var amplitudeRunnable: Runnable? = null
    private val recentAmplitudes = FloatArray(24) { 0.1f }
    private var amplitudeIndex = 0

    val isRecording get() = state == State.RECORDING && recorder != null

    private fun dp(v: Float): Float = v * activity.resources.displayMetrics.density
    private fun dpInt(v: Float): Int = (v * activity.resources.displayMetrics.density).toInt()

    fun showPanel() {
        if (!hasPermission()) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                permissionRequestCode,
            )
            return
        }
        buildAndShowDialog()
    }

    fun onPermissionGranted() {
        showPanel()
    }

    fun onPermissionDenied() {
        Toast.makeText(
            activity,
            "Microphone permission is required for voiceover recording.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun buildAndShowDialog() {
        currentDialog?.dismiss()
        stopPreviewPlayback()

        val dialog = BottomSheetDialog(activity)
        currentDialog = dialog
        state = State.READY
        timelineStartMs = currentTimeMsProvider().coerceAtLeast(0L)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#11151B"))
                cornerRadii = floatArrayOf(
                    dp(24f), dp(24f),
                    dp(24f), dp(24f),
                    0f, 0f, 0f, 0f,
                )
                setStroke(dpInt(1f), Color.parseColor("#1B222C"))
            }
            setPadding(dpInt(20f), dpInt(12f), dpInt(20f), dpInt(20f))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            root.setPadding(dpInt(20f), dpInt(12f), dpInt(20f), dpInt(20f) + navInsets)
            insets
        }

        // Drag handle
        val handle = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpInt(10f))
        }
        handle.addView(TextView(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999f)
                setColor(Color.parseColor("#3A4452"))
            }
            layoutParams = LinearLayout.LayoutParams(dpInt(36f), dpInt(4f))
        })
        root.addView(handle)

        // Header: Title + Close
        val header = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpInt(36f),
            ).also { it.bottomMargin = dpInt(12f) }
        }

        val titleView = TextView(activity).apply {
            text = "Voiceover"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START,
            )
        }
        header.addView(titleView)

        val closeBtn = ImageView(activity).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor("#8A99AD"))
            setPadding(dpInt(6f), dpInt(6f), dpInt(6f), dpInt(6f))
            layoutParams = FrameLayout.LayoutParams(
                dpInt(32f),
                dpInt(32f),
                Gravity.END or Gravity.CENTER_VERTICAL,
            )
            setOnClickListener {
                cancelRecording()
                dialog.dismiss()
            }
        }
        header.addView(closeBtn)
        root.addView(header)

        // Insertion time badge
        val timeBadge = TextView(activity).apply {
            text = "Start Position: ${formatTimestamp(timelineStartMs)}"
            setTextColor(Color.parseColor("#8A99AD"))
            textSize = 12f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#171C23"))
                cornerRadius = dp(6f)
                setStroke(dpInt(1f), Color.parseColor("#222A36"))
            }
            setPadding(dpInt(12f), dpInt(6f), dpInt(12f), dpInt(6f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dpInt(16f)
            }
        }
        root.addView(timeBadge)

        // Status Header (RECORDING / READY / REVIEW)
        val statusText = TextView(activity).apply {
            text = "Ready to record"
            setTextColor(Color.parseColor("#8A99AD"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = dpInt(6f) }
        }
        root.addView(statusText)

        // Big Elapsed Timer display: 00:00.0
        val timerDisplay = TextView(activity).apply {
            text = "00:00.0"
            setTextColor(Color.WHITE)
            textSize = 36f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = dpInt(16f) }
        }
        root.addView(timerDisplay)

        // Waveform / Amplitude Visualizer View
        val visualizerView = WaveformVisualizerView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpInt(44f),
            ).also { it.bottomMargin = dpInt(20f) }
        }
        root.addView(visualizerView)

        // Action Buttons Container
        val actionContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpInt(56f),
            )
        }
        root.addView(actionContainer)

        fun updateUiForState() {
            actionContainer.removeAllViews()
            when (state) {
                State.READY -> {
                    statusText.text = "Tap to Record"
                    statusText.setTextColor(Color.parseColor("#8A99AD"))
                    timerDisplay.text = "00:00.0"
                    timerDisplay.setTextColor(Color.WHITE)
                    visualizerView.clear()

                    val recordBtn = TextView(activity).apply {
                        text = "● Start Recording"
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#F85149"))
                            cornerRadius = dp(12f)
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dpInt(48f),
                        )
                        setOnClickListener {
                            startRecording(
                                onTimerTick = { elapsedMs ->
                                    timerDisplay.text = formatElapsedTimer(elapsedMs)
                                },
                                onAmplitude = { amp ->
                                    visualizerView.pushAmplitude(amp)
                                },
                                onStateChanged = { updateUiForState() },
                            )
                        }
                    }
                    actionContainer.addView(recordBtn)
                }
                State.RECORDING -> {
                    statusText.text = "● RECORDING"
                    statusText.setTextColor(Color.parseColor("#F85149"))
                    timerDisplay.setTextColor(Color.parseColor("#F85149"))

                    val cancelBtn = TextView(activity).apply {
                        text = "Cancel"
                        setTextColor(Color.parseColor("#8A99AD"))
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#171C23"))
                            setStroke(dpInt(1f), Color.parseColor("#252D3A"))
                            cornerRadius = dp(12f)
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            dpInt(48f),
                            1f,
                        ).also { it.marginEnd = dpInt(10f) }
                        setOnClickListener {
                            cancelRecording()
                            updateUiForState()
                        }
                    }
                    actionContainer.addView(cancelBtn)

                    val stopBtn = TextView(activity).apply {
                        text = "■ Done"
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#388BFD"))
                            cornerRadius = dp(12f)
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            dpInt(48f),
                            1.5f,
                        )
                        setOnClickListener {
                            stopRecording()
                            updateUiForState()
                        }
                    }
                    actionContainer.addView(stopBtn)
                }
                State.REVIEW -> {
                    statusText.text = "Recording Complete"
                    statusText.setTextColor(Color.parseColor("#388BFD"))
                    timerDisplay.setTextColor(Color.WHITE)
                    timerDisplay.text = formatElapsedTimer(recordedDurationMs)

                    val discardBtn = TextView(activity).apply {
                        text = "Discard"
                        setTextColor(Color.parseColor("#F85149"))
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#171C23"))
                            setStroke(dpInt(1f), Color.parseColor("#331818"))
                            cornerRadius = dp(12f)
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            dpInt(48f),
                            1f,
                        ).also { it.marginEnd = dpInt(8f) }
                        setOnClickListener {
                            discardRecording()
                            updateUiForState()
                        }
                    }
                    actionContainer.addView(discardBtn)

                    val previewBtn = TextView(activity).apply {
                        text = if (previewPlayer?.isPlaying == true) "❚❚ Pause" else "▶ Preview"
                        setTextColor(Color.WHITE)
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#1D2533"))
                            setStroke(dpInt(1f), Color.parseColor("#2F3D54"))
                            cornerRadius = dp(12f)
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            dpInt(48f),
                            1.1f,
                        ).also { it.marginEnd = dpInt(8f) }
                        setOnClickListener {
                            togglePreviewPlayback {
                                text = if (previewPlayer?.isPlaying == true) "❚❚ Pause" else "▶ Preview"
                            }
                        }
                    }
                    actionContainer.addView(previewBtn)

                    val acceptBtn = TextView(activity).apply {
                        text = "Add to Timeline"
                        setTextColor(Color.WHITE)
                        textSize = 13.5f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#388BFD"))
                            cornerRadius = dp(12f)
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            dpInt(48f),
                            1.4f,
                        )
                        setOnClickListener {
                            val file = outputFile
                            if (file != null && file.exists() && recordedDurationMs > 0L) {
                                stopPreviewPlayback()
                                dialog.dismiss()
                                onRecordingComplete(file.absolutePath, timelineStartMs, recordedDurationMs)
                            } else {
                                Toast.makeText(activity, "Recording not found", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    actionContainer.addView(acceptBtn)
                }
                State.COUNTDOWN -> {
                    // Handled in countdown loop
                }
            }
        }

        dialog.setOnDismissListener {
            cancelRecording()
            stopPreviewPlayback()
        }

        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.peekHeight = dpInt(360f)
        dialog.behavior.skipCollapsed = true
        dialog.show()

        updateUiForState()
    }

    private fun startRecording(
        onTimerTick: (elapsedMs: Long) -> Unit,
        onAmplitude: (amplitudeNorm: Float) -> Unit,
        onStateChanged: () -> Unit,
    ) {
        if (state == State.RECORDING) return
        val dir = File(activity.filesDir, "voiceovers").apply { mkdirs() }
        val file = File(dir, "vo_${System.currentTimeMillis()}.m4a")
        outputFile = file
        timelineStartMs = currentTimeMsProvider().coerceAtLeast(0L)

        recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(activity) else @Suppress("DEPRECATION") MediaRecorder()
        ).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setOutputFile(file.absolutePath)
            runCatching { prepare() }.onFailure {
                Log.e(TAG, "prepare failed: ${it.message}")
                Toast.makeText(activity, "Microphone initialization failed", Toast.LENGTH_SHORT).show()
                recorder = null
                state = State.READY
                onStateChanged()
                return
            }
            runCatching { start() }.onFailure {
                Log.e(TAG, "start recording failed: ${it.message}")
                Toast.makeText(activity, "Failed to start recording", Toast.LENGTH_SHORT).show()
                recorder = null
                state = State.READY
                onStateChanged()
                return
            }
        }

        recordingStartMs = SystemClock.elapsedRealtime()
        state = State.RECORDING
        onStateChanged()

        // Elapsed Timer Runnable (50ms interval)
        timerRunnable = object : Runnable {
            override fun run() {
                if (state != State.RECORDING) return
                val elapsed = SystemClock.elapsedRealtime() - recordingStartMs
                onTimerTick(elapsed)
                mainHandler.postDelayed(this, 50L)
            }
        }
        mainHandler.post(timerRunnable!!)

        // Amplitude Meter Runnable (80ms interval)
        amplitudeRunnable = object : Runnable {
            override fun run() {
                if (state != State.RECORDING) return
                val maxAmp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                val normalized = (maxAmp.toFloat() / 32767f).coerceIn(0.05f, 1.0f)
                onAmplitude(normalized)
                mainHandler.postDelayed(this, 80L)
            }
        }
        mainHandler.post(amplitudeRunnable!!)

        Log.d(TAG, "Recording started: ${file.absolutePath} startMs=$timelineStartMs")
    }

    private fun stopRecording() {
        stopTimerCallbacks()
        val rec = recorder
        recorder = null
        val file = outputFile

        if (rec != null) {
            runCatching {
                rec.stop()
                rec.release()
            }.onFailure { Log.w(TAG, "Recorder stop warning: ${it.message}") }
        }

        val duration = SystemClock.elapsedRealtime() - recordingStartMs
        recordedDurationMs = duration

        if (duration < 400L || file == null || !file.exists() || file.length() == 0L) {
            Toast.makeText(activity, "Recording too short", Toast.LENGTH_SHORT).show()
            file?.delete()
            state = State.READY
            return
        }

        state = State.REVIEW
        Log.d(TAG, "Recording completed: ${file.absolutePath} duration=${duration}ms")
    }

    private fun cancelRecording() {
        stopTimerCallbacks()
        val rec = recorder
        recorder = null
        if (rec != null) {
            runCatching {
                rec.stop()
                rec.release()
            }
        }
        outputFile?.delete()
        outputFile = null
        state = State.READY
    }

    private fun discardRecording() {
        stopPreviewPlayback()
        outputFile?.delete()
        outputFile = null
        recordedDurationMs = 0L
        state = State.READY
    }

    private fun togglePreviewPlayback(onStatusChanged: () -> Unit) {
        val file = outputFile ?: return
        val player = previewPlayer
        if (player != null && player.isPlaying) {
            player.pause()
            onStatusChanged()
            return
        }
        if (player != null) {
            player.start()
            onStatusChanged()
            return
        }
        val newPlayer = MediaPlayer()
        previewPlayer = newPlayer
        try {
            newPlayer.setDataSource(file.absolutePath)
            newPlayer.prepare()
            newPlayer.setOnCompletionListener {
                onStatusChanged()
            }
            newPlayer.start()
            onStatusChanged()
        } catch (e: Exception) {
            Log.e(TAG, "Preview playback failed: ${e.message}")
            Toast.makeText(activity, "Unable to preview audio", Toast.LENGTH_SHORT).show()
            stopPreviewPlayback()
            onStatusChanged()
        }
    }

    private fun stopPreviewPlayback() {
        previewPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) player.stop()
                player.release()
            }
        }
        previewPlayer = null
    }

    private fun stopTimerCallbacks() {
        timerRunnable?.let { mainHandler.removeCallbacks(it) }
        timerRunnable = null
        amplitudeRunnable?.let { mainHandler.removeCallbacks(it) }
        amplitudeRunnable = null
    }

    fun release() {
        cancelRecording()
        stopPreviewPlayback()
        currentDialog?.dismiss()
        currentDialog = null
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun formatElapsedTimer(elapsedMs: Long): String {
        val safeMs = elapsedMs.coerceAtLeast(0L)
        val tenths = (safeMs % 1000L) / 100L
        val totalSec = safeMs / 1000L
        val sec = totalSec % 60L
        val min = totalSec / 60L
        return String.format(Locale.US, "%02d:%02d.%d", min, sec, tenths)
    }

    private fun formatTimestamp(timeMs: Long): String {
        val safeMs = timeMs.coerceAtLeast(0L)
        val tenths = (safeMs % 1000L) / 100L
        val totalSec = safeMs / 1000L
        val sec = totalSec % 60L
        val min = totalSec / 60L
        return String.format(Locale.US, "%02d:%02d.%d", min, sec, tenths)
    }

    // Dynamic amplitude visualizer for recording feedback
    private class WaveformVisualizerView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#388BFD")
            strokeWidth = 3f * context.resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
        }
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#151B24")
        }
        private val amplitudes = FloatArray(28) { 0.08f }

        fun pushAmplitude(amp: Float) {
            for (i in 0 until amplitudes.size - 1) {
                amplitudes[i] = amplitudes[i + 1]
            }
            amplitudes[amplitudes.size - 1] = amp.coerceIn(0.08f, 1.0f)
            invalidate()
        }

        fun clear() {
            for (i in amplitudes.indices) {
                amplitudes[i] = 0.08f
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            canvas.drawRoundRect(0f, 0f, w, h, 12f, 12f, bgPaint)

            val barCount = amplitudes.size
            val step = w / (barCount + 1)
            val midY = h / 2f
            val maxH = (h * 0.78f) / 2f

            for (i in 0 until barCount) {
                val x = step * (i + 1)
                val amp = amplitudes[i] * maxH
                canvas.drawLine(x, midY - amp, x, midY + amp, paint)
            }
        }
    }
}

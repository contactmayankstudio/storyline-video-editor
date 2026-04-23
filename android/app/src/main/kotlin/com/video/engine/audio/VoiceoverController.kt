package com.video.engine.audio

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.video.engine.UiToast as Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.video.engine.ModernSheet
import java.io.File

class VoiceoverController(
    private val activity: Activity,
    private val permissionRequestCode: Int,
    private val currentTimeMsProvider: () -> Long,
    private val onRecordingComplete: (path: String, startTimeMs: Long, durationMs: Long) -> Unit,
) {
    companion object {
        private const val TAG = "[Voiceover]"
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var recordingStartMs = 0L
    private var timelineStartMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var elapsedSec = 0

    val isRecording get() = recorder != null

    fun showPanel() {
        if (!hasPermission()) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                permissionRequestCode,
            )
            return
        }
        ModernSheet.show(activity, "Voiceover") {
            chips(
                if (isRecording) "⏺ Recording..." else "Ready",
                if (isRecording) listOf("Stop") else listOf("Start Recording"),
                -1,
            ) { _, opt ->
                if (opt == "Start Recording") startRecording()
                else stopRecording()
            }
        }
    }

    fun onPermissionGranted() {
        showPanel()
    }

    private fun startRecording() {
        if (isRecording) return
        val dir = File(activity.cacheDir, "voiceovers").apply { mkdirs() }
        val file = File(dir, "vo_${System.currentTimeMillis()}.m4a")
        outputFile = file
        timelineStartMs = currentTimeMsProvider()

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
                Toast.makeText(activity, "Mic prepare failed", Toast.LENGTH_SHORT).show()
                recorder = null
                return
            }
            start()
        }
        recordingStartMs = System.currentTimeMillis()
        elapsedSec = 0
        Log.d(TAG, "Recording started: ${file.absolutePath} timeline=$timelineStartMs")
        Toast.makeText(activity, "Recording started...", Toast.LENGTH_SHORT).show()

        // Re-show panel with Stop button
        showPanel()
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        val file = outputFile ?: return
        runCatching {
            rec.stop()
            rec.release()
        }.onFailure { Log.e(TAG, "stop failed: ${it.message}") }
        recorder = null

        val durationMs = System.currentTimeMillis() - recordingStartMs
        if (durationMs < 300L || !file.exists() || file.length() == 0L) {
            Toast.makeText(activity, "Recording too short", Toast.LENGTH_SHORT).show()
            file.delete()
            return
        }
        Log.d(TAG, "Recording complete: ${file.absolutePath} duration=${durationMs}ms")
        Toast.makeText(activity, "Voiceover saved (${durationMs / 1000}s)", Toast.LENGTH_SHORT).show()
        onRecordingComplete(file.absolutePath, timelineStartMs, durationMs)
    }

    fun release() {
        runCatching { recorder?.stop(); recorder?.release() }
        recorder = null
    }

    private fun hasPermission() = ContextCompat.checkSelfPermission(
        activity, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
}

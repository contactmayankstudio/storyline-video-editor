package com.video.engine


import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
import android.util.Log

/**
 * Video Preview Activity with Timeline Scrubbing.
 * 
 * Demonstrates real-time frame preview while scrubbing through a video timeline.
 * 
 * Features:
 * - SurfaceView GPU preview
 * - SeekBar for timeline scrubbing
 * - Real-time frame render on drag (no playback)
 * - Touch feedback with time display
 * 
 * Architecture:
 * - UI SeekBar → onProgressChanged() → nativeRenderFrame(timeMs)
 * - No playback thread spawned
 * - One EGL context (reused from preview)
 * - GPU rendering only
 * 
 * Scrubbing vs Playback:
 * - Scrubbing: User drag → single frame render → instant preview
 * - Playback: Continuous loop → frame-by-frame at video FPS
 * 
 * This keeps preview scrubbing efficient without starting full playback.
 */
class PreviewActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "PreviewActivity"
    }

    private lateinit var previewView: VideoPreviewView
    private lateinit var seekBar: SeekBar
    private lateinit var timeTextView: TextView
    private var videoDurationMs: Long = 0L
    private var isScrubbingActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Create layout programmatically
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Preview view (takes most of space)
        previewView = VideoPreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f  // weight = 1, fills remaining space
            )
        }
        rootLayout.addView(previewView)

        // Time display
        timeTextView = TextView(this).apply {
            text = "0:00 / 0:00"
            textSize = 14f
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rootLayout.addView(timeTextView)

        // SeekBar for scrubbing
        seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 8, 16, 8)
            }
            progress = 0
            max = 1000  // Normalized 0-1000
            
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        // User dragging seekbar
                        handleScrubbing(progress)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    Log.d(TAG, "[Scrub] Start dragging")
                    isScrubbingActive = true
                    // Stop any playback
                    if (::previewView.isInitialized) {
                        previewView.stopPlayback()
                    }
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    Log.d(TAG, "[Scrub] Stop dragging")
                    isScrubbingActive = false
                    // Render final frame and stay there
                    if (::previewView.isInitialized) {
                        val currentProgress = seekBar.progress
                        val timeMs = (currentProgress.toLong() * videoDurationMs) / 1000L
                        previewView.seekToTime(timeMs)
                        updateTimeDisplay(timeMs)
                    }
                }
            })
        }
        rootLayout.addView(seekBar)

        setContentView(rootLayout)

        // Request permissions
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                1
            )
        } else {
            loadVideo()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            loadVideo()
        }
    }

    private fun loadVideo() {
        // Try to load video from intent extra if available, else fallback to the camera sample path.
        val videoPathExtra = intent?.getStringExtra("video_path")
        val requestedVideoPath = if (!videoPathExtra.isNullOrEmpty()) {
            videoPathExtra
        } else {
            MediaPathResolver.defaultCameraVideoFile().absolutePath
        }
        val videoPath = MediaPathResolver.normalizeForFileAccess(requestedVideoPath)
        
        if (!java.io.File(videoPath).exists()) {
            Log.e(TAG, "Video not found: $videoPath")
            timeTextView.text = "Video not found"
            return
        }

        Log.d(TAG, "Loading video: $videoPath")
        
        // Load video
        if (!previewView.loadVideo(videoPath)) {
            Log.e(TAG, "Failed to load video")
            timeTextView.text = "Failed to load video"
            return
        }

        // Get actual duration from native side
        videoDurationMs = previewView.getDuration()
        if (videoDurationMs <= 0) {
            Log.w(TAG, "Invalid duration, using default")
            videoDurationMs = 10000  // Fallback: 10 seconds
        }
        
        // Set seekbar range based on duration
        seekBar.max = (videoDurationMs / 1000).toInt()  // max = duration in seconds
        
        // Render first frame
        previewView.seekToTime(0)
        updateTimeDisplay(0)
        
        Log.d(TAG, "[Scrub] Video loaded, duration: ${videoDurationMs}ms")
    }

    private fun handleScrubbing(progress: Int) {
        if (videoDurationMs == 0L) return

        // Convert normalized progress (0-1000) to time in milliseconds
        val timeMs = (progress.toLong() * videoDurationMs) / 1000L

        // Render frame at this time (no playback)
        // Add null check to prevent crash if previewView state is unexpectedly null
        if (::previewView.isInitialized) {
            previewView.seekToTime(timeMs)
        } else {
            Log.w(TAG, "previewView not initialized during scrubbing")
        }

        // Update time display
        updateTimeDisplay(timeMs)

        Log.d(TAG, "[Scrub] time=${timeMs}ms")
    }

    private fun updateTimeDisplay(timeMs: Long) {
        val currentSec = timeMs / 1000
        val currentMin = currentSec / 60
        val currentSecMod = currentSec % 60

        val totalSec = videoDurationMs / 1000
        val totalMin = totalSec / 60
        val totalSecMod = totalSec % 60

        timeTextView.text = String.format(
            "%d:%02d / %d:%02d",
            currentMin, currentSecMod,
            totalMin, totalSecMod
        )
    }

    override fun onResume() {
        super.onResume()
        if (::previewView.isInitialized) {
            previewView.onResume()
        }
    }

    override fun onPause() {
        if (::previewView.isInitialized) {
            previewView.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::previewView.isInitialized) {
            previewView.stopPlayback()
        }
        super.onDestroy()
    }
}

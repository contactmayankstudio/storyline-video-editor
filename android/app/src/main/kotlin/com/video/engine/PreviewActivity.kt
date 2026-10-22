package com.video.engine


import android.app.PictureInPictureParams
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Video Preview Activity with Timeline Scrubbing and Picture-in-Picture.
 * 
 * Demonstrates real-time frame preview while scrubbing through a video timeline,
 * with edge-to-edge display and Picture-in-Picture (PiP) support.
 */
class PreviewActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "PreviewActivity"
    }

    private lateinit var previewView: VideoPreviewView
    private lateinit var seekBar: SeekBar
    private lateinit var timeTextView: TextView
    private var controlsLayout: LinearLayout? = null
    private var videoDurationMs: Long = 0L
    private var isScrubbingActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        // Create layout programmatically
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
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

        val bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        controlsLayout = bottomControls

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Time display
        timeTextView = TextView(this).apply {
            text = "0:00 / 0:00"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        headerRow.addView(timeTextView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pipButton = Button(this).apply {
                text = "PiP"
                textSize = 12f
                setOnClickListener {
                    enterPipMode()
                }
            }
            headerRow.addView(pipButton)
        }
        bottomControls.addView(headerRow)

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
                        handleScrubbing(progress)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    Log.d(TAG, "[Scrub] Start dragging")
                    isScrubbingActive = true
                    if (::previewView.isInitialized) {
                        previewView.stopPlayback()
                    }
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    Log.d(TAG, "[Scrub] Stop dragging")
                    isScrubbingActive = false
                    if (::previewView.isInitialized) {
                        val currentProgress = seekBar.progress
                        val timeMs = (currentProgress.toLong() * videoDurationMs) / 1000L
                        previewView.seekToTime(timeMs)
                        updateTimeDisplay(timeMs)
                    }
                }
            })
        }
        bottomControls.addView(seekBar)
        rootLayout.addView(bottomControls)

        setContentView(rootLayout)

        // Load video
        loadVideo()
    }

    fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && videoDurationMs > 0) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        controlsLayout?.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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

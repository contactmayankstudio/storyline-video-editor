package com.video.engine.ai

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.video.engine.VideoEditorActivity
import com.video.engine.overlay.TextOverlay

/**
 * Status Maker Pro Engine.
 * Converts 1 photo (or current project clips) into a high-energy, rhythmic,
 * beat-synced status video with dynamic Ken Burns zooms, transitions,
 * lyrical text overlays, and trending background music (Storyline Pro Status Maker).
 */
object AiStatusMakerProPipeline {
    private const val TAG = "StatusMakerPro"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun execute(
        activity: VideoEditorActivity,
        dispatcher: AiCommandDispatcher,
        style: String = "Trending",
        onProgress: ((step: Int, total: Int, label: String) -> Unit)? = null,
        onComplete: ((success: Boolean, feedback: String) -> Unit)? = null,
    ): Boolean {
        // Step 1: Force vertical 9:16 aspect ratio (Status / Reels / Shorts standard)
        onProgress?.invoke(1, 5, "9:16 Vertical Status Aspect Ratio set ho raha hai...")
        dispatcher.setAspectRatio("9:16")

        // Step 2: Dynamic Camera Movement & Kinetic Zooms (Ken Burns Choreography)
        onProgress?.invoke(2, 5, "Kinetic motion aur dynamic beat zooms apply ho rahe hain...")
        dispatcher.dynamicZoom("push_in")

        // Step 3: Color Pop & Cinematic Filter
        onProgress?.invoke(3, 5, "Vibrant cinematic color filter apply ho raha hai...")
        dispatcher.applyColorGrade("Vibrant Pop")

        // Step 4: Add high-energy trending beat soundtrack
        onProgress?.invoke(4, 5, "Trending attitude bass beat add ho raha hai...")
        dispatcher.addMusic("attitude")

        // Step 5: Add stylish lyrical status overlays with glow
        mainHandler.postDelayed({
            onProgress?.invoke(5, 5, "Lyrical status titles aur beat typography add ho rahi hai...")
            attachStatusLyricalOverlays(activity)

            // Start playback from 0 so the user immediately sees the magical video playing!
            mainHandler.postDelayed({
                dispatcher.playVideo()
                onComplete?.invoke(
                    true,
                    "🔥 **Status Maker Pro Complete!** 🚀\n\n" +
                    "✅ 9:16 Fullscreen Reels format\n" +
                    "✅ Dynamic kinetic zooms & motion\n" +
                    "✅ Cinematic vibrant color grading\n" +
                    "✅ High-energy attitude bass drop beat\n" +
                    "✅ Stylish lyrical status overlays\n\n" +
                    "Aapki jordaar status video preview ke liye chal rahi hai! 💥"
                )
            }, 300)
        }, 400)

        return true
    }

    private fun attachStatusLyricalOverlays(activity: VideoEditorActivity) {
        try {
            val durationMs = activity.currentProjectDurationMs().coerceAtLeast(10000L)
            val beatDur = durationMs / 3

            // Lyrical 1: Intro Hook
            activity.addTextOverlay(
                TextOverlay(
                    id = (System.currentTimeMillis() % 100000).toInt() + 1,
                    text = "FEEL THE VIBE ✨",
                    x = 0.5f,
                    y = 0.22f,
                    scale = 1.25f,
                    rotation = 0f,
                    color = Color.parseColor("#FFE500"),
                    fontSize = 28f,
                    startTimeMs = 0,
                    endTimeMs = beatDur.toInt(),
                ),
                "Status: Feel The Vibe"
            )

            // Lyrical 2: Beat Drop
            activity.addTextOverlay(
                TextOverlay(
                    id = (System.currentTimeMillis() % 100000).toInt() + 2,
                    text = "WAIT FOR THE DROP 🔥",
                    x = 0.5f,
                    y = 0.80f,
                    scale = 1.35f,
                    rotation = 0f,
                    color = Color.parseColor("#FF3366"),
                    fontSize = 30f,
                    startTimeMs = beatDur.toInt(),
                    endTimeMs = (beatDur * 2).toInt(),
                ),
                "Status: Beat Drop"
            )

            // Lyrical 3: Outro
            activity.addTextOverlay(
                TextOverlay(
                    id = (System.currentTimeMillis() % 100000).toInt() + 3,
                    text = "NEVER GIVE UP ⚡",
                    x = 0.5f,
                    y = 0.50f,
                    scale = 1.20f,
                    rotation = 0f,
                    color = Color.WHITE,
                    fontSize = 26f,
                    startTimeMs = (beatDur * 2).toInt(),
                    endTimeMs = durationMs.toInt(),
                ),
                "Status: Outro"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach lyrical overlays", e)
        }
    }
}

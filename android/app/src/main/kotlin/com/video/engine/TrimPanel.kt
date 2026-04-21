package com.video.engine

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import kotlin.math.roundToInt

/**
 * Trim panel for scrubbing source media and setting in/out points visually.
 */
class TrimPanel(
    private val activity: Activity,
    private val clipId: Int,
    private val sourcePath: String,
    private val sourceInMs: Long,
    private val sourceOutMs: Long,
    private val sourceDurationMs: Long,
    private val onApply: (newInMs: Long, newOutMs: Long) -> Unit,
) {
    private var inMs = sourceInMs
    private var outMs = sourceOutMs

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(px(16), px(12), px(16), px(32))
        }

        // Handle
        root.addView(View(activity).apply {
            setBackgroundColor(Color.parseColor("#555555"))
            layoutParams = LinearLayout.LayoutParams(px(40), px(4)).also {
                it.gravity = Gravity.CENTER; it.bottomMargin = px(12)
            }
        })

        // Title
        root.addView(TextView(activity).apply {
            text = "Trim Clip"
            textSize = 16f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, 0, 0, px(12))
        })

        // Thumbnail preview
        val thumbView = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(160))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#111111"))
        }
        root.addView(thumbView)

        // Time display
        val timeLabel = TextView(activity).apply {
            text = formatMs(inMs)
            textSize = 13f; setTextColor(Color.parseColor("#4db8ff"))
            gravity = Gravity.CENTER
            setPadding(0, px(6), 0, px(4))
        }
        root.addView(timeLabel)

        // Scrub seekbar (full source)
        val scrubBar = SeekBar(activity).apply {
            max = 1000
            progress = if (sourceDurationMs > 0) (inMs * 1000 / sourceDurationMs).toInt() else 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4db8ff"))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setPadding(0, px(4), 0, px(4))
        }
        root.addView(scrubBar)

        // In/Out labels row
        val inOutRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(8), 0, px(4))
        }
        val inLabel = TextView(activity).apply {
            text = "In: ${formatMs(inMs)}"; textSize = 12f
            setTextColor(Color.parseColor("#88FF88"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val outLabel = TextView(activity).apply {
            text = "Out: ${formatMs(outMs)}"; textSize = 12f
            setTextColor(Color.parseColor("#FF8888"))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        inOutRow.addView(inLabel); inOutRow.addView(outLabel)
        root.addView(inOutRow)

        // In/Out set buttons
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(4), 0, px(12))
        }
        fun actionBtn(text: String, color: Int, action: () -> Unit) = TextView(activity).apply {
            this.text = text; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(0, px(40), 1f)
                .also { it.setMargins(px(4), 0, px(4), 0) }
            setOnClickListener { action() }
        }

        var scrubMs = inMs
        btnRow.addView(actionBtn("Set In", Color.parseColor("#1B5E20")) {
            inMs = scrubMs.coerceAtMost(outMs - 100)
            inLabel.text = "In: ${formatMs(inMs)}"
        })
        btnRow.addView(actionBtn("Set Out", Color.parseColor("#B71C1C")) {
            outMs = scrubMs.coerceAtLeast(inMs + 100)
            outLabel.text = "Out: ${formatMs(outMs)}"
        })
        btnRow.addView(actionBtn("Reset", Color.parseColor("#333333")) {
            inMs = 0; outMs = sourceDurationMs
            inLabel.text = "In: ${formatMs(inMs)}"
            outLabel.text = "Out: ${formatMs(outMs)}"
            scrubBar.progress = 0
        })
        root.addView(btnRow)

        // Apply button
        root.addView(TextView(activity).apply {
            text = "Apply Trim"; textSize = 15f; gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#2196F3"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(48)
            )
            setOnClickListener {
                onApply(inMs, outMs)
                dialog.dismiss()
            }
        })

        // Scrub logic
        val retriever = MediaMetadataRetriever()
        try {
            if (sourcePath.startsWith("content://"))
                retriever.setDataSource(activity, Uri.parse(sourcePath))
            else if (File(sourcePath).exists())
                retriever.setDataSource(sourcePath)
        } catch (_: Exception) {}

        fun updateThumb(ms: Long) {
            scrubMs = ms
            timeLabel.text = formatMs(ms)
            try {
                val bmp = retriever.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bmp != null) thumbView.setImageBitmap(bmp)
            } catch (_: Exception) {}
        }

        scrubBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) updateThumb(p * sourceDurationMs / 1000)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Load initial frame
        updateThumb(inMs)

        dialog.setOnDismissListener { try { retriever.release() } catch (_: Exception) {} }
        dialog.setContentView(root)
        dialog.show()
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000; val m = s / 60
        return "%d:%02d.%03d".format(m, s % 60, ms % 1000)
    }

    private fun px(dp: Int) = (dp * activity.resources.displayMetrics.density).roundToInt()
}

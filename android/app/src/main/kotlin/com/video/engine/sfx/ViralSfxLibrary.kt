package com.video.engine.sfx

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

data class ViralSfxItem(
    val id: String,
    val name: String,
    val category: String,
    val emoji: String,
    val durationMs: Long,
    val description: String,
)

object ViralSfxLibrary {

    private const val TAG = "ViralSfxLibrary"
    private const val SAMPLE_RATE = 44100
    private var previewPlayer: MediaPlayer? = null

    val items: List<ViralSfxItem> = listOf(
        ViralSfxItem("vine_boom", "Vine Boom", "🔥 Memes", "💥", 1200L, "Deep sub-bass booming drop"),
        ViralSfxItem("whoosh_fast", "Whoosh Fast", "⚡ Transitions", "💨", 400L, "Clean high-speed whip sweep"),
        ViralSfxItem("camera_shutter", "Camera Click", "📸 SFX", "📷", 350L, "Crisp mechanical shutter click"),
        ViralSfxItem("success_chime", "Success Chime", "✨ UI/Viral", "🔔", 900L, "Bright crystal bell chime"),
        ViralSfxItem("record_scratch", "Record Scratch", "🔥 Memes", "💿", 600L, "DJ vinyl zip freeze stop"),
        ViralSfxItem("cyber_glitch", "Cyber Glitch", "⚡ Transitions", "👾", 500L, "8-bit digital stutter blast"),
        ViralSfxItem("dramatic_hit", "Dramatic Hit", "🔥 Memes", "🥁", 1400L, "Dun Dun Dun orchestral impact"),
        ViralSfxItem("pop_bubble", "Bubble Pop", "✨ UI/Viral", "🫧", 250L, "Resonant water pop sound"),
        ViralSfxItem("airhorn_meme", "Airhorn Blast", "🔥 Memes", "🚨", 1100L, "Triple fanfare MLG airhorn"),
        ViralSfxItem("tick_countdown", "Countdown Tick", "⚡ Transitions", "⏱️", 200L, "Clean studio woodblock tick"),
    )

    fun getOrGenerateSfxFile(context: Context, item: ViralSfxItem): File {
        val dir = File(context.cacheDir, "viral_sfx").apply { if (!exists()) mkdirs() }
        val file = File(dir, "${item.id}.wav")
        if (file.exists() && file.length() > 100L) {
            return file
        }

        val samples = synthesizeSfx(item.id, (item.durationMs * SAMPLE_RATE / 1000).toInt())
        writeWavFile(file, samples, SAMPLE_RATE)
        return file
    }

    private fun synthesizeSfx(id: String, totalSamples: Int): ShortArray {
        val out = ShortArray(totalSamples)
        val rnd = Random(42)

        when (id) {
            "vine_boom" -> {
                // Sub-bass sweep: starts at 110Hz, curves down to 32Hz with exponential decay and slight saturation
                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val freq = 110.0 * exp(-3.2 * t) + 32.0
                    val env = exp(-2.4 * t)
                    val raw = sin(2.0 * PI * freq * t) * env
                    val saturated = (raw * 1.5).coerceIn(-1.0, 1.0)
                    out[i] = (saturated * 31000).toInt().toShort()
                }
            }
            "whoosh_fast" -> {
                // Swept bandpass filtered noise
                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / totalSamples
                    val env = sin(PI * t).coerceAtLeast(0.0)
                    val noise = (rnd.nextDouble() * 2.0 - 1.0) * env
                    out[i] = (noise * 26000).toInt().toShort()
                }
            }
            "camera_shutter" -> {
                // Two mechanical clicks: t=0 and t=0.12
                val click1End = (0.04 * SAMPLE_RATE).toInt().coerceAtMost(totalSamples)
                val click2Start = (0.10 * SAMPLE_RATE).toInt().coerceAtMost(totalSamples)
                val click2End = (0.16 * SAMPLE_RATE).toInt().coerceAtMost(totalSamples)
                for (i in 0 until click1End) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val env = exp(-120.0 * t)
                    out[i] = (sin(2.0 * PI * 2200.0 * t) * env * 28000).toInt().toShort()
                }
                for (i in click2Start until click2End) {
                    val t = (i - click2Start).toDouble() / SAMPLE_RATE
                    val env = exp(-100.0 * t)
                    out[i] = (sin(2.0 * PI * 1400.0 * t) * env * 30000).toInt().toShort()
                }
            }
            "success_chime" -> {
                // C6 (1046 Hz) and E6 (1318 Hz) crystal sine chime
                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val env = exp(-3.8 * t)
                    val wave = 0.55 * sin(2.0 * PI * 1046.5 * t) + 0.45 * sin(2.0 * PI * 1318.5 * t)
                    out[i] = (wave * env * 29000).toInt().toShort()
                }
            }
            "record_scratch" -> {
                // Modulated chirp scratching back and forth
                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val freq = 600.0 + 800.0 * sin(2.0 * PI * 6.5 * t)
                    val env = exp(-2.0 * t)
                    out[i] = (sin(2.0 * PI * freq * t) * env * 27000).toInt().toShort()
                }
            }
            "cyber_glitch" -> {
                // 8-bit square wave frequency-hopping bursts
                var phase = 0.0
                for (i in 0 until totalSamples) {
                    val step = (i / (SAMPLE_RATE / 25)) % 6
                    val freq = when (step) {
                        0 -> 440.0; 1 -> 880.0; 2 -> 220.0; 3 -> 1760.0; 4 -> 330.0; else -> 1320.0
                    }
                    phase += 2.0 * PI * freq / SAMPLE_RATE
                    val sq = if (sin(phase) > 0) 0.65 else -0.65
                    val env = 1.0 - (i.toDouble() / totalSamples)
                    out[i] = (sq * env * 25000).toInt().toShort()
                }
            }
            "dramatic_hit" -> {
                // Dun Dun Dun low triad punch
                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val env = exp(-2.0 * t)
                    val wave = 0.4 * sin(2.0 * PI * 65.4 * t) + 0.35 * sin(2.0 * PI * 98.0 * t) + 0.25 * sin(2.0 * PI * 130.8 * t)
                    out[i] = (wave * env * 31000).toInt().toShort()
                }
            }
            "pop_bubble" -> {
                // Upward resonant chirp (350Hz -> 1100Hz in 50ms)
                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val sweepT = (i.toDouble() / (0.05 * SAMPLE_RATE)).coerceIn(0.0, 1.0)
                    val freq = 350.0 + 750.0 * sweepT
                    val env = exp(-20.0 * t)
                    out[i] = (sin(2.0 * PI * freq * t) * env * 29000).toInt().toShort()
                }
            }
            "airhorn_meme" -> {
                // Triple blast harmonic horn
                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val blastIndex = (t / 0.35).toInt()
                    val tInBlast = t - (blastIndex * 0.35)
                    val active = tInBlast in 0.0..0.22
                    if (active) {
                        val wave = 0.45 * sin(2.0 * PI * 466.16 * t) + 0.35 * sin(2.0 * PI * 622.25 * t) + 0.20 * sin(2.0 * PI * 932.33 * t)
                        out[i] = (wave * 30000).toInt().toShort()
                    } else {
                        out[i] = 0
                    }
                }
            }
            "tick_countdown" -> {
                // Short crisp wooden tick
                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val env = exp(-85.0 * t)
                    out[i] = (sin(2.0 * PI * 1200.0 * t) * env * 28000).toInt().toShort()
                }
            }
            else -> {
                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / SAMPLE_RATE
                    out[i] = (sin(2.0 * PI * 440.0 * t) * exp(-3.0 * t) * 20000).toInt().toShort()
                }
            }
        }
        return out
    }

    private fun writeWavFile(file: File, samples: ShortArray, sampleRate: Int) {
        val totalAudioLen = (samples.size * 2).toLong()
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = (sampleRate * channels * 2).toLong()

        val header = ByteArray(44)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(totalDataLen.toInt())
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16) // Subchunk1Size
        buf.putShort(1) // AudioFormat PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate.toInt())
        buf.putShort((channels * 2).toShort()) // BlockAlign
        buf.putShort(16) // BitsPerSample
        buf.put("data".toByteArray())
        buf.putInt(totalAudioLen.toInt())

        FileOutputStream(file).use { fos ->
            fos.write(header)
            val sampleBuffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                sampleBuffer.putShort(s)
            }
            fos.write(sampleBuffer.array())
        }
    }

    fun playPreview(context: Context, item: ViralSfxItem) {
        try {
            stopPreview()
            val file = getOrGenerateSfxFile(context, item)
            previewPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.fromFile(file))
                prepare()
                start()
                setOnCompletionListener {
                    it.release()
                    if (previewPlayer === it) previewPlayer = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to preview SFX", e)
        }
    }

    fun stopPreview() {
        try {
            previewPlayer?.stop()
            previewPlayer?.release()
        } catch (_: Exception) {}
        previewPlayer = null
    }

    fun showSfxSoundBoard(
        activity: Context,
        onAddSfxToTimeline: (item: ViralSfxItem, file: File) -> Unit,
    ) {
        val dialog = BottomSheetDialog(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10131A"))
                cornerRadii = floatArrayOf(
                    24f * density, 24f * density,
                    24f * density, 24f * density,
                    0f, 0f, 0f, 0f
                )
                setStroke(dp(1f), Color.parseColor("#1E2430"))
            }
            setPadding(dp(16f), dp(16f), dp(16f), dp(24f))
        }

        // Header Title
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12f))
        }
        val title = TextView(activity).apply {
            text = "🔊 Viral Reel Meme SFX"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(activity).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.parseColor("#8E99A8"))
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(title)
        header.addView(closeBtn)
        root.addView(header)

        // Subtitle
        val subtitle = TextView(activity).apply {
            text = "Tap to test sound • Click '⚡ ADD' to attach at playhead"
            textSize = 12f
            setTextColor(Color.parseColor("#7D8898"))
            setPadding(0, 0, 0, dp(12f))
        }
        root.addView(subtitle)

        // Recycler View
        val recyclerView = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(340f)
            )
        }

        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = items.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val itemView = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        dp(58f)
                    ).apply { setMargins(0, dp(4f), 0, dp(4f)) }
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#181D26"))
                        cornerRadius = 14f * density
                        setStroke(dp(1f), Color.parseColor("#262E3D"))
                    }
                    setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                }
                return object : RecyclerView.ViewHolder(itemView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = items[position]
                val layout = holder.itemView as LinearLayout
                layout.removeAllViews()

                // Emoji badge
                val emojiView = TextView(layout.context).apply {
                    text = item.emoji
                    textSize = 22f
                    setPadding(0, 0, dp(12f), 0)
                }
                layout.addView(emojiView)

                // Name & description
                val textContainer = LinearLayout(layout.context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val nameView = TextView(layout.context).apply {
                    text = item.name
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                val descView = TextView(layout.context).apply {
                    text = "${item.category} • ${item.durationMs}ms"
                    textSize = 11f
                    setTextColor(Color.parseColor("#8E99A8"))
                }
                textContainer.addView(nameView)
                textContainer.addView(descView)
                layout.addView(textContainer)

                // Preview tap on whole card
                layout.setOnClickListener {
                    playPreview(layout.context, item)
                }

                // Add button
                val addBtn = TextView(layout.context).apply {
                    text = "⚡ ADD"
                    textSize = 12f
                    setTextColor(Color.parseColor("#00E5FF"))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#0F2833"))
                        cornerRadius = 10f * density
                        setStroke(dp(1f), Color.parseColor("#00E5FF"))
                    }
                    setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                    setOnClickListener {
                        val file = getOrGenerateSfxFile(context, item)
                        stopPreview()
                        dialog.dismiss()
                        onAddSfxToTimeline(item, file)
                    }
                }
                layout.addView(addBtn)
            }
        }
        root.addView(recyclerView)

        dialog.setOnDismissListener {
            stopPreview()
        }
        dialog.setContentView(root)
        dialog.show()
    }
}

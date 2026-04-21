package com.video.engine.pro.timeline

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.video.engine.pro.model.ClipSegment
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

object AudioWaveformCache {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val cache = object : android.util.LruCache<String, FloatArray>(128) {}
    private val inFlight = mutableSetOf<String>()
    private var appContext: Context? = null

    fun init(context: Context) { appContext = context.applicationContext }

    fun request(clip: ClipSegment, barCount: Int, callback: (String, FloatArray) -> Unit) {
        val key = "${clip.sourcePath}#${clip.sourceInMs}#${clip.sourceOutMs}#$barCount"
        synchronized(lock) { cache[key]?.let { mainHandler.post { callback(key, it) }; return } }
        synchronized(lock) { if (!inFlight.add(key)) return }
        executor.execute {
            val peaks = extractPeaks(clip, barCount)
            synchronized(lock) { cache.put(key, peaks); inFlight.remove(key) }
            mainHandler.post { callback(key, peaks) }
        }
    }

    private fun extractPeaks(clip: ClipSegment, barCount: Int): FloatArray {
        val result = FloatArray(barCount)
        val path = clip.sourcePath.takeIf { it.isNotBlank() } ?: return result
        val ctx = appContext ?: return result
        val extractor = MediaExtractor()
        try {
            if (path.startsWith("content://")) extractor.setDataSource(ctx, Uri.parse(path), null)
            else { if (!File(path).exists()) return result; extractor.setDataSource(path) }

            // Find audio track
            var audioTrackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIdx = i; format = fmt; break
                }
            }
            if (audioTrackIdx < 0 || format == null) return result
            extractor.selectTrack(audioTrackIdx)

            val durationUs = (clip.sourceOutMs - clip.sourceInMs) * 1000L
            val startUs = clip.sourceInMs * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val totalSamples = (sampleRate * (durationUs / 1_000_000.0)).toLong().coerceAtLeast(1)
            val samplesPerBar = (totalSamples / barCount).coerceAtLeast(1)
            val buckets = FloatArray(barCount)
            val counts = IntArray(barCount)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleIndex = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0 || extractor.sampleTime - startUs > durationUs) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 5000)
                if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    val shorts = buf.asShortBuffer()
                    while (shorts.hasRemaining()) {
                        val s = shorts.get()
                        val barIdx = ((sampleIndex / samplesPerBar).toInt()).coerceIn(0, barCount - 1)
                        buckets[barIdx] = max(buckets[barIdx], abs(s.toFloat()) / 32768f)
                        counts[barIdx]++
                        sampleIndex++
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // ignore
                }
            }
            codec.stop(); codec.release()
            buckets.copyInto(result)
        } catch (_: Throwable) {
        } finally {
            extractor.release()
        }
        return result
    }
}

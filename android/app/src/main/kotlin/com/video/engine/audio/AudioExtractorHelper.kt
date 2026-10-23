package com.video.engine.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * High-performance audio extractor for Instagram Reels, TikTok, YouTube Shorts, and gallery videos.
 * Extracts AAC/M4A audio stream using MediaExtractor and MediaMuxer without re-encoding (~150ms).
 */
object AudioExtractorHelper {

    private const val TAG = "[AudioExtractor]"
    private const val BUFFER_SIZE_BYTES = 256 * 1024

    data class ExtractionResult(
        val file: File,
        val durationMs: Long,
        val mimeType: String,
        val title: String,
    )

    /**
     * Extracts the audio track from a video [Uri] (handles content://, file://) and writes it as an .m4a file.
     * Returns null if no audio track exists or extraction fails.
     */
    fun extractAudioFromVideoUri(
        context: Context,
        videoUri: Uri,
        destinationDir: File = File(context.filesDir, "extracted_audio").apply { mkdirs() },
        baseFileName: String? = null,
    ): ExtractionResult? {
        val fileName = baseFileName?.ifBlank { null }
            ?: "reel_audio_${System.currentTimeMillis()}"
        val outputFile = File(destinationDir, "$fileName.m4a")
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        return try {
            extractor.setDataSource(context, videoUri, null)
            val audioTrackIndex = findAudioTrack(extractor)
            if (audioTrackIndex < 0) {
                Log.w(TAG, "No audio track found in video: $videoUri")
                return null
            }

            val format = extractor.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            val durationMs = (durationUs / 1000L).coerceAtLeast(0L)

            extractor.selectTrack(audioTrackIndex)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(format)
            muxer.start()
            muxerStarted = true

            val bufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
            } else {
                BUFFER_SIZE_BYTES
            }

            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    break
                }
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
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

            if (outputFile.exists() && outputFile.length() > 0L) {
                Log.i(TAG, "Audio successfully extracted: ${outputFile.absolutePath} size=${outputFile.length()} duration=${durationMs}ms")
                ExtractionResult(
                    file = outputFile,
                    durationMs = durationMs,
                    mimeType = mime,
                    title = "Reel Audio"
                )
            } else {
                outputFile.delete()
                Log.w(TAG, "Extracted audio file is empty")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract audio from video: $videoUri", e)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            null
        } finally {
            try {
                if (muxerStarted) {
                    muxer?.stop()
                }
                muxer?.release()
            } catch (ignored: Exception) {}
            try {
                extractor.release()
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Extracts audio from a local file path.
     */
    fun extractAudioFromVideoPath(
        context: Context,
        videoPath: String,
        destinationDir: File = File(context.filesDir, "extracted_audio").apply { mkdirs() },
    ): ExtractionResult? {
        val uri = if (videoPath.startsWith("content://")) {
            Uri.parse(videoPath)
        } else {
            Uri.fromFile(File(videoPath))
        }
        return extractAudioFromVideoUri(context, uri, destinationDir)
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }
}

package com.video.engine.export

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin Hardware-Independent Animated GIF89a Encoder.
 * Produces smooth, high-fidelity looping animated GIFs from video files or bitmap frame sequences.
 */
class AnimatedGifEncoder {

    companion object {
        private const val TAG = "AnimatedGifEncoder"

        /**
         * Transcode a source video file into a crisp, looping animated GIF.
         * Runs in background thread and reports progress from 0 to 100%.
         */
        fun encodeVideoToGif(
            videoPath: String,
            outputFile: File,
            maxDurationMs: Long = 8000L,
            targetFps: Int = 12,
            targetWidth: Int = 480,
            onProgress: (Int) -> Unit = {}
        ): Boolean {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(videoPath)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val totalDurationMs = durationStr?.toLongOrNull() ?: maxDurationMs
                val effectiveDurationMs = min(totalDurationMs, maxDurationMs).coerceAtLeast(500L)

                val frameIntervalMs = (1000L / targetFps.coerceIn(5, 30)).coerceAtLeast(33L)
                val totalFrames = ((effectiveDurationMs / frameIntervalMs)).toInt().coerceIn(1, 120)

                val encoder = AnimatedGifEncoder()
                encoder.setDelay(frameIntervalMs.toInt())
                encoder.setRepeat(0) // Infinite loop

                val fos = FileOutputStream(outputFile)
                val bos = BufferedOutputStream(fos, 64 * 1024)
                if (!encoder.start(bos)) {
                    bos.close()
                    return false
                }

                for (i in 0 until totalFrames) {
                    val timeUs = (i * frameIntervalMs * 1000L)
                    val frameBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frameBitmap != null) {
                        val scaledBitmap = scalePreservingAspect(frameBitmap, targetWidth)
                        encoder.addFrame(scaledBitmap)
                        if (scaledBitmap != frameBitmap) {
                            scaledBitmap.recycle()
                        }
                        frameBitmap.recycle()
                    }
                    val progress = ((i + 1) * 100 / totalFrames)
                    onProgress(progress)
                }

                encoder.finish()
                bos.flush()
                bos.close()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to encode video to GIF: ${e.message}", e)
                false
            } finally {
                runCatching { retriever.release() }
            }
        }

        private fun scalePreservingAspect(source: Bitmap, targetWidth: Int): Bitmap {
            val srcW = source.width
            val srcH = source.height
            if (srcW <= targetWidth) return source
            val targetHeight = max(1, (srcH.toFloat() * targetWidth / srcW).roundToInt())
            return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        }
    }

    private var width = 0
    private var height = 0
    private var delay = 100 // ms
    private var repeat = 0  // 0 = repeat indefinitely
    private var out: OutputStream? = null

    private var indexedPixels: ByteArray = ByteArray(0)
    private var colorTab: ByteArray = ByteArray(0)
    private var started = false

    fun setDelay(ms: Int) {
        delay = ms.coerceAtLeast(10)
    }

    fun setRepeat(iter: Int) {
        repeat = iter.coerceAtLeast(0)
    }

    fun start(os: OutputStream): Boolean {
        out = os
        var ok = true
        try {
            writeString("GIF89a") // Header
            started = true
        } catch (e: Exception) {
            ok = false
        }
        return ok
    }

    fun addFrame(bitmap: Bitmap): Boolean {
        if (!started || out == null) return false
        try {
            val isFirst = width == 0
            width = bitmap.width
            height = bitmap.height

            // Build palette and quantize
            quantizeImage(bitmap)

            if (isFirst) {
                writeLSD() // Logical Screen Descriptor
                writePalette() // Global palette
                if (repeat >= 0) {
                    writeNetscapeExt() // Looping extension
                }
            }

            writeGraphicCtrlExt()
            writeImageDesc()
            writePixels()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding GIF frame", e)
            return false
        }
    }

    fun finish(): Boolean {
        if (!started) return false
        var ok = true
        started = false
        try {
            out?.write(0x3B) // GIF Trailer
            out?.flush()
        } catch (e: Exception) {
            ok = false
        }
        width = 0
        height = 0
        out = null
        return ok
    }

    /**
     * Fast and crisp median-cut-like quantization producing 256 colors.
     */
    private fun quantizeImage(bitmap: Bitmap) {
        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        indexedPixels = ByteArray(totalPixels)
        val paletteList = ArrayList<Int>(256)

        // Generate 6x6x6 color cube (216 colors) + 40 grayscale ramp = 256 total colors
        val paletteBytes = ByteArray(256 * 3)
        var pIdx = 0

        // 6x6x6 color cube
        for (r in 0 until 6) {
            for (g in 0 until 6) {
                for (b in 0 until 6) {
                    val cr = (r * 51).toByte()
                    val cg = (g * 51).toByte()
                    val cb = (b * 51).toByte()
                    paletteBytes[pIdx++] = cr
                    paletteBytes[pIdx++] = cg
                    paletteBytes[pIdx++] = cb
                }
            }
        }

        // 40 grayscale levels from 0 to 255
        for (i in 0 until 40) {
            val v = ((i * 255) / 39).toByte()
            paletteBytes[pIdx++] = v
            paletteBytes[pIdx++] = v
            paletteBytes[pIdx++] = v
        }

        colorTab = paletteBytes

        // Map pixels to palette indices
        for (i in 0 until totalPixels) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF

            // Fast index formula for 6x6x6 cube
            val rIdx = (r + 25) / 51
            val gIdx = (g + 25) / 51
            val bIdx = (b + 25) / 51

            val cubeIdx = (rIdx.coerceIn(0, 5) * 36) + (gIdx.coerceIn(0, 5) * 6) + bIdx.coerceIn(0, 5)
            indexedPixels[i] = cubeIdx.toByte()
        }
    }

    private fun writeGraphicCtrlExt() {
        val os = out ?: return
        os.write(0x21) // Extension Introducer
        os.write(0xF9) // Graphic Control Label
        os.write(4)    // Block Size
        os.write(0)    // Packed fields: disposal method 0, no user input, no transparency
        writeShort(delay / 10) // Delay in hundredths of a second
        os.write(0)    // Transparent color index
        os.write(0)    // Block Terminator
    }

    private fun writeImageDesc() {
        val os = out ?: return
        os.write(0x2C) // Image Separator
        writeShort(0)  // Left
        writeShort(0)  // Top
        writeShort(width)
        writeShort(height)
        os.write(0)    // Packed fields (no local color table, not interlaced)
    }

    private fun writeLSD() {
        val os = out ?: return
        writeShort(width)
        writeShort(height)
        // Global color table: flag=1, color res=7, sort=0, size=7 (256 colors)
        os.write(0x80 or 0x70 or 0x07)
        os.write(0) // Background color index
        os.write(0) // Pixel aspect ratio
    }

    private fun writeNetscapeExt() {
        val os = out ?: return
        os.write(0x21) // Extension Introducer
        os.write(0xFF) // Application Extension Label
        os.write(11)   // Block Size
        writeString("NETSCAPE2.0")
        os.write(3)    // Sub-block Size
        os.write(1)    // Loop sub-block ID
        writeShort(repeat) // Loop count (0 = infinite)
        os.write(0)    // Block Terminator
    }

    private fun writePalette() {
        out?.write(colorTab, 0, colorTab.size)
    }

    private fun writePixels() {
        val encoder = LzwEncoder(width, height, indexedPixels, 8)
        encoder.encode(out ?: return)
    }

    private fun writeShort(value: Int) {
        val os = out ?: return
        os.write(value and 0xFF)
        os.write((value shr 8) and 0xFF)
    }

    private fun writeString(s: String) {
        val os = out ?: return
        for (ch in s) {
            os.write(ch.code)
        }
    }

    /**
     * Standard LZW compressor for GIF image data blocks.
     */
    private class LzwEncoder(
        private val imgW: Int,
        private val imgH: Int,
        private val pixAry: ByteArray,
        private val initCodeSize: Int
    ) {
        companion object {
            private const val BITS = 12
            private const val HSIZE = 5003
        }

        private var nBits = 0
        private var maxCode = 0
        private val htab = IntArray(HSIZE)
        private val codetab = IntArray(HSIZE)
        private val hsize = HSIZE
        private var freeEnt = 0
        private var clearFlg = false

        private var gInitBits = 0
        private var clearCode = 0
        private var eofCode = 0

        private var curAccum = 0
        private var curBits = 0
        private val accum = ByteArray(256)
        private var aCount = 0

        fun encode(os: OutputStream) {
            os.write(initCodeSize)
            compress(initCodeSize + 1, os)
            os.write(0) // Zero length block terminator
        }

        private fun compress(initBits: Int, os: OutputStream) {
            gInitBits = initBits
            clearFlg = false
            nBits = gInitBits
            maxCode = (1 shl nBits) - 1

            clearCode = 1 shl (initBits - 1)
            eofCode = clearCode + 1
            freeEnt = clearCode + 2

            aCount = 0
            var ent = nextPixel()

            var hshift = 0
            var fcode = hsize
            while (fcode < 65536) {
                hshift++
                fcode *= 2
            }
            hshift = 8 - hshift

            val clHash = hsize
            for (i in 0 until clHash) {
                htab[i] = -1
            }

            output(clearCode, os)

            var c = nextPixel()
            while (c != -1) {
                fcode = (c shl BITS) + ent
                var i = (c shl hshift) xor ent
                if (htab[i] == fcode) {
                    ent = codetab[i]
                    c = nextPixel()
                    continue
                } else if (htab[i] >= 0) {
                    var disp = clHash - i
                    if (i == 0) disp = 1
                    var found = false
                    while (true) {
                        i -= disp
                        if (i < 0) i += clHash
                        if (htab[i] == fcode) {
                            ent = codetab[i]
                            found = true
                            break
                        }
                        if (htab[i] < 0) break
                    }
                    if (found) {
                        c = nextPixel()
                        continue
                    }
                }
                output(ent, os)
                ent = c
                if (freeEnt < (1 shl BITS)) {
                    codetab[i] = freeEnt++
                    htab[i] = fcode
                } else {
                    clBlock(os)
                }
                c = nextPixel()
            }
            output(ent, os)
            output(eofCode, os)
        }

        private var curPixel = 0
        private fun nextPixel(): Int {
            if (curPixel >= pixAry.size) return -1
            return pixAry[curPixel++].toInt() and 0xFF
        }

        private fun clBlock(os: OutputStream) {
            for (i in 0 until hsize) {
                htab[i] = -1
            }
            freeEnt = clearCode + 2
            clearFlg = true
            output(clearCode, os)
        }

        private fun output(code: Int, os: OutputStream) {
            curAccum = curAccum or (code shl curBits)
            curBits += nBits
            while (curBits >= 8) {
                charOut((curAccum and 0xFF).toByte(), os)
                curAccum = curAccum shr 8
                curBits -= 8
            }
            if (freeEnt > maxCode || clearFlg) {
                if (clearFlg) {
                    nBits = gInitBits
                    maxCode = (1 shl nBits) - 1
                    clearFlg = false
                } else {
                    nBits++
                    maxCode = if (nBits == BITS) (1 shl BITS) else (1 shl nBits) - 1
                }
            }
            if (code == eofCode) {
                while (curBits > 0) {
                    charOut((curAccum and 0xFF).toByte(), os)
                    curAccum = curAccum shr 8
                    curBits -= 8
                }
                flushChar(os)
            }
        }

        private fun charOut(c: Byte, os: OutputStream) {
            accum[aCount++] = c
            if (aCount >= 254) {
                flushChar(os)
            }
        }

        private fun flushChar(os: OutputStream) {
            if (aCount > 0) {
                os.write(aCount)
                os.write(accum, 0, aCount)
                aCount = 0
            }
        }
    }
}

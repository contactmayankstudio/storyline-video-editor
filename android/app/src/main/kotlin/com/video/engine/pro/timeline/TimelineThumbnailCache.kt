package com.video.engine.pro.timeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.video.engine.DeviceDetector
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import com.video.engine.pro.model.ClipSegment
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object TimelineThumbnailCache {
    private const val MAX_CACHE_BYTES = 64 * 1024 * 1024
    private const val MAX_STRIP_FRAMES = 16
    private const val MAX_VIEWPORT_WIDTH_PX = 1920
    private const val MAX_TARGET_HEIGHT_PX = 320
    private const val MAX_TILE_WIDTH_PX = 360

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        cache.resize(maxCacheBytes())
    }
    private const val TARGET_TILE_WIDTH_PX = 120

    private fun maxCacheBytes() = DeviceDetector.getRecommendedThumbnailCacheBytes()

    private fun maxFrames() = 12

    private fun maxTargetHeightPx() = MAX_TARGET_HEIGHT_PX

    private fun callbackDelayMs() = 0L
    private val IMAGE_EXTENSIONS =
        setOf("jpg", "jpeg", "jpe", "jfif", "png", "webp", "bmp", "gif", "tif", "tiff", "heic", "heif", "avif")

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(1) { runnable ->
        Thread(runnable, "TimelineThumbnailCache").apply {
            priority = Thread.MIN_PRIORITY
        }
    }
    private val lock = Any()
    private val inFlight = mutableMapOf<String, MutableList<(String, List<Bitmap>) -> Unit>>()
    private val lastVideoStripBuildElapsedMsBySource = mutableMapOf<String, Long>()
    @Volatile private var suspendedUntilElapsedMs: Long = 0L
    private val cache = object : LruCache<String, List<Bitmap>>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: List<Bitmap>): Int {
            return value.sumOf { bitmap -> bitmap.allocationByteCount }
        }
    }

    fun evictAll() {
        synchronized(lock) {
            cache.evictAll()
        }
    }

    fun suspendRequests(windowMs: Long) {
        val until = SystemClock.elapsedRealtime() + windowMs.coerceAtLeast(250L)
        suspendedUntilElapsedMs = max(suspendedUntilElapsedMs, until)
    }

    fun buildRequestKey(
        clip: ClipSegment,
        viewportWidthPx: Int,
        targetHeightPx: Int,
    ): String {
        val safeViewportWidthPx = viewportWidthPx.coerceIn(1, MAX_VIEWPORT_WIDTH_PX)
        val safeTargetHeightPx = targetHeightPx.coerceIn(24, maxTargetHeightPx())
        val widthBucket = ceil(safeViewportWidthPx / 64.0).toInt() * 64
        val heightBucket = ceil(safeTargetHeightPx / 16.0).toInt() * 16
        val durationBucket = ((clip.sourceOutMs - clip.sourceInMs).coerceAtLeast(1L) / 250L) * 250L
        return buildString {
            append(clip.sourcePath)
            append('#')
            append(clip.sourceInMs)
            append('#')
            append(durationBucket)
            append('#')
            append(widthBucket)
            append('x')
            append(heightBucket)
        }
    }

    fun requestStrip(
        clip: ClipSegment,
        viewportWidthPx: Int,
        targetHeightPx: Int,
        callback: (String, List<Bitmap>) -> Unit,
    ) {
        val requestKey = buildRequestKey(clip, viewportWidthPx, targetHeightPx)
        synchronized(lock) {
            cache.get(requestKey)?.let { cached ->
                postCallback { callback(requestKey, cached) }
                return
            }
            val pendingCallbacks = inFlight[requestKey]
            if (pendingCallbacks != null) {
                pendingCallbacks += callback
                return
            }
            inFlight[requestKey] = mutableListOf(callback)
        }

        if (SystemClock.elapsedRealtime() < suspendedUntilElapsedMs) {
            synchronized(lock) {
                inFlight.remove(requestKey)
            }
            return
        }

        executor.execute {
            val sourcePath = clip.sourcePath.takeIf { it.isNotBlank() }
            if (SystemClock.elapsedRealtime() < suspendedUntilElapsedMs ||
                (!sourcePath.isNullOrBlank() && !isStillImagePath(sourcePath) && shouldThrottleVideoStripBuild(sourcePath))
            ) {
                synchronized(lock) {
                    inFlight.remove(requestKey)
                }
                return@execute
            }
            val strip = buildStripBitmaps(clip, viewportWidthPx, targetHeightPx)
            val callbacks = synchronized(lock) {
                cache.put(requestKey, strip)
                inFlight.remove(requestKey).orEmpty()
            }
            if (callbacks.isEmpty()) {
                return@execute
            }
            postCallback {
                callbacks.forEach { it(requestKey, strip) }
            }
        }
    }

    private fun shouldThrottleVideoStripBuild(sourcePath: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val minIntervalMs = when (DeviceDetector.getDeviceTier()) {
            DeviceDetector.DeviceTier.LOW -> 2_400L
            DeviceDetector.DeviceTier.MID -> 1_500L
            DeviceDetector.DeviceTier.HIGH -> 700L
        }
        return synchronized(lock) {
            val previous = lastVideoStripBuildElapsedMsBySource[sourcePath] ?: 0L
            if (previous > 0L && now - previous < minIntervalMs) {
                true
            } else {
                lastVideoStripBuildElapsedMsBySource[sourcePath] = now
                false
            }
        }
    }

    private fun postCallback(action: () -> Unit) {
        val delayMs = callbackDelayMs()
        if (delayMs <= 0L) {
            mainHandler.post(action)
        } else {
            mainHandler.postDelayed(action, delayMs)
        }
    }

    private fun buildStripBitmaps(
        clip: ClipSegment,
        viewportWidthPx: Int,
        targetHeightPx: Int,
    ): List<Bitmap> {
        val sourcePath = clip.sourcePath.takeIf { it.isNotBlank() } ?: return emptyList()
        val isContentUri = sourcePath.startsWith("content://")
        if (!isContentUri && !File(sourcePath).exists()) {
            return emptyList()
        }

        if (isStillImagePath(sourcePath)) {
            return loadStillImageStrip(sourcePath, viewportWidthPx, targetHeightPx)
        }

        val retriever = MediaMetadataRetriever()
        return try {
            if (isContentUri) {
                val ctx = appContext ?: return emptyList()
                retriever.setDataSource(ctx, Uri.parse(sourcePath))
            } else {
                retriever.setDataSource(sourcePath)
            }
            val clipSpanMs = (clip.sourceOutMs - clip.sourceInMs).coerceAtLeast(1L)
            val safeViewportWidthPx = viewportWidthPx.coerceIn(1, MAX_VIEWPORT_WIDTH_PX)
            val safeTargetHeightPx = targetHeightPx.coerceIn(24, maxTargetHeightPx())
            val requestedFrames = max(
                1,
                min(
                    maxFrames(),
                    safeViewportWidthPx / TARGET_TILE_WIDTH_PX,
                ),
            )
            val durationDrivenFrames = max(1, min(maxFrames(), ceil(clipSpanMs / 3000.0).toInt()))
            val frameCount = max(requestedFrames, durationDrivenFrames).coerceIn(1, maxFrames())
            val frameTimes = buildFrameTimes(
                startMs = clip.sourceInMs.coerceAtLeast(0L),
                endMs = clip.sourceOutMs.coerceAtLeast(clip.sourceInMs + 1L),
                frameCount = frameCount,
            )
            val tileWidthPx = (safeViewportWidthPx.coerceAtLeast(TARGET_TILE_WIDTH_PX) / frameCount)
                .coerceIn(TARGET_TILE_WIDTH_PX / 2, MAX_TILE_WIDTH_PX)
            val tileHeightPx = safeTargetHeightPx

            frameTimes.mapNotNull { timeMs ->
                val rawBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        tileWidthPx,
                        tileHeightPx,
                    )
                } else {
                    retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                    ?: return@mapNotNull null
                val sized = if (rawBitmap.width == tileWidthPx && rawBitmap.height == tileHeightPx) {
                    rawBitmap
                } else {
                    Bitmap.createScaledBitmap(rawBitmap, tileWidthPx, tileHeightPx, true).also { scaled ->
                        if (scaled !== rawBitmap) {
                            rawBitmap.recycle()
                        }
                    }
                }
                // Use RGB_565 on low-end devices to halve thumbnail memory
                if (DeviceDetector.shouldUseRgb565Thumbnails() && sized.config != Bitmap.Config.RGB_565) {
                    sized.copy(Bitmap.Config.RGB_565, false)?.also { compact ->
                        sized.recycle()
                    } ?: sized
                } else {
                    sized
                }
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun isStillImagePath(sourcePath: String): Boolean {
        val extension = sourcePath.substringAfterLast('.', "").lowercase()
        return extension in IMAGE_EXTENSIONS
    }

    private fun loadStillImageStrip(
        sourcePath: String,
        viewportWidthPx: Int,
        targetHeightPx: Int,
    ): List<Bitmap> {
        val safeViewportWidthPx = viewportWidthPx.coerceIn(1, MAX_VIEWPORT_WIDTH_PX)
        val safeTargetHeightPx = targetHeightPx.coerceIn(24, maxTargetHeightPx())
        val targetWidthPx = safeViewportWidthPx
            .coerceAtLeast(TARGET_TILE_WIDTH_PX)
            .coerceAtMost(MAX_TILE_WIDTH_PX)
        val rawBitmap = runCatching {
            if (sourcePath.startsWith("content://")) {
                val ctx = appContext ?: return emptyList()
                ctx.contentResolver.openInputStream(Uri.parse(sourcePath))?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            } else {
                BitmapFactory.decodeFile(sourcePath)
            }
        }.getOrNull() ?: return emptyList()

        val scaledBitmap = try {
            if (rawBitmap.width <= 0 || rawBitmap.height <= 0) {
                rawBitmap
            } else {
                val scale = minOf(
                    targetWidthPx.toFloat() / rawBitmap.width.toFloat(),
                    safeTargetHeightPx.toFloat() / rawBitmap.height.toFloat(),
                )
                val scaledWidth = max(1, (rawBitmap.width * scale).toInt())
                val scaledHeight = max(1, (rawBitmap.height * scale).toInt())
                if (scaledWidth == rawBitmap.width && scaledHeight == rawBitmap.height) {
                    rawBitmap
                } else {
                    Bitmap.createScaledBitmap(rawBitmap, scaledWidth, scaledHeight, true).also { scaled ->
                        if (scaled !== rawBitmap) {
                            rawBitmap.recycle()
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            rawBitmap
        }
        return listOf(scaledBitmap)
    }

    private fun buildFrameTimes(startMs: Long, endMs: Long, frameCount: Int): List<Long> {
        if (frameCount <= 1) {
            return listOf(startMs + ((endMs - startMs) / 2L))
        }
        val spanMs = (endMs - startMs).coerceAtLeast(1L)
        return List(frameCount) { index ->
            val progress = index.toDouble() / (frameCount - 1).toDouble()
            startMs + (spanMs * progress).toLong()
        }
    }
}

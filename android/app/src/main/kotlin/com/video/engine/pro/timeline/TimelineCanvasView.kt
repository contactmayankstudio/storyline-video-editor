package com.video.engine.pro.timeline

import android.content.Context
import android.graphics.*
import android.os.VibrationEffect
import com.video.engine.DeviceDetector
import android.os.Vibrator
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import com.video.engine.pro.model.ClipSegment
import com.video.engine.pro.model.TrackState
import com.video.engine.pro.model.TrackType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Single-canvas timeline view.
 * Handles scroll, pinch-zoom, trim, move, magnetic snap — no nested RecyclerView conflicts.
 */
class TimelineCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Public API ────────────────────────────────────────────────────────────
    interface Listener {
        fun onClipUpdatePreview(update: ClipUpdate)
        fun onClipUpdateCommitted(update: ClipUpdate)
        fun onZoomChanged(pxPerSecond: Float)
        fun onClipSelected(clipId: String?)
        fun onPlayheadScrub(timeMs: Long)
        fun onTrackVisibilityChanged(trackType: TrackType, isVisible: Boolean) {}
        fun onTrackImportRequested(trackType: TrackType) {}
        fun onTrackLockedChanged(trackType: TrackType, isLocked: Boolean) {}
    }
    var listener: Listener? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private var tracks: List<TrackState> = emptyList()
    private var playheadMs: Long = 0L
    private var selectedClipId: String? = null
    private var pxPerSecond: Float = 120f   // zoom level
    private val pxPerMs get() = pxPerSecond / 1000f

    // scroll offset in px (how far left the content is scrolled)
    private var scrollX: Float = 0f
    private var totalContentWidthPx: Float = 0f

    // ── Layout constants ──────────────────────────────────────────────────────
    private val rulerHeightPx = dp(20)
    private val minTrackHeightBasePx = dp(31)
    private val maxTrackHeightBasePx = dp(42)
    private val trackGapPx = dp(2)
    private val headerWidthPx = dp(46)
    private val timelineBottomInsetPx = dp(6)
    private val handleWidthPx = dp(10)
    private val snapThresholdPx = dp(12).toFloat()
    private val minClipWidthPx = dp(4).toFloat()

    // ── Paints ────────────────────────────────────────────────────────────────
    private val bgPaint = Paint().apply { color = Color.parseColor("#06080B") }
    private val rulerPaint = Paint().apply { color = Color.parseColor("#090D12") }
    private val rulerTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#28303A"); strokeWidth = 1f
    }
    private val rulerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#74808D"); textSize = dp(8.5f); textAlign = Paint.Align.CENTER
    }
    private val headerPaint = Paint().apply { color = Color.parseColor("#070A0E") }
    private val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E7EDF3"); textSize = dp(8f); textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); color = Color.parseColor("#87D9FF")
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB56B"); strokeWidth = dp(2).toFloat()
    }
    private val snapLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6FDBFF"); strokeWidth = dp(1).toFloat()
    }
    private val clipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(9).toFloat()
        setShadowLayer(2f, 0f, 1f, Color.BLACK)
    }

    private val clipRect = RectF()
    private val handleRect = RectF()
    private val laneRect = RectF()

    // ── Extra paints ──────────────────────────────────────────────────────────
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC000000") }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(11).toFloat(); textAlign = Paint.Align.CENTER
    }
    private val durationTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D6E0EA"); textSize = dp(8).toFloat(); textAlign = Paint.Align.CENTER
    }
    private val transitionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB56B") }
    private val plusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#171C22"); style = Paint.Style.FILL
    }
    private val plusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9EABB8"); textSize = dp(15).toFloat(); textAlign = Paint.Align.CENTER
    }
    private val trackVisibilityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7E8A97"); textSize = dp(7.5f); textAlign = Paint.Align.CENTER
    }
    private val trackLockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B94A5"); strokeWidth = dp(1.5f)
        style = Paint.Style.STROKE
    }
    private val trackLockedOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#32000000")
    }
    private val trackLanePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackLaneStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
    }
    private val headerCellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headerDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#171D25")
        strokeWidth = dp(1).toFloat()
    }

    // ── Long press state ──────────────────────────────────────────────────────
    private var longPressClipId: String? = null
    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        longPressClipId?.let { showClipContextMenu(it) }
    }
    private var showPlayheadTooltip = false

    // ── Track visibility ──────────────────────────────────────────────────────
    private val trackVisible = mutableMapOf<TrackType, Boolean>()
    private val trackLocked = mutableMapOf<TrackType, Boolean>()

    // ── Double tap zoom reset ─────────────────────────────────────────────────
    private var lastTapTimeMs = 0L
    private var lastTapX = 0f

    // ── Touch state ───────────────────────────────────────────────────────────
    private enum class GestureKind { NONE, SCROLL, TRIM_START, TRIM_END, MOVE, PLAYHEAD_SCRUB }
    private var gesture = GestureKind.NONE
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastTouchX = 0f
    private var gestureClipId: String? = null
    private var gestureClipSnapshot: ClipSegment? = null
    private var snapTimeMs: Long? = null
    private var gestureStarted = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // ── Pinch zoom ────────────────────────────────────────────────────────────
    private var zoomFocusX = 0f
    private var zoomFocusScrollX = 0f
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                zoomFocusX = d.focusX
                zoomFocusScrollX = scrollX
                return true
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val newPps = (pxPerSecond * d.scaleFactor).coerceIn(24f, 6000f)
                // Keep focus point stable
                val focusMs = (zoomFocusScrollX + zoomFocusX - headerWidthPx) / (pxPerSecond / 1000f)
                pxPerSecond = newPps
                scrollX = ((focusMs * pxPerMs) - (zoomFocusX - headerWidthPx))
                    .coerceAtLeast(0f)
                listener?.onZoomChanged(pxPerSecond)
                invalidate()
                return true
            }
        })

    // ── Vibrator ──────────────────────────────────────────────────────────────
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private var lastHapticSnapMs: Long? = null

    // ── Fling ─────────────────────────────────────────────────────────────────
    private val velocityTracker = android.view.VelocityTracker.obtain()
    private val scroller = android.widget.OverScroller(context)

    // ── Public setters ────────────────────────────────────────────────────────
    fun setTracks(tracks: List<TrackState>) {
        this.tracks = tracks
        tracks.forEach { track ->
            trackVisible[track.type] = track.isVisible
            trackLocked[track.type] = track.isLocked
        }
        recalcContentWidth()
        scheduleAssetRequests()
        invalidate()
    }

    fun getTracks(): List<TrackState> = tracks

    fun getSelectedClipId(): String? = selectedClipId

    fun getPlayheadMs(): Long = playheadMs

    // Center-fixed playhead: playhead always at screen center, clips scroll
    private fun centerX() = width / 2f

    private fun scrollForPlayhead(ms: Long) =
        (ms * pxPerMs - (centerX() - headerWidthPx)).coerceAtLeast(0f)

    fun setPlayheadMs(ms: Long) {
        playheadMs = ms
        scrollX = scrollForPlayhead(ms)
        invalidate()
    }

    fun setSelectedClipId(id: String?) {
        selectedClipId = id
        invalidate()
    }

    fun setZoomPxPerSecond(pps: Float) {
        pxPerSecond = pps.coerceIn(24f, 6000f)
        recalcContentWidth()
        scheduleAssetRequests()
        invalidate()
    }

    private fun scheduleAssetRequests() {
        if (width == 0) return
        tracks.forEach { track ->
            track.clips.forEach { clip ->
                requestThumbnails(clip)
                requestWaveform(clip)
            }
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────
    private fun maxScrollX() = (totalContentWidthPx - width).coerceAtLeast(0f)

    private fun clampScroll() {
        scrollX = scrollX.coerceIn(0f, maxScrollX())
    }

    private fun recalcContentWidth() {
        var maxEndMs = 0L
        tracks.forEach { t -> t.clips.forEach { c -> maxEndMs = max(maxEndMs, c.startTimeMs + c.durationMs) } }
        totalContentWidthPx = headerWidthPx + maxEndMs * pxPerMs + width * 0.5f
    }

    private val layoutTrackCount: Int
        get() = tracks.size.coerceAtLeast(5)

    private val trackHeightPx: Int
        get() {
            val count = layoutTrackCount
            if (height <= 0 || count <= 0) return minTrackHeightBasePx
            val availableHeight =
                (height - rulerHeightPx - timelineBottomInsetPx - (count - 1) * trackGapPx)
                    .coerceAtLeast(count * minTrackHeightBasePx)
            return (availableHeight / count).coerceIn(minTrackHeightBasePx, maxTrackHeightBasePx)
        }

    private fun trackTop(trackIndex: Int) =
        rulerHeightPx + trackIndex * (trackHeightPx + trackGapPx)

    private fun headerVisibilityX(): Float = headerWidthPx * 0.34f

    private fun headerLockX(): Float = headerWidthPx * 0.72f

    private fun headerToggleY(trackIndex: Int): Float = trackTop(trackIndex) + trackHeightPx * 0.72f

    private fun clipTop(trackIndex: Int, clip: ClipSegment): Float {
        val base = trackTop(trackIndex).toFloat()
        val subTrack = clip.metadata["subTrack"]?.toIntOrNull()?.minus(1) ?: 0
        return if (subTrack > 0) base + subTrack * (trackHeightPx / 2f) else base
    }

    private fun clipBottom(trackIndex: Int, clip: ClipSegment): Float {
        val subTrack = clip.metadata["subTrack"]?.toIntOrNull()?.minus(1) ?: 0
        val h = if (subTrack > 0) trackHeightPx / 2f else trackHeightPx.toFloat()
        return clipTop(trackIndex, clip) + h
    }

    private fun msToX(ms: Long) = headerWidthPx + ms * pxPerMs - scrollX

    private fun xToMs(x: Float) = ((x - headerWidthPx + scrollX) / pxPerMs).roundToLong().coerceAtLeast(0L)

    // ── Draw ──────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawRuler(canvas)
        drawHeaders(canvas)
        drawTracks(canvas)
        drawEmptyTrackButtons(canvas)
        drawTransitionMarkers(canvas)
        drawPlayhead(canvas)
        snapTimeMs?.let { drawSnapLine(canvas, it) }
        if (showPlayheadTooltip) drawPlayheadTooltip(canvas)
    }

    private fun drawRuler(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), rulerHeightPx.toFloat(), rulerPaint)
        canvas.drawLine(headerWidthPx.toFloat(), 0f, headerWidthPx.toFloat(), height.toFloat(), headerDividerPaint)
        val stepMs = rulerStepMs()
        val startMs = xToMs(headerWidthPx.toFloat()).let { it - it % stepMs }
        var ms = startMs
        while (msToX(ms) < width) {
            val x = msToX(ms)
            if (x >= headerWidthPx) {
                canvas.drawLine(x, rulerHeightPx * 0.5f, x, rulerHeightPx.toFloat(), rulerTickPaint)
                canvas.drawText(formatMs(ms), x, rulerHeightPx * 0.45f, rulerTextPaint)
            }
            ms += stepMs
        }
    }

    private fun rulerStepMs(): Long {
        val targetTickPx = dp(60).toFloat()
        val rawMs = targetTickPx / pxPerMs
        val steps = longArrayOf(100, 250, 500, 1000, 2000, 5000, 10000, 30000, 60000)
        return steps.firstOrNull { it >= rawMs } ?: 60000L
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        val m = s / 60
        return if (m > 0) "%d:%02d".format(m, s % 60) else "0:%02d".format(s % 60)
    }

    private fun drawHeaders(canvas: Canvas) {
        canvas.drawRect(0f, rulerHeightPx.toFloat(), headerWidthPx.toFloat(), height.toFloat(), headerPaint)
        tracks.forEachIndexed { i, track ->
            val top = trackTop(i).toFloat()
            val bottom = top + trackHeightPx
            val label = when (track.type) {
                TrackType.VIDEO -> "V1"
                TrackType.OVERLAY -> "O1"
                TrackType.LAYER -> "L1"
                TrackType.TEXT -> "T1"
                TrackType.AUDIO -> "A1"
            }
            val isVisible = trackVisible[track.type] != false
            val isLocked = trackLocked[track.type] == true
            laneRect.set(dp(4).toFloat(), top + dp(1), headerWidthPx.toFloat() - dp(4), bottom - dp(1))
            headerCellPaint.color = trackLaneFill(track.type, isLocked)
            canvas.drawRoundRect(laneRect, dp(8).toFloat(), dp(8).toFloat(), headerCellPaint)
            trackLaneStrokePaint.color = trackLaneStroke(track.type)
            canvas.drawRoundRect(laneRect, dp(8).toFloat(), dp(8).toFloat(), trackLaneStrokePaint)
            headerTextPaint.alpha = if (isVisible) 255 else 110
            canvas.drawText(label, headerWidthPx / 2f, top + dp(13), headerTextPaint)
            trackVisibilityPaint.color = if (isVisible) Color.parseColor("#6FDBFF") else Color.parseColor("#6C7480")
            canvas.drawText(if (isVisible) "●" else "○", headerVisibilityX(), headerToggleY(i), trackVisibilityPaint)
            drawLockIcon(
                canvas = canvas,
                centerX = headerLockX(),
                centerY = headerToggleY(i) - dp(4),
                locked = isLocked,
                tint = if (isLocked) Color.parseColor("#F5C06A") else Color.parseColor("#6F7785"),
            )
            headerTextPaint.alpha = 255
        }
    }

    private fun drawLockIcon(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        locked: Boolean,
        tint: Int,
    ) {
        trackLockPaint.color = tint
        val bodyW = dp(8).toFloat()
        val bodyH = dp(6).toFloat()
        val shackleH = dp(4).toFloat()
        val bodyRect = RectF(
            centerX - (bodyW / 2f),
            centerY,
            centerX + (bodyW / 2f),
            centerY + bodyH,
        )
        canvas.drawRoundRect(bodyRect, dp(1).toFloat(), dp(1).toFloat(), trackLockPaint)
        val shackleRect = RectF(
            centerX - (bodyW * 0.32f),
            centerY - shackleH,
            centerX + (bodyW * 0.32f),
            centerY + (bodyH * 0.2f),
        )
        val startAngle = if (locked) 180f else 210f
        val sweepAngle = if (locked) 180f else 130f
        canvas.drawArc(shackleRect, startAngle, sweepAngle, false, trackLockPaint)
    }

    // ── Waveform cache ────────────────────────────────────────────────────────
    private val clipWaveforms = mutableMapOf<String, FloatArray>()
    private val clipWaveformKeys = mutableMapOf<String, String>()
    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#AAFFFFFF") }
    private var lastInvalidateMs = 0L
    private val invalidateThrottleMs = 100L

    private fun throttledInvalidate() {
        val now = System.currentTimeMillis()
        if (now - lastInvalidateMs >= invalidateThrottleMs) {
            lastInvalidateMs = now
            postInvalidate()
        }
    }

    private fun requestWaveform(clip: ClipSegment) {
        if (clip.trackType != TrackType.AUDIO && clip.trackType != TrackType.VIDEO) return
        if (clip.sourcePath.isBlank()) return
        val barCount = ((clip.durationMs * pxPerMs) / dp(4)).toInt().coerceIn(16, if (DeviceDetector.isLowEndDevice()) 64 else 256)
        val key = "${clip.sourcePath}#${clip.sourceInMs}#${clip.sourceOutMs}#$barCount"
        if (clipWaveformKeys[clip.id] == key && clipWaveforms[clip.id] != null) return
        clipWaveformKeys[clip.id] = key
        AudioWaveformCache.request(clip, barCount) { k, peaks ->
            if (clipWaveformKeys[clip.id] == k) {
                clipWaveforms[clip.id] = peaks
                throttledInvalidate()
            }
        }
    }

    private fun drawWaveform(canvas: Canvas, clip: ClipSegment, top: Float, bottom: Float) {
        val peaks = clipWaveforms[clip.id] ?: return
        if (peaks.isEmpty()) return
        val left = msToX(clip.startTimeMs)
        val right = msToX(clip.startTimeMs + clip.durationMs)
        val midY = (top + bottom) / 2f
        val maxAmp = (bottom - top) / 2f - dp(4)
        val barW = (right - left) / peaks.size
        canvas.save()
        canvas.clipRect(maxOf(left, headerWidthPx.toFloat()), top, right, bottom)
        peaks.forEachIndexed { i, peak ->
            val x = left + i * barW + barW / 2f
            val amp = peak * maxAmp
            canvas.drawLine(x, midY - amp, x, midY + amp, waveformPaint)
        }
        canvas.restore()
    }
    private val clipThumbnails = mutableMapOf<String, List<android.graphics.Bitmap>>()
    private val clipThumbnailKeys = mutableMapOf<String, String>()

    private fun requestThumbnails(clip: ClipSegment) {
        if (clip.trackType != TrackType.VIDEO && !clip.trackType.isOverlayLike()) return
        if (clip.sourcePath.isBlank()) return
        val left = msToX(clip.startTimeMs)
        val right = msToX(clip.startTimeMs + clip.durationMs)
        if (right < headerWidthPx || left > width) return
        val targetH = trackHeightPx - dp(4)
        val visibleWidth = (min(right, width.toFloat()) - max(left, headerWidthPx.toFloat()))
            .roundToInt()
            .coerceAtLeast(dp(72))
        val viewportW = visibleWidth.coerceAtMost((width - headerWidthPx).coerceAtLeast(dp(72)))
        val key = TimelineThumbnailCache.buildRequestKey(clip, viewportW, targetH)
        if (clipThumbnailKeys[clip.id] == key && clipThumbnails[clip.id]?.isNotEmpty() == true) return
        clipThumbnailKeys[clip.id] = key
        TimelineThumbnailCache.requestStrip(clip, viewportW, targetH) { k, bitmaps ->
            if (clipThumbnailKeys[clip.id] == k) {
                clipThumbnails[clip.id] = bitmaps
                throttledInvalidate()
            }
        }
    }

    private fun drawTracks(canvas: Canvas) {
        tracks.forEachIndexed { i, track ->
            val top = trackTop(i).toFloat()
            val bottom = top + trackHeightPx
            laneRect.set(headerWidthPx.toFloat() + dp(4), top + dp(1), width.toFloat() - dp(4), bottom - dp(1))
            trackLanePaint.color = trackLaneFill(track.type, trackLocked[track.type] == true)
            canvas.drawRoundRect(laneRect, dp(8).toFloat(), dp(8).toFloat(), trackLanePaint)
            trackLaneStrokePaint.color = trackLaneStroke(track.type)
            canvas.drawRoundRect(laneRect, dp(8).toFloat(), dp(8).toFloat(), trackLaneStrokePaint)
            track.clips.forEach { clip ->
                val ct = clipTop(i, clip)
                val cb = clipBottom(i, clip)
                drawClip(canvas, clip, ct, cb)
                drawWaveform(canvas, clip, ct, cb)
            }
        }
    }

    private val thumbSrcRect = Rect()
    private val thumbDstRect = RectF()

    private fun drawClip(canvas: Canvas, clip: ClipSegment, top: Float, bottom: Float) {
        val left = msToX(clip.startTimeMs)
        val right = msToX(clip.startTimeMs + clip.durationMs)
        if (right < headerWidthPx || left > width) return

        val clampedLeft = max(left, headerWidthPx.toFloat())
        clipRect.set(clampedLeft, top + dp(2), right, bottom - dp(2))

        val color = clipColor(clip)
        clipPaint.color = color
        canvas.drawRoundRect(clipRect, dp(6).toFloat(), dp(6).toFloat(), clipPaint)

        // Draw thumbnails
        val thumbs = clipThumbnails[clip.id]
        if (!thumbs.isNullOrEmpty() && (clip.trackType == TrackType.VIDEO || clip.trackType.isOverlayLike())) {
            val clipW = right - left
            val tileW = clipW / thumbs.size
            canvas.save()
            canvas.clipRect(clampedLeft, top + dp(2), right, bottom - dp(2))
            thumbs.forEachIndexed { i, bmp ->
                val tx = left + i * tileW
                thumbSrcRect.set(0, 0, bmp.width, bmp.height)
                thumbDstRect.set(tx, top + dp(2), tx + tileW, bottom - dp(2))
                canvas.drawBitmap(bmp, thumbSrcRect, thumbDstRect, null)
            }
            // Tint overlay so clip color shows through
            clipPaint.color = color and 0x55FFFFFF.toInt()
            canvas.drawRoundRect(clipRect, dp(6).toFloat(), dp(6).toFloat(), clipPaint)
            clipPaint.color = color
            canvas.restore()
        }

        // Label
        val labelX = max(clampedLeft + dp(4), left + dp(4))
        val labelWidth = right - labelX - dp(4)
        if (labelWidth > dp(20)) {
            val name = clip.sourcePath.substringAfterLast('/').ifBlank { clip.id }
            clipTextPaint.color = Color.WHITE
            canvas.save()
            canvas.clipRect(labelX, top, right - dp(2), bottom)
            canvas.drawText(name, labelX, top + trackHeightPx * 0.55f, clipTextPaint)
            canvas.restore()
        }

        // Muted / hidden dim
        if (clip.isMuted || clip.isHidden) {
            val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = 0x88000000.toInt() }
            canvas.drawRoundRect(clipRect, dp(6).toFloat(), dp(6).toFloat(), dimPaint)
        }
        if (trackLocked[clip.trackType] == true) {
            canvas.drawRoundRect(clipRect, dp(6).toFloat(), dp(6).toFloat(), trackLockedOverlayPaint)
        }

        // Selection border + handles
        if (clip.id == selectedClipId) {
            canvas.drawRoundRect(clipRect, dp(6).toFloat(), dp(6).toFloat(), clipStrokePaint)
            // Duration label
            val durSec = clip.durationMs / 1000f
            val durLabel = if (durSec >= 60) "%d:%02d".format((durSec/60).toInt(), (durSec%60).toInt())
                           else "%.1fs".format(durSec)
            val midX = (clipRect.left + clipRect.right) / 2f
            canvas.drawText(durLabel, midX, clipRect.bottom - dp(4), durationTextPaint)
            // Left handle
            handleRect.set(clipRect.left, top + dp(2), clipRect.left + handleWidthPx, bottom - dp(2))
            handlePaint.color = Color.WHITE
            canvas.drawRoundRect(handleRect, dp(4).toFloat(), dp(4).toFloat(), handlePaint)
            // Right handle
            handleRect.set(clipRect.right - handleWidthPx, top + dp(2), clipRect.right, bottom - dp(2))
            canvas.drawRoundRect(handleRect, dp(4).toFloat(), dp(4).toFloat(), handlePaint)
        }
    }

    private fun clipColor(clip: ClipSegment) = when (clip.trackType) {
        TrackType.VIDEO -> Color.parseColor("#23634C")
        TrackType.OVERLAY -> Color.parseColor("#335D89")
        TrackType.LAYER -> Color.parseColor("#2F6970")
        TrackType.TEXT -> Color.parseColor("#6C45A3")
        TrackType.AUDIO -> Color.parseColor("#9A5C2F")
    }

    private fun trackLaneFill(trackType: TrackType, locked: Boolean): Int {
        val base = when (trackType) {
            TrackType.VIDEO -> "#0D1713"
            TrackType.OVERLAY -> "#0D141C"
            TrackType.LAYER -> "#0C1618"
            TrackType.TEXT -> "#14111B"
            TrackType.AUDIO -> "#18120D"
        }
        return Color.parseColor(if (locked) "#0B0E12" else base)
    }

    private fun trackLaneStroke(trackType: TrackType): Int = when (trackType) {
        TrackType.VIDEO -> Color.parseColor("#1E2E28")
        TrackType.OVERLAY -> Color.parseColor("#1D2835")
        TrackType.LAYER -> Color.parseColor("#1C3135")
        TrackType.TEXT -> Color.parseColor("#2A2036")
        TrackType.AUDIO -> Color.parseColor("#32261A")
    }

    private fun drawEmptyTrackButtons(canvas: Canvas) {
        tracks.forEachIndexed { i, track ->
            if (track.clips.isEmpty()) {
                val top = trackTop(i).toFloat()
                val mid = top + trackHeightPx / 2f
                val btnX = headerWidthPx + dp(24).toFloat()
                val r = dp(14).toFloat()
                val isLocked = trackLocked[track.type] == true
                plusPaint.color = if (isLocked) Color.parseColor("#11151A") else Color.parseColor("#1A2027")
                plusTextPaint.color = if (isLocked) Color.parseColor("#626B74") else Color.parseColor("#D7E0E8")
                canvas.drawCircle(btnX, mid, r, plusPaint)
                canvas.drawText(if (isLocked) "x" else "+", btnX, mid + dp(6), plusTextPaint)
                // hint label
                val hint =
                    if (isLocked) {
                        "Unlock to add"
                    } else {
                        when (track.type) {
                            TrackType.VIDEO -> "Add video"
                            TrackType.OVERLAY -> "Add overlay"
                            TrackType.LAYER -> "Add layer"
                            TrackType.TEXT -> "Add title"
                            TrackType.AUDIO -> "Add audio"
                        }
                    }
                val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#616B76")
                    textSize = dp(9).toFloat()
                }
                canvas.drawText(hint, btnX + r + dp(8), mid + dp(4), hintPaint)
            }
        }
    }

    private fun drawTransitionMarkers(canvas: Canvas) {
        tracks.firstOrNull { it.type == TrackType.VIDEO }?.let { track ->
            val sorted = track.clips.sortedBy { it.startTimeMs }
            for (i in 0 until sorted.size - 1) {
                val x = msToX(sorted[i].startTimeMs + sorted[i].durationMs)
                if (x < headerWidthPx || x > width) continue
                val top = trackTop(tracks.indexOf(track)).toFloat()
                val mid = top + trackHeightPx / 2f
                transitionPaint.alpha = 180
                canvas.drawCircle(x, mid, dp(6).toFloat(), transitionPaint)
                transitionPaint.alpha = 255
            }
        }
    }

    private fun drawPlayheadTooltip(canvas: Canvas) {
        val x = msToX(playheadMs)
        if (x < headerWidthPx || x > width) return
        val label = formatMs(playheadMs)
        val pad = dp(6).toFloat()
        val tw = tooltipTextPaint.measureText(label)
        val bw = tw + pad * 2
        val bh = dp(20).toFloat()
        val bx = (x - bw / 2f).coerceIn(headerWidthPx.toFloat(), width - bw)
        val by = rulerHeightPx.toFloat() + dp(4)
        val r = RectF(bx, by, bx + bw, by + bh)
        canvas.drawRoundRect(r, dp(4).toFloat(), dp(4).toFloat(), tooltipBgPaint)
        canvas.drawText(label, bx + bw / 2f, by + bh - dp(5), tooltipTextPaint)
    }

    // Context menu for long press
    var onClipLongPress: ((clipId: String, x: Float, y: Float) -> Unit)? = null

    private fun showClipContextMenu(clipId: String) {
        vibrate()
        onClipLongPress?.invoke(clipId, touchDownX, touchDownY)
    }

    // Split button callback
    var onSplitAtPlayhead: ((Long) -> Unit)? = null
    private val showPlayheadSplitButton = false

    private val splitBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF4444") }
    private val splitBtnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(11).toFloat(); textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val splitBtnRect = RectF()
    private val splitBtnRadiusPx = dp(14).toFloat()

    private fun drawPlayhead(canvas: Canvas) {
        val x = centerX()  // always center
        canvas.drawLine(x, rulerHeightPx.toFloat(), x, height.toFloat(), playheadPaint)
        // Triangle head on ruler
        val path = Path()
        path.moveTo(x - dp(6), 0f)
        path.lineTo(x + dp(6), 0f)
        path.lineTo(x, dp(12).toFloat())
        path.close()
        canvas.drawPath(path, playheadPaint)
        if (showPlayheadSplitButton) {
            val btnY = rulerHeightPx + splitBtnRadiusPx + dp(4)
            splitBtnRect.set(x - splitBtnRadiusPx, btnY - splitBtnRadiusPx, x + splitBtnRadiusPx, btnY + splitBtnRadiusPx)
            canvas.drawCircle(x, btnY, splitBtnRadiusPx, splitBtnPaint)
            canvas.drawText("✂", x, btnY + dp(4), splitBtnTextPaint)
        } else {
            splitBtnRect.setEmpty()
        }
    }

    private fun drawSnapLine(canvas: Canvas, ms: Long) {
        val x = msToX(ms)
        if (x >= headerWidthPx && x <= width) {
            canvas.drawLine(x, rulerHeightPx.toFloat(), x, height.toFloat(), snapLinePaint)
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        velocityTracker.addMovement(event)
        if (scaleDetector.isInProgress) {
            gesture = GestureKind.NONE
            return true
        }
        if (event.pointerCount > 1) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { scroller.abortAnimation(); onDown(event) }
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gesture == GestureKind.SCROLL) {
                    velocityTracker.computeCurrentVelocity(1000)
                    val vx = -velocityTracker.xVelocity
                    scroller.fling(scrollX.toInt(), 0, vx.toInt(), 0, 0, maxScrollX().toInt(), 0, 0)
                    postInvalidateOnAnimation()
                }
                onUp(event)
                velocityTracker.clear()
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.currX.toFloat().coerceIn(0f, maxScrollX())
            val newMs = ((scrollX + centerX() - headerWidthPx) / pxPerMs).toLong().coerceAtLeast(0L)
            playheadMs = newMs
            listener?.onPlayheadScrub(newMs)
            postInvalidateOnAnimation()
        }
    }

    private fun onDown(event: MotionEvent) {
        touchDownX = event.x
        touchDownY = event.y
        lastTouchX = event.x
        gestureStarted = false
        snapTimeMs = null
        gesture = GestureKind.NONE
        gestureClipId = null
        gestureClipSnapshot = null

        // Double tap → reset zoom
        val now = System.currentTimeMillis()
        if (now - lastTapTimeMs < 300 && abs(event.x - lastTapX) < dp(20)) {
            pxPerSecond = 120f
            recalcContentWidth()
            listener?.onZoomChanged(pxPerSecond)
            invalidate()
            lastTapTimeMs = 0L
            return
        }
        lastTapTimeMs = now
        lastTapX = event.x

        // Track header tap → toggle visibility / lock
        if (event.x < headerWidthPx) {
            tracks.forEachIndexed { i, track ->
                val top = trackTop(i).toFloat()
                if (event.y in top..(top + trackHeightPx)) {
                    val toggleY = headerToggleY(i)
                    val hitRadius = dp(12).toFloat()
                    if (abs(event.x - headerVisibilityX()) <= hitRadius && abs(event.y - toggleY) <= hitRadius) {
                        val cur = trackVisible[track.type] != false
                        trackVisible[track.type] = !cur
                        listener?.onTrackVisibilityChanged(track.type, !cur)
                    } else if (abs(event.x - headerLockX()) <= hitRadius && abs(event.y - toggleY) <= hitRadius) {
                        val cur = trackLocked[track.type] == true
                        trackLocked[track.type] = !cur
                        listener?.onTrackLockedChanged(track.type, !cur)
                    }
                    invalidate()
                    return
                }
            }
            return
        }

        // Split button tap
        if (showPlayheadSplitButton && splitBtnRect.contains(event.x, event.y)) {
            onSplitAtPlayhead?.invoke(playheadMs)
            return
        }

        // Empty track "+" button tap
        tracks.forEachIndexed { i, track ->
            if (track.clips.isEmpty()) {
                val top = trackTop(i).toFloat()
                val mid = top + trackHeightPx / 2f
                val btnX = headerWidthPx + dp(24).toFloat()
                val r = dp(14).toFloat()
                if (abs(event.x - btnX) < r && abs(event.y - mid) < r) {
                    if (trackLocked[track.type] == true) {
                        return
                    }
                    listener?.onTrackImportRequested(track.type)
                    return
                }
            }
        }

        // Ruler tap → playhead scrub
        if (event.y < rulerHeightPx) {
            gesture = GestureKind.PLAYHEAD_SCRUB
            showPlayheadTooltip = true
            val ms = xToMs(event.x)
            playheadMs = ms
            scrollX = scrollForPlayhead(ms)
            listener?.onPlayheadScrub(ms)
            invalidate()
            return
        }

        // Find clip under touch
        val clip = findClipAt(event.x, event.y) ?: return
        val left = msToX(clip.startTimeMs)
        val right = msToX(clip.startTimeMs + clip.durationMs)

        val isTrackLocked = trackLocked[clip.trackType] == true
        if (isTrackLocked) {
            if (clip.id != selectedClipId) {
                selectedClipId = clip.id
                listener?.onClipSelected(clip.id)
                invalidate()
            }
            return
        }

        gesture = when {
            clip.id == selectedClipId && event.x <= left + handleWidthPx + dp(4) -> GestureKind.TRIM_START
            clip.id == selectedClipId && event.x >= right - handleWidthPx - dp(4) -> GestureKind.TRIM_END
            else -> GestureKind.MOVE
        }
        gestureClipId = clip.id
        gestureClipSnapshot = clip

        // Schedule long press
        longPressClipId = clip.id
        longPressHandler.postDelayed(longPressRunnable, 500L)

        if (clip.id != selectedClipId) {
            selectedClipId = clip.id
            listener?.onClipSelected(clip.id)
            invalidate()
        }
    }

    private fun onMove(event: MotionEvent) {
        val dx = event.x - touchDownX
        val dy = event.y - touchDownY
        val stepX = event.x - lastTouchX
        lastTouchX = event.x

        if (!gestureStarted) {
            if (abs(dx) < touchSlop && abs(dy) < touchSlop) return
            gestureStarted = true
            longPressHandler.removeCallbacks(longPressRunnable) // cancel long press on move
        }

        when (gesture) {
            GestureKind.PLAYHEAD_SCRUB -> {
                val ms = xToMs(event.x)
                playheadMs = ms
                scrollX = scrollForPlayhead(ms)
                showPlayheadTooltip = true
                listener?.onPlayheadScrub(ms)
                invalidate()
            }
            GestureKind.SCROLL -> {
                scrollX = (scrollX - stepX).coerceIn(0f, maxScrollX())
                // Update playhead time based on scroll position
                val newMs = ((scrollX + centerX() - headerWidthPx) / pxPerMs).toLong().coerceAtLeast(0L)
                playheadMs = newMs
                listener?.onPlayheadScrub(newMs)
                invalidate()
            }
            GestureKind.NONE -> {
                // Decide: scroll or clip gesture
                if (gestureClipId == null) {
                    gesture = GestureKind.SCROLL
                    scrollX = (scrollX - stepX).coerceAtLeast(0f)
                    invalidate()
                } else {
                    // Already set in onDown — just start
                }
            }
            GestureKind.TRIM_START, GestureKind.TRIM_END, GestureKind.MOVE -> {
                val snap = gestureClipId?.let { applyGesture(it, dx) }
                snapTimeMs = snap
                invalidate()
            }
        }
    }

    private fun onUp(event: MotionEvent) {
        longPressHandler.removeCallbacks(longPressRunnable)
        longPressClipId = null
        showPlayheadTooltip = false
        if (gestureStarted && gesture in listOf(GestureKind.TRIM_START, GestureKind.TRIM_END, GestureKind.MOVE)) {
            gestureClipId?.let { commitGesture(it, event.x - touchDownX) }
        } else if (!gestureStarted && gesture == GestureKind.NONE && gestureClipId == null) {
            if (event.x >= headerWidthPx && event.y >= rulerHeightPx) {
                val ms = xToMs(event.x)
                playheadMs = ms
                scrollX = scrollForPlayhead(ms)
                listener?.onPlayheadScrub(ms)
            }
            // Tap on empty area → deselect
            selectedClipId = null
            listener?.onClipSelected(null)
            invalidate()
        }
        snapTimeMs = null
        gesture = GestureKind.NONE
        gestureClipId = null
        gestureClipSnapshot = null
        gestureStarted = false
        invalidate()
    }

    // ── Gesture math ──────────────────────────────────────────────────────────
    private fun applyGesture(clipId: String, deltaPx: Float): Long? {
        val snap = gestureClipSnapshot ?: return null
        val update = buildUpdate(snap, deltaPx) ?: return null
        listener?.onClipUpdatePreview(update)
        // Update visual immediately
        updateClipPreview(clipId, update)
        invalidate()
        return snapTimeMs
    }

    private fun commitGesture(clipId: String, deltaPx: Float) {
        val snap = gestureClipSnapshot ?: return
        val update = buildUpdate(snap, deltaPx) ?: return
        listener?.onClipUpdateCommitted(update)
    }

    private fun buildUpdate(clip: ClipSegment, deltaPx: Float): ClipUpdate? {
        val deltaMs = (deltaPx / pxPerMs).roundToLong()
        return when (gesture) {
            GestureKind.TRIM_START -> {
                val allSnaps = buildSnapTargets(clip)
                val sameTrackClips = tracks
                    .firstOrNull { t -> t.clips.any { it.id == clip.id } }
                    ?.clips?.filter { it.id != clip.id } ?: emptyList()
                val prevClipEnd = sameTrackClips
                    .filter { it.startTimeMs + it.durationMs <= clip.startTimeMs }
                    .maxOfOrNull { it.startTimeMs + it.durationMs } ?: 0L
                val trimStartMax = (clip.startTimeMs + clip.durationMs - 33L).coerceAtLeast(prevClipEnd)
                val rawStart = (clip.startTimeMs + deltaMs)
                    .coerceIn(prevClipEnd, trimStartMax)
                val snapped = snapToTargets(rawStart, allSnaps)
                    .coerceIn(prevClipEnd, trimStartMax)
                val deltaStartMs = snapped - clip.startTimeMs
                val newDur = (clip.durationMs - deltaStartMs).coerceAtLeast(33L)
                ClipUpdate(
                    clipId = clip.id, trackType = clip.trackType,
                    startTimeMs = snapped, durationMs = newDur,
                    sourceInMs = clip.sourceInMs + deltaStartMs, sourceOutMs = clip.sourceOutMs,
                    originalStartTimeMs = clip.startTimeMs, originalDurationMs = clip.durationMs,
                    originalSourceInMs = clip.sourceInMs, originalSourceOutMs = clip.sourceOutMs,
                    gestureKind = ClipGestureKind.TRIM_START,
                )
            }
            GestureKind.TRIM_END -> {
                val allSnaps = buildSnapTargets(clip)
                val sameTrackClips = tracks
                    .firstOrNull { t -> t.clips.any { it.id == clip.id } }
                    ?.clips?.filter { it.id != clip.id } ?: emptyList()
                val nextClipStart = sameTrackClips
                    .filter { it.startTimeMs >= clip.startTimeMs + clip.durationMs }
                    .minOfOrNull { it.startTimeMs } ?: Long.MAX_VALUE
                val maxEnd = if (nextClipStart == Long.MAX_VALUE) Long.MAX_VALUE else nextClipStart
                val rawEnd = (clip.startTimeMs + clip.durationMs + deltaMs)
                    .coerceIn(clip.startTimeMs + 33L, maxEnd)
                val snapped = snapToTargets(rawEnd, allSnaps)
                    .coerceIn(clip.startTimeMs + 33L, maxEnd)
                val newDur = (snapped - clip.startTimeMs).coerceAtLeast(33L)
                ClipUpdate(
                    clipId = clip.id, trackType = clip.trackType,
                    startTimeMs = clip.startTimeMs, durationMs = newDur,
                    sourceInMs = clip.sourceInMs, sourceOutMs = clip.sourceInMs + newDur,
                    originalStartTimeMs = clip.startTimeMs, originalDurationMs = clip.durationMs,
                    originalSourceInMs = clip.sourceInMs, originalSourceOutMs = clip.sourceOutMs,
                    gestureKind = ClipGestureKind.TRIM_END,
                )
            }
            GestureKind.MOVE -> {
                val allSnaps = buildSnapTargets(clip)
                val rawStart = (clip.startTimeMs + deltaMs).coerceAtLeast(0L)
                var snapped = snapToTargets(rawStart, allSnaps)

                // Prevent overlap with other clips on same track
                val sameTrackClips = tracks
                    .firstOrNull { t -> t.clips.any { it.id == clip.id } }
                    ?.clips?.filter { it.id != clip.id } ?: emptyList()

                val newEnd = snapped + clip.durationMs
                for (other in sameTrackClips) {
                    val otherEnd = other.startTimeMs + other.durationMs
                    // Clip moving right — snap to left edge of next clip
                    if (snapped < other.startTimeMs && newEnd > other.startTimeMs) {
                        snapped = other.startTimeMs - clip.durationMs
                    }
                    // Clip moving left — snap to right edge of prev clip
                    if (snapped < otherEnd && snapped + clip.durationMs > otherEnd && snapped >= other.startTimeMs) {
                        snapped = otherEnd
                    }
                }
                snapped = snapped.coerceAtLeast(0L)

                ClipUpdate(
                    clipId = clip.id, trackType = clip.trackType,
                    startTimeMs = snapped, durationMs = clip.durationMs,
                    sourceInMs = clip.sourceInMs, sourceOutMs = clip.sourceOutMs,
                    originalStartTimeMs = clip.startTimeMs, originalDurationMs = clip.durationMs,
                    originalSourceInMs = clip.sourceInMs, originalSourceOutMs = clip.sourceOutMs,
                    gestureKind = ClipGestureKind.MOVE,
                )
            }
            else -> null
        }
    }

    private fun buildSnapTargets(clip: ClipSegment): List<Long> {
        val targets = mutableListOf(0L, playheadMs)
        tracks.forEach { t ->
            t.clips.forEach { c ->
                if (c.id != clip.id) {
                    targets += c.startTimeMs
                    targets += c.startTimeMs + c.durationMs
                }
            }
        }
        return targets
    }

    private fun snapToTargets(ms: Long, targets: List<Long>): Long {
        val thresholdMs = (snapThresholdPx / pxPerMs).roundToLong()
        val best = targets.minByOrNull { abs(it - ms) } ?: return ms
        return if (abs(best - ms) <= thresholdMs) {
            if (best != lastHapticSnapMs) {
                lastHapticSnapMs = best
                snapTimeMs = best
                vibrate()
            }
            best
        } else {
            lastHapticSnapMs = null
            ms
        }
    }

    private fun vibrate() {
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    // Update clip in tracks list for live preview
    private fun updateClipPreview(clipId: String, update: ClipUpdate) {
        tracks = tracks.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) clip.copy(
                    startTimeMs = update.startTimeMs,
                    durationMs = update.durationMs,
                    sourceInMs = update.sourceInMs,
                    sourceOutMs = update.sourceOutMs,
                ) else clip
            })
        }
        recalcContentWidth()
    }

    // ── Hit test ──────────────────────────────────────────────────────────────
    private fun findClipAt(x: Float, y: Float): ClipSegment? {
        tracks.forEachIndexed { i, track ->
            val top = trackTop(i).toFloat()
            val bottom = top + trackHeightPx
            if (y < top || y > bottom) return@forEachIndexed
            track.clips.forEach { clip ->
                val left = msToX(clip.startTimeMs)
                val right = msToX(clip.startTimeMs + clip.durationMs)
                if (x >= left - dp(4) && x <= right + dp(4)) return clip
            }
        }
        return null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        recalcContentWidth()
        scheduleAssetRequests()
    }

    // Preferred height = ruler + all tracks
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val trackCount = layoutTrackCount
        val preferred = rulerHeightPx + trackCount * (minTrackHeightBasePx + trackGapPx) + timelineBottomInsetPx
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(preferred, MeasureSpec.getSize(heightMeasureSpec))
            else -> preferred
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }
}

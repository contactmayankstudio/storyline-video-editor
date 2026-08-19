package com.video.engine.pro.timeline

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.os.VibrationEffect
import com.video.engine.DeviceDetector
import com.video.engine.transition.TransitionStore
import com.video.engine.transition.TransitionType
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
        fun onTrackSelected(trackType: TrackType) {}
        fun onTrackLockedChanged(trackType: TrackType, isLocked: Boolean) {}
        fun onTransitionRequested(outgoingClipId: Int, incomingClipId: Int) {}
    }
    var listener: Listener? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private var tracks: List<TrackState> = emptyList()
    private var playheadMs: Long = 0L
    private var selectedClipId: String? = null
    private var pxPerSecond: Float = 120f   // zoom level
    private val pxPerMs get() = pxPerSecond / 1000f
    private var playbackActive: Boolean = false
    private var playbackAnchorTimelineMs: Long = 0L
    private var playbackAnchorRealtimeMs: Long = 0L
    private var playbackTickerRunning = false

    // scroll offset in px (how far left the content is scrolled)
    private var scrollX: Float = 0f
    private var scrollY: Float = 0f
    private var totalContentWidthPx: Float = 0f

    // ── Layout constants ──────────────────────────────────────────────────────
    private val rulerHeightPx = dp(20)
    private val minTrackHeightBasePx = dp(34)
    private val minLaneHeightPx = dp(24)
    private val maxTrackHeightBasePx = dp(50)
    private val trackGapPx = dp(4)
    private val trackInnerGapPx = dp(3)
    private val headerWidthPx = 0
    private val timelineBottomInsetPx = dp(6)
    private val handleWidthPx = dp(10)
    private val snapThresholdPx = dp(12).toFloat()
    private val minClipWidthPx = dp(4).toFloat()
    private val playheadFollowMarginPx = dp(28).toFloat()

    // ── Paints ────────────────────────────────────────────────────────────────
    private val bgPaint = Paint().apply { color = Color.parseColor("#06080B") }
    private val rulerPaint = Paint().apply { color = Color.parseColor("#0D1015") }
    private val rulerTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E2530"); strokeWidth = 1f
    }
    private val rulerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7888"); textSize = dp(8.5f); textAlign = Paint.Align.CENTER
    }
    private val headerPaint = Paint().apply { color = Color.parseColor("#0D1015") }
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.5f).toFloat(); color = Color.parseColor("#388BFD")
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = dp(1.5f).toFloat()
    }
    private val snapLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#388BFD"); strokeWidth = dp(1).toFloat()
    }
    private val clipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(9).toFloat()
        setShadowLayer(2f, 0f, 1f, Color.BLACK)
    }

    private val clipRect = RectF()
    private val handleRect = RectF()
    private val laneRect = RectF()

    // ── Extra paints ──────────────────────────────────────────────────────────
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC0D1015") }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(11).toFloat(); textAlign = Paint.Align.CENTER
    }
    private val durationTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D6E0EA"); textSize = dp(8).toFloat(); textAlign = Paint.Align.CENTER
    }
    private val transitionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#388BFD") }
    private val transitionStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = Color.WHITE
    }
    private val transitionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(8.2f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val trackAddChipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val trackActionRailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#04FFFFFF")
    }
    private val trackActionRailStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.65f)
        color = Color.parseColor("#0AFFFFFF")
    }

    // ── Cover & Track Badge Paints ───────────────────────────────────────────
    private val coverBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#11151B") }
    private val coverStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(0.75f).toFloat(); color = Color.parseColor("#1B222C")
    }
    private val coverTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A99AD"); textSize = dp(9f); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val coverCardRect = RectF()
    private val trackBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#141922") }
    private val trackBadgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(0.75f).toFloat(); color = Color.parseColor("#1E2633")
    }
    private val trackBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A99AD"); textSize = dp(8f); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val trackBadgeRect = RectF()
    private val thumbnailPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val trackActionChipShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#12000000")
    }
    private val trackAddChipStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.75f)
        color = Color.parseColor("#222A36")
    }
    private val trackAddChipInnerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
        color = Color.parseColor("#14FFFFFF")
    }
    private val trackAddChipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(6.8f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textScaleX = 0.84f
    }
    private val trackLockedOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#32000000")
    }
    private val trackLanePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackLaneStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.75f).toFloat()
        color = Color.parseColor("#171D26")
    }
    private val verticalScrollThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60388BFD")
    }
    private val headerCellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headerDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#141922")
        strokeWidth = dp(0.75f).toFloat()
    }

    private data class TransitionMarker(
        val outgoingClipId: Int,
        val incomingClipId: Int,
        val rect: RectF,
    )

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
    private var lastTouchY = 0f
    private var gestureClipId: String? = null
    private var gestureClipSnapshot: ClipSegment? = null
    private var snapTimeMs: Long? = null
    private var gestureStarted = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var gestureStartScrollX = 0f
    private var gestureStartScrollY = 0f
    private var gestureCurrentX = 0f
    private var gestureCurrentY = 0f
    private var pendingGestureApplyClipId: String? = null
    private var pendingGestureApplyDeltaPx = 0f
    private var pendingGestureApplyDeltaYPx = 0f
    private var gestureApplyScheduled = false
    private var edgeAutoScrollRunning = false
    private val gestureApplyRunnable = Runnable {
        gestureApplyScheduled = false
        val clipId = pendingGestureApplyClipId ?: return@Runnable
        pendingGestureApplyClipId = null
        val snap = applyGesture(clipId, pendingGestureApplyDeltaPx, pendingGestureApplyDeltaYPx)
        snapTimeMs = snap
    }
    private val playbackTickerRunnable = object : Runnable {
        override fun run() {
            if (!playbackActive) {
                playbackTickerRunning = false
                return
            }
            playbackTickerRunning = true
            if (!timelineInteractionActive()) {
                val now = SystemClock.uptimeMillis()
                val predictedMs = (playbackAnchorTimelineMs + (now - playbackAnchorRealtimeMs))
                    .coerceAtLeast(0L)
                    .coerceAtMost(maxTimelineEndMs().coerceAtLeast(playbackAnchorTimelineMs))
                renderPlaybackPosition(predictedMs)
            }
            postOnAnimationDelayed(this, playbackTickerIntervalMs())
        }
    }
    private val edgeAutoScrollRunnable = object : Runnable {
        override fun run() {
            if (!gestureStarted || gesture !in listOf(GestureKind.TRIM_START, GestureKind.TRIM_END, GestureKind.MOVE)) {
                edgeAutoScrollRunning = false
                return
            }
            val requestedStepX = computeEdgeAutoScrollStepPx(gestureCurrentX)
            val requestedStepY = 0f
            if (kotlin.math.abs(requestedStepX) < 0.5f && kotlin.math.abs(requestedStepY) < 0.5f) {
                edgeAutoScrollRunning = false
                return
            }
            val beforeScrollX = scrollX
            val beforeScrollY = scrollY
            scrollX = (scrollX + requestedStepX).coerceIn(0f, maxScrollX())
            scrollY = (scrollY + requestedStepY).coerceIn(0f, maxScrollY())
            val consumedPx = scrollX - beforeScrollX
            val consumedYPx = scrollY - beforeScrollY
            if (kotlin.math.abs(consumedPx) < 0.5f && kotlin.math.abs(consumedYPx) < 0.5f) {
                edgeAutoScrollRunning = false
                return
            }
            gestureClipId?.let { clipId ->
                queueGestureApply(clipId, gestureCurrentX - touchDownX, gestureCurrentY - touchDownY)
            }
            scheduleAssetRequests(deferForInteraction = true)
            postInvalidateOnAnimation()
            postOnAnimation(this)
        }
    }

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
                val focusMs = (zoomFocusScrollX + zoomFocusX - centerX()) / (pxPerSecond / 1000f)
                pxPerSecond = newPps
                invalidateAssetRequestWindow()
                scrollX = ((focusMs * pxPerMs) - (zoomFocusX - centerX()))
                    .coerceIn(0f, maxScrollX())
                listener?.onZoomChanged(pxPerSecond)
                scheduleAssetRequests()
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
        val filteredTracks = tracks.filter { it.type != TrackType.LAYER || it.clips.isNotEmpty() }
        if (this.tracks == filteredTracks) {
            return
        }
        this.tracks = filteredTracks
        this.tracks.forEach { track ->
            trackVisible[track.type] = track.isVisible
            trackLocked[track.type] = track.isLocked
        }
        recalcContentWidth()
        clampScroll()
        invalidateAssetRequestWindow()
        scheduleAssetRequests()
        requestLayout()
        invalidate()
    }

    fun getTracks(): List<TrackState> = tracks

    fun getSelectedClipId(): String? = selectedClipId

    fun getPlayheadMs(): Long = playheadMs

    fun setPlaybackActive(active: Boolean) {
        if (playbackActive == active) return
        playbackActive = active
        if (active) {
            scroller.abortAnimation()
            assetRequestHandler.removeCallbacks(assetRequestRunnable)
            assetRequestScheduled = false
            playbackAnchorTimelineMs = playheadMs
            playbackAnchorRealtimeMs = SystemClock.uptimeMillis()
            startPlaybackTicker()
        } else {
            stopPlaybackTicker()
            scheduleAssetRequests()
        }
    }

    fun suspendAssetRequests(windowMs: Long) {
        val until = SystemClock.elapsedRealtime() + windowMs.coerceAtLeast(250L)
        externalAssetRequestsSuspendedUntilElapsedMs =
            max(externalAssetRequestsSuspendedUntilElapsedMs, until)
        assetRequestHandler.removeCallbacks(assetRequestRunnable)
        assetRequestScheduled = false
        scheduleAssetRequests(deferForInteraction = true)
    }

    // Center-fixed playhead: playhead always at screen center, clips scroll
    private fun centerX() = width / 2f

    private fun scrollForPlayhead(ms: Long) =
        (ms * pxPerMs).coerceIn(0f, maxScrollX())

    private fun scrollToKeepPlayheadVisible(ms: Long): Float {
        return scrollForPlayhead(ms)
    }

    fun setPlayheadMs(ms: Long) {
        if (playbackActive) {
            playbackAnchorTimelineMs = ms.coerceAtLeast(0L)
            playbackAnchorRealtimeMs = SystemClock.uptimeMillis()
            if (!timelineInteractionActive()) {
                renderPlaybackPosition(playbackAnchorTimelineMs)
            }
            startPlaybackTicker()
            return
        }
        val previousScrollX = scrollX
        val previousPlayheadMs = playheadMs
        playheadMs = ms
        if (timelineInteractionActive()) {
            postInvalidateOnAnimation()
            return
        }
        scrollX = scrollForPlayhead(ms)
        if (abs(scrollX - previousScrollX) >= 0.5f) {
            maybeSchedulePlaybackAssetRequests()
            postInvalidateOnAnimation()
        } else if (abs(ms - previousPlayheadMs) >= 16L) {
            postInvalidateOnAnimation()
        }
    }

    private fun renderPlaybackPosition(ms: Long) {
        val clampedMs = ms.coerceAtLeast(0L)
        val previousScrollX = scrollX
        val previousPlayheadMs = playheadMs
        playheadMs = clampedMs
        scrollX = scrollForPlayhead(clampedMs)
        if (abs(scrollX - previousScrollX) >= 0.5f || abs(clampedMs - previousPlayheadMs) >= 8L) {
            postInvalidateOnAnimation()
        }
    }

    private fun startPlaybackTicker() {
        if (playbackTickerRunning) return
        playbackTickerRunning = true
        removeCallbacks(playbackTickerRunnable)
        postOnAnimation(playbackTickerRunnable)
    }

    private fun stopPlaybackTicker() {
        playbackTickerRunning = false
        removeCallbacks(playbackTickerRunnable)
    }

    private fun playbackTickerIntervalMs(): Long =
        when (DeviceDetector.getDeviceTier()) {
            DeviceDetector.DeviceTier.LOW -> 33L
            DeviceDetector.DeviceTier.MID -> 24L
            DeviceDetector.DeviceTier.HIGH -> 16L
        }

    fun setSelectedClipId(id: String?) {
        if (selectedClipId == id) {
            return
        }
        selectedClipId = id
        invalidate()
    }

    fun setZoomPxPerSecond(pps: Float) {
        pxPerSecond = pps.coerceIn(24f, 6000f)
        recalcContentWidth()
        invalidateAssetRequestWindow()
        scheduleAssetRequests()
        invalidate()
    }

    private fun invalidateAssetRequestWindow() {
        lastAssetWindowStartBucket = Int.MIN_VALUE
        lastAssetWindowEndBucket = Int.MIN_VALUE
        lastAssetZoomBucket = Int.MIN_VALUE
        lastAssetTrackCount = -1
        lastPlaybackAssetRequestScrollX = Float.NaN
    }

    private fun assetRequestDelayMs(): Long =
        when (DeviceDetector.getDeviceTier()) {
            DeviceDetector.DeviceTier.LOW -> 220L
            DeviceDetector.DeviceTier.MID -> 120L
            DeviceDetector.DeviceTier.HIGH -> 56L
        }

    private fun assetRequestInteractionDelayMs(): Long =
        when (DeviceDetector.getDeviceTier()) {
            DeviceDetector.DeviceTier.LOW -> 420L
            DeviceDetector.DeviceTier.MID -> 220L
            DeviceDetector.DeviceTier.HIGH -> 120L
        }

    private fun timelineInteractionActive(): Boolean =
        gesture != GestureKind.NONE ||
            gestureStarted ||
            edgeAutoScrollRunning ||
            scaleDetector.isInProgress ||
            !scroller.isFinished

    private fun externalAssetRequestsSuspended(): Boolean =
        SystemClock.elapsedRealtime() < externalAssetRequestsSuspendedUntilElapsedMs

    private fun maybeSchedulePlaybackAssetRequests() {
        if (playbackActive) return
        val playbackRequestStridePx = dp(72).toFloat()
        if (lastPlaybackAssetRequestScrollX.isNaN() ||
            abs(scrollX - lastPlaybackAssetRequestScrollX) >= playbackRequestStridePx
        ) {
            lastPlaybackAssetRequestScrollX = scrollX
            scheduleAssetRequests()
        }
    }

    private fun assetPrefetchMarginPx(): Float {
        val viewportWidthPx = (width - headerWidthPx).coerceAtLeast(dp(72))
        val overscanMultiplier = if (DeviceDetector.isLowEndDevice()) 0.4f else 0.85f
        return viewportWidthPx * overscanMultiplier
    }

    private fun clipContentStartPx(clip: ClipSegment): Float =
        headerWidthPx + clip.startTimeMs * pxPerMs

    private fun clipContentEndPx(clip: ClipSegment): Float =
        headerWidthPx + (clip.startTimeMs + clip.durationMs) * pxPerMs

    private fun maxTimelineEndMs(): Long {
        var maxEndMs = 0L
        tracks.forEach { track ->
            track.clips.forEach { clip ->
                maxEndMs = max(maxEndMs, clip.startTimeMs + clip.durationMs)
            }
        }
        return maxEndMs
    }

    private fun performAssetRequests() {
        if (width <= 0) return
        if (playbackActive) return
        if (timelineInteractionActive() || externalAssetRequestsSuspended()) {
            scheduleAssetRequests(deferForInteraction = true)
            return
        }
        val windowStartPx = (scrollX + headerWidthPx - assetPrefetchMarginPx())
            .coerceAtLeast(headerWidthPx.toFloat())
        val windowEndPx = scrollX + width + assetPrefetchMarginPx()
        val bucketSizePx = dp(96).coerceAtLeast(1)
        val startBucket = (windowStartPx / bucketSizePx).toInt()
        val endBucket = (windowEndPx / bucketSizePx).toInt()
        val zoomBucket = (pxPerSecond / 24f).roundToInt()
        val trackCount = tracks.sumOf { it.clips.size }
        if (
            startBucket == lastAssetWindowStartBucket &&
            endBucket == lastAssetWindowEndBucket &&
            zoomBucket == lastAssetZoomBucket &&
            trackCount == lastAssetTrackCount
        ) {
            return
        }
        lastAssetWindowStartBucket = startBucket
        lastAssetWindowEndBucket = endBucket
        lastAssetZoomBucket = zoomBucket
        lastAssetTrackCount = trackCount
        tracks.forEach { track ->
            track.clips.forEach { clip ->
                requestThumbnails(clip, windowStartPx, windowEndPx)
                requestWaveform(clip, windowStartPx, windowEndPx)
            }
        }
    }

    private fun scheduleAssetRequests(
        immediate: Boolean = false,
        deferForInteraction: Boolean = false,
    ) {
        if (immediate) {
            assetRequestHandler.removeCallbacks(assetRequestRunnable)
            assetRequestScheduled = false
            performAssetRequests()
            return
        }
        if (assetRequestScheduled) return
        assetRequestScheduled = true
        val delayMs = if (deferForInteraction) assetRequestInteractionDelayMs() else assetRequestDelayMs()
        assetRequestHandler.postDelayed(assetRequestRunnable, delayMs)
    }

    // ── Layout ────────────────────────────────────────────────────────────────
    private val videoTrackHeightScale = 2.2f

    private fun trackLaneHeightPx(track: TrackState): Int =
        if (track.type == TrackType.VIDEO) (laneHeightPx * videoTrackHeightScale).toInt() else laneHeightPx

    private fun maxScrollX() = totalContentWidthPx.coerceAtLeast(0f)

    private fun tracksContentHeightPx(): Float {
        if (height <= 0) return 0f
        val trackCount = tracks.size
        var total = 0f
        for (index in 0 until trackCount) {
            total += trackVisualHeightPx(index)
            if (index < trackCount - 1) total += trackGapPx
        }
        return total + timelineBottomInsetPx
    }

    private fun maxScrollY(): Float = 0f

    private fun clampScroll() {
        scrollX = scrollX.coerceIn(0f, maxScrollX())
        scrollY = 0f
    }

    private fun recalcContentWidth() {
        totalContentWidthPx = maxTimelineEndMs() * pxPerMs
    }

    private val layoutTrackCount: Int
        get() = tracks.size.coerceAtLeast(1)

    private fun trackLaneCount(track: TrackState): Int {
        if (!track.type.allowsIndependentLanes()) return 1
        return track.clips
            .maxOfOrNull { clipLaneIndex(it) + 1 }
            ?.coerceAtLeast(1)
            ?: 1
    }

    private val totalLaneSlotCount: Int
        get() = if (tracks.isEmpty()) 1 else tracks.sumOf(::trackLaneCount)

    private val laneHeightPx: Int
        get() {
            if (height <= 0) return minTrackHeightBasePx
            val totalWeight = tracks.sumOf { if (it.type == TrackType.VIDEO) videoTrackHeightScale.toDouble() else 1.0 }.toFloat().coerceAtLeast(1f)
            val availableHeight =
                (height - rulerHeightPx - timelineBottomInsetPx - (tracks.size.coerceAtLeast(1) - 1) * trackGapPx)
                    .coerceAtLeast(tracks.size.coerceAtLeast(1) * minLaneHeightPx)
            val unitH = (availableHeight / totalWeight).toInt()
            return unitH.coerceIn(minLaneHeightPx, maxTrackHeightBasePx)
        }

    private fun trackVisualHeightPx(track: TrackState): Int {
        val laneCount = trackLaneCount(track)
        val baseH = trackLaneHeightPx(track)
        return laneCount * baseH + (laneCount - 1) * trackInnerGapPx
    }

    private fun trackVisualHeightPx(trackIndex: Int): Int {
        val track = tracks.getOrNull(trackIndex) ?: return laneHeightPx
        return trackVisualHeightPx(track)
    }

    private fun trackContentTop(trackIndex: Int): Float {
        var top = 0f
        val clampedIndex = trackIndex.coerceAtLeast(0)
        for (i in 0 until clampedIndex) {
            top += trackVisualHeightPx(i) + trackGapPx
        }
        return top
    }

    private fun trackTop(trackIndex: Int): Float {
        return rulerHeightPx + trackContentTop(trackIndex)
    }

    private fun clipLaneIndex(clip: ClipSegment): Int {
        if (!clip.trackType.allowsIndependentLanes()) return 0
        return clip.metadata["subTrack"]?.toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0
    }

    private fun clipSourceDurationMs(clip: ClipSegment): Long =
        clip.metadata["sourceDurationMs"]?.toLongOrNull()?.coerceAtLeast(1L) ?: 0L

    private fun clipTop(trackIndex: Int, clip: ClipSegment): Float {
        val base = trackTop(trackIndex)
        val laneIndex = clipLaneIndex(clip)
        val track = tracks.getOrNull(trackIndex)
        val laneH = track?.let { trackLaneHeightPx(it) } ?: laneHeightPx
        return base + laneIndex * (laneH + trackInnerGapPx)
    }

    private fun clipBottom(trackIndex: Int, clip: ClipSegment): Float {
        val track = tracks.getOrNull(trackIndex)
        val laneH = track?.let { trackLaneHeightPx(it) } ?: laneHeightPx
        return clipTop(trackIndex, clip) + laneH
    }

    private fun msToX(ms: Long) = centerX() + ms * pxPerMs - scrollX

    private fun xToMs(x: Float) = (((x - centerX() + scrollX) / pxPerMs).roundToLong()).coerceAtLeast(0L)

    // ── Draw ──────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        canvas.save()
        canvas.clipRect(0f, rulerHeightPx.toFloat(), width.toFloat(), height.toFloat())
        drawTracks(canvas)
        drawCoverCard(canvas)
        drawTrackImportChips(canvas)
        drawTransitionMarkers(canvas)
        snapTimeMs?.let { drawSnapLine(canvas, it) }
        canvas.restore()
        drawRuler(canvas)
        drawPlayhead(canvas)
        if (showPlayheadTooltip) drawPlayheadTooltip(canvas)
    }

    private fun drawCoverCard(canvas: Canvas) {
        val zeroX = msToX(0L)
        val cardW = dp(62).toFloat()
        val cardRight = zeroX - dp(8).toFloat()
        val cardLeft = cardRight - cardW
        if (cardRight <= 0f) return

        val videoTrackIndex = tracks.indexOfFirst { it.type == TrackType.VIDEO }.takeIf { it >= 0 } ?: 0
        val top = trackTop(videoTrackIndex).toFloat()
        val bottom = top + trackVisualHeightPx(videoTrackIndex).toFloat()
        coverCardRect.set(cardLeft, top + dp(1), cardRight, bottom - dp(1))

        canvas.drawRoundRect(coverCardRect, dp(6).toFloat(), dp(6).toFloat(), coverBgPaint)
        canvas.drawRoundRect(coverCardRect, dp(6).toFloat(), dp(6).toFloat(), coverStrokePaint)

        val firstThumb = clipThumbnails.values.firstOrNull()?.firstOrNull()
        if (firstThumb != null && !firstThumb.isRecycled) {
            canvas.save()
            canvas.clipRect(coverCardRect)
            thumbSrcRect.set(0, 0, firstThumb.width, firstThumb.height)
            thumbDstRect.set(coverCardRect.left, coverCardRect.top, coverCardRect.right, coverCardRect.bottom - dp(15))
            canvas.drawBitmap(firstThumb, thumbSrcRect, thumbDstRect, thumbnailPaint)
            canvas.restore()
        }

        canvas.drawText("Cover", coverCardRect.centerX(), coverCardRect.bottom - dp(4), coverTextPaint)
    }

    private fun drawVerticalScrollThumb(canvas: Canvas) {
        return
    }

    private fun drawRuler(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), rulerHeightPx.toFloat(), rulerPaint)
        val stepMs = rulerStepMs()
        val startMs = xToMs(0f).let { it - it % stepMs }
        var ms = startMs
        while (msToX(ms) < width) {
            val x = msToX(ms)
            if (x >= 0f && x <= width) {
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

    // ── Waveform cache ────────────────────────────────────────────────────────
    private val clipWaveforms = mutableMapOf<String, FloatArray>()
    private val clipWaveformKeys = mutableMapOf<String, String>()
    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#AAFFFFFF") }
    private var lastInvalidateMs = 0L
    private val invalidateThrottleMs = 16L
    private val assetRequestHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var assetRequestScheduled = false
    private var externalAssetRequestsSuspendedUntilElapsedMs = 0L
    private var lastAssetWindowStartBucket = Int.MIN_VALUE
    private var lastAssetWindowEndBucket = Int.MIN_VALUE
    private var lastAssetZoomBucket = Int.MIN_VALUE
    private var lastAssetTrackCount = -1
    private var lastPlaybackAssetRequestScrollX = Float.NaN
    private val assetRequestRunnable = Runnable {
        assetRequestScheduled = false
        performAssetRequests()
    }

    private fun throttledInvalidate() {
        val now = System.currentTimeMillis()
        if (now - lastInvalidateMs >= invalidateThrottleMs) {
            lastInvalidateMs = now
            postInvalidate()
        }
    }

    private fun requestWaveform(clip: ClipSegment, windowStartPx: Float, windowEndPx: Float) {
        if (clip.trackType != TrackType.AUDIO && clip.trackType != TrackType.VIDEO) return
        if (clip.sourcePath.isBlank()) return
        val clipLeftPx = clipContentStartPx(clip)
        val clipRightPx = clipContentEndPx(clip)
        if (clipRightPx < windowStartPx || clipLeftPx > windowEndPx) return
        val visibleWidthPx = (min(clipRightPx, windowEndPx) - max(clipLeftPx, windowStartPx))
            .roundToInt()
            .coerceAtLeast(dp(48))
        val barCount = (visibleWidthPx / dp(4).coerceAtLeast(1))
            .coerceIn(16, if (DeviceDetector.isLowEndDevice()) 64 else 256)
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
        if (playbackActive && DeviceDetector.isLowEndDevice()) return
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

    private fun requestThumbnails(clip: ClipSegment, windowStartPx: Float, windowEndPx: Float) {
        if (clip.trackType != TrackType.VIDEO && !clip.trackType.isOverlayLike()) return
        if (clip.sourcePath.isBlank()) return
        val clipLeftPx = clipContentStartPx(clip)
        val clipRightPx = clipContentEndPx(clip)
        if (clipRightPx < windowStartPx || clipLeftPx > windowEndPx) return
        val track = tracks.firstOrNull { it.clips.any { c -> c.id == clip.id } }
        val actualLaneH = track?.let { trackLaneHeightPx(it) } ?: laneHeightPx
        val targetH = (actualLaneH * 2).coerceIn(120, 320)
        val visibleWidth = (min(clipRightPx, windowEndPx) - max(clipLeftPx, windowStartPx))
            .roundToInt()
            .coerceAtLeast(dp(72))
        val assetWindowWidth = (windowEndPx - windowStartPx).roundToInt().coerceAtLeast(dp(72))
        val viewportW = (visibleWidth.coerceAtMost(assetWindowWidth) * 2).coerceIn(120, 1920)
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
            val bottom = top + trackVisualHeightPx(track)
            laneRect.set(headerWidthPx.toFloat() + dp(4), top + dp(1), width.toFloat() - dp(4), bottom - dp(1))
            trackLanePaint.color = trackLaneFill(track.type, trackLocked[track.type] == true)
            canvas.drawRoundRect(laneRect, dp(8).toFloat(), dp(8).toFloat(), trackLanePaint)
            trackLaneStrokePaint.color = trackLaneStroke(track.type)
            canvas.drawRoundRect(laneRect, dp(8).toFloat(), dp(8).toFloat(), trackLaneStrokePaint)
            val laneCount = trackLaneCount(track)
            if (laneCount > 1) {
                for (lane in 1 until laneCount) {
                    val y = top + lane * laneHeightPx + (lane - 0.5f) * trackInnerGapPx
                    canvas.drawLine(
                        headerWidthPx + dp(8).toFloat(),
                        y,
                        width - dp(8).toFloat(),
                        y,
                        headerDividerPaint,
                    )
                }
            }

            // Draw floating track badge on the left inside the track row
            val badgeLeft = dp(8).toFloat()
            val badgeTop = top + dp(4).toFloat()
            trackBadgeRect.set(badgeLeft, badgeTop, badgeLeft + dp(18).toFloat(), badgeTop + dp(13).toFloat())
            canvas.drawRoundRect(trackBadgeRect, dp(3).toFloat(), dp(3).toFloat(), trackBadgeBgPaint)
            canvas.drawRoundRect(trackBadgeRect, dp(3).toFloat(), dp(3).toFloat(), trackBadgeStrokePaint)
            canvas.drawText(track.type.timelineCode(), trackBadgeRect.centerX(), trackBadgeRect.centerY() + dp(3f), trackBadgeTextPaint)

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
        val clipDrawRight = contentRightLimitPx()
        if (right < headerWidthPx || left > clipDrawRight) return

        val clampedLeft = max(left, headerWidthPx.toFloat())
        val clampedRight = min(right, clipDrawRight)
        clipRect.set(clampedLeft, top + dp(2), clampedRight, bottom - dp(2))
        if (clipRect.width() <= dp(2)) return

        val color = clipColor(clip)
        clipPaint.color = color
        canvas.drawRoundRect(clipRect, dp(6).toFloat(), dp(6).toFloat(), clipPaint)

        // Draw thumbnails with HD bilinear filtering and crisp brightness
        val thumbs = clipThumbnails[clip.id]
        if (!thumbs.isNullOrEmpty() && (clip.trackType == TrackType.VIDEO || clip.trackType.isOverlayLike())) {
            val clipW = right - left
            val tileW = clipW / thumbs.size
            canvas.save()
            canvas.clipRect(clampedLeft, top + dp(2), clampedRight, bottom - dp(2))
            thumbs.forEachIndexed { i, bmp ->
                val tx = left + i * tileW
                thumbSrcRect.set(0, 0, bmp.width, bmp.height)
                thumbDstRect.set(tx, top + dp(2), tx + tileW, bottom - dp(2))
                canvas.drawBitmap(bmp, thumbSrcRect, thumbDstRect, thumbnailPaint)
            }
            canvas.restore()
        }

        // Label
        val labelX = max(clampedLeft + dp(4), left + dp(4))
        val labelWidth = clampedRight - labelX - dp(4)
        if (labelWidth > dp(20)) {
            val name = clip.sourcePath.substringAfterLast('/').ifBlank { clip.id }
            clipTextPaint.color = Color.WHITE
            canvas.save()
            canvas.clipRect(labelX, top, clampedRight - dp(2), bottom)
            canvas.drawText(name, labelX, top + ((bottom - top) * 0.62f), clipTextPaint)
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
            if (right <= clipDrawRight + 0.5f) {
                handleRect.set(clipRect.right - handleWidthPx, top + dp(2), clipRect.right, bottom - dp(2))
                canvas.drawRoundRect(handleRect, dp(4).toFloat(), dp(4).toFloat(), handlePaint)
            }
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
            TrackType.VIDEO -> "#131720"
            TrackType.OVERLAY -> "#11151D"
            TrackType.LAYER -> "#10141C"
            TrackType.TEXT -> "#10141B"
            TrackType.AUDIO -> "#0F131A"
        }
        return Color.parseColor(if (locked) "#0B0E14" else base)
    }

    private fun trackLaneStroke(trackType: TrackType): Int = Color.parseColor("#171D26")

    private fun drawTrackImportChips(canvas: Canvas) {
        tracks.forEachIndexed { i, track ->
            drawTrackActionRail(canvas, i, track)
            val chip = trackImportChipRect(i, track)
            if (chip.right < headerWidthPx || chip.left > width) return@forEachIndexed
            val isLocked = trackLocked[track.type] == true
            canvas.drawRoundRect(
                RectF(chip.left + dp(0.4f), chip.top + dp(0.6f), chip.right + dp(0.8f), chip.bottom + dp(1f)),
                trackImportChipRadiusPx(),
                trackImportChipRadiusPx(),
                trackActionChipShadowPaint,
            )
            trackAddChipPaint.color = if (isLocked) Color.parseColor("#44FFFFFF") else trackActionColor(track.type)
            trackAddChipPaint.alpha = if (isLocked) 10 else 16
            canvas.drawRoundRect(chip, trackImportChipRadiusPx(), trackImportChipRadiusPx(), trackAddChipPaint)
            trackAddChipPaint.alpha = 255
            trackAddChipStrokePaint.color = if (isLocked) Color.parseColor("#54606B") else trackActionStrokeColor(track.type)
            trackAddChipStrokePaint.alpha = if (isLocked) 38 else 78
            canvas.drawRoundRect(chip, trackImportChipRadiusPx(), trackImportChipRadiusPx(), trackAddChipStrokePaint)
            trackAddChipStrokePaint.alpha = 255
            trackAddChipTextPaint.color = if (isLocked) Color.parseColor("#8B95A1") else Color.WHITE
            trackAddChipTextPaint.alpha = if (isLocked) 86 else 220
            trackAddChipTextPaint.textSize = dp(11.5f)
            trackAddChipTextPaint.isFakeBoldText = true
            canvas.drawText("+", chip.centerX(), chip.centerY() + dp(3.5f), trackAddChipTextPaint)
            trackAddChipTextPaint.alpha = 255
        }
    }

    private fun drawTrackActionRail(canvas: Canvas, trackIndex: Int, track: TrackState) {
        val chip = trackImportChipRect(trackIndex, track)
        val top = trackTop(trackIndex).toFloat()
        val bottom = top + trackVisualHeightPx(track)
        val rail = RectF(
            chip.left - dp(3),
            top + dp(3),
            chip.right + dp(3),
            bottom - dp(3),
        )
        trackActionRailPaint.alpha = 8
        canvas.drawRoundRect(rail, dp(9).toFloat(), dp(9).toFloat(), trackActionRailPaint)
        trackActionRailPaint.alpha = 255
        trackActionRailStrokePaint.color = trackActionRailStroke(track.type)
        trackActionRailStrokePaint.alpha = 18
        canvas.drawRoundRect(rail, dp(9).toFloat(), dp(9).toFloat(), trackActionRailStrokePaint)
        trackActionRailStrokePaint.alpha = 255
    }

    private fun trackImportChipRect(trackIndex: Int, track: TrackState): RectF {
        val chipWidth = dp(28).toFloat()
        val chipHeight = min(laneHeightPx - dp(16), dp(17)).coerceAtLeast(dp(15)).toFloat()
        val top = trackTop(trackIndex).toFloat()
        val centerY = top + trackVisualHeightPx(track) / 2f
        val left = fixedTrackImportChipLeft(chipWidth)
        return RectF(left, centerY - chipHeight / 2f, left + chipWidth, centerY + chipHeight / 2f)
    }

    private fun trackImportChipRadiusPx(): Float = dp(5).toFloat()

    private fun fixedTrackImportChipLeft(chipWidth: Float = dp(28).toFloat()): Float {
        return (width - chipWidth - dp(6).toFloat())
            .coerceAtLeast(headerWidthPx + dp(6).toFloat())
    }

    private fun contentRightLimitPx(): Float {
        return (fixedTrackImportChipLeft() - dp(8).toFloat())
            .coerceAtLeast(headerWidthPx + dp(32).toFloat())
    }

    private fun trackImportChipHit(x: Float, y: Float): TrackState? {
        tracks.forEachIndexed { i, track ->
            val hitRect = RectF(trackImportChipRect(i, track)).apply {
                inset(-dp(8).toFloat(), -dp(8).toFloat())
            }
            if (hitRect.contains(x, y)) {
                return track
            }
        }
        return null
    }

    private fun trackActionColor(trackType: TrackType): Int = when (trackType) {
        TrackType.VIDEO,
        TrackType.OVERLAY,
        TrackType.LAYER,
        TrackType.TEXT,
        TrackType.AUDIO -> Color.WHITE
    }

    private fun trackActionStrokeColor(trackType: TrackType): Int = when (trackType) {
        TrackType.VIDEO,
        TrackType.OVERLAY,
        TrackType.LAYER,
        TrackType.TEXT,
        TrackType.AUDIO -> Color.parseColor("#F2F5F8")
    }

    private fun trackActionRailStroke(trackType: TrackType): Int = when (trackType) {
        TrackType.VIDEO,
        TrackType.OVERLAY,
        TrackType.LAYER,
        TrackType.TEXT,
        TrackType.AUDIO -> Color.parseColor("#2D333C")
    }

    private fun drawTransitionMarkers(canvas: Canvas) {
        tracks.firstOrNull { it.type == TrackType.VIDEO }?.let { track ->
            val sorted = track.clips.sortedBy { it.startTimeMs }
            for (i in 0 until sorted.size - 1) {
                val marker = transitionMarkerFor(track, i, sorted) ?: continue
                if (marker.rect.right < headerWidthPx || marker.rect.left > contentRightLimitPx()) continue
                drawTransitionMarker(canvas, marker)
            }
        }
    }

    private fun transitionMarkerFor(track: TrackState, sortedIndex: Int, sorted: List<ClipSegment>): TransitionMarker? {
        val outgoing = sorted.getOrNull(sortedIndex) ?: return null
        val incoming = sorted.getOrNull(sortedIndex + 1) ?: return null
        val outgoingClipId = outgoing.id.toIntOrNull() ?: return null
        val incomingClipId = incoming.id.toIntOrNull() ?: return null
        val gapMs = incoming.startTimeMs - outgoing.endTimeMs()
        if (kotlin.math.abs(gapMs) > 250L) return null

        val x = msToX(outgoing.endTimeMs())
        val trackIndex = tracks.indexOf(track).takeIf { it >= 0 } ?: return null
        val top = trackTop(trackIndex).toFloat()
        val mid = top + trackVisualHeightPx(track) / 2f
        val activeTransition =
            TransitionStore.getByOutgoingClip(outgoingClipId)
                .firstOrNull { it.incomingClipId == incomingClipId && it.type != TransitionType.NONE }
        val chipWidth = if (activeTransition == null) dp(28).toFloat() else dp(38).toFloat()
        val chipHeight = dp(22).toFloat()
        return TransitionMarker(
            outgoingClipId = outgoingClipId,
            incomingClipId = incomingClipId,
            rect = RectF(
                x - chipWidth / 2f,
                mid - chipHeight / 2f,
                x + chipWidth / 2f,
                mid + chipHeight / 2f,
            ),
        )
    }

    private fun drawTransitionMarker(canvas: Canvas, marker: TransitionMarker) {
        val transition =
            TransitionStore.getByOutgoingClip(marker.outgoingClipId)
                .firstOrNull { it.incomingClipId == marker.incomingClipId && it.type != TransitionType.NONE }
        val active = transition != null
        val rect = marker.rect
        val radius = dp(8).toFloat()
        transitionPaint.color = if (active) Color.parseColor("#FFB56B") else Color.parseColor("#EE05070A")
        transitionPaint.alpha = if (active) 255 else 248
        canvas.drawRoundRect(rect, radius, radius, transitionPaint)
        transitionPaint.alpha = 255
        transitionStrokePaint.color = if (active) Color.parseColor("#FFF4D2") else Color.parseColor("#F2F5F8")
        canvas.drawRoundRect(rect, radius, radius, transitionStrokePaint)

        val label =
            when (transition?.type) {
                TransitionType.FADE -> "FD"
                TransitionType.CROSS -> "MX"
                TransitionType.WIPE -> "WP"
                TransitionType.SLIDE -> "SL"
                else -> "+"
            }
        transitionTextPaint.color = if (active) Color.parseColor("#141414") else Color.WHITE
        canvas.drawText(label, rect.centerX(), rect.centerY() + dp(3.1f), transitionTextPaint)
    }

    private fun transitionMarkerHit(x: Float, y: Float): TransitionMarker? {
        tracks.firstOrNull { it.type == TrackType.VIDEO }?.let { track ->
            val sorted = track.clips.sortedBy { it.startTimeMs }
            for (i in 0 until sorted.size - 1) {
                val marker = transitionMarkerFor(track, i, sorted) ?: continue
                val hitRect = RectF(marker.rect)
                hitRect.inset(-dp(8).toFloat(), -dp(8).toFloat())
                if (hitRect.contains(x, y)) {
                    return marker
                }
            }
        }
        return null
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
        val callback = onClipLongPress ?: return
        vibrate()
        callback.invoke(clipId, touchDownX, touchDownY)
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
        val x = centerX()
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

        if (event.pointerCount > 1 || scaleDetector.isInProgress) {
            longPressHandler.removeCallbacks(longPressRunnable)
            gesture = GestureKind.NONE
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { scroller.abortAnimation(); onDown(event) }
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gesture == GestureKind.SCROLL) {
                    // No fling — timeline stops exactly where finger is released
                    // for precise 1:1 scrubbing without inertial drift.
                    scroller.abortAnimation()
                }
                onUp(event)
                velocityTracker.clear()
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val previousScrollX = scrollX
            scrollX = scroller.currX.toFloat().coerceIn(0f, maxScrollX())
            scrollY = 0f
            if (abs(scrollX - previousScrollX) >= 0.5f) {
                val newMs = (scrollX / pxPerMs).toLong().coerceAtLeast(0L)
                updatePlayheadWithHaptics(newMs)
                scheduleAssetRequests()
            }
            postInvalidateOnAnimation()
        }
    }

    private fun onDown(event: MotionEvent) {
        touchDownX = event.x
        touchDownY = event.y
        lastTouchX = event.x
        lastTouchY = event.y
        gestureCurrentX = event.x
        gestureCurrentY = event.y
        gestureStarted = false
        snapTimeMs = null
        gesture = GestureKind.NONE
        gestureClipId = null
        gestureClipSnapshot = null
        gestureStartScrollX = scrollX
        gestureStartScrollY = scrollY
        stopEdgeAutoScroll()

        // Double tap → reset zoom
        val now = System.currentTimeMillis()
        if (now - lastTapTimeMs < 300 && abs(event.x - lastTapX) < dp(20)) {
            pxPerSecond = 120f
            recalcContentWidth()
            invalidateAssetRequestWindow()
            listener?.onZoomChanged(pxPerSecond)
            scheduleAssetRequests()
            invalidate()
            lastTapTimeMs = 0L
            return
        }
        lastTapTimeMs = now
        lastTapX = event.x

        trackImportChipHit(event.x, event.y)?.let { track ->
            if (trackLocked[track.type] == true) {
                listener?.onTrackSelected(track.type)
                invalidate()
                return
            }
            listener?.onTrackSelected(track.type)
            listener?.onTrackImportRequested(track.type)
            invalidate()
            return
        }

        transitionMarkerHit(event.x, event.y)?.let { marker ->
            vibrate()
            listener?.onTransitionRequested(marker.outgoingClipId, marker.incomingClipId)
            invalidate()
            return
        }

        // Split button tap
        if (showPlayheadSplitButton && splitBtnRect.contains(event.x, event.y)) {
            onSplitAtPlayhead?.invoke(playheadMs)
            return
        }

        // Ruler tap → playhead scrub
        if (event.y < rulerHeightPx) {
            gesture = GestureKind.PLAYHEAD_SCRUB
            showPlayheadTooltip = true
            val ms = xToMs(event.x)
            playheadMs = ms
            scrollX = scrollForPlayhead(ms)
            listener?.onPlayheadScrub(ms)
            scheduleAssetRequests()
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

        if (onClipLongPress != null) {
            longPressClipId = clip.id
            longPressHandler.postDelayed(longPressRunnable, 500L)
        } else {
            longPressClipId = null
        }

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
        val stepY = event.y - lastTouchY
        lastTouchX = event.x
        lastTouchY = event.y
        gestureCurrentX = event.x
        gestureCurrentY = event.y

        if (!gestureStarted) {
            if (abs(dx) < touchSlop && abs(dy) < touchSlop) return
            gestureStarted = true
            longPressHandler.removeCallbacks(longPressRunnable) // cancel long press on move
        }

        when (gesture) {
            GestureKind.PLAYHEAD_SCRUB -> {
                val ms = xToMs(event.x)
                updatePlayheadWithHaptics(ms)
                scrollX = scrollForPlayhead(ms)
                showPlayheadTooltip = true
                scheduleAssetRequests(deferForInteraction = true)
                postInvalidateOnAnimation()
            }
            GestureKind.SCROLL -> {
                val previousScrollX = scrollX
                scrollX = (scrollX - stepX).coerceIn(0f, maxScrollX())
                scrollY = 0f
                if (abs(scrollX - previousScrollX) >= 0.5f) {
                    val newMs = (scrollX / pxPerMs).toLong().coerceAtLeast(0L)
                    updatePlayheadWithHaptics(newMs)
                    scheduleAssetRequests(deferForInteraction = true)
                }
                postInvalidateOnAnimation()
            }
            GestureKind.NONE -> {
                // Decide: scroll or clip gesture
                if (gestureClipId == null) {
                    gesture = GestureKind.SCROLL
                    scrollX = (scrollX - stepX).coerceIn(0f, maxScrollX())
                    scrollY = 0f
                    if (abs(stepX) >= 0.5f) scheduleAssetRequests(deferForInteraction = true)
                    postInvalidateOnAnimation()
                }
            }
            GestureKind.TRIM_START, GestureKind.TRIM_END, GestureKind.MOVE -> {
                gestureClipId?.let { queueGestureApply(it, dx, dy) }
                updateEdgeAutoScroll()
                postInvalidateOnAnimation()
            }
        }
    }

    private fun onUp(event: MotionEvent) {
        longPressHandler.removeCallbacks(longPressRunnable)
        longPressClipId = null
        showPlayheadTooltip = false
        stopEdgeAutoScroll()
        flushPendingGestureApply()
        if (gestureStarted && gesture in listOf(GestureKind.TRIM_START, GestureKind.TRIM_END, GestureKind.MOVE)) {
            gestureClipId?.let { commitGesture(it, event.x - touchDownX, event.y - touchDownY) }
        } else if (!gestureStarted && gesture == GestureKind.NONE && gestureClipId == null) {
            if (event.y < rulerHeightPx) {
                val ms = xToMs(event.x)
                updatePlayheadWithHaptics(ms)
                scrollX = scrollForPlayhead(ms)
            }
            // Tap on empty area → deselect clip without jumping playhead
            selectedClipId = null
            listener?.onClipSelected(null)
            invalidate()
        }
        snapTimeMs = null
        gesture = GestureKind.NONE
        gestureClipId = null
        gestureClipSnapshot = null
        gestureStarted = false
        scheduleAssetRequests(deferForInteraction = true)
        invalidate()
    }

    private fun updatePlayheadWithHaptics(newMs: Long) {
        val oldSec = playheadMs / 1000L
        val newSec = newMs / 1000L
        if (oldSec != newSec) {
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
        playheadMs = newMs
        listener?.onPlayheadScrub(newMs)
    }

    // ── Gesture math ──────────────────────────────────────────────────────────
    private fun effectiveGestureDeltaPx(rawDeltaPx: Float): Float {
        return rawDeltaPx + (scrollX - gestureStartScrollX)
    }

    private fun effectiveGestureDeltaYPx(rawDeltaPx: Float): Float {
        return rawDeltaPx + (scrollY - gestureStartScrollY)
    }

    private fun applyGesture(clipId: String, deltaPx: Float, deltaYPx: Float): Long? {
        val snap = gestureClipSnapshot ?: return null
        val update = buildUpdate(snap, deltaPx, deltaYPx) ?: return null
        updateClipPreview(clipId, update)
        listener?.onClipUpdatePreview(update)
        postInvalidateOnAnimation()
        return snapTimeMs
    }

    private fun queueGestureApply(clipId: String, deltaPx: Float, deltaYPx: Float) {
        pendingGestureApplyClipId = clipId
        pendingGestureApplyDeltaPx = deltaPx
        pendingGestureApplyDeltaYPx = deltaYPx
        if (gestureApplyScheduled) return
        gestureApplyScheduled = true
        postOnAnimation(gestureApplyRunnable)
    }

    private fun flushPendingGestureApply() {
        if (!gestureApplyScheduled && pendingGestureApplyClipId == null) return
        removeCallbacks(gestureApplyRunnable)
        gestureApplyScheduled = false
        val clipId = pendingGestureApplyClipId ?: return
        pendingGestureApplyClipId = null
        val snap = applyGesture(clipId, pendingGestureApplyDeltaPx, pendingGestureApplyDeltaYPx)
        snapTimeMs = snap
    }

    private fun commitGesture(clipId: String, deltaPx: Float, deltaYPx: Float) {
        val snap = gestureClipSnapshot ?: return
        val update = buildUpdate(snap, deltaPx, deltaYPx) ?: return
        listener?.onClipUpdateCommitted(update)
    }

    private data class MoveTarget(
        val track: TrackState?,
        val laneIndex: Int,
        val zOrder: Int,
    )

    private fun TrackType.allowsIndependentLanes(): Boolean {
        return false
    }

    private fun zOrderForLane(trackType: TrackType, laneIndex: Int): Int {
        return when (trackType) {
            TrackType.TEXT -> 400
            TrackType.OVERLAY -> 200
            TrackType.LAYER -> 120
            TrackType.VIDEO -> 0
            TrackType.AUDIO -> 0
        }
    }

    private fun resolveMoveTarget(clip: ClipSegment, effectiveDeltaYPx: Float): MoveTarget {
        val currentTrackIndex = tracks.indexOfFirst { it.type == clip.trackType }
            .takeIf { it >= 0 }
            ?: tracks.indexOfFirst { t -> t.clips.any { it.id == clip.id } }
        if (currentTrackIndex < 0) {
            return MoveTarget(track = null, laneIndex = 0, zOrder = zOrderForLane(clip.trackType, 0))
        }
        val currentCenterY = clipTop(currentTrackIndex, clip) + laneHeightPx / 2f
        val targetCenterY = currentCenterY + effectiveDeltaYPx
        val targetTrackIndex = tracks.indices.firstOrNull { index ->
            val top = trackTop(index)
            val bottom = top + trackVisualHeightPx(index)
            targetCenterY in top..bottom
        } ?: when {
            targetCenterY < rulerHeightPx -> 0
            else -> tracks.lastIndex
        }
        val targetTrack = tracks.getOrNull(targetTrackIndex)
        val laneIndex = targetTrack?.let { track ->
            if (!track.type.allowsIndependentLanes()) {
                0
            } else {
                val lanePitch = (laneHeightPx + trackInnerGapPx).coerceAtLeast(1)
                val localY = (targetCenterY - trackTop(targetTrackIndex)).coerceAtLeast(0f)
                val maxLane = trackLaneCount(track).coerceAtLeast(1) - 1
                (localY / lanePitch).toInt().coerceIn(0, maxLane)
            }
        } ?: 0
        return MoveTarget(
            track = targetTrack,
            laneIndex = laneIndex,
            zOrder = zOrderForLane(targetTrack?.type ?: clip.trackType, laneIndex),
        )
    }

    private fun buildUpdate(clip: ClipSegment, deltaPx: Float, deltaYPx: Float): ClipUpdate? {
        val deltaMs = (effectiveGestureDeltaPx(deltaPx) / pxPerMs).roundToLong()
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
                val newSourceIn = clip.sourceInMs + deltaStartMs
                ClipUpdate(
                    clipId = clip.id, trackType = clip.trackType,
                    startTimeMs = snapped, durationMs = newDur,
                    sourceInMs = newSourceIn, sourceOutMs = clip.sourceOutMs.coerceAtLeast(newSourceIn + newDur),
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
                val sourceDurationMs = clipSourceDurationMs(clip)
                val maxSourceDurationMs =
                    if (sourceDurationMs > clip.sourceInMs) {
                        sourceDurationMs - clip.sourceInMs
                    } else {
                        Long.MAX_VALUE
                    }
                val nextClipStart = sameTrackClips
                    .filter { it.startTimeMs >= clip.startTimeMs + clip.durationMs }
                    .minOfOrNull { it.startTimeMs } ?: Long.MAX_VALUE
                val sourceLimitedEnd =
                    if (maxSourceDurationMs == Long.MAX_VALUE) Long.MAX_VALUE else clip.startTimeMs + maxSourceDurationMs
                val maxEnd = min(
                    if (nextClipStart == Long.MAX_VALUE) Long.MAX_VALUE else nextClipStart,
                    sourceLimitedEnd,
                )
                val rawEnd = (clip.startTimeMs + clip.durationMs + deltaMs)
                    .coerceIn(clip.startTimeMs + 33L, maxEnd)
                val snapped = snapToTargets(rawEnd, allSnaps)
                    .coerceIn(clip.startTimeMs + 33L, maxEnd)
                val newDur = (snapped - clip.startTimeMs).coerceAtLeast(33L)
                ClipUpdate(
                    clipId = clip.id, trackType = clip.trackType,
                    startTimeMs = clip.startTimeMs, durationMs = newDur,
                    sourceInMs = clip.sourceInMs,
                    sourceOutMs = clip.sourceInMs + newDur,
                    originalStartTimeMs = clip.startTimeMs, originalDurationMs = clip.durationMs,
                    originalSourceInMs = clip.sourceInMs, originalSourceOutMs = clip.sourceOutMs,
                    gestureKind = ClipGestureKind.TRIM_END,
                )
            }
            GestureKind.MOVE -> {
                val allSnaps = buildSnapTargets(clip)
                val rawStart = (clip.startTimeMs + deltaMs).coerceAtLeast(0L)
                var snapped = snapToTargets(rawStart, allSnaps)
                val targetTrack = tracks.firstOrNull { track -> track.type == clip.trackType }
                    ?: tracks.firstOrNull { track -> track.clips.any { it.id == clip.id } }
                val laneIndex = clipLaneIndex(clip)
                val sameTrackClips = targetTrack?.clips
                    ?.filter { other ->
                        other.id != clip.id &&
                            (!targetTrack.type.allowsIndependentLanes() || clipLaneIndex(other) == laneIndex)
                    }
                    ?: emptyList()

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
                    targetLane = laneIndex,
                    targetZOrder = clip.zOrder,
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

    private fun updateEdgeAutoScroll() {
        if (!gestureStarted || gesture !in listOf(GestureKind.TRIM_START, GestureKind.TRIM_END, GestureKind.MOVE)) {
            stopEdgeAutoScroll()
            return
        }
        val requestedStepX = computeEdgeAutoScrollStepPx(gestureCurrentX)
        val requestedStepY = 0f
        if (kotlin.math.abs(requestedStepX) < 0.5f && kotlin.math.abs(requestedStepY) < 0.5f) {
            stopEdgeAutoScroll()
            return
        }
        if (!edgeAutoScrollRunning) {
            edgeAutoScrollRunning = true
            postOnAnimation(edgeAutoScrollRunnable)
        }
    }

    private fun stopEdgeAutoScroll() {
        edgeAutoScrollRunning = false
        removeCallbacks(edgeAutoScrollRunnable)
    }

    private fun computeEdgeAutoScrollStepPx(touchX: Float): Float {
        val zonePx = dp(48).toFloat()
        val maxStepPx = dp(22).toFloat()
        val leftBoundary = headerWidthPx + zonePx
        return when {
            touchX < leftBoundary -> {
                val proximity = ((leftBoundary - touchX) / zonePx).coerceIn(0f, 1f)
                -(maxStepPx * proximity)
            }
            touchX > width - zonePx -> {
                val proximity = ((touchX - (width - zonePx)) / zonePx).coerceIn(0f, 1f)
                maxStepPx * proximity
            }
            else -> 0f
        }
    }

    private fun computeVerticalEdgeAutoScrollStepPx(touchY: Float): Float {
        if (maxScrollY() <= 0f) return 0f
        val zonePx = dp(42).toFloat()
        val maxStepPx = dp(18).toFloat()
        val topBoundary = rulerHeightPx + zonePx
        return when {
            touchY < topBoundary -> {
                val proximity = ((topBoundary - touchY) / zonePx).coerceIn(0f, 1f)
                -(maxStepPx * proximity)
            }
            touchY > height - zonePx -> {
                val proximity = ((touchY - (height - zonePx)) / zonePx).coerceIn(0f, 1f)
                maxStepPx * proximity
            }
            else -> 0f
        }
    }

    private fun vibrate() {
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    // Update clip in tracks list for live preview
    private fun updateClipPreview(clipId: String, update: ClipUpdate) {
        var movedClip: ClipSegment? = null
        val withoutMovedClip = tracks.map { track ->
            val nextClips = track.clips.mapNotNull { clip ->
                if (clip.id == clipId) {
                    val laneIndex = update.targetLane.takeIf { it >= 0 } ?: clipLaneIndex(clip)
                    movedClip = clip.copy(
                        trackType = update.trackType,
                        startTimeMs = update.startTimeMs,
                        durationMs = update.durationMs,
                        sourceInMs = update.sourceInMs,
                        sourceOutMs = update.sourceOutMs,
                        zOrder = update.targetZOrder.takeIf { it != Int.MIN_VALUE } ?: clip.zOrder,
                        metadata = clip.metadata + ("subTrack" to (laneIndex + 1).toString()),
                    )
                    null
                } else {
                    clip
                }
            }
            track.copy(clips = nextClips)
        }
        tracks = movedClip?.let { moved ->
            withoutMovedClip.map { track ->
                if (track.type == moved.trackType) {
                    track.copy(
                        clips = (track.clips + moved)
                            .sortedWith(compareBy<ClipSegment> { it.startTimeMs }.thenBy { it.zOrder }.thenBy { it.id }),
                    )
                } else {
                    track
                }
            }
        } ?: withoutMovedClip
        clampScroll()
        recalcContentWidth()
    }

    // ── Hit test ──────────────────────────────────────────────────────────────
    private fun findClipAt(x: Float, y: Float): ClipSegment? {
        tracks.forEachIndexed { i, track ->
            track.clips.forEach { clip ->
                val top = clipTop(i, clip)
                val bottom = clipBottom(i, clip)
                if (y < top || y > bottom) return@forEach
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
        clampScroll()
        invalidateAssetRequestWindow()
        scheduleAssetRequests()
    }

    override fun onDetachedFromWindow() {
        assetRequestHandler.removeCallbacks(assetRequestRunnable)
        assetRequestScheduled = false
        stopPlaybackTicker()
        removeCallbacks(gestureApplyRunnable)
        gestureApplyScheduled = false
        pendingGestureApplyClipId = null
        stopEdgeAutoScroll()
        super.onDetachedFromWindow()
    }

    // Preferred height = ruler + all tracks
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val trackCount = layoutTrackCount
        val laneSlotCount = totalLaneSlotCount.coerceAtLeast(trackCount)
        val innerGapCount =
            if (tracks.isEmpty()) 0 else tracks.sumOf { (trackLaneCount(it) - 1).coerceAtLeast(0) }
        val preferred =
            rulerHeightPx +
                laneSlotCount * minTrackHeightBasePx +
                innerGapCount * trackInnerGapPx +
                (trackCount - 1) * trackGapPx +
                timelineBottomInsetPx
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(preferred, MeasureSpec.getSize(heightMeasureSpec))
            else -> preferred
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }
}

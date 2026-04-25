package com.video.engine.pro.timeline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.video.engine.pro.model.ClipSegment
import com.video.engine.pro.model.TrackState
import com.video.engine.pro.model.TrackType
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Optimized 4-track synchronized timeline.
 *
 * Why this shape:
 * - Only four rows exist, so each row is its own horizontal RecyclerView.
 * - A shared scroll coordinator keeps all rows pixel-perfect in sync.
 * - A shared recycled view pool avoids clip-view churn.
 * - Item animator is disabled to avoid expensive relayout during drag edits.
 * - Pinch-to-zoom only invalidates width math, not the entire screen tree.
 *
 * Playhead:
 * - Fixed in the horizontal center of the widget.
 * - Timestamp = horizontalScrollPx / pxPerMs.
 */
class MultiTrackTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    companion object {
        private const val RULER_HEIGHT_DP = 16
        private const val TRACK_ROW_HEIGHT_DP = 32
        private const val TRACK_HEADER_WIDTH_DP = 54
        private const val MIN_CLIP_WIDTH_DP = 52
        private const val PAYLOAD_SELECTION = "selection"
        private const val DEFAULT_TIMELINE_PX_PER_SECOND = 120f
    }

    data class TimelineMetrics(
        val pxPerSecond: Float,
    ) {
        val pxPerMs: Float
            get() = pxPerSecond / 1000f
    }

    enum class ClipGestureKind {
        MOVE,
        TRIM_START,
        TRIM_END,
    }

    data class ClipUpdate(
        val clipId: String,
        val trackType: TrackType,
        val startTimeMs: Long,
        val durationMs: Long,
        val sourceInMs: Long,
        val sourceOutMs: Long,
        val originalStartTimeMs: Long,
        val originalDurationMs: Long,
        val originalSourceInMs: Long,
        val originalSourceOutMs: Long,
        val gestureKind: ClipGestureKind,
    )

    interface Listener {
        fun onSeek(timeMs: Long)
        fun onClipUpdatePreview(update: ClipUpdate)
        fun onClipUpdateCommitted(update: ClipUpdate)
        fun onZoomChanged(pxPerSecond: Float)
        fun onClipSelected(clipId: String?)
        fun onTrackImportRequested(trackType: TrackType)
        fun onTrackVisibilityChanged(trackType: TrackType, isVisible: Boolean)
        fun onTrackLockedChanged(trackType: TrackType, isLocked: Boolean)
    }

    private val rowContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val rulerHeader = RulerHeaderView(context)
    private val playheadOverlay = PlayheadOverlayView(context)
    private val recycledViewPool = RecyclerView.RecycledViewPool()
    private val rowViews = linkedMapOf<TrackType, TrackRowView>()
    private val scaleDetector = ScaleGestureDetector(context, ZoomGestureListener())
    private val gestureDetector = GestureDetector(context, TimelineGestureListener())
    private val choreographer = Choreographer.getInstance()

    private var activeSnapTimeMs: Long? = null
    private val snapGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700")
        strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f, context.resources.displayMetrics)
        style = Paint.Style.STROKE
    }
    private var lastHapticSnapTimeMs: Long? = null
    private var listener: Listener? = null
    private var scrollOffsetPx = 0
    private var scrollOffsetPxFloat = 0f
    private var contentInsetPx = 0
    private var metrics = TimelineMetrics(pxPerSecond = 120f)
    private var isSyncingScroll = false
    private var lastAppliedTimeMs = Long.MIN_VALUE
    private var animatedTargetOffsetPx = 0f
    private var animationFrameScheduled = false
    private var lastAnimationFrameNs = 0L
    private var zoomGestureActive = false
    private var zoomGestureBaseMetrics = metrics
    private var zoomGestureFocusX = 0f
    private var zoomGestureAccumulatedScale = 1f
    private var zoomGestureTargetPxPerSecond = metrics.pxPerSecond
    private val timelineAnimationFrame = Choreographer.FrameCallback { frameTimeNanos ->
        animationFrameScheduled = false
        val delta = animatedTargetOffsetPx - scrollOffsetPxFloat
        if (abs(delta) <= 0.5f) {
            syncRowsTo(animatedTargetOffsetPx)
            return@FrameCallback
        }
        val frameDeltaNs =
            if (lastAnimationFrameNs > 0L) frameTimeNanos - lastAnimationFrameNs else 16_000_000L
        lastAnimationFrameNs = frameTimeNanos
        val dtSec = (frameDeltaNs / 1_000_000_000f).coerceIn(0.008f, 0.05f)
        val alpha = (1f - exp(-24f * dtSec)).coerceIn(0.16f, 0.58f)
        val nextOffset = scrollOffsetPxFloat + (delta * alpha)
        syncRowsTo(nextOffset)
        if (abs(animatedTargetOffsetPx - scrollOffsetPxFloat) > 0.5f) {
            scheduleTimelineAnimationFrame()
        }
    }

    private fun scheduleTimelineAnimationFrame() {
        if (animationFrameScheduled) return
        animationFrameScheduled = true
        choreographer.postFrameCallback(timelineAnimationFrame)
    }

    private fun cancelTimelineAnimationFrame() {
        if (!animationFrameScheduled) return
        choreographer.removeFrameCallback(timelineAnimationFrame)
        animationFrameScheduled = false
    }

    init {
        setWillNotDraw(false)
        addView(
            rowContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            playheadOverlay,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        buildRows()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // Draw snap guide line over all tracks
        val snapMs = activeSnapTimeMs ?: return
        val snapX = contentInsetPx + (snapMs * metrics.pxPerMs) - scrollOffsetPxFloat
        canvas.drawLine(snapX, 0f, snapX, height.toFloat(), snapGuidePaint)
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun submitTracks(trackStates: List<TrackState>) {
        TrackType.displayOrder().forEach { type ->
            rowViews[type]?.submitTrack(trackStates.firstOrNull { it.type == type })
            rowViews[type]?.setPlayheadTimeMs(currentTimeMs())
        }
        rulerHeader.invalidate()
        syncRowsTo(scrollOffsetPxFloat)
    }

    fun setSelectedClipId(clipId: String?) {
        rowViews.values.forEach { it.setSelectedClipId(clipId) }
    }

    fun currentTimeMs(): Long {
        if (animationFrameScheduled && lastAppliedTimeMs != Long.MIN_VALUE) {
            return lastAppliedTimeMs.coerceAtLeast(0L)
        }
        return computePlayheadTimeMs(scrollOffsetPxFloat)
    }

    fun setCurrentTimeMs(timeMs: Long) {
        if (timeMs == lastAppliedTimeMs && !animationFrameScheduled) {
            return
        }
        val targetOffsetPx = (contentInsetPx + (timeMs * metrics.pxPerMs) - playheadRecyclerX()).coerceAtLeast(0f)
        animatedTargetOffsetPx = targetOffsetPx
        if (abs(targetOffsetPx - scrollOffsetPxFloat) <= 0.75f) {
            cancelTimelineAnimationFrame()
            lastAppliedTimeMs = timeMs
            rowViews.values.forEach { it.setPlayheadTimeMs(timeMs) }
            playheadOverlay.invalidate()
            rulerHeader.invalidate()
            return
        }
        lastAppliedTimeMs = timeMs
        scheduleTimelineAnimationFrame()
    }

    fun setZoomPxPerSecond(pxPerSecond: Float) {
        endTransientZoom(commit = false)
        val next = pxPerSecond.coerceIn(48f, 3200f)
        if (kotlin.math.abs(next - metrics.pxPerSecond) < 0.5f) {
            return
        }
        val anchorTimeMs = currentTimeMs()
        metrics = TimelineMetrics(pxPerSecond = next)
        rowViews.values.forEach { it.setMetrics(metrics) }
        rulerHeader.invalidate()
        setCurrentTimeMs(anchorTimeMs)
    }

    fun revealClip(clipId: String) {
        val clip = rowViews.values
            .asSequence()
            .mapNotNull { row -> row.findClip(clipId) }
            .firstOrNull()
            ?: return
        val targetContentPx = clip.startTimeMs * metrics.pxPerMs
        cancelTimelineAnimationFrame()
        syncRowsTo((contentInsetPx + targetContentPx - playheadRecyclerX()).coerceAtLeast(0f))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1) {
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val rulerBottom = rulerHeader.y + rulerHeader.height
            val touchedRuler = event.y <= rulerBottom
            if (!touchedRuler && findClipIdUnder(event.x, event.y) == null) {
                setSelectedClipId(null)
                listener?.onClipSelected(null)
            }
        }
        return true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount > 1) {
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        scaleDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun focusClip(clipId: String, boostZoom: Boolean) {
        val clip = rowViews.values
            .asSequence()
            .mapNotNull { row -> row.findClip(clipId) }
            .firstOrNull()
            ?: return
        if (boostZoom) {
            val viewportWidth = contentViewportWidthPx().coerceAtLeast(dp(180))
            val targetPxPerSecond =
                (((viewportWidth * 0.72f) / clip.durationMs.coerceAtLeast(1L).toFloat()) * 1000f)
                    .coerceIn(metrics.pxPerSecond, 2200f)
            if (abs(targetPxPerSecond - metrics.pxPerSecond) >= 0.5f) {
                metrics = TimelineMetrics(pxPerSecond = targetPxPerSecond)
                rowViews.values.forEach { it.setMetrics(metrics) }
                listener?.onZoomChanged(targetPxPerSecond)
                rulerHeader.invalidate()
            }
        }
        val clipCenterMs = clip.startTimeMs + (clip.durationMs / 2L)
        setCurrentTimeMs(clipCenterMs)
        listener?.onSeek(clipCenterMs)
    }

    private fun toggleTimelineZoomAt(focusX: Float) {
        val nextPxPerSecond =
            when {
                metrics.pxPerSecond < 180f -> 260f
                metrics.pxPerSecond < 420f -> 560f
                else -> DEFAULT_TIMELINE_PX_PER_SECOND
            }
        commitZoom(nextPxPerSecond, focusX)
    }

    private inner class TimelineGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val clipId = findClipIdUnder(e.x, e.y)
            return if (clipId != null) {
                setSelectedClipId(clipId)
                listener?.onClipSelected(clipId)
                focusClip(clipId, boostZoom = true)
                true
            } else {
                toggleTimelineZoomAt(e.x)
                true
            }
        }
    }

    private fun findClipIdUnder(x: Float, y: Float): String? {
        rowViews.values.forEach { row ->
            val localY = y - row.y
            if (localY < 0f || localY > row.height) return@forEach
            val localX = x - row.x - row.headerWidthPx().toFloat()
            if (localX < 0f) return@forEach
            row.findClipIdUnder(localX, localY)?.let { return it }
        }
        return null
    }

    private fun settlePlayheadToTouchX(localRecyclerX: Float) {
        val clampedX = localRecyclerX.coerceIn(
            0f,
            contentViewportWidthPx().toFloat().coerceAtLeast(0f),
        )
        val targetTimeMs = ((scrollOffsetPxFloat - contentInsetPx + clampedX) / metrics.pxPerMs)
            .toLong()
            .coerceAtLeast(0L)
        listener?.onSeek(targetTimeMs)
    }

    private fun buildRows() {
        rowContainer.addView(
            rulerHeader,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(RULER_HEIGHT_DP),
            ),
        )
        TrackType.displayOrder().forEach { type ->
            val row = TrackRowView(context, type)
            row.setMetrics(metrics)
            row.setSharedPool(recycledViewPool)
            row.setOnHorizontalScroll { offset ->
                if (isSyncingScroll) {
                    return@setOnHorizontalScroll
                }
                cancelTimelineAnimationFrame()
                scrollOffsetPx = offset
                scrollOffsetPxFloat = offset.toFloat()
                syncRowsTo(offset.toFloat(), source = type)
                val timeMs = computePlayheadTimeMs(offset.toFloat())
                lastAppliedTimeMs = timeMs
                rowViews.values.forEach { it.setPlayheadTimeMs(timeMs) }
                listener?.onSeek(timeMs)
                playheadOverlay.invalidate()
            }
            row.setOnScrollRelease { localRecyclerX ->
                settlePlayheadToTouchX(localRecyclerX)
            }
            row.setOnClipUpdatePreview { update ->
                listener?.onClipUpdatePreview(update)
            }
            row.setOnClipUpdateCommitted { update ->
                listener?.onClipUpdateCommitted(update)
            }
            row.setOnClipSelected { clipId ->
                setSelectedClipId(clipId)
                listener?.onClipSelected(clipId)
            }
            row.setOnTrackVisibilityChanged { trackType, isVisible ->
                listener?.onTrackVisibilityChanged(trackType, isVisible)
            }
            row.setOnTrackImportRequested { trackType ->
                listener?.onTrackImportRequested(trackType)
            }
            row.setOnTrackLockedChanged { trackType, isLocked ->
                listener?.onTrackLockedChanged(trackType, isLocked)
            }
            rowContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            rowViews[type] = row
        }
        doOnLayout {
            updateContentInsetsIfNeeded()
            playheadOverlay.invalidate()
        }
    }

    private fun syncRowsTo(offsetPx: Float, source: TrackType? = null) {
        isSyncingScroll = true
        scrollOffsetPxFloat = offsetPx.coerceAtLeast(0f)
        scrollOffsetPx = scrollOffsetPxFloat.toInt()
        rowViews.forEach { (type, row) ->
            if (type != source) {
                row.scrollToOffset(scrollOffsetPx)
            }
        }
        val syncedTimeMs = computePlayheadTimeMs(scrollOffsetPxFloat)
        rowViews.values.forEach { it.setPlayheadTimeMs(syncedTimeMs) }
        isSyncingScroll = false
        rulerHeader.invalidate()
        playheadOverlay.invalidate()
    }

    private fun commitZoom(nextPxPerSecond: Float, focusX: Float) {
        val current = metrics.pxPerSecond
        val next = nextPxPerSecond.coerceIn(48f, 3200f)
        if (kotlin.math.abs(next - current) < 0.5f) return
        val contentLeftPx = headerWidthPx().toFloat()
        val viewportWidthPx = contentViewportWidthPx().toFloat().coerceAtLeast(1f)
        val contentFocusX = (focusX - contentLeftPx).coerceIn(0f, viewportWidthPx)
        val originInsetPx = contentInsetPx.toFloat()
        val anchorTimeMs = ((scrollOffsetPxFloat + contentFocusX - originInsetPx) / metrics.pxPerMs)
            .coerceAtLeast(0f)

        metrics = TimelineMetrics(pxPerSecond = next)
        rowViews.values.forEach { it.setMetrics(metrics) }
        listener?.onZoomChanged(next)
        rulerHeader.invalidate()
        val newAnchorContentPx = anchorTimeMs * metrics.pxPerMs
        val newOffsetPx = (newAnchorContentPx - contentFocusX + originInsetPx).coerceAtLeast(0f)
        cancelTimelineAnimationFrame()
        syncRowsTo(newOffsetPx)
        listener?.onSeek(currentTimeMs())
    }

    private fun beginTransientZoom(focusX: Float) {
        zoomGestureActive = true
        zoomGestureBaseMetrics = metrics
        zoomGestureFocusX = focusX
        zoomGestureAccumulatedScale = 1f
        zoomGestureTargetPxPerSecond = metrics.pxPerSecond
    }

    private fun updateTransientZoom(scaleFactor: Float, focusX: Float) {
        if (!zoomGestureActive) {
            beginTransientZoom(focusX)
        }
        zoomGestureFocusX = focusX
        zoomGestureAccumulatedScale = (zoomGestureAccumulatedScale * scaleFactor).coerceAtLeast(0.01f)
        val maxScale = 3200f / zoomGestureBaseMetrics.pxPerSecond
        val minScale = 48f / zoomGestureBaseMetrics.pxPerSecond
        zoomGestureAccumulatedScale = zoomGestureAccumulatedScale.coerceIn(minScale, maxScale)
        zoomGestureTargetPxPerSecond = (zoomGestureBaseMetrics.pxPerSecond * zoomGestureAccumulatedScale)
            .coerceIn(48f, 3200f)
        val visualScale = (zoomGestureTargetPxPerSecond / zoomGestureBaseMetrics.pxPerSecond).coerceIn(0.25f, 8f)
        val contentFocusX = (focusX - headerWidthPx()).coerceIn(
            0f,
            contentViewportWidthPx().toFloat().coerceAtLeast(1f),
        )
        rowViews.values.forEach { it.setTransientZoom(visualScale, contentFocusX) }
        rulerHeader.setTransientZoom(
            baseMetrics = zoomGestureBaseMetrics,
            previewPxPerSecond = zoomGestureTargetPxPerSecond,
            contentFocusX = contentFocusX,
            scrollOffsetPx = scrollOffsetPxFloat,
            contentViewportWidthPx = contentViewportWidthPx(),
            contentInsetPx = contentInsetPx,
        )
    }

    private fun endTransientZoom(commit: Boolean) {
        if (!zoomGestureActive) return
        rowViews.values.forEach { it.clearTransientZoom() }
        rulerHeader.clearTransientZoom()
        val nextPxPerSecond = zoomGestureTargetPxPerSecond
        val focusX = zoomGestureFocusX
        zoomGestureActive = false
        zoomGestureAccumulatedScale = 1f
        if (commit) {
            commitZoom(nextPxPerSecond, focusX)
        } else {
            rulerHeader.invalidate()
            playheadOverlay.invalidate()
        }
    }

    private fun computePlayheadTimeMs(scrollOffsetPx: Float): Long {
        val playheadContentPx = scrollOffsetPx - contentInsetPx + playheadRecyclerX()
        return (playheadContentPx / metrics.pxPerMs).toLong().coerceAtLeast(0L)
    }

    private fun updateContentInsetsIfNeeded() {
        val nextInsetPx = (contentViewportWidthPx() / 2f).toInt().coerceAtLeast(0)
        if (nextInsetPx == contentInsetPx) return
        contentInsetPx = nextInsetPx
        rowViews.values.forEach { it.setContentInset(contentInsetPx) }
        syncRowsTo(scrollOffsetPxFloat)
    }

    private fun contentViewportWidthPx(): Int {
        return rowViews.values.firstOrNull()?.contentViewportWidthPx() ?: width
    }

    private fun playheadRecyclerX(): Float {
        val x = (width / 2f) - headerWidthPx().toFloat()
        return x.coerceIn(0f, contentViewportWidthPx().toFloat().coerceAtLeast(0f))
    }

    private fun headerWidthPx(): Int = dp(TRACK_HEADER_WIDTH_DP)

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }

    private inner class ZoomGestureListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            beginTransientZoom(detector.focusX)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            updateTransientZoom(detector.scaleFactor, detector.focusX)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            endTransientZoom(commit = true)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) {
            updateContentInsetsIfNeeded()
        }
    }

    override fun onDetachedFromWindow() {
        cancelTimelineAnimationFrame()
        endTransientZoom(commit = false)
        super.onDetachedFromWindow()
    }

    private inner class PlayheadOverlayView(context: Context) : View(context) {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = dp(2).toFloat()
        }
        private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF4A1F")
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val centerX = (width / 2f).coerceAtLeast(0f)
            canvas.drawRect(centerX - dp(6), 0f, centerX + dp(6), dp(12).toFloat(), headPaint)
            canvas.drawLine(centerX, 0f, centerX, height.toFloat(), linePaint)
        }
    }

    private inner class RulerHeaderView(context: Context) : View(context) {
        private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#A0FFFFFF")
            strokeWidth = dp(1).toFloat()
        }
        private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#55FFFFFF")
            strokeWidth = 1f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D7E4F2")
            textSize = dp(10).toFloat()
        }
        private var transientBaseMetrics: TimelineMetrics? = null
        private var transientPreviewPxPerSecond = 0f
        private var transientContentFocusX = 0f
        private var transientScrollOffsetPx = 0f
        private var transientViewportWidthPx = 0
        private var transientContentInsetPx = 0

        fun setTransientZoom(
            baseMetrics: TimelineMetrics,
            previewPxPerSecond: Float,
            contentFocusX: Float,
            scrollOffsetPx: Float,
            contentViewportWidthPx: Int,
            contentInsetPx: Int,
        ) {
            transientBaseMetrics = baseMetrics
            transientPreviewPxPerSecond = previewPxPerSecond
            transientContentFocusX = contentFocusX
            transientScrollOffsetPx = scrollOffsetPx
            transientViewportWidthPx = contentViewportWidthPx
            transientContentInsetPx = contentInsetPx
            invalidate()
        }

        fun clearTransientZoom() {
            transientBaseMetrics = null
            transientPreviewPxPerSecond = 0f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val labelWidthPx = dp(TRACK_HEADER_WIDTH_DP)
            val contentLeft = labelWidthPx.toFloat()
            val previewMetrics = transientBaseMetrics?.let { TimelineMetrics(transientPreviewPxPerSecond) } ?: metrics
            val viewportWidth = transientBaseMetrics
                ?.let { transientViewportWidthPx.coerceAtLeast(1) }
                ?: (width - labelWidthPx).coerceAtLeast(1)
            val visibleStartMs: Long
            val visibleEndMs: Long
            val xForTimeMs: (Long) -> Float
            if (transientBaseMetrics != null) {
                val baseMetrics = transientBaseMetrics ?: metrics
                val originInsetPx = transientContentInsetPx.toFloat()
                val anchorTimeMs = ((transientScrollOffsetPx + transientContentFocusX - originInsetPx) / baseMetrics.pxPerMs)
                    .coerceAtLeast(0f)
                visibleStartMs = (anchorTimeMs - (transientContentFocusX / previewMetrics.pxPerMs)).toLong().coerceAtLeast(0L)
                visibleEndMs = (anchorTimeMs + ((viewportWidth - transientContentFocusX) / previewMetrics.pxPerMs))
                    .toLong()
                    .coerceAtLeast(visibleStartMs) + 1000L
                xForTimeMs = { tickMs ->
                    contentLeft + transientContentFocusX + ((tickMs - anchorTimeMs) * previewMetrics.pxPerMs)
                }
            } else {
                val contentStartPx = scrollOffsetPxFloat - contentInsetPx
                visibleStartMs = (contentStartPx / previewMetrics.pxPerMs).toLong().coerceAtLeast(0L)
                visibleEndMs = ((contentStartPx + viewportWidth) / previewMetrics.pxPerMs).toLong()
                    .coerceAtLeast(visibleStartMs) + 1000L
                val originInsetPx = contentInsetPx.toFloat()
                xForTimeMs = { tickMs ->
                    contentLeft + originInsetPx + ((tickMs * previewMetrics.pxPerMs) - scrollOffsetPxFloat)
                }
            }
            val majorStepMs = chooseMajorTickMs(previewMetrics.pxPerSecond)
            val minorStepMs = majorStepMs / 2
            var tickMs = (visibleStartMs / minorStepMs) * minorStepMs
            while (tickMs <= visibleEndMs) {
                val x = xForTimeMs(tickMs)
                if (x >= contentLeft && x <= width) {
                    val isMajor = tickMs % majorStepMs == 0L
                    val tickBottom = if (isMajor) height.toFloat() else height * 0.72f
                    canvas.drawLine(x, dp(10).toFloat(), x, tickBottom, if (isMajor) majorTickPaint else minorTickPaint)
                    if (isMajor) {
                        canvas.drawText(formatTickLabel(tickMs), x + dp(4), dp(9).toFloat(), textPaint)
                    }
                }
                tickMs += minorStepMs
            }
        }

        private fun chooseMajorTickMs(pxPerSecond: Float): Long {
            return when {
                pxPerSecond >= 2600f -> 10L
                pxPerSecond >= 2000f -> 20L
                pxPerSecond >= 1600f -> 25L
                pxPerSecond >= 1200f -> 40L
                pxPerSecond >= 900f -> 50L
                pxPerSecond >= 700f -> 100L
                pxPerSecond >= 480f -> 125L
                pxPerSecond >= 360f -> 250L
                pxPerSecond >= 220f -> 500L
                pxPerSecond >= 120f -> 1000L
                pxPerSecond >= 72f -> 2000L
                else -> 5000L
            }
        }

        private fun formatTickLabel(timeMs: Long): String {
            val totalSeconds = timeMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%02d:%02d", minutes, seconds)
        }
    }

    private class TrackRowView(
        context: Context,
        private val trackType: TrackType,
    ) : LinearLayout(context) {

        private val labelView = TextView(context).apply {
            setTextColor(Color.parseColor("#C8D8E6"))
            gravity = Gravity.CENTER_VERTICAL
            textSize = 8f
            text = when (trackType) {
                TrackType.VIDEO -> "VID"
                TrackType.OVERLAY -> "OVR"
                TrackType.LAYER -> "LYR"
                TrackType.TEXT -> "TXT"
                TrackType.AUDIO -> "AUD"
            }
            setPadding(dp(4), 0, 0, 0)
            maxLines = 1
        }
        private val visibilityToggle = ImageView(context).apply {
            setImageResource(android.R.drawable.presence_online)
            setColorFilter(Color.parseColor("#9EF0C2"))
            visibility = View.GONE
        }
        private val lockToggle = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_lock_lock)
            setColorFilter(Color.parseColor("#8B94A5"))
            visibility = View.GONE
        }
        private val importButton = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_input_add)
            setColorFilter(Color.WHITE)
            contentDescription = "Import ${trackType.name.lowercase()}"
            alpha = 0.98f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2E86C1"))
                setStroke(dp(1), Color.parseColor("#8DD4FF"))
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        private val headerContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        private val recyclerView = RecyclerView(context)
        private val layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        private val adapter = ClipLaneAdapter()

        private var metrics = TimelineMetrics(pxPerSecond = 120f)
        private var onHorizontalScroll: ((Int) -> Unit)? = null
        private var onScrollRelease: ((Float) -> Unit)? = null
        private var onClipUpdatePreview: ((ClipUpdate) -> Unit)? = null
        private var onClipUpdateCommitted: ((ClipUpdate) -> Unit)? = null
        private var onClipSelected: ((String?) -> Unit)? = null
        private var onTrackImportRequested: ((TrackType) -> Unit)? = null
        private var onTrackVisibilityChanged: ((TrackType, Boolean) -> Unit)? = null
        private var onTrackLockedChanged: ((TrackType, Boolean) -> Unit)? = null
        private var trackVisible = true
        private var trackLocked = false
        private var playheadTimeMs = 0L
        private var transientZoomActive = false

        init {
            orientation = HORIZONTAL
            setBackgroundColor(Color.parseColor("#171717"))
            minimumHeight = dp(TRACK_ROW_HEIGHT_DP)

            recyclerView.layoutManager = layoutManager
            recyclerView.adapter = adapter
            recyclerView.setHasFixedSize(true)
            recyclerView.itemAnimator = null
            recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
            recyclerView.isNestedScrollingEnabled = false
            recyclerView.setItemViewCacheSize(8)
            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dx != 0) {
                        onHorizontalScroll?.invoke(recyclerView.computeHorizontalScrollOffset())
                    }
                }
            })
            val rowTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
            var downX = 0f
            var downY = 0f
            var didScroll = false
            recyclerView.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        didScroll = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(event.x - downX) > rowTouchSlop) didScroll = true
                    }
                    MotionEvent.ACTION_UP -> {
                        val movedX = abs(event.x - downX)
                        val movedY = abs(event.y - downY)
                        val moved = movedX > rowTouchSlop || movedY > rowTouchSlop
                        val tappedClip = recyclerView.findChildViewUnder(event.x, event.y)
                        // Only deselect on intentional tap on empty area (no scroll happened)
                        if (!moved && !didScroll && tappedClip == null) {
                            onClipSelected?.invoke(null)
                            return@setOnTouchListener true
                        }
                    }
                }
                false
            }

            headerContainer.addView(importButton, LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(3) })
            headerContainer.addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            importButton.setOnClickListener {
                onTrackImportRequested?.invoke(trackType)
            }
            visibilityToggle.setOnClickListener {
                trackVisible = !trackVisible
                updateHeaderState()
                onTrackVisibilityChanged?.invoke(trackType, trackVisible)
            }

            addView(headerContainer, LayoutParams(dp(TRACK_HEADER_WIDTH_DP), LayoutParams.MATCH_PARENT))
            addView(recyclerView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            updateHeaderState()
        }

        fun setSharedPool(pool: RecyclerView.RecycledViewPool) {
            recyclerView.setRecycledViewPool(pool)
        }

        fun setMetrics(metrics: TimelineMetrics) {
            this.metrics = metrics
            adapter.metrics = metrics
            val dispatchMetricsChange = {
                if (adapter.itemCount > 0) {
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                }
            }
            if (recyclerView.isComputingLayout) {
                recyclerView.post { dispatchMetricsChange() }
            } else {
                dispatchMetricsChange()
            }
        }

        fun setOnHorizontalScroll(listener: (Int) -> Unit) {
            onHorizontalScroll = listener
        }

        fun setOnScrollRelease(listener: (Float) -> Unit) {
            onScrollRelease = listener
            adapter.onTimelineScrollRelease = listener
        }

        fun setOnClipUpdatePreview(listener: (ClipUpdate) -> Unit) {
            onClipUpdatePreview = listener
            adapter.onClipUpdatePreview = listener
        }

        fun setOnClipUpdateCommitted(listener: (ClipUpdate) -> Unit) {
            onClipUpdateCommitted = listener
            adapter.onClipUpdateCommitted = listener
        }

        fun setOnClipSelected(listener: (String?) -> Unit) {
            onClipSelected = listener
            adapter.onClipSelected = { clipId -> listener(clipId) }
        }

        fun setOnTrackVisibilityChanged(listener: (TrackType, Boolean) -> Unit) {
            onTrackVisibilityChanged = listener
        }

        fun setOnTrackImportRequested(listener: (TrackType) -> Unit) {
            onTrackImportRequested = listener
        }

        fun setOnTrackLockedChanged(listener: (TrackType, Boolean) -> Unit) {
            onTrackLockedChanged = listener
        }

        fun submitTrack(track: TrackState?) {
            trackVisible = track?.isVisible ?: true
            trackLocked = track?.isLocked ?: false
            updateHeaderState()
            val nextClips = track?.clips.orEmpty().toList()
            if (recyclerView.isComputingLayout) {
                recyclerView.post {
                    adapter.submit(nextClips)
                }
            } else {
                adapter.submit(nextClips)
            }
        }

        fun setPlayheadTimeMs(timeMs: Long) {
            playheadTimeMs = timeMs
        }

        fun setSelectedClipId(clipId: String?) {
            val previous = adapter.selectedClipId
            if (previous == clipId) return
            val previousIndex = adapter.findPosition(previous)
            val nextIndex = adapter.findPosition(clipId)
            adapter.selectedClipId = clipId
            val notifySelection: () -> Unit = {
                if (previousIndex >= 0) {
                    adapter.notifyItemChanged(previousIndex, PAYLOAD_SELECTION)
                }
                if (nextIndex >= 0 && nextIndex != previousIndex) {
                    adapter.notifyItemChanged(nextIndex, PAYLOAD_SELECTION)
                }
            }
            if (recyclerView.isComputingLayout) {
                recyclerView.post { notifySelection() }
            } else {
                notifySelection()
            }
        }

        fun findClip(clipId: String): ClipSegment? = adapter.findClip(clipId)

        fun scrollToOffset(offsetPx: Int) {
            val current = recyclerView.computeHorizontalScrollOffset()
            val delta = offsetPx - current
            if (delta != 0) {
                recyclerView.scrollBy(delta, 0)
            }
        }

        fun contentViewportWidthPx(): Int = recyclerView.width

        fun headerWidthPx(): Int = dp(TRACK_HEADER_WIDTH_DP)

        fun findClipIdUnder(localRecyclerX: Float, localRecyclerY: Float): String? {
            val child = recyclerView.findChildViewUnder(localRecyclerX, localRecyclerY) ?: return null
            return (recyclerView.getChildViewHolder(child) as? ClipViewHolder)?.clipId
        }

        fun setContentInset(insetPx: Int) {
            if (recyclerView.paddingLeft == insetPx && recyclerView.paddingRight == insetPx) {
                return
            }
            val keepOffsetPx = recyclerView.computeHorizontalScrollOffset()
            recyclerView.setPadding(insetPx, recyclerView.paddingTop, insetPx, recyclerView.paddingBottom)
            recyclerView.clipToPadding = false
            recyclerView.post { scrollToOffset(keepOffsetPx) }
        }

        fun setTransientZoom(scale: Float, contentPivotX: Float) {
            if (!transientZoomActive) {
                recyclerView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                transientZoomActive = true
            }
            recyclerView.pivotX = contentPivotX
            recyclerView.scaleX = scale
        }

        fun clearTransientZoom() {
            if (!transientZoomActive && kotlin.math.abs(recyclerView.scaleX - 1f) < 0.001f) {
                return
            }
            transientZoomActive = false
            recyclerView.scaleX = 1f
            recyclerView.setLayerType(View.LAYER_TYPE_NONE, null)
        }

        private fun updateHeaderState() {
            alpha = if (trackVisible) 1f else 0.45f
            labelView.setTextColor(Color.WHITE)
            adapter.isTrackLocked = trackLocked
        }

        private fun dp(value: Int): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                resources.displayMetrics,
            ).toInt()
        }

        private inner class ClipLaneAdapter : RecyclerView.Adapter<ClipViewHolder>() {
            private val clips = mutableListOf<ClipSegment>()
            var metrics: TimelineMetrics = TimelineMetrics(120f)
            var onClipUpdatePreview: ((ClipUpdate) -> Unit)? = null
            var onClipUpdateCommitted: ((ClipUpdate) -> Unit)? = null
            var onClipSelected: ((String) -> Unit)? = null
            var onInsertRequested: ((String?, String?) -> Unit)? = null
            var onTimelineScrollRelease: ((Float) -> Unit)? = null
            var selectedClipId: String? = null
            var isTrackLocked: Boolean = false

            init {
                setHasStableIds(true)
            }

            fun submit(newClips: List<ClipSegment>) {
                val sortedClips = newClips.sortedBy { it.startTimeMs }
                val diff = DiffUtil.calculateDiff(
                    object : DiffUtil.Callback() {
                        override fun getOldListSize(): Int = clips.size

                        override fun getNewListSize(): Int = sortedClips.size

                        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                            return clips[oldItemPosition].id == sortedClips[newItemPosition].id
                        }

                        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                            return clips[oldItemPosition] == sortedClips[newItemPosition]
                        }
                    },
                )
                clips.clear()
                clips.addAll(sortedClips)
                if (selectedClipId != null && clips.none { it.id == selectedClipId }) {
                    selectedClipId = null
                }
                diff.dispatchUpdatesTo(this)
            }

            fun findClip(clipId: String): ClipSegment? = clips.firstOrNull { it.id == clipId }

            fun findPosition(clipId: String?): Int {
                if (clipId == null) return -1
                return clips.indexOfFirst { it.id == clipId }
            }

            override fun getItemId(position: Int): Long {
                return clips[position].id.toLongOrNull() ?: clips[position].id.hashCode().toLong()
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClipViewHolder {
                return ClipViewHolder(ClipItemView(parent.context))
            }

            override fun getItemCount(): Int = clips.size

            override fun onBindViewHolder(holder: ClipViewHolder, position: Int, payloads: MutableList<Any>) {
                if (payloads.contains(PAYLOAD_SELECTION)) {
                    holder.updateSelection(clips[position].id == selectedClipId)
                    return
                }
                onBindViewHolder(holder, position)
            }

            override fun onBindViewHolder(holder: ClipViewHolder, position: Int) {
                val clip = clips[position]
                val previousClip = clips.getOrNull(position - 1)
                val nextClip = clips.getOrNull(position + 1)
                val gapBeforeMs = if (previousClip == null) {
                    clip.startTimeMs
                } else {
                    (clip.startTimeMs - (previousClip.startTimeMs + previousClip.durationMs)).coerceAtLeast(0L)
                }
                holder.bind(
                    clip = clip,
                    allClips = clips,
                    metrics = metrics,
                    playheadTimeMs = playheadTimeMs,
                    onClipUpdatePreview = onClipUpdatePreview,
                    onClipUpdateCommitted = onClipUpdateCommitted,
                    onClipSelected = onClipSelected,
                    onInsertRequested = onInsertRequested,
                    onTimelineScrollRelease = onTimelineScrollRelease,
                    isSelected = clip.id == selectedClipId,
                    isTrackLocked = isTrackLocked,
                    gapBeforeMs = gapBeforeMs,
                    previousClipId = previousClip?.id,
                    nextClipId = nextClip?.id,
                )
            }
        }

        private class ClipViewHolder(
            private val item: ClipItemView,
        ) : RecyclerView.ViewHolder(item) {
            var clipId: String? = null

            fun bind(
                clip: ClipSegment,
                allClips: List<ClipSegment>,
                metrics: TimelineMetrics,
                playheadTimeMs: Long,
                onClipUpdatePreview: ((ClipUpdate) -> Unit)?,
                onClipUpdateCommitted: ((ClipUpdate) -> Unit)?,
                onClipSelected: ((String) -> Unit)?,
                onInsertRequested: ((String?, String?) -> Unit)?,
                onTimelineScrollRelease: ((Float) -> Unit)?,
                isSelected: Boolean,
                isTrackLocked: Boolean,
                gapBeforeMs: Long,
                previousClipId: String?,
                nextClipId: String?,
            ) {
                clipId = clip.id
                item.bind(
                    clip,
                    allClips,
                    metrics,
                    playheadTimeMs,
                    onClipUpdatePreview,
                    onClipUpdateCommitted,
                    onClipSelected,
                    onInsertRequested,
                    onTimelineScrollRelease,
                    isSelected,
                    isTrackLocked,
                    gapBeforeMs,
                    previousClipId,
                    nextClipId,
                )
            }

            fun updateSelection(isSelected: Boolean) {
                item.updateSelectionState(isSelected)
            }
        }
    }

    private class ClipItemView(context: Context) : FrameLayout(context) {
        companion object {
            private const val MAX_PEAK_CACHE_ENTRIES = 256
            private val peakCache = LinkedHashMap<String, IntArray>(MAX_PEAK_CACHE_ENTRIES, 0.75f, true)

            private fun decodePeakLevels(metadata: Map<String, String>): IntArray {
                val raw = metadata["peakLevels"].orEmpty()
                if (raw.isBlank()) return IntArray(0)
                val cacheKey = metadata["peakMapPath"].orEmpty() + "#" + raw.hashCode()
                synchronized(peakCache) {
                    peakCache[cacheKey]?.let { return it }
                }
                val parsed = raw
                    .split(',')
                    .asSequence()
                    .mapNotNull { token -> token.trim().toIntOrNull() }
                    .map { it.coerceIn(0, 255) }
                    .toList()
                    .toIntArray()
                synchronized(peakCache) {
                    peakCache[cacheKey] = parsed
                    while (peakCache.size > MAX_PEAK_CACHE_ENTRIES) {
                        val oldest = peakCache.entries.iterator()
                        if (oldest.hasNext()) {
                            oldest.next()
                            oldest.remove()
                        }
                    }
                }
                return parsed
            }
        }

        private val titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 9f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpPx(10), 0, dpPx(34), 0)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        private val leftInsertButton = TextView(context).apply {
            text = "+"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundResource(android.R.drawable.btn_default)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
            setPadding(dpPx(2), dpPx(2), dpPx(2), dpPx(2))
            visibility = View.GONE
        }
        private val rightInsertButton = TextView(context).apply {
            text = "+"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundResource(android.R.drawable.btn_default)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
            setPadding(dpPx(2), dpPx(2), dpPx(2), dpPx(2))
            visibility = View.GONE
        }
        private val clipFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clipTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22000000")
        }
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpPx(2).toFloat()
            color = Color.WHITE
        }
        private val audioWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#55FFFFFF")
            strokeWidth = dpPx(1).toFloat()
        }
        private val audioBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B3261E")
        }
        private val audioBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dpPx(8).toFloat()
            isFakeBoldText = true
        }
        private val trimHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6FFFFFF")
        }
        private val selectionRect = RectF()
        private val clipRect = RectF()
        private val badgeRect = RectF()
        private val leftHandleRect = RectF()
        private val rightHandleRect = RectF()
        private val thumbnailDestRect = RectF()
        private val roundedClipPath = Path()
        private var selected = false
        private var boundClip: ClipSegment? = null
        private var boundMetrics = TimelineMetrics(pxPerSecond = 120f)
        private var boundPlayheadTimeMs = 0L
        private var originalWidthPx = 0
        private var activePreviewUpdate: ClipUpdate? = null
        private var boundAudioPeaks: IntArray = IntArray(0)
        private var boundVideoThumbnails: List<android.graphics.Bitmap> = emptyList()
        private var thumbnailRequestKey: String? = null

        init {
            isClickable = true
            isFocusable = true
            setWillNotDraw(false)
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                titleView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
            addView(
                leftInsertButton,
                LayoutParams(dpPx(24), dpPx(24), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                    marginStart = dpPx(2)
                },
            )
            addView(
                rightInsertButton,
                LayoutParams(dpPx(24), dpPx(24), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                    marginEnd = dpPx(2)
                },
            )
        }

        fun bind(
            clip: ClipSegment,
            allClips: List<ClipSegment>,
            metrics: TimelineMetrics,
            playheadTimeMs: Long,
            onClipUpdatePreview: ((ClipUpdate) -> Unit)?,
            onClipUpdateCommitted: ((ClipUpdate) -> Unit)?,
            onClipSelected: ((String) -> Unit)?,
            onInsertRequested: ((String?, String?) -> Unit)?,
            onTimelineScrollRelease: ((Float) -> Unit)?,
            isSelected: Boolean,
            isTrackLocked: Boolean,
            gapBeforeMs: Long,
            previousClipId: String?,
            nextClipId: String?,
        ) {
            boundClip = clip
            boundMetrics = metrics
            boundPlayheadTimeMs = playheadTimeMs
            boundAudioPeaks = if (clip.trackType == TrackType.AUDIO) {
                decodePeakLevels(clip.metadata)
            } else {
                IntArray(0)
            }
            titleView.text = "${clipLabel(clip)} • ${formatDuration(clip.durationMs)}"
            updateSelectionState(isSelected)
            val widthPx = max((clip.durationMs * metrics.pxPerMs).toInt(), dpPx(MIN_CLIP_WIDTH_DP))
            originalWidthPx = widthPx
            val leftMarginPx = (gapBeforeMs * metrics.pxPerMs).toInt()
            val rightMarginPx = dpPx(1)
            val nextParams = (layoutParams as? RecyclerView.LayoutParams)
                ?: RecyclerView.LayoutParams(widthPx, LayoutParams.MATCH_PARENT)
            var needsLayout = false
            if (nextParams.width != widthPx) {
                nextParams.width = widthPx
                needsLayout = true
            }
            if (nextParams.leftMargin != leftMarginPx) {
                nextParams.leftMargin = leftMarginPx
                needsLayout = true
            }
            if (nextParams.rightMargin != rightMarginPx) {
                nextParams.rightMargin = rightMarginPx
                needsLayout = true
            }
            if (layoutParams !== nextParams) {
                layoutParams = nextParams
            } else if (needsLayout) {
                requestLayout()
            }
            if (kotlin.math.abs(translationX) > 0.5f) {
                translationX = 0f
            }
            if (kotlin.math.abs(scaleX - 1f) > 0.001f) {
                scaleX = 1f
            }
            clipFillPaint.color = trackColor(clip.trackType)
            alpha = if (isTrackLocked) 0.55f else 1f
            contentDescription = "${clip.trackType.name}:${clipLabel(clip)}"
            maybeRequestVideoThumbnails(clip)

            leftInsertButton.visibility = if (previousClipId == null) View.VISIBLE else View.GONE
            rightInsertButton.visibility = View.VISIBLE
            leftInsertButton.setOnClickListener {
                onInsertRequested?.invoke(previousClipId, clip.id)
            }
            rightInsertButton.setOnClickListener {
                onInsertRequested?.invoke(clip.id, nextClipId)
            }
            leftInsertButton.isEnabled = !isTrackLocked
            rightInsertButton.isEnabled = !isTrackLocked

            var gestureStarted = false
            var timelineScrollStarted = false
            var downX = 0f
            var downY = 0f
            var downRawX = 0f
            var downRawY = 0f
            var lastRawX = 0f
            var lastPreviewKey = ""
            val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
            val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
            // Keep trim hit-zone narrow so casual timeline scroll does not become trim.
            val handleZonePx = minOf(dpPx(28).toFloat(), width * 0.4f)
            val previewWidthMinPx = dpPx(MIN_CLIP_WIDTH_DP)
            var activeGestureKind: ClipGestureKind? = null
            var pendingTrimKind: ClipGestureKind? = null
            var moveLongPressArmed = false
            val snapTargetsMs = buildSnapTargets(clip, allClips, playheadTimeMs)
            val moveArmRunnable = Runnable {
                if (!isTrackLocked && isSelected && pendingTrimKind == null) {
                    moveLongPressArmed = true
                    activeGestureKind = ClipGestureKind.MOVE
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onClipSelected?.invoke(clip.id)
                    alpha = 0.92f
                }
            }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        removeCallbacks(moveArmRunnable)
                        restorePreviewState()
                        downX = event.x
                        downY = event.y
                        downRawX = event.rawX
                        downRawY = event.rawY
                        lastRawX = event.rawX
                        gestureStarted = false
                        timelineScrollStarted = false
                        moveLongPressArmed = false
                        lastPreviewKey = ""
                        activePreviewUpdate = null
                        activeGestureKind = null
                        pendingTrimKind = when {
                            isSelected && !isTrackLocked && event.x <= handleZonePx -> ClipGestureKind.TRIM_START
                            isSelected && !isTrackLocked && width > handleZonePx && event.x >= width - handleZonePx -> ClipGestureKind.TRIM_END
                            else -> null
                        }
                        if (pendingTrimKind != null) {
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        if (isSelected && !isTrackLocked && pendingTrimKind == null) {
                            postDelayed(moveArmRunnable, longPressTimeoutMs)
                        }
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaPx = event.rawX - downRawX
                        val deltaYPx = event.rawY - downRawY
                        val stepPx = event.rawX - lastRawX
                        lastRawX = event.rawX

                        if (activeGestureKind == null) {
                            val movedEnough = kotlin.math.abs(deltaPx) >= touchSlop || kotlin.math.abs(deltaYPx) >= touchSlop
                            if (!movedEnough && !moveLongPressArmed) {
                                return@setOnTouchListener true
                            }

                            if (pendingTrimKind != null &&
                                kotlin.math.abs(deltaPx) >= touchSlop &&
                                kotlin.math.abs(deltaPx) >= kotlin.math.abs(deltaYPx)
                            ) {
                                removeCallbacks(moveArmRunnable)
                                activeGestureKind = pendingTrimKind
                                // Reset downRawX to current position to avoid jump on gesture start
                                downRawX = event.rawX
                                parent?.requestDisallowInterceptTouchEvent(true)
                                onClipSelected?.invoke(clip.id)
                                alpha = 0.92f
                            } else if (pendingTrimKind != null &&
                                kotlin.math.abs(deltaYPx) > kotlin.math.abs(deltaPx)
                            ) {
                                // Vertical drag — release intercept so parent can scroll
                                pendingTrimKind = null
                                parent?.requestDisallowInterceptTouchEvent(false)
                            } else if (moveLongPressArmed) {
                                activeGestureKind = ClipGestureKind.MOVE
                            } else {
                                removeCallbacks(moveArmRunnable)
                                val recyclerView = findParentRecyclerView()
                                if (recyclerView != null && kotlin.math.abs(stepPx) > 0f) {
                                    timelineScrollStarted = true
                                    recyclerView.scrollBy((-stepPx).toInt(), 0)
                                }
                                return@setOnTouchListener true
                            }
                        }

                        if (!gestureStarted && kotlin.math.abs(deltaPx) < touchSlop && kotlin.math.abs(deltaYPx) < touchSlop) {
                            return@setOnTouchListener true
                        }
                        gestureStarted = true
                        val previewUpdate = when (activeGestureKind ?: ClipGestureKind.MOVE) {
                            ClipGestureKind.MOVE -> buildMoveUpdate(clip, deltaPx, snapTargetsMs)
                            ClipGestureKind.TRIM_START -> buildTrimStartUpdate(clip, deltaPx, snapTargetsMs)
                            ClipGestureKind.TRIM_END -> buildTrimEndUpdate(clip, deltaPx, snapTargetsMs)
                        }
                        applyPreviewUpdate(clip, previewUpdate, previewWidthMinPx)
                        val previewKey = "${previewUpdate.startTimeMs}:${previewUpdate.durationMs}:${previewUpdate.sourceInMs}:${previewUpdate.sourceOutMs}"
                        if (previewKey != lastPreviewKey) {
                            onClipUpdatePreview?.invoke(previewUpdate)
                            lastPreviewKey = previewKey
                        }
                        activePreviewUpdate = previewUpdate
                        alpha = 0.92f
                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                    -> {
                        removeCallbacks(moveArmRunnable)
                        val committedUpdate = activePreviewUpdate
                        val shouldCommit = event.actionMasked != MotionEvent.ACTION_CANCEL &&
                            gestureStarted &&
                            committedUpdate != null &&
                            hasMeaningfulChange(clip, committedUpdate)
                        if (!shouldCommit) {
                            restorePreviewState()
                        }
                        alpha = 1f
                        if (!shouldCommit &&
                            timelineScrollStarted &&
                            event.actionMasked != MotionEvent.ACTION_CANCEL
                        ) {
                            scrollReleaseRecyclerX(event.rawX)?.let { localRecyclerX ->
                                onTimelineScrollRelease?.invoke(localRecyclerX)
                            }
                        }
                        if (shouldCommit && committedUpdate != null) {
                            onClipUpdateCommitted?.invoke(committedUpdate)
                            onClipSelected?.invoke(clip.id)
                        } else if (!timelineScrollStarted &&
                            event.actionMasked != MotionEvent.ACTION_CANCEL &&
                            kotlin.math.abs(event.x - downX) < touchSlop &&
                            kotlin.math.abs(event.y - downY) < touchSlop
                        ) {
                            performClick()
                        }
                        gestureStarted = false
                        timelineScrollStarted = false
                        moveLongPressArmed = false
                        pendingTrimKind = null
                        activeGestureKind = null
                        activePreviewUpdate = null
                        parent?.requestDisallowInterceptTouchEvent(false)
                        // Clear snap guide on release
                        findOuterTimeline()?.let {
                            it.activeSnapTimeMs = null
                            it.lastHapticSnapTimeMs = null
                            it.invalidate()
                        }
                        true
                    }

                    else -> false
                }
            }
            setOnClickListener {
                onClipSelected?.invoke(clip.id)
            }
            invalidate()
        }

        fun updateSelectionState(isSelected: Boolean) {
            if (selected == isSelected) return
            selected = isSelected
            invalidate()
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private fun findParentRecyclerView(): RecyclerView? {
            var current = parent
            while (current != null) {
                if (current is RecyclerView) {
                    return current
                }
                current = current.parent
            }
            return null
        }

        private fun scrollReleaseRecyclerX(rawX: Float): Float? {
            val recyclerView = findParentRecyclerView() ?: return null
            val location = IntArray(2)
            recyclerView.getLocationOnScreen(location)
            return (rawX - location[0]).coerceIn(0f, recyclerView.width.toFloat().coerceAtLeast(0f))
        }

        private fun buildMoveUpdate(
            clip: ClipSegment,
            deltaPx: Float,
            snapTargetsMs: List<Long>,
        ): ClipUpdate {
            val rawStartTimeMs = (clip.startTimeMs + pxToMs(deltaPx, sensitivity = 1.5f)).coerceAtLeast(0L)
            val snappedStartTimeMs = snapToTargets(
                timeMs = rawStartTimeMs,
                snapTargetsMs = snapTargetsMs,
                thresholdDp = 4,
                applyGrid = false,
            )
            return ClipUpdate(
                clipId = clip.id,
                trackType = clip.trackType,
                startTimeMs = snappedStartTimeMs,
                durationMs = clip.durationMs,
                sourceInMs = clip.sourceInMs,
                sourceOutMs = clip.sourceOutMs,
                originalStartTimeMs = clip.startTimeMs,
                originalDurationMs = clip.durationMs,
                originalSourceInMs = clip.sourceInMs,
                originalSourceOutMs = clip.sourceOutMs,
                gestureKind = ClipGestureKind.MOVE,
            )
        }

        private fun buildTrimStartUpdate(
            clip: ClipSegment,
            deltaPx: Float,
            snapTargetsMs: List<Long>,
        ): ClipUpdate {
            val rawStartTimeMs = (clip.startTimeMs + pxToMs(deltaPx, sensitivity = 2.4f)).coerceIn(
                0L,
                clip.startTimeMs + clip.durationMs - 1L,
            )
            val maxStartTimeMs = clip.startTimeMs + clip.durationMs - 1L
            val snappedStartTimeMs = snapToTargets(
                timeMs = rawStartTimeMs,
                snapTargetsMs = snapTargetsMs,
                thresholdDp = 3,
                applyGrid = false,
            )
                .coerceIn(0L, maxStartTimeMs)
            val deltaStartMs = snappedStartTimeMs - clip.startTimeMs
            val newDurationMs = (clip.durationMs - deltaStartMs).coerceAtLeast(1L)
            return ClipUpdate(
                clipId = clip.id,
                trackType = clip.trackType,
                startTimeMs = snappedStartTimeMs,
                durationMs = newDurationMs,
                sourceInMs = clip.sourceInMs + deltaStartMs,
                sourceOutMs = clip.sourceOutMs,
                originalStartTimeMs = clip.startTimeMs,
                originalDurationMs = clip.durationMs,
                originalSourceInMs = clip.sourceInMs,
                originalSourceOutMs = clip.sourceOutMs,
                gestureKind = ClipGestureKind.TRIM_START,
            )
        }

        private fun buildTrimEndUpdate(
            clip: ClipSegment,
            deltaPx: Float,
            snapTargetsMs: List<Long>,
        ): ClipUpdate {
            val clipEndTimeMs = clip.endTimeMs()
            val rawEndTimeMs = (clipEndTimeMs + pxToMs(deltaPx, sensitivity = 2.4f)).coerceAtLeast(clip.startTimeMs + 1L)
            val snappedEndTimeMs = snapToTargets(
                timeMs = rawEndTimeMs,
                snapTargetsMs = snapTargetsMs,
                thresholdDp = 3,
                applyGrid = false,
            )
                .coerceAtLeast(clip.startTimeMs + 1L)
            val newDurationMs = (snappedEndTimeMs - clip.startTimeMs).coerceAtLeast(1L)
            return ClipUpdate(
                clipId = clip.id,
                trackType = clip.trackType,
                startTimeMs = clip.startTimeMs,
                durationMs = newDurationMs,
                sourceInMs = clip.sourceInMs,
                sourceOutMs = clip.sourceInMs + newDurationMs,
                originalStartTimeMs = clip.startTimeMs,
                originalDurationMs = clip.durationMs,
                originalSourceInMs = clip.sourceInMs,
                originalSourceOutMs = clip.sourceOutMs,
                gestureKind = ClipGestureKind.TRIM_END,
            )
        }

        private fun applyPreviewUpdate(
            clip: ClipSegment,
            update: ClipUpdate,
            previewWidthMinPx: Int,
        ) {
            val widthPx = max((update.durationMs * boundMetrics.pxPerMs).toInt(), previewWidthMinPx)
            val deltaStartPx = (update.startTimeMs - clip.startTimeMs) * boundMetrics.pxPerMs
            when (update.gestureKind) {
                ClipGestureKind.MOVE -> {
                    translationX = deltaStartPx
                    updatePreviewWidth(originalWidthPx)
                }
                ClipGestureKind.TRIM_START -> {
                    translationX = deltaStartPx
                    updatePreviewWidth(widthPx)
                }
                ClipGestureKind.TRIM_END -> {
                    translationX = 0f
                    updatePreviewWidth(widthPx)
                }
            }
        }

        private fun updatePreviewWidth(widthPx: Int) {
            val params = layoutParams as? RecyclerView.LayoutParams ?: return
            if (params.width != widthPx) {
                params.width = widthPx
                layoutParams = params
            }
        }

        private fun restorePreviewState() {
            if (kotlin.math.abs(translationX) > 0.5f) {
                animate().translationX(0f).setDuration(90L).start()
            } else {
                translationX = 0f
            }
            updatePreviewWidth(originalWidthPx)
        }

        private fun buildSnapTargets(
            clip: ClipSegment,
            allClips: List<ClipSegment>,
            playheadTimeMs: Long,
        ): List<Long> {
            val targets = mutableSetOf<Long>()
            targets += playheadTimeMs
            allClips
                .filter { it.id != clip.id }
                .forEach { other ->
                    targets += other.startTimeMs
                    targets += other.endTimeMs()
                }
            return targets.toList()
        }

        private fun snapToTargets(
            timeMs: Long,
            snapTargetsMs: List<Long>,
            thresholdDp: Int = 10,
            applyGrid: Boolean = true,
        ): Long {
            val thresholdMs = max(2L, kotlin.math.abs(pxToMs(dpPx(thresholdDp).toFloat(), sensitivity = 1f)))
            val nearestTarget = snapTargetsMs.minByOrNull { kotlin.math.abs(it - timeMs) }
            return if (nearestTarget != null && kotlin.math.abs(nearestTarget - timeMs) <= thresholdMs) {
                // Snap happened — show guide line and haptic
                val outer = findOuterTimeline()
                if (outer?.activeSnapTimeMs != nearestTarget) {
                    outer?.activeSnapTimeMs = nearestTarget
                    outer?.invalidate()
                    if (outer?.lastHapticSnapTimeMs != nearestTarget) {
                        outer?.lastHapticSnapTimeMs = nearestTarget
                        outer?.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                nearestTarget
            } else {
                if (applyGrid) snapToGrid(timeMs) else timeMs
            }
        }

        private fun findOuterTimeline(): MultiTrackTimelineView? {
            var v: android.view.ViewParent? = parent
            while (v != null) {
                if (v is MultiTrackTimelineView) return v
                v = v.parent
            }
            return null
        }

        private fun pxToMs(deltaPx: Float, sensitivity: Float = 1f): Long {
            return ((deltaPx * sensitivity) / boundMetrics.pxPerMs).toLong()
        }

        private fun hasMeaningfulChange(clip: ClipSegment, update: ClipUpdate): Boolean {
            return clip.startTimeMs != update.startTimeMs ||
                clip.durationMs != update.durationMs ||
                clip.sourceInMs != update.sourceInMs ||
                clip.sourceOutMs != update.sourceOutMs
        }

        private fun trackColor(trackType: TrackType): Int {
            return when (trackType) {
                TrackType.VIDEO -> Color.parseColor("#3E5BFF")
                TrackType.OVERLAY -> Color.parseColor("#1FA774")
                TrackType.LAYER -> Color.parseColor("#2A8FA0")
                TrackType.TEXT -> Color.parseColor("#A14DFF")
                TrackType.AUDIO -> {
                    val clip = boundClip
                    when {
                        clip?.metadata?.containsKey("mirrorsVideoClipId") == true -> Color.parseColor("#A85A24")
                        clip?.isMuted == true -> Color.parseColor("#7A4B2B")
                        else -> Color.parseColor("#FF7A1A")
                    }
                }
            }
        }

        private fun clipLabel(clip: ClipSegment): String {
            val lanePrefix = clip.metadata["subTrack"]?.takeIf { it.isNotBlank() }?.let { "L$it " } ?: ""
            return when (clip.trackType) {
                TrackType.AUDIO -> {
                    val displayName = clip.metadata["displayName"]
                        ?.substringBeforeLast('.')
                        ?.replace('_', ' ')
                        ?.trim()
                    when {
                        !displayName.isNullOrBlank() -> lanePrefix + displayName
                        clip.metadata.containsKey("mirrorsVideoClipId") -> lanePrefix + "Linked audio"
                        else -> lanePrefix + clip.id
                    }
                }
                TrackType.TEXT -> lanePrefix + clip.sourcePath.ifBlank { clip.id }
                TrackType.LAYER -> lanePrefix + clip.sourcePath.substringAfterLast('/').ifBlank { clip.id }
                TrackType.OVERLAY -> lanePrefix + clip.sourcePath.substringAfterLast('/').ifBlank { clip.id }
                TrackType.VIDEO -> lanePrefix + clip.sourcePath.substringAfterLast('/').ifBlank { clip.id }
            }
        }

        private fun snapToGrid(timeMs: Long): Long {
            val gridMs = when {
                boundMetrics.pxPerSecond >= 2600f -> 5L
                boundMetrics.pxPerSecond >= 2000f -> 10L
                boundMetrics.pxPerSecond >= 1600f -> 20L
                boundMetrics.pxPerSecond >= 1200f -> 25L
                boundMetrics.pxPerSecond >= 900f -> 40L
                boundMetrics.pxPerSecond >= 700f -> 50L
                boundMetrics.pxPerSecond >= 480f -> 75L
                else -> 100L
            }
            return ((timeMs + (gridMs / 2)) / gridMs) * gridMs
        }

        override fun dispatchDraw(canvas: Canvas) {
            drawClipSurface(canvas)
            super.dispatchDraw(canvas)
            drawAudioDecorations(canvas)
            drawTrimHandles(canvas)
            if (!selected) return
            selectionRect.set(1f, 1f, width - 1f, height - 1f)
            canvas.drawRoundRect(selectionRect, dpPx(8).toFloat(), dpPx(8).toFloat(), strokePaint)
        }

        private fun drawClipSurface(canvas: Canvas) {
            clipRect.set(dpPx(4).toFloat(), 0f, (width - dpPx(4)).toFloat(), height.toFloat())
            roundedClipPath.reset()
            roundedClipPath.addRoundRect(clipRect, dpPx(8).toFloat(), dpPx(8).toFloat(), Path.Direction.CW)
            val saveCount = canvas.save()
            canvas.clipPath(roundedClipPath)
            canvas.drawRoundRect(clipRect, dpPx(8).toFloat(), dpPx(8).toFloat(), clipFillPaint)
            drawVideoThumbnails(canvas)
            canvas.drawRect(clipRect, clipTintPaint)
            canvas.restoreToCount(saveCount)
        }

        private fun drawVideoThumbnails(canvas: Canvas) {
            val clip = boundClip ?: return
            if (clip.trackType != TrackType.VIDEO && !clip.trackType.isOverlayLike()) return
            if (boundVideoThumbnails.isEmpty()) return
            val tileCount = boundVideoThumbnails.size
            val tileWidth = (width.toFloat() / tileCount.coerceAtLeast(1)).coerceAtLeast(dpPx(20).toFloat())
            var left = 0f
            boundVideoThumbnails.forEachIndexed { index, bitmap ->
                val right = if (index == tileCount - 1) {
                    width.toFloat()
                } else {
                    (left + tileWidth).coerceAtMost(width.toFloat())
                }
                thumbnailDestRect.set(left, 0f, right, height.toFloat())
                canvas.drawBitmap(bitmap, null, thumbnailDestRect, bitmapPaint)
                left = right
                if (left >= width) {
                    return
                }
            }
        }

        private fun maybeRequestVideoThumbnails(clip: ClipSegment) {
            if (clip.trackType != TrackType.VIDEO && !clip.trackType.isOverlayLike()) {
                boundVideoThumbnails = emptyList()
                thumbnailRequestKey = null
                return
            }
            if (clip.sourcePath.isBlank()) {
                boundVideoThumbnails = emptyList()
                thumbnailRequestKey = null
                return
            }
            val viewportWidthPx = findParentRecyclerView()?.width?.coerceAtLeast(1) ?: width.coerceAtLeast(dpPx(72))
            val targetHeightPx = height.takeIf { it > 0 } ?: dpPx(TRACK_ROW_HEIGHT_DP - 6)
            val requestKey = TimelineThumbnailCache.buildRequestKey(clip, viewportWidthPx, targetHeightPx)
            if (thumbnailRequestKey == requestKey && boundVideoThumbnails.isNotEmpty()) {
                return
            }
            thumbnailRequestKey = requestKey
            TimelineThumbnailCache.requestStrip(
                clip = clip,
                viewportWidthPx = viewportWidthPx,
                targetHeightPx = targetHeightPx,
            ) { key, thumbnails ->
                if (thumbnailRequestKey != key) return@requestStrip
                if (boundClip?.id != clip.id) return@requestStrip
                boundVideoThumbnails = thumbnails
                postInvalidateOnAnimation()
            }
        }

        private fun drawTrimHandles(canvas: Canvas) {
            if (!selected) return
            val handleWidth = dpPx(12).toFloat()
            val handleInset = dpPx(2).toFloat()
            leftHandleRect.set(handleInset, handleInset, handleInset + handleWidth, height - handleInset)
            rightHandleRect.set(width - handleInset - handleWidth, handleInset, width - handleInset, height - handleInset)
            canvas.drawRoundRect(leftHandleRect, dpPx(4).toFloat(), dpPx(4).toFloat(), trimHandlePaint)
            canvas.drawRoundRect(rightHandleRect, dpPx(4).toFloat(), dpPx(4).toFloat(), trimHandlePaint)
        }

        private fun drawAudioDecorations(canvas: Canvas) {
            // Audio decorations are temporarily disabled while the timeline UI is being polished.
            // This keeps the current build working without changing the visible toolbar/timeline layout.
        }

        private fun formatDuration(ms: Long): String {
            val s = ms / 1000
            return if (s >= 60) "%d:%02d".format(s / 60, s % 60) else "${s}s"
        }

        private fun dpPx(value: Int): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                resources.displayMetrics,
            ).toInt()
        }
    }
}

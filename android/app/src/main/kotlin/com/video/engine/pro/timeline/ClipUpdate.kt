package com.video.engine.pro.timeline

import com.video.engine.pro.model.TrackType

enum class ClipGestureKind { MOVE, TRIM_START, TRIM_END }

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
    val targetLane: Int = -1,
    val targetZOrder: Int = Int.MIN_VALUE,
)

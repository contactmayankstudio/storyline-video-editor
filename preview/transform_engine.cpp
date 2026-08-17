#include "preview/transform_engine.h"

namespace VideoEngine {

float TransformEngine::normalizeAngleDeg(float value) {
    float wrapped = std::fmod(value, 360.0f);
    if (wrapped > 180.0f) wrapped -= 360.0f;
    if (wrapped < -180.0f) wrapped += 360.0f;
    return wrapped;
}

float TransformEngine::shortestAngleDeltaDeg(float fromDeg, float toDeg) {
    return normalizeAngleDeg(toDeg - fromDeg);
}

std::array<float, 2> TransformEngine::rotateVector(float x, float y, float angleDeg) {
    constexpr float kPi = 3.14159265358979323846f;
    const float radians = angleDeg * (kPi / 180.0f);
    const float cosValue = std::cos(radians);
    const float sinValue = std::sin(radians);
    return {
        (x * cosValue) - (y * sinValue),
        (x * sinValue) + (y * cosValue),
    };
}

TransformState TransformEngine::normalize(const TransformState& transform, const Bounds& bounds) const {
    TransformState normalized = transform;
    normalized.zoom = std::clamp(normalized.zoom, bounds.minScale, bounds.maxScale);
    normalized.scaleX = std::clamp(normalized.scaleX, 0.15f, 8.0f);
    normalized.scaleY = std::clamp(normalized.scaleY, 0.15f, 8.0f);
    normalized.rotationDeg = normalizeAngleDeg(normalized.rotationDeg);

    const float viewportSpan =
        std::max(1.0f, std::max(bounds.viewportWidthPx, bounds.viewportHeightPx));
    const float zoomFactor =
        std::max(1.0f, normalized.zoom * std::max(normalized.scaleX, normalized.scaleY));
    const float panLimitMultiplier = bounds.objectTransform
        ? (1.65f + (zoomFactor * 1.85f))
        : (0.95f + (zoomFactor * 1.20f));
    const float maxPanPx = viewportSpan * panLimitMultiplier;
    normalized.panXPx = std::clamp(normalized.panXPx, -maxPanPx, maxPanPx);
    normalized.panYPx = std::clamp(normalized.panYPx, -maxPanPx, maxPanPx);

    const float centerSnapPx = bounds.objectTransform
        ? (normalized.zoom < 0.45f ? 0.04f :
            normalized.zoom < 1.10f ? 0.08f : 0.16f)
        : (normalized.zoom < 1.10f ? 0.75f : 0.35f);
    if (std::fabs(normalized.panXPx) < centerSnapPx) {
        normalized.panXPx = 0.0f;
    }
    if (std::fabs(normalized.panYPx) < centerSnapPx) {
        normalized.panYPx = 0.0f;
    }
    if (std::fabs(normalized.rotationDeg) < 0.15f) {
        normalized.rotationDeg = 0.0f;
    }
    return normalized;
}

void TransformEngine::setPersistedTransform(int clipId, const TransformState& transform) {
    if (clipId <= 0) {
        return;
    }
    m_persistedTransforms[clipId] = transform;
}

TransformState TransformEngine::getPersistedTransform(int clipId) const {
    const auto it = m_persistedTransforms.find(clipId);
    if (it == m_persistedTransforms.end()) {
        return {};
    }
    return it->second;
}

bool TransformEngine::hasPersistedTransform(int clipId) const {
    return m_persistedTransforms.find(clipId) != m_persistedTransforms.end();
}

void TransformEngine::clearPersistedTransform(int clipId) {
    m_persistedTransforms.erase(clipId);
}

void TransformEngine::clearPersistedTransforms() {
    m_persistedTransforms.clear();
}

TransformState TransformEngine::beginSession(const GestureBeginRequest& request) {
    m_session = {};
    if (request.selectedId <= 0) {
        return {};
    }
    m_session.active = true;
    m_session.selectedId = request.selectedId;
    m_session.mode = request.mode;
    m_session.bounds = request.bounds;
    m_session.baseTransform = normalize(request.baseTransform, request.bounds);
    m_session.outputTransform = m_session.baseTransform;
    m_session.baseCentroidXPx = request.centroidOffsetXPx;
    m_session.baseCentroidYPx = request.centroidOffsetYPx;
    m_session.baseSpanPx = std::max(0.01f, request.spanPx);
    m_session.baseAngleDeg = request.angleDeg;
    m_session.edgeSignX = request.edgeSignX;
    m_session.edgeSignY = request.edgeSignY;
    m_session.allowRotation = request.allowRotation;
    m_session.filteredCentroidXPx = request.centroidOffsetXPx;
    m_session.filteredCentroidYPx = request.centroidOffsetYPx;
    m_session.filteredSpanPx = std::max(0.01f, request.spanPx);
    m_session.filteredAngleDeg = request.angleDeg;
    m_session.lastSampleAt = std::chrono::steady_clock::now();
    return m_session.baseTransform;
}

TransformState TransformEngine::updateSession(const GestureUpdateRequest& request) {
    if (!m_session.active || m_session.selectedId <= 0) {
        return {};
    }

    TransformState target = m_session.baseTransform;
    const float viewportSpan =
        std::max(1.0f, std::max(m_session.bounds.viewportWidthPx, m_session.bounds.viewportHeightPx));
    const auto now = std::chrono::steady_clock::now();
    const float dtMs = std::max(
        1.0f,
        static_cast<float>(
            std::chrono::duration_cast<std::chrono::milliseconds>(now - m_session.lastSampleAt).count()));
    const float centroidSpeedPxPerMs =
        std::hypot(
            request.centroidOffsetXPx - m_session.filteredCentroidXPx,
            request.centroidOffsetYPx - m_session.filteredCentroidYPx) / dtMs;
    const float frameAlpha =
        m_session.mode == GestureMode::Drag
            ? 1.0f
            : std::clamp(0.20f + (centroidSpeedPxPerMs * 0.028f), 0.18f, 0.72f);
    m_session.filteredCentroidXPx +=
        (request.centroidOffsetXPx - m_session.filteredCentroidXPx) * frameAlpha;
    m_session.filteredCentroidYPx +=
        (request.centroidOffsetYPx - m_session.filteredCentroidYPx) * frameAlpha;
    m_session.filteredSpanPx +=
        (std::max(0.01f, request.spanPx) - m_session.filteredSpanPx) *
        std::clamp(frameAlpha * 0.92f, 0.16f, 0.66f);
    const float filteredAngleDelta =
        shortestAngleDeltaDeg(m_session.filteredAngleDeg, request.angleDeg);
    m_session.filteredAngleDeg =
        normalizeAngleDeg(m_session.filteredAngleDeg + (filteredAngleDelta * std::clamp(frameAlpha, 0.14f, 0.58f)));
    m_session.lastSampleAt = now;

    const float deltaX = m_session.filteredCentroidXPx - m_session.baseCentroidXPx;
    const float deltaY = m_session.filteredCentroidYPx - m_session.baseCentroidYPx;

    switch (m_session.mode) {
        case GestureMode::Drag: {
            const float rawDeltaX = request.centroidOffsetXPx - m_session.baseCentroidXPx;
            const float rawDeltaY = request.centroidOffsetYPx - m_session.baseCentroidYPx;
            target.panXPx = m_session.baseTransform.panXPx + rawDeltaX;
            target.panYPx = m_session.baseTransform.panYPx + rawDeltaY;
            break;
        }
        case GestureMode::PinchRotate: {
            const float rawScale =
                std::max(0.05f, m_session.filteredSpanPx) / std::max(0.05f, m_session.baseSpanPx);
            float tunedScale = rawScale;
            if (rawScale >= 1.0f) {
                tunedScale = 1.0f + ((rawScale - 1.0f) * 1.18f);
            } else {
                tunedScale = 1.0f - ((1.0f - rawScale) * 1.72f);
            }
            target.zoom = m_session.baseTransform.zoom * std::clamp(tunedScale, 0.15f, 8.0f);

            const float rotationDelta = m_session.allowRotation
                ? shortestAngleDeltaDeg(m_session.baseAngleDeg, m_session.filteredAngleDeg)
                : 0.0f;
            target.rotationDeg = normalizeAngleDeg(m_session.baseTransform.rotationDeg + rotationDelta);

            const float scaleRatio = target.zoom / std::max(0.001f, m_session.baseTransform.zoom);
            const float baseOffsetX = m_session.baseTransform.panXPx - m_session.baseCentroidXPx;
            const float baseOffsetY = m_session.baseTransform.panYPx - m_session.baseCentroidYPx;
            const auto rotatedScaled = rotateVector(baseOffsetX * scaleRatio, baseOffsetY * scaleRatio, rotationDelta);
            target.panXPx = m_session.filteredCentroidXPx + rotatedScaled[0];
            target.panYPx = m_session.filteredCentroidYPx + rotatedScaled[1];
            break;
        }
        case GestureMode::EdgeResize: {
            const bool horizontalOnly = m_session.edgeSignX != 0.0f && m_session.edgeSignY == 0.0f;
            const bool verticalOnly = m_session.edgeSignY != 0.0f && m_session.edgeSignX == 0.0f;
            if (horizontalOnly) {
                const float signedDeltaPx = deltaX * m_session.edgeSignX;
                const float normalizedDelta =
                    signedDeltaPx / std::max(1.0f, viewportSpan * 0.46f);
                const float resizeGain = normalizedDelta >= 0.0f ? 1.72f : 1.44f;
                const float scaleAccumulator = 1.0f + (normalizedDelta * resizeGain);
                target.scaleX =
                    m_session.baseTransform.scaleX * std::clamp(scaleAccumulator, 0.15f, 8.0f);
                target.panXPx = m_session.baseTransform.panXPx + (deltaX * 0.50f);
                target.panYPx = m_session.baseTransform.panYPx;
            } else if (verticalOnly) {
                const float signedDeltaPx = deltaY * m_session.edgeSignY;
                const float normalizedDelta =
                    signedDeltaPx / std::max(1.0f, viewportSpan * 0.46f);
                const float resizeGain = normalizedDelta >= 0.0f ? 1.72f : 1.44f;
                const float scaleAccumulator = 1.0f + (normalizedDelta * resizeGain);
                target.scaleY =
                    m_session.baseTransform.scaleY * std::clamp(scaleAccumulator, 0.15f, 8.0f);
                target.panXPx = m_session.baseTransform.panXPx;
                target.panYPx = m_session.baseTransform.panYPx + (deltaY * 0.50f);
            } else {
                const float signedRadialDeltaPx =
                    (deltaX * m_session.edgeSignX) + (deltaY * m_session.edgeSignY);
                const float normalizedDelta =
                    signedRadialDeltaPx / std::max(1.0f, viewportSpan * 0.42f);
                const float resizeGain = normalizedDelta >= 0.0f ? 1.86f : 1.48f;
                const float scaleAccumulator = 1.0f + (normalizedDelta * resizeGain);
                target.zoom =
                    m_session.baseTransform.zoom * std::clamp(scaleAccumulator, 0.15f, 8.0f);
                target.panXPx = m_session.baseTransform.panXPx + (deltaX * 0.50f);
                target.panYPx = m_session.baseTransform.panYPx + (deltaY * 0.50f);
            }
            break;
        }
        case GestureMode::None:
            break;
    }

    target = normalize(target, m_session.bounds);

    const float modeBaseAlpha =
        m_session.mode == GestureMode::Drag ? 0.58f :
        m_session.mode == GestureMode::PinchRotate ? 0.26f :
        m_session.mode == GestureMode::EdgeResize ? 0.24f : 0.30f;
    const float alphaBoost =
        m_session.mode == GestureMode::Drag ? 0.22f :
        m_session.mode == GestureMode::PinchRotate ? 0.10f : 0.08f;
    float transformAlpha = std::clamp(
        modeBaseAlpha + (centroidSpeedPxPerMs * alphaBoost),
        m_session.mode == GestureMode::Drag ? 0.42f : 0.22f,
        m_session.mode == GestureMode::Drag ? 0.82f : 0.38f);

    const float panJumpPx =
        std::max(std::fabs(target.panXPx - m_session.outputTransform.panXPx),
                 std::fabs(target.panYPx - m_session.outputTransform.panYPx));
    const float zoomJump =
        std::max(
            std::fabs(target.zoom - m_session.outputTransform.zoom),
            std::max(
                std::fabs(target.scaleX - m_session.outputTransform.scaleX),
                std::fabs(target.scaleY - m_session.outputTransform.scaleY)));
    const float rotationJump =
        std::fabs(shortestAngleDeltaDeg(m_session.outputTransform.rotationDeg, target.rotationDeg));
    if (panJumpPx > (viewportSpan * 0.42f) || zoomJump > 1.25f || rotationJump > 38.0f) {
        transformAlpha = 1.0f;
    }

    auto blendFloat = [](float from, float to, float alpha) -> float {
        return from + ((to - from) * alpha);
    };

    TransformState output = m_session.outputTransform;
    output.zoom = blendFloat(output.zoom, target.zoom, transformAlpha);
    output.scaleX = blendFloat(output.scaleX, target.scaleX, transformAlpha);
    output.scaleY = blendFloat(output.scaleY, target.scaleY, transformAlpha);
    output.panXPx = blendFloat(output.panXPx, target.panXPx, transformAlpha);
    output.panYPx = blendFloat(output.panYPx, target.panYPx, transformAlpha);
    output.rotationDeg =
        normalizeAngleDeg(
            output.rotationDeg +
            (shortestAngleDeltaDeg(output.rotationDeg, target.rotationDeg) * transformAlpha));
    output.mirrorX = target.mirrorX;
    output = normalize(output, m_session.bounds);
    m_session.outputTransform = output;
    return output;
}

void TransformEngine::endSession() {
    m_session = {};
}

}  // namespace VideoEngine

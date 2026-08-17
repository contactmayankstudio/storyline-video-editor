#pragma once

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <unordered_map>

namespace VideoEngine {

struct TransformState {
    float zoom = 1.0f;
    float scaleX = 1.0f;
    float scaleY = 1.0f;
    float panXPx = 0.0f;
    float panYPx = 0.0f;
    float rotationDeg = 0.0f;
    bool mirrorX = false;

    bool isIdentity() const {
        return std::fabs(zoom - 1.0f) <= 0.001f &&
            std::fabs(scaleX - 1.0f) <= 0.001f &&
            std::fabs(scaleY - 1.0f) <= 0.001f &&
            std::fabs(panXPx) <= 0.5f &&
            std::fabs(panYPx) <= 0.5f &&
            std::fabs(rotationDeg) <= 0.001f &&
            !mirrorX;
    }
};

class TransformEngine {
public:
    enum class GestureMode {
        None = 0,
        Drag = 1,
        PinchRotate = 2,
        EdgeResize = 3,
    };

    struct Bounds {
        float minScale = 0.2f;
        float maxScale = 5.0f;
        float viewportWidthPx = 1.0f;
        float viewportHeightPx = 1.0f;
        bool objectTransform = false;
    };

    struct GestureBeginRequest {
        int selectedId = 0;
        GestureMode mode = GestureMode::None;
        TransformState baseTransform;
        float centroidOffsetXPx = 0.0f;
        float centroidOffsetYPx = 0.0f;
        float spanPx = 0.0f;
        float angleDeg = 0.0f;
        float edgeSignX = 0.0f;
        float edgeSignY = 0.0f;
        bool allowRotation = false;
        Bounds bounds;
    };

    struct GestureUpdateRequest {
        float centroidOffsetXPx = 0.0f;
        float centroidOffsetYPx = 0.0f;
        float spanPx = 0.0f;
        float angleDeg = 0.0f;
    };

    TransformState normalize(const TransformState& transform, const Bounds& bounds) const;

    void setPersistedTransform(int clipId, const TransformState& transform);
    TransformState getPersistedTransform(int clipId) const;
    bool hasPersistedTransform(int clipId) const;
    void clearPersistedTransform(int clipId);
    void clearPersistedTransforms();

    TransformState beginSession(const GestureBeginRequest& request);
    TransformState updateSession(const GestureUpdateRequest& request);
    void endSession();

    bool hasActiveSession() const { return m_session.active; }
    int activeSessionClipId() const { return m_session.selectedId; }

private:
    struct GestureSession {
        bool active = false;
        int selectedId = 0;
        GestureMode mode = GestureMode::None;
        TransformState baseTransform;
        TransformState outputTransform;
        Bounds bounds;
        float baseCentroidXPx = 0.0f;
        float baseCentroidYPx = 0.0f;
        float baseSpanPx = 0.0f;
        float baseAngleDeg = 0.0f;
        float edgeSignX = 0.0f;
        float edgeSignY = 0.0f;
        bool allowRotation = false;
        float filteredCentroidXPx = 0.0f;
        float filteredCentroidYPx = 0.0f;
        float filteredSpanPx = 0.0f;
        float filteredAngleDeg = 0.0f;
        std::chrono::steady_clock::time_point lastSampleAt;
    };

    static float normalizeAngleDeg(float value);
    static float shortestAngleDeltaDeg(float fromDeg, float toDeg);
    static std::array<float, 2> rotateVector(float x, float y, float angleDeg);

    std::unordered_map<int, TransformState> m_persistedTransforms;
    GestureSession m_session;
};

}  // namespace VideoEngine

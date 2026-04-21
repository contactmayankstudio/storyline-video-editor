#pragma once

#include <atomic>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>

#include "preview/preview_controller.h"

struct ANativeWindow;

struct Transition {
    int64_t id = 0;
    int32_t outgoingClipId = 0;
    int32_t incomingClipId = 0;
    int32_t typeId = 0;
    int32_t durationMs = 0;
    int64_t startTimeMs = 0;
    bool isEnabled = true;

    float getProgress(int64_t currentTimeMs) const {
        if (!isEnabled) return -1.0f;
        int64_t elapsed = currentTimeMs - startTimeMs;
        if (elapsed < 0 || elapsed > durationMs) return -1.0f;
        return static_cast<float>(elapsed) / static_cast<float>(durationMs);
    }
};

extern std::mutex g_mutex;
extern std::unique_ptr<VideoEngine::PreviewController> g_preview;
extern std::atomic<long long> g_currentTimeMs;
extern std::atomic<bool> g_isRenderingActive;
extern std::atomic<int> g_timelineZoomMilliPxPerSecond;
extern ANativeWindow* g_nativeWindow;
extern std::map<int64_t, Transition> g_transitions;
extern int64_t g_nextTransitionId;
extern std::atomic<long long> g_pendingScrubMs;

// Releases the outer JNI EGL ownership so PreviewController can attach the same
// ANativeWindow with its internal EGLRenderer.
// Must be called while holding g_mutex.
bool releaseOuterEglForPreviewAttachLocked();

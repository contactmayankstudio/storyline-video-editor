#include "preview_controller.h"

#include "backend/ffmpeg/video_decoder.h"
#include "backend/ffmpeg/frame_converter.h"
#include "gpu/gl_texture.h"
#include "gpu/egl_renderer.h"
#include "core/timeline.h"
#include "core/clip.h"
#include "smooth_engine/AdaptiveResolution.h"
#include "smooth_engine/SpeedRamping.h"
#include "smooth_engine/SuperResolution.h"
#include "smooth_engine/TransitionEngine.h"
#include "smooth_engine/VulkanRenderer.h"
#include <iostream>
#include <cstdarg>
#include <cstring>
#include <cstdlib>
#include <chrono>
#include <thread>
#include <mutex>
#include <algorithm>
#include <cmath>
#include <limits>
#include <cerrno>
#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>
#include <unistd.h>
namespace {
void applyRealtimePreviewPriority() {
    sched_param sp{};
    const int maxPrio = sched_get_priority_max(SCHED_FIFO);
    if (maxPrio > 0) {
        sp.sched_priority = std::max(1, maxPrio - 2);
        if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &sp) == 0) {
            return;
        }
    }
    // Fallback if realtime scheduling is not permitted.
    setpriority(PRIO_PROCESS, 0, -8);
}

int64_t frameIntervalForPreviewFps(int fps) {
    return static_cast<int64_t>(1000.0 / std::max(1, fps));
}

bool fileExists(const std::string& path) {
    return !path.empty() && access(path.c_str(), F_OK) == 0;
}

bool usesObjectStylePreviewTransform(const std::shared_ptr<VideoEngine::Clip>& clip) {
    if (!clip) {
        return false;
    }
    const auto role = clip->getTrackRole();
    return role == VideoEngine::Clip::TrackRole::MainVideo ||
        role == VideoEngine::Clip::TrackRole::Overlay;
}

std::string resolvePreviewDecoderPath(const VideoEngine::Clip* clip, bool adaptiveProxyEnabled) {
    if (!adaptiveProxyEnabled) return clip ? clip->getMediaPath() : std::string();
    if (!clip) {
        return {};
    }
    const std::string& proxyPath = clip->getPreviewProxyPath();
    if (fileExists(proxyPath)) {
        return proxyPath;
    }
    return clip->getMediaPath();
}

bool decodeStillImageFrame(
    VideoEngine::Backend::VideoDecoder* decoder,
    const std::string& decoderPath,
    int previewScaleLimitPx,
    VideoEngine::Backend::DecodedFrame* decodedFrameOut,
    std::string* errorOut = nullptr) {
    if (!decoder || !decodedFrameOut) {
        if (errorOut) {
            *errorOut = "decoder unavailable";
        }
        return false;
    }

    auto decodeCurrentFrame = [&]() -> bool {
        if (!decoder->decodeNextFrame(*decodedFrameOut)) {
            return false;
        }
        if (decodedFrameOut->width == 0 || decodedFrameOut->height == 0 ||
            decodedFrameOut->rgb.empty()) {
            if (errorOut) {
                *errorOut = "decoded frame was empty";
            }
            return false;
        }
        decodedFrameOut->ptsMs = 0;
        return true;
    };

    if (decodeCurrentFrame()) {
        return true;
    }

    decoder->close();
    if (!decoder->open(decoderPath)) {
        if (errorOut) {
            *errorOut = decoder->getLastError();
        }
        return false;
    }
    decoder->setPreviewScaleLimit(previewScaleLimitPx);

    if (!decodeCurrentFrame()) {
        if (errorOut && errorOut->empty()) {
            *errorOut = decoder->getLastError();
        }
        return false;
    }

    return true;
}
}  // namespace

namespace VideoEngine {

using namespace Backend;
using namespace GPU;

PreviewController::PreviewController()
    : m_isPlaying(false)
    , m_currentTimeMs(0)
    , m_lastRenderTimeMs(0)
    , m_frameIntervalMs(33.33)  // Default ~30 FPS
    , m_playbackAnchorTimeMs(0)
    , m_playbackAnchorWallClock(std::chrono::steady_clock::now())
    , m_nativeWindow(nullptr)
    , m_surfaceWidth(0)
    , m_surfaceHeight(0)
    , m_videoWidth(0)
    , m_videoHeight(0)
    , m_videoFps(0.0)
    , m_videoDurationMs(0)
    , m_decodeRunning(false)
    , m_reachedEos(false)
    , m_maxQueuedFrames(5)
    , m_ghostPreviewEnabled(true)
    , m_ghostPreviewLongEdgePx(640)
    , m_adaptiveFrameDropEnabled(true)
    , m_targetPreviewFps(30)
    , m_minPreviewFps(15)
    , m_frameDropOverloadScore(0)
    , m_budgetPreviewFps(30)
    , m_budgetPreviewLongEdgePx(640)
    , m_budgetBypassOverlayComposition(false)
    , m_budgetPredictivePrefetchAllowed(true)
    , m_audioMasterClockWallClock(std::chrono::steady_clock::now())
    , m_lastScrubRequestWallClock(std::chrono::steady_clock::now())
{
    m_timeline = std::make_shared<Timeline>();
    m_audioSyncEngine = std::make_unique<VideoEngine::DeepPro::AudioEnginePro>();
    m_frameBudgetController = std::make_unique<VideoEngine::Performance::FrameBudgetController>();
    m_framePrefetcher = std::make_unique<VideoEngine::Performance::FramePrefetcher>();
    m_proxyManager = std::make_unique<VideoEngine::DeepPro::ProxyManager>();
    m_smartCache = std::make_unique<VideoEngine::Performance::SmartCache>();
    m_superResolution = std::make_unique<VideoEngine::AI::SuperResolution>();
    m_thermalManager = std::make_unique<VideoEngine::Android::ThermalManager>();
    m_vulkanRenderer = std::make_unique<VideoEngine::Backend::VulkanRenderer>();
    m_smartCache->startBackgroundRender();
    m_prefetchThread = std::thread(&PreviewController::predictivePrefetchLoop, this);
}

PreviewController::~PreviewController() {
    destroy();
}

bool PreviewController::open(const std::string& videoPath) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    if (m_decoder) {
        // Allow hot source switch (used by ghost/proxy preview upgrades).
        m_isPlaying.store(false);
        stopDecodeWorkerLocked();
        clearQueuedFramesLocked();
        m_decoder->close();
        m_decoder.reset();
        m_converter.reset();
        m_lastRenderTimeMs = 0;
        resetFrameBudgetLocked();
        m_hasDecodedFrame = false;
        m_lastRenderedSourceMs = -1;
        m_lastRenderedClipId = -1;
    }

    // Create decoder
    m_decoder = std::make_unique<VideoDecoder>();
    if (!m_decoder->open(videoPath)) {
        setError("Failed to open video: %s", m_decoder->getLastError());
        m_decoder = nullptr;
        return false;
    }
    m_openVideoPath = videoPath;
    clearPredictiveCacheLocked();
    m_hasLastScrubRequestSample = false;
    m_audioMasterClockValid = false;
    m_lastRenderedSourceMs = -1;
    m_lastRenderedClipId = -1;

    // Create frame converter
    m_converter = std::make_unique<FrameConverter>();

    // Cache metadata
    m_videoWidth = m_decoder->getWidth();
    m_videoHeight = m_decoder->getHeight();
    m_videoFps = m_decoder->getFps();
    m_videoDurationMs = static_cast<int64_t>(m_decoder->getDuration() * 1000.0);

    // Calculate frame interval for timing
    if (m_videoFps > 0.0) {
        m_frameIntervalMs = 1000.0 / m_videoFps;
    } else {
        m_frameIntervalMs = 33.33;  // Fallback to ~30 FPS
    }
    m_adaptiveResolution =
        std::make_unique<VideoEngine::Performance::AdaptiveResolution>(
            std::max(1, m_videoWidth),
            std::max(1, m_videoHeight));
    m_dynamicPreviewScaleLimitPx = 0;
    resetFrameBudgetLocked();
    maybeQueueProxyBuildForSourceLocked(videoPath, m_videoWidth, m_videoHeight, m_videoFps);

    if (m_renderer && m_tripleBuffer) {
        if (!m_renderer->acquireContext()) {
            setError("Render context acquire failed while switching source");
            return false;
        }
        const bool ok = ensurePrimaryTripleBufferLocked(m_videoWidth, m_videoHeight);
        m_renderer->releaseContext();
        if (!ok) {
            setError("Texture resize failed while switching source");
            return false;
        }
    }

    std::cout << "[PreviewController] Video opened: " << m_videoWidth << "x" 
              << m_videoHeight << " @ " << m_videoFps << " fps\n";

    requestPredictivePrefetchLocked(0);
    clearError();

    return true;
}

bool PreviewController::attachSurface(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    if (!window) {
        setError("ANativeWindow is null");
        return false;
    }

    m_nativeWindow = window;

    // Shutdown existing renderer before creating new one
    if (m_renderer) {
        releasePrimaryTextureLocked();
        m_renderer->shutdown();
        m_renderer = nullptr;
    }
    releasePrimaryTextureLocked();

    // Create renderer — retry on transient EGL conflicts.
    // 0x3003 happens when previous EGL surface has not been released yet.
    // 0x3002 can surface when another thread/driver path still owns the window.
    for (int attempt = 0; attempt < 5; ++attempt) {
        m_renderer = std::make_unique<EGLRenderer>();
        if (m_renderer->initialize(window)) break;
        const std::string err = m_renderer->getLastError();
        m_renderer = nullptr;
        if (err.find("0x3003") != std::string::npos ||
            err.find("0x3002") != std::string::npos) {
            // Wait for previous EGL ownership to unwind before retrying.
            std::this_thread::sleep_for(std::chrono::milliseconds(140 * (attempt + 1)));
            continue;
        }
        setError("EGL initialization failed: %s", err.c_str());
        return false;
    }
    if (!m_renderer) {
        setError("EGL initialization failed after retry");
        return false;
    }

    // Create texture with fallback size if video not loaded yet.
    // EGLRenderer::initialize() usually leaves the context current, but some
    // vendor stacks release it during window-surface bring-up. Acquire it
    // explicitly before the first TripleBuffer / GLTexture allocation.
    const bool textureContextAcquired = m_renderer->acquireContext();
    if (!textureContextAcquired) {
        setError("Render context acquire failed during surface attach");
        m_renderer->shutdown();
        m_renderer = nullptr;
        releasePrimaryTextureLocked();
        return false;
    }
    const int texW = m_videoWidth > 0 ? m_videoWidth : 1280;
    const int texH = m_videoHeight > 0 ? m_videoHeight : 720;
    if (!ensurePrimaryTripleBufferLocked(texW, texH)) {
        setError("Texture initialization failed");
        m_renderer->releaseContext();
        m_renderer->shutdown();
        m_renderer = nullptr;
        releasePrimaryTextureLocked();
        return false;
    }

    m_renderer->releaseContext();
    if (m_vulkanRenderer) {
        m_vulkanRenderer->init();
    }

    std::cout << "[PreviewController] Surface attached, texture allocated\n";
    clearError();
    return true;
}

void PreviewController::resizeSurface(int width, int height) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    if (m_renderer) {
        m_surfaceWidth = width;
        m_surfaceHeight = height;
        m_renderer->resizeViewport(width, height);
        std::cout << "[PreviewController] Surface resized to " << width << "x" << height << "\n";
    }
}

void PreviewController::detachSurface() {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_isPlaying.store(false);
    stopDecodeWorkerLocked();

    releasePrimaryTextureLocked();

    if (m_renderer) {
        m_renderer->shutdown();
        m_renderer = nullptr;
    }
    if (m_vulkanRenderer) {
        m_vulkanRenderer->shutdown();
    }

    m_nativeWindow = nullptr;
    std::cout << "[PreviewController] Surface detached\n";
}

void PreviewController::start() {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    if (m_isPlaying.load()) {
        return;
    }

    if (!m_renderer || !m_texture) {
        setError("Surface not attached");
        return;
    }
    if (!m_decoder && (m_timeline && m_timeline->clips().empty())) {
        setError("Video not loaded");
        return;
    }

    m_isPlaying.store(true);
    m_playbackAnchorTimeMs = m_currentTimeMs.load();
    m_playbackAnchorWallClock = std::chrono::steady_clock::now();
    m_reachedEos.store(false);
    resetFrameBudgetLocked();
    m_lastPlaybackPrefetchRequestMs = std::numeric_limits<int64_t>::min();
    cancelPredictivePrefetchLocked(false);
    stopDecodeWorkerLocked();
    clearQueuedFramesLocked();
    requestPredictivePrefetchLocked(m_playbackAnchorTimeMs);
    if (m_framePrefetcher && m_timeline) {
        m_framePrefetcher->start(m_timeline->clips(), m_playbackAnchorTimeMs, 1.0f);
    }
    std::cout << "[PreviewController] Playback started\n";
    clearError();
}

void PreviewController::stop() {
    m_isPlaying.store(false);
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_lastPlaybackPrefetchRequestMs = std::numeric_limits<int64_t>::min();
    cancelPredictivePrefetchLocked(false);
    stopDecodeWorkerLocked();
    clearQueuedFramesLocked();
    if (m_framePrefetcher) {
        m_framePrefetcher->stop();
    }
    if (m_dynamicPreviewScaleLimitPx != 0) {
        m_dynamicPreviewScaleLimitPx = 0;
    }
    resetFrameBudgetLocked();
    std::cout << "[PreviewController] Playback stopped\n";
}

void PreviewController::seekTo(int64_t timeMs) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    if (!m_renderer || !m_texture) {
        setError("Components not initialized");
        return;
    }
    const int64_t clampedTimeMs = clampTimelineTimeMsLocked(timeMs);
    const int64_t currentTimelineMs = m_currentTimeMs.load();
    if (!m_isPlaying.load() &&
        currentTimelineMs >= 0 &&
        std::llabs(clampedTimeMs - currentTimelineMs) <= 12 &&
        m_lastRenderedVisualStateVersion == m_visualStateVersion) {
        clearError();
        m_playbackAnchorTimeMs = currentTimelineMs;
        m_playbackAnchorWallClock = std::chrono::steady_clock::now();
        m_lastRenderTimeMs = currentTimelineMs;
        return;
    }
    m_isPlaying.store(false);
    if (m_framePrefetcher) {
        m_framePrefetcher->stop();
    }
    if (m_dynamicPreviewScaleLimitPx != 0) {
        m_dynamicPreviewScaleLimitPx = 0;
        applyPreviewScaleLimitLocked();
    }
    m_lastPlaybackPrefetchRequestMs = std::numeric_limits<int64_t>::min();
    cancelPredictivePrefetchLocked(false);
    stopDecodeWorkerLocked();
    clearQueuedFramesLocked();
    int64_t renderedTimelineMs = clampedTimeMs;
    if (!renderTimelineFrameLocked(
            clampedTimeMs,
            /*keyframeOnlyScrub=*/false,
            /*allowPredictiveCache=*/true,
            /*updatePredictiveCache=*/true,
            &renderedTimelineMs)) {
        return;
    }
    clearError();
    m_currentTimeMs.store(renderedTimelineMs);
    m_playbackAnchorTimeMs = renderedTimelineMs;
    m_playbackAnchorWallClock = std::chrono::steady_clock::now();
    m_lastRenderTimeMs = renderedTimelineMs;
}

bool PreviewController::playFrom(int64_t timeMs) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    if (!m_renderer || !m_texture) {
        setError(
            "Components not initialized (renderer=%d texture=%d converter=%d)",
            m_renderer ? 1 : 0,
            m_texture ? 1 : 0,
            m_converter ? 1 : 0);
        return false;
    }

    m_isPlaying.store(false);
    m_lastPlaybackPrefetchRequestMs = std::numeric_limits<int64_t>::min();
    cancelPredictivePrefetchLocked(false);
    stopDecodeWorkerLocked();
    clearQueuedFramesLocked();

    const int64_t clampedTimeMs = clampTimelineTimeMsLocked(timeMs);
    int64_t renderedTimelineMs = clampedTimeMs;
    const int64_t cachedTimelineMs = m_currentTimeMs.load();
    const int64_t cacheReuseToleranceMs = std::max<int64_t>(
        2,
        static_cast<int64_t>(std::llround(m_frameIntervalMs)));
    const bool canReusePausedStartFrame =
        m_hasDecodedFrame &&
        m_texture &&
        m_texture->isValid() &&
        m_lastRenderedVisualStateVersion == m_visualStateVersion &&
        std::llabs(cachedTimelineMs - clampedTimeMs) <= cacheReuseToleranceMs;
    if (canReusePausedStartFrame) {
        renderedTimelineMs = clampedTimeMs;
    } else if (!renderTimelineFrameLocked(
                   clampedTimeMs,
                   /*keyframeOnlyScrub=*/false,
                   /*allowPredictiveCache=*/true,
                   /*updatePredictiveCache=*/false,
                   &renderedTimelineMs)) {
        return false;
    }
    clearError();
    m_isPlaying.store(true);
    m_currentTimeMs.store(renderedTimelineMs);
    m_playbackAnchorTimeMs = renderedTimelineMs;
    m_playbackAnchorWallClock = std::chrono::steady_clock::now();
    m_lastRenderTimeMs = renderedTimelineMs;
    m_reachedEos.store(false);
    resetFrameBudgetLocked();
    requestPredictivePrefetchLocked(renderedTimelineMs);
    return true;
}

bool PreviewController::renderFrame() {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    if (!m_isPlaying.load()) {
        return false;
    }

    if (!m_renderer || !m_texture) {
        setError("Components not initialized");
        return false;
    }
    return processFrame();
}

bool PreviewController::processFrame() {
    const int64_t targetTimelineMs = playbackTimelineTimeMsLocked();
    if (m_framePrefetcher) {
        m_framePrefetcher->updatePlaybackTime(targetTimelineMs);
    }
    const int64_t timelineDurationMs =
        (m_timeline && m_timeline->getDuration() > 0) ? m_timeline->getDuration() : m_videoDurationMs;
    if (timelineDurationMs > 0 && targetTimelineMs >= timelineDurationMs) {
        int64_t renderedTimelineMs = timelineDurationMs;
        renderTimelineFrameLocked(
            timelineDurationMs,
            /*keyframeOnlyScrub=*/false,
            /*allowPredictiveCache=*/true,
            /*updatePredictiveCache=*/true,
            &renderedTimelineMs);
        m_currentTimeMs.store(timelineDurationMs);
        m_isPlaying.store(false);
        m_lastRenderTimeMs = timelineDurationMs;
        clearError();
        std::cout << "[PreviewController] End of timeline\n";
        return false;
    }

    int64_t renderedTimelineMs = targetTimelineMs;
    const auto renderStartedAt = std::chrono::steady_clock::now();
    const bool rendered = renderTimelineFrameLocked(
        targetTimelineMs,
        /*keyframeOnlyScrub=*/false,
        /*allowPredictiveCache=*/true,
        /*updatePredictiveCache=*/true,
        &renderedTimelineMs);
    const auto renderFinishedAt = std::chrono::steady_clock::now();
    const int64_t renderCostMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        renderFinishedAt - renderStartedAt).count();
    updateAdaptiveOverloadScoreLocked(renderCostMs);
    if (!rendered) {
        return false;
    }
    clearError();
    m_currentTimeMs.store(renderedTimelineMs);
    m_lastRenderTimeMs = renderedTimelineMs;
    return true;
}

int PreviewController::getVideoWidth() const {
    return m_videoWidth;
}

int PreviewController::getVideoHeight() const {
    return m_videoHeight;
}

double PreviewController::getVideoFps() const {
    return m_videoFps;
}

int64_t PreviewController::getVideoDurationMs() const {
    return m_videoDurationMs;
}

int64_t PreviewController::getCurrentTimeMs() const {
    return m_currentTimeMs.load();
}

int64_t PreviewController::getPlaybackTimelineTimeMs() const {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    return m_isPlaying.load() ? playbackTimelineTimeMsLocked() : m_currentTimeMs.load();
}

int64_t PreviewController::preferredRenderSleepMs() const {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    return preferredRenderSleepMsLocked();
}

void PreviewController::destroy() {
    m_isPlaying.store(false);
    stopPredictivePrefetchWorker();
    if (m_smartCache) {
        m_smartCache->stopBackgroundRender();
    }
    if (m_framePrefetcher) {
        m_framePrefetcher->stop();
    }
    {
        std::lock_guard<std::mutex> lock(m_playbackMutex);
        stopDecodeWorkerLocked();
        clearQueuedFramesLocked();
        clearPredictiveCacheLocked();

        releasePrimaryTextureLocked();

        if (m_renderer) {
            m_renderer->shutdown();
            m_renderer = nullptr;
        }
        if (m_vulkanRenderer) {
            m_vulkanRenderer->shutdown();
        }

        if (m_converter) {
            m_converter = nullptr;
        }

        if (m_decoder) {
            m_decoder->close();
            m_decoder = nullptr;
        }

        // Cleanup per-clip decoders
        for (auto& [id, state] : m_clipDecoders) {
            if (state.decoder) state.decoder->close();
            if (state.texture) state.texture->release();
        }
        m_clipDecoders.clear();
        m_transformEngine.clearPersistedTransforms();
        m_transformEngine.endSession();
        m_transitions.clear();

        m_hasDecodedFrame = false;
        m_openVideoPath.clear();
        m_nativeWindow = nullptr;
        m_hasLastScrubRequestSample = false;
        m_audioMasterClockValid = false;
        m_rgbaScratch.clear();
        m_lastRenderedSourceMs = -1;
        m_lastRenderedClipId = -1;
    }
    std::cout << "[PreviewController] Destroyed\n";
}

void PreviewController::setError(const char* fmt, ...) {
    char buffer[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    m_lastError = buffer;
}

void PreviewController::clearError() {
    m_lastError.clear();
}

bool PreviewController::ensurePrimaryTripleBufferLocked(int width, int height) {
    if (width <= 0 || height <= 0) {
        return false;
    }
    if (!m_tripleBuffer) {
        m_tripleBuffer = std::make_unique<VideoEngine::Performance::TripleBuffer>(width, height);
    }
    if (!m_tripleBuffer->isValid() || m_tripleBuffer->width() != width || m_tripleBuffer->height() != height) {
        if (!m_tripleBuffer->initialize(width, height)) {
            m_texture = nullptr;
            return false;
        }
    }
    refreshPrimaryTextureAliasLocked();
    return m_texture != nullptr && m_texture->isValid();
}

void PreviewController::refreshPrimaryTextureAliasLocked() {
    m_texture = m_tripleBuffer ? m_tripleBuffer->frontTexture() : nullptr;
}

void PreviewController::releasePrimaryTextureLocked() {
    if (!m_tripleBuffer) {
        m_texture = nullptr;
        return;
    }
    const bool acquired = m_renderer && m_renderer->acquireContext();
    m_tripleBuffer->release();
    if (acquired && m_renderer) {
        m_renderer->releaseContext();
    }
    m_tripleBuffer.reset();
    m_texture = nullptr;
}

void PreviewController::scrubToTimelineTime(int64_t timelineMs) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    if (!m_renderer || !m_texture) {
        setError("Components not initialized");
        return;
    }
    const int64_t requestedTimelineMs = clampTimelineTimeMsLocked(timelineMs);
    int64_t clampedTimelineMs = requestedTimelineMs;
    int64_t timelineDurationMs = 0;
    if (m_timeline) {
        timelineDurationMs = m_timeline->getDuration();
    }
    if (timelineDurationMs <= 0) {
        timelineDurationMs = m_videoDurationMs;
    }
    if (timelineDurationMs > 1 && clampedTimelineMs >= timelineDurationMs) {
        clampedTimelineMs = timelineDurationMs - 1;
    }
    const int64_t currentTimelineMs = m_currentTimeMs.load();
    if (!m_isPlaying.load() &&
        currentTimelineMs >= 0 &&
        std::llabs(clampedTimelineMs - currentTimelineMs) <= 12 &&
        m_lastRenderedVisualStateVersion == m_visualStateVersion) {
        clearError();
        m_playbackAnchorTimeMs = currentTimelineMs;
        m_playbackAnchorWallClock = std::chrono::steady_clock::now();
        m_lastRenderTimeMs = currentTimelineMs;
        return;
    }

    stopDecodeWorkerLocked();
    clearQueuedFramesLocked();

    m_isPlaying.store(false);
    m_reachedEos.store(false);

    const bool keyframeOnlyScrub = shouldUseKeyframeOnlyScrubLocked(clampedTimelineMs);
    int64_t renderedTimelineMs = clampedTimelineMs;
    if (!renderTimelineFrameLocked(
            clampedTimelineMs,
            keyframeOnlyScrub,
            /*allowPredictiveCache=*/true,
            /*updatePredictiveCache=*/true,
            &renderedTimelineMs)) {
        return;
    }

    clearError();
    m_currentTimeMs.store(renderedTimelineMs);
    m_playbackAnchorTimeMs = renderedTimelineMs;
    m_playbackAnchorWallClock = std::chrono::steady_clock::now();
    m_lastRenderTimeMs = renderedTimelineMs;
    resetFrameBudgetLocked();

}

bool PreviewController::shouldUseKeyframeOnlyScrubLocked(int64_t requestTimelineMs) {
    const auto now = std::chrono::steady_clock::now();
    bool useKeyframeOnly = false;
    int64_t velocityMsPerSec = 0;
    if (m_hasLastScrubRequestSample) {
        const int64_t deltaTimelineMs = std::llabs(requestTimelineMs - m_lastScrubRequestTimelineMs);
        const int64_t deltaWallMs = std::max<int64_t>(
            1,
            std::chrono::duration_cast<std::chrono::milliseconds>(
                now - m_lastScrubRequestWallClock).count());
        velocityMsPerSec = (deltaTimelineMs * 1000) / deltaWallMs;
        useKeyframeOnly =
            deltaTimelineMs >= m_keyframeScrubMinDeltaMs &&
            velocityMsPerSec >= m_keyframeScrubVelocityThresholdMsPerSec;
    }
    updateAdaptivePreviewScaleLocked(velocityMsPerSec);
    m_lastScrubRequestTimelineMs = requestTimelineMs;
    m_lastScrubRequestWallClock = now;
    m_hasLastScrubRequestSample = true;
    return useKeyframeOnly;
}

int64_t PreviewController::clampTimeMs(int64_t timeMs) const {
    if (timeMs < 0) {
        return 0;
    }
    if (m_videoDurationMs > 0 && timeMs > m_videoDurationMs) {
        return m_videoDurationMs;
    }
    return timeMs;
}

int64_t PreviewController::clampDecodableTimeMs(int64_t timeMs, int64_t durationMs) const {
    if (timeMs < 0) {
        return 0;
    }
    if (durationMs <= 1) {
        return 0;
    }
    if (timeMs >= durationMs) {
        return durationMs - 1;
    }
    return timeMs;
}

int64_t PreviewController::clampTimelineTimeMsLocked(int64_t timeMs) const {
    if (timeMs < 0) {
        return 0;
    }
    int64_t timelineDurationMs = 0;
    if (m_timeline) {
        timelineDurationMs = m_timeline->getDuration();
    }
    if (timelineDurationMs <= 0) {
        timelineDurationMs = m_videoDurationMs;
    }
    if (timelineDurationMs > 0 && timeMs > timelineDurationMs) {
        return timelineDurationMs;
    }
    return timeMs;
}

int64_t PreviewController::mapClipTimelineToSourceMs(
    const std::shared_ptr<Clip>& clip,
    int64_t timelineMs,
    bool ignoreFreeze) const {
    if (!clip) {
        return clampTimeMs(timelineMs);
    }
    if (clip->getMediaType() == Clip::MediaType::Image) {
        return 0;
    }

    const int64_t clipStart = clip->getStartTime();
    const int64_t clipDuration = std::max<int64_t>(1, clip->getDuration());
    const int64_t clipEndExclusive = clipStart + clipDuration;

    int64_t sourceInMs = 0;
    int64_t sourceOutMs = 0;
    clip->getTrimPoints(sourceInMs, sourceOutMs);
    if (sourceOutMs <= sourceInMs) {
        sourceOutMs = sourceInMs + clipDuration;
    }
    if (sourceOutMs <= sourceInMs) {
        sourceOutMs = sourceInMs + 1;
    }

    const auto& props = clip->getProperties();
    auto mapWithoutFreeze = [&](int64_t localTimelineMs) -> int64_t {
        const int64_t clampedLocalMs = std::clamp<int64_t>(localTimelineMs, 0, clipDuration - 1);
        return VideoEngine::Advanced::mapTimelineToSourceWithProfile(
            clampedLocalMs,
            clipDuration,
            sourceInMs,
            sourceOutMs,
            props.playbackSpeed,
            props.reversePlayback,
            props.curveSpeedProfile,
            props.curveSpeedStrength);
    };

    if (!ignoreFreeze && props.freezeFrameEnabled && props.freezeFrameDurationMs > 0) {
        const int64_t freezeStartMs = std::clamp<int64_t>(
            props.freezeFrameTimeMs,
            clipStart,
            std::max<int64_t>(clipStart, clipEndExclusive - 1));
        const int64_t freezeEndMs = freezeStartMs + std::max<int64_t>(100, props.freezeFrameDurationMs);
        if (timelineMs >= freezeStartMs && timelineMs < freezeEndMs) {
            const int64_t freezeLocalMs = std::clamp<int64_t>(
                freezeStartMs - clipStart,
                0,
                clipDuration - 1);
            return mapWithoutFreeze(freezeLocalMs);
        }
    }

    const int64_t localTimelineMs = std::clamp<int64_t>(
        timelineMs - clipStart,
        0,
        clipDuration - 1);
    return mapWithoutFreeze(localTimelineMs);
}

bool PreviewController::switchDecoderSourceLocked(const std::shared_ptr<Clip>& clip) {
    if (!clip) {
        setError("Clip is unavailable");
        return false;
    }
    if (clip->getMediaPath().empty()) {
        setError("Clip source path is empty");
        return false;
    }
    syncClipProxyPathLocked(clip);
    const std::string decoderPath = resolvePreviewDecoderPath(clip.get(), m_adaptiveProxyEnabled);
    if (decoderPath.empty()) {
        setError("Clip decoder path is empty");
        return false;
    }
    if (m_decoder && m_openVideoPath == decoderPath) {
        return true;
    }

    stopDecodeWorkerLocked();
    clearQueuedFramesLocked();

    if (m_decoder) {
        m_decoder->close();
        m_decoder.reset();
    }
    m_converter = std::make_unique<FrameConverter>();
    m_decoder = std::make_unique<VideoDecoder>();
    if (!m_decoder->open(decoderPath)) {
        setError("Failed to open video: %s", m_decoder->getLastError());
        m_decoder.reset();
        return false;
    }

    m_openVideoPath = decoderPath;
    clearPredictiveCacheLocked();
    m_hasLastScrubRequestSample = false;
    m_hasDecodedFrame = false;
    m_lastRenderedSourceMs = -1;
    m_lastRenderedClipId = -1;

    m_videoWidth = m_decoder->getWidth();
    m_videoHeight = m_decoder->getHeight();
    m_videoFps = m_decoder->getFps();
    m_videoDurationMs = static_cast<int64_t>(m_decoder->getDuration() * 1000.0);
    if (m_videoFps > 0.0) {
        m_frameIntervalMs = 1000.0 / m_videoFps;
    } else {
        m_frameIntervalMs = 33.33;
    }
    m_adaptiveResolution =
        std::make_unique<VideoEngine::Performance::AdaptiveResolution>(
            std::max(1, m_videoWidth),
            std::max(1, m_videoHeight));
    m_dynamicPreviewScaleLimitPx = 0;
    applyPreviewScaleLimitLocked();
    maybeQueueProxyBuildLocked(clip);

    if (m_renderer && m_tripleBuffer) {
        if (!m_renderer->acquireContext()) {
            setError("Render context acquire failed while switching source");
            return false;
        }
        const bool ok = ensurePrimaryTripleBufferLocked(m_videoWidth, m_videoHeight);
        m_renderer->releaseContext();
        if (!ok) {
            setError("Texture resize failed while switching source");
            return false;
        }
    }
    return true;
}

const PreviewController::ClipTransition* PreviewController::findActiveTransitionLocked(int64_t timelineMs) const {
    const ClipTransition* best = nullptr;
    for (const auto& [transitionId, transition] : m_transitions) {
        (void)transitionId;
        if (!transition.isActive(timelineMs)) {
            continue;
        }
        if (!findTimelineClipByIdLocked(transition.outgoingClipId) ||
            !findTimelineClipByIdLocked(transition.incomingClipId)) {
            continue;
        }
        if (!best || transition.startTimeMs > best->startTimeMs) {
            best = &transition;
        }
    }
    return best;
}

std::shared_ptr<Clip> PreviewController::findTimelineClipByIdLocked(int clipId) const {
    if (!m_timeline || clipId <= 0) {
        return nullptr;
    }
    for (const auto& clip : m_timeline->clips()) {
        if (clip && static_cast<int>(clip->getId()) == clipId) {
            return clip;
        }
    }
    return nullptr;
}

bool PreviewController::uploadClipFrameToTextureLocked(
    ClipDecodeState& state,
    const Backend::DecodedFrame& frame,
    int64_t renderedSourceMs) {
    const int frameWidth = static_cast<int>(frame.width);
    const int frameHeight = static_cast<int>(frame.height);
    if (frameWidth <= 0 || frameHeight <= 0 || frame.rgb.empty()) {
        return false;
    }

    if (!state.texture) {
        state.texture = std::make_unique<GPU::GLTexture>();
    }
    if (!state.texture->initialize(frameWidth, frameHeight)) {
        return false;
    }

    const size_t pixelCount = static_cast<size_t>(frameWidth) * frameHeight;
    const size_t expectedRgbaBytes = pixelCount * 4;
    const size_t expectedRgbBytes = pixelCount * 3;
    const uint8_t* rgbaPixels = nullptr;

    if (frame.rgb.size() >= expectedRgbaBytes) {
        rgbaPixels = frame.rgb.data();
    } else if (frame.rgb.size() >= expectedRgbBytes) {
        if (m_rgbaScratch.size() != expectedRgbaBytes) {
            m_rgbaScratch.resize(expectedRgbaBytes);
        }
        const uint8_t* src = frame.rgb.data();
        uint8_t* dst = m_rgbaScratch.data();
        for (size_t i = 0; i < pixelCount; ++i) {
            const size_t srcIndex = i * 3;
            const size_t dstIndex = i * 4;
            dst[dstIndex + 0] = src[srcIndex + 0];
            dst[dstIndex + 1] = src[srcIndex + 1];
            dst[dstIndex + 2] = src[srcIndex + 2];
            dst[dstIndex + 3] = 0xFF;
        }
        rgbaPixels = m_rgbaScratch.data();
    } else {
        return false;
    }

    state.texture->update(rgbaPixels);
    state.lastRenderedSourceMs = renderedSourceMs;
    return true;
}

GPU::EGLRenderer::Layer PreviewController::buildLayerForClipLocked(
    const std::shared_ptr<Clip>& clip,
    GPU::GLTexture* texture) const {
    GPU::EGLRenderer::Layer layer;
    layer.texture = texture;
    if (!clip) {
        return layer;
    }

    layer.opacity = clip->getProperties().opacity;
    const auto transform = clipPreviewTransformLocked(static_cast<int>(clip->getId()));
    layer.zoom = transform.zoom;
    layer.scaleX = transform.scaleX;
    layer.scaleY = transform.scaleY;
    layer.panXPx = transform.panXPx;
    layer.panYPx = transform.panYPx;
    layer.rotationDeg = transform.rotationDeg;
    layer.mirrorX = transform.mirrorX;
    layer.objectTransform = usesObjectStylePreviewTransform(clip);
    const auto& chromaKey = clip->getChromaKey();
    layer.chromaEnabled = chromaKey.enabled;
    layer.blueKey = (chromaKey.color == Clip::ChromaKeyParams::KeyColor::Blue);
    layer.chromaSimilarity = chromaKey.similarity;
    layer.chromaSmoothness = chromaKey.smoothness;
    layer.chromaSpill = chromaKey.spill;
    const auto& effects = clip->getEffects();
    layer.brightness = effects.brightness;
    layer.contrast = effects.contrast;
    layer.saturation = effects.saturation;
    return layer;
}

bool PreviewController::renderTransitionFrameLocked(
    const ClipTransition& transition,
    int64_t timelineMs,
    bool keyframeOnlyScrub,
    int64_t* renderedTimelineMs) {
    const float rawProgress = transition.progressAt(timelineMs);
    if (rawProgress < 0.0f) {
        return false;
    }
    const auto& transitionProfile =
        VideoEngine::Advanced::TransitionEngine::resolveTransition(transition.typeId);
    const float progress =
        VideoEngine::Advanced::TransitionEngine::remapProgress(transitionProfile.typeId, rawProgress);

    const auto outgoingClip = findTimelineClipByIdLocked(transition.outgoingClipId);
    const auto incomingClip = findTimelineClipByIdLocked(transition.incomingClipId);
    if (!outgoingClip || !incomingClip) {
        return false;
    }
    if (!outgoingClip->getProperties().enabled || !incomingClip->getProperties().enabled) {
        return false;
    }

    if (m_timeline) {
        for (const auto& clip : m_timeline->clips()) {
            if (!clip || !clip->getProperties().enabled) {
                continue;
            }
            const auto role = clip->getTrackRole();
            const bool isVisualTrack =
                role == Clip::TrackRole::MainVideo ||
                role == Clip::TrackRole::Overlay;
            if (!isVisualTrack) {
                continue;
            }
            const int64_t startMs = clip->getStartTime();
            const int64_t endMs = startMs + std::max<int64_t>(1, clip->getDuration());
            if (timelineMs < startMs || timelineMs >= endMs) {
                continue;
            }
            const int clipId = static_cast<int>(clip->getId());
            if (clipId != transition.outgoingClipId && clipId != transition.incomingClipId) {
                return false;
            }
        }
    }

    if (!m_renderer || !m_renderer->acquireContext()) {
        setError("Render context acquire failed for transition");
        return false;
    }

    bool rendered = false;
    do {
        auto& outgoingState = m_clipDecoders[transition.outgoingClipId];
        auto& incomingState = m_clipDecoders[transition.incomingClipId];

        const int64_t outgoingTimelineMs = std::min<int64_t>(
            timelineMs,
            outgoingClip->getStartTime() + std::max<int64_t>(1, outgoingClip->getDuration()) - 1);
        const int64_t incomingTimelineMs = std::max<int64_t>(timelineMs, incomingClip->getStartTime());

        Backend::DecodedFrame outgoingFrame;
        Backend::DecodedFrame incomingFrame;
        int64_t outgoingRenderedSourceMs = 0;
        int64_t incomingRenderedSourceMs = 0;

        const int64_t outgoingSourceMs = clampTimeMs(
            mapClipTimelineToSourceMs(outgoingClip, outgoingTimelineMs, false));
        const int64_t incomingSourceMs = clampTimeMs(
            mapClipTimelineToSourceMs(incomingClip, incomingTimelineMs, false));

        if (!decodeClipFrameLocked(
                outgoingClip,
                outgoingState,
                outgoingSourceMs,
                keyframeOnlyScrub,
                &outgoingFrame,
                &outgoingRenderedSourceMs)) {
            break;
        }
        if (!decodeClipFrameLocked(
                incomingClip,
                incomingState,
                incomingSourceMs,
                keyframeOnlyScrub,
                &incomingFrame,
                &incomingRenderedSourceMs)) {
            break;
        }
        if (!uploadClipFrameToTextureLocked(outgoingState, outgoingFrame, outgoingRenderedSourceMs) ||
            !uploadClipFrameToTextureLocked(incomingState, incomingFrame, incomingRenderedSourceMs)) {
            break;
        }

        auto outgoingLayer = buildLayerForClipLocked(outgoingClip, outgoingState.texture.get());
        auto incomingLayer = buildLayerForClipLocked(incomingClip, incomingState.texture.get());
        rendered = m_renderer->renderTransition(
            outgoingLayer,
            incomingLayer,
            transitionProfile.typeId,
            progress);
        if (!rendered) {
            break;
        }

        m_chromaKey = {};
        m_hasDecodedFrame = false;
        m_lastRenderedClipId = -1;
        m_lastRenderedSourceMs = -1;
        m_lastRenderedVisualStateVersion = m_visualStateVersion;
        if (renderedTimelineMs) {
            *renderedTimelineMs = timelineMs;
        }
    } while (false);

    m_renderer->releaseContext();
    return rendered;
}

bool PreviewController::renderTimelineFrameLocked(
    int64_t timelineMs,
    bool keyframeOnlyScrub,
    bool allowPredictiveCache,
    bool updatePredictiveCache,
    int64_t* renderedTimelineMs) {
    if (!m_renderer || !m_texture) {
        setError("Components not initialized");
        return false;
    }

    const int64_t requestedTimelineMs = clampTimelineTimeMsLocked(timelineMs);
    int64_t clampedTimelineMs = requestedTimelineMs;
    int64_t timelineDurationMs = 0;
    if (m_timeline) {
        timelineDurationMs = m_timeline->getDuration();
    }
    if (timelineDurationMs <= 0) {
        timelineDurationMs = m_videoDurationMs;
    }
    if (timelineDurationMs > 1 && clampedTimelineMs >= timelineDurationMs) {
        clampedTimelineMs = timelineDurationMs - 1;
    }
    if (const auto* activeTransition = findActiveTransitionLocked(clampedTimelineMs)) {
        if (renderTransitionFrameLocked(
                *activeTransition,
                clampedTimelineMs,
                keyframeOnlyScrub,
                renderedTimelineMs)) {
            return true;
        }
    }

    std::shared_ptr<Clip> activeClip;
    const size_t totalTimelineClips = m_timeline ? m_timeline->clips().size() : 0;
    auto visualTrackPriority = [](Clip::TrackRole role) {
        switch (role) {
            case Clip::TrackRole::MainVideo: return 0;
            case Clip::TrackRole::Overlay: return 1;
            default: return 2;
        }
    };

    std::vector<std::shared_ptr<Clip>> activeVisualClips;
    if (m_timeline) {
        for (const auto& clip : m_timeline->clips()) {
            if (!clip || !clip->getProperties().enabled) continue;
            const auto trackRole = clip->getTrackRole();
            const bool isVisualTrack =
                trackRole == Clip::TrackRole::MainVideo ||
                trackRole == Clip::TrackRole::Overlay;
            if (!isVisualTrack) continue;
            const int64_t startMs = clip->getStartTime();
            const int64_t endMs = startMs + std::max<int64_t>(1, clip->getDuration());
            if (clampedTimelineMs < startMs || clampedTimelineMs >= endMs) continue;
            activeVisualClips.push_back(clip);
        }

        std::sort(
            activeVisualClips.begin(),
            activeVisualClips.end(),
            [&](const std::shared_ptr<Clip>& a, const std::shared_ptr<Clip>& b) {
                if (!a || !b) return static_cast<bool>(a);
                if (a->getTrackZOrder() != b->getTrackZOrder()) {
                    return a->getTrackZOrder() < b->getTrackZOrder();
                }
                const int priorityA = visualTrackPriority(a->getTrackRole());
                const int priorityB = visualTrackPriority(b->getTrackRole());
                if (priorityA != priorityB) {
                    return priorityA < priorityB;
                }
                if (a->getTrackLane() != b->getTrackLane()) {
                    return a->getTrackLane() < b->getTrackLane();
                }
                if (a->getStartTime() != b->getStartTime()) {
                    return a->getStartTime() < b->getStartTime();
                }
                return a->getId() < b->getId();
            });

        if (!activeVisualClips.empty()) {
            activeClip = activeVisualClips.front();
        }

    }

    std::vector<std::shared_ptr<Clip>> activeCompositeClips;
    if (activeVisualClips.size() > 1) {
        activeCompositeClips.assign(activeVisualClips.begin() + 1, activeVisualClips.end());
    }
    const bool hasTransformComposition =
        hasClipPreviewTransformLocked(activeClip) ||
        std::any_of(
            activeCompositeClips.begin(),
            activeCompositeClips.end(),
            [&](const std::shared_ptr<Clip>& clip) { return hasClipPreviewTransformLocked(clip); });
    const bool shouldCompositeAdditionalLayers =
        (!activeCompositeClips.empty() || hasTransformComposition) &&
        (!shouldBypassOverlayCompositionLocked() || hasTransformComposition);

    int64_t sourceSeekMs = clampedTimelineMs;
    int activeClipId = -1;
    bool linearForwardMapping = false;
    bool predictiveAllowed = allowPredictiveCache && m_predictiveCachingEnabled;
    if (!activeCompositeClips.empty()) {
        predictiveAllowed = false;
    }
    if (activeClip) {
        if (!switchDecoderSourceLocked(activeClip)) {
            return false;
        }
        activeClipId = static_cast<int>(activeClip->getId());
        m_chromaKey = activeClip->getChromaKey();
        sourceSeekMs = mapClipTimelineToSourceMs(activeClip, clampedTimelineMs, /*ignoreFreeze=*/false);
        const auto& props = activeClip->getProperties();
        linearForwardMapping =
            !props.reversePlayback &&
            !props.freezeFrameEnabled &&
            std::fabs(props.playbackSpeed - 1.0f) <= 0.001f &&
            props.curveSpeedProfile == "linear";
        if (!linearForwardMapping) {
            predictiveAllowed = false;
        }
        if (activeClip->getMediaType() == Clip::MediaType::Image) {
            predictiveAllowed = false;
        }
    } else {
        if (!m_renderer->renderLayers({})) {
            setError(
                "Blank frame render failed at timeline %lld ms: %s",
                static_cast<long long>(clampedTimelineMs),
                m_renderer->getLastError());
            return false;
        }
        m_chromaKey = {};
        m_hasDecodedFrame = false;
        m_lastRenderedClipId = -1;
        m_lastRenderedSourceMs = -1;
        m_lastRenderedVisualStateVersion = m_visualStateVersion;
        if (renderedTimelineMs) {
            *renderedTimelineMs = requestedTimelineMs;
        }
        return true;
    }

    const bool isStillImageClip =
        activeClip && activeClip->getMediaType() == Clip::MediaType::Image;
    const bool canReuseStillImageFrame =
        activeClip &&
        isStillImageClip &&
        m_hasDecodedFrame &&
        m_texture &&
        m_texture->isValid() &&
        activeClipId > 0 &&
        m_lastRenderedClipId == activeClipId &&
        sourceSeekMs == 0;
    const bool reuseStillImageFrameForComposite =
        canReuseStillImageFrame && shouldCompositeAdditionalLayers;
    const bool reuseCachedDecodedFrameForComposite =
        shouldCompositeAdditionalLayers &&
        m_hasDecodedFrame &&
        m_texture &&
        m_texture->isValid() &&
        activeClipId > 0 &&
        m_lastRenderedClipId == activeClipId &&
        m_lastRenderedSourceMs == sourceSeekMs;
    const int64_t activeApproximateFrameMs = std::max<int64_t>(
        1,
        static_cast<int64_t>(std::llround(m_frameIntervalMs)));
    const int64_t videoReuseToleranceMs = m_isPlaying.load()
        ? std::max<int64_t>(2, activeApproximateFrameMs / 2)
        : std::max<int64_t>(2, activeApproximateFrameMs / 3);
    const bool canReuseVideoFrame =
        activeClip &&
        !isStillImageClip &&
        m_hasDecodedFrame &&
        m_texture &&
        m_texture->isValid() &&
        activeClipId > 0 &&
        m_lastRenderedClipId == activeClipId &&
        m_lastRenderedSourceMs >= 0 &&
        m_lastRenderedVisualStateVersion == m_visualStateVersion &&
        std::llabs(sourceSeekMs - m_lastRenderedSourceMs) <= videoReuseToleranceMs;
    const bool reuseVideoFrameForComposite =
        canReuseVideoFrame && shouldCompositeAdditionalLayers;
    if (canReuseStillImageFrame) {
        m_lastRenderedClipId = activeClipId;
        m_lastRenderedSourceMs = 0;
        if (renderedTimelineMs) {
            *renderedTimelineMs = requestedTimelineMs;
        }
        if (!shouldCompositeAdditionalLayers) {
            return redrawTextureLocked(0, 0, m_surfaceWidth, m_surfaceHeight, false);
        }
    }

    int64_t renderedSourceMs = sourceSeekMs;
    bool renderedFromPredictiveCache =
        reuseStillImageFrameForComposite ||
        reuseCachedDecodedFrameForComposite ||
        reuseVideoFrameForComposite;
    if (isStillImageClip && !canReuseStillImageFrame) {
        if (!m_decoder) {
            setError("Image decoder unavailable");
            return false;
        }
        DecodedFrame decodedFrame;
        std::string decodeError;
        if (!decodeStillImageFrame(
                m_decoder.get(),
                resolvePreviewDecoderPath(activeClip.get(), m_adaptiveProxyEnabled),
                resolveSuperResolutionPreviewScaleLimitLocked(
                    m_ghostPreviewEnabled ? m_ghostPreviewLongEdgePx : 0,
                    m_videoWidth,
                    m_videoHeight),
                &decodedFrame,
                &decodeError)) {
            setError(
                "Still image decode failed at timeline %lld ms: %s",
                static_cast<long long>(clampedTimelineMs),
                decodeError.empty() ? "unknown error" : decodeError.c_str());
            return false;
        }
        m_videoWidth = static_cast<int>(decodedFrame.width);
        m_videoHeight = static_cast<int>(decodedFrame.height);
        m_videoDurationMs = 0;
        renderedSourceMs = 0;
        if (!renderDecodedFrame(
                decodedFrame,
                clampedTimelineMs,
                /*presentFrame=*/!shouldCompositeAdditionalLayers)) {
            return false;
        }
    } else {
        sourceSeekMs = clampDecodableTimeMs(sourceSeekMs, m_videoDurationMs);
        renderedSourceMs = sourceSeekMs;
        if (canReuseVideoFrame) {
            renderedSourceMs = m_lastRenderedSourceMs;
            if (!shouldCompositeAdditionalLayers) {
                if (renderedTimelineMs) {
                    *renderedTimelineMs = requestedTimelineMs;
                }
                return redrawTextureLocked(0, 0, m_surfaceWidth, m_surfaceHeight, false);
            }
        }
        if (!renderedFromPredictiveCache && predictiveAllowed) {
            if (m_isPlaying.load() && m_framePrefetcher) {
                if (const auto prefetchedFrame = m_framePrefetcher->getFrame(static_cast<uint32_t>(activeClipId), clampedTimelineMs)) {
                    renderedSourceMs = prefetchedFrame->sourcePtsMs;
                    if (!renderDecodedFrame(
                            prefetchedFrame->frame,
                            clampedTimelineMs,
                            /*presentFrame=*/!shouldCompositeAdditionalLayers)) {
                        return false;
                    }
                    renderedFromPredictiveCache = true;
                }
            }
        }

        if (!renderedFromPredictiveCache && predictiveAllowed) {
            renderedFromPredictiveCache =
                tryRenderFromPredictiveCacheLocked(
                    sourceSeekMs,
                    &renderedSourceMs,
                    /*presentFrame=*/!shouldCompositeAdditionalLayers);
        }

        const int64_t approximateFrameMs = activeApproximateFrameMs;
        const int64_t sequentialFrameToleranceMs = std::max<int64_t>(
            2,
            approximateFrameMs / 2);
        const bool playingNow = m_isPlaying.load();
        const int64_t sequentialDecodeWindowMs = playingNow
            ? std::max<int64_t>(1800, approximateFrameMs * 48)
            : 420;
        const int64_t forwardDeltaMs = sourceSeekMs - m_lastRenderedSourceMs;
        const bool allowSequentialDecode =
            !renderedFromPredictiveCache &&
            !keyframeOnlyScrub &&
            activeClipId > 0 &&
            linearForwardMapping &&
            m_lastRenderedClipId == activeClipId &&
            m_lastRenderedSourceMs >= 0 &&
            forwardDeltaMs >= 0 &&
            forwardDeltaMs <= sequentialDecodeWindowMs;

        if (allowSequentialDecode) {
            DecodedFrame decodedFrame;
            bool renderedSequential = false;
            const int maxSequentialAttempts = playingNow ? 12 : 6;
            for (int attempt = 0; attempt < maxSequentialAttempts; ++attempt) {
                if (!m_decoder || !m_decoder->decodeNextFrame(decodedFrame)) {
                    break;
                }
                const int64_t frameSourceMs = decodedFrame.ptsMs > 0
                    ? clampTimeMs(decodedFrame.ptsMs)
                    : clampTimeMs(
                          m_lastRenderedSourceMs +
                          static_cast<int64_t>(std::llround(m_frameIntervalMs)));
                renderedSourceMs = frameSourceMs;
                if (frameSourceMs + sequentialFrameToleranceMs >= sourceSeekMs ||
                    attempt == maxSequentialAttempts - 1) {
                    if (!renderDecodedFrame(
                            decodedFrame,
                            clampedTimelineMs,
                            /*presentFrame=*/!shouldCompositeAdditionalLayers)) {
                        return false;
                    }
                    renderedSequential = true;
                    break;
                }
            }
            if (renderedSequential) {
                m_lastRenderedClipId = activeClipId;
                m_lastRenderedSourceMs = renderedSourceMs;
                if (renderedTimelineMs) {
                    *renderedTimelineMs = requestedTimelineMs;
                }
                if (!shouldCompositeAdditionalLayers) {
                    return true;
                }
                renderedFromPredictiveCache = true;
            }
        }

        if (!renderedFromPredictiveCache) {
            if (!m_decoder) {
                setError("Decoder unavailable");
                return false;
            }
            const bool seekOk = keyframeOnlyScrub
                ? m_decoder->seekToKeyframeForPreview(sourceSeekMs)
                : m_decoder->seekForPreview(sourceSeekMs);
            if (!seekOk) {
                setError(
                    "Preview seek failed at timeline %lld ms (source %lld ms)",
                    static_cast<long long>(clampedTimelineMs),
                    static_cast<long long>(sourceSeekMs));
                return false;
            }

            DecodedFrame decodedFrame;
            if (!m_decoder->decodeNextFrame(decodedFrame)) {
                if (keyframeOnlyScrub &&
                    m_decoder->seekForPreview(sourceSeekMs) &&
                    m_decoder->decodeNextFrame(decodedFrame)) {
                    // Recovered frame after keyframe miss.
                } else {
                    setError(
                        "Frame decode failed at timeline %lld ms (source %lld ms)",
                        static_cast<long long>(clampedTimelineMs),
                        static_cast<long long>(sourceSeekMs));
                    return false;
                }
            }

            renderedSourceMs = decodedFrame.ptsMs > 0
                ? clampTimeMs(decodedFrame.ptsMs)
                : sourceSeekMs;
            if (!renderDecodedFrame(
                    decodedFrame,
                    clampedTimelineMs,
                    /*presentFrame=*/!shouldCompositeAdditionalLayers)) {
                return false;
            }
            if (predictiveAllowed || updatePredictiveCache) {
                cachePredictiveFrameLocked(std::move(decodedFrame), renderedSourceMs);
            }
        }
    }

    m_lastRenderedClipId = activeClipId;
    m_lastRenderedSourceMs = renderedSourceMs;
    if (updatePredictiveCache && predictiveAllowed && activeCompositeClips.empty()) {
        if (!m_isPlaying.load()) {
            requestPredictivePrefetchLocked(renderedSourceMs);
        } else {
            const int64_t requestStepMs = std::max<int64_t>(24, m_predictiveSampleStepMs / 2);
            if (m_lastPlaybackPrefetchRequestMs == std::numeric_limits<int64_t>::min() ||
                std::llabs(renderedSourceMs - m_lastPlaybackPrefetchRequestMs) >= requestStepMs) {
                m_lastPlaybackPrefetchRequestMs = renderedSourceMs;
                requestPredictivePrefetchLocked(renderedSourceMs);
            }
        }
    }
    if (renderedTimelineMs) {
        *renderedTimelineMs = requestedTimelineMs;
    }

    // ---- Multi-track compositing: render remaining visual clips on top ----
    if (shouldCompositeAdditionalLayers) {
        std::vector<GPU::EGLRenderer::Layer> layers;

        // Base layer: already rendered into m_texture.
        if (m_texture && m_texture->isValid()) {
            GPU::EGLRenderer::Layer base;
            base.texture = m_texture;
            base.opacity = activeClip ? activeClip->getProperties().opacity : 1.0f;
            if (activeClip) {
                const auto transform = clipPreviewTransformLocked(static_cast<int>(activeClip->getId()));
                base.zoom = transform.zoom;
                base.scaleX = transform.scaleX;
                base.scaleY = transform.scaleY;
                base.panXPx = transform.panXPx;
                base.panYPx = transform.panYPx;
                base.rotationDeg = transform.rotationDeg;
                base.mirrorX = transform.mirrorX;
                base.objectTransform = usesObjectStylePreviewTransform(activeClip);
                const auto& ck = activeClip->getChromaKey();
                base.chromaEnabled = ck.enabled;
                base.blueKey = (ck.color == Clip::ChromaKeyParams::KeyColor::Blue);
                base.chromaSimilarity = ck.similarity;
                base.chromaSmoothness = ck.smoothness;
                base.chromaSpill = ck.spill;
                const auto& fx = activeClip->getEffects();
                base.brightness = fx.brightness;
                base.contrast   = fx.contrast;
                base.saturation = fx.saturation;
            }
            layers.push_back(base);
        }

        const bool hasCompositeLayers = !layers.empty() || !activeCompositeClips.empty();
        const bool compositeContextReady =
            hasCompositeLayers &&
            m_renderer &&
            m_renderer->acquireContext();
        if (hasCompositeLayers && !compositeContextReady) {
            setError("Render context acquire failed for overlay composite");
            return false;
        }

        const std::string activeDecoderPath =
            activeClip ? resolvePreviewDecoderPath(activeClip.get(), m_adaptiveProxyEnabled) : std::string{};
        const int64_t baseTextureShareToleranceMs = m_isPlaying.load()
            ? std::max<int64_t>(48, static_cast<int64_t>(std::llround(m_frameIntervalMs)))
            : 2;

        // Remaining visual layers: decode each clip at the current time.
        for (const auto& clip : activeCompositeClips) {
            const int clipId = static_cast<int>(clip->getId());
            const int64_t sourceMs =
                clip->getMediaType() == Clip::MediaType::Image
                    ? 0
                    : clampTimeMs(mapClipTimelineToSourceMs(clip, clampedTimelineMs, false));
            const bool canShareBaseTexture =
                activeClip &&
                m_texture &&
                m_texture->isValid() &&
                !activeDecoderPath.empty() &&
                activeDecoderPath == resolvePreviewDecoderPath(clip.get(), m_adaptiveProxyEnabled) &&
                std::llabs(sourceMs - renderedSourceMs) <= baseTextureShareToleranceMs;
            const GPU::GLTexture* compositeTexture = nullptr;

            if (canShareBaseTexture) {
                compositeTexture = m_texture;
            } else {
                auto& state = m_clipDecoders[clipId];
                const int64_t playbackCompositeReuseWindowMs = m_isPlaying.load()
                    ? playbackCompositeReuseWindowMsLocked(state.approximateFrameMs)
                    : 0;
                const bool canReuseClipTexture =
                    state.texture &&
                    state.texture->isValid() &&
                    state.lastRenderedSourceMs >= 0 &&
                    (state.lastRenderedSourceMs == sourceMs ||
                        (playbackCompositeReuseWindowMs > 0 &&
                            std::llabs(sourceMs - state.lastRenderedSourceMs) <= playbackCompositeReuseWindowMs));
                if (!canReuseClipTexture) {
                    Backend::DecodedFrame frame;
                    int64_t overlayRenderedSourceMs = sourceMs;
                    if (!decodeClipFrameLocked(
                            clip,
                            state,
                            sourceMs,
                            keyframeOnlyScrub,
                            &frame,
                            &overlayRenderedSourceMs)) {
                        continue;
                    }

                    const int fw = static_cast<int>(frame.width);
                    const int fh = static_cast<int>(frame.height);
                    if (fw <= 0 || fh <= 0 || frame.rgb.empty()) {
                        continue;
                    }

                    if (!state.texture) {
                        state.texture = std::make_unique<GPU::GLTexture>();
                    }
                    if (!state.texture->initialize(fw, fh)) {
                        continue;
                    }

                    const size_t pixelCount = static_cast<size_t>(fw) * fh;
                    const uint8_t* rgba = nullptr;
                    std::vector<uint8_t> scratch;
                    if (frame.rgb.size() >= pixelCount * 4) {
                        rgba = frame.rgb.data();
                    } else if (frame.rgb.size() >= pixelCount * 3) {
                        scratch.resize(pixelCount * 4);
                        const uint8_t* src = frame.rgb.data();
                        uint8_t* dst = scratch.data();
                        for (size_t i = 0; i < pixelCount; ++i) {
                            dst[i * 4 + 0] = src[i * 3 + 0];
                            dst[i * 4 + 1] = src[i * 3 + 1];
                            dst[i * 4 + 2] = src[i * 3 + 2];
                            dst[i * 4 + 3] = 0xFF;
                        }
                        rgba = scratch.data();
                    }
                    if (!rgba) {
                        continue;
                    }

                    state.texture->update(rgba);
                    state.lastRenderedSourceMs = overlayRenderedSourceMs;
                }
                if (!state.texture || !state.texture->isValid()) {
                    continue;
                }
                compositeTexture = state.texture.get();
            }

            GPU::EGLRenderer::Layer layer;
            layer.texture = compositeTexture;
            layer.opacity = clip->getProperties().opacity;
            const auto transform = clipPreviewTransformLocked(clipId);
            layer.zoom = transform.zoom;
            layer.scaleX = transform.scaleX;
            layer.scaleY = transform.scaleY;
            layer.panXPx = transform.panXPx;
            layer.panYPx = transform.panYPx;
            layer.rotationDeg = transform.rotationDeg;
            layer.mirrorX = transform.mirrorX;
            layer.objectTransform = usesObjectStylePreviewTransform(clip);
            const auto& ck = clip->getChromaKey();
            layer.chromaEnabled = ck.enabled;
            layer.blueKey = (ck.color == Clip::ChromaKeyParams::KeyColor::Blue);
            layer.chromaSimilarity = ck.similarity;
            layer.chromaSmoothness = ck.smoothness;
            layer.chromaSpill = ck.spill;
            const auto& fx = clip->getEffects();
            layer.brightness = fx.brightness;
            layer.contrast   = fx.contrast;
            layer.saturation = fx.saturation;
            layers.push_back(layer);
        }

        // Render a single composite pass. If overlay decode failed, this still
        // presents the already-uploaded base layer with correct effects.
        if (!layers.empty()) {
            if (m_vulkanRenderer &&
                m_vulkanRenderer->shouldWarmForPreview(m_surfaceWidth, m_surfaceHeight, layers.size())) {
                std::vector<uint32_t> textureBatch;
                textureBatch.reserve(layers.size());
                for (const auto& layer : layers) {
                    if (layer.texture && layer.texture->isValid()) {
                        textureBatch.push_back(layer.texture->getHandle());
                    }
                }
                if (!textureBatch.empty()) {
                    m_vulkanRenderer->submitRenderCommands(textureBatch);
                }
            }
            const bool composited = m_renderer->renderLayers(layers);
            if (!composited) {
                if (compositeContextReady) {
                    m_renderer->releaseContext();
                }
                setError("Overlay composite failed: %s", m_renderer->getLastError());
                return false;
            }
            m_lastRenderedVisualStateVersion = m_visualStateVersion;
        }
        if (compositeContextReady) {
            m_renderer->releaseContext();
        }
    }

    return true;
}

bool PreviewController::renderDecodedFrame(
    const DecodedFrame& decodedFrame,
    int64_t timelineMs,
    bool presentFrame) {
    const int frameWidth = static_cast<int>(decodedFrame.width);
    const int frameHeight = static_cast<int>(decodedFrame.height);
    if (frameWidth <= 0 || frameHeight <= 0 || decodedFrame.rgb.empty()) {
        setError("Frame conversion failed");
        return false;
    }
    const size_t pixelCount = static_cast<size_t>(frameWidth) * frameHeight;
    const size_t expectedRgbaBytes = pixelCount * 4;
    const size_t expectedRgbBytes = pixelCount * 3;

    const uint8_t* rgbaPixels = nullptr;
    if (decodedFrame.rgb.size() >= expectedRgbaBytes) {
        // Decoder already outputs RGBA (zero extra copy path).
        rgbaPixels = decodedFrame.rgb.data();
    } else if (decodedFrame.rgb.size() >= expectedRgbBytes) {
        // Fallback conversion path for RGB24 input; reuse scratch buffer.
        if (m_rgbaScratch.size() != expectedRgbaBytes) {
            m_rgbaScratch.resize(expectedRgbaBytes);
        }
        const uint8_t* src = decodedFrame.rgb.data();
        uint8_t* dst = m_rgbaScratch.data();
        for (size_t i = 0; i < pixelCount; ++i) {
            const size_t srcIndex = i * 3;
            const size_t dstIndex = i * 4;
            dst[dstIndex + 0] = src[srcIndex + 0];
            dst[dstIndex + 1] = src[srcIndex + 1];
            dst[dstIndex + 2] = src[srcIndex + 2];
            dst[dstIndex + 3] = 0xFF;
        }
        rgbaPixels = m_rgbaScratch.data();
    } else {
        setError("Frame buffer too small for conversion");
        return false;
    }

    if (!m_renderer || !m_renderer->acquireContext()) {
        setError("Render context acquire failed: %s",
                 m_renderer ? m_renderer->getLastError() : "renderer missing");
        return false;
    }

    if (!ensurePrimaryTripleBufferLocked(frameWidth, frameHeight)) {
        m_renderer->releaseContext();
        setError("Texture initialization failed for %dx%d frame", frameWidth, frameHeight);
        return false;
    }
    auto* uploadTexture = m_tripleBuffer ? m_tripleBuffer->acquireBackTexture() : nullptr;
    if (!uploadTexture || !uploadTexture->isValid()) {
        m_renderer->releaseContext();
        setError("TripleBuffer back texture unavailable for %dx%d frame", frameWidth, frameHeight);
        return false;
    }
    uploadTexture->update(rgbaPixels);
    if (m_tripleBuffer) {
        m_tripleBuffer->presentBackTexture();
        refreshPrimaryTextureAliasLocked();
    }

    if (!presentFrame) {
        m_renderer->releaseContext();
        m_hasDecodedFrame = true;
        return true;
    }

    if (m_renderer) {
        const auto& chroma = m_chromaKey;
        m_renderer->setChromaKey(
            chroma.enabled,
            chroma.color == Clip::ChromaKeyParams::KeyColor::Blue,
            chroma.similarity,
            chroma.smoothness,
            chroma.spill
        );
    }

    if (!m_renderer->renderFrame(*m_texture)) {
        m_renderer->releaseContext();
        setError("Render failed at %lld ms: %s",
                 static_cast<long long>(timelineMs),
                 m_renderer->getLastError());
        return false;
    }
    m_renderer->releaseContext();
    m_hasDecodedFrame = true;
    m_lastRenderedVisualStateVersion = m_visualStateVersion;
    return true;
}

bool PreviewController::decodeClipFrameLocked(
    const std::shared_ptr<Clip>& clip,
    ClipDecodeState& state,
    int64_t sourceSeekMs,
    bool keyframeOnlyScrub,
    Backend::DecodedFrame* decodedFrameOut,
    int64_t* renderedSourceMsOut) {
    if (!clip || !decodedFrameOut || !renderedSourceMsOut) {
        return false;
    }

    syncClipProxyPathLocked(clip);
    const std::string& mediaPath = clip->getMediaPath();
    const std::string decoderPath = resolvePreviewDecoderPath(clip.get(), m_adaptiveProxyEnabled);
    if (!state.decoder || state.openPath != decoderPath) {
        state.decoder = std::make_unique<Backend::VideoDecoder>();
        if (!state.decoder->open(decoderPath)) {
            state.decoder.reset();
            state.openPath.clear();
            state.texture.reset();
            state.lastRenderedSourceMs = -1;
            state.hasLastDecodedFrame = false;
            return false;
        }
        state.decoder->setPreviewScaleLimit(
            resolveSuperResolutionPreviewScaleLimitLocked(
                m_ghostPreviewEnabled ? m_ghostPreviewLongEdgePx : 0,
                state.decoder->getWidth(),
                state.decoder->getHeight()));
        state.openPath = decoderPath;
        state.mediaDurationMs = static_cast<int64_t>(state.decoder->getDuration() * 1000.0);
        state.texture.reset();
        state.lastRenderedSourceMs = -1;
        state.hasLastDecodedFrame = false;
        const double fps = state.decoder->getFps();
        state.approximateFrameMs = fps > 0.0
            ? std::max<int64_t>(1, static_cast<int64_t>(std::llround(1000.0 / fps)))
            : static_cast<int64_t>(std::max(1.0, m_frameIntervalMs));
        maybeQueueProxyBuildLocked(clip);
    }

    if (!state.decoder) {
        return false;
    }

    if (clip->getMediaType() == Clip::MediaType::Image) {
        if (state.hasLastDecodedFrame) {
            *decodedFrameOut = state.lastDecodedFrame;
            *renderedSourceMsOut = 0;
            state.lastRenderedSourceMs = 0;
            return true;
        }

        Backend::DecodedFrame decodedFrame;
        std::string decodeError;
        if (!decodeStillImageFrame(
                state.decoder.get(),
                decoderPath,
                resolveSuperResolutionPreviewScaleLimitLocked(
                    m_ghostPreviewEnabled ? m_ghostPreviewLongEdgePx : 0,
                    state.decoder->getWidth(),
                    state.decoder->getHeight()),
                &decodedFrame,
                &decodeError)) {
            state.decoder.reset();
            state.openPath.clear();
            state.texture.reset();
            state.lastRenderedSourceMs = -1;
            state.hasLastDecodedFrame = false;
            return false;
        }

        state.mediaDurationMs = 0;
        state.lastDecodedFrame = decodedFrame;
        state.hasLastDecodedFrame = true;
        state.lastRenderedSourceMs = 0;
        *decodedFrameOut = std::move(decodedFrame);
        *renderedSourceMsOut = 0;
        return true;
    }

    const auto& props = clip->getProperties();
    const bool linearForwardMapping =
        !props.reversePlayback &&
        !props.freezeFrameEnabled &&
        std::fabs(props.playbackSpeed - 1.0f) <= 0.001f &&
        props.curveSpeedProfile == "linear";
    const int64_t frameToleranceMs = std::max<int64_t>(2, (state.approximateFrameMs * 2) / 3);
    const bool playingNow = m_isPlaying.load();
    const int64_t sequentialDecodeWindowMs = playingNow
        ? std::max<int64_t>(360, state.approximateFrameMs * 12)
        : state.sequentialDecodeWindowMs;
    const int64_t clampedSourceMs = clampDecodableTimeMs(sourceSeekMs, state.mediaDurationMs);

    if (state.hasLastDecodedFrame) {
        const int64_t previousMs = clampDecodableTimeMs(
            state.lastDecodedFrame.ptsMs > 0 ? state.lastDecodedFrame.ptsMs : state.lastRenderedSourceMs,
            state.mediaDurationMs);
        const int64_t deltaMs = clampedSourceMs - previousMs;

        if (std::llabs(deltaMs) <= frameToleranceMs) {
            *decodedFrameOut = state.lastDecodedFrame;
            *renderedSourceMsOut = previousMs;
            state.lastRenderedSourceMs = previousMs;
            return true;
        }

        if (playingNow &&
            deltaMs > 0 &&
            deltaMs <= playbackCompositeReuseWindowMsLocked(state.approximateFrameMs)) {
            *decodedFrameOut = state.lastDecodedFrame;
            *renderedSourceMsOut = previousMs;
            state.lastRenderedSourceMs = previousMs;
            return true;
        }

        if (!keyframeOnlyScrub &&
            linearForwardMapping &&
            deltaMs > 0 &&
            deltaMs <= sequentialDecodeWindowMs) {
            Backend::DecodedFrame candidate;
            const int maxSequentialAttempts = playingNow ? 8 : 24;
            int attempts = 0;
            while (attempts < maxSequentialAttempts && state.decoder->decodeNextFrame(candidate)) {
                ++attempts;
                const int64_t candidatePtsMs = clampDecodableTimeMs(
                    candidate.ptsMs > 0 ? candidate.ptsMs : previousMs + state.approximateFrameMs,
                    state.mediaDurationMs);
                candidate.ptsMs = candidatePtsMs;
                state.lastDecodedFrame = candidate;
                state.hasLastDecodedFrame = true;
                state.lastRenderedSourceMs = candidatePtsMs;
                if (candidatePtsMs + frameToleranceMs >= clampedSourceMs) {
                    *decodedFrameOut = candidate;
                    *renderedSourceMsOut = candidatePtsMs;
                    return true;
                }
            }

            *decodedFrameOut = state.lastDecodedFrame;
            *renderedSourceMsOut = state.lastRenderedSourceMs;
            return true;
        }
    }

    const bool seekOk = keyframeOnlyScrub
        ? state.decoder->seekToKeyframeForPreview(clampedSourceMs)
        : state.decoder->seekForPreview(clampedSourceMs);
    if (!seekOk) {
        return false;
    }

    Backend::DecodedFrame decodedFrame;
    if (!state.decoder->decodeNextFrame(decodedFrame)) {
        if (keyframeOnlyScrub &&
            state.decoder->seekForPreview(clampedSourceMs) &&
            state.decoder->decodeNextFrame(decodedFrame)) {
            // Recovered after a coarse keyframe-only scrub miss.
        } else {
            return false;
        }
    }

    const int64_t renderedSourceMs = clampDecodableTimeMs(
        decodedFrame.ptsMs > 0 ? decodedFrame.ptsMs : clampedSourceMs,
        state.mediaDurationMs);
    decodedFrame.ptsMs = renderedSourceMs;
    state.lastDecodedFrame = decodedFrame;
    state.hasLastDecodedFrame = true;
    state.lastRenderedSourceMs = renderedSourceMs;
    *decodedFrameOut = std::move(decodedFrame);
    *renderedSourceMsOut = renderedSourceMs;
    return true;
}

bool PreviewController::shouldAutoRequestPreviewProxyLocked(
    const std::string& sourcePath,
    int width,
    int height,
    double fps) const {
    if (!m_proxyManager || sourcePath.empty() || width <= 0 || height <= 0) {
        return false;
    }
    const int longEdge = std::max(width, height);
    return longEdge >= 1920 || fps >= 50.0;
}

void PreviewController::maybeQueueProxyBuildForSourceLocked(
    const std::string& sourcePath,
    int width,
    int height,
    double fps) {
    if (!shouldAutoRequestPreviewProxyLocked(sourcePath, width, height, fps)) {
        return;
    }
    m_proxyManager->requestProxy(sourcePath);
    m_proxyManager->manageStorage();
}

void PreviewController::syncClipProxyPathLocked(const std::shared_ptr<Clip>& clip) {
    if (!clip || !m_proxyManager) {
        return;
    }
    const std::string& sourcePath = clip->getMediaPath();
    if (sourcePath.empty()) {
        clip->clearPreviewProxyPath();
        return;
    }
    const std::string existingProxyPath = clip->getPreviewProxyPath();
    if (!existingProxyPath.empty() && fileExists(existingProxyPath)) {
        return;
    }
    const std::string playbackPath = m_proxyManager->resolvePlaybackPath(sourcePath);
    if (!playbackPath.empty() && playbackPath != sourcePath) {
        clip->setPreviewProxyPath(playbackPath);
    } else if (!clip->getPreviewProxyPath().empty()) {
        clip->clearPreviewProxyPath();
    }
}

void PreviewController::maybeQueueProxyBuildLocked(const std::shared_ptr<Clip>& clip) {
    if (!clip || clip->getMediaType() != Clip::MediaType::Video) {
        return;
    }

    syncClipProxyPathLocked(clip);

    if (m_smartCache) {
        m_smartCache->markHeavySegment(clip->getStartTime(), clip->getEndTime());
    }

    int width = 0;
    int height = 0;
    double fps = 0.0;
    const std::string activeDecoderPath = resolvePreviewDecoderPath(clip.get(), m_adaptiveProxyEnabled);
    if (m_decoder && m_openVideoPath == activeDecoderPath) {
        width = m_videoWidth;
        height = m_videoHeight;
        fps = m_videoFps;
    } else {
        const auto stateIt = m_clipDecoders.find(static_cast<int>(clip->getId()));
        if (stateIt != m_clipDecoders.end() && stateIt->second.decoder) {
            width = stateIt->second.decoder->getWidth();
            height = stateIt->second.decoder->getHeight();
            fps = stateIt->second.decoder->getFps();
        }
    }
    maybeQueueProxyBuildForSourceLocked(clip->getMediaPath(), width, height, fps);
}

void PreviewController::rebuildSmartCacheHintsLocked() {
    if (!m_smartCache || !m_timeline) {
        return;
    }
    m_smartCache->reset();

    for (const auto& clip : m_timeline->clips()) {
        if (!clip) {
            continue;
        }
        const auto& effects = clip->getEffects();
        const auto& chroma = clip->getChromaKey();
        const bool visuallyHeavy =
            (effects.enabled &&
                (std::fabs(effects.brightness) > 0.001f ||
                 std::fabs(effects.contrast - 1.0f) > 0.001f ||
                 std::fabs(effects.saturation - 1.0f) > 0.001f ||
                 effects.lutEnabled)) ||
            chroma.enabled ||
            clip->getTrackRole() == Clip::TrackRole::Overlay ||
            clip->getTrackRole() == Clip::TrackRole::TextSticker;
        if (visuallyHeavy) {
            m_smartCache->markHeavySegment(clip->getStartTime(), clip->getEndTime());
        }
    }

    for (const auto& [transitionId, transition] : m_transitions) {
        (void)transitionId;
        if (transition.durationMs > 0) {
            m_smartCache->markHeavySegment(
                transition.startTimeMs,
                transition.startTimeMs + transition.durationMs);
        }
    }
}

bool PreviewController::primePlaybackAtLocked(int64_t targetTimeMs) {
    DecodedFrame decodedFrame;
    DecodedFrame selectedFrame;
    bool foundFrame = false;

    while (m_decoder->decodeNextFrame(decodedFrame)) {
        const int64_t framePtsMs = decodedFrame.ptsMs > 0
            ? clampTimeMs(decodedFrame.ptsMs)
            : targetTimeMs;
        selectedFrame = decodedFrame;
        selectedFrame.ptsMs = framePtsMs;
        foundFrame = true;
        if (framePtsMs >= targetTimeMs) {
            break;
        }
    }

    if (!foundFrame) {
        setError("Failed to preroll/decode frame at %lld ms",
                 static_cast<long long>(targetTimeMs));
        return false;
    }

    if (!renderDecodedFrame(selectedFrame, selectedFrame.ptsMs)) {
        return false;
    }

    m_currentTimeMs.store(selectedFrame.ptsMs);
    m_playbackAnchorTimeMs = selectedFrame.ptsMs;
    m_playbackAnchorWallClock = std::chrono::steady_clock::now();
    m_lastRenderTimeMs = selectedFrame.ptsMs;
    QueuedFrame primedFrame;
    primedFrame.frame = std::move(selectedFrame);
    primedFrame.ptsMs = m_currentTimeMs.load();
    m_frameQueue.push_back(std::move(primedFrame));
    return true;
}

void PreviewController::clearQueuedFramesLocked() {
    m_frameQueue.clear();
}

void PreviewController::startDecodeWorkerLocked() {
    if (m_decodeRunning.exchange(true)) {
        return;
    }
    m_decodeThread = std::thread([this]() {
        applyRealtimePreviewPriority();
        decodeWorkerLoop();
    });
}

void PreviewController::stopDecodeWorkerLocked() {
    if (!m_decodeRunning.exchange(false)) {
        return;
    }
    if (m_decodeThread.joinable()) {
        m_decodeThread.join();
    }
}

void PreviewController::decodeWorkerLoop() {
    using namespace std::chrono_literals;
    while (m_decodeRunning.load()) {
        {
            std::lock_guard<std::mutex> lock(m_playbackMutex);
            if (!m_decoder || !m_isPlaying.load()) {
                // Keep worker lightweight while paused/stopped.
            } else if (m_frameQueue.size() < m_maxQueuedFrames && !m_reachedEos.load()) {
                DecodedFrame decodedFrame;
                if (m_decoder->decodeNextFrame(decodedFrame)) {
                    const int64_t ptsMs = decodedFrame.ptsMs > 0
                        ? clampTimeMs(decodedFrame.ptsMs)
                        : clampTimeMs(
                              m_currentTimeMs.load() +
                              static_cast<int64_t>(m_frameIntervalMs));
                    const int64_t playbackTimeMs = playbackTimelineTimeMsLocked();
                    if (ptsMs + 120 >= playbackTimeMs) {
                        QueuedFrame queuedFrame;
                        queuedFrame.frame = std::move(decodedFrame);
                        queuedFrame.ptsMs = ptsMs;
                        m_frameQueue.push_back(std::move(queuedFrame));
                    }
                } else {
                    m_reachedEos.store(true);
                }
            }
        }
        std::this_thread::sleep_for(4ms);
    }
}

int64_t PreviewController::playbackTimelineTimeMsLocked() const {
    const auto now = std::chrono::steady_clock::now();
    if (m_audioMasterClockEnabled && m_audioMasterClockValid) {
        const int64_t audioClockAgeUs = std::chrono::duration_cast<std::chrono::microseconds>(
            now - m_audioMasterClockWallClock).count();
        if (audioClockAgeUs >= 0 &&
            audioClockAgeUs <= (m_audioMasterClockStaleAfterMs * 1000LL)) {
            int64_t predictedAudioClockUs = std::max<int64_t>(0, m_audioMasterClockUs + audioClockAgeUs);
            if (m_audioSyncEngine) {
                const double correctionScale =
                    static_cast<double>(m_audioSyncEngine->playbackRateCorrectionPpm()) / 1000000.0;
                predictedAudioClockUs += static_cast<int64_t>(
                    static_cast<double>(audioClockAgeUs) * correctionScale);
            }
            return clampTimelineTimeMsLocked(std::max<int64_t>(0, predictedAudioClockUs / 1000));
        }
    }
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        now - m_playbackAnchorWallClock);
        int64_t clampedTarget = clampTimelineTimeMsLocked(m_playbackAnchorTimeMs + elapsed.count());
    if (m_loopingLiveTransitionsEnabled) {
        if (const auto* t = findActiveTransitionLocked(m_playbackAnchorTimeMs)) {
            if (t->durationMs > 0) {
                int64_t transitionElapsed = clampedTarget - t->startTimeMs;
                transitionElapsed %= t->durationMs;
                clampedTarget = t->startTimeMs + transitionElapsed;
            }
        }
    }
    return clampedTarget;
}

VideoEngine::Performance::FrameBudgetInput PreviewController::makeFrameBudgetInputLocked(
    int64_t renderCostMs) const {
    VideoEngine::Performance::FrameBudgetInput input;
    input.adaptiveEnabled = m_adaptiveFrameDropEnabled;
    input.playing = m_isPlaying.load();
    input.predictiveCachingEnabled = m_predictiveCachingEnabled;
    input.renderCostMs = std::max<int64_t>(0, renderCostMs);
    input.targetFps = m_targetPreviewFps;
    input.minFps = m_minPreviewFps;
    input.basePreviewLongEdgePx = m_ghostPreviewEnabled ? m_ghostPreviewLongEdgePx : 0;
    input.predictiveLookAroundMs = m_predictiveLookAroundBaseMs;
    input.predictiveSampleStepMs = m_predictiveSampleStepBaseMs;
    input.predictiveCacheMaxFrames = m_predictiveCacheBaseMaxFrames;
    input.queuedFrames = static_cast<int>(m_frameQueue.size());
    return input;
}

void PreviewController::applyFrameBudgetDecisionLocked(
    const VideoEngine::Performance::FrameBudgetDecision& decision) {
    const int previousScaleLimitPx = m_budgetPreviewLongEdgePx;
    const int previousLookAroundMs = m_predictiveLookAroundMs;
    const int previousSampleStepMs = m_predictiveSampleStepMs;
    const bool previousPrefetchAllowed = m_budgetPredictivePrefetchAllowed;

    m_frameDropOverloadScore = std::clamp(decision.overloadScore, 0, 12);
    m_budgetPreviewFps = std::max(1, decision.previewFps);
    m_budgetPreviewLongEdgePx = std::max(0, decision.previewLongEdgePx);
    m_budgetBypassOverlayComposition = decision.bypassOverlayComposition;
    m_budgetPredictivePrefetchAllowed = decision.allowPredictivePrefetch;
    m_predictiveLookAroundMs = std::clamp(decision.predictiveLookAroundMs, 200, 8000);
    m_predictiveSampleStepMs = std::clamp(decision.predictiveSampleStepMs, 16, 1000);
    m_predictiveCacheMaxFrames = std::clamp(decision.predictiveCacheMaxFrames, 4, 240);

    if (previousScaleLimitPx != m_budgetPreviewLongEdgePx) {
        applyPreviewScaleLimitLocked();
    }
    if (previousScaleLimitPx != m_budgetPreviewLongEdgePx ||
        previousLookAroundMs != m_predictiveLookAroundMs ||
        previousSampleStepMs != m_predictiveSampleStepMs ||
        previousPrefetchAllowed != m_budgetPredictivePrefetchAllowed) {
        cancelPredictivePrefetchLocked(false);
    }
}

void PreviewController::resetFrameBudgetLocked() {
    if (!m_frameBudgetController) {
        m_frameDropOverloadScore = 0;
        m_budgetPreviewFps = std::max(1, m_targetPreviewFps);
        m_budgetPreviewLongEdgePx = m_ghostPreviewEnabled ? std::max(0, m_ghostPreviewLongEdgePx) : 0;
        m_budgetBypassOverlayComposition = false;
        m_budgetPredictivePrefetchAllowed = m_predictiveCachingEnabled;
        m_predictiveLookAroundMs = m_predictiveLookAroundBaseMs;
        m_predictiveSampleStepMs = m_predictiveSampleStepBaseMs;
        m_predictiveCacheMaxFrames = m_predictiveCacheBaseMaxFrames;
        applyPreviewScaleLimitLocked();
        return;
    }
    applyFrameBudgetDecisionLocked(
        m_frameBudgetController->reset(makeFrameBudgetInputLocked(0)));
}

int64_t PreviewController::preferredRenderSleepMsLocked() const {
    if (!m_isPlaying.load()) {
        return 16;
    }

    int targetFps = m_adaptiveFrameDropEnabled
        ? std::max(1, m_budgetPreviewFps)
        : std::max(1, m_targetPreviewFps);
    const int minFps = std::max(1, std::min(m_minPreviewFps, targetFps));
    if (m_thermalManager) {
        targetFps = m_thermalManager->recommendedPreviewFps(targetFps, minFps);
    }
    return std::max<int64_t>(8, frameIntervalForPreviewFps(targetFps));
}

void PreviewController::updateAdaptiveOverloadScoreLocked(int64_t renderCostMs) {
    const int64_t clampedRenderCostMs = std::max<int64_t>(0, renderCostMs);
    const int targetFps = std::max(1, m_targetPreviewFps);
    const int64_t targetIntervalMs = frameIntervalForPreviewFps(targetFps);

    if (!m_adaptiveFrameDropEnabled) {
        resetFrameBudgetLocked();
        return;
    }

    if (m_frameBudgetController) {
        applyFrameBudgetDecisionLocked(
            m_frameBudgetController->update(makeFrameBudgetInputLocked(clampedRenderCostMs)));
    } else {
        const int minFps = std::max(1, std::min(m_minPreviewFps, targetFps));
        const int64_t minIntervalMs = frameIntervalForPreviewFps(minFps);
        if (clampedRenderCostMs > (minIntervalMs + 8)) {
            m_frameDropOverloadScore = std::min(12, m_frameDropOverloadScore + 3);
        } else if (clampedRenderCostMs > (targetIntervalMs + 4)) {
            m_frameDropOverloadScore = std::min(12, m_frameDropOverloadScore + 1);
        } else if (clampedRenderCostMs < std::max<int64_t>(4, targetIntervalMs - 6)) {
            m_frameDropOverloadScore = std::max(0, m_frameDropOverloadScore - 1);
        }
    }

    if (m_thermalManager) {
        m_thermalManager->updatePreviewLoad(
            clampedRenderCostMs,
            targetIntervalMs,
            m_frameDropOverloadScore);
    }
}

bool PreviewController::shouldBypassOverlayCompositionLocked() const {
    return m_adaptiveFrameDropEnabled &&
        m_isPlaying.load() &&
        m_budgetBypassOverlayComposition;
}

int64_t PreviewController::playbackCompositeReuseWindowMsLocked(int64_t approximateFrameMs) const {
    const int64_t frameMs = std::max<int64_t>(1, approximateFrameMs);
    int64_t floorMs = 96;
    if (m_frameDropOverloadScore >= 8) {
        floorMs = 180;
    } else if (m_frameDropOverloadScore >= 5) {
        floorMs = 144;
    } else if (m_frameDropOverloadScore >= 3) {
        floorMs = 120;
    }
    return std::max<int64_t>(floorMs, frameMs * 2);
}

int PreviewController::resolvePreviewScaleLimitLocked() const {
    if (!m_ghostPreviewEnabled) {
        return 0;
    }
    int resolvedLimitPx = std::max(0, m_budgetPreviewLongEdgePx);
    if (m_dynamicPreviewScaleLimitPx > 0) {
        resolvedLimitPx = resolvedLimitPx > 0
            ? std::min(m_dynamicPreviewScaleLimitPx, resolvedLimitPx)
            : m_dynamicPreviewScaleLimitPx;
    }
    if (m_thermalManager) {
        resolvedLimitPx = m_thermalManager->recommendedPreviewLongEdgePx(resolvedLimitPx);
    }
    return resolvedLimitPx;
}

int PreviewController::resolveSuperResolutionPreviewScaleLimitLocked(
    int baseLimitPx,
    int sourceWidth,
    int sourceHeight) const {
    if (baseLimitPx <= 0 || !m_superResolution) {
        return baseLimitPx;
    }
    return m_superResolution->recommendPreviewLongEdgePx(
        sourceWidth,
        sourceHeight,
        m_surfaceWidth,
        m_surfaceHeight,
        baseLimitPx);
}

void PreviewController::applyPreviewScaleLimitLocked() {
    const int baseScaleLimitPx = resolvePreviewScaleLimitLocked();
    if (m_decoder) {
        m_decoder->setPreviewScaleLimit(
            resolveSuperResolutionPreviewScaleLimitLocked(
                baseScaleLimitPx,
                m_videoWidth,
                m_videoHeight));
    }
    for (auto& entry : m_clipDecoders) {
        if (entry.second.decoder) {
            entry.second.decoder->setPreviewScaleLimit(
                resolveSuperResolutionPreviewScaleLimitLocked(
                    baseScaleLimitPx,
                    entry.second.decoder->getWidth(),
                    entry.second.decoder->getHeight()));
        }
    }
    m_prefetchScaleLimitPx =
        resolveSuperResolutionPreviewScaleLimitLocked(
            baseScaleLimitPx,
            m_videoWidth,
            m_videoHeight);
}

void PreviewController::updateAdaptivePreviewScaleLocked(int64_t scrubVelocityMsPerSec) {
    if (!m_ghostPreviewEnabled || !m_adaptiveResolution || m_ghostPreviewLongEdgePx <= 0) {
        if (m_dynamicPreviewScaleLimitPx != 0) {
            m_dynamicPreviewScaleLimitPx = 0;
            applyPreviewScaleLimitLocked();
        }
        return;
    }

    const float scrubSpeed = static_cast<float>(std::max<int64_t>(0, scrubVelocityMsPerSec)) / 1000.0f;
    m_adaptiveResolution->updateScrubSpeed(scrubSpeed);
    const float scaleFactor = std::clamp(m_adaptiveResolution->getScaleFactor(), 0.45f, 1.0f);
    const int desiredScaleLimitPx =
        scaleFactor >= 0.995f
            ? 0
            : std::max(240, static_cast<int>(std::lround(m_ghostPreviewLongEdgePx * scaleFactor)));

    if (desiredScaleLimitPx == m_dynamicPreviewScaleLimitPx) {
        return;
    }

    m_dynamicPreviewScaleLimitPx = desiredScaleLimitPx;
    applyPreviewScaleLimitLocked();
}

PreviewController::ClipPreviewTransform PreviewController::clipPreviewTransformLocked(int clipId) const {
    return m_transformEngine.getPersistedTransform(clipId);
}

bool PreviewController::hasClipPreviewTransformLocked(const std::shared_ptr<Clip>& clip) const {
    if (!clip) {
        return false;
    }
    return m_transformEngine.hasPersistedTransform(static_cast<int>(clip->getId()));
}

void PreviewController::markVisualStateDirtyLocked() {
    if (m_visualStateVersion == std::numeric_limits<uint64_t>::max()) {
        m_visualStateVersion = 1;
        m_lastRenderedVisualStateVersion = 0;
        return;
    }
    ++m_visualStateVersion;
}

void PreviewController::invalidateVisualState() {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    markVisualStateDirtyLocked();
}

void PreviewController::notifyProxyReady(int clipId) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    // Update the clip's proxyPath from the ProxyManager so getPreviewProxyPath() returns
    // the new file on the next decoder open attempt.
    const auto clip = findTimelineClipByIdLocked(clipId);
    if (clip) {
        syncClipProxyPathLocked(clip);
    }
    // Evict the existing decoder for this clip so it re-opens using the proxy path.
    auto it = m_clipDecoders.find(clipId);
    if (it != m_clipDecoders.end()) {
        m_clipDecoders.erase(it);
    }
    // Force a visual state refresh so the next renderFrame() re-decodes from proxy.
    markVisualStateDirtyLocked();
}

float PreviewController::clipPreviewMinZoomLocked(int clipId) const {
    if (clipId <= 0) {
        return 1.0f;
    }
    const auto clip = findTimelineClipByIdLocked(clipId);
    const bool objectClip = usesObjectStylePreviewTransform(clip);
    return objectClip ? 0.15f : 1.0f;
}

float PreviewController::clipPreviewMaxZoomLocked(int clipId) const {
    if (clipId <= 0) {
        return 1.0f;
    }
    const auto clip = findTimelineClipByIdLocked(clipId);
    const bool objectClip = usesObjectStylePreviewTransform(clip);
    return objectClip ? 8.0f : 4.0f;
}

void PreviewController::normalizeClipPreviewTransformLocked(int clipId, ClipPreviewTransform& transform) const {
    const auto clip = findTimelineClipByIdLocked(clipId);
    TransformEngine::Bounds bounds;
    bounds.minScale = clipPreviewMinZoomLocked(clipId);
    bounds.maxScale = clipPreviewMaxZoomLocked(clipId);
    bounds.viewportWidthPx = static_cast<float>(std::max(1, m_surfaceWidth));
    bounds.viewportHeightPx = static_cast<float>(std::max(1, m_surfaceHeight));
    bounds.objectTransform = usesObjectStylePreviewTransform(clip);
    transform = m_transformEngine.normalize(transform, bounds);
}

void PreviewController::setGhostPreviewEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_ghostPreviewEnabled = enabled;
    if (!enabled) {
        m_dynamicPreviewScaleLimitPx = 0;
    }
    resetFrameBudgetLocked();
}

void PreviewController::setGhostPreviewLongEdgePx(int longEdgePx) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_ghostPreviewLongEdgePx = std::max(0, longEdgePx);
    resetFrameBudgetLocked();
}

void PreviewController::setAdaptiveFrameDropPolicy(bool enabled, int targetFps, int minFps) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_adaptiveFrameDropEnabled = enabled;
    m_targetPreviewFps = std::max(1, targetFps);
    m_minPreviewFps = std::max(1, std::min(minFps, m_targetPreviewFps));
    resetFrameBudgetLocked();
}

void PreviewController::setDirtyRegionRedrawEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_dirtyRegionRedrawEnabled = enabled;
}

void PreviewController::setClipPreviewTransform(
    int clipId,
    float zoom,
    float scaleX,
    float scaleY,
    float panXPx,
    float panYPx,
    float rotationDeg,
    bool mirrorX,
    bool immediate) {
    if (clipId <= 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    ClipPreviewTransform target;
    target.zoom = zoom;
    target.scaleX = scaleX;
    target.scaleY = scaleY;
    target.rotationDeg = rotationDeg;
    target.mirrorX = mirrorX;
    target.panXPx = panXPx;
    target.panYPx = panYPx;
    normalizeClipPreviewTransformLocked(clipId, target);
    const auto clip = findTimelineClipByIdLocked(clipId);
    const bool objectClip = usesObjectStylePreviewTransform(clip);
    const float viewportSpan =
        static_cast<float>(std::max(1, std::max(m_surfaceWidth, m_surfaceHeight)));

    auto storeTransform = [&](const ClipPreviewTransform& transform) {
        m_transformEngine.setPersistedTransform(clipId, transform);
    };

    if (immediate) {
        if (target.isIdentity()) {
            m_transformEngine.clearPersistedTransform(clipId);
        } else {
            storeTransform(target);
        }
        markVisualStateDirtyLocked();
        return;
    }

    const auto current = clipPreviewTransformLocked(clipId);
    if (current.isIdentity()) {
        if (target.isIdentity()) {
            m_transformEngine.clearPersistedTransform(clipId);
        } else {
            storeTransform(target);
        }
        markVisualStateDirtyLocked();
        return;
    }

    auto blendFloat = [](float from, float to, float alpha) -> float {
        return from + ((to - from) * alpha);
    };
    auto shortestAngleDelta = [](float fromDeg, float toDeg) -> float {
        float delta = std::fmod((toDeg - fromDeg), 360.0f);
        if (delta > 180.0f) delta -= 360.0f;
        if (delta < -180.0f) delta += 360.0f;
        return delta;
    };

    const float panDeltaPx =
        std::max(std::fabs(target.panXPx - current.panXPx), std::fabs(target.panYPx - current.panYPx));
    const float panDelta = panDeltaPx / viewportSpan;
    const float zoomDelta =
        std::max(
            std::fabs(target.zoom - current.zoom),
            std::max(
                std::fabs(target.scaleX - current.scaleX),
                std::fabs(target.scaleY - current.scaleY)));
    const float rotationDelta = std::fabs(shortestAngleDelta(current.rotationDeg, target.rotationDeg));

    float response = objectClip ? 0.42f : 0.50f;
    if (panDelta < 0.035f && zoomDelta < 0.045f && rotationDelta < 3.5f) {
        response *= 0.82f;
    }
    if (panDelta > 0.45f || zoomDelta > 0.55f || rotationDelta > 24.0f || current.mirrorX != target.mirrorX) {
        response = 1.0f;
    }

    ClipPreviewTransform smoothed;
    smoothed.zoom = blendFloat(current.zoom, target.zoom, response);
    smoothed.scaleX = blendFloat(current.scaleX, target.scaleX, response);
    smoothed.scaleY = blendFloat(current.scaleY, target.scaleY, response);
    smoothed.panXPx = blendFloat(current.panXPx, target.panXPx, response);
    smoothed.panYPx = blendFloat(current.panYPx, target.panYPx, response);
    smoothed.rotationDeg = current.rotationDeg + (shortestAngleDelta(current.rotationDeg, target.rotationDeg) * response);
    smoothed.rotationDeg = std::clamp(smoothed.rotationDeg, -180.0f, 180.0f);
    smoothed.mirrorX = (response >= 0.999f) ? target.mirrorX : current.mirrorX;

    if (target.isIdentity() && smoothed.isIdentity()) {
        m_transformEngine.clearPersistedTransform(clipId);
    } else {
        storeTransform(smoothed);
    }
    markVisualStateDirtyLocked();
}

float PreviewController::getClipPreviewMinZoom(int clipId) {
    if (clipId <= 0) {
        return 1.0f;
    }
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    return clipPreviewMinZoomLocked(clipId);
}

std::array<float, 7> PreviewController::getClipPreviewTransformValues(int clipId) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    ClipPreviewTransform current = clipPreviewTransformLocked(clipId);
    normalizeClipPreviewTransformLocked(clipId, current);
    return {
        current.zoom,
        current.panXPx,
        current.panYPx,
        current.rotationDeg,
        current.mirrorX ? 1.0f : 0.0f,
        current.scaleX,
        current.scaleY,
    };
}

std::array<float, 7> PreviewController::computeNormalizedPreviewTransform(
    int clipId,
    float zoom,
    float scaleX,
    float scaleY,
    float panXPx,
    float panYPx,
    float rotationDeg,
    bool mirrorX) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    ClipPreviewTransform target;
    target.zoom = zoom;
    target.scaleX = scaleX;
    target.scaleY = scaleY;
    target.panXPx = panXPx;
    target.panYPx = panYPx;
    target.rotationDeg = rotationDeg;
    target.mirrorX = mirrorX;
    normalizeClipPreviewTransformLocked(clipId, target);
    return {
        target.zoom,
        target.panXPx,
        target.panYPx,
        target.rotationDeg,
        target.mirrorX ? 1.0f : 0.0f,
        target.scaleX,
        target.scaleY,
    };
}

std::array<float, 3> PreviewController::computeScaleGesturePreviewTransform(
    int clipId,
    float baseZoom,
    float basePanXPx,
    float basePanYPx,
    float scaleAccumulator,
    float focusOffsetXPx,
    float focusOffsetYPx) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    const auto clip = findTimelineClipByIdLocked(clipId);
    const bool objectClip = usesObjectStylePreviewTransform(clip);
    const float clampedAccumulator = std::clamp(scaleAccumulator, 0.15f, 8.0f);
    float tunedAccumulator = clampedAccumulator;
    if (clampedAccumulator >= 1.0f) {
        const float expandGain = objectClip ? 1.24f : 1.24f;
        tunedAccumulator = 1.0f + ((clampedAccumulator - 1.0f) * expandGain);
    } else {
        const float shrinkGain = objectClip ? 2.10f : 1.18f;
        tunedAccumulator = 1.0f - ((1.0f - clampedAccumulator) * shrinkGain);
    }
    ClipPreviewTransform target;
    target.zoom = baseZoom * std::clamp(tunedAccumulator, 0.15f, 8.0f);
    const float zoomRatio = target.zoom / std::max(baseZoom, 0.001f);
    target.panXPx = (basePanXPx * zoomRatio) + ((1.0f - zoomRatio) * focusOffsetXPx);
    target.panYPx = (basePanYPx * zoomRatio) + ((1.0f - zoomRatio) * focusOffsetYPx);
    normalizeClipPreviewTransformLocked(clipId, target);
    return {target.zoom, target.panXPx, target.panYPx};
}

std::array<float, 2> PreviewController::computeDragPanPreviewTransform(
    int clipId,
    float currentZoom,
    float currentPanXPx,
    float currentPanYPx,
    float deltaXPx,
    float deltaYPx) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    ClipPreviewTransform target;
    target.zoom = currentZoom;
    target.panXPx = currentPanXPx + deltaXPx;
    target.panYPx = currentPanYPx + deltaYPx;
    normalizeClipPreviewTransformLocked(clipId, target);
    return {target.panXPx, target.panYPx};
}

std::array<float, 7> PreviewController::beginPreviewTransformGesture(
    int clipId,
    float zoom,
    float scaleX,
    float scaleY,
    float panXPx,
    float panYPx,
    float rotationDeg,
    bool mirrorX,
    float centroidOffsetXPx,
    float centroidOffsetYPx,
    float spanPx,
    float angleDeg,
    int mode,
    float edgeSignX,
    float edgeSignY,
    bool allowRotation) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    TransformEngine::GestureBeginRequest request;
    request.selectedId = clipId;
    request.mode =
        mode == static_cast<int>(TransformEngine::GestureMode::EdgeResize)
            ? TransformEngine::GestureMode::EdgeResize
            : mode == static_cast<int>(TransformEngine::GestureMode::PinchRotate)
                ? TransformEngine::GestureMode::PinchRotate
                : TransformEngine::GestureMode::Drag;
    request.baseTransform.zoom = zoom;
    request.baseTransform.scaleX = scaleX;
    request.baseTransform.scaleY = scaleY;
    request.baseTransform.panXPx = panXPx;
    request.baseTransform.panYPx = panYPx;
    request.baseTransform.rotationDeg = rotationDeg;
    request.baseTransform.mirrorX = mirrorX;
    normalizeClipPreviewTransformLocked(clipId, request.baseTransform);
    request.centroidOffsetXPx = centroidOffsetXPx;
    request.centroidOffsetYPx = centroidOffsetYPx;
    request.spanPx = spanPx;
    request.angleDeg = angleDeg;
    request.edgeSignX = edgeSignX;
    request.edgeSignY = edgeSignY;
    request.allowRotation = allowRotation;
    request.bounds.minScale = clipPreviewMinZoomLocked(clipId);
    request.bounds.maxScale = clipPreviewMaxZoomLocked(clipId);
    request.bounds.viewportWidthPx = static_cast<float>(std::max(1, m_surfaceWidth));
    request.bounds.viewportHeightPx = static_cast<float>(std::max(1, m_surfaceHeight));
    request.bounds.objectTransform =
        usesObjectStylePreviewTransform(findTimelineClipByIdLocked(clipId));
    const auto result = m_transformEngine.beginSession(request);
    if (result.isIdentity()) {
        m_transformEngine.clearPersistedTransform(clipId);
    } else {
        m_transformEngine.setPersistedTransform(clipId, result);
    }
    markVisualStateDirtyLocked();
    return {
        result.zoom,
        result.panXPx,
        result.panYPx,
        result.rotationDeg,
        result.mirrorX ? 1.0f : 0.0f,
        result.scaleX,
        result.scaleY,
    };
}

std::array<float, 7> PreviewController::updatePreviewTransformGesture(
    float centroidOffsetXPx,
    float centroidOffsetYPx,
    float spanPx,
    float angleDeg) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    TransformEngine::GestureUpdateRequest request;
    request.centroidOffsetXPx = centroidOffsetXPx;
    request.centroidOffsetYPx = centroidOffsetYPx;
    request.spanPx = spanPx;
    request.angleDeg = angleDeg;
    const auto result = m_transformEngine.updateSession(request);
    const int clipId = m_transformEngine.activeSessionClipId();
    if (clipId > 0) {
        if (result.isIdentity()) {
            m_transformEngine.clearPersistedTransform(clipId);
        } else {
            m_transformEngine.setPersistedTransform(clipId, result);
        }
        markVisualStateDirtyLocked();
    }
    return {
        result.zoom,
        result.panXPx,
        result.panYPx,
        result.rotationDeg,
        result.mirrorX ? 1.0f : 0.0f,
        result.scaleX,
        result.scaleY,
    };
}

void PreviewController::endPreviewTransformGesture() {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_transformEngine.endSession();
    markVisualStateDirtyLocked();
}

std::array<float, 3> PreviewController::computeCornerHandlePreviewTransform(
    int clipId,
    float baseZoom,
    float basePanXPx,
    float basePanYPx,
    float deltaXPx,
    float deltaYPx,
    float cornerSignX,
    float cornerSignY) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    const auto clip = findTimelineClipByIdLocked(clipId);
    const bool objectClip = usesObjectStylePreviewTransform(clip);
    const float viewportSpan =
        static_cast<float>(std::max(1, std::max(m_surfaceWidth, m_surfaceHeight)));
    const float signedRadialDeltaPx = (deltaXPx * cornerSignX) + (deltaYPx * cornerSignY);
    const float normalizedDelta =
        signedRadialDeltaPx / std::max(1.0f, viewportSpan * (objectClip ? 0.42f : 0.58f));

    float scaleAccumulator;
    if (normalizedDelta >= 0.0f) {
        scaleAccumulator = 1.0f + (normalizedDelta * (objectClip ? 1.95f : 1.35f));
    } else {
        scaleAccumulator = 1.0f + (normalizedDelta * (objectClip ? 1.55f : 1.05f));
    }

    ClipPreviewTransform target;
    target.zoom = baseZoom * std::clamp(scaleAccumulator, 0.15f, 8.0f);
    const float edgeFollowGain = objectClip ? 0.92f : 0.46f;
    target.panXPx = basePanXPx + ((cornerSignX == 0.0f ? 0.0f : deltaXPx) * edgeFollowGain);
    target.panYPx = basePanYPx + ((cornerSignY == 0.0f ? 0.0f : deltaYPx) * edgeFollowGain);
    normalizeClipPreviewTransformLocked(clipId, target);
    return {target.zoom, target.panXPx, target.panYPx};
}

std::array<float, 3> PreviewController::computeDoubleTapPreviewTransform(
    int clipId,
    float currentZoom,
    float currentPanXPx,
    float currentPanYPx,
    float tapOffsetXPx,
    float tapOffsetYPx) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    const float minZoom = clipPreviewMinZoomLocked(clipId);
    const float targetZoom =
        currentZoom < (minZoom + 0.05f) ? std::max(1.15f, minZoom + 0.35f) :
        currentZoom < 1.75f ? 2.0f :
        currentZoom < 2.45f ? 2.75f : minZoom;
    ClipPreviewTransform target;
    if (std::fabs(targetZoom - minZoom) <= 0.001f) {
        target.zoom = minZoom;
        target.panXPx = 0.0f;
        target.panYPx = 0.0f;
    } else {
        target.zoom = targetZoom;
        const float zoomRatio = target.zoom / std::max(currentZoom, 0.001f);
        target.panXPx = (currentPanXPx * zoomRatio) + ((1.0f - zoomRatio) * tapOffsetXPx);
        target.panYPx = (currentPanYPx * zoomRatio) + ((1.0f - zoomRatio) * tapOffsetYPx);
    }
    normalizeClipPreviewTransformLocked(clipId, target);
    return {target.zoom, target.panXPx, target.panYPx};
}

void PreviewController::clearClipPreviewTransform(int clipId) {
    if (clipId <= 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_transformEngine.clearPersistedTransform(clipId);
    if (m_transformEngine.activeSessionClipId() == clipId) {
        m_transformEngine.endSession();
    }
    markVisualStateDirtyLocked();
}

void PreviewController::clearClipPreviewTransforms() {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_transformEngine.clearPersistedTransforms();
    m_transformEngine.endSession();
    markVisualStateDirtyLocked();
}

void PreviewController::upsertTransition(
    int64_t transitionId,
    int outgoingClipId,
    int incomingClipId,
    int typeId,
    int durationMs,
    int64_t startTimeMs) {
    if (transitionId <= 0 || outgoingClipId <= 0 || incomingClipId <= 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    ClipTransition transition;
    transition.id = transitionId;
    transition.outgoingClipId = outgoingClipId;
    transition.incomingClipId = incomingClipId;
    transition.typeId = VideoEngine::Advanced::TransitionEngine::normalizeTypeId(typeId);
    transition.durationMs = std::max(1, durationMs);
    transition.startTimeMs = std::max<int64_t>(0, startTimeMs);
    transition.enabled = true;
    m_transitions[transitionId] = transition;
    rebuildSmartCacheHintsLocked();
    markVisualStateDirtyLocked();
}

void PreviewController::removeTransition(int64_t transitionId) {
    if (transitionId <= 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_transitions.erase(transitionId);
    rebuildSmartCacheHintsLocked();
    markVisualStateDirtyLocked();
}

bool PreviewController::setClipEffects(
    int clipId,
    float brightness,
    float contrast,
    float saturation) {
    if (clipId <= 0) {
        return false;
    }
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    if (!m_timeline) {
        return false;
    }
    for (const auto& clip : m_timeline->clips()) {
        if (!clip || static_cast<int>(clip->getId()) != clipId) {
            continue;
        }
        clip->setEffectBrightness(brightness);
        clip->setEffectContrast(contrast);
        clip->setEffectSaturation(saturation);
        clip->setEffectsEnabled(true);
        markVisualStateDirtyLocked();
        return true;
    }
    return false;
}

void PreviewController::resetTimelinePreviewState() {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    stopDecodeWorkerLocked();
    clearQueuedFramesLocked();
    cancelPredictivePrefetchLocked(true);

    if (m_decoder) {
        m_decoder->close();
        m_decoder.reset();
    }
    m_openVideoPath.clear();

    for (auto& [id, state] : m_clipDecoders) {
        if (state.decoder) {
            state.decoder->close();
        }
        if (state.texture) {
            state.texture->release();
        }
    }
    m_clipDecoders.clear();
    m_transformEngine.clearPersistedTransforms();
    m_transformEngine.endSession();
    m_transitions.clear();

    m_hasDecodedFrame = false;
    m_visualStateVersion = 1;
    m_lastRenderedVisualStateVersion = 0;
    m_hasLastScrubRequestSample = false;
    m_lastRenderedClipId = -1;
    m_lastRenderedSourceMs = -1;
    m_chromaKey = {};
    rebuildSmartCacheHintsLocked();
}

void PreviewController::setPredictiveCachingPolicy(
    bool enabled,
    int lookAroundMs,
    int sampleStepMs,
    int cacheMaxFrames) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_predictiveCachingEnabled = enabled;
    m_predictiveLookAroundBaseMs = std::clamp(lookAroundMs, 200, 8000);
    m_predictiveSampleStepBaseMs = std::clamp(sampleStepMs, 16, 1000);
    m_predictiveCacheBaseMaxFrames = std::clamp(cacheMaxFrames, 4, 240);
    resetFrameBudgetLocked();
    if (!m_predictiveCachingEnabled) {
        clearPredictiveCacheLocked();
    }
}

void PreviewController::setAudioMasterClockEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_audioMasterClockEnabled = enabled;
    if (!enabled) {
        m_audioMasterClockValid = false;
        if (m_audioSyncEngine) {
            m_audioSyncEngine = std::make_unique<VideoEngine::DeepPro::AudioEnginePro>();
        }
    }
}

void PreviewController::updateAudioMasterClockUs(int64_t ptsUs) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    const int64_t clampedPtsUs = std::max<int64_t>(0, ptsUs);
    const auto now = std::chrono::steady_clock::now();
    const auto anchorElapsedUs = std::chrono::duration_cast<std::chrono::microseconds>(
        now - m_playbackAnchorWallClock).count();
    const int64_t expectedVideoPtsUs = std::max<int64_t>(
        0,
        (m_playbackAnchorTimeMs * 1000LL) + anchorElapsedUs);
    if (m_audioSyncEngine) {
        m_audioSyncEngine->observeClocks(clampedPtsUs, expectedVideoPtsUs);
        m_audioMasterClockUs = std::max<int64_t>(
            0,
            m_audioSyncEngine->smoothedAudioClockUs());
    } else {
        m_audioMasterClockUs = clampedPtsUs;
    }
    m_audioMasterClockWallClock = std::chrono::steady_clock::now();
    m_audioMasterClockValid = true;
}

bool PreviewController::redrawCachedFrame() {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    return redrawTextureLocked(0, 0, m_surfaceWidth, m_surfaceHeight, false);
}

bool PreviewController::redrawCachedFrameRegion(int x, int y, int width, int height) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    return redrawTextureLocked(x, y, width, height, true);
}

bool PreviewController::redrawTextureLocked(
    int x,
    int y,
    int width,
    int height,
    bool useDirtyRegion) {
    if (!m_renderer || !m_texture || !m_texture->isValid() || !m_hasDecodedFrame) {
        setError("No cached frame available for redraw");
        return false;
    }

    if (!m_renderer->acquireContext()) {
        setError("Render context acquire failed: %s",
                 m_renderer ? m_renderer->getLastError() : "renderer missing");
        return false;
    }

    const auto& chroma = m_chromaKey;
    m_renderer->setChromaKey(
        chroma.enabled,
        chroma.color == Clip::ChromaKeyParams::KeyColor::Blue,
        chroma.similarity,
        chroma.smoothness,
        chroma.spill
    );

    bool ok = false;
    if (useDirtyRegion && m_dirtyRegionRedrawEnabled && width > 0 && height > 0) {
        ok = m_renderer->renderFrameRegion(*m_texture, x, y, width, height);
    } else {
        ok = m_renderer->renderFrame(*m_texture);
    }

    m_renderer->releaseContext();
    if (!ok) {
        setError("Cached frame redraw failed: %s", m_renderer->getLastError());
        return false;
    }
    m_lastRenderedVisualStateVersion = m_visualStateVersion;
    return true;
}

void PreviewController::cachePredictiveFrameLocked(DecodedFrame&& frame, int64_t ptsMs) {
    if (!m_predictiveCachingEnabled || frame.rgb.empty()) {
        return;
    }
    const int64_t key = clampTimeMs(ptsMs);
    auto it = m_predictiveFrameCache.find(key);
    if (it == m_predictiveFrameCache.end()) {
        m_predictiveFrameOrder.push_back(key);
        m_predictiveFrameCache.emplace(key, std::move(frame));
    } else {
        it->second = std::move(frame);
    }

    while (static_cast<int>(m_predictiveFrameOrder.size()) > m_predictiveCacheMaxFrames) {
        const int64_t evictKey = m_predictiveFrameOrder.front();
        m_predictiveFrameOrder.pop_front();
        m_predictiveFrameCache.erase(evictKey);
    }
}

bool PreviewController::tryRenderFromPredictiveCacheLocked(
    int64_t requestTimeMs,
    int64_t* renderedTimeMs,
    bool presentFrame) {
    if (m_predictiveFrameCache.empty()) {
        return false;
    }
    const int64_t toleranceMs = std::max<int64_t>(
        80,
        static_cast<int64_t>(m_predictiveSampleStepMs * 2));
    auto right = m_predictiveFrameCache.lower_bound(requestTimeMs);
    auto best = m_predictiveFrameCache.end();
    int64_t bestDelta = std::numeric_limits<int64_t>::max();

    if (right != m_predictiveFrameCache.end()) {
        best = right;
        bestDelta = std::llabs(right->first - requestTimeMs);
    }
    if (right != m_predictiveFrameCache.begin()) {
        auto left = std::prev(right);
        const int64_t delta = std::llabs(left->first - requestTimeMs);
        if (delta < bestDelta) {
            best = left;
            bestDelta = delta;
        }
    }
    if (best == m_predictiveFrameCache.end() || bestDelta > toleranceMs) {
        return false;
    }

    const int64_t pts = best->first;
    if (!renderDecodedFrame(best->second, pts, presentFrame)) {
        return false;
    }
    if (renderedTimeMs) {
        *renderedTimeMs = pts;
    }
    return true;
}

void PreviewController::clearPredictiveCacheLocked() {
    m_predictiveFrameCache.clear();
    m_predictiveFrameOrder.clear();
}

void PreviewController::cancelPredictivePrefetchLocked(bool clearCache) {
    {
        std::lock_guard<std::mutex> prefetchLock(m_prefetchMutex);
        m_prefetchCenterMs = 0;
        m_prefetchVideoPath.clear();
        ++m_prefetchRequestedGeneration;
    }
    if (clearCache) {
        clearPredictiveCacheLocked();
    }
    m_prefetchCv.notify_one();
}

void PreviewController::requestPredictivePrefetchLocked(int64_t centerMs) {
    if (!m_predictiveCachingEnabled ||
        !m_budgetPredictivePrefetchAllowed ||
        m_openVideoPath.empty()) {
        return;
    }
    const int64_t clampedCenterMs = clampTimeMs(centerMs);
    const int scaleLimitPx = resolvePreviewScaleLimitLocked();
    const int64_t requestStepMs = std::max<int64_t>(24, m_predictiveSampleStepMs / 2);

    {
        std::lock_guard<std::mutex> prefetchLock(m_prefetchMutex);
        if (m_prefetchVideoPath == m_openVideoPath &&
            std::llabs(m_prefetchCenterMs - clampedCenterMs) < requestStepMs &&
            m_prefetchLookAroundMs == m_predictiveLookAroundMs &&
            m_prefetchSampleStepMs == m_predictiveSampleStepMs &&
            m_prefetchScaleLimitPx == scaleLimitPx) {
            return;
        }
        m_prefetchCenterMs = clampedCenterMs;
        m_prefetchVideoPath = m_openVideoPath;
        m_prefetchLookAroundMs = m_predictiveLookAroundMs;
        m_prefetchSampleStepMs = m_predictiveSampleStepMs;
        m_prefetchScaleLimitPx = scaleLimitPx;
        ++m_prefetchRequestedGeneration;
    }
    m_prefetchCv.notify_one();
}

void PreviewController::predictivePrefetchLoop() {
    while (true) {
        int64_t centerMs = 0;
        std::string videoPath;
        int lookAroundMs = 0;
        int sampleStepMs = 0;
        int scaleLimitPx = 0;
        uint64_t generation = 0;

        {
            std::unique_lock<std::mutex> lock(m_prefetchMutex);
            m_prefetchCv.wait(lock, [&]() {
                return m_prefetchExit || m_prefetchRequestedGeneration != m_prefetchProcessedGeneration;
            });
            if (m_prefetchExit) {
                return;
            }
            m_prefetchProcessedGeneration = m_prefetchRequestedGeneration;
            generation = m_prefetchProcessedGeneration;
            centerMs = m_prefetchCenterMs;
            videoPath = m_prefetchVideoPath;
            lookAroundMs = m_prefetchLookAroundMs;
            sampleStepMs = m_prefetchSampleStepMs;
            scaleLimitPx = m_prefetchScaleLimitPx;
        }

        if (videoPath.empty()) {
            continue;
        }

        VideoDecoder decoder;
        if (!decoder.open(videoPath)) {
            continue;
        }
        decoder.setPreviewScaleLimit(
            resolveSuperResolutionPreviewScaleLimitLocked(
                scaleLimitPx,
                decoder.getWidth(),
                decoder.getHeight()));

        const int stepMs = std::max(16, sampleStepMs);
        for (int offset = -lookAroundMs; offset <= lookAroundMs; offset += stepMs) {
            {
                std::lock_guard<std::mutex> lock(m_prefetchMutex);
                if (m_prefetchExit || generation != m_prefetchRequestedGeneration) {
                    break;
                }
            }

            const int64_t targetMs = std::max<int64_t>(0, centerMs + offset);
            if (!decoder.seekForPreview(targetMs)) {
                continue;
            }
            DecodedFrame frame;
            if (!decoder.decodeNextFrame(frame)) {
                continue;
            }
            const int64_t ptsMs = frame.ptsMs > 0 ? frame.ptsMs : targetMs;
            {
                std::lock_guard<std::mutex> lock(m_playbackMutex);
                cachePredictiveFrameLocked(std::move(frame), clampTimeMs(ptsMs));
            }
        }

        decoder.close();
    }
}

void PreviewController::stopPredictivePrefetchWorker() {
    {
        std::lock_guard<std::mutex> lock(m_prefetchMutex);
        m_prefetchExit = true;
    }
    m_prefetchCv.notify_all();
    if (m_prefetchThread.joinable()) {
        m_prefetchThread.join();
    }
}


}  // namespace VideoEngine

namespace VideoEngine {
void PreviewController::setLoopingLiveTransitionsEnabled(bool enabled) { m_loopingLiveTransitionsEnabled = enabled; }
void PreviewController::setAdaptiveProxyEnabled(bool enabled) { m_adaptiveProxyEnabled = enabled; }
}  // namespace VideoEngine

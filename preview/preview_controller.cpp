#include "preview_controller.h"

#include "backend/ffmpeg/video_decoder.h"
#include "backend/ffmpeg/frame_converter.h"
#include "gpu/gl_texture.h"
#include "gpu/egl_renderer.h"
#include "core/timeline.h"
#include "core/clip.h"
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

std::string resolvePreviewDecoderPath(const VideoEngine::Clip* clip) {
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
    , m_audioMasterClockWallClock(std::chrono::steady_clock::now())
    , m_lastScrubRequestWallClock(std::chrono::steady_clock::now())
{
    m_timeline = std::make_shared<Timeline>();
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
        m_frameDropOverloadScore = 0;
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
    m_decoder->setPreviewScaleLimit(m_ghostPreviewEnabled ? m_ghostPreviewLongEdgePx : 0);
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

    if (m_renderer && m_texture) {
        if (!m_renderer->acquireContext()) {
            setError("Render context acquire failed while switching source");
            return false;
        }
        const bool ok = m_texture->initialize(m_videoWidth, m_videoHeight);
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
        m_renderer->shutdown();
        m_renderer = nullptr;
    }
    if (m_texture) {
        m_texture->release();
        m_texture = nullptr;
    }

    // Create renderer — retry on EGL_BAD_ALLOC (0x3003) conflict
    // 0x3003 happens when previous render thread hasn't released EGL surface yet
    for (int attempt = 0; attempt < 5; ++attempt) {
        m_renderer = std::make_unique<EGLRenderer>();
        if (m_renderer->initialize(window)) break;
        const std::string err = m_renderer->getLastError();
        m_renderer = nullptr;
        if (err.find("0x3003") != std::string::npos) {
            // Wait for previous EGL surface to be released
            std::this_thread::sleep_for(std::chrono::milliseconds(100 * (attempt + 1)));
            continue;
        }
        setError("EGL initialization failed: %s", err.c_str());
        return false;
    }
    if (!m_renderer) {
        setError("EGL initialization failed after retry");
        return false;
    }

    // Create texture with fallback size if video not loaded yet
    const int texW = m_videoWidth > 0 ? m_videoWidth : 1280;
    const int texH = m_videoHeight > 0 ? m_videoHeight : 720;
    m_texture = std::make_unique<GLTexture>();
    if (!m_texture->initialize(texW, texH)) {
        setError("Texture initialization failed");
        m_renderer->shutdown();
        m_renderer = nullptr;
        m_texture = nullptr;
        return false;
    }

    m_renderer->releaseContext();

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

    if (m_renderer) {
        m_renderer->shutdown();
        m_renderer = nullptr;
    }

    if (m_texture) {
        m_texture->release();
        m_texture = nullptr;
    }

    m_nativeWindow = nullptr;
    std::cout << "[PreviewController] Surface detached\n";
}

void PreviewController::start() {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    if (m_isPlaying.load()) {
        return;
    }

    if (!m_renderer || !m_texture || !m_converter) {
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
    m_frameDropOverloadScore = 0;
    cancelPredictivePrefetchLocked(false);
    stopDecodeWorkerLocked();
    clearQueuedFramesLocked();
    std::cout << "[PreviewController] Playback started\n";
    clearError();
}

void PreviewController::stop() {
    m_isPlaying.store(false);
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    cancelPredictivePrefetchLocked(false);
    stopDecodeWorkerLocked();
    clearQueuedFramesLocked();
    m_frameDropOverloadScore = 0;
    std::cout << "[PreviewController] Playback stopped\n";
}

void PreviewController::seekTo(int64_t timeMs) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    if (!m_renderer || !m_texture || !m_converter) {
        setError("Components not initialized");
        return;
    }
    const int64_t clampedTimeMs = clampTimelineTimeMsLocked(timeMs);
    m_isPlaying.store(false);
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
    if (!m_renderer || !m_texture || !m_converter) {
        setError(
            "Components not initialized (renderer=%d texture=%d converter=%d)",
            m_renderer ? 1 : 0,
            m_texture ? 1 : 0,
            m_converter ? 1 : 0);
        return false;
    }

    m_isPlaying.store(false);
    cancelPredictivePrefetchLocked(false);
    stopDecodeWorkerLocked();
    clearQueuedFramesLocked();

    const int64_t clampedTimeMs = clampTimelineTimeMsLocked(timeMs);
    int64_t renderedTimelineMs = clampedTimeMs;
    if (!renderTimelineFrameLocked(
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
    m_frameDropOverloadScore = 0;
    return true;
}

bool PreviewController::renderFrame() {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    if (!m_isPlaying.load()) {
        return false;
    }

    if (!m_renderer || !m_texture || !m_converter) {
        setError("Components not initialized");
        return false;
    }
    return processFrame();
}

bool PreviewController::processFrame() {
    const int64_t targetTimelineMs = playbackTimelineTimeMsLocked();
    const int64_t timelineDurationMs =
        (m_timeline && m_timeline->getDuration() > 0) ? m_timeline->getDuration() : m_videoDurationMs;
    if (timelineDurationMs > 0 && targetTimelineMs >= timelineDurationMs) {
        m_currentTimeMs.store(clampTimelineTimeMsLocked(targetTimelineMs));
        m_isPlaying.store(false);
        clearError();
        std::cout << "[PreviewController] End of timeline\n";
        return false;
    }

    int64_t renderedTimelineMs = targetTimelineMs;
    const auto renderStartedAt = std::chrono::steady_clock::now();
    const bool rendered = renderTimelineFrameLocked(
        targetTimelineMs,
        /*keyframeOnlyScrub=*/false,
        /*allowPredictiveCache=*/false,
        /*updatePredictiveCache=*/false,
        &renderedTimelineMs);
    const int64_t renderCostMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - renderStartedAt).count();
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
    {
        std::lock_guard<std::mutex> lock(m_playbackMutex);
        stopDecodeWorkerLocked();
        clearQueuedFramesLocked();
        clearPredictiveCacheLocked();

        if (m_renderer) {
            m_renderer->shutdown();
            m_renderer = nullptr;
        }

        if (m_texture) {
            m_texture->release();
            m_texture = nullptr;
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

void PreviewController::scrubToTimelineTime(int64_t timelineMs) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    if (!m_renderer || !m_texture || !m_converter) {
        setError("Components not initialized");
        return;
    }

    stopDecodeWorkerLocked();
    clearQueuedFramesLocked();

    m_isPlaying.store(false);
    m_reachedEos.store(false);

    const int64_t clampedTimelineMs = clampTimelineTimeMsLocked(timelineMs);
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
    m_frameDropOverloadScore = 0;

}

bool PreviewController::shouldUseKeyframeOnlyScrubLocked(int64_t requestTimelineMs) {
    const auto now = std::chrono::steady_clock::now();
    bool useKeyframeOnly = false;
    if (m_hasLastScrubRequestSample) {
        const int64_t deltaTimelineMs = std::llabs(requestTimelineMs - m_lastScrubRequestTimelineMs);
        const int64_t deltaWallMs = std::max<int64_t>(
            1,
            std::chrono::duration_cast<std::chrono::milliseconds>(
                now - m_lastScrubRequestWallClock).count());
        const int64_t velocityMsPerSec = (deltaTimelineMs * 1000) / deltaWallMs;
        useKeyframeOnly =
            deltaTimelineMs >= m_keyframeScrubMinDeltaMs &&
            velocityMsPerSec >= m_keyframeScrubVelocityThresholdMsPerSec;
    }
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
        const double localProgress = clipDuration > 1
            ? static_cast<double>(clampedLocalMs) / static_cast<double>(clipDuration - 1)
            : 0.0;
        const double t = std::clamp(localProgress, 0.0, 1.0);

        double shaped = t;
        const float clampedCurveStrength = std::clamp(props.curveSpeedStrength, 0.1f, 4.0f);
        if (props.curveSpeedProfile == "ease_in") {
            const double gamma = 1.0 + (std::max(0.0f, clampedCurveStrength - 1.0f) * 1.35);
            shaped = std::pow(t, gamma);
        } else if (props.curveSpeedProfile == "ease_out") {
            const double gamma = 1.0 + (std::max(0.0f, clampedCurveStrength - 1.0f) * 1.35);
            shaped = 1.0 - std::pow(1.0 - t, gamma);
        } else if (props.curveSpeedProfile == "ease_in_out") {
            // Smoothstep curve for gentle accel/decel.
            shaped = t * t * (3.0 - 2.0 * t);
        } else if (props.curveSpeedProfile == "hyperlapse") {
            const double alpha = std::clamp(1.4 - (clampedCurveStrength * 0.15), 0.55, 1.4);
            shaped = std::pow(t, alpha);
        }

        const int64_t trimmedSpanMs = std::max<int64_t>(1, sourceOutMs - sourceInMs);
        const int64_t curveSourceMs = sourceInMs + static_cast<int64_t>(
            std::llround(shaped * static_cast<double>(trimmedSpanMs - 1)));
        const double playbackSpeed = std::max(0.1f, props.playbackSpeed);
        const int64_t speedSourceMs = sourceInMs + static_cast<int64_t>(
            std::llround(static_cast<double>(clampedLocalMs) * playbackSpeed));

        int64_t mappedSourceMs = speedSourceMs;
        if (props.curveSpeedProfile != "linear") {
            const double blend = std::clamp(
                static_cast<double>(clampedCurveStrength - 0.1f) / 3.9,
                0.15,
                0.9);
            mappedSourceMs = static_cast<int64_t>(
                std::llround((1.0 - blend) * static_cast<double>(speedSourceMs) +
                             blend * static_cast<double>(curveSourceMs)));
        }
        mappedSourceMs = std::clamp<int64_t>(mappedSourceMs, sourceInMs, sourceOutMs - 1);

        if (props.reversePlayback) {
            mappedSourceMs = sourceOutMs - 1 - (mappedSourceMs - sourceInMs);
            mappedSourceMs = std::clamp<int64_t>(mappedSourceMs, sourceInMs, sourceOutMs - 1);
        }
        return mappedSourceMs;
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
    const std::string decoderPath = resolvePreviewDecoderPath(clip.get());
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

    m_decoder->setPreviewScaleLimit(m_ghostPreviewEnabled ? m_ghostPreviewLongEdgePx : 0);
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

    if (m_renderer && m_texture) {
        if (!m_renderer->acquireContext()) {
            setError("Render context acquire failed while switching source");
            return false;
        }
        const bool ok = m_texture->initialize(m_videoWidth, m_videoHeight);
        m_renderer->releaseContext();
        if (!ok) {
            setError("Texture resize failed while switching source");
            return false;
        }
    }
    return true;
}

bool PreviewController::renderTimelineFrameLocked(
    int64_t timelineMs,
    bool keyframeOnlyScrub,
    bool allowPredictiveCache,
    bool updatePredictiveCache,
    int64_t* renderedTimelineMs) {
    if (!m_renderer || !m_texture || !m_converter) {
        setError("Components not initialized");
        return false;
    }

    const int64_t clampedTimelineMs = clampTimelineTimeMsLocked(timelineMs);
    std::shared_ptr<Clip> activeClip;
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
    if (shouldCompositeAdditionalLayers) {
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
        if (renderedTimelineMs) {
            *renderedTimelineMs = clampedTimelineMs;
        }
        return true;
    }

    const bool canReuseStillImageFrame =
        activeClip &&
        activeClip->getMediaType() == Clip::MediaType::Image &&
        m_hasDecodedFrame &&
        m_texture &&
        m_texture->isValid() &&
        activeClipId > 0 &&
        m_lastRenderedClipId == activeClipId &&
        sourceSeekMs == 0;
    const bool reuseStillImageFrameForComposite =
        canReuseStillImageFrame && shouldCompositeAdditionalLayers;
    if (canReuseStillImageFrame) {
        m_lastRenderedClipId = activeClipId;
        m_lastRenderedSourceMs = 0;
        if (renderedTimelineMs) {
            *renderedTimelineMs = clampedTimelineMs;
        }
        if (!shouldCompositeAdditionalLayers) {
            return redrawTextureLocked(0, 0, m_surfaceWidth, m_surfaceHeight, false);
        }
    }

    int64_t renderedSourceMs = sourceSeekMs;
    bool renderedFromPredictiveCache = reuseStillImageFrameForComposite;
    const bool isStillImageClip =
        activeClip && activeClip->getMediaType() == Clip::MediaType::Image;
    if (isStillImageClip && !canReuseStillImageFrame) {
        if (!m_decoder) {
            setError("Image decoder unavailable");
            return false;
        }
        DecodedFrame decodedFrame;
        std::string decodeError;
        if (!decodeStillImageFrame(
                m_decoder.get(),
                resolvePreviewDecoderPath(activeClip.get()),
                m_ghostPreviewEnabled ? m_ghostPreviewLongEdgePx : 0,
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
        if (!renderedFromPredictiveCache && predictiveAllowed) {
            renderedFromPredictiveCache =
                tryRenderFromPredictiveCacheLocked(
                    sourceSeekMs,
                    &renderedSourceMs,
                    /*presentFrame=*/!shouldCompositeAdditionalLayers);
        }

        const int64_t forwardDeltaMs = sourceSeekMs - m_lastRenderedSourceMs;
        const bool allowSequentialDecode =
            !renderedFromPredictiveCache &&
            !keyframeOnlyScrub &&
            !predictiveAllowed &&
            !updatePredictiveCache &&
            activeClipId > 0 &&
            linearForwardMapping &&
            m_lastRenderedClipId == activeClipId &&
            m_lastRenderedSourceMs >= 0 &&
            forwardDeltaMs >= 0 &&
            forwardDeltaMs <= 420;

        if (allowSequentialDecode) {
            DecodedFrame decodedFrame;
            bool renderedSequential = false;
            for (int attempt = 0; attempt < 6; ++attempt) {
                if (!m_decoder || !m_decoder->decodeNextFrame(decodedFrame)) {
                    break;
                }
                const int64_t frameSourceMs = decodedFrame.ptsMs > 0
                    ? clampTimeMs(decodedFrame.ptsMs)
                    : clampTimeMs(
                          m_lastRenderedSourceMs +
                          static_cast<int64_t>(std::llround(m_frameIntervalMs)));
                renderedSourceMs = frameSourceMs;
                if (frameSourceMs + 1 >= sourceSeekMs || attempt == 5) {
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
                    *renderedTimelineMs = clampedTimelineMs;
                }
                return true;
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
    if (updatePredictiveCache && !shouldCompositeAdditionalLayers && !m_isPlaying.load()) {
        requestPredictivePrefetchLocked(renderedSourceMs);
    }
    if (renderedTimelineMs) {
        *renderedTimelineMs = clampedTimelineMs;
    }

    // ---- Multi-track compositing: render remaining visual clips on top ----
    if (shouldCompositeAdditionalLayers) {
        std::vector<GPU::EGLRenderer::Layer> layers;

        // Base layer: already rendered into m_texture.
        if (m_texture && m_texture->isValid()) {
            GPU::EGLRenderer::Layer base;
            base.texture = m_texture.get();
            base.opacity = activeClip ? activeClip->getProperties().opacity : 1.0f;
            if (activeClip) {
                const auto transform = clipPreviewTransformLocked(static_cast<int>(activeClip->getId()));
                base.zoom = transform.zoom;
                base.panXNorm = transform.panXNorm;
                base.panYNorm = transform.panYNorm;
                base.rotationDeg = transform.rotationDeg;
                base.mirrorX = transform.mirrorX;
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

        // Remaining visual layers: decode each clip at the current time.
        for (const auto& clip : activeCompositeClips) {
            const int clipId = static_cast<int>(clip->getId());
            auto& state = m_clipDecoders[clipId];

            const int64_t sourceMs = clampTimeMs(
                mapClipTimelineToSourceMs(clip, clampedTimelineMs, false));
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

            // Upload to per-clip texture
            const int fw = static_cast<int>(frame.width);
            const int fh = static_cast<int>(frame.height);
            if (fw <= 0 || fh <= 0 || frame.rgb.empty()) continue;

            if (!state.texture) {
                state.texture = std::make_unique<GPU::GLTexture>();
            }
            if (!state.texture->initialize(fw, fh)) {
                continue;
            }

            // Convert RGB24 → RGBA if needed
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
                    dst[i*4+0] = src[i*3+0];
                    dst[i*4+1] = src[i*3+1];
                    dst[i*4+2] = src[i*3+2];
                    dst[i*4+3] = 0xFF;
                }
                rgba = scratch.data();
            }
            if (!rgba) {
                continue;
            }

            state.texture->update(rgba);
            state.lastRenderedSourceMs = overlayRenderedSourceMs;

            GPU::EGLRenderer::Layer layer;
            layer.texture = state.texture.get();
            layer.opacity = clip->getProperties().opacity;
            const auto transform = clipPreviewTransformLocked(clipId);
            layer.zoom = transform.zoom;
            layer.panXNorm = transform.panXNorm;
            layer.panYNorm = transform.panYNorm;
            layer.rotationDeg = transform.rotationDeg;
            layer.mirrorX = transform.mirrorX;
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
            const bool composited = m_renderer->renderLayers(layers);
            if (!composited) {
                if (compositeContextReady) {
                    m_renderer->releaseContext();
                }
                setError("Overlay composite failed: %s", m_renderer->getLastError());
                return false;
            }
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

    if (!m_texture || !m_texture->initialize(frameWidth, frameHeight)) {
        m_renderer->releaseContext();
        setError("Texture initialization failed for %dx%d frame", frameWidth, frameHeight);
        return false;
    }
    m_texture->update(rgbaPixels);

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

    const std::string& mediaPath = clip->getMediaPath();
    const std::string decoderPath = resolvePreviewDecoderPath(clip.get());
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
        state.decoder->setPreviewScaleLimit(m_ghostPreviewEnabled ? m_ghostPreviewLongEdgePx : 0);
        state.openPath = decoderPath;
        state.mediaDurationMs = static_cast<int64_t>(state.decoder->getDuration() * 1000.0);
        state.texture.reset();
        state.lastRenderedSourceMs = -1;
        state.hasLastDecodedFrame = false;
        const double fps = state.decoder->getFps();
        state.approximateFrameMs = fps > 0.0
            ? std::max<int64_t>(1, static_cast<int64_t>(std::llround(1000.0 / fps)))
            : static_cast<int64_t>(std::max(1.0, m_frameIntervalMs));
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
                m_ghostPreviewEnabled ? m_ghostPreviewLongEdgePx : 0,
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

        if (!keyframeOnlyScrub &&
            linearForwardMapping &&
            deltaMs > 0 &&
            deltaMs <= state.sequentialDecodeWindowMs) {
            Backend::DecodedFrame candidate;
            while (state.decoder->decodeNextFrame(candidate)) {
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
        const int64_t audioClockAgeMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            now - m_audioMasterClockWallClock).count();
        if (audioClockAgeMs >= 0 && audioClockAgeMs <= m_audioMasterClockStaleAfterMs) {
            return clampTimelineTimeMsLocked(std::max<int64_t>(0, m_audioMasterClockUs / 1000));
        }
    }
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        now - m_playbackAnchorWallClock);
    return clampTimelineTimeMsLocked(m_playbackAnchorTimeMs + elapsed.count());
}

int64_t PreviewController::preferredRenderSleepMsLocked() const {
    if (!m_isPlaying.load()) {
        return 16;
    }

    const int targetFps = std::max(1, m_targetPreviewFps);
    const int minFps = std::max(1, std::min(m_minPreviewFps, targetFps));
    const int64_t targetIntervalMs = frameIntervalForPreviewFps(targetFps);
    const int64_t minIntervalMs = frameIntervalForPreviewFps(minFps);

    if (!m_adaptiveFrameDropEnabled) {
        return std::max<int64_t>(8, targetIntervalMs);
    }
    const bool overloaded = m_frameDropOverloadScore >= 3;
    return overloaded
        ? std::max<int64_t>(8, minIntervalMs)
        : std::max<int64_t>(8, targetIntervalMs);
}

void PreviewController::updateAdaptiveOverloadScoreLocked(int64_t renderCostMs) {
    if (!m_adaptiveFrameDropEnabled) {
        m_frameDropOverloadScore = 0;
        return;
    }

    const int targetFps = std::max(1, m_targetPreviewFps);
    const int minFps = std::max(1, std::min(m_minPreviewFps, targetFps));
    const int64_t targetIntervalMs = frameIntervalForPreviewFps(targetFps);
    const int64_t minIntervalMs = frameIntervalForPreviewFps(minFps);
    const int64_t clampedRenderCostMs = std::max<int64_t>(0, renderCostMs);

    if (clampedRenderCostMs > (minIntervalMs + 8)) {
        m_frameDropOverloadScore = std::min(12, m_frameDropOverloadScore + 3);
        return;
    }
    if (clampedRenderCostMs > (targetIntervalMs + 4)) {
        m_frameDropOverloadScore = std::min(12, m_frameDropOverloadScore + 1);
        return;
    }
    if (clampedRenderCostMs < std::max<int64_t>(4, targetIntervalMs - 6)) {
        m_frameDropOverloadScore = std::max(0, m_frameDropOverloadScore - 1);
    }
}

bool PreviewController::shouldBypassOverlayCompositionLocked() const {
    return m_adaptiveFrameDropEnabled &&
        m_isPlaying.load() &&
        m_frameDropOverloadScore >= 8;
}

PreviewController::ClipPreviewTransform PreviewController::clipPreviewTransformLocked(int clipId) const {
    auto it = m_clipPreviewTransforms.find(clipId);
    if (it == m_clipPreviewTransforms.end()) {
        return {};
    }
    return it->second;
}

bool PreviewController::hasClipPreviewTransformLocked(const std::shared_ptr<Clip>& clip) const {
    if (!clip) {
        return false;
    }
    return !clipPreviewTransformLocked(static_cast<int>(clip->getId())).isIdentity();
}

void PreviewController::setGhostPreviewEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_ghostPreviewEnabled = enabled;
    if (m_decoder) {
        m_decoder->setPreviewScaleLimit(enabled ? m_ghostPreviewLongEdgePx : 0);
    }
}

void PreviewController::setGhostPreviewLongEdgePx(int longEdgePx) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_ghostPreviewLongEdgePx = std::max(0, longEdgePx);
    if (m_decoder && m_ghostPreviewEnabled) {
        m_decoder->setPreviewScaleLimit(m_ghostPreviewLongEdgePx);
    }
}

void PreviewController::setAdaptiveFrameDropPolicy(bool enabled, int targetFps, int minFps) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_adaptiveFrameDropEnabled = enabled;
    m_targetPreviewFps = std::max(1, targetFps);
    m_minPreviewFps = std::max(1, std::min(minFps, m_targetPreviewFps));
    if (!enabled) {
        m_frameDropOverloadScore = 0;
    }
}

void PreviewController::setDirtyRegionRedrawEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_dirtyRegionRedrawEnabled = enabled;
}

void PreviewController::setClipPreviewTransform(
    int clipId,
    float zoom,
    float panXNorm,
    float panYNorm,
    float rotationDeg,
    bool mirrorX) {
    if (clipId <= 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    ClipPreviewTransform transform;
    transform.zoom = std::max(1.0f, zoom);
    transform.panXNorm = std::clamp(panXNorm, -1.0f, 1.0f);
    transform.panYNorm = std::clamp(panYNorm, -1.0f, 1.0f);
    transform.rotationDeg = std::clamp(rotationDeg, -180.0f, 180.0f);
    transform.mirrorX = mirrorX;
    if (transform.isIdentity()) {
        m_clipPreviewTransforms.erase(clipId);
    } else {
        m_clipPreviewTransforms[clipId] = transform;
    }
}

void PreviewController::clearClipPreviewTransform(int clipId) {
    if (clipId <= 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_clipPreviewTransforms.erase(clipId);
}

void PreviewController::clearClipPreviewTransforms() {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_clipPreviewTransforms.clear();
}

void PreviewController::setPredictiveCachingPolicy(
    bool enabled,
    int lookAroundMs,
    int sampleStepMs,
    int cacheMaxFrames) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_predictiveCachingEnabled = enabled;
    m_predictiveLookAroundMs = std::clamp(lookAroundMs, 200, 8000);
    m_predictiveSampleStepMs = std::clamp(sampleStepMs, 16, 1000);
    m_predictiveCacheMaxFrames = std::clamp(cacheMaxFrames, 4, 240);
    if (!m_predictiveCachingEnabled) {
        clearPredictiveCacheLocked();
    }
}

void PreviewController::setAudioMasterClockEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_audioMasterClockEnabled = enabled;
    if (!enabled) {
        m_audioMasterClockValid = false;
    }
}

void PreviewController::updateAudioMasterClockUs(int64_t ptsUs) {
    std::lock_guard<std::mutex> lock(m_playbackMutex);
    m_audioMasterClockUs = std::max<int64_t>(0, ptsUs);
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
    if (!m_predictiveCachingEnabled || m_openVideoPath.empty()) {
        return;
    }

    {
        std::lock_guard<std::mutex> prefetchLock(m_prefetchMutex);
        m_prefetchCenterMs = clampTimeMs(centerMs);
        m_prefetchVideoPath = m_openVideoPath;
        m_prefetchLookAroundMs = m_predictiveLookAroundMs;
        m_prefetchSampleStepMs = m_predictiveSampleStepMs;
        m_prefetchScaleLimitPx = m_ghostPreviewEnabled ? m_ghostPreviewLongEdgePx : 0;
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
        decoder.setPreviewScaleLimit(scaleLimitPx);

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

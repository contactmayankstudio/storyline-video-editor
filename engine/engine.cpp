#include "engine/engine.h"
#include "engine/project.h"
#include "engine/preview_controller.h"
#ifdef VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE
#include "backend/ffmpeg/packet_demuxer.h"
#endif
#include "backend/mediacodec/media_codec_surface_renderer.h"
#ifdef __ANDROID__
#include <android/log.h>
#define ALOGE_ENGINE(...) __android_log_print(ANDROID_LOG_ERROR, "VideoEngine", __VA_ARGS__)
#define ALOGI_ENGINE(...) __android_log_print(ANDROID_LOG_INFO,  "VideoEngine", __VA_ARGS__)
#else
#include <cstdio>
#define ALOGE_ENGINE(...) do { fprintf(stderr, "[E] "); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#define ALOGI_ENGINE(...) do { printf("[I] "); printf(__VA_ARGS__); printf("\n"); } while(0)
#endif
#include <algorithm>
#include <chrono>
#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>

namespace VideoEngine {

namespace {
void adjustProxySize(bool enabled, int& width, int& height) {
    if (!enabled || width <= 0 || height <= 0) return;
    const int maxProxyWidth = 960;
    if (width <= maxProxyWidth) return;
    const double scale = static_cast<double>(maxProxyWidth) / static_cast<double>(width);
    width = maxProxyWidth;
    height = static_cast<int>(height * scale);
    if (height % 2 != 0) height -= 1;
}

void boostPreviewThreadPriority() {
    sched_param sp{};
    const int maxPrio = sched_get_priority_max(SCHED_FIFO);
    if (maxPrio > 0) {
        sp.sched_priority = std::max(1, maxPrio - 2);
        if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &sp) == 0) {
            return;
        }
    }
    setpriority(PRIO_PROCESS, 0, -8);
}
} // namespace

Engine::Engine()
    : m_project(nullptr),
      m_previewController(nullptr),
      m_exportController(nullptr),
      m_pendingPreviewWindow(nullptr),
      m_previewSurfaceAttached(false),
      m_mediaCodecRenderer(nullptr) {
    // Initialize other components as needed
    ALOGI_ENGINE("VideoEngine created.");
}

Engine::~Engine() {
    stopPreviewFeedLoop();
    stopPreview();
    if (m_previewController) {
        delete m_previewController;
        m_previewController = nullptr;
    }
    m_exportController = nullptr;
    if (m_mediaCodecRenderer) {
        m_mediaCodecRenderer->release();
        delete m_mediaCodecRenderer;
        m_mediaCodecRenderer = nullptr;
    }
    if (m_pendingPreviewWindow) {
        ANativeWindow_release(m_pendingPreviewWindow);
        m_pendingPreviewWindow = nullptr;
    }
    ALOGI_ENGINE("VideoEngine destroyed.");
}

bool Engine::init() {
    // Initialize core engine components
    m_project = std::make_unique<Project>();
    m_previewController = new PreviewController(); // Assuming this handles the main preview loop
    m_exportController = nullptr;
    ALOGI_ENGINE("VideoEngine initialized.");
    return true;
}

void Engine::setPreviewSurface(void* nativeWindow) {
    if (!m_previewController) return;

    auto* window = static_cast<ANativeWindow*>(nativeWindow);
    if (window) {
        ANativeWindow_acquire(window);
    }
    if (m_pendingPreviewWindow) {
        ANativeWindow_release(m_pendingPreviewWindow);
        m_pendingPreviewWindow = nullptr;
    }
    m_pendingPreviewWindow = window;

    if (!window) {
        m_previewController->detachSurface();
        m_previewSurfaceAttached = false;
        ALOGI_ENGINE("Preview surface detached (GL).");
        return;
    }

    if (!m_previewController->isReady()) {
        m_previewSurfaceAttached = false;
        ALOGI_ENGINE("Preview surface stored until video loads.");
        return;
    }

    if (!m_previewController->attachSurface(window)) {
        m_previewSurfaceAttached = false;
        ALOGE_ENGINE("Failed to attach preview surface: %s", m_previewController->getLastError());
    } else {
        m_previewSurfaceAttached = true;
        ALOGI_ENGINE("Preview surface attached (GL).");
    }
}

bool Engine::startPreview() {
    const int64_t startAt = m_previewStartMs.load();
    ALOGI_ENGINE("startPreview at %lld ms", (long long)startAt);
    return playFromPreviewMs(startAt);
}

bool Engine::playFromPreviewMs(int64_t timeMs) {
    ALOGI_ENGINE("playFromPreviewMs called with timeMs: %lld", (long long)timeMs);
    if (!m_previewController) return false;
    if (!m_previewController->isReady()) {
        ALOGE_ENGINE("Cannot start preview: video not loaded.");
        return false;
    }

    // Prepare surface if needed
    if (!m_previewSurfaceAttached && m_pendingPreviewWindow) {
        if (!m_previewController->attachSurface(m_pendingPreviewWindow)) {
            ALOGE_ENGINE("Failed to attach stored preview surface: %s", m_previewController->getLastError());
            return false;
        }
        m_previewSurfaceAttached = true;
    }

    if (!m_previewSurfaceAttached) {
        ALOGE_ENGINE("Cannot start preview: surface not attached.");
        return false;
    }

    // Stop previous playback thread if running
    stopPreview();

    const int64_t clampedTimeMs = std::max<int64_t>(0, timeMs);
    m_previewStartMs.store(clampedTimeMs);
    m_previewSeekRequested.store(false);

    // Tell controller to arm playback from specific time
    if (!m_previewController->playFrom(clampedTimeMs)) {
        ALOGE_ENGINE(
            "Preview controller failed to play from %lld ms: %s",
            static_cast<long long>(clampedTimeMs),
            m_previewController->getLastError());
        return false;
    }

    // Emit initial clock update immediately so UI snaps to position
    if (m_audioClockCallback) {
        m_audioClockCallback(clampedTimeMs * 1000);
    }

    // Start render loop thread
    if (m_previewRenderRunning.exchange(true)) {
        return true;
    }

    m_previewRenderThread = std::thread([this]() {
        using namespace std::chrono_literals;
        boostPreviewThreadPriority();
        ALOGI_ENGINE("Native render thread started.");
        while (m_previewRenderRunning.load()) {
            if (!m_previewController) {
                std::this_thread::sleep_for(8ms);
                continue;
            }

            const bool isPlaying = m_previewController->isPlaying();
            if (isPlaying && !m_previewController->renderFrame()) {
                // End of stream or error
                m_previewRenderRunning.store(false);
                continue;
            }

            if (m_audioClockCallback) {
                const int64_t ptsUs =
                    m_previewController->getPlaybackTimelineTimeMs() * 1000;
                m_audioClockCallback(ptsUs);
            }

            const int64_t sleepMs = std::max<int64_t>(
                1,
                m_previewController->preferredRenderSleepMs());
            std::this_thread::sleep_for(std::chrono::milliseconds(sleepMs));
        }
        ALOGI_ENGINE("Native render thread exiting.");
    });
    
    return true;
}

void Engine::stopPreview() {
    const bool wasRunning = m_previewRenderRunning.exchange(false);
    if (wasRunning && m_previewRenderThread.joinable()) {
        m_previewRenderThread.join();
    }
    if (m_previewController) {
        m_previewController->stop();
    }
}

void Engine::seekPreview(int64_t timeMs) {
    if (m_previewController) {
        const int64_t clampedTimeMs = std::max<int64_t>(0, timeMs);
        m_previewStartMs.store(clampedTimeMs);
        m_previewSeekRequested.store(false);
        m_previewController->scrubToTimelineTime(clampedTimeMs);
        if (m_audioClockCallback) {
            m_audioClockCallback(clampedTimeMs * 1000);
        }
    }
}

void Engine::resizePreview(int width, int height) {
    if (m_previewController) {
        m_previewController->resizeSurface(width, height);
    }
}

void Engine::setPreviewChroma(bool enabled, int color, float similarity, float smoothness, float spill) {
    if (!m_previewController) return;
    auto& chroma = m_previewController->getMutableChromaKey();
    chroma.enabled = enabled;
    chroma.similarity = similarity;
    chroma.smoothness = smoothness;
    chroma.spill = spill;
    chroma.color = (color == 1)
        ? Clip::ChromaKeyParams::KeyColor::Blue
        : Clip::ChromaKeyParams::KeyColor::Green;
}

// --- MediaCodec Preview Pipeline Skeleton Methods ---

bool Engine::initMediaCodecPreview(ANativeWindow* window, int width, int height, const std::string& mimeType) {
    if (m_mediaCodecRenderer) {
        ALOGI_ENGINE("MediaCodec renderer already exists, releasing and re-initializing.");
        m_mediaCodecRenderer->release();
        delete m_mediaCodecRenderer;
        m_mediaCodecRenderer = nullptr;
    }

    adjustProxySize(m_proxyPreviewEnabled.load(), width, height);
    ALOGI_ENGINE(
        "MediaCodec preview size %dx%d (proxy=%s)",
        width,
        height,
        m_proxyPreviewEnabled.load() ? "true" : "false");

    std::vector<uint8_t> csd0;
    std::vector<uint8_t> csd1;
#ifdef VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE
    if (!m_previewSourcePath.empty()) {
        video_engine::backend::PacketDemuxer demuxer;
        if (demuxer.open(m_previewSourcePath)) {
            csd0 = demuxer.csd0();
            csd1 = demuxer.csd1();
            if (csd0.empty() && !demuxer.codecConfig().empty()) {
                csd0 = demuxer.codecConfig();
            }
        }
    }
#endif

    m_mediaCodecRenderer = new video_engine::MediaCodecSurfaceRenderer();
    if (!m_mediaCodecRenderer->init(window, width, height, mimeType, csd0, csd1)) {
        ALOGE_ENGINE("Failed to initialize MediaCodecSurfaceRenderer.");
        delete m_mediaCodecRenderer;
        m_mediaCodecRenderer = nullptr;
        return false;
    }
    ALOGI_ENGINE("MediaCodec preview initialized.");
    return true;
}

bool Engine::startMediaCodecPreview() {
    if (!m_mediaCodecRenderer) {
        ALOGE_ENGINE("MediaCodec renderer not initialized. Cannot start.");
        return false;
    }
    if (!m_mediaCodecRenderer->start()) {
        ALOGE_ENGINE("Failed to start MediaCodecSurfaceRenderer.");
        return false;
    }

    // Skeleton: In a real scenario, you'd now start feeding encoded data
    // from your demuxer/video source to m_mediaCodecRenderer->feedEncodedData.
    // For this skeleton, we start a placeholder feed loop.
    startPreviewFeedLoop();
    ALOGI_ENGINE("MediaCodec preview started.");
    return true;
}

bool Engine::stopMediaCodecPreview() {
    if (!m_mediaCodecRenderer) {
        ALOGI_ENGINE("MediaCodec renderer not initialized. Nothing to stop.");
        return true;
    }
    stopPreviewFeedLoop();
    if (!m_mediaCodecRenderer->stop()) {
        ALOGE_ENGINE("Failed to stop MediaCodecSurfaceRenderer.");
        return false;
    }
    ALOGI_ENGINE("MediaCodec preview stopped.");
    return true;
}

void Engine::releaseMediaCodecPreview() {
    stopPreviewFeedLoop();
    if (m_mediaCodecRenderer) {
        m_mediaCodecRenderer->release();
        delete m_mediaCodecRenderer;
        m_mediaCodecRenderer = nullptr;
        ALOGI_ENGINE("MediaCodec preview released.");
    }
}

// Placeholder method to demonstrate feeding data (called from JNI or internal logic)
void Engine::feedMediaCodecData(const uint8_t* data, size_t size, int64_t pts, int flags) {
    if (m_mediaCodecRenderer) {
        m_mediaCodecRenderer->feedEncodedData(data, size, pts, flags);
    } else {
        ALOGE_ENGINE("MediaCodec renderer not active to feed data.");
    }
}

void Engine::setProxyPreviewEnabled(bool enabled) {
    static auto lastToggle = std::chrono::steady_clock::now();
    const auto now = std::chrono::steady_clock::now();
    if (std::chrono::duration_cast<std::chrono::milliseconds>(now - lastToggle).count() < 120) {
        return;
    }
    lastToggle = now;

    const bool prev = m_proxyPreviewEnabled.exchange(enabled);
    ALOGI_ENGINE("setProxyPreviewEnabled %s", enabled ? "true" : "false");
    if (prev == enabled) return;
    if (!m_mediaCodecRenderer) return;

    ANativeWindow* window = m_mediaCodecRenderer->nativeWindow();
    const std::string mime = m_mediaCodecRenderer->mimeType();
    int width = m_mediaCodecRenderer->width();
    int height = m_mediaCodecRenderer->height();
    const bool wasRunning = m_mediaCodecRenderer->isRunning();

    if (!window || width <= 0 || height <= 0 || mime.empty()) return;

    stopPreviewFeedLoop();
    m_mediaCodecRenderer->stop();
    m_mediaCodecRenderer->release();
    delete m_mediaCodecRenderer;
    m_mediaCodecRenderer = nullptr;

    if (!initMediaCodecPreview(window, width, height, mime)) {
        ALOGE_ENGINE("Failed to reinit MediaCodec preview after proxy toggle.");
        return;
    }
    if (wasRunning) {
        startMediaCodecPreview();
    }
}

void Engine::startPreviewFeedLoop() {
    if (m_previewFeedRunning.exchange(true)) {
        return;
    }
    m_previewFeedThread = std::thread([this]() {
        using namespace std::chrono_literals;
#ifdef VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE
        video_engine::backend::PacketDemuxer demuxer;
        if (!m_previewSourcePath.empty()) {
            demuxer.open(m_previewSourcePath);
        }
        if (demuxer.isOpen() && m_previewStartMs.load() > 0) {
            demuxer.seekToMsKeyframe(m_previewStartMs.load());
        }
#endif
        int64_t lastPtsUs = -1;
        int feedErrors = 0;
        (void)lastPtsUs; (void)feedErrors; // used inside VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE block
        while (m_previewFeedRunning.load()) {
            if (!m_mediaCodecRenderer || !m_mediaCodecRenderer->isRunning()) {
                std::this_thread::sleep_for(16ms);
                continue;
            }
#ifdef VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE
            if (!demuxer.isOpen()) {
                std::this_thread::sleep_for(33ms);
                continue;
            }

            if (m_previewSeekRequested.exchange(false)) {
                const auto targetMs = m_previewStartMs.load();
                if (targetMs > 0) {
                    if (m_previewApproxSeek.load()) {
                        demuxer.seekToMsApprox(targetMs);
                    } else {
                        demuxer.seekToMsKeyframe(targetMs);
                    }
                } else {
                    demuxer.rewind();
                }
                lastPtsUs = -1;
            }

            video_engine::backend::EncodedPacket pkt;
            if (!demuxer.readNextPacket(pkt)) {
                if (m_previewStartMs.load() > 0) {
                    demuxer.seekToMsKeyframe(m_previewStartMs.load());
                } else {
                    demuxer.rewind();
                }
                std::this_thread::sleep_for(10ms);
                continue;
            }
            const bool ok = m_mediaCodecRenderer->feedEncodedData(
                pkt.data.data(),
                pkt.data.size(),
                pkt.ptsUs,
                pkt.flags);
            if (!ok) {
                feedErrors++;
            } else {
                feedErrors = 0;
            }
            if (feedErrors >= 5) {
                if (!m_previewApproxSeek.load()) {
                    feedErrors = 0;
                    ANativeWindow* window = m_mediaCodecRenderer->nativeWindow();
                    const std::string mime = m_mediaCodecRenderer->mimeType();
                    int width = m_mediaCodecRenderer->width();
                    int height = m_mediaCodecRenderer->height();
                    m_mediaCodecRenderer->stop();
                    m_mediaCodecRenderer->release();
                    delete m_mediaCodecRenderer;
                    m_mediaCodecRenderer = nullptr;
                    if (window && !mime.empty()) {
                        initMediaCodecPreview(window, width, height, mime);
                        startMediaCodecPreview();
                    }
                } else {
                    feedErrors = 0;
                }
            }

            if (m_audioClockCallback) {
                m_audioClockCallback(pkt.ptsUs);
            }

            const double speed = m_previewSpeed.load();
            if (lastPtsUs > 0 && pkt.ptsUs > lastPtsUs) {
                const int64_t deltaUs = pkt.ptsUs - lastPtsUs;
                if (deltaUs > 0 && deltaUs < 200000) {
                    std::this_thread::sleep_for(
                        std::chrono::microseconds(
                            static_cast<int64_t>(deltaUs / (speed <= 0.1 ? 0.1 : speed))));
                }
            } else if (demuxer.fps() > 0.0) {
                const int64_t frameUs =
                    static_cast<int64_t>(1000000.0 / demuxer.fps());
                if (frameUs > 0) {
                    std::this_thread::sleep_for(
                        std::chrono::microseconds(
                            static_cast<int64_t>(frameUs / (speed <= 0.1 ? 0.1 : speed))));
                }
            }
            lastPtsUs = pkt.ptsUs;
#else
            std::this_thread::sleep_for(33ms);
#endif
        }
    });
}

void Engine::stopPreviewFeedLoop() {
    if (!m_previewFeedRunning.exchange(false)) {
        return;
    }
    if (m_previewFeedThread.joinable()) {
        m_previewFeedThread.join();
    }
}

void Engine::setPreviewSourcePath(const std::string& path) {
    if (path.empty() || !m_previewController) {
        return;
    }
    const bool pathChanged = path != m_previewSourcePath;
    m_previewSourcePath = path;

    if (pathChanged) {
        stopPreview();
        m_previewController->destroy();
        delete m_previewController;
        m_previewController = new PreviewController();
        m_previewSurfaceAttached = false;
    }

    if (!m_previewController->isReady()) {
        if (!m_previewController->open(path)) {
            ALOGE_ENGINE("Failed to open preview source: %s", m_previewController->getLastError());
            return;
        }
    }

    if (!m_previewSurfaceAttached && m_pendingPreviewWindow) {
        if (!m_previewController->attachSurface(m_pendingPreviewWindow)) {
            ALOGE_ENGINE("Failed to attach stored preview surface after load: %s", m_previewController->getLastError());
            return;
        }
        m_previewSurfaceAttached = true;
    }
}

void Engine::setPreviewStartMs(int64_t timeMs) {
    m_previewStartMs.store(timeMs < 0 ? 0 : timeMs);
    m_previewSeekRequested.store(true);
    if (m_previewController && m_previewController->isReady() && m_previewSurfaceAttached) {
        const int64_t clampedTimeMs = m_previewStartMs.load();
        m_previewController->scrubToTimelineTime(clampedTimeMs);
        if (m_audioClockCallback) {
            m_audioClockCallback(clampedTimeMs * 1000);
        }
    }
}

void Engine::setPreviewApproxSeek(bool enabled) {
    m_previewApproxSeek.store(enabled);
}

void Engine::setPreviewSpeed(double speed) {
    if (speed < 0.1) speed = 0.1;
    m_previewSpeed.store(speed);
}

void Engine::setAudioClockCallback(std::function<void(int64_t)> callback) {
    m_audioClockCallback = std::move(callback);
}

} // namespace VideoEngine

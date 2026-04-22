#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#define LOG_TAG "AndroidPreview"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#else
#define LOG_TAG_DESKTOP "NativePreview" // Set the tag for desktop logging
#include "../../desktop_log.h" // Path from JNI folder to desktop_log.h
// Mock ANativeWindow and related functions for desktop
#define ANativeWindow void
#define ANativeWindow_fromSurface(env, surface) nullptr
#define ANativeWindow_release(window) do { (void)window; } while(0)
// LOGI, LOGE, LOGD, LOGW are now defined in desktop_log.h
#endif

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#include <memory>
#include <mutex>
#include <thread>
#include <atomic>
#include <chrono>
#include <cstring>
#include <cstdio>
#include <map>
#include <algorithm>
#include <functional>
#include <string>
#include <sstream>
#include <cmath>
#include <limits>
#include <dlfcn.h>
#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>

#include "native_preview_shared.h"
#include "engine/preview_controller.h"
#include "core/timeline.h"
#include "backend/ffmpeg/video_decoder.h"
#include <vector>
#include "../../text_overlay.h"
#if defined(VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE)
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}
#endif

using namespace VideoEngine; // Add this line here
using AudioGainKeyframe = VideoEngine::Clip::AudioGainKeyframe;

// Global JavaVM for thread attachment
static JavaVM* g_javaVM = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_javaVM = vm;
    return JNI_VERSION_1_6;
}

// Safe null checks to prevent crashes
#define CHECK_EGL_CONTEXT() \
    if (g_eglDisplay == EGL_NO_DISPLAY || g_eglContext == EGL_NO_CONTEXT) { \
        LOGE("EGL context not initialized"); \
        return; \
    }

#define CHECK_NATIVE_WINDOW() \
    if (g_nativeWindow == nullptr) { \
        LOGE("Native window is null"); \
        return false; \
    }

#define CHECK_PREVIEW_CONTROLLER() \
    if (!g_preview) { \
        LOGE("Preview controller not initialized"); \
        return; \
    }

// Global state (thread-safe)
std::mutex g_mutex;

// EGL state
EGLDisplay g_eglDisplay = EGL_NO_DISPLAY;
EGLContext g_eglContext = EGL_NO_CONTEXT;
EGLSurface g_eglSurface = EGL_NO_SURFACE;
ANativeWindow* g_nativeWindow = nullptr;

// Engine state
std::unique_ptr<VideoEngine::PreviewController> g_preview;

int g_surfaceWidth = 0;
int g_surfaceHeight = 0;

// Rendering loop state
std::thread g_renderThread;
std::atomic<bool> g_isRenderingActive(false);
std::atomic<bool> g_shouldExit(false);
std::atomic<long long> g_currentTimeMs(0);
std::atomic<int> g_timelineZoomMilliPxPerSecond(120000);
std::atomic<long long> g_pendingScrubMs{-1}; // -1 = no pending scrub
std::atomic<long long> g_pendingPlayMs{-1};  // -1 = no pending play

static bool initializeEGL();
bool releaseOuterEglForPreviewAttachLocked();

namespace {
void configureRenderThreadPriority() {
    pthread_setname_np(pthread_self(), "ve-render");

    sched_param sp{};
    const int maxPrio = sched_get_priority_max(SCHED_FIFO);
    if (maxPrio > 0) {
        sp.sched_priority = std::max(1, maxPrio - 2);
        if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &sp) == 0) {
            return;
        }
    }

    // Fallback when realtime scheduling is not allowed.
    setpriority(PRIO_PROCESS, 0, -8);
}
}  // namespace

    // Text overlay storage
    std::map<int64_t, TextOverlay> g_textOverlays;
    int64_t g_nextTextOverlayId = 1;
    // Maintain explicit render order vector for overlays. Sorting is done on demand.
    std::vector<int64_t> g_textOverlayOrder;
    bool g_orderDirty = false;

    struct OverlaySharedImage {
        EGLImageKHR image = EGL_NO_IMAGE_KHR;
        AHardwareBuffer* buffer = nullptr;
    };
    std::map<int64_t, OverlaySharedImage> g_overlaySharedImages;
    using AhbFromHardwareBufferFn = AHardwareBuffer* (*)(JNIEnv*, jobject);
    using AhbAcquireFn = void (*)(AHardwareBuffer*);
    using AhbReleaseFn = void (*)(AHardwareBuffer*);
    struct AhbApi {
        void* handle = nullptr;
        AhbFromHardwareBufferFn fromHardwareBuffer = nullptr;
        AhbAcquireFn acquire = nullptr;
        AhbReleaseFn release = nullptr;
        bool initialized = false;
    };
    AhbApi g_ahbApi;
    std::mutex g_ahbMutex;
    struct HardwareBufferTelemetry {
        std::atomic<uint64_t> attempts{0};
        std::atomic<uint64_t> successes{0};
        std::atomic<uint64_t> fallbacks{0};
        std::atomic<uint64_t> invalidArgs{0};
        std::atomic<uint64_t> bridgeUnavailable{0};
        std::atomic<uint64_t> eglUnavailable{0};
        std::atomic<uint64_t> eglMakeCurrentFailed{0};
        std::atomic<uint64_t> overlayMissing{0};
        std::atomic<uint64_t> importFailed{0};
        std::atomic<uint64_t> extensionMissing{0};
        std::atomic<uint64_t> nativeClientBufferFailed{0};
        std::atomic<uint64_t> createImageFailed{0};
        std::atomic<uint64_t> bindImageFailed{0};
    };
    HardwareBufferTelemetry g_hardwareBufferTelemetry;

    static void recordHardwareBufferFallback(std::atomic<uint64_t>& reasonCounter) {
        reasonCounter.fetch_add(1, std::memory_order_relaxed);
        g_hardwareBufferTelemetry.fallbacks.fetch_add(1, std::memory_order_relaxed);
    }

    static std::string buildHardwareBufferTelemetryJson() {
        const uint64_t attempts = g_hardwareBufferTelemetry.attempts.load(std::memory_order_relaxed);
        const uint64_t successes = g_hardwareBufferTelemetry.successes.load(std::memory_order_relaxed);
        const uint64_t fallbacks = g_hardwareBufferTelemetry.fallbacks.load(std::memory_order_relaxed);
        const double successRatioPct = attempts > 0
            ? (100.0 * static_cast<double>(successes) / static_cast<double>(attempts))
            : 0.0;
        const double fallbackRatioPct = attempts > 0
            ? (100.0 * static_cast<double>(fallbacks) / static_cast<double>(attempts))
            : 0.0;
        char buffer[1024];
        std::snprintf(
            buffer,
            sizeof(buffer),
            "{\"attempts\":%llu,\"successes\":%llu,\"fallbacks\":%llu,"
            "\"successRatioPct\":%.2f,\"fallbackRatioPct\":%.2f,"
            "\"invalidArgs\":%llu,\"bridgeUnavailable\":%llu,\"eglUnavailable\":%llu,"
            "\"eglMakeCurrentFailed\":%llu,\"overlayMissing\":%llu,\"importFailed\":%llu,"
            "\"extensionMissing\":%llu,\"nativeClientBufferFailed\":%llu,"
            "\"createImageFailed\":%llu,\"bindImageFailed\":%llu}",
            static_cast<unsigned long long>(attempts),
            static_cast<unsigned long long>(successes),
            static_cast<unsigned long long>(fallbacks),
            successRatioPct,
            fallbackRatioPct,
            static_cast<unsigned long long>(g_hardwareBufferTelemetry.invalidArgs.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(g_hardwareBufferTelemetry.bridgeUnavailable.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(g_hardwareBufferTelemetry.eglUnavailable.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(g_hardwareBufferTelemetry.eglMakeCurrentFailed.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(g_hardwareBufferTelemetry.overlayMissing.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(g_hardwareBufferTelemetry.importFailed.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(g_hardwareBufferTelemetry.extensionMissing.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(g_hardwareBufferTelemetry.nativeClientBufferFailed.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(g_hardwareBufferTelemetry.createImageFailed.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(g_hardwareBufferTelemetry.bindImageFailed.load(std::memory_order_relaxed)));
        return std::string(buffer);
    }

    static void maybeLogHardwareBufferTelemetrySample() {
        const uint64_t attempts = g_hardwareBufferTelemetry.attempts.load(std::memory_order_relaxed);
        if (attempts == 0 || (attempts % 20ULL) != 0ULL) {
            return;
        }
        const std::string telemetry = buildHardwareBufferTelemetryJson();
        LOGI("[Text] HardwareBuffer telemetry: %s", telemetry.c_str());
    }

    static bool ensureAhbApiLoaded() {
        std::lock_guard<std::mutex> lock(g_ahbMutex);
        if (g_ahbApi.initialized) {
            return g_ahbApi.fromHardwareBuffer && g_ahbApi.acquire && g_ahbApi.release;
        }
        g_ahbApi.initialized = true;
        g_ahbApi.handle = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (!g_ahbApi.handle) {
            LOGW("[Text] libandroid.so not available for HardwareBuffer bridge");
            return false;
        }
        g_ahbApi.fromHardwareBuffer = reinterpret_cast<AhbFromHardwareBufferFn>(
            dlsym(g_ahbApi.handle, "AHardwareBuffer_fromHardwareBuffer"));
        g_ahbApi.acquire = reinterpret_cast<AhbAcquireFn>(
            dlsym(g_ahbApi.handle, "AHardwareBuffer_acquire"));
        g_ahbApi.release = reinterpret_cast<AhbReleaseFn>(
            dlsym(g_ahbApi.handle, "AHardwareBuffer_release"));
        const bool ok = g_ahbApi.fromHardwareBuffer && g_ahbApi.acquire && g_ahbApi.release;
        if (!ok) {
            LOGW("[Text] HardwareBuffer symbols unavailable on this device/API");
        }
        return ok;
    }

    // Export state
    std::atomic<int32_t> g_exportProgress(0);           // 0-100%
    std::atomic<bool> g_isExporting(false);             // Currently exporting?
    std::atomic<bool> g_exportCancelled(false);         // User cancelled?
    std::thread g_exportThread;                         // Background export thread
    std::string g_lastExportError;
    // Export callback (JNI global ref)
    jobject g_exportCallback = nullptr;
    jmethodID g_onExportProgressMethod = nullptr;
    jmethodID g_onExportCompletedMethod = nullptr;

    static void callExportCallbackProgress(int progress) {
        if (!g_exportCallback || !g_onExportProgressMethod || !g_javaVM) return;
        JNIEnv* env = nullptr;
        bool attached = false;
        if (g_javaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
            if (g_javaVM->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            } else {
                return;
            }
        }
        if (env) {
            env->CallVoidMethod(g_exportCallback, g_onExportProgressMethod, progress);
        }
        if (attached) {
            g_javaVM->DetachCurrentThread();
        }
    }

    static void callExportCallbackCompleted(bool success, const std::string& error) {
        if (!g_exportCallback || !g_onExportCompletedMethod || !g_javaVM) return;
        JNIEnv* env = nullptr;
        bool attached = false;
        if (g_javaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
            if (g_javaVM->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            } else {
                return;
            }
        }
        if (env) {
            jstring errorJ = error.empty() ? nullptr : env->NewStringUTF(error.c_str());
            env->CallVoidMethod(g_exportCallback, g_onExportCompletedMethod, success ? JNI_TRUE : JNI_FALSE, errorJ);
            if (errorJ) env->DeleteLocalRef(errorJ);
        }
        if (attached) {
            g_javaVM->DetachCurrentThread();
        }
    }
    struct ClipExportSource {
        int clipId = -1;
        std::string path;
        int64_t durationMs = 0;
    };

    struct TimelineClipExportSpec {
        int clipId = -1;
        std::string path;
        int64_t startTimeMs = 0;
        int64_t durationMs = 0;
        Clip::TrackRole trackRole = Clip::TrackRole::MainVideo;
        int trackLane = 0;
        int trackZOrder = 0;
        int64_t trimInMs = 0;
        int64_t trimOutMs = 0;
        int64_t sourceDurationMs = 0;
        bool enabled = true;
        float opacity = 1.0f;
        float playbackSpeed = 1.0f;
        bool reversePlayback = false;
        bool freezeFrameEnabled = false;
        int64_t freezeFrameTimeMs = 0;
        int64_t freezeFrameDurationMs = 1000;
        std::string curveSpeedProfile = "linear";
        float curveSpeedStrength = 1.0f;
        // Effects
        float brightness = 0.0f;   // -1..+1
        float contrast   = 1.0f;   // 0..2
        float saturation = 1.0f;   // 0..2
        bool  effectsEnabled = false;
        // Volume
        float volumeGain = 1.0f;
        int32_t fadeInMs = 0;
        int32_t fadeOutMs = 0;
        std::vector<AudioGainKeyframe> audioGainKeyframes;
        // Chroma key
        bool  chromaEnabled = false;
        float chromaSimilarity = 0.35f;
        float chromaSmoothness = 0.10f;
        float chromaSpill     = 0.05f;
        bool  chromaIsBlue    = false;
    };

    // CPU-side pixel buffer for text overlays (for export compositing)
    struct OverlayCpuBitmap {
        std::vector<uint8_t> rgba; // RGBA8888
        int width = 0;
        int height = 0;
    };
    std::map<int64_t, OverlayCpuBitmap> g_overlayCpuBitmaps;

    // Audio clip info passed from Kotlin for export mixing
    struct AudioExportClip {
        std::string path;
        int64_t startTimeMs = 0;
        int64_t durationMs  = 0;
        float   volume      = 1.0f;
        int32_t fadeInMs = 0;
        int32_t fadeOutMs = 0;
        std::vector<AudioGainKeyframe> audioGainKeyframes;
        int64_t sourceInMs = 0;
        int64_t sourceOutMs = 0;
        float playbackSpeed = 1.0f;
        bool reversePlayback = false;
        bool freezeFrameEnabled = false;
        int64_t freezeFrameTimeMs = 0;
        int64_t freezeFrameDurationMs = 0;
        std::string curveSpeedProfile = "linear";
        float curveSpeedStrength = 1.0f;
    };
    std::vector<AudioExportClip> g_audioExportClips;

    std::vector<ClipExportSource> g_timelineClipPaths;
    constexpr int64_t kDefaultStillImageDurationMs = 5000;

    static std::string normalizedExtension(const std::string& path) {
        const size_t dotPos = path.find_last_of('.');
        if (dotPos == std::string::npos) {
            return {};
        }
        std::string ext = path.substr(dotPos + 1);
        std::transform(
            ext.begin(),
            ext.end(),
            ext.begin(),
            [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        return ext;
    }

    static bool isStillImagePath(const std::string& path) {
        const std::string ext = normalizedExtension(path);
        return ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp" ||
            ext == "bmp" || ext == "gif" || ext == "tif" || ext == "tiff";
    }

    static std::vector<AudioGainKeyframe> sanitizeAudioGainKeyframes(
        const std::vector<AudioGainKeyframe>& keyframes,
        int64_t clipDurationMs) {
        const int64_t clampedDurationMs = std::max<int64_t>(1, clipDurationMs);
        std::vector<AudioGainKeyframe> normalized = keyframes;
        for (auto& keyframe : normalized) {
            keyframe.timeMs = std::clamp<int64_t>(keyframe.timeMs, 0, clampedDurationMs - 1);
            keyframe.gain = std::clamp(keyframe.gain, 0.0f, 2.0f);
        }
        std::stable_sort(
            normalized.begin(),
            normalized.end(),
            [](const AudioGainKeyframe& left, const AudioGainKeyframe& right) {
                return left.timeMs < right.timeMs;
            });

        std::vector<AudioGainKeyframe> deduped;
        deduped.reserve(normalized.size());
        for (const auto& keyframe : normalized) {
            if (!deduped.empty() && deduped.back().timeMs == keyframe.timeMs) {
                deduped.back() = keyframe;
            } else {
                deduped.push_back(keyframe);
            }
        }
        return deduped;
    }

    static std::vector<AudioGainKeyframe> parseAudioGainKeyframesCsv(
        const std::string& csv,
        int64_t clipDurationMs) {
        std::vector<AudioGainKeyframe> parsed;
        if (csv.empty()) {
            return parsed;
        }
        std::stringstream stream(csv);
        std::string token;
        while (std::getline(stream, token, ',')) {
            if (token.empty()) {
                continue;
            }
            const size_t colonPos = token.find(':');
            if (colonPos == std::string::npos) {
                continue;
            }
            try {
                parsed.push_back(AudioGainKeyframe{
                    std::stoll(token.substr(0, colonPos)),
                    std::stof(token.substr(colonPos + 1)),
                });
            } catch (...) {
            }
        }
        return sanitizeAudioGainKeyframes(parsed, clipDurationMs);
    }

    static float sampleAudioGainEnvelope(
        const std::vector<AudioGainKeyframe>& keyframes,
        int64_t localTimeMs) {
        if (keyframes.empty()) {
            return 1.0f;
        }
        const int64_t clampedLocalTimeMs = std::max<int64_t>(0, localTimeMs);
        if (clampedLocalTimeMs <= keyframes.front().timeMs) {
            return std::clamp(keyframes.front().gain, 0.0f, 2.0f);
        }
        if (clampedLocalTimeMs >= keyframes.back().timeMs) {
            return std::clamp(keyframes.back().gain, 0.0f, 2.0f);
        }
        for (size_t index = 1; index < keyframes.size(); ++index) {
            const auto& right = keyframes[index];
            if (clampedLocalTimeMs > right.timeMs) {
                continue;
            }
            const auto& left = keyframes[index - 1];
            const int64_t spanMs = std::max<int64_t>(1, right.timeMs - left.timeMs);
            const float progress = static_cast<float>(clampedLocalTimeMs - left.timeMs) /
                static_cast<float>(spanMs);
            return std::clamp(left.gain + ((right.gain - left.gain) * progress), 0.0f, 2.0f);
        }
        return 1.0f;
    }

    static int64_t lookupCachedClipDurationMs(const std::string& path) {
        auto it = std::find_if(
            g_timelineClipPaths.begin(),
            g_timelineClipPaths.end(),
            [&path](const ClipExportSource& entry) { return entry.path == path; });
        return it != g_timelineClipPaths.end() ? it->durationMs : 0;
    }

    static int64_t probeClipDurationMs(const std::string& path) {
        if (isStillImagePath(path)) {
            return kDefaultStillImageDurationMs;
        }
        const int64_t cachedDurationMs = lookupCachedClipDurationMs(path);
        if (cachedDurationMs > 0) {
            return cachedDurationMs;
        }

        VideoEngine::Backend::VideoDecoder decoder;
        if (!decoder.open(path)) {
            LOGW("[Timeline] Failed to probe duration for %s: %s", path.c_str(), decoder.getLastError());
            return 0;
        }

        const double durationSeconds = decoder.getDuration();
        decoder.close();
        if (durationSeconds <= 0.0) {
            return 0;
        }
        return static_cast<int64_t>(durationSeconds * 1000.0);
    }

    static void cacheClipExportSource(int clipId, const std::string& path, int64_t durationMs) {
        auto cached = std::find_if(
            g_timelineClipPaths.begin(),
            g_timelineClipPaths.end(),
            [clipId](const ClipExportSource& entry) { return entry.clipId == clipId; });
        if (cached != g_timelineClipPaths.end()) {
            cached->path = path;
            cached->durationMs = durationMs;
        } else {
            g_timelineClipPaths.push_back({clipId, path, durationMs});
        }
    }

    static TimelineClipExportSpec buildTimelineClipExportSpec(const std::shared_ptr<VideoEngine::Clip>& clip) {
        TimelineClipExportSpec spec;
        if (!clip) {
            return spec;
        }

        spec.clipId = static_cast<int>(clip->getId());
        spec.path = clip->getMediaPath();
        spec.startTimeMs = clip->getStartTime();
        spec.durationMs = std::max<int64_t>(1, clip->getDuration());
        spec.trackRole = clip->getTrackRole();
        spec.trackLane = clip->getTrackLane();
        spec.trackZOrder = clip->getTrackZOrder();
        clip->getTrimPoints(spec.trimInMs, spec.trimOutMs);
        if (spec.trimOutMs <= spec.trimInMs) {
            spec.trimOutMs = spec.trimInMs + spec.durationMs;
        }

        const auto& props = clip->getProperties();
        spec.enabled = props.enabled;
        spec.opacity = props.opacity;
        spec.playbackSpeed = std::max(0.1f, props.playbackSpeed);
        spec.reversePlayback = props.reversePlayback;
        spec.freezeFrameEnabled = props.freezeFrameEnabled;
        spec.freezeFrameTimeMs = props.freezeFrameTimeMs;
        spec.freezeFrameDurationMs = std::max<int64_t>(0, props.freezeFrameDurationMs);
        spec.curveSpeedProfile = props.curveSpeedProfile;
        spec.curveSpeedStrength = props.curveSpeedStrength;
        spec.volumeGain = props.volumeGain;
        spec.fadeInMs = std::max<int32_t>(0, props.fadeInMs);
        spec.fadeOutMs = std::max<int32_t>(0, props.fadeOutMs);
        spec.audioGainKeyframes = sanitizeAudioGainKeyframes(props.audioGainKeyframes, spec.durationMs);

        const auto& fx = clip->getEffects();
        spec.brightness     = fx.brightness;
        spec.contrast       = fx.contrast;
        spec.saturation     = fx.saturation;
        spec.effectsEnabled = (fx.brightness != 0.0f || fx.contrast != 1.0f || fx.saturation != 1.0f);
        LOGI("[Export] clip=%d brightness=%.3f contrast=%.3f saturation=%.3f effectsEnabled=%d",
             spec.clipId, spec.brightness, spec.contrast, spec.saturation, spec.effectsEnabled);

        const auto& ck = clip->getChromaKey();
        spec.chromaEnabled    = ck.enabled;
        spec.chromaSimilarity = ck.similarity;
        spec.chromaSmoothness = ck.smoothness;
        spec.chromaSpill      = ck.spill;
        spec.chromaIsBlue     = (ck.color == VideoEngine::Clip::ChromaKeyParams::KeyColor::Blue);

        spec.sourceDurationMs = probeClipDurationMs(spec.path);
        if (isStillImagePath(spec.path)) {
            spec.sourceDurationMs = 1;
            spec.trimInMs = 0;
            spec.trimOutMs = 1;
        }
        if (spec.sourceDurationMs > 0) {
            spec.trimInMs = std::clamp<int64_t>(spec.trimInMs, 0, std::max<int64_t>(0, spec.sourceDurationMs - 1));
            spec.trimOutMs = std::clamp<int64_t>(
                spec.trimOutMs,
                spec.trimInMs + 1,
                std::max<int64_t>(spec.trimInMs + 1, spec.sourceDurationMs));
        }
        cacheClipExportSource(spec.clipId, spec.path, spec.durationMs);
        return spec;
    }

    static std::vector<TimelineClipExportSpec> collectTimelineClipExportSpecsLocked() {
        std::vector<TimelineClipExportSpec> specs;
        if (!g_preview) {
            return specs;
        }
        auto timeline = g_preview->getTimeline();
        if (!timeline) {
            return specs;
        }

        const auto& clips = timeline->clips();
        specs.reserve(clips.size());
        for (const auto& clip : clips) {
            if (!clip) {
                continue;
            }
            specs.push_back(buildTimelineClipExportSpec(clip));
        }

        std::sort(
            specs.begin(),
            specs.end(),
            [](const TimelineClipExportSpec& a, const TimelineClipExportSpec& b) {
                if (a.startTimeMs != b.startTimeMs) return a.startTimeMs < b.startTimeMs;
                return a.clipId < b.clipId;
            });
        return specs;
    }

    static int64_t mapTimelineClipToSourceMs(
        const TimelineClipExportSpec& spec,
        int64_t timelineMs,
        bool ignoreFreeze) {
        const int64_t clipStart = spec.startTimeMs;
        const int64_t clipDuration = std::max<int64_t>(1, spec.durationMs);
        const int64_t clipEndExclusive = clipStart + clipDuration;
        int64_t sourceInMs = std::max<int64_t>(0, spec.trimInMs);
        int64_t sourceOutMs = spec.trimOutMs;
        if (sourceOutMs <= sourceInMs) {
            sourceOutMs = sourceInMs + clipDuration;
        }
        if (spec.sourceDurationMs > 0) {
            sourceInMs = std::clamp<int64_t>(sourceInMs, 0, std::max<int64_t>(0, spec.sourceDurationMs - 1));
            sourceOutMs = std::clamp<int64_t>(
                sourceOutMs,
                sourceInMs + 1,
                std::max<int64_t>(sourceInMs + 1, spec.sourceDurationMs));
        } else if (sourceOutMs <= sourceInMs) {
            sourceOutMs = sourceInMs + 1;
        }

        auto mapWithoutFreeze = [&](int64_t localTimelineMs) -> int64_t {
            const int64_t clampedLocalMs = std::clamp<int64_t>(localTimelineMs, 0, clipDuration - 1);
            const double localProgress = clipDuration > 1
                ? static_cast<double>(clampedLocalMs) / static_cast<double>(clipDuration - 1)
                : 0.0;
            const double t = std::clamp(localProgress, 0.0, 1.0);

            double shaped = t;
            const float clampedCurveStrength = std::clamp(spec.curveSpeedStrength, 0.1f, 4.0f);
            if (spec.curveSpeedProfile == "ease_in") {
                const double gamma = 1.0 + (std::max(0.0f, clampedCurveStrength - 1.0f) * 1.35);
                shaped = std::pow(t, gamma);
            } else if (spec.curveSpeedProfile == "ease_out") {
                const double gamma = 1.0 + (std::max(0.0f, clampedCurveStrength - 1.0f) * 1.35);
                shaped = 1.0 - std::pow(1.0 - t, gamma);
            } else if (spec.curveSpeedProfile == "ease_in_out") {
                shaped = t * t * (3.0 - 2.0 * t);
            } else if (spec.curveSpeedProfile == "hyperlapse") {
                const double alpha = std::clamp(1.4 - (clampedCurveStrength * 0.15), 0.55, 1.4);
                shaped = std::pow(t, alpha);
            }

            const int64_t trimmedSpanMs = std::max<int64_t>(1, sourceOutMs - sourceInMs);
            const int64_t curveSourceMs = sourceInMs + static_cast<int64_t>(
                std::llround(shaped * static_cast<double>(trimmedSpanMs - 1)));
            const double playbackSpeed = std::max(0.1f, spec.playbackSpeed);
            const int64_t speedSourceMs = sourceInMs + static_cast<int64_t>(
                std::llround(static_cast<double>(clampedLocalMs) * playbackSpeed));

            int64_t mappedSourceMs = speedSourceMs;
            if (spec.curveSpeedProfile != "linear") {
                const double blend = std::clamp(
                    static_cast<double>(clampedCurveStrength - 0.1f) / 3.9,
                    0.15,
                    0.9);
                mappedSourceMs = static_cast<int64_t>(
                    std::llround((1.0 - blend) * static_cast<double>(speedSourceMs) +
                                 blend * static_cast<double>(curveSourceMs)));
            }
            mappedSourceMs = std::clamp<int64_t>(mappedSourceMs, sourceInMs, sourceOutMs - 1);
            if (spec.reversePlayback) {
                mappedSourceMs = sourceOutMs - 1 - (mappedSourceMs - sourceInMs);
                mappedSourceMs = std::clamp<int64_t>(mappedSourceMs, sourceInMs, sourceOutMs - 1);
            }
            return mappedSourceMs;
        };

        if (!ignoreFreeze && spec.freezeFrameEnabled && spec.freezeFrameDurationMs > 0) {
            const int64_t freezeStartMs = std::clamp<int64_t>(
                spec.freezeFrameTimeMs,
                clipStart,
                std::max<int64_t>(clipStart, clipEndExclusive - 1));
            const int64_t freezeEndMs = freezeStartMs + std::max<int64_t>(100, spec.freezeFrameDurationMs);
            if (timelineMs >= freezeStartMs && timelineMs < freezeEndMs) {
                const int64_t freezeLocalMs = std::clamp<int64_t>(freezeStartMs - clipStart, 0, clipDuration - 1);
                return mapWithoutFreeze(freezeLocalMs);
            }
        }

        const int64_t localTimelineMs = std::clamp<int64_t>(timelineMs - clipStart, 0, clipDuration - 1);
        return mapWithoutFreeze(localTimelineMs);
    }

    static const TimelineClipExportSpec* findActiveTimelineClipForMs(
        const std::vector<TimelineClipExportSpec>& clipSpecs,
        int64_t timelineMs) {
        const TimelineClipExportSpec* active = nullptr;
        int64_t bestStartMs = std::numeric_limits<int64_t>::min();
        for (const auto& spec : clipSpecs) {
            if (!spec.enabled || spec.path.empty()) continue;
            const int64_t startMs = spec.startTimeMs;
            const int64_t endMs = startMs + std::max<int64_t>(1, spec.durationMs);
            if (timelineMs < startMs || timelineMs >= endMs) continue;
            if (!active || startMs > bestStartMs ||
                (startMs == bestStartMs && spec.clipId > active->clipId)) {
                bestStartMs = startMs;
                active = &spec;
            }
        }
        if (!active) {
            int64_t bestEndMs = std::numeric_limits<int64_t>::min();
            for (const auto& spec : clipSpecs) {
                if (!spec.enabled || spec.path.empty()) continue;
                const int64_t endMs = spec.startTimeMs + std::max<int64_t>(1, spec.durationMs);
                if (endMs <= timelineMs && endMs > bestEndMs) {
                    bestEndMs = endMs;
                    active = &spec;
                }
            }
        }
        if (!active) {
            for (const auto& spec : clipSpecs) {
                if (!spec.enabled || spec.path.empty()) continue;
                if (!active || spec.startTimeMs < active->startTimeMs) {
                    active = &spec;
                }
            }
        }
        return active;
    }

    static bool codecParamsCompatible(const AVCodecParameters* a, const AVCodecParameters* b) {
        if (!a || !b) return false;
        if (a->codec_type != b->codec_type ||
            a->codec_id != b->codec_id ||
            a->format != b->format ||
            a->width != b->width ||
            a->height != b->height ||
            a->sample_rate != b->sample_rate ||
            a->ch_layout.nb_channels != b->ch_layout.nb_channels) {
            return false;
        }
        if (a->extradata_size != b->extradata_size) {
            return false;
        }
        if (a->extradata_size > 0 &&
            std::memcmp(a->extradata, b->extradata, a->extradata_size) != 0) {
            return false;
        }
        return true;
    }

    struct SimpleVideoEncoderContext {
        AVFormatContext* formatCtx = nullptr;
        AVStream* videoStream = nullptr;
        AVCodecContext* codecCtx = nullptr;
        AVFrame* frame = nullptr;
        AVPacket* packet = nullptr;
        int width = 0;
        int height = 0;
        int fps = 30;
        AVPixelFormat pixelFormat = AV_PIX_FMT_YUV420P;
        int64_t nextPts = 0;

        ~SimpleVideoEncoderContext() {
            if (frame) av_frame_free(&frame);
            if (packet) av_packet_free(&packet);
            if (codecCtx) avcodec_free_context(&codecCtx);
            if (formatCtx) {
                if (!(formatCtx->oformat->flags & AVFMT_NOFILE) && formatCtx->pb) {
                    avio_closep(&formatCtx->pb);
                }
                avformat_free_context(formatCtx);
            }
        }
    };

    static int64_t chooseEncoderBitrate(int width, int height, int fps) {
        const int safeFps = std::max(fps, 24);
        const int64_t pixelsPerSecond = static_cast<int64_t>(width) * static_cast<int64_t>(height) * safeFps;
        int64_t bitrate = pixelsPerSecond / 12;
        bitrate = std::clamp<int64_t>(bitrate, 900000, 8000000);
        return bitrate;
    }

    static bool initSimpleVideoEncoder(
        const std::string& outputPath,
        int width,
        int height,
        int fps,
        SimpleVideoEncoderContext& ctx,
        std::string& errorOut) {
#if defined(VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE)
        if (avformat_alloc_output_context2(&ctx.formatCtx, nullptr, nullptr, outputPath.c_str()) < 0 || !ctx.formatCtx) {
            errorOut = "Failed to allocate output format";
            return false;
        }

        ctx.videoStream = avformat_new_stream(ctx.formatCtx, nullptr);
        if (!ctx.videoStream) {
            errorOut = "Failed to create output video stream";
            return false;
        }

        ctx.width = width;
        ctx.height = height;
        ctx.fps = std::max(fps, 1);

        struct EncoderCandidate {
            const AVCodec* codec;
            const char* name;
        };
        std::vector<EncoderCandidate> candidates;
        const char* codecNames[] = {
            "h264_mediacodec",      // Android hardware H264 — fastest
            "hevc_mediacodec",      // Android hardware HEVC
            "libx264",
            "libopenh264",
            "libopenh264enc",
            "h264_v4l2m2m_encoder",
            "mpeg4_v4l2m2m_encoder",
            "h264",
            "mpeg4",
        };
        for (const char* candidateName : codecNames) {
            if (const AVCodec* c = avcodec_find_encoder_by_name(candidateName)) {
                candidates.push_back({c, candidateName});
            }
        }
        if (const AVCodec* c = avcodec_find_encoder(AV_CODEC_ID_H264)) {
            candidates.push_back({c, "AV_CODEC_ID_H264"});
        }
        if (const AVCodec* c = avcodec_find_encoder(AV_CODEC_ID_MPEG4)) {
            candidates.push_back({c, "AV_CODEC_ID_MPEG4"});
        }
        if (candidates.empty()) {
            errorOut = "No video encoder available";
            return false;
        }

        bool encoderOpened = false;
        std::string lastEncoderError;
        for (const auto& candidate : candidates) {
            if (ctx.codecCtx) {
                avcodec_free_context(&ctx.codecCtx);
            }
            ctx.codecCtx = avcodec_alloc_context3(candidate.codec);
            if (!ctx.codecCtx) {
                lastEncoderError = std::string("Failed to allocate codec context for ") + candidate.name;
                continue;
            }

            ctx.codecCtx->codec_type = AVMEDIA_TYPE_VIDEO;
            ctx.codecCtx->codec_id = candidate.codec->id;
            ctx.codecCtx->pix_fmt = AV_PIX_FMT_YUV420P;
            ctx.codecCtx->width = width;
            ctx.codecCtx->height = height;
            ctx.codecCtx->framerate = AVRational{ctx.fps, 1};
            ctx.codecCtx->time_base = AVRational{1, ctx.fps};
            ctx.codecCtx->bit_rate = chooseEncoderBitrate(width, height, ctx.fps);
            ctx.codecCtx->gop_size = ctx.fps * 2;
            ctx.codecCtx->max_b_frames = 0;
            ctx.codecCtx->sample_aspect_ratio = AVRational{1, 1};
            ctx.codecCtx->thread_count = 4; // use 4 threads for faster encoding
            ctx.codecCtx->thread_type = FF_THREAD_FRAME;
            ctx.videoStream->time_base = ctx.codecCtx->time_base;
            ctx.videoStream->avg_frame_rate = ctx.codecCtx->framerate;
            ctx.pixelFormat = ctx.codecCtx->pix_fmt;

            LOGI(
                "[Export] Trying encoder=%s size=%dx%d pix_fmt=%s fps=%d/%d bitrate=%lld",
                candidate.name,
                width,
                height,
                av_get_pix_fmt_name(ctx.codecCtx->pix_fmt),
                ctx.codecCtx->framerate.num,
                ctx.codecCtx->framerate.den,
                static_cast<long long>(ctx.codecCtx->bit_rate));

            if (ctx.formatCtx->oformat->flags & AVFMT_GLOBALHEADER) {
                ctx.codecCtx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
            }

            AVDictionary* codecOptions = nullptr;
            if (std::strcmp(candidate.name, "h264_mediacodec") == 0 ||
                std::strcmp(candidate.name, "hevc_mediacodec") == 0) {
                // Android hardware encoder — use NV12 pixel format which MediaCodec prefers
                ctx.codecCtx->pix_fmt = AV_PIX_FMT_NV12;
                ctx.pixelFormat = AV_PIX_FMT_NV12;
                ctx.codecCtx->max_b_frames = 0;
                av_dict_set(&codecOptions, "ndk_codec", "1", 0);
            } else if (std::strcmp(candidate.name, "libx264") == 0) {
                av_dict_set(&codecOptions, "preset", "ultrafast", 0);
                av_dict_set(&codecOptions, "tune", "zerolatency", 0);
                av_dict_set(&codecOptions, "profile", "baseline", 0);
            } else if (std::strcmp(candidate.name, "libopenh264") == 0 ||
                       std::strcmp(candidate.name, "libopenh264enc") == 0) {
                av_dict_set(&codecOptions, "profile", "baseline", 0);
            }

            const int openRet = avcodec_open2(ctx.codecCtx, candidate.codec, &codecOptions);
            av_dict_free(&codecOptions);
            if (openRet < 0) {
                char errbuf[AV_ERROR_MAX_STRING_SIZE] = {};
                av_strerror(openRet, errbuf, sizeof(errbuf));
                lastEncoderError =
                    std::string("Failed to open output encoder: ") + candidate.name + " (" + errbuf + ")";
                LOGE("[Export] Encoder open failed for %s: %s", candidate.name, errbuf);
                continue;
            }

            LOGI(
                "[Export] Using encoder: %s pix_fmt=%s fps=%d/%d bitrate=%lld",
                candidate.name,
                av_get_pix_fmt_name(ctx.codecCtx->pix_fmt),
                ctx.codecCtx->framerate.num,
                ctx.codecCtx->framerate.den,
                static_cast<long long>(ctx.codecCtx->bit_rate));
            encoderOpened = true;
            break;
        }

        if (!encoderOpened) {
            errorOut = lastEncoderError.empty() ? "Failed to open any output encoder" : lastEncoderError;
            return false;
        }

        if (avcodec_parameters_from_context(ctx.videoStream->codecpar, ctx.codecCtx) < 0) {
            errorOut = "Failed to copy encoder parameters";
            return false;
        }
        if (!(ctx.formatCtx->oformat->flags & AVFMT_NOFILE) &&
            avio_open(&ctx.formatCtx->pb, outputPath.c_str(), AVIO_FLAG_WRITE) < 0) {
            errorOut = "Failed to open output file";
            return false;
        }
        if (avformat_write_header(ctx.formatCtx, nullptr) < 0) {
            errorOut = "Failed to write output header";
            return false;
        }

        ctx.frame = av_frame_alloc();
        ctx.packet = av_packet_alloc();
        if (!ctx.frame || !ctx.packet) {
            errorOut = "Failed to allocate output frame/packet";
            return false;
        }
        ctx.frame->format = ctx.pixelFormat;
        ctx.frame->width = width;
        ctx.frame->height = height;
        if (av_frame_get_buffer(ctx.frame, 32) < 0) {
            errorOut = "Failed to allocate output frame buffer";
            return false;
        }
        return true;
#else
        (void)outputPath; (void)width; (void)height; (void)fps; (void)ctx;
        errorOut = "FFmpeg encode not available";
        return false;
#endif
    }

    static bool encodeVideoFrame(SimpleVideoEncoderContext& ctx, AVFrame* frame, std::string& errorOut) {
#if defined(VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE)
        if (avcodec_send_frame(ctx.codecCtx, frame) < 0) {
            errorOut = "Failed to send frame to encoder";
            return false;
        }
        while (true) {
            const int ret = avcodec_receive_packet(ctx.codecCtx, ctx.packet);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
            if (ret < 0) {
                errorOut = "Failed to receive encoded packet";
                return false;
            }
            av_packet_rescale_ts(ctx.packet, ctx.codecCtx->time_base, ctx.videoStream->time_base);
            ctx.packet->stream_index = ctx.videoStream->index;
            if (av_interleaved_write_frame(ctx.formatCtx, ctx.packet) < 0) {
                av_packet_unref(ctx.packet);
                errorOut = "Failed to write encoded packet";
                return false;
            }
            av_packet_unref(ctx.packet);
        }
        return true;
#else
        (void)ctx; (void)frame;
        errorOut = "FFmpeg encode not available";
        return false;
#endif
    }

    static bool finalizeSimpleVideoEncoder(SimpleVideoEncoderContext& ctx, std::string& errorOut) {
#if defined(VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE)
        if (!encodeVideoFrame(ctx, nullptr, errorOut)) {
            return false;
        }
        if (av_write_trailer(ctx.formatCtx) < 0) {
            errorOut = "Failed to finalize output file";
            return false;
        }
        return true;
#else
        (void)ctx;
        errorOut = "FFmpeg encode not available";
        return false;
#endif
    }

    static bool copyDecodedFrameToEncoder(
        const VideoEngine::Backend::DecodedFrame& decoded,
        SimpleVideoEncoderContext& encoder,
        SwsContext*& sws,
        std::vector<uint8_t>& rgbaScratch,
        std::string& errorOut) {
#if defined(VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE)
        const int srcWidth = static_cast<int>(decoded.width);
        const int srcHeight = static_cast<int>(decoded.height);
        if (srcWidth <= 0 || srcHeight <= 0) {
            errorOut = "Invalid decoded frame size";
            return false;
        }

        const size_t pixelCount = static_cast<size_t>(srcWidth) * static_cast<size_t>(srcHeight);
        const size_t expectedRgbaBytes = pixelCount * 4;
        const size_t expectedRgbBytes = pixelCount * 3;

        const uint8_t* srcPixels = nullptr;
        if (decoded.rgb.size() >= expectedRgbaBytes) {
            srcPixels = decoded.rgb.data();
        } else if (decoded.rgb.size() >= expectedRgbBytes) {
            if (rgbaScratch.size() != expectedRgbaBytes) {
                rgbaScratch.resize(expectedRgbaBytes);
            }
            for (size_t i = 0; i < pixelCount; ++i) {
                const size_t srcIndex = i * 3;
                const size_t dstIndex = i * 4;
                rgbaScratch[dstIndex + 0] = decoded.rgb[srcIndex + 0];
                rgbaScratch[dstIndex + 1] = decoded.rgb[srcIndex + 1];
                rgbaScratch[dstIndex + 2] = decoded.rgb[srcIndex + 2];
                rgbaScratch[dstIndex + 3] = 0xFF;
            }
            srcPixels = rgbaScratch.data();
        } else {
            errorOut = "Decoded frame pixel buffer is incomplete";
            return false;
        }

        if (av_frame_make_writable(encoder.frame) < 0) {
            errorOut = "Output frame not writable";
            return false;
        }

        sws = sws_getCachedContext(
            sws,
            srcWidth,
            srcHeight,
            AV_PIX_FMT_RGBA,
            encoder.width,
            encoder.height,
            encoder.pixelFormat,
            SWS_BILINEAR,
            nullptr,
            nullptr,
            nullptr);
        if (!sws) {
            errorOut = "Failed to initialize export scaler";
            return false;
        }

        const uint8_t* srcData[4] = {srcPixels, nullptr, nullptr, nullptr};
        int srcLinesize[4] = {srcWidth * 4, 0, 0, 0};
        sws_scale(
            sws,
            srcData,
            srcLinesize,
            0,
            srcHeight,
            encoder.frame->data,
            encoder.frame->linesize);
        return true;
#else
        (void)decoded;
        (void)encoder;
        (void)sws;
        (void)rgbaScratch;
        errorOut = "FFmpeg transcode not available";
        return false;
#endif
    }

    static int64_t computeTimelineExportDurationMs(
        const std::vector<TimelineClipExportSpec>& clipSpecs) {
        int64_t durationMs = 0;
        for (const auto& spec : clipSpecs) {
            if (!spec.enabled || spec.path.empty()) continue;
            const int64_t endMs = spec.startTimeMs + std::max<int64_t>(1, spec.durationMs);
            durationMs = std::max(durationMs, endMs);
        }
        return durationMs;
    }

    static int visualTrackPriority(Clip::TrackRole role) {
        switch (role) {
            case Clip::TrackRole::MainVideo: return 0;
            case Clip::TrackRole::Overlay: return 1;
            default: return 2;
        }
    }

    static std::vector<const TimelineClipExportSpec*> collectActiveTimelineClipSpecsForMs(
        const std::vector<TimelineClipExportSpec>& clipSpecs,
        int64_t timelineMs) {
        std::vector<const TimelineClipExportSpec*> active;
        for (const auto& spec : clipSpecs) {
            if (!spec.enabled || spec.path.empty()) continue;
            if (spec.trackRole != Clip::TrackRole::MainVideo &&
                spec.trackRole != Clip::TrackRole::Overlay) {
                continue;
            }
            const int64_t startMs = spec.startTimeMs;
            const int64_t endMs = startMs + std::max<int64_t>(1, spec.durationMs);
            if (timelineMs < startMs || timelineMs >= endMs) continue;
            active.push_back(&spec);
        }
        std::sort(
            active.begin(),
            active.end(),
            [](const TimelineClipExportSpec* a, const TimelineClipExportSpec* b) {
                if (a->trackZOrder != b->trackZOrder) return a->trackZOrder < b->trackZOrder;
                const int priorityA = visualTrackPriority(a->trackRole);
                const int priorityB = visualTrackPriority(b->trackRole);
                if (priorityA != priorityB) return priorityA < priorityB;
                if (a->startTimeMs != b->startTimeMs) return a->startTimeMs < b->startTimeMs;
                return a->clipId < b->clipId;
            });
        return active;
    }

    struct ExportClipDecoderState {
        std::unique_ptr<VideoEngine::Backend::VideoDecoder> decoder;
        std::string openPath;
        SwsContext* rgbaScale = nullptr;
        std::vector<uint8_t> rgbaScratch;
        std::vector<uint8_t> processedLayerRgba;
        VideoEngine::Backend::DecodedFrame lastDecoded;
        bool hasLastDecoded = false;
        bool hasProcessedLayer = false;
        int processedWidth = 0;
        int processedHeight = 0;
        int64_t processedFramePtsMs = -1;
        int64_t approximateFrameMs = 33;
        int64_t sequentialDecodeWindowMs = 750;
        std::vector<VideoEngine::Backend::DecodedFrame> reverseFrameCache;
        int64_t reverseCacheStartMs = -1;
        int64_t reverseCacheEndMs = -1;
        int64_t reverseCacheWindowMs = 400;

        ~ExportClipDecoderState() {
            if (rgbaScale) {
                sws_freeContext(rgbaScale);
                rgbaScale = nullptr;
            }
            if (decoder && decoder->isOpen()) {
                decoder->close();
            }
        }
    };

    static void resetExportDecoderStateMetadata(ExportClipDecoderState& state) {
        state.lastDecoded = VideoEngine::Backend::DecodedFrame{};
        state.hasLastDecoded = false;
        state.processedLayerRgba.clear();
        state.hasProcessedLayer = false;
        state.processedWidth = 0;
        state.processedHeight = 0;
        state.processedFramePtsMs = -1;
        state.approximateFrameMs = 33;
        state.sequentialDecodeWindowMs = 750;
        state.reverseFrameCache.clear();
        state.reverseCacheStartMs = -1;
        state.reverseCacheEndMs = -1;
        state.reverseCacheWindowMs = 400;
        if (!state.decoder) {
            return;
        }
        const double fps = state.decoder->getFps();
        if (fps > 0.1) {
            const int64_t frameMs = std::max<int64_t>(
                1,
                static_cast<int64_t>(std::llround(1000.0 / fps)));
            state.approximateFrameMs = frameMs;
            state.sequentialDecodeWindowMs = std::clamp<int64_t>(frameMs * 24, 250, 1500);
            state.reverseCacheWindowMs = std::clamp<int64_t>(frameMs * 12, 240, 720);
        }
    }

    static bool selectReverseCachedFrame(
        const ExportClipDecoderState& state,
        int64_t sourceMs,
        int64_t frameToleranceMs,
        VideoEngine::Backend::DecodedFrame& decodedOut) {
        if (state.reverseFrameCache.empty()) {
            return false;
        }
        if (sourceMs < state.reverseCacheStartMs - frameToleranceMs ||
            sourceMs > state.reverseCacheEndMs + frameToleranceMs) {
            return false;
        }

        const VideoEngine::Backend::DecodedFrame* best = nullptr;
        int64_t bestDelta = std::numeric_limits<int64_t>::max();
        for (const auto& candidate : state.reverseFrameCache) {
            const int64_t delta = std::llabs(candidate.ptsMs - sourceMs);
            if (!best || delta < bestDelta) {
                best = &candidate;
                bestDelta = delta;
            }
            if (candidate.ptsMs > sourceMs && delta > bestDelta) {
                break;
            }
        }
        if (!best) {
            return false;
        }
        decodedOut = *best;
        return true;
    }

    static bool refillReverseFrameCache(
        const TimelineClipExportSpec& clipSpec,
        int64_t sourceMs,
        int64_t frameToleranceMs,
        ExportClipDecoderState& state,
        VideoEngine::Backend::DecodedFrame& decodedOut,
        std::string& errorOut) {
        if (!state.decoder) {
            errorOut = "Reverse export decoder unavailable";
            return false;
        }

        const int64_t windowMs = std::max<int64_t>(state.reverseCacheWindowMs, frameToleranceMs * 4);
        const int64_t cacheStartMs = std::max<int64_t>(0, sourceMs - windowMs);
        const int64_t cacheEndTargetMs = sourceMs + frameToleranceMs;
        state.reverseFrameCache.clear();
        state.reverseCacheStartMs = cacheStartMs;
        state.reverseCacheEndMs = cacheStartMs;
        LOGI("[Export] reverse cache refill clip=%d target=%lld windowStart=%lld windowMs=%lld",
             clipSpec.clipId,
             static_cast<long long>(sourceMs),
             static_cast<long long>(cacheStartMs),
             static_cast<long long>(windowMs));

        if (!state.decoder->seekForPreview(cacheStartMs)) {
            if (!state.decoder->seekTo(cacheStartMs)) {
                errorOut = std::string("Failed to seek reverse export cache: ") + clipSpec.path;
                return false;
            }
        }

        VideoEngine::Backend::DecodedFrame candidate;
        const size_t maxCachedFrames = 24;
        while (state.decoder->decodeNextFrame(candidate)) {
            state.reverseCacheEndMs = std::max<int64_t>(state.reverseCacheEndMs, candidate.ptsMs);
            if (candidate.ptsMs + frameToleranceMs < cacheStartMs) {
                continue;
            }
            state.reverseFrameCache.push_back(candidate);
            if (state.reverseFrameCache.size() > maxCachedFrames) {
                state.reverseFrameCache.erase(state.reverseFrameCache.begin());
                state.reverseCacheStartMs = state.reverseFrameCache.front().ptsMs;
            }
            if (candidate.ptsMs + frameToleranceMs >= cacheEndTargetMs) {
                break;
            }
        }

        if (state.reverseFrameCache.empty()) {
            errorOut = std::string("Failed to populate reverse export cache: ") + clipSpec.path;
            return false;
        }
        state.reverseCacheStartMs = state.reverseFrameCache.front().ptsMs;
        state.reverseCacheEndMs = state.reverseFrameCache.back().ptsMs;
        LOGI("[Export] reverse cache ready clip=%d frames=%zu range=%lld..%lld",
             clipSpec.clipId,
             state.reverseFrameCache.size(),
             static_cast<long long>(state.reverseCacheStartMs),
             static_cast<long long>(state.reverseCacheEndMs));

        if (!selectReverseCachedFrame(state, sourceMs, frameToleranceMs, decodedOut)) {
            decodedOut = state.reverseFrameCache.back();
        }
        state.lastDecoded = decodedOut;
        state.hasLastDecoded = true;
        return true;
    }

    static bool decodeClipFrameForExport(
        const TimelineClipExportSpec& clipSpec,
        int64_t sourceMs,
        int outputWidth,
        int outputHeight,
        ExportClipDecoderState& state,
        VideoEngine::Backend::DecodedFrame& decodedOut,
        std::string& errorOut) {
        if (!state.decoder || state.openPath != clipSpec.path) {
            if (state.rgbaScale) {
                sws_freeContext(state.rgbaScale);
                state.rgbaScale = nullptr;
            }
            if (state.decoder && state.decoder->isOpen()) {
                state.decoder->close();
            }
            state.decoder.reset();
            state.openPath.clear();
            state.rgbaScratch.clear();

            state.decoder = std::make_unique<VideoEngine::Backend::VideoDecoder>();
            if (!state.decoder->open(clipSpec.path)) {
                errorOut = std::string("Failed to open clip for export: ") + clipSpec.path;
                return false;
            }
            state.decoder->setPreviewScaleLimit(std::max(outputWidth, outputHeight));
            state.openPath = clipSpec.path;
            resetExportDecoderStateMetadata(state);
        }

        if (isStillImagePath(clipSpec.path)) {
            if (state.hasLastDecoded) {
                decodedOut = state.lastDecoded;
                decodedOut.ptsMs = 0;
                return true;
            }

            auto decodeStillFrame = [&](VideoEngine::Backend::DecodedFrame& decoded) -> bool {
                if (!state.decoder || !state.decoder->decodeNextFrame(decoded)) {
                    return false;
                }
                if (decoded.width == 0 || decoded.height == 0 || decoded.rgb.empty()) {
                    return false;
                }
                decoded.ptsMs = 0;
                return true;
            };

            VideoEngine::Backend::DecodedFrame decoded;
            if (!decodeStillFrame(decoded)) {
                if (state.decoder && state.decoder->isOpen()) {
                    state.decoder->close();
                }
                if (!state.decoder->open(clipSpec.path)) {
                    errorOut = std::string("Failed to reopen still image for export: ") + clipSpec.path;
                    return false;
                }
                state.decoder->setPreviewScaleLimit(std::max(outputWidth, outputHeight));
                resetExportDecoderStateMetadata(state);
                if (!decodeStillFrame(decoded)) {
                    errorOut = std::string("Failed to decode still image during export: ") + clipSpec.path;
                    return false;
                }
            }

            state.lastDecoded = decoded;
            state.hasLastDecoded = true;
            decodedOut = decoded;
            return true;
        }

        const int64_t frameToleranceMs =
            std::max<int64_t>(2, (state.approximateFrameMs * 2) / 3);
        const int64_t clampedSourceMs = std::max<int64_t>(0, sourceMs);

        if (clipSpec.reversePlayback) {
            if (selectReverseCachedFrame(state, clampedSourceMs, frameToleranceMs, decodedOut)) {
                state.lastDecoded = decodedOut;
                state.hasLastDecoded = true;
                return true;
            }
            if (refillReverseFrameCache(
                    clipSpec,
                    clampedSourceMs,
                    frameToleranceMs,
                    state,
                    decodedOut,
                    errorOut)) {
                return true;
            }
            if (!errorOut.empty()) {
                return false;
            }
        }

        if (state.hasLastDecoded) {
            const int64_t previousMs = state.lastDecoded.ptsMs;
            const int64_t deltaMs = clampedSourceMs - previousMs;

            if (std::llabs(deltaMs) <= frameToleranceMs) {
                decodedOut = state.lastDecoded;
                return true;
            }

            if (deltaMs > 0 && deltaMs <= state.sequentialDecodeWindowMs) {
                VideoEngine::Backend::DecodedFrame candidate;
                while (state.decoder->decodeNextFrame(candidate)) {
                    state.lastDecoded = candidate;
                    state.hasLastDecoded = true;
                    if (candidate.ptsMs + frameToleranceMs >= clampedSourceMs) {
                        decodedOut = candidate;
                        return true;
                    }
                }
                decodedOut = state.lastDecoded;
                return true;
            }
        }

        VideoEngine::Backend::DecodedFrame decoded;
        if (state.decoder->seekTo(clampedSourceMs) && state.decoder->decodeNextFrame(decoded)) {
            state.lastDecoded = decoded;
            state.hasLastDecoded = true;
            decodedOut = decoded;
            return true;
        }
        if (state.decoder->seekForPreview(clampedSourceMs) && state.decoder->decodeNextFrame(decoded)) {
            state.lastDecoded = decoded;
            state.hasLastDecoded = true;
            decodedOut = decoded;
            return true;
        }

        if (state.hasLastDecoded) {
            decodedOut = state.lastDecoded;
            return true;
        }

        errorOut = std::string("Failed to decode clip during export: ") + clipSpec.path;
        return false;
    }

    static bool convertDecodedFrameToRgba(
        const VideoEngine::Backend::DecodedFrame& decoded,
        int outputWidth,
        int outputHeight,
        SwsContext*& sws,
        std::vector<uint8_t>& rgbaOut,
        std::vector<uint8_t>& rgbaScratch,
        std::string& errorOut) {
#if defined(VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE)
        const int srcWidth = static_cast<int>(decoded.width);
        const int srcHeight = static_cast<int>(decoded.height);
        if (srcWidth <= 0 || srcHeight <= 0) {
            errorOut = "Invalid decoded frame size";
            return false;
        }

        const size_t pixelCount = static_cast<size_t>(srcWidth) * static_cast<size_t>(srcHeight);
        const size_t expectedRgbaBytes = pixelCount * 4;
        const size_t expectedRgbBytes = pixelCount * 3;

        const uint8_t* srcPixels = nullptr;
        if (decoded.rgb.size() >= expectedRgbaBytes) {
            srcPixels = decoded.rgb.data();
        } else if (decoded.rgb.size() >= expectedRgbBytes) {
            if (rgbaScratch.size() != expectedRgbaBytes) {
                rgbaScratch.resize(expectedRgbaBytes);
            }
            for (size_t i = 0; i < pixelCount; ++i) {
                const size_t srcIndex = i * 3;
                const size_t dstIndex = i * 4;
                rgbaScratch[dstIndex + 0] = decoded.rgb[srcIndex + 0];
                rgbaScratch[dstIndex + 1] = decoded.rgb[srcIndex + 1];
                rgbaScratch[dstIndex + 2] = decoded.rgb[srcIndex + 2];
                rgbaScratch[dstIndex + 3] = 0xFF;
            }
            srcPixels = rgbaScratch.data();
        } else {
            errorOut = "Decoded frame pixel buffer is incomplete";
            return false;
        }

        if (srcWidth == outputWidth && srcHeight == outputHeight) {
            rgbaOut.assign(srcPixels, srcPixels + expectedRgbaBytes);
            return true;
        }

        rgbaOut.resize(static_cast<size_t>(outputWidth) * static_cast<size_t>(outputHeight) * 4);
        sws = sws_getCachedContext(
            sws,
            srcWidth,
            srcHeight,
            AV_PIX_FMT_RGBA,
            outputWidth,
            outputHeight,
            AV_PIX_FMT_RGBA,
            SWS_BILINEAR,
            nullptr,
            nullptr,
            nullptr);
        if (!sws) {
            errorOut = "Failed to initialize RGBA scaler";
            return false;
        }

        const uint8_t* srcData[4] = {srcPixels, nullptr, nullptr, nullptr};
        int srcLinesize[4] = {srcWidth * 4, 0, 0, 0};
        uint8_t* dstData[4] = {rgbaOut.data(), nullptr, nullptr, nullptr};
        int dstLinesize[4] = {outputWidth * 4, 0, 0, 0};
        sws_scale(sws, srcData, srcLinesize, 0, srcHeight, dstData, dstLinesize);
        return true;
#else
        (void)decoded;
        (void)outputWidth;
        (void)outputHeight;
        (void)sws;
        (void)rgbaOut;
        (void)rgbaScratch;
        errorOut = "FFmpeg transcode not available";
        return false;
#endif
    }

    static void applyEffectsToRgbaBuffer(
        std::vector<uint8_t>& rgba,
        int width,
        int height,
        const TimelineClipExportSpec& spec);

    static void applyChromaKeyToRgbaBuffer(
        std::vector<uint8_t>& rgba,
        int width,
        int height,
        const TimelineClipExportSpec& spec);

    static bool prepareProcessedLayerRgbaForExport(
        const TimelineClipExportSpec& clipSpec,
        const VideoEngine::Backend::DecodedFrame& decoded,
        int outputWidth,
        int outputHeight,
        ExportClipDecoderState& state,
        std::string& errorOut) {
        const bool canReuseProcessedLayer =
            state.hasProcessedLayer &&
            state.processedWidth == outputWidth &&
            state.processedHeight == outputHeight &&
            state.processedFramePtsMs == decoded.ptsMs &&
            state.processedLayerRgba.size() ==
                static_cast<size_t>(outputWidth) * static_cast<size_t>(outputHeight) * 4;
        if (canReuseProcessedLayer) {
            return true;
        }

        if (!convertDecodedFrameToRgba(
                decoded,
                outputWidth,
                outputHeight,
                state.rgbaScale,
                state.processedLayerRgba,
                state.rgbaScratch,
                errorOut)) {
            return false;
        }

        applyEffectsToRgbaBuffer(state.processedLayerRgba, outputWidth, outputHeight, clipSpec);
        applyChromaKeyToRgbaBuffer(state.processedLayerRgba, outputWidth, outputHeight, clipSpec);

        state.hasProcessedLayer = true;
        state.processedWidth = outputWidth;
        state.processedHeight = outputHeight;
        state.processedFramePtsMs = decoded.ptsMs;
        return true;
    }

    static void applyEffectsToRgbaBuffer(
        std::vector<uint8_t>& rgba,
        int width,
        int height,
        const TimelineClipExportSpec& spec) {
        if (!spec.effectsEnabled || rgba.empty()) {
            return;
        }
        const float brightness = spec.brightness;
        const float contrast = spec.contrast;
        const float saturation = spec.saturation;
        const size_t pixelCount = static_cast<size_t>(width) * static_cast<size_t>(height);
        for (size_t i = 0; i < pixelCount; ++i) {
            uint8_t* px = rgba.data() + (i * 4);
            float r = px[0] / 255.0f;
            float g = px[1] / 255.0f;
            float b = px[2] / 255.0f;
            r = (r - 0.5f) * contrast + 0.5f + brightness;
            g = (g - 0.5f) * contrast + 0.5f + brightness;
            b = (b - 0.5f) * contrast + 0.5f + brightness;
            const float luma = (0.2126f * r) + (0.7152f * g) + (0.0722f * b);
            r = luma + (r - luma) * saturation;
            g = luma + (g - luma) * saturation;
            b = luma + (b - luma) * saturation;
            px[0] = static_cast<uint8_t>(std::clamp(r, 0.0f, 1.0f) * 255.0f);
            px[1] = static_cast<uint8_t>(std::clamp(g, 0.0f, 1.0f) * 255.0f);
            px[2] = static_cast<uint8_t>(std::clamp(b, 0.0f, 1.0f) * 255.0f);
        }
    }

    static inline float smoothstep01(float edge0, float edge1, float value) {
        const float width = std::max(0.0001f, edge1 - edge0);
        const float t = std::clamp((value - edge0) / width, 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    static void applyChromaKeyToRgbaBuffer(
        std::vector<uint8_t>& rgba,
        int width,
        int height,
        const TimelineClipExportSpec& spec) {
        if (!spec.chromaEnabled || rgba.empty()) {
            return;
        }
        const float keyR = spec.chromaIsBlue ? 0.12f : 0.12f;
        const float keyG = spec.chromaIsBlue ? 0.24f : 0.94f;
        const float keyB = spec.chromaIsBlue ? 0.92f : 0.14f;
        const float similarity = std::clamp(spec.chromaSimilarity, 0.02f, 1.0f);
        const float smoothness = std::clamp(spec.chromaSmoothness, 0.01f, 1.0f);
        const float spill = std::clamp(spec.chromaSpill, 0.0f, 1.0f);
        const float keyLuma = (0.299f * keyR) + (0.587f * keyG) + (0.114f * keyB);
        const float keyCb = (keyB - keyLuma) * 0.564f;
        const float keyCr = (keyR - keyLuma) * 0.713f;
        const size_t pixelCount = static_cast<size_t>(width) * static_cast<size_t>(height);
        for (size_t i = 0; i < pixelCount; ++i) {
            uint8_t* px = rgba.data() + (i * 4);
            float r = px[0] / 255.0f;
            float g = px[1] / 255.0f;
            float b = px[2] / 255.0f;
            const float luma = (0.299f * r) + (0.587f * g) + (0.114f * b);
            const float cb = (b - luma) * 0.564f;
            const float cr = (r - luma) * 0.713f;
            const float chromaDist = std::sqrt(
                ((cb - keyCb) * (cb - keyCb)) +
                ((cr - keyCr) * (cr - keyCr)));
            const float keyDominance = spec.chromaIsBlue
                ? (b - std::max(r, g))
                : (g - std::max(r, b));
            const float dominanceCenter = 0.03f + (similarity * 0.42f);
            const float dominanceSoftness = 0.015f + (smoothness * 0.20f);
            const float matteByDominance = smoothstep01(
                dominanceCenter - dominanceSoftness,
                dominanceCenter + dominanceSoftness,
                keyDominance);
            const float distanceCenter = 0.015f + (similarity * 0.26f);
            const float distanceSoftness = 0.025f + (smoothness * 0.28f);
            const float matteByDistance = 1.0f - smoothstep01(
                distanceCenter,
                distanceCenter + distanceSoftness,
                chromaDist);
            const float matte = std::clamp(
                std::max(matteByDistance, matteByDominance * 0.96f),
                0.0f,
                1.0f);
            const float alpha = 1.0f - matte;
            if (spill > 0.0f && matte > 0.0f) {
                const float spillMix = matte * spill;
                if (spec.chromaIsBlue) {
                    const float neutralBlue = (r + g) * 0.5f;
                    b = (b * (1.0f - spillMix)) + (neutralBlue * spillMix);
                } else {
                    const float neutralGreen = (r + b) * 0.5f;
                    g = (g * (1.0f - spillMix)) + (neutralGreen * spillMix);
                }
                px[0] = static_cast<uint8_t>(std::clamp(r, 0.0f, 1.0f) * 255.0f);
                px[1] = static_cast<uint8_t>(std::clamp(g, 0.0f, 1.0f) * 255.0f);
                px[2] = static_cast<uint8_t>(std::clamp(b, 0.0f, 1.0f) * 255.0f);
            }
            px[3] = static_cast<uint8_t>((px[3] / 255.0f) * alpha * 255.0f);
        }
    }

    static void blendFullFrameRgba(
        const std::vector<uint8_t>& src,
        int width,
        int height,
        float opacity,
        std::vector<uint8_t>& dst) {
        if (src.empty() || dst.empty()) {
            return;
        }
        const float layerOpacity = std::clamp(opacity, 0.0f, 1.0f);
        const size_t pixelCount = static_cast<size_t>(width) * static_cast<size_t>(height);
        for (size_t i = 0; i < pixelCount; ++i) {
            const uint8_t* srcPx = src.data() + (i * 4);
            uint8_t* dstPx = dst.data() + (i * 4);
            const float srcAlpha = (srcPx[3] / 255.0f) * layerOpacity;
            const float dstAlpha = dstPx[3] / 255.0f;
            const float outAlpha = srcAlpha + dstAlpha * (1.0f - srcAlpha);
            if (outAlpha <= 0.0001f) {
                dstPx[0] = 0;
                dstPx[1] = 0;
                dstPx[2] = 0;
                dstPx[3] = 0;
                continue;
            }
            for (int c = 0; c < 3; ++c) {
                const float srcColor = srcPx[c] / 255.0f;
                const float dstColor = dstPx[c] / 255.0f;
                const float outColor = (srcColor * srcAlpha) + (dstColor * dstAlpha * (1.0f - srcAlpha));
                dstPx[c] = static_cast<uint8_t>(std::clamp(outColor / outAlpha, 0.0f, 1.0f) * 255.0f);
            }
            dstPx[3] = static_cast<uint8_t>(std::clamp(outAlpha, 0.0f, 1.0f) * 255.0f);
        }
    }

    struct OverlayRenderState {
        float x = 0.5f;
        float y = 0.5f;
        float scale = 1.0f;
        float opacity = 1.0f;
        float rotation = 0.0f;
    };

    static OverlayRenderState resolveOverlayRenderState(
        const TextOverlay& overlay,
        int64_t timelineMs) {
        OverlayRenderState state;
        state.x = overlay.x;
        state.y = overlay.y;
        state.scale = overlay.scale;
        state.opacity = overlay.opacity;
        state.rotation = overlay.rotation;

        if (!overlay.keyframes.empty()) {
            const auto& kfs = overlay.keyframes;
            if (kfs.size() == 1) {
                state.x = kfs[0].posX;
                state.y = kfs[0].posY;
                state.scale = kfs[0].scale;
                state.opacity = kfs[0].opacity;
            } else if (timelineMs <= kfs.front().timeMs) {
                state.x = kfs.front().posX;
                state.y = kfs.front().posY;
                state.scale = kfs.front().scale;
                state.opacity = kfs.front().opacity;
            } else if (timelineMs >= kfs.back().timeMs) {
                state.x = kfs.back().posX;
                state.y = kfs.back().posY;
                state.scale = kfs.back().scale;
                state.opacity = kfs.back().opacity;
            } else {
                size_t idx = 0;
                while (idx + 1 < kfs.size() && kfs[idx + 1].timeMs <= timelineMs) {
                    ++idx;
                }
                const auto& kf0 = kfs[idx];
                const auto& kf1 = kfs[idx + 1];
                const float dt = static_cast<float>(kf1.timeMs - kf0.timeMs);
                float t = 0.0f;
                if (dt > 0.0f) {
                    t = static_cast<float>(timelineMs - kf0.timeMs) / dt;
                }
                t = std::clamp(t, 0.0f, 1.0f);
                auto lerpf = [](float a, float b, float t) -> float {
                    return a + (b - a) * t;
                };
                state.x = lerpf(kf0.posX, kf1.posX, t);
                state.y = lerpf(kf0.posY, kf1.posY, t);
                state.scale = lerpf(kf0.scale, kf1.scale, t);
                state.opacity = lerpf(kf0.opacity, kf1.opacity, t);
            }
        } else {
            if (overlay.fadeInMs > 0 && timelineMs < overlay.startTime + overlay.fadeInMs) {
                state.opacity *= static_cast<float>(timelineMs - overlay.startTime) /
                    static_cast<float>(overlay.fadeInMs);
            }
            if (overlay.fadeOutMs > 0 &&
                overlay.endTime > 0 &&
                timelineMs > overlay.endTime - overlay.fadeOutMs) {
                state.opacity *= static_cast<float>(overlay.endTime - timelineMs) /
                    static_cast<float>(overlay.fadeOutMs);
            }
        }

        state.scale = std::max(0.01f, state.scale);
        state.opacity = std::clamp(state.opacity, 0.0f, 1.0f);
        return state;
    }

    static void alphaBlendPixel(
        const uint8_t* src,
        float alpha,
        uint8_t* dst) {
        const float srcAlpha = (src[3] / 255.0f) * alpha;
        const float dstAlpha = dst[3] / 255.0f;
        const float outAlpha = srcAlpha + dstAlpha * (1.0f - srcAlpha);
        if (outAlpha <= 0.0001f) {
            dst[0] = 0;
            dst[1] = 0;
            dst[2] = 0;
            dst[3] = 0;
            return;
        }
        for (int c = 0; c < 3; ++c) {
            const float srcColor = src[c] / 255.0f;
            const float dstColor = dst[c] / 255.0f;
            const float outColor =
                (srcColor * srcAlpha) + (dstColor * dstAlpha * (1.0f - srcAlpha));
            dst[c] = static_cast<uint8_t>(
                std::clamp(outColor / outAlpha, 0.0f, 1.0f) * 255.0f);
        }
        dst[3] = static_cast<uint8_t>(std::clamp(outAlpha, 0.0f, 1.0f) * 255.0f);
    }

    static void compositeBitmapOverlayOnRgba(
        const OverlayCpuBitmap& bmp,
        const OverlayRenderState& state,
        int frameWidth,
        int frameHeight,
        std::vector<uint8_t>& frameBuf) {
        if (bmp.rgba.empty() || frameBuf.empty() || bmp.width <= 0 || bmp.height <= 0) {
            return;
        }

        const float scaledWidth = std::max(1.0f, static_cast<float>(bmp.width) * state.scale);
        const float scaledHeight = std::max(1.0f, static_cast<float>(bmp.height) * state.scale);
        const float centerX = state.x * static_cast<float>(frameWidth);
        const float centerY = state.y * static_cast<float>(frameHeight);
        const float halfW = scaledWidth * 0.5f;
        const float halfH = scaledHeight * 0.5f;
        const float angleRad = state.rotation * 0.01745329251994329577f;
        const float cosA = std::cos(angleRad);
        const float sinA = std::sin(angleRad);

        auto rotatePoint = [&](float localX, float localY) {
            return std::pair<float, float>{
                centerX + (localX * cosA) - (localY * sinA),
                centerY + (localX * sinA) + (localY * cosA)
            };
        };

        const auto p0 = rotatePoint(-halfW, -halfH);
        const auto p1 = rotatePoint(halfW, -halfH);
        const auto p2 = rotatePoint(halfW, halfH);
        const auto p3 = rotatePoint(-halfW, halfH);
        const float minXf = std::min(std::min(p0.first, p1.first), std::min(p2.first, p3.first));
        const float maxXf = std::max(std::max(p0.first, p1.first), std::max(p2.first, p3.first));
        const float minYf = std::min(std::min(p0.second, p1.second), std::min(p2.second, p3.second));
        const float maxYf = std::max(std::max(p0.second, p1.second), std::max(p2.second, p3.second));

        const int minX = std::max(0, static_cast<int>(std::floor(minXf)));
        const int maxX = std::min(frameWidth - 1, static_cast<int>(std::ceil(maxXf)));
        const int minY = std::max(0, static_cast<int>(std::floor(minYf)));
        const int maxY = std::min(frameHeight - 1, static_cast<int>(std::ceil(maxYf)));
        if (minX > maxX || minY > maxY) {
            return;
        }

        for (int fy = minY; fy <= maxY; ++fy) {
            for (int fx = minX; fx <= maxX; ++fx) {
                const float dx = (static_cast<float>(fx) + 0.5f) - centerX;
                const float dy = (static_cast<float>(fy) + 0.5f) - centerY;

                // Apply inverse rotation before mapping into source space.
                const float localX = (dx * cosA) + (dy * sinA);
                const float localY = (-dx * sinA) + (dy * cosA);
                const float normX = (localX + halfW) / scaledWidth;
                const float normY = (localY + halfH) / scaledHeight;
                if (normX < 0.0f || normX > 1.0f || normY < 0.0f || normY > 1.0f) {
                    continue;
                }

                const int srcX = std::clamp(
                    static_cast<int>(normX * static_cast<float>(bmp.width)),
                    0,
                    bmp.width - 1);
                const int srcY = std::clamp(
                    static_cast<int>(normY * static_cast<float>(bmp.height)),
                    0,
                    bmp.height - 1);

                const uint8_t* src = bmp.rgba.data() + ((srcY * bmp.width + srcX) * 4);
                uint8_t* dst = frameBuf.data() + ((fy * frameWidth + fx) * 4);
                alphaBlendPixel(src, state.opacity, dst);
            }
        }
    }

    static void compositeTextOverlaysOnRgba(
        int64_t timelineMs,
        int frameWidth,
        int frameHeight,
        std::vector<uint8_t>& frameBuf) {
        if (g_overlayCpuBitmaps.empty() || frameBuf.empty()) {
            return;
        }
        if (g_orderDirty) {
            std::sort(
                g_textOverlayOrder.begin(),
                g_textOverlayOrder.end(),
                [](int64_t a, int64_t b) {
                    const auto overlayA = g_textOverlays.find(a);
                    const auto overlayB = g_textOverlays.find(b);
                    int zA = 0;
                    int zB = 0;
                    if (overlayA != g_textOverlays.end()) zA = overlayA->second.zOrder;
                    if (overlayB != g_textOverlays.end()) zB = overlayB->second.zOrder;
                    if (zA == zB) {
                        return a < b;
                    }
                    return zA < zB;
                });
            g_orderDirty = false;
        }

        std::vector<int64_t> orderedIds = g_textOverlayOrder;
        for (const auto& [overlayId, _] : g_overlayCpuBitmaps) {
            if (std::find(orderedIds.begin(), orderedIds.end(), overlayId) == orderedIds.end()) {
                orderedIds.push_back(overlayId);
            }
        }
        std::sort(
            orderedIds.begin(),
            orderedIds.end(),
            [](int64_t a, int64_t b) {
                const auto overlayA = g_textOverlays.find(a);
                const auto overlayB = g_textOverlays.find(b);
                int zA = 0;
                int zB = 0;
                if (overlayA != g_textOverlays.end()) zA = overlayA->second.zOrder;
                if (overlayB != g_textOverlays.end()) zB = overlayB->second.zOrder;
                if (zA == zB) {
                    return a < b;
                }
                return zA < zB;
            });

        for (int64_t overlayId : orderedIds) {
            const auto bitmapIt = g_overlayCpuBitmaps.find(overlayId);
            if (bitmapIt == g_overlayCpuBitmaps.end()) {
                continue;
            }
            const auto overlayIt = g_textOverlays.find(overlayId);
            if (overlayIt == g_textOverlays.end()) continue;
            const TextOverlay& overlay = overlayIt->second;
            if (!overlay.enabled) continue;
            if (timelineMs < overlay.startTime) continue;
            if (overlay.endTime != -1 && timelineMs > overlay.endTime) continue;

            const OverlayRenderState state = resolveOverlayRenderState(overlay, timelineMs);
            if (state.opacity <= 0.0001f) {
                continue;
            }
            compositeBitmapOverlayOnRgba(
                bitmapIt->second,
                state,
                frameWidth,
                frameHeight,
                frameBuf);
        }
    }

    static bool transcodeTimelineSpecsToMp4(
        const std::vector<TimelineClipExportSpec>& clipSpecs,
        const std::string& outputPath,
        int outputWidth,
        int outputHeight,
        int outputFps,
        const std::function<bool(int)>& onProgress,
        std::string& errorOut) {
#if defined(VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE)
        if (clipSpecs.empty()) {
            errorOut = "No timeline clip specs available";
            return false;
        }

        const int64_t totalDurationMs = computeTimelineExportDurationMs(clipSpecs);
        if (totalDurationMs <= 0) {
            errorOut = "Timeline duration is zero";
            return false;
        }

        auto normalizeEven = [](int value) {
            value = std::max(value, 2);
            return (value % 2 == 0) ? value : (value - 1);
        };
        outputWidth = normalizeEven(outputWidth);
        outputHeight = normalizeEven(outputHeight);

        std::remove(outputPath.c_str());
        SimpleVideoEncoderContext encoder;
        if (!initSimpleVideoEncoder(
                outputPath,
                outputWidth,
                outputHeight,
                std::max(1, outputFps),
                encoder,
                errorOut)) {
            std::remove(outputPath.c_str());
            return false;
        }

        const int safeFps = std::max(1, outputFps);
        const int64_t totalFrames =
            std::max<int64_t>(1, (totalDurationMs * static_cast<int64_t>(safeFps) + 999) / 1000);

        std::map<int, ExportClipDecoderState> decoderStates;
        SwsContext* canvasToYuv = nullptr;
        std::vector<uint8_t> composedRgba;
        int lastProgress = -1;

        auto cleanupFrameConverter = [&]() {
            if (canvasToYuv) {
                sws_freeContext(canvasToYuv);
                canvasToYuv = nullptr;
            }
        };

        for (int64_t frameIndex = 0; frameIndex < totalFrames; ++frameIndex) {
            const int64_t timelineMs = std::min<int64_t>(
                (frameIndex * 1000) / safeFps,
                totalDurationMs - 1);
            const auto activeClips = collectActiveTimelineClipSpecsForMs(clipSpecs, timelineMs);
            composedRgba.assign(
                static_cast<size_t>(outputWidth) * static_cast<size_t>(outputHeight) * 4,
                0);
            bool hasCompositedVisualLayer = false;

            for (const TimelineClipExportSpec* clipSpec : activeClips) {
                if (!clipSpec) continue;
                auto& state = decoderStates[clipSpec->clipId];
                const int64_t sourceMs = std::max<int64_t>(
                    0,
                    mapTimelineClipToSourceMs(*clipSpec, timelineMs, /*ignoreFreeze=*/false));
                VideoEngine::Backend::DecodedFrame decoded;
                if (!decodeClipFrameForExport(
                        *clipSpec,
                        sourceMs,
                        outputWidth,
                        outputHeight,
                        state,
                        decoded,
                        errorOut)) {
                    cleanupFrameConverter();
                    std::remove(outputPath.c_str());
                    return false;
                }

                if (!prepareProcessedLayerRgbaForExport(
                        *clipSpec,
                        decoded,
                        outputWidth,
                        outputHeight,
                        state,
                        errorOut)) {
                    cleanupFrameConverter();
                    std::remove(outputPath.c_str());
                    return false;
                }

                if (!hasCompositedVisualLayer && clipSpec->opacity >= 0.999f) {
                    std::copy(
                        state.processedLayerRgba.begin(),
                        state.processedLayerRgba.end(),
                        composedRgba.begin());
                } else {
                    blendFullFrameRgba(
                        state.processedLayerRgba,
                        outputWidth,
                        outputHeight,
                        clipSpec->opacity,
                        composedRgba);
                }
                hasCompositedVisualLayer = true;
            }

            compositeTextOverlaysOnRgba(timelineMs, outputWidth, outputHeight, composedRgba);

            if (av_frame_make_writable(encoder.frame) < 0) {
                errorOut = "Output frame not writable";
                cleanupFrameConverter();
                std::remove(outputPath.c_str());
                return false;
            }

            canvasToYuv = sws_getCachedContext(
                canvasToYuv,
                outputWidth,
                outputHeight,
                AV_PIX_FMT_RGBA,
                outputWidth,
                outputHeight,
                encoder.pixelFormat,
                SWS_BILINEAR,
                nullptr,
                nullptr,
                nullptr);
            if (!canvasToYuv) {
                errorOut = "Failed to initialize export canvas converter";
                cleanupFrameConverter();
                std::remove(outputPath.c_str());
                return false;
            }

            const uint8_t* srcData[4] = {composedRgba.data(), nullptr, nullptr, nullptr};
            int srcLinesize[4] = {outputWidth * 4, 0, 0, 0};
            sws_scale(
                canvasToYuv,
                srcData,
                srcLinesize,
                0,
                outputHeight,
                encoder.frame->data,
                encoder.frame->linesize);

            encoder.frame->pts = encoder.nextPts++;
            if (!encodeVideoFrame(encoder, encoder.frame, errorOut)) {
                cleanupFrameConverter();
                std::remove(outputPath.c_str());
                return false;
            }

            int progress = static_cast<int>(
                std::clamp((frameIndex * 100) / totalFrames, int64_t(0), int64_t(99)));
            if (progress != lastProgress) {
                lastProgress = progress;
                if (onProgress && !onProgress(progress)) {
                    errorOut = "Export cancelled";
                    cleanupFrameConverter();
                    std::remove(outputPath.c_str());
                    return false;
                }
            }
        }

        cleanupFrameConverter();
        if (!finalizeSimpleVideoEncoder(encoder, errorOut)) {
            std::remove(outputPath.c_str());
            return false;
        }
        if (onProgress && !onProgress(100)) {
            errorOut = "Export cancelled";
            std::remove(outputPath.c_str());
            return false;
        }
        return true;
#else
        (void)clipSpecs;
        (void)outputPath;
        (void)outputWidth;
        (void)outputHeight;
        (void)outputFps;
        (void)onProgress;
        errorOut = "FFmpeg transcode not available";
        return false;
#endif
    }

    static bool transcodeTimelineClipsToMp4(
        const std::vector<std::string>& inputPaths,
        const std::string& outputPath,
        int outputWidth,
        int outputHeight,
        int outputFps,
        const std::function<bool(int)>& onProgress,
        std::string& errorOut) {
#if defined(VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE)
        struct OutputSize {
            int width;
            int height;
        };

        auto normalizeEven = [](int value) {
            value = std::max(value, 2);
            return (value % 2 == 0) ? value : (value - 1);
        };

        auto addSizeCandidate = [](std::vector<OutputSize>& candidates, int width, int height) {
            width = std::max(width, 2);
            height = std::max(height, 2);
            if (width % 2 != 0) --width;
            if (height % 2 != 0) --height;
            for (const auto& existing : candidates) {
                if (existing.width == width && existing.height == height) {
                    return;
                }
            }
            candidates.push_back({width, height});
        };

        int sourceWidth = 0;
        int sourceHeight = 0;
        if (!inputPaths.empty()) {
            AVFormatContext* probeFmt = nullptr;
            if (avformat_open_input(&probeFmt, inputPaths.front().c_str(), nullptr, nullptr) == 0 &&
                avformat_find_stream_info(probeFmt, nullptr) >= 0) {
                for (unsigned int i = 0; i < probeFmt->nb_streams; ++i) {
                    const AVCodecParameters* codecpar = probeFmt->streams[i]->codecpar;
                    if (codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
                        sourceWidth = codecpar->width;
                        sourceHeight = codecpar->height;
                        break;
                    }
                }
            }
            if (probeFmt) {
                avformat_close_input(&probeFmt);
            }
        }

        std::vector<OutputSize> sizeCandidates;
        const bool requestedPortrait = outputHeight > outputWidth;
        if (outputWidth > 1280 || outputHeight > 720) {
            addSizeCandidate(sizeCandidates, requestedPortrait ? 720 : 1280, requestedPortrait ? 1280 : 720);
            addSizeCandidate(sizeCandidates, requestedPortrait ? 480 : 854, requestedPortrait ? 854 : 480);
        }
        addSizeCandidate(sizeCandidates, outputWidth, outputHeight);
        if (sourceWidth > 0 && sourceHeight > 0) {
            addSizeCandidate(sizeCandidates, sourceWidth, sourceHeight);
            const bool portrait = sourceHeight > sourceWidth;
            addSizeCandidate(sizeCandidates, portrait ? 720 : 1280, portrait ? 1280 : 720);
            addSizeCandidate(sizeCandidates, portrait ? 480 : 854, portrait ? 854 : 480);
            addSizeCandidate(sizeCandidates, portrait ? 360 : 640, portrait ? 640 : 360);
        } else {
            addSizeCandidate(sizeCandidates, 1280, 720);
            addSizeCandidate(sizeCandidates, 854, 480);
            addSizeCandidate(sizeCandidates, 640, 360);
        }

        std::remove(outputPath.c_str());

        SimpleVideoEncoderContext encoder;
        std::string lastInitError;
        bool encoderReady = false;
        for (const auto& size : sizeCandidates) {
            LOGI("[Export] Trying transcode output size %dx%d", size.width, size.height);
            encoder = SimpleVideoEncoderContext{};
            if (initSimpleVideoEncoder(outputPath, size.width, size.height, outputFps, encoder, lastInitError)) {
                outputWidth = normalizeEven(size.width);
                outputHeight = normalizeEven(size.height);
                encoderReady = true;
                break;
            }
            LOGE("[Export] Encoder init failed for %dx%d: %s", size.width, size.height, lastInitError.c_str());
            std::remove(outputPath.c_str());
        }

        if (!encoderReady) {
            errorOut = lastInitError.empty() ? "Failed to initialize transcode encoder" : lastInitError;
            return false;
        }

        int64_t totalDurationUs = 0;
        for (const auto& inputPath : inputPaths) {
            AVFormatContext* fmt = nullptr;
            if (avformat_open_input(&fmt, inputPath.c_str(), nullptr, nullptr) == 0) {
                const int64_t clipDurationMs = lookupCachedClipDurationMs(inputPath);
                if (clipDurationMs > 0) {
                    totalDurationUs += clipDurationMs * 1000;
                } else if (avformat_find_stream_info(fmt, nullptr) >= 0 && fmt->duration > 0) {
                    totalDurationUs += fmt->duration;
                }
                avformat_close_input(&fmt);
            }
        }

        int64_t completedDurationUs = 0;
        int lastProgress = -1;
        for (const auto& inputPath : inputPaths) {
            AVFormatContext* inFmt = nullptr;
            AVCodecContext* decCtx = nullptr;
            AVFrame* decoded = nullptr;
            SwsContext* sws = nullptr;
            int videoStreamIndex = -1;
            int64_t clipBasePts = encoder.nextPts;
            const int64_t clipDurationUs = lookupCachedClipDurationMs(inputPath) * 1000;

            auto cleanup = [&]() {
                if (sws) sws_freeContext(sws);
                if (decoded) av_frame_free(&decoded);
                if (decCtx) avcodec_free_context(&decCtx);
                if (inFmt) avformat_close_input(&inFmt);
            };

            if (avformat_open_input(&inFmt, inputPath.c_str(), nullptr, nullptr) < 0 ||
                avformat_find_stream_info(inFmt, nullptr) < 0) {
                cleanup();
                errorOut = "Failed to open input for transcode";
                return false;
            }
            for (unsigned int i = 0; i < inFmt->nb_streams; ++i) {
                if (inFmt->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
                    videoStreamIndex = static_cast<int>(i);
                    break;
                }
            }
            if (videoStreamIndex < 0) {
                cleanup();
                errorOut = "No video stream found for transcode";
                return false;
            }

            AVStream* inStream = inFmt->streams[videoStreamIndex];
            const AVCodec* decoder = avcodec_find_decoder(inStream->codecpar->codec_id);
            if (!decoder) {
                cleanup();
                errorOut = "No decoder available for input clip";
                return false;
            }
            decCtx = avcodec_alloc_context3(decoder);
            decoded = av_frame_alloc();
            if (!decCtx || !decoded ||
                avcodec_parameters_to_context(decCtx, inStream->codecpar) < 0 ||
                avcodec_open2(decCtx, decoder, nullptr) < 0) {
                cleanup();
                errorOut = "Failed to initialize decoder";
                return false;
            }

            sws = sws_getContext(
                decCtx->width, decCtx->height, decCtx->pix_fmt,
                outputWidth, outputHeight, encoder.pixelFormat,
                SWS_BILINEAR, nullptr, nullptr, nullptr);
            if (!sws) {
                cleanup();
                errorOut = "Failed to initialize scaler";
                return false;
            }

            AVPacket packet;
            av_init_packet(&packet);
            while (av_read_frame(inFmt, &packet) >= 0) {
                if (packet.stream_index != videoStreamIndex) {
                    av_packet_unref(&packet);
                    continue;
                }
                if (avcodec_send_packet(decCtx, &packet) < 0) {
                    av_packet_unref(&packet);
                    cleanup();
                    errorOut = "Failed to send packet to decoder";
                    return false;
                }
                av_packet_unref(&packet);

                while (avcodec_receive_frame(decCtx, decoded) == 0) {
                    int64_t srcPts = decoded->best_effort_timestamp;
                    if (srcPts == AV_NOPTS_VALUE) srcPts = decoded->pts;
                    const int64_t ptsUs = srcPts != AV_NOPTS_VALUE
                        ? av_rescale_q(srcPts, inStream->time_base, AVRational{1, AV_TIME_BASE})
                        : 0;
                    if (clipDurationUs > 0 && ptsUs >= clipDurationUs) {
                        goto transcode_clip_done;
                    }

                    if (av_frame_make_writable(encoder.frame) < 0) {
                        cleanup();
                        errorOut = "Output frame not writable";
                        return false;
                    }
                    sws_scale(
                        sws,
                        decoded->data,
                        decoded->linesize,
                        0,
                        decoded->height,
                        encoder.frame->data,
                        encoder.frame->linesize);

                    int64_t relPts = srcPts != AV_NOPTS_VALUE
                        ? av_rescale_q(srcPts, inStream->time_base, AVRational{outputFps, 1})
                        : 0;
                    if (relPts < 0) relPts = 0;
                    encoder.frame->pts = std::max(encoder.nextPts, clipBasePts + relPts);
                    encoder.nextPts = encoder.frame->pts + 1;

                    if (!encodeVideoFrame(encoder, encoder.frame, errorOut)) {
                        cleanup();
                        return false;
                    }

                    if (totalDurationUs > 0 && srcPts != AV_NOPTS_VALUE) {
                        const int64_t clippedUs = clipDurationUs > 0 ? std::min(ptsUs, clipDurationUs) : ptsUs;
                        const int64_t progressUs = completedDurationUs + clippedUs;
                        int progress = static_cast<int>(std::clamp((progressUs * 100) / totalDurationUs, int64_t(0), int64_t(99)));
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            if (onProgress && !onProgress(progress)) {
                                cleanup();
                                errorOut = "Export cancelled";
                                std::remove(outputPath.c_str());
                                return false;
                            }
                        }
                    }
                }
            }

transcode_clip_done:
            if (clipDurationUs > 0) {
                completedDurationUs += clipDurationUs;
            } else if (inFmt->duration > 0) {
                completedDurationUs += inFmt->duration;
            }
            cleanup();
        }

        if (!finalizeSimpleVideoEncoder(encoder, errorOut)) {
            std::remove(outputPath.c_str());
            return false;
        }
        if (onProgress && !onProgress(100)) {
            errorOut = "Export cancelled";
            std::remove(outputPath.c_str());
            return false;
        }
        return true;
#else
        (void)inputPaths; (void)outputPath; (void)outputWidth; (void)outputHeight; (void)outputFps; (void)onProgress;
        errorOut = "FFmpeg transcode not available";
        return false;
#endif
    }

    static bool exportTimelineClipsToMp4(
        const std::vector<std::string>& inputPaths,
        const std::string& outputPath,
        const std::function<bool(int)>& onProgress,
        std::string& errorOut,
        int outputWidth = 1920,
        int outputHeight = 1080,
        int outputFps = 30,
        const std::vector<TimelineClipExportSpec>* clipSpecs = nullptr) {
#if defined(VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE)
        if (clipSpecs && !clipSpecs->empty()) {
            return transcodeTimelineSpecsToMp4(
                *clipSpecs,
                outputPath,
                outputWidth,
                outputHeight,
                outputFps,
                onProgress,
                errorOut);
        }

        if (inputPaths.empty()) {
            errorOut = "No input clips available";
            return false;
        }

        AVFormatContext* outFmt = nullptr;
        bool wroteHeader = false;
        int64_t totalDurationUs = 0;
        std::vector<int64_t> streamPtsOffsetUs;
        std::vector<int64_t> streamDtsOffsetUs;

        auto cleanup = [&]() {
            if (outFmt) {
                if (wroteHeader) {
                    av_write_trailer(outFmt);
                }
                if (!(outFmt->oformat->flags & AVFMT_NOFILE) && outFmt->pb) {
                    avio_closep(&outFmt->pb);
                }
                avformat_free_context(outFmt);
            }
        };

        AVFormatContext* firstFmt = nullptr;
        if (avformat_open_input(&firstFmt, inputPaths.front().c_str(), nullptr, nullptr) < 0) {
            errorOut = "Failed to open first input clip";
            cleanup();
            return false;
        }
        if (avformat_find_stream_info(firstFmt, nullptr) < 0) {
            errorOut = "Failed to read first input stream info";
            avformat_close_input(&firstFmt);
            cleanup();
            return false;
        }

        if (avformat_alloc_output_context2(&outFmt, nullptr, nullptr, outputPath.c_str()) < 0 || !outFmt) {
            errorOut = "Failed to allocate output container";
            avformat_close_input(&firstFmt);
            cleanup();
            return false;
        }

        for (unsigned int i = 0; i < firstFmt->nb_streams; ++i) {
            AVStream* inStream = firstFmt->streams[i];
            AVStream* outStream = avformat_new_stream(outFmt, nullptr);
            if (!outStream) {
                errorOut = "Failed to create output stream";
                avformat_close_input(&firstFmt);
                cleanup();
                return false;
            }
            if (avcodec_parameters_copy(outStream->codecpar, inStream->codecpar) < 0) {
                errorOut = "Failed to copy codec parameters";
                avformat_close_input(&firstFmt);
                cleanup();
                return false;
            }
            outStream->codecpar->codec_tag = 0;
            outStream->time_base = inStream->time_base;
        }
        streamPtsOffsetUs.assign(outFmt->nb_streams, 0);
        streamDtsOffsetUs.assign(outFmt->nb_streams, 0);

        if (!(outFmt->oformat->flags & AVFMT_NOFILE) &&
            avio_open(&outFmt->pb, outputPath.c_str(), AVIO_FLAG_WRITE) < 0) {
            errorOut = "Failed to open output file";
            avformat_close_input(&firstFmt);
            cleanup();
            return false;
        }
        if (avformat_write_header(outFmt, nullptr) < 0) {
            errorOut = "Failed to write output header";
            avformat_close_input(&firstFmt);
            cleanup();
            return false;
        }
        wroteHeader = true;

        for (const auto& inputPath : inputPaths) {
            AVFormatContext* durationFmt = nullptr;
            if (avformat_open_input(&durationFmt, inputPath.c_str(), nullptr, nullptr) == 0) {
                const int64_t clipDurationMs = lookupCachedClipDurationMs(inputPath);
                if (clipDurationMs > 0) {
                    totalDurationUs += clipDurationMs * 1000;
                } else if (avformat_find_stream_info(durationFmt, nullptr) >= 0 && durationFmt->duration > 0) {
                    totalDurationUs += durationFmt->duration;
                }
                avformat_close_input(&durationFmt);
            }
        }
        if (totalDurationUs <= 0 && firstFmt->duration > 0) {
            totalDurationUs = firstFmt->duration;
        }

        int lastProgress = -1;
        int64_t completedDurationUs = 0;
        for (size_t clipIndex = 0; clipIndex < inputPaths.size(); ++clipIndex) {
            AVFormatContext* inFmt = nullptr;
            const int64_t clipDurationUs = lookupCachedClipDurationMs(inputPaths[clipIndex]) * 1000;
            if (avformat_open_input(&inFmt, inputPaths[clipIndex].c_str(), nullptr, nullptr) < 0) {
                errorOut = "Failed to open input clip";
                cleanup();
                return false;
            }
            if (avformat_find_stream_info(inFmt, nullptr) < 0) {
                avformat_close_input(&inFmt);
                errorOut = "Failed to read input stream info";
                cleanup();
                return false;
            }
            if (inFmt->nb_streams != outFmt->nb_streams) {
                avformat_close_input(&inFmt);
                errorOut = "Clip stream count mismatch";
                cleanup();
                return false;
            }
            for (unsigned int i = 0; i < inFmt->nb_streams; ++i) {
                if (!codecParamsCompatible(inFmt->streams[i]->codecpar, outFmt->streams[i]->codecpar)) {
                    avformat_close_input(&inFmt);
                    avformat_close_input(&firstFmt);
                    cleanup();
                    errorOut = "Clip stream parameters mismatch";
                    return transcodeTimelineClipsToMp4(
                        inputPaths,
                        outputPath,
                        outputWidth,
                        outputHeight,
                        outputFps,
                        onProgress,
                        errorOut);
                }
            }

            AVPacket* packet = av_packet_alloc();
            while (av_read_frame(inFmt, packet) >= 0) {
                AVStream* inStream = inFmt->streams[packet->stream_index];
                AVStream* outStream = outFmt->streams[packet->stream_index];
                if (clipDurationUs > 0 && packet->pts != AV_NOPTS_VALUE) {
                    const int64_t packetPtsUs =
                        av_rescale_q(packet->pts, inStream->time_base, AVRational{1, AV_TIME_BASE});
                    if (packetPtsUs >= clipDurationUs) {
                        av_packet_unref(packet);
                        break;
                    }
                }
                const int64_t offsetPts = av_rescale_q(streamPtsOffsetUs[packet->stream_index], AVRational{1, AV_TIME_BASE}, outStream->time_base);
                const int64_t offsetDts = av_rescale_q(streamDtsOffsetUs[packet->stream_index], AVRational{1, AV_TIME_BASE}, outStream->time_base);

                if (packet->pts != AV_NOPTS_VALUE) {
                    packet->pts = av_rescale_q_rnd(packet->pts, inStream->time_base, outStream->time_base,
                                                  static_cast<AVRounding>(AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX)) + offsetPts;
                }
                if (packet->dts != AV_NOPTS_VALUE) {
                    packet->dts = av_rescale_q_rnd(packet->dts, inStream->time_base, outStream->time_base,
                                                  static_cast<AVRounding>(AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX)) + offsetDts;
                }
                packet->duration = av_rescale_q(packet->duration, inStream->time_base, outStream->time_base);
                packet->pos = -1;

                if (av_interleaved_write_frame(outFmt, packet) < 0) {
                    av_packet_unref(packet);
                    avformat_close_input(&inFmt);
                    errorOut = "Failed to write output packet";
                    cleanup();
                    return false;
                }

                if (totalDurationUs > 0 && packet->pts != AV_NOPTS_VALUE) {
                    const int64_t packetPtsUs =
                        av_rescale_q(packet->pts, outStream->time_base, AVRational{1, AV_TIME_BASE});
                    int progress = static_cast<int>(
                        std::clamp((packetPtsUs * 100) / totalDurationUs, int64_t(0), int64_t(99)));
                    if (progress != lastProgress) {
                        lastProgress = progress;
                        if (onProgress && !onProgress(progress)) {
                            av_packet_unref(packet);
                            avformat_close_input(&inFmt);
                            errorOut = "Export cancelled";
                            cleanup();
                            std::remove(outputPath.c_str());
                            return false;
                        }
                    }
                }

                av_packet_unref(packet);
            }
            av_packet_free(&packet);

            for (unsigned int i = 0; i < inFmt->nb_streams; ++i) {
                AVStream* inStream = inFmt->streams[i];
                if (clipDurationUs > 0) {
                    streamPtsOffsetUs[i] += clipDurationUs;
                    streamDtsOffsetUs[i] += clipDurationUs;
                } else if (inStream->duration > 0) {
                    const int64_t streamDurationUs =
                        av_rescale_q(inStream->duration, inStream->time_base, AVRational{1, AV_TIME_BASE});
                    streamPtsOffsetUs[i] += streamDurationUs;
                    streamDtsOffsetUs[i] += streamDurationUs;
                } else if (inFmt->duration > 0) {
                    streamPtsOffsetUs[i] += inFmt->duration;
                    streamDtsOffsetUs[i] += inFmt->duration;
                }
            }
            if (clipDurationUs > 0) {
                completedDurationUs += clipDurationUs;
            } else if (inFmt->duration > 0) {
                completedDurationUs += inFmt->duration;
            }
            avformat_close_input(&inFmt);
            if (totalDurationUs > 0) {
                int progress = static_cast<int>(std::clamp((completedDurationUs * 100) / totalDurationUs, int64_t(0), int64_t(99)));
                if (progress != lastProgress) {
                    lastProgress = progress;
                    if (onProgress && !onProgress(progress)) {
                        errorOut = "Export cancelled";
                        cleanup();
                        std::remove(outputPath.c_str());
                        return false;
                    }
                }
            }
        }

        avformat_close_input(&firstFmt);
        if (onProgress && !onProgress(100)) {
            errorOut = "Export cancelled";
            cleanup();
            std::remove(outputPath.c_str());
            return false;
        }

        cleanup();
        return true;
#else
        (void)inputPaths;
        (void)outputPath;
        (void)onProgress;
        (void)clipSpecs;
        errorOut = "FFmpeg export not available in this build";
        return false;
#endif
    }

    // Transition storage
    std::map<int64_t, Transition> g_transitions;
    int64_t g_nextTransitionId = 1;

    // Simple GL program for colored quad placeholder (used until glyph textures are provided)
    GLuint g_overlayProgram = 0;
    GLint g_overlayPosLoc = -1;
    GLint g_overlayColorLoc = -1;

    struct DirtyRegionPx {
        int x = 0;
        int y = 0;      // top-left origin
        int width = 0;
        int height = 0;

        bool isValid() const {
            return width > 0 && height > 0;
        }
    };

    static DirtyRegionPx clampDirtyRegion(DirtyRegionPx region) {
        if (g_surfaceWidth <= 0 || g_surfaceHeight <= 0 || !region.isValid()) {
            return DirtyRegionPx{};
        }
        region.x = std::max(0, std::min(region.x, g_surfaceWidth - 1));
        region.y = std::max(0, std::min(region.y, g_surfaceHeight - 1));
        region.width = std::max(1, std::min(region.width, g_surfaceWidth - region.x));
        region.height = std::max(1, std::min(region.height, g_surfaceHeight - region.y));
        return region;
    }

    static DirtyRegionPx unionDirtyRegion(const DirtyRegionPx& a, const DirtyRegionPx& b) {
        if (!a.isValid()) return b;
        if (!b.isValid()) return a;
        DirtyRegionPx out;
        const int left = std::min(a.x, b.x);
        const int top = std::min(a.y, b.y);
        const int right = std::max(a.x + a.width, b.x + b.width);
        const int bottom = std::max(a.y + a.height, b.y + b.height);
        out.x = left;
        out.y = top;
        out.width = std::max(1, right - left);
        out.height = std::max(1, bottom - top);
        return clampDirtyRegion(out);
    }

    static DirtyRegionPx textOverlayBoundsPx(const TextOverlay& t) {
        if (g_surfaceWidth <= 0 || g_surfaceHeight <= 0) {
            return DirtyRegionPx{};
        }

        auto toClip = [](float nx)->float { return nx * 2.0f - 1.0f; };
        const float drawX = t.x;
        const float drawY = t.y;
        const float drawScale = t.scale;

        float sx;
        float sy;
        if (t.hasTexture && t.texWidth > 0 && t.texHeight > 0) {
            const float aspectRatio = static_cast<float>(t.texWidth) / static_cast<float>(t.texHeight);
            const float baseHeight = 0.1f;
            sx = (baseHeight * aspectRatio) * drawScale *
                (g_surfaceHeight > 0 ? static_cast<float>(g_surfaceHeight) / static_cast<float>(g_surfaceWidth) : 1.0f);
            sy = baseHeight * drawScale;
        } else {
            sx = drawScale * 0.5f;
            sy = drawScale * 0.25f;
        }

        const float centerClipX = toClip(drawX);
        const float centerClipY = toClip(1.0f - drawY);
        const float centerPxX = (centerClipX * 0.5f + 0.5f) * static_cast<float>(g_surfaceWidth);
        const float centerPxY = (1.0f - (centerClipY * 0.5f + 0.5f)) * static_cast<float>(g_surfaceHeight);

        const float rawWidthPx = std::abs(sx) * static_cast<float>(g_surfaceWidth);
        const float rawHeightPx = std::abs(sy) * static_cast<float>(g_surfaceHeight);
        const float angleRad = t.rotation * 0.01745329251994329577f;
        const float cosA = std::abs(std::cos(angleRad));
        const float sinA = std::abs(std::sin(angleRad));
        const float bboxWidthPx = rawWidthPx * cosA + rawHeightPx * sinA;
        const float bboxHeightPx = rawWidthPx * sinA + rawHeightPx * cosA;
        const float paddingPx = 8.0f;

        DirtyRegionPx out;
        out.x = static_cast<int>(std::floor(centerPxX - (bboxWidthPx * 0.5f) - paddingPx));
        out.y = static_cast<int>(std::floor(centerPxY - (bboxHeightPx * 0.5f) - paddingPx));
        out.width = static_cast<int>(std::ceil(bboxWidthPx + (paddingPx * 2.0f)));
        out.height = static_cast<int>(std::ceil(bboxHeightPx + (paddingPx * 2.0f)));
        return clampDirtyRegion(out);
    }

    static void destroyEglImageCompat(EGLDisplay display, EGLImageKHR image) {
        if (display == EGL_NO_DISPLAY || image == EGL_NO_IMAGE_KHR) {
            return;
        }
        auto destroyImageFn = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
            eglGetProcAddress("eglDestroyImageKHR"));
        if (destroyImageFn) {
            destroyImageFn(display, image);
        }
    }

    static void releaseSharedImageForOverlay(int64_t overlayId) {
        auto sharedIt = g_overlaySharedImages.find(overlayId);
        if (sharedIt == g_overlaySharedImages.end()) {
            return;
        }
        if (sharedIt->second.image != EGL_NO_IMAGE_KHR &&
            g_eglDisplay != EGL_NO_DISPLAY) {
            destroyEglImageCompat(g_eglDisplay, sharedIt->second.image);
        }
        if (sharedIt->second.buffer) {
            if (ensureAhbApiLoaded() && g_ahbApi.release) {
                g_ahbApi.release(sharedIt->second.buffer);
            } else {
                LOGW("[Text] releaseSharedImageForOverlay: release symbol unavailable");
            }
            sharedIt->second.buffer = nullptr;
        }
        g_overlaySharedImages.erase(sharedIt);
    }

    static void releaseOverlayTextureResources(int64_t overlayId, TextOverlay& overlay) {
        if (overlay.hasTexture && overlay.texture != 0) {
            glDeleteTextures(1, reinterpret_cast<GLuint*>(&overlay.texture));
        }
        overlay.texture = 0;
        overlay.hasTexture = false;
        overlay.texWidth = 0;
        overlay.texHeight = 0;
        releaseSharedImageForOverlay(overlayId);
    }

    static GLuint compileShader(GLenum type, const char* src) {
        GLuint s = glCreateShader(type);
        glShaderSource(s, 1, &src, nullptr);
        glCompileShader(s);
        GLint ok = 0;
        glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
        if (!ok) {
            char buf[512];
            glGetShaderInfoLog(s, sizeof(buf), nullptr, buf);
            LOGE("Shader compile failed: %s", buf);
            glDeleteShader(s);
            return 0;
        }
        return s;
    }

    static bool initTextOverlayGL() {
        if (g_overlayProgram) return true;

        const char* vs =
            "#version 300 es\n"
            "layout(location = 0) in vec2 aPos;\n"
            "layout(location = 1) in vec2 aUV;\n"
            "out vec2 vUV;\n"
            "uniform vec2 uScale;\n"
            "uniform vec2 uTranslate;\n"
            "uniform float uAngle;\n"
            "void main() {\n"
            "  float rad = radians(uAngle);\n"
            "  mat2 rot = mat2(cos(rad), -sin(rad), sin(rad), cos(rad));\n"
            "  vec2 p = (rot * (aPos * uScale)) + uTranslate;\n"
            "  vUV = aUV;\n"
            "  gl_Position = vec4(p, 0.0, 1.0);\n"
            "}\n";

        const char* fs =
            "#version 300 es\n"
            "precision mediump float;\n"
            "in vec2 vUV;\n"
            "uniform vec4 uColor;\n"
            "uniform float uTextOpacity;\n"
            "uniform sampler2D uTexture;\n"
            "uniform int uUseTexture;\n"
            "out vec4 outColor;\n"
            "void main() {\n"
            "  if (uUseTexture == 1) {\n"
            "    vec4 s = texture(uTexture, vUV);\n"
            "    outColor = s * uColor;\n"
            "    outColor.a *= uTextOpacity;\n"
            "  } else {\n"
            "    outColor = uColor;\n"
            "    outColor.a *= uTextOpacity;\n"
            "  }\n"
            "}\n";

        GLuint vsId = compileShader(GL_VERTEX_SHADER, vs);
        GLuint fsId = compileShader(GL_FRAGMENT_SHADER, fs);
        if (!vsId || !fsId) return false;

        g_overlayProgram = glCreateProgram();
        glAttachShader(g_overlayProgram, vsId);
        glAttachShader(g_overlayProgram, fsId);
        glBindAttribLocation(g_overlayProgram, 0, "aPos");
        glBindAttribLocation(g_overlayProgram, 1, "aUV");
        glLinkProgram(g_overlayProgram);

        GLint linked = 0;
        glGetProgramiv(g_overlayProgram, GL_LINK_STATUS, &linked);
        if (!linked) {
            char buf[512];
            glGetProgramInfoLog(g_overlayProgram, sizeof(buf), nullptr, buf);
            LOGE("Program link failed: %s", buf);
            glDeleteProgram(g_overlayProgram);
            g_overlayProgram = 0;
            return false;
        }

        glDeleteShader(vsId);
        glDeleteShader(fsId);

        // Query uniform locations
        g_overlayPosLoc = 0; // aPos bound to location 0
        // Additional uniform locations will be queried during draw
        return true;
    }

    static void cleanupTextOverlayGL() {
        if (g_overlayProgram) {
            glDeleteProgram(g_overlayProgram);
            g_overlayProgram = 0;
        }
    }

    // Render text overlays as textured quads when bitmap present, otherwise colored quad.
    static void renderTextOverlays(long long timelineMs, const DirtyRegionPx* dirtyRegion = nullptr) {
        if (!g_overlayProgram) return;

        auto toClip = [](float nx)->float { return nx * 2.0f - 1.0f; };

        // Interleaved vertex data: pos(x,y), uv(u,v) for a unit quad centered at origin
        const float quadVerts[] = {
            -0.5f, -0.5f, 0.0f, 1.0f,
             0.5f, -0.5f, 1.0f, 1.0f,
            -0.5f,  0.5f, 0.0f, 0.0f,
             0.5f,  0.5f, 1.0f, 0.0f
        };

        const bool useDirtyScissor = dirtyRegion && dirtyRegion->isValid() && g_surfaceWidth > 0 && g_surfaceHeight > 0;
        if (useDirtyScissor) {
            const DirtyRegionPx safeDirty = clampDirtyRegion(*dirtyRegion);
            if (safeDirty.isValid()) {
                const int scissorY = std::max(0, g_surfaceHeight - (safeDirty.y + safeDirty.height));
                glEnable(GL_SCISSOR_TEST);
                glScissor(safeDirty.x, scissorY, safeDirty.width, safeDirty.height);
            }
        }

        glUseProgram(g_overlayProgram);

        // Enable attrib arrays 0 = aPos, 1 = aUV
        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), quadVerts);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), quadVerts + 2);

        // Ensure overlay order is sorted if needed (sort by zOrder ascending)
        if (g_orderDirty) {
            std::stable_sort(g_textOverlayOrder.begin(), g_textOverlayOrder.end(), [](int64_t a, int64_t b){
                const auto &ma = g_textOverlays.find(a);
                const auto &mb = g_textOverlays.find(b);
                int za = 0;
                int zb = 0;
                if (ma != g_textOverlays.end()) za = ma->second.zOrder;
                if (mb != g_textOverlays.end()) zb = mb->second.zOrder;
                if (za == zb) return a < b; // deterministic tie-breaker by id
                return za < zb;
            });
            g_orderDirty = false;
        }

        for (int64_t id : g_textOverlayOrder) {
            auto it = g_textOverlays.find(id);
            if (it == g_textOverlays.end()) continue;
            TextOverlay& t = it->second;
            if (!t.enabled) continue;
            if (t.endTime >= 0 && timelineMs < t.startTime) continue;
            if (t.endTime >= 0 && timelineMs > t.endTime) continue;

            // Interpolate transform from keyframes if present.
            float drawX = t.x;
            float drawY = t.y;
            float drawScale = t.scale;
            float drawOpacity = t.opacity;

            if (!t.keyframes.empty()) {
                // Keyframes are expected to be sorted by timeMs.
                auto &kfs = t.keyframes;
                if (kfs.size() == 1) {
                    drawX = kfs[0].posX;
                    drawY = kfs[0].posY;
                    drawScale = kfs[0].scale;
                    drawOpacity = kfs[0].opacity;
                } else {
                    // Find surrounding keyframes
                    if (timelineMs <= kfs.front().timeMs) {
                        drawX = kfs.front().posX;
                        drawY = kfs.front().posY;
                        drawScale = kfs.front().scale;
                        drawOpacity = kfs.front().opacity;
                    } else if (timelineMs >= kfs.back().timeMs) {
                        drawX = kfs.back().posX;
                        drawY = kfs.back().posY;
                        drawScale = kfs.back().scale;
                        drawOpacity = kfs.back().opacity;
                    } else {
                        // find index i where kfs[i].time <= timelineMs < kfs[i+1].time
                        size_t i = 0;
                        while (i + 1 < kfs.size() && kfs[i+1].timeMs <= timelineMs) ++i;
                        const auto &kf0 = kfs[i];
                        const auto &kf1 = kfs[i+1];
                        float dt = float(kf1.timeMs - kf0.timeMs);
                        float tnorm = 0.0f;
                        if (dt > 0.0f) tnorm = float(timelineMs - kf0.timeMs) / dt;
                        if (tnorm < 0.0f) tnorm = 0.0f;
                        if (tnorm > 1.0f) tnorm = 1.0f;
                        auto lerpf = [](float a, float b, float t)->float { return a + (b - a) * t; };
                        drawX = lerpf(kf0.posX, kf1.posX, tnorm);
                        drawY = lerpf(kf0.posY, kf1.posY, tnorm);
                        drawScale = lerpf(kf0.scale, kf1.scale, tnorm);
                        drawOpacity = lerpf(kf0.opacity, kf1.opacity, tnorm);
                    }
                }
            }

            float cx = toClip(drawX);
            float cy = toClip(1.0f - drawY);

            // If we have a texture, scale it relative to its aspect
            float sx, sy;
            if (t.hasTexture && t.texWidth > 0 && t.texHeight > 0) {
                // Calculate aspect ratio
                float aspectRatio = (float)t.texWidth / (float)t.texHeight;
                // Base size for the quad (e.g., 0.1 normalized screen height)
                // This `0.1f` is a heuristic and might need to be adjusted for desired text size
                float baseHeight = 0.1f;
                sx = (baseHeight * aspectRatio) * drawScale * (g_surfaceHeight > 0 ? (float)g_surfaceHeight / (float)g_surfaceWidth : 1.0f);
                sy = baseHeight * drawScale;
            } else {
                // Original heuristic for colored quads (if no texture)
                sx = drawScale * 0.5f; // user-tweakable heuristic
                sy = drawScale * 0.25f;
            }

            // Extract color and alpha
            float a = ((t.color >> 24) & 0xFF) / 255.0f;
            float r = ((t.color >> 16) & 0xFF) / 255.0f;
            float g = ((t.color >> 8) & 0xFF) / 255.0f;
            float b = ((t.color >> 0) & 0xFF) / 255.0f;

            GLint uScale = glGetUniformLocation(g_overlayProgram, "uScale");
            GLint uTranslate = glGetUniformLocation(g_overlayProgram, "uTranslate");
            GLint uAngle = glGetUniformLocation(g_overlayProgram, "uAngle");
            GLint uColor = glGetUniformLocation(g_overlayProgram, "uColor");
            GLint uTextOpacity = glGetUniformLocation(g_overlayProgram, "uTextOpacity");
            GLint uUseTexture = glGetUniformLocation(g_overlayProgram, "uUseTexture");
            GLint uTexture = glGetUniformLocation(g_overlayProgram, "uTexture");

            glUniform2f(uScale, sx, sy);
            glUniform2f(uTranslate, cx, cy);
            glUniform1f(uAngle, t.rotation);
            glUniform4f(uColor, r, g, b, a);
            // Compute effective opacity: if keyframes present use drawOpacity; otherwise fall back to fade logic
            float effOpacity = drawOpacity;
            if (t.keyframes.empty()) {
                // Fade in/out behavior applies only when no keyframes drive opacity
                if (t.fadeInMs > 0 && timelineMs < (t.startTime + t.fadeInMs)) {
                    float dt = float(timelineMs - t.startTime);
                    float progress = dt / float(t.fadeInMs);
                    if (progress < 0.0f) progress = 0.0f;
                    if (progress > 1.0f) progress = 1.0f;
                    effOpacity = t.opacity * progress;
                }
                if (t.endTime >= 0 && t.fadeOutMs > 0 && timelineMs > (t.endTime - t.fadeOutMs)) {
                    float dt = float(t.endTime - timelineMs);
                    float progress = dt / float(t.fadeOutMs);
                    if (progress < 0.0f) progress = 0.0f;
                    if (progress > 1.0f) progress = 1.0f;
                    effOpacity = t.opacity * progress;
                }
            }

            glUniform1f(uTextOpacity, effOpacity);

            if (t.hasTexture && t.texture != 0) {
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, t.texture);
                glUniform1i(uUseTexture, 1);
                glUniform1i(uTexture, 0);
            } else {
                glUniform1i(uUseTexture, 0);
            }

            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

            if (t.hasTexture) {
                glBindTexture(GL_TEXTURE_2D, 0);
            }
        }

        glDisableVertexAttribArray(1);
        glDisableVertexAttribArray(0);
        glUseProgram(0);
        if (useDirtyScissor) {
            glDisable(GL_SCISSOR_TEST);
        }
    }

    static bool hasActiveTextOverlayAtTimeLocked(long long timelineMs) {
        for (const auto& [_, overlay] : g_textOverlays) {
            if (!overlay.enabled) continue;
            if (timelineMs < overlay.startTime) continue;
            if (overlay.endTime >= 0 && timelineMs > overlay.endTime) continue;
            return true;
        }
        return false;
    }

    static bool shouldRenderTextOverlaysInPreviewLocked() {
        // Live preview already shows text/sticker overlays via Android overlay views.
        // Re-compositing the same text through a second EGL owner on the preview
        // ANativeWindow causes repeated EGL_BAD_ALLOC conflicts and stutter.
        return false;
    }

    static bool makeOuterEglCurrentLocked(const char* logPrefix) {
        if (g_eglDisplay == EGL_NO_DISPLAY ||
            g_eglContext == EGL_NO_CONTEXT ||
            g_eglSurface == EGL_NO_SURFACE) {
            if (!g_nativeWindow || !initializeEGL()) {
                LOGW("%s outer EGL unavailable", logPrefix);
                return false;
            }
        }
        if (eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext)) {
            return true;
        }
        LOGW("%s eglMakeCurrent failed (0x%x) — reinitializing EGL", logPrefix, eglGetError());
        releaseOuterEglForPreviewAttachLocked();
        if (!g_nativeWindow || !initializeEGL()) {
            LOGE("%s EGL reinit failed", logPrefix);
            return false;
        }
        if (!eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext)) {
            LOGE("%s eglMakeCurrent failed after reinit", logPrefix);
            return false;
        }
        return true;
    }

    static void renderOverlayEditFrame(const DirtyRegionPx* dirtyRegion = nullptr) {
        if (!g_preview) {
            return;
        }

        const long long currentTime = g_currentTimeMs.load(std::memory_order_acquire);
        bool baseRendered = false;
        if (dirtyRegion && dirtyRegion->isValid()) {
            const DirtyRegionPx safe = clampDirtyRegion(*dirtyRegion);
            if (safe.isValid()) {
                baseRendered = g_preview->redrawCachedFrameRegion(
                    safe.x,
                    safe.y,
                    safe.width,
                    safe.height);
            }
        } else {
            baseRendered = g_preview->redrawCachedFrame();
        }
        if (!baseRendered) {
            g_preview->scrubToTimelineTime(currentTime);
        }

        if (!shouldRenderTextOverlaysInPreviewLocked()) {
            return;
        }
        if (!hasActiveTextOverlayAtTimeLocked(currentTime)) {
            return;
        }
        if (!makeOuterEglCurrentLocked("[OverlayEdit]")) {
            return;
        }
        renderTextOverlays(currentTime, dirtyRegion);
        if (!eglSwapBuffers(g_eglDisplay, g_eglSurface)) {
            LOGW("eglSwapBuffers failed in renderOverlayEditFrame: 0x%x", eglGetError());
        }
    }

    // Rendering thread function
    void renderThreadProc() {
        configureRenderThreadPriority();
        LOGI("Render thread started");

        while (!g_shouldExit.load(std::memory_order_acquire)) {
            int64_t sleepMs = 16;
            const bool isRenderingActive = g_isRenderingActive.load(std::memory_order_acquire);

            if (!isRenderingActive) {
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                continue;
            }

            {
                std::lock_guard<std::mutex> lock(g_mutex);

                if (g_preview) {
                    // Handle pending scrub request (posted from main thread)
                    const long long pendingScrub = g_pendingScrubMs.exchange(-1, std::memory_order_acq_rel);
                    if (pendingScrub >= 0) {
                        const int64_t scrubTargetMs = static_cast<int64_t>(pendingScrub);
                        const bool restarted = g_preview->playFrom(scrubTargetMs);
                        const int64_t scrubTime = g_preview->getPlaybackTimelineTimeMs();
                        g_currentTimeMs.store(scrubTime, std::memory_order_release);
                        if (restarted &&
                            shouldRenderTextOverlaysInPreviewLocked() &&
                            hasActiveTextOverlayAtTimeLocked(scrubTime) &&
                            makeOuterEglCurrentLocked("[Preview]")) {
                            renderTextOverlays(scrubTime);
                            if (!eglSwapBuffers(g_eglDisplay, g_eglSurface)) {
                                LOGE("[Preview] eglSwapBuffers failed after scrub: 0x%x", eglGetError());
                            }
                        }
                        if (!restarted) {
                            g_isRenderingActive.store(false, std::memory_order_release);
                            g_preview->stop();
                            LOGE("[Preview] playback scrub failed at %lld ms: %s",
                                 static_cast<long long>(scrubTargetMs),
                                 g_preview->getLastError());
                        }
                        sleepMs = std::max<int64_t>(4, g_preview->preferredRenderSleepMs());
                        continue;
                    }

                    const bool rendered = g_preview->renderFrame();
                    const int64_t currentTimeMs = g_preview->getPlaybackTimelineTimeMs();
                    g_currentTimeMs.store(currentTimeMs, std::memory_order_release);

                    if (rendered) {
                        if (shouldRenderTextOverlaysInPreviewLocked() &&
                            hasActiveTextOverlayAtTimeLocked(currentTimeMs) &&
                            makeOuterEglCurrentLocked("[Preview]")) {
                            renderTextOverlays(currentTimeMs);
                            if (!eglSwapBuffers(g_eglDisplay, g_eglSurface)) {
                                LOGE("[Preview] eglSwapBuffers failed: 0x%x", eglGetError());
                            }
                        }
                    } else {
                        g_isRenderingActive.store(false, std::memory_order_release);
                        g_preview->stop();
                        const char* lastError = g_preview->getLastError();
                        if (lastError && lastError[0] != '\0') {
                            LOGE("[Preview] playback render failed at %lld ms: %s",
                                 static_cast<long long>(currentTimeMs),
                                 lastError);
                        } else {
                            LOGI("[Preview] playback reached end at %lld ms",
                                 static_cast<long long>(currentTimeMs));
                        }
                    }
                    
                    // Drive playback loop with engine-provided pacing instead of a fixed 60fps poll.
                    const int64_t preferredSleep = g_preview->preferredRenderSleepMs();
                    sleepMs = std::max<int64_t>(4, preferredSleep);
                } else {
                    sleepMs = 8;
                }
            }

            std::this_thread::sleep_for(std::chrono::milliseconds(std::max<int64_t>(1, sleepMs)));
        }

        LOGI("Render thread exiting");
    }

// JNI: Add a transition and return its id
extern "C" JNIEXPORT jlong JNICALL
Java_com_video_engine_VideoPreviewView_nativeAddTransition(
    JNIEnv* env, jobject thiz,
    jint outgoingClipId,
    jint incomingClipId,
    jint typeId,
    jint durationMs,
    jlong startTimeMs) {

    std::lock_guard<std::mutex> lock(g_mutex);
    Transition t;
    t.id = g_nextTransitionId++;
    t.outgoingClipId = outgoingClipId;
    t.incomingClipId = incomingClipId;
    t.typeId = typeId;
    t.durationMs = durationMs;
    t.startTimeMs = startTimeMs;
    t.isEnabled = true;

    g_transitions[t.id] = t;
    LOGI("[TRANSITION] add id=%lld type=%d duration=%dms between %d -> %d", (long long)t.id, t.typeId, t.durationMs, t.outgoingClipId, t.incomingClipId);
    return static_cast<jlong>(t.id);
}

// JNI: Update an existing transition
extern "C" JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeUpdateTransition(
    JNIEnv* env, jobject thiz,
    jlong transitionId,
    jint typeId,
    jint durationMs) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_transitions.find(transitionId);
    if (it == g_transitions.end()) {
        LOGW("[TRANSITION] update missing id=%lld", (long long)transitionId);
        return;
    }
    it->second.typeId = typeId;
    it->second.durationMs = durationMs;
    LOGI("[TRANSITION] update id=%lld type=%d duration=%dms", (long long)transitionId, typeId, durationMs);
}

// JNI: Remove a transition
extern "C" JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeRemoveTransition(
    JNIEnv* env, jobject thiz,
    jlong transitionId) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_transitions.find(transitionId);
    if (it != g_transitions.end()) {
        LOGI("[TRANSITION] remove id=%lld", (long long)transitionId);
        g_transitions.erase(it);
    } else {
        LOGW("[TRANSITION] remove missing id=%lld", (long long)transitionId);
    }
}


/**
 * Initialize EGL context for rendering.
 * Assumes g_nativeWindow is valid. Safe null checks prevent crashes.
 * @return true on success
 */
static bool initializeEGL() {
    CHECK_NATIVE_WINDOW();  // Crash if null

    if (g_eglDisplay != EGL_NO_DISPLAY) {
        LOGD("[EGL] Already initialized");
        return true;
    }

    // Get display
    g_eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_eglDisplay == EGL_NO_DISPLAY) {
        LOGE("[EGL] eglGetDisplay failed");
        return false;
    }

    // Initialize
    EGLint major, minor;
    if (!eglInitialize(g_eglDisplay, &major, &minor)) {
        LOGE("[EGL] eglInitialize failed: 0x%x", eglGetError());
        g_eglDisplay = EGL_NO_DISPLAY;
        return false;
    }

    LOGI("[EGL] initialized: %d.%d", major, minor);

    // Choose config
    const EGLint configAttribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_NONE
    };

    EGLConfig config;
    EGLint numConfigs;
    if (!eglChooseConfig(g_eglDisplay, configAttribs, &config, 1, &numConfigs) || 
        numConfigs == 0) {
        LOGE("eglChooseConfig failed");
        eglTerminate(g_eglDisplay);
        g_eglDisplay = EGL_NO_DISPLAY;
        return false;
    }

    // Create context
    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    g_eglContext = eglCreateContext(g_eglDisplay, config, EGL_NO_CONTEXT, contextAttribs);
    if (g_eglContext == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed");
        eglTerminate(g_eglDisplay);
        g_eglDisplay = EGL_NO_DISPLAY;
        return false;
    }

    // Create surface
    g_eglSurface = eglCreateWindowSurface(g_eglDisplay, config, g_nativeWindow, nullptr);
    if (g_eglSurface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
        eglDestroyContext(g_eglDisplay, g_eglContext);
        eglTerminate(g_eglDisplay);
        g_eglDisplay = EGL_NO_DISPLAY;
        g_eglContext = EGL_NO_CONTEXT;
        return false;
    }

    // Make current
    if (!eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext)) {
        LOGE("eglMakeCurrent failed");
        eglDestroySurface(g_eglDisplay, g_eglSurface);
        eglDestroyContext(g_eglDisplay, g_eglContext);
        eglTerminate(g_eglDisplay);
        g_eglDisplay = EGL_NO_DISPLAY;
        g_eglContext = EGL_NO_CONTEXT;
        g_eglSurface = EGL_NO_SURFACE;
        return false;
    }

    // Configure OpenGL state
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glDisable(GL_DEPTH_TEST);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glViewport(0, 0, g_surfaceWidth, g_surfaceHeight);

    // Initialize overlay GL program
    initTextOverlayGL();

    LOGI("[AndroidPreview] EGL context created and made current");
    return true;
}

/**
 * Terminate EGL context.
 * Safe cleanup: always check before destroying to avoid double-free crashes
 */
void terminateEGL() {
    LOGI("[EGL] Terminating");
    
    if (g_eglDisplay == EGL_NO_DISPLAY) {
        LOGD("[EGL] Already terminated");
        return;
    }

    eglMakeCurrent(g_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    if (g_eglSurface != EGL_NO_SURFACE) {
        eglDestroySurface(g_eglDisplay, g_eglSurface);
        g_eglSurface = EGL_NO_SURFACE;
        LOGD("[EGL] Surface destroyed");
    }

    if (g_eglContext != EGL_NO_CONTEXT) {
        eglDestroyContext(g_eglDisplay, g_eglContext);
        g_eglContext = EGL_NO_CONTEXT;
        LOGD("[EGL] Context destroyed");
    }

    if (g_eglDisplay != EGL_NO_DISPLAY) {
        eglTerminate(g_eglDisplay);
        g_eglDisplay = EGL_NO_DISPLAY;
        LOGD("[EGL] Display terminated");
    }
    
    cleanupTextOverlayGL();
    LOGI("[EGL] Cleanup complete");
    LOGI("[AndroidPreview] EGL released");
}

bool releaseOuterEglForPreviewAttachLocked() {
    // g_mutex is owned by caller (command path / JNI path).
    g_isRenderingActive.store(false, std::memory_order_release);
    if (g_eglDisplay == EGL_NO_DISPLAY &&
        g_eglContext == EGL_NO_CONTEXT &&
        g_eglSurface == EGL_NO_SURFACE) {
        return true;
    }
    LOGI("[Timeline] Releasing outer EGL so PreviewController can own the surface");
    terminateEGL();
    return true;
}

extern "C" {

/**
 * JNI: Called when SurfaceView surface is created.
 * Creates EGL context and initializes PreviewController.
 * 
 * @param env JNI environment
 * @param thiz Java object reference
 * @param surface Android Surface object
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeInitPreview(
    JNIEnv* env, jobject thiz, jobject surface) {

    LOGI("[AndroidPreview] Surface created");

    // Get new native window first
    ANativeWindow* newWindow = ANativeWindow_fromSurface(env, surface);
    if (!newWindow) {
        LOGE("[AndroidPreview] ANativeWindow_fromSurface failed");
        return;
    }

    // If same window and renderer already initialized — just resume, don't reinit
    {
        std::lock_guard<std::mutex> lk(g_mutex);
        if (newWindow == g_nativeWindow && g_preview &&
            g_preview->isRendererInitialized()) {
            ANativeWindow_release(newWindow);
            LOGI("[AndroidPreview] Same window, renderer OK — skipping reinit");
            if (!g_renderThread.joinable()) {
                g_shouldExit.store(false, std::memory_order_release);
                g_renderThread = std::thread(renderThreadProc);
                LOGI("[AndroidPreview] Render thread restarted");
            }
            return;
        }
    }
    ANativeWindow_release(newWindow); // will re-acquire below

    // Stop render thread BEFORE acquiring g_mutex to avoid deadlock
    g_shouldExit.store(true, std::memory_order_release);
    if (g_renderThread.joinable()) {
        g_renderThread.join();
    }
    g_shouldExit.store(false, std::memory_order_release);

    std::lock_guard<std::mutex> lock(g_mutex);

    // Drop stale window ref before grabbing the latest Surface window.
    if (g_nativeWindow) {
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
    }

    // Get native window from Surface
    g_nativeWindow = ANativeWindow_fromSurface(env, surface);
    if (!g_nativeWindow) {
        LOGE("[AndroidPreview] ANativeWindow_fromSurface failed");
        return;
    }

    // Ensure any stale outer EGL is fully terminated before PreviewController
    // tries to create its own EGL surface on the same ANativeWindow.
    if (g_eglDisplay != EGL_NO_DISPLAY) {
        terminateEGL();
    }
    bool outerEglReady = false;

    // Reuse the preview controller across transient surface recreation so the
    // timeline survives file picker/background round-trips.
    if (!g_preview) {
        try {
            g_preview = std::make_unique<VideoEngine::PreviewController>();
        } catch (const std::exception& e) {
            LOGE("[AndroidPreview] Exception creating PreviewController: %s", e.what());
            if (outerEglReady) {
                terminateEGL();
            }
            if (g_nativeWindow) {
                ANativeWindow_release(g_nativeWindow);
                g_nativeWindow = nullptr;
            }
            return;
        }
        // Limit decode resolution to surface size immediately
        if (g_surfaceWidth > 0 && g_surfaceHeight > 0) {
            g_preview->setGhostPreviewEnabled(true);
            g_preview->setGhostPreviewLongEdgePx(std::max(g_surfaceWidth, g_surfaceHeight));
        }
        LOGI("[AndroidPreview] Created new PreviewController");
    } else {
        LOGI("[AndroidPreview] Reusing existing PreviewController");
        // Detach old surface — render thread already stopped above, so EGL is free
        g_preview->detachSurface();
        // Wait for Android EGL driver to fully release the window surface
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }

    // Initialize GL resources on preview controller by attaching surface.
    bool attached = g_preview->attachSurface(g_nativeWindow);
    if (!attached) {
        const char* attachError = g_preview->getLastError();
        LOGE("[AndroidPreview] Surface attach failed: %s", attachError);
        // Retry once
        g_preview->detachSurface();
        attached = g_preview->attachSurface(g_nativeWindow);
        if (!attached) {
            LOGE("[AndroidPreview] Surface attach retry failed: %s", g_preview->getLastError());
        } else {
            LOGI("[AndroidPreview] Surface attach recovered on retry");
        }
    }

    // Start render thread
    g_renderThread = std::thread(renderThreadProc);

    LOGI("[AndroidPreview] PreviewController initialized");
}

/**
 * JNI: Called when SurfaceView surface size changes.
 * Updates viewport.
 * 
 * @param env JNI environment
 * @param thiz Java object reference
 * @param width Surface width in pixels
 * @param height Surface height in pixels
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeSetSurfaceSize(
    JNIEnv* env, jobject thiz, jint width, jint height) {
    
    std::lock_guard<std::mutex> lock(g_mutex);

    LOGD("[AndroidPreview] SurfaceSize: %dx%d", width, height);

    g_surfaceWidth = width;
    g_surfaceHeight = height;

    if (g_eglDisplay != EGL_NO_DISPLAY && g_eglContext != EGL_NO_CONTEXT) {
        eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext);
        glViewport(0, 0, width, height);
    }

    // Limit preview decode resolution to surface size — avoids decoding 4K for a 640p surface
    if (g_preview && width > 0 && height > 0) {
        const int longEdge = std::max(width, height);
        g_preview->setGhostPreviewEnabled(true);
        g_preview->setGhostPreviewLongEdgePx(longEdge);
    }
}

/**
 * JNI: Called when SurfaceView surface is destroyed.
 * Cleans up EGL context and resources.
 * 
 * @param env JNI environment
 * @param thiz Java object reference
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeReleasePreview(
    JNIEnv* env, jobject thiz) {
    
    std::lock_guard<std::mutex> lock(g_mutex);

    LOGI("[AndroidPreview] Surface destroyed");

    // Stop render thread
    g_isRenderingActive.store(false, std::memory_order_release);
    g_shouldExit.store(true, std::memory_order_release);
    if (g_renderThread.joinable()) {
        g_renderThread.join();
    }

    // Detach only the surface; keep preview/timeline alive across transient
    // Activity pauses and picker round-trips.
    if (g_preview) {
        g_preview->detachSurface();
    }

    // Release overlay GPU resources tied to this EGL context.
    if (g_eglDisplay != EGL_NO_DISPLAY && g_eglContext != EGL_NO_CONTEXT &&
        g_eglSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext);
    }
    for (auto& kv : g_textOverlays) {
        releaseOverlayTextureResources(kv.first, kv.second);
    }

    // Terminate EGL
    terminateEGL();

    // Release native window
    if (g_nativeWindow) {
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
    }

    LOGI("[AndroidPreview] Cleanup complete");
}

/**
 * JNI: Add clip to timeline, returns clip ID.
 */
JNIEXPORT jint JNICALL
Java_com_video_engine_VideoPreviewView_nativeAddClip(
    JNIEnv* env, jobject thiz, jstring videoPathJ) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_preview) {
        LOGE("[Timeline] Cannot add clip: preview controller missing");
        return -1;
    }
    
    auto timeline = g_preview->getTimeline();
    if (!timeline) {
        LOGE("[Timeline] Cannot add clip: timeline missing");
        return -1;
    }

    const char* path = env->GetStringUTFChars(videoPathJ, nullptr);
    std::string videoPath(path);
    env->ReleaseStringUTFChars(videoPathJ, path);

    if (!g_preview->isReady()) {
        if (!g_preview->open(videoPath)) {
            LOGE("[Timeline] Failed to open first clip '%s': %s",
                 videoPath.c_str(),
                 g_preview->getLastError());
            return -1;
        }

        if (g_eglDisplay != EGL_NO_DISPLAY) {
            LOGI("[Timeline] Releasing outer EGL so PreviewController can own the surface");
            terminateEGL();
        }

        if (g_nativeWindow && !g_preview->attachSurface(g_nativeWindow)) {
            LOGE("[Timeline] Failed to attach surface after open '%s': %s",
                 videoPath.c_str(),
                 g_preview->getLastError());
            return -1;
        }
    }

    const int64_t probedDurationMs = probeClipDurationMs(videoPath);
    const int64_t clipDurationMs =
        probedDurationMs > 0 ? probedDurationMs : g_preview->getVideoDurationMs();
    auto clip = std::make_shared<VideoEngine::Clip>(
        videoPath,
        timeline->getDuration(),
        clipDurationMs);
    timeline->addClip(clip);
    cacheClipExportSource(
        static_cast<int>(clip->getId()),
        videoPath,
        clip->getDuration());
    
    LOGI("[Timeline] Added clip id=%d path=%s", clip->getId(), videoPath.c_str());
    return (jint)clip->getId();
}

/**
 * JNI: Remove clip from timeline.
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeRemoveClip(
    JNIEnv* env, jobject thiz, jint clipId) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_preview) return;
    
    auto timeline = g_preview->getTimeline();
    if (!timeline) return;

    timeline->removeClip(std::to_string(clipId));
    g_timelineClipPaths.erase(
        std::remove_if(
            g_timelineClipPaths.begin(),
            g_timelineClipPaths.end(),
            [clipId](const ClipExportSource& entry) { return entry.clipId == clipId; }),
        g_timelineClipPaths.end());
    LOGI("[Timeline] Removed clip id=%d", clipId);
}

/**
 * JNI: Get array of clip IDs in timeline order.
 */
JNIEXPORT jintArray JNICALL
Java_com_video_engine_VideoPreviewView_nativeGetClipIds(
    JNIEnv* env, jobject thiz) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_preview) return env->NewIntArray(0);
    
    auto timeline = g_preview->getTimeline();
    if (!timeline) return env->NewIntArray(0);

    const auto& clips = timeline->clips();
    jintArray result = env->NewIntArray(clips.size());
    if (result == nullptr) return nullptr;

    std::vector<jint> ids;
    for (const auto& clip : clips) {
        ids.push_back((jint)clip->getId());
    }

    env->SetIntArrayRegion(result, 0, ids.size(), ids.data());
    return result;
}

/**
 * JNI: Get clip duration in milliseconds.
 */
JNIEXPORT jlong JNICALL
Java_com_video_engine_VideoPreviewView_nativeGetClipDuration(
    JNIEnv* env, jobject thiz, jint clipId) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_preview) return 0;
    
    auto timeline = g_preview->getTimeline();
    if (!timeline) return 0;

    const auto& clips = timeline->clips();
    for (const auto& clip : clips) {
        if (clip->getId() == (uint32_t)clipId) {
            return (jlong)clip->getDuration();
        }
    }
    return 0;
}

/**
 * JNI: Get video width.
 * 
 * @param env JNI environment
 * @param thiz Java object reference
 * @return video width in pixels, or 0 if not initialized
 */
JNIEXPORT jint JNICALL
Java_com_video_engine_VideoPreviewView_nativeGetVideoWidth(
    JNIEnv* env, jobject thiz) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_preview) {
        return g_preview->getVideoWidth();
    }
    return 0;
}

/**
 * JNI: Get video height.
 * 
 * @param env JNI environment
 * @param thiz Java object reference
 * @return video height in pixels, or 0 if not initialized
 */
JNIEXPORT jint JNICALL
Java_com_video_engine_VideoPreviewView_nativeGetVideoHeight(
    JNIEnv* env, jobject thiz) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_preview) {
        return g_preview->getVideoHeight();
    }
    return 0;
}

/**
 * Seek to timeline position and render ONE preview frame (scrubbing).
 * Optimized for SeekBar drag events - uses fast seeking without resetting decoder.
 * 
 * This is the core of timeline scrubbing:
 * - User drags SeekBar → onProgressChanged() → nativeSeekPreview(timeMs)
 * - Native code seeks to timeMs and renders ONE frame
 * - Result: Instant frame preview without playback
 * 
 * Why this is fast:
 * 1. No thread spawning (uses existing EGL context)
 * 2. No surface recreation (reuses GL surface)
 * 3. No playback loop (single frame render)
 * 4. Decoder state kept between seeks (if possible)
 * 5. GPU rendering only (no CPU bitmap conversion)
 * 
 * Thread-safe via mutex. Makes EGL context current, seeks, decodes, renders.
 * 
 * @param env JNI environment
 * @param thiz Java object reference
 * @param timelineMs Timeline position in milliseconds
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeSeekPreview(
    JNIEnv* env, jobject thiz, jlong timelineMs) {
    
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_preview) {
        LOGE("[Scrub] Preview not initialized");
        return;
    }

    const int64_t clampedTimelineMs = std::max<int64_t>(0, static_cast<int64_t>(timelineMs));
    g_currentTimeMs.store(clampedTimelineMs, std::memory_order_release);

    const bool playbackActive = g_isRenderingActive.load(std::memory_order_acquire);
    if (playbackActive) {
        g_pendingScrubMs.store(static_cast<long long>(clampedTimelineMs), std::memory_order_release);
        LOGD("[Preview] seekTo %lldms - queued for playback render thread", (long long)clampedTimelineMs);
        return;
    }

    // Render paused scrubs immediately so they do not leak into the next play().
    g_pendingScrubMs.store(-1, std::memory_order_release);
    g_preview->scrubToTimelineTime(clampedTimelineMs);
    const int64_t scrubTime = g_preview->getPlaybackTimelineTimeMs();
    g_currentTimeMs.store(scrubTime, std::memory_order_release);

    if (shouldRenderTextOverlaysInPreviewLocked() &&
        hasActiveTextOverlayAtTimeLocked(scrubTime) &&
        makeOuterEglCurrentLocked("[Scrub]")) {
        renderTextOverlays(scrubTime);
        if (!eglSwapBuffers(g_eglDisplay, g_eglSurface)) {
            LOGW("[Scrub] eglSwapBuffers failed: 0x%x", eglGetError());
        }
    }

    LOGD("[Preview] seekTo %lldms - rendered immediately", (long long)scrubTime);
}

/**
 * Load a video file for preview.
 * 
 * @param env JNI environment
 * @param thiz Java object reference
 * @param videoPathJava Video file path as Java string
 * @return true if loaded successfully, false otherwise
 */
JNIEXPORT jboolean JNICALL
Java_com_video_engine_VideoPreviewView_nativeLoadVideo(
    JNIEnv* env, jobject thiz, jstring videoPathJava) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_preview || g_eglDisplay == EGL_NO_DISPLAY) {
        LOGE("[AndroidPreview] Preview or EGL not initialized");
        return JNI_FALSE;
    }
    
    // Convert Java string to C++ string
    const char* videoPathC = env->GetStringUTFChars(videoPathJava, nullptr);
    if (!videoPathC) {
        LOGE("[AndroidPreview] Failed to convert video path from Java");
        return JNI_FALSE;
    }
    
    std::string videoPath(videoPathC);
    env->ReleaseStringUTFChars(videoPathJava, videoPathC);
    
    // Make context current
    if (!eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext)) {
        LOGE("[AndroidPreview] eglMakeCurrent failed");
        return JNI_FALSE;
    }
    
    // Open video
    bool success = g_preview->open(videoPath);
    LOGI("[AndroidPreview] Video loaded: %s - %s", videoPath.c_str(), success ? "SUCCESS" : "FAILED");
    
    return success ? JNI_TRUE : JNI_FALSE;
}

/**
 * Start playback from a timeline position.
 * Activates the rendering loop to continuously render frames at native FPS.
 *
 * Architecture:
 * - Main thread calls JNI nativeStartPlayback(timeMs)
 * - JNI handler stores timeMs in g_currentTimeMs
 * - Signals g_isRenderingActive = true
 * - Render thread wakes up and begins playback loop
 * - Each frame: advance time by elapsed wall-clock, call scrubToTimelineTime()
 * - Scrubbing still works: main thread can call nativeSeekPreview() anytime
 *
 * Why native render thread for playback?
 * - Android main thread is for UI only
 * - GL rendering MUST happen on GL context thread
 * - Separate thread enables 30fps without blocking UI
 * - Wall-clock timing ensures smooth, frame-accurate playback
 *
 * @param env JNI environment
 * @param thiz Java object reference
 * @param startTimeMs Starting timeline position in milliseconds
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeStartPlayback(
    JNIEnv* env, jobject thiz, jlong startTimeMs) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_preview) {
        LOGE("[Preview] Preview not initialized");
        return;
    }

    const int64_t clampedStartMs = std::max<int64_t>(0, static_cast<int64_t>(startTimeMs));
    g_currentTimeMs.store(clampedStartMs, std::memory_order_release);
    g_pendingScrubMs.store(-1, std::memory_order_release);

    if (!g_preview->playFrom(clampedStartMs)) {
        LOGE("[Preview] playback start failed at %lld ms: %s",
             static_cast<long long>(clampedStartMs),
             g_preview->getLastError());
        return;
    }

    // Signal render thread to begin continuous rendering
    g_isRenderingActive.store(true, std::memory_order_release);

    LOGI("[Preview] playback started at %lld ms", (long long)clampedStartMs);
}

/**
 * Stop playback.
 * Deactivates the rendering loop and freezes on the current frame.
 *
 * Thread-safe: Render thread checks g_isRenderingActive flag and stops gracefully.
 * Scrubbing still works after pause: UI can call nativeSeekPreview().
 *
 * @param env JNI environment
 * @param thiz Java object reference
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeStopPlayback(
    JNIEnv* env, jobject thiz) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_preview) {
        return;
    }

    // Signal render thread to stop playback
    g_isRenderingActive.store(false, std::memory_order_release);

    // Stop playback in PreviewController
    g_preview->stop();

    LOGI("[Preview] playback paused");
}

JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeSetAudioMasterClockEnabled(
    JNIEnv* env, jobject thiz, jboolean enabled) {

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_preview) {
        return;
    }
    g_preview->setAudioMasterClockEnabled(enabled == JNI_TRUE);
    LOGI("[Preview] audio master clock %s", enabled == JNI_TRUE ? "enabled" : "disabled");
}

JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeUpdateAudioClockUs(
    JNIEnv* env, jobject thiz, jlong ptsUs) {

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_preview) {
        return;
    }
    g_preview->updateAudioMasterClockUs(static_cast<int64_t>(ptsUs));
}

/**
 * Pause rendering (called from Activity.onPause()).
 * Stops the rendering thread and releases EGL context for other apps.
 * 
 * @param env JNI environment
 * @param thiz Java object reference
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativePauseRendering(
    JNIEnv* env, jobject thiz) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (g_eglDisplay != EGL_NO_DISPLAY && g_eglContext != EGL_NO_CONTEXT) {
        eglMakeCurrent(g_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    
    g_isRenderingActive.store(false, std::memory_order_release);
    
    if (g_preview) {
        g_preview->stop();
    }
    
    LOGI("[AndroidPreview] Rendering paused");
}

/**
 * Resume rendering (called from Activity.onResume()).
 * Re-acquires EGL context and resumes rendering.
 * 
 * @param env JNI environment
 * @param thiz Java object reference
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeResumeRendering(
    JNIEnv* env, jobject thiz) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (g_eglDisplay != EGL_NO_DISPLAY && g_eglContext != EGL_NO_CONTEXT && 
        g_eglSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext);
    }
    
    LOGI("[AndroidPreview] Rendering resumed");
}

/**
 * Get video duration in milliseconds.
 * 
 * Call this after loadVideo() to get the total duration.
 * Used to set SeekBar maximum range for timeline scrubbing.
 * 
 * @param env JNI environment
 * @param thiz Java object reference
 * @return Duration in milliseconds, or 0 if not loaded
 * 
 * Example:
 *   long durationMs = getDuration();
 *   seekBar.setMax((int)(durationMs / 1000));  // Progress in seconds
 */
JNIEXPORT jlong JNICALL
Java_com_video_engine_VideoPreviewView_getDuration(
    JNIEnv* env, jobject thiz) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_preview) {
        LOGE("[Duration] Not initialized");
        return 0L;
    }
    
    long long durationMs = g_preview->getVideoDurationMs();
    if (auto timeline = g_preview->getTimeline()) {
        durationMs = std::max<long long>(durationMs, timeline->getDuration());
    }
    LOGI("[Duration] duration=%lldms", (long long)durationMs);
    
    return (jlong)durationMs;
}

JNIEXPORT jlong JNICALL
Java_com_video_engine_VideoPreviewView_nativeGetDuration(
    JNIEnv* env, jobject thiz) {
    return Java_com_video_engine_VideoPreviewView_getDuration(env, thiz);
}

JNIEXPORT jlong JNICALL
Java_com_video_engine_VideoPreviewView_nativeGetCurrentPlaybackTime(
    JNIEnv* env, jobject thiz) {

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_preview) {
        return static_cast<jlong>(g_currentTimeMs.load(std::memory_order_acquire));
    }
    return static_cast<jlong>(g_preview->getPlaybackTimelineTimeMs());
}

JNIEXPORT jlong JNICALL
Java_com_video_engine_VideoPreviewView_nativeGetCurrentPlaybackTimeFast(
    JNIEnv* env, jobject thiz) {

    // Lock-free hot path for UI choreographer tick.
    // Render thread continuously updates this atomic clock.
    return static_cast<jlong>(g_currentTimeMs.load(std::memory_order_acquire));
}

JNIEXPORT jboolean JNICALL
Java_com_video_engine_VideoPreviewView_nativeIsPlaybackActive(
    JNIEnv* env, jobject thiz) {

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_preview) {
        return g_isRenderingActive.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
    }
    return (g_isRenderingActive.load(std::memory_order_acquire) && g_preview->isPlaying())
        ? JNI_TRUE
        : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_video_engine_VideoPreviewView_nativeIsPlaybackActiveFast(
    JNIEnv* env, jobject thiz) {

    return g_isRenderingActive.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

/**
 * JNI: Add a text overlay to the timeline.
 * Returns a numeric id for the overlay.
 */
JNIEXPORT jlong JNICALL
Java_com_video_engine_VideoPreviewView_nativeAddTextOverlay(
    JNIEnv* env, jobject thiz,
    jint idParam,
    jstring textJ,
    jfloat x, jfloat y,
    jfloat scale, jfloat rotation,
    jint color,
    jfloat fontSize,
    jint startTimeMs, jint endTimeMs) {

    std::lock_guard<std::mutex> lock(g_mutex);

    TextOverlay t;
    const char* cstr = env->GetStringUTFChars(textJ, nullptr);
    if (cstr) {
        t.text = cstr;
        env->ReleaseStringUTFChars(textJ, cstr);
    }
    t.x = x;
    t.y = y;
    t.scale = scale;
    t.rotation = rotation;
    t.color = static_cast<uint32_t>(color);
    t.startTime = static_cast<TimeMs>(startTimeMs);
    t.endTime = static_cast<TimeMs>(endTimeMs);
    t.enabled = true;

    if ((int)idParam <= 0) {
        t.id = g_nextTextOverlayId++;
    } else {
        t.id = (int64_t)idParam;
        if (t.id >= g_nextTextOverlayId) g_nextTextOverlayId = t.id + 1;
    }

    g_textOverlays[t.id] = t;
    // Maintain order list and mark dirty so sorting happens before next render
    g_textOverlayOrder.push_back(t.id);
    g_orderDirty = true;

    LOGI("[Text] added id=%lld text='%s' start=%lld end=%lld", (long long)t.id, t.text.c_str(), (long long)t.startTime, (long long)t.endTime);

    return (jlong)t.id;
}

/**
 * JNI: Update transform/properties of an existing overlay.
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeUpdateTextOverlay(
    JNIEnv* env, jobject thiz,
    jint idParam,
    jfloat x, jfloat y,
    jfloat scale, jfloat rotation,
    jint color,
    jfloat fontSize,
    jint startTimeMs, int endTimeMs) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_textOverlays.find((int64_t)idParam);
    if (it == g_textOverlays.end()) return;
    const DirtyRegionPx oldDirty = textOverlayBoundsPx(it->second);
    TextOverlay& t = it->second;
    t.x = x;
    t.y = y;
    t.scale = scale;
    t.rotation = rotation;
    t.color = static_cast<uint32_t>(color);
    t.startTime = static_cast<TimeMs>(startTimeMs);
    t.endTime = static_cast<TimeMs>(endTimeMs);

    const DirtyRegionPx newDirty = textOverlayBoundsPx(t);
    const DirtyRegionPx dirtyUnion = unionDirtyRegion(oldDirty, newDirty);
    renderOverlayEditFrame(dirtyUnion.isValid() ? &dirtyUnion : nullptr);
}

/**
 * JNI: Update only the scale of a text overlay (optimized for pinch gestures).
 * This updates the in-memory overlay scale and triggers a single render pass
 * so the user sees the scale change immediately on the preview.
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeUpdateTextScale(
    JNIEnv* env, jobject thiz,
    jint idParam, jfloat scale) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_textOverlays.find((int64_t)idParam);
    if (it == g_textOverlays.end()) return;
    const DirtyRegionPx oldDirty = textOverlayBoundsPx(it->second);
    TextOverlay& t = it->second;
    t.scale = scale;
    const DirtyRegionPx newDirty = textOverlayBoundsPx(t);
    const DirtyRegionPx dirtyUnion = unionDirtyRegion(oldDirty, newDirty);

    renderOverlayEditFrame(dirtyUnion.isValid() ? &dirtyUnion : nullptr);
}

/**
 * JNI: Update only the rotation (degrees) of a text overlay (optimized for two-finger rotate gestures).
 * Updates in-memory overlay rotation and triggers a single render pass for immediate feedback.
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeUpdateTextRotation(
    JNIEnv* env, jobject thiz,
    jint idParam, jfloat rotationDeg) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_textOverlays.find((int64_t)idParam);
    if (it == g_textOverlays.end()) return;
    const DirtyRegionPx oldDirty = textOverlayBoundsPx(it->second);
    TextOverlay& t = it->second;
    t.rotation = rotationDeg;
    const DirtyRegionPx newDirty = textOverlayBoundsPx(t);
    const DirtyRegionPx dirtyUnion = unionDirtyRegion(oldDirty, newDirty);

    renderOverlayEditFrame(dirtyUnion.isValid() ? &dirtyUnion : nullptr);
}

/**
 * JNI: Update opacity and fade durations for a text overlay.
 * Parameters: opacity (0.0..1.0), fadeInMs, fadeOutMs
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeUpdateTextOpacity(
    JNIEnv* env, jobject thiz,
    jint idParam, jfloat opacity, jint fadeInMs, jint fadeOutMs) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_textOverlays.find((int64_t)idParam);
    if (it == g_textOverlays.end()) return;
    const DirtyRegionPx oldDirty = textOverlayBoundsPx(it->second);
    TextOverlay& t = it->second;
    t.opacity = opacity;
    t.fadeInMs = static_cast<int32_t>(fadeInMs);
    t.fadeOutMs = static_cast<int32_t>(fadeOutMs);
    const DirtyRegionPx newDirty = textOverlayBoundsPx(t);
    const DirtyRegionPx dirtyUnion = unionDirtyRegion(oldDirty, newDirty);

    LOGD("[Text] opacity updated id=%d -> %f fadeIn=%d fadeOut=%d", idParam, opacity, fadeInMs, fadeOutMs);
    renderOverlayEditFrame(dirtyUnion.isValid() ? &dirtyUnion : nullptr);
}

/**
 * JNI: Set explicit zOrder for a text overlay.
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeSetTextZOrder(
    JNIEnv* env, jobject thiz, jint idParam, jint z) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_textOverlays.find((int64_t)idParam);
    if (it == g_textOverlays.end()) return;
    const DirtyRegionPx dirtyRegion = textOverlayBoundsPx(it->second);
    it->second.zOrder = static_cast<int32_t>(z);
    g_orderDirty = true;

    LOGD("[Text] zOrder set id=%d -> %d", idParam, z);
    renderOverlayEditFrame(dirtyRegion.isValid() ? &dirtyRegion : nullptr);
}

/**
 * JNI: Bring overlay to front by assigning zOrder = maxZ + 1
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeBringTextOverlayToFront(
    JNIEnv* env, jobject thiz, jint idParam) {

    std::lock_guard<std::mutex> lock(g_mutex);
    int32_t maxZ = INT32_MIN;
    for (auto &kv : g_textOverlays) {
        maxZ = std::max<int32_t>(maxZ, kv.second.zOrder);
    }
    auto it = g_textOverlays.find((int64_t)idParam);
    if (it == g_textOverlays.end()) return;
    const DirtyRegionPx dirtyRegion = textOverlayBoundsPx(it->second);
    it->second.zOrder = (maxZ == INT32_MIN) ? 0 : (maxZ + 1);
    g_orderDirty = true;

    LOGD("[Text] bringToFront id=%d newZ=%d", idParam, it->second.zOrder);
    renderOverlayEditFrame(dirtyRegion.isValid() ? &dirtyRegion : nullptr);
}

/**
 * JNI: Send overlay to back by assigning zOrder = minZ - 1
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeSendTextOverlayToBack(
    JNIEnv* env, jobject thiz, jint idParam) {

    std::lock_guard<std::mutex> lock(g_mutex);
    int32_t minZ = INT32_MAX;
    for (auto &kv : g_textOverlays) {
        minZ = std::min<int32_t>(minZ, kv.second.zOrder);
    }
    auto it = g_textOverlays.find((int64_t)idParam);
    if (it == g_textOverlays.end()) return;
    const DirtyRegionPx dirtyRegion = textOverlayBoundsPx(it->second);
    it->second.zOrder = (minZ == INT32_MAX) ? 0 : (minZ - 1);
    g_orderDirty = true;

    LOGD("[Text] sendToBack id=%d newZ=%d", idParam, it->second.zOrder);
    renderOverlayEditFrame(dirtyRegion.isValid() ? &dirtyRegion : nullptr);
}

/**
 * JNI: Add or update a text keyframe for a given overlay.
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeAddTextKeyframe(
    JNIEnv* env, jobject thiz,
    jint idParam, jlong timeMs, jfloat posX, jfloat posY, jfloat scale, jfloat opacity) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_textOverlays.find((int64_t)idParam);
    if (it == g_textOverlays.end()) return;
    TextOverlay& t = it->second;

    // Insert or replace keyframe with same timeMs, keep vector sorted
    auto &kfs = t.keyframes;
    // search for existing
    for (auto &kf : kfs) {
        if (kf.timeMs == (int64_t)timeMs) {
            kf.posX = posX; kf.posY = posY; kf.scale = scale; kf.opacity = opacity;
            LOGD("[Text] update keyframe id=%d time=%lld", idParam, (long long)timeMs);
            return;
        }
    }
    TextOverlay::TextKeyframe nk;
    nk.timeMs = (int64_t)timeMs;
    nk.posX = posX; nk.posY = posY; nk.scale = scale; nk.opacity = opacity;
    kfs.push_back(nk);
    std::sort(kfs.begin(), kfs.end(), [](const TextOverlay::TextKeyframe &a, const TextOverlay::TextKeyframe &b){ return a.timeMs < b.timeMs; });

    LOGD("[Text] added keyframe id=%d time=%lld", idParam, (long long)timeMs);
}

/**
 * JNI: Delete a keyframe at specified time for an overlay.
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeDeleteTextKeyframe(JNIEnv* env, jobject thiz, jint idParam, jlong timeMs) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_textOverlays.find((int64_t)idParam);
    if (it == g_textOverlays.end()) return;
    auto &kfs = it->second.keyframes;
    auto it2 = std::remove_if(kfs.begin(), kfs.end(), [&](const TextOverlay::TextKeyframe &kf){ return kf.timeMs == (int64_t)timeMs; });
    if (it2 != kfs.end()) {
        kfs.erase(it2, kfs.end());
        LOGD("[Text] deleted keyframe id=%d time=%lld", idParam, (long long)timeMs);
    }
}

/**
 * JNI: Clear all keyframes for an overlay.
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeClearTextKeyframes(JNIEnv* env, jobject thiz, jint idParam) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_textOverlays.find((int64_t)idParam);
    if (it == g_textOverlays.end()) return;
    it->second.keyframes.clear();
    LOGD("[Text] cleared keyframes id=%d", idParam);
}

/**
 * JNI: Remove an overlay by id.
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeRemoveTextOverlay(
    JNIEnv* env, jobject thiz, jint idParam) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto existing = g_textOverlays.find((int64_t)idParam);
    if (existing != g_textOverlays.end()) {
        releaseOverlayTextureResources((int64_t)idParam, existing->second);
        g_textOverlays.erase(existing);
        g_overlayCpuBitmaps.erase((int64_t)idParam);
        LOGI("[Text] removed id=%lld", (long long)idParam);
        // Remove from order vector if present and mark dirty
        auto it = std::find(g_textOverlayOrder.begin(), g_textOverlayOrder.end(), (int64_t)idParam);
        if (it != g_textOverlayOrder.end()) {
            g_textOverlayOrder.erase(it);
            g_orderDirty = true;
        }
    }
}

/**
 * JNI: Upload ARGB pixels from Android to create/update a GL texture for overlay.
 * pixels: int[] ARGB_8888 (row-major)
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeSetTextOverlayBitmap(
    JNIEnv* env, jobject thiz,
    jint idParam,
    jintArray pixels,
    jint width, jint height) {
    (void)thiz;

    if (pixels == nullptr || width <= 0 || height <= 0) return;

    std::lock_guard<std::mutex> lock(g_mutex);

    auto it = g_textOverlays.find((int64_t)idParam);
    if (it == g_textOverlays.end()) {
        LOGW("[Text] setBitmap: overlay id=%d not found", idParam);
        return;
    }

    TextOverlay& t = it->second;

    jint *arr = env->GetIntArrayElements(pixels, nullptr);
    if (!arr) return;
    jsize len = env->GetArrayLength(pixels);

    // Convert ARGB int -> RGBA bytes
    std::vector<uint8_t> rgba;
    rgba.reserve((size_t)len * 4);
    for (jsize i = 0; i < len; ++i) {
        uint32_t p = static_cast<uint32_t>(arr[i]);
        uint8_t a = (p >> 24) & 0xFF;
        uint8_t r = (p >> 16) & 0xFF;
        uint8_t g = (p >> 8) & 0xFF;
        uint8_t b = (p >> 0) & 0xFF;
        rgba.push_back(r);
        rgba.push_back(g);
        rgba.push_back(b);
        rgba.push_back(a);
    }

    // Release Java array (we copied data)
    env->ReleaseIntArrayElements(pixels, arr, JNI_ABORT);

    // Always keep a CPU-side copy so export can composite overlays even if
    // the live EGL context is unavailable at upload time.
    g_overlayCpuBitmaps[t.id] = OverlayCpuBitmap{rgba, width, height};

    if (g_eglDisplay == EGL_NO_DISPLAY || g_eglContext == EGL_NO_CONTEXT ||
        g_eglSurface == EGL_NO_SURFACE) {
        t.texWidth = width;
        t.texHeight = height;
        t.hasTexture = false;
        LOGW("[Text] setBitmap stored CPU copy only: outer EGL unavailable id=%lld w=%d h=%d",
             (long long)t.id, width, height);
        return;
    }
    if (!eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext)) {
        t.texWidth = width;
        t.texHeight = height;
        t.hasTexture = false;
        LOGW("[Text] setBitmap stored CPU copy only: eglMakeCurrent failed id=%lld w=%d h=%d",
             (long long)t.id, width, height);
        return;
    }

    // Create or update GL texture
    releaseOverlayTextureResources((int64_t)idParam, t);

    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
    glBindTexture(GL_TEXTURE_2D, 0);

    t.texture = (unsigned int)tex;
    t.texWidth = width;
    t.texHeight = height;
    t.hasTexture = true;

    LOGI("[Text] bitmap uploaded id=%lld w=%d h=%d tex=%u", (long long)t.id, width, height, tex);
}

/**
 * JNI: Zero-copy style upload path using Android HardwareBuffer.
 * Returns true if the buffer is imported as EGLImage and bound to overlay texture.
 */
JNIEXPORT jboolean JNICALL
Java_com_video_engine_VideoPreviewView_nativeSetTextOverlayHardwareBuffer(
    JNIEnv* env, jobject thiz,
    jint idParam,
    jobject hardwareBufferObj,
    jint width,
    jint height) {
    (void)thiz;
    g_hardwareBufferTelemetry.attempts.fetch_add(1, std::memory_order_relaxed);
    if (hardwareBufferObj == nullptr || width <= 0 || height <= 0) {
        recordHardwareBufferFallback(g_hardwareBufferTelemetry.invalidArgs);
        maybeLogHardwareBufferTelemetrySample();
        return JNI_FALSE;
    }
    if (!ensureAhbApiLoaded() || !g_ahbApi.fromHardwareBuffer || !g_ahbApi.acquire) {
        recordHardwareBufferFallback(g_hardwareBufferTelemetry.bridgeUnavailable);
        LOGW("[Text] setHardwareBuffer: AHardwareBuffer bridge unavailable");
        maybeLogHardwareBufferTelemetrySample();
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_eglDisplay == EGL_NO_DISPLAY || g_eglContext == EGL_NO_CONTEXT ||
        g_eglSurface == EGL_NO_SURFACE) {
        recordHardwareBufferFallback(g_hardwareBufferTelemetry.eglUnavailable);
        maybeLogHardwareBufferTelemetrySample();
        return JNI_FALSE;
    }
    if (!eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext)) {
        recordHardwareBufferFallback(g_hardwareBufferTelemetry.eglMakeCurrentFailed);
        LOGW("[Text] setHardwareBuffer: eglMakeCurrent failed");
        maybeLogHardwareBufferTelemetrySample();
        return JNI_FALSE;
    }

    auto overlayIt = g_textOverlays.find((int64_t)idParam);
    if (overlayIt == g_textOverlays.end()) {
        recordHardwareBufferFallback(g_hardwareBufferTelemetry.overlayMissing);
        LOGW("[Text] setHardwareBuffer: overlay id=%d not found", idParam);
        maybeLogHardwareBufferTelemetrySample();
        return JNI_FALSE;
    }
    TextOverlay& overlay = overlayIt->second;

    AHardwareBuffer* ahb = g_ahbApi.fromHardwareBuffer(env, hardwareBufferObj);
    if (!ahb) {
        recordHardwareBufferFallback(g_hardwareBufferTelemetry.importFailed);
        LOGW("[Text] setHardwareBuffer: AHardwareBuffer_fromHardwareBuffer failed");
        maybeLogHardwareBufferTelemetrySample();
        return JNI_FALSE;
    }

    auto eglGetNativeClientBufferANDROIDFn =
        reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
            eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    auto eglCreateImageKHRFn =
        reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(
            eglGetProcAddress("eglCreateImageKHR"));
    auto glEGLImageTargetTexture2DOESFn =
        reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
            eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (!eglGetNativeClientBufferANDROIDFn || !eglCreateImageKHRFn || !glEGLImageTargetTexture2DOESFn) {
        recordHardwareBufferFallback(g_hardwareBufferTelemetry.extensionMissing);
        LOGW("[Text] setHardwareBuffer: required EGL/GL extension missing");
        maybeLogHardwareBufferTelemetrySample();
        return JNI_FALSE;
    }

    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROIDFn(ahb);
    if (!clientBuffer) {
        recordHardwareBufferFallback(g_hardwareBufferTelemetry.nativeClientBufferFailed);
        LOGW("[Text] setHardwareBuffer: eglGetNativeClientBufferANDROID failed");
        maybeLogHardwareBufferTelemetrySample();
        return JNI_FALSE;
    }

    const EGLint attrs[] = {
        EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
        EGL_NONE
    };
    EGLImageKHR image = eglCreateImageKHRFn(
        g_eglDisplay,
        EGL_NO_CONTEXT,
        EGL_NATIVE_BUFFER_ANDROID,
        clientBuffer,
        attrs);
    if (image == EGL_NO_IMAGE_KHR) {
        recordHardwareBufferFallback(g_hardwareBufferTelemetry.createImageFailed);
        LOGW("[Text] setHardwareBuffer: eglCreateImageKHR failed: 0x%x", eglGetError());
        maybeLogHardwareBufferTelemetrySample();
        return JNI_FALSE;
    }

    releaseOverlayTextureResources((int64_t)idParam, overlay);

    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glEGLImageTargetTexture2DOESFn(GL_TEXTURE_2D, image);
    glBindTexture(GL_TEXTURE_2D, 0);

    GLenum glError = glGetError();
    if (glError != GL_NO_ERROR) {
        recordHardwareBufferFallback(g_hardwareBufferTelemetry.bindImageFailed);
        glDeleteTextures(1, &tex);
        destroyEglImageCompat(g_eglDisplay, image);
        LOGW("[Text] setHardwareBuffer: glEGLImageTargetTexture2DOES failed: 0x%x", glError);
        maybeLogHardwareBufferTelemetrySample();
        return JNI_FALSE;
    }

    g_ahbApi.acquire(ahb);
    OverlaySharedImage sharedImage;
    sharedImage.image = image;
    sharedImage.buffer = ahb;
    g_overlaySharedImages[(int64_t)idParam] = sharedImage;

    overlay.texture = tex;
    overlay.texWidth = width;
    overlay.texHeight = height;
    overlay.hasTexture = true;

    LOGI("[Text] hardware buffer imported id=%lld w=%d h=%d tex=%u",
         (long long)overlay.id,
         width,
         height,
         tex);
    g_hardwareBufferTelemetry.successes.fetch_add(1, std::memory_order_relaxed);
    maybeLogHardwareBufferTelemetrySample();
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_video_engine_VideoPreviewView_nativeGetHardwareBufferTelemetry(
    JNIEnv* env,
    jobject thiz) {
    (void)thiz;
    const std::string telemetryJson = buildHardwareBufferTelemetryJson();
    return env->NewStringUTF(telemetryJson.c_str());
}

JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeResetHardwareBufferTelemetry(
    JNIEnv* env,
    jobject thiz) {
    (void)env;
    (void)thiz;
    g_hardwareBufferTelemetry.attempts.store(0, std::memory_order_relaxed);
    g_hardwareBufferTelemetry.successes.store(0, std::memory_order_relaxed);
    g_hardwareBufferTelemetry.fallbacks.store(0, std::memory_order_relaxed);
    g_hardwareBufferTelemetry.invalidArgs.store(0, std::memory_order_relaxed);
    g_hardwareBufferTelemetry.bridgeUnavailable.store(0, std::memory_order_relaxed);
    g_hardwareBufferTelemetry.eglUnavailable.store(0, std::memory_order_relaxed);
    g_hardwareBufferTelemetry.eglMakeCurrentFailed.store(0, std::memory_order_relaxed);
    g_hardwareBufferTelemetry.overlayMissing.store(0, std::memory_order_relaxed);
    g_hardwareBufferTelemetry.importFailed.store(0, std::memory_order_relaxed);
    g_hardwareBufferTelemetry.extensionMissing.store(0, std::memory_order_relaxed);
    g_hardwareBufferTelemetry.nativeClientBufferFailed.store(0, std::memory_order_relaxed);
    g_hardwareBufferTelemetry.createImageFailed.store(0, std::memory_order_relaxed);
    g_hardwareBufferTelemetry.bindImageFailed.store(0, std::memory_order_relaxed);
    LOGI("[Text] HardwareBuffer telemetry reset");
}

/**
 * Export video with all compositions (video, effects, text, audio).
 * 
 * Spawns background export thread to render frames via FFmpeg.
 * Progress reported via polling nativeGetExportProgress().
 * 
 * @param env JNI environment
 * @param thiz Java object reference
 * @param outputPathJava Output file path (absolute)
 * @param width Output width in pixels
 * @param height Output height in pixels
 * @param fps Output frame rate
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeSetExportAudioClips(
    JNIEnv* env, jobject thiz,
    jobjectArray pathsArray,
    jlongArray startTimesMs,
    jlongArray durationsMs,
    jfloatArray volumes,
    jintArray fadeInMs,
    jintArray fadeOutMs,
    jobjectArray keyframeCsvArray) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_audioExportClips.clear();
    if (!pathsArray) return;
    const jsize count = env->GetArrayLength(pathsArray);
    jlong* starts = startTimesMs ? env->GetLongArrayElements(startTimesMs, nullptr) : nullptr;
    jlong* durs   = durationsMs  ? env->GetLongArrayElements(durationsMs,  nullptr) : nullptr;
    jfloat* vols  = volumes      ? env->GetFloatArrayElements(volumes,     nullptr) : nullptr;
    jint* fadeIns = fadeInMs ? env->GetIntArrayElements(fadeInMs, nullptr) : nullptr;
    jint* fadeOuts = fadeOutMs ? env->GetIntArrayElements(fadeOutMs, nullptr) : nullptr;
    
    // Validate array lengths to prevent crashes
    const jsize startsCount = startTimesMs ? env->GetArrayLength(startTimesMs) : 0;
    const jsize dursCount = durationsMs ? env->GetArrayLength(durationsMs) : 0;
    const jsize volsCount = volumes ? env->GetArrayLength(volumes) : 0;
    const jsize fadeInsCount = fadeInMs ? env->GetArrayLength(fadeInMs) : 0;
    const jsize fadeOutsCount = fadeOutMs ? env->GetArrayLength(fadeOutMs) : 0;
    const jsize keyframeCsvCount = keyframeCsvArray ? env->GetArrayLength(keyframeCsvArray) : 0;
    
    if (startsCount != count || dursCount != count || volsCount != count ||
        fadeInsCount != count || fadeOutsCount != count || keyframeCsvCount != count) {
        LOGE("[Export] Array length mismatch: paths=%d starts=%d durs=%d vols=%d fadeIn=%d fadeOut=%d keyframes=%d - skipping",
             (int)count, (int)startsCount, (int)dursCount, (int)volsCount,
             (int)fadeInsCount, (int)fadeOutsCount, (int)keyframeCsvCount);
        if (starts) env->ReleaseLongArrayElements(startTimesMs,  starts, JNI_ABORT);
        if (durs)   env->ReleaseLongArrayElements(durationsMs,   durs,   JNI_ABORT);
        if (vols)   env->ReleaseFloatArrayElements(volumes,      vols,   JNI_ABORT);
        if (fadeIns) env->ReleaseIntArrayElements(fadeInMs, fadeIns, JNI_ABORT);
        if (fadeOuts) env->ReleaseIntArrayElements(fadeOutMs, fadeOuts, JNI_ABORT);
        return;
    }
    
    for (jsize i = 0; i < count; ++i) {
        jstring js = (jstring)env->GetObjectArrayElement(pathsArray, i);
        if (!js) continue;
        const char* cs = env->GetStringUTFChars(js, nullptr);
        AudioExportClip ac;
        ac.path        = cs ? cs : "";
        ac.startTimeMs = starts ? starts[i] : 0;
        ac.durationMs  = durs   ? durs[i]   : 0;
        ac.volume      = vols   ? vols[i]   : 1.0f;
        ac.fadeInMs    = fadeIns ? std::max<jint>(0, fadeIns[i]) : 0;
        ac.fadeOutMs   = fadeOuts ? std::max<jint>(0, fadeOuts[i]) : 0;
        if (keyframeCsvArray) {
            jstring keyframeCsvJ = (jstring)env->GetObjectArrayElement(keyframeCsvArray, i);
            if (keyframeCsvJ) {
                const char* keyframeCsvChars = env->GetStringUTFChars(keyframeCsvJ, nullptr);
                ac.audioGainKeyframes = parseAudioGainKeyframesCsv(
                    keyframeCsvChars ? keyframeCsvChars : "",
                    ac.durationMs);
                if (keyframeCsvChars) env->ReleaseStringUTFChars(keyframeCsvJ, keyframeCsvChars);
                env->DeleteLocalRef(keyframeCsvJ);
            }
        }
        ac.sourceInMs  = 0;
        ac.sourceOutMs = ac.durationMs > 0 ? ac.durationMs : 0;
        g_audioExportClips.push_back(ac);
        if (cs) env->ReleaseStringUTFChars(js, cs);
        env->DeleteLocalRef(js);
    }
    if (starts) env->ReleaseLongArrayElements(startTimesMs,  starts, JNI_ABORT);
    if (durs)   env->ReleaseLongArrayElements(durationsMs,   durs,   JNI_ABORT);
    if (vols)   env->ReleaseFloatArrayElements(volumes,      vols,   JNI_ABORT);
    if (fadeIns) env->ReleaseIntArrayElements(fadeInMs, fadeIns, JNI_ABORT);
    if (fadeOuts) env->ReleaseIntArrayElements(fadeOutMs, fadeOuts, JNI_ABORT);
    LOGI("[Export] registered %d audio clips", (int)g_audioExportClips.size());
}

#if defined(VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE)
static int64_t mapAudioClipTimelineToSourceMs(
    const AudioExportClip& clip,
    int64_t timelineMs) {
    const int64_t clipStart = clip.startTimeMs;
    const int64_t clipDuration = std::max<int64_t>(1, clip.durationMs);
    const int64_t clipEndExclusive = clipStart + clipDuration;
    int64_t sourceInMs = std::max<int64_t>(0, clip.sourceInMs);
    int64_t sourceOutMs = clip.sourceOutMs;
    if (sourceOutMs <= sourceInMs) {
        sourceOutMs = sourceInMs + clipDuration;
    }
    if (sourceOutMs <= sourceInMs) {
        sourceOutMs = sourceInMs + 1;
    }

    auto mapWithoutFreeze = [&](int64_t localTimelineMs) -> int64_t {
        const int64_t clampedLocalMs = std::clamp<int64_t>(localTimelineMs, 0, clipDuration - 1);
        const double localProgress = clipDuration > 1
            ? static_cast<double>(clampedLocalMs) / static_cast<double>(clipDuration - 1)
            : 0.0;
        const double t = std::clamp(localProgress, 0.0, 1.0);

        double shaped = t;
        const float clampedCurveStrength = std::clamp(clip.curveSpeedStrength, 0.1f, 4.0f);
        if (clip.curveSpeedProfile == "ease_in") {
            const double gamma = 1.0 + (std::max(0.0f, clampedCurveStrength - 1.0f) * 1.35);
            shaped = std::pow(t, gamma);
        } else if (clip.curveSpeedProfile == "ease_out") {
            const double gamma = 1.0 + (std::max(0.0f, clampedCurveStrength - 1.0f) * 1.35);
            shaped = 1.0 - std::pow(1.0 - t, gamma);
        } else if (clip.curveSpeedProfile == "ease_in_out") {
            shaped = t * t * (3.0 - 2.0 * t);
        } else if (clip.curveSpeedProfile == "hyperlapse") {
            const double alpha = std::clamp(1.4 - (clampedCurveStrength * 0.15), 0.55, 1.4);
            shaped = std::pow(t, alpha);
        }

        const int64_t trimmedSpanMs = std::max<int64_t>(1, sourceOutMs - sourceInMs);
        const int64_t curveSourceMs = sourceInMs + static_cast<int64_t>(
            std::llround(shaped * static_cast<double>(trimmedSpanMs - 1)));
        const double playbackSpeed = std::max(0.1f, clip.playbackSpeed);
        const int64_t speedSourceMs = sourceInMs + static_cast<int64_t>(
            std::llround(static_cast<double>(clampedLocalMs) * playbackSpeed));

        int64_t mappedSourceMs = speedSourceMs;
        if (clip.curveSpeedProfile != "linear") {
            const double blend = std::clamp(
                static_cast<double>(clampedCurveStrength - 0.1f) / 3.9,
                0.15,
                0.9);
            mappedSourceMs = static_cast<int64_t>(
                std::llround((1.0 - blend) * static_cast<double>(speedSourceMs) +
                             blend * static_cast<double>(curveSourceMs)));
        }
        mappedSourceMs = std::clamp<int64_t>(mappedSourceMs, sourceInMs, sourceOutMs - 1);
        if (clip.reversePlayback) {
            mappedSourceMs = sourceOutMs - 1 - (mappedSourceMs - sourceInMs);
            mappedSourceMs = std::clamp<int64_t>(mappedSourceMs, sourceInMs, sourceOutMs - 1);
        }
        return mappedSourceMs;
    };

    if (clip.freezeFrameEnabled && clip.freezeFrameDurationMs > 0) {
        const int64_t freezeStartMs = std::clamp<int64_t>(
            clip.freezeFrameTimeMs,
            clipStart,
            std::max<int64_t>(clipStart, clipEndExclusive - 1));
        const int64_t freezeEndMs = freezeStartMs + std::max<int64_t>(100, clip.freezeFrameDurationMs);
        if (timelineMs >= freezeStartMs && timelineMs < freezeEndMs) {
            const int64_t freezeLocalMs = std::clamp<int64_t>(freezeStartMs - clipStart, 0, clipDuration - 1);
            return mapWithoutFreeze(freezeLocalMs);
        }
    }

    const int64_t localTimelineMs = std::clamp<int64_t>(timelineMs - clipStart, 0, clipDuration - 1);
    return mapWithoutFreeze(localTimelineMs);
}

static bool decodeAudioClipToStereoFloat(
    const std::string& inputPath,
    int outputSampleRate,
    std::vector<float>& pcmOut,
    std::string& errorOut) {
    AVFormatContext* inputFmt = nullptr;
    AVCodecContext* decoderCtx = nullptr;
    SwrContext* swr = nullptr;
    AVFrame* frame = nullptr;
    AVPacket* packet = nullptr;
    int audioStreamIndex = -1;

    auto cleanup = [&]() {
        if (packet) av_packet_free(&packet);
        if (frame) av_frame_free(&frame);
        if (swr) swr_free(&swr);
        if (decoderCtx) avcodec_free_context(&decoderCtx);
        if (inputFmt) avformat_close_input(&inputFmt);
    };

    if (avformat_open_input(&inputFmt, inputPath.c_str(), nullptr, nullptr) < 0 ||
        avformat_find_stream_info(inputFmt, nullptr) < 0) {
        errorOut = "Failed to open audio input";
        cleanup();
        return false;
    }

    for (unsigned int i = 0; i < inputFmt->nb_streams; ++i) {
        if (inputFmt->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            audioStreamIndex = static_cast<int>(i);
            break;
        }
    }
    if (audioStreamIndex < 0) {
        errorOut = "No audio stream found";
        cleanup();
        return false;
    }

    AVStream* audioStream = inputFmt->streams[audioStreamIndex];
    const AVCodec* decoder = avcodec_find_decoder(audioStream->codecpar->codec_id);
    if (!decoder) {
        errorOut = "Audio decoder unavailable";
        cleanup();
        return false;
    }

    decoderCtx = avcodec_alloc_context3(decoder);
    if (!decoderCtx ||
        avcodec_parameters_to_context(decoderCtx, audioStream->codecpar) < 0 ||
        avcodec_open2(decoderCtx, decoder, nullptr) < 0) {
        errorOut = "Failed to initialize audio decoder";
        cleanup();
        return false;
    }

    AVChannelLayout inputLayout{};
    const int inputChannels = std::max(
        1,
        std::max(
            decoderCtx->ch_layout.nb_channels,
            audioStream->codecpar ? audioStream->codecpar->ch_layout.nb_channels : 0));
    if (decoderCtx->ch_layout.nb_channels > 0) {
        av_channel_layout_copy(&inputLayout, &decoderCtx->ch_layout);
    } else if (audioStream->codecpar && audioStream->codecpar->ch_layout.nb_channels > 0) {
        av_channel_layout_copy(&inputLayout, &audioStream->codecpar->ch_layout);
    } else {
        LOGW("[Export] audio layout missing for %s, defaulting to %d channel(s)",
             inputPath.c_str(),
             inputChannels);
        av_channel_layout_default(&inputLayout, inputChannels);
    }

    AVChannelLayout outLayout{};
    av_channel_layout_default(&outLayout, 2);
    if (swr_alloc_set_opts2(
            &swr,
            &outLayout,
            AV_SAMPLE_FMT_FLT,
            outputSampleRate,
            &inputLayout,
            decoderCtx->sample_fmt,
            std::max(1, decoderCtx->sample_rate),
            0,
            nullptr) < 0 ||
        !swr ||
        swr_init(swr) < 0) {
        av_channel_layout_uninit(&outLayout);
        av_channel_layout_uninit(&inputLayout);
        errorOut = "Failed to initialize audio resampler";
        cleanup();
        return false;
    }
    av_channel_layout_uninit(&outLayout);
    av_channel_layout_uninit(&inputLayout);

    frame = av_frame_alloc();
    packet = av_packet_alloc();
    if (!frame || !packet) {
        errorOut = "Failed to allocate audio decode buffers";
        cleanup();
        return false;
    }

    auto appendFrame = [&](AVFrame* decodedFrame) -> bool {
        const int dstSamples = av_rescale_rnd(
            swr_get_delay(swr, std::max(1, decoderCtx->sample_rate)) + decodedFrame->nb_samples,
            outputSampleRate,
            std::max(1, decoderCtx->sample_rate),
            AV_ROUND_UP);
        if (dstSamples <= 0) {
            return true;
        }
        const size_t oldSize = pcmOut.size();
        pcmOut.resize(oldSize + static_cast<size_t>(dstSamples) * 2);
        uint8_t* outData[4] = {
            reinterpret_cast<uint8_t*>(pcmOut.data() + oldSize),
            nullptr,
            nullptr,
            nullptr
        };
        const int converted = swr_convert(
            swr,
            outData,
            dstSamples,
            const_cast<const uint8_t**>(decodedFrame->extended_data),
            decodedFrame->nb_samples);
        if (converted < 0) {
            errorOut = "Failed to resample decoded audio";
            return false;
        }
        pcmOut.resize(oldSize + static_cast<size_t>(converted) * 2);
        return true;
    };

    while (av_read_frame(inputFmt, packet) >= 0) {
        if (packet->stream_index != audioStreamIndex) {
            av_packet_unref(packet);
            continue;
        }
        if (avcodec_send_packet(decoderCtx, packet) < 0) {
            av_packet_unref(packet);
            errorOut = "Failed to send packet to audio decoder";
            cleanup();
            return false;
        }
        av_packet_unref(packet);
        while (avcodec_receive_frame(decoderCtx, frame) == 0) {
            if (!appendFrame(frame)) {
                cleanup();
                return false;
            }
            av_frame_unref(frame);
        }
    }

    avcodec_send_packet(decoderCtx, nullptr);
    while (avcodec_receive_frame(decoderCtx, frame) == 0) {
        if (!appendFrame(frame)) {
            cleanup();
            return false;
        }
        av_frame_unref(frame);
    }

    cleanup();
    return true;
}

static bool encodeMixedAudioToAac(
    const std::vector<float>& mixedPcm,
    int sampleRate,
    const std::string& outputPath,
    std::string& errorOut) {
    AVFormatContext* outFmt = nullptr;
    AVCodecContext* encoderCtx = nullptr;
    AVStream* outStream = nullptr;
    AVFrame* frame = nullptr;
    AVPacket* packet = nullptr;
    bool wroteHeader = false;

    auto cleanup = [&]() {
        if (frame) av_frame_free(&frame);
        if (packet) av_packet_free(&packet);
        if (encoderCtx) avcodec_free_context(&encoderCtx);
        if (outFmt) {
            if (wroteHeader) {
                av_write_trailer(outFmt);
            }
            if (!(outFmt->oformat->flags & AVFMT_NOFILE) && outFmt->pb) {
                avio_closep(&outFmt->pb);
            }
            avformat_free_context(outFmt);
        }
    };

    const AVCodec* encoder = avcodec_find_encoder(AV_CODEC_ID_AAC);
    if (!encoder) {
        errorOut = "AAC encoder unavailable";
        cleanup();
        return false;
    }

    AVSampleFormat targetFmt = AV_SAMPLE_FMT_FLTP;
    if (encoder->sample_fmts) {
        bool found = false;
        for (const AVSampleFormat* fmt = encoder->sample_fmts; *fmt != AV_SAMPLE_FMT_NONE; ++fmt) {
            if (*fmt == AV_SAMPLE_FMT_FLTP) {
                targetFmt = *fmt;
                found = true;
                break;
            }
        }
        if (!found) {
            targetFmt = encoder->sample_fmts[0];
        }
    }
    if (targetFmt != AV_SAMPLE_FMT_FLTP && targetFmt != AV_SAMPLE_FMT_FLT) {
        errorOut = "Unsupported AAC sample format";
        cleanup();
        return false;
    }

    if (avformat_alloc_output_context2(&outFmt, nullptr, nullptr, outputPath.c_str()) < 0 || !outFmt) {
        errorOut = "Failed to allocate audio output container";
        cleanup();
        return false;
    }
    outStream = avformat_new_stream(outFmt, nullptr);
    if (!outStream) {
        errorOut = "Failed to create audio stream";
        cleanup();
        return false;
    }

    encoderCtx = avcodec_alloc_context3(encoder);
    if (!encoderCtx) {
        errorOut = "Failed to allocate audio encoder context";
        cleanup();
        return false;
    }

    encoderCtx->sample_rate = sampleRate;
    encoderCtx->sample_fmt = targetFmt;
    encoderCtx->bit_rate = 192000;
    encoderCtx->time_base = AVRational{1, sampleRate};
    av_channel_layout_default(&encoderCtx->ch_layout, 2);
    outStream->time_base = encoderCtx->time_base;
    if (outFmt->oformat->flags & AVFMT_GLOBALHEADER) {
        encoderCtx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }
    if (avcodec_open2(encoderCtx, encoder, nullptr) < 0) {
        errorOut = "Failed to open audio encoder";
        cleanup();
        return false;
    }
    if (avcodec_parameters_from_context(outStream->codecpar, encoderCtx) < 0) {
        errorOut = "Failed to copy audio encoder parameters";
        cleanup();
        return false;
    }
    if (!(outFmt->oformat->flags & AVFMT_NOFILE) &&
        avio_open(&outFmt->pb, outputPath.c_str(), AVIO_FLAG_WRITE) < 0) {
        errorOut = "Failed to open audio output file";
        cleanup();
        return false;
    }
    if (avformat_write_header(outFmt, nullptr) < 0) {
        errorOut = "Failed to write audio output header";
        cleanup();
        return false;
    }
    wroteHeader = true;

    frame = av_frame_alloc();
    packet = av_packet_alloc();
    if (!frame || !packet) {
        errorOut = "Failed to allocate audio encode buffers";
        cleanup();
        return false;
    }

    frame->format = encoderCtx->sample_fmt;
    frame->nb_samples = encoderCtx->frame_size > 0 ? encoderCtx->frame_size : 1024;
    frame->sample_rate = encoderCtx->sample_rate;
    av_channel_layout_copy(&frame->ch_layout, &encoderCtx->ch_layout);
    if (av_frame_get_buffer(frame, 0) < 0) {
        errorOut = "Failed to allocate audio frame buffer";
        cleanup();
        return false;
    }

    const size_t totalSamples = mixedPcm.size() / 2;
    size_t cursor = 0;
    int64_t pts = 0;

    auto sendFrame = [&](AVFrame* encodeFrame) -> bool {
        if (avcodec_send_frame(encoderCtx, encodeFrame) < 0) {
            errorOut = "Failed to send audio frame to encoder";
            return false;
        }
        while (true) {
            const int ret = avcodec_receive_packet(encoderCtx, packet);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                break;
            }
            if (ret < 0) {
                errorOut = "Failed to receive audio packet";
                return false;
            }
            av_packet_rescale_ts(packet, encoderCtx->time_base, outStream->time_base);
            packet->stream_index = outStream->index;
            if (av_interleaved_write_frame(outFmt, packet) < 0) {
                av_packet_unref(packet);
                errorOut = "Failed to write audio packet";
                return false;
            }
            av_packet_unref(packet);
        }
        return true;
    };

    while (cursor < totalSamples) {
        if (av_frame_make_writable(frame) < 0) {
            errorOut = "Audio frame not writable";
            cleanup();
            return false;
        }
        const int targetSamples = encoderCtx->frame_size > 0 ? encoderCtx->frame_size : frame->nb_samples;
        const int samplesThisFrame = static_cast<int>(
            std::min<size_t>(targetSamples, totalSamples - cursor));
        frame->pts = pts;
        frame->nb_samples = targetSamples;

        if (encoderCtx->sample_fmt == AV_SAMPLE_FMT_FLTP) {
            float* left = reinterpret_cast<float*>(frame->data[0]);
            float* right = reinterpret_cast<float*>(frame->data[1]);
            std::fill(left, left + targetSamples, 0.0f);
            std::fill(right, right + targetSamples, 0.0f);
            for (int i = 0; i < samplesThisFrame; ++i) {
                left[i] = mixedPcm[(cursor + i) * 2];
                right[i] = mixedPcm[(cursor + i) * 2 + 1];
            }
        } else {
            float* interleaved = reinterpret_cast<float*>(frame->data[0]);
            std::fill(interleaved, interleaved + (targetSamples * 2), 0.0f);
            for (int i = 0; i < samplesThisFrame; ++i) {
                interleaved[i * 2] = mixedPcm[(cursor + i) * 2];
                interleaved[i * 2 + 1] = mixedPcm[(cursor + i) * 2 + 1];
            }
        }

        if (!sendFrame(frame)) {
            cleanup();
            return false;
        }
        cursor += static_cast<size_t>(samplesThisFrame);
        pts += targetSamples;
        frame->nb_samples = encoderCtx->frame_size > 0 ? encoderCtx->frame_size : 1024;
    }

    if (!sendFrame(nullptr)) {
        cleanup();
        return false;
    }

    cleanup();
    return true;
}

static float computeTimelineFadeMultiplier(
    int64_t clipStartTimeMs,
    int64_t clipDurationMs,
    int32_t fadeInMs,
    int32_t fadeOutMs,
    int64_t timelineMs) {
    const int64_t clampedDurationMs = std::max<int64_t>(1, clipDurationMs);
    const int64_t localTimelineMs = std::clamp<int64_t>(timelineMs - clipStartTimeMs, 0, clampedDurationMs - 1);
    const int64_t safeFadeInMs = std::clamp<int64_t>(fadeInMs, 0, clampedDurationMs);
    const int64_t safeFadeOutMs = std::clamp<int64_t>(fadeOutMs, 0, clampedDurationMs);

    float fadeGain = 1.0f;
    if (safeFadeInMs > 0 && localTimelineMs < safeFadeInMs) {
        fadeGain = std::min(
            fadeGain,
            static_cast<float>(localTimelineMs) / static_cast<float>(safeFadeInMs));
    }
    if (safeFadeOutMs > 0) {
        const int64_t fadeOutStartMs = std::max<int64_t>(0, clampedDurationMs - safeFadeOutMs);
        if (localTimelineMs >= fadeOutStartMs) {
            const int64_t remainingMs = std::max<int64_t>(0, (clampedDurationMs - 1) - localTimelineMs);
            fadeGain = std::min(
                fadeGain,
                static_cast<float>(remainingMs) / static_cast<float>(safeFadeOutMs));
        }
    }
    return std::clamp(fadeGain, 0.0f, 1.0f);
}

static bool mixAudioClipsToAac(
    const std::vector<AudioExportClip>& audioClips,
    const std::string& outputPath,
    int64_t totalDurationMs,
    std::string& errorOut) {
    constexpr int kOutputSampleRate = 48000;
    constexpr int kOutputChannels = 2;

    if (totalDurationMs <= 0) {
        errorOut = "Invalid audio duration";
        return false;
    }

    const int64_t totalSamples = std::max<int64_t>(
        1,
        (totalDurationMs * kOutputSampleRate + 999) / 1000);
    if (totalSamples > 100 * 60 * kOutputSampleRate) { // 100 minutes limit for safety
        errorOut = "Timeline duration too long for export";
        return false;
    }
    std::vector<float> mixedPcm;
    try {
        mixedPcm.resize(static_cast<size_t>(totalSamples) * kOutputChannels, 0.0f);
    } catch (const std::bad_alloc& e) {
        errorOut = "Failed to allocate audio buffer (out of memory)";
        return false;
    }
    bool mixedAny = false;

    LOGI("[Export] mixing %zu audio clips to %s duration=%lldms",
         audioClips.size(),
         outputPath.c_str(),
         static_cast<long long>(totalDurationMs));

    for (const auto& clip : audioClips) {
        if (clip.path.empty() || clip.durationMs <= 0 || clip.volume <= 0.0001f) {
            continue;
        }
        std::vector<float> clipPcm;
        std::string clipError;
        if (!decodeAudioClipToStereoFloat(clip.path, kOutputSampleRate, clipPcm, clipError)) {
            LOGW("[Export] skipping audio clip path=%s reason=%s", clip.path.c_str(), clipError.c_str());
            continue;
        }
        const int64_t sourceSampleCount = static_cast<int64_t>(clipPcm.size() / kOutputChannels);
        if (sourceSampleCount <= 0) {
            continue;
        }
        const int64_t writeStartSample = std::max<int64_t>(0, (clip.startTimeMs * kOutputSampleRate) / 1000);
        if (writeStartSample >= totalSamples) {
            continue;
        }
        const int64_t writeSampleCount = std::min<int64_t>(
            totalSamples - writeStartSample,
            std::max<int64_t>(1, (clip.durationMs * kOutputSampleRate + 999) / 1000));
        for (int64_t outSampleOffset = 0; outSampleOffset < writeSampleCount; ++outSampleOffset) {
            const int64_t timelineMs = clip.startTimeMs + ((outSampleOffset * 1000) / kOutputSampleRate);
            const int64_t sourceMs = mapAudioClipTimelineToSourceMs(clip, timelineMs);
            const int64_t sourceSample =
                std::clamp<int64_t>((sourceMs * kOutputSampleRate) / 1000, 0, sourceSampleCount - 1);
            const float fadeGain = computeTimelineFadeMultiplier(
                clip.startTimeMs,
                clip.durationMs,
                clip.fadeInMs,
                clip.fadeOutMs,
                timelineMs);
            const int64_t localTimelineMs = std::clamp<int64_t>(timelineMs - clip.startTimeMs, 0, clip.durationMs - 1);
            const float envelopeGain = sampleAudioGainEnvelope(clip.audioGainKeyframes, localTimelineMs);
            const float effectiveGain = clip.volume * envelopeGain * fadeGain;
            if (effectiveGain <= 0.0001f) {
                continue;
            }
            const size_t dstIndex = static_cast<size_t>(writeStartSample + outSampleOffset) * kOutputChannels;
            const size_t srcIndex = static_cast<size_t>(sourceSample) * kOutputChannels;
            mixedPcm[dstIndex] += clipPcm[srcIndex] * effectiveGain;
            mixedPcm[dstIndex + 1] += clipPcm[srcIndex + 1] * effectiveGain;
            mixedAny = true;
        }
    }

    if (!mixedAny) {
        LOGW("[Export] No audio clips mixed (silent output)");
    }

    float peakSample = 0.0f;
    for (float sample : mixedPcm) {
        peakSample = std::max(peakSample, std::fabs(sample));
    }
    constexpr float kTargetPeak = 0.90f;
    if (peakSample > kTargetPeak) {
        const float scale = kTargetPeak / peakSample;
        LOGI("[Export] applying audio headroom scale=%.3f peak=%.3f", scale, peakSample);
        for (float& sample : mixedPcm) {
            sample *= scale;
        }
    }
    if (peakSample > 0.72f) {
        const float limiterDrive = 1.15f;
        const float limiterNorm = std::tanh(limiterDrive);
        for (float& sample : mixedPcm) {
            sample = std::tanh(sample * limiterDrive) / limiterNorm;
        }
    }
    for (float& sample : mixedPcm) {
        sample = std::clamp(sample, -1.0f, 1.0f);
    }
    const bool encoded = encodeMixedAudioToAac(mixedPcm, kOutputSampleRate, outputPath, errorOut);
    if (encoded) {
        LOGI("[Export] mixed audio encoded: %s", outputPath.c_str());
    }
    return encoded;
}

static bool muxVideoAndAudioToMp4(
    const std::string& videoPath,
    const std::string& audioPath,
    const std::string& outputPath,
    std::string& errorOut) {
    AVFormatContext* videoFmt = nullptr;
    AVFormatContext* audioFmt = nullptr;
    AVFormatContext* outFmt = nullptr;
    AVPacket* videoPacket = nullptr;
    AVPacket* audioPacket = nullptr;
    bool wroteHeader = false;

    LOGI("[Export] muxing video=%s audio=%s -> %s",
         videoPath.c_str(),
         audioPath.c_str(),
         outputPath.c_str());

    auto cleanup = [&]() {
        if (videoPacket) av_packet_free(&videoPacket);
        if (audioPacket) av_packet_free(&audioPacket);
        if (videoFmt) avformat_close_input(&videoFmt);
        if (audioFmt) avformat_close_input(&audioFmt);
        if (outFmt) {
            if (wroteHeader) {
                av_write_trailer(outFmt);
            }
            if (!(outFmt->oformat->flags & AVFMT_NOFILE) && outFmt->pb) {
                avio_closep(&outFmt->pb);
            }
            avformat_free_context(outFmt);
        }
    };

    if (avformat_open_input(&videoFmt, videoPath.c_str(), nullptr, nullptr) < 0 ||
        avformat_find_stream_info(videoFmt, nullptr) < 0) {
        errorOut = "Failed to open rendered video";
        cleanup();
        return false;
    }
    if (avformat_open_input(&audioFmt, audioPath.c_str(), nullptr, nullptr) < 0 ||
        avformat_find_stream_info(audioFmt, nullptr) < 0) {
        errorOut = "Failed to open rendered audio";
        cleanup();
        return false;
    }

    int videoStreamIndex = -1;
    int audioStreamIndex = -1;
    for (unsigned int i = 0; i < videoFmt->nb_streams; ++i) {
        if (videoFmt->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            videoStreamIndex = static_cast<int>(i);
            break;
        }
    }
    for (unsigned int i = 0; i < audioFmt->nb_streams; ++i) {
        if (audioFmt->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            audioStreamIndex = static_cast<int>(i);
            break;
        }
    }
    if (videoStreamIndex < 0 || audioStreamIndex < 0) {
        errorOut = "Required audio/video streams not found";
        cleanup();
        return false;
    }

    if (avformat_alloc_output_context2(&outFmt, nullptr, nullptr, outputPath.c_str()) < 0 || !outFmt) {
        errorOut = "Failed to allocate final output container";
        cleanup();
        return false;
    }

    AVStream* outVideo = avformat_new_stream(outFmt, nullptr);
    AVStream* outAudio = avformat_new_stream(outFmt, nullptr);
    if (!outVideo || !outAudio) {
        errorOut = "Failed to create final output streams";
        cleanup();
        return false;
    }
    if (avcodec_parameters_copy(outVideo->codecpar, videoFmt->streams[videoStreamIndex]->codecpar) < 0 ||
        avcodec_parameters_copy(outAudio->codecpar, audioFmt->streams[audioStreamIndex]->codecpar) < 0) {
        errorOut = "Failed to copy final output stream parameters";
        cleanup();
        return false;
    }
    outVideo->codecpar->codec_tag = 0;
    outAudio->codecpar->codec_tag = 0;
    outVideo->time_base = videoFmt->streams[videoStreamIndex]->time_base;
    outAudio->time_base = audioFmt->streams[audioStreamIndex]->time_base;

    if (!(outFmt->oformat->flags & AVFMT_NOFILE) &&
        avio_open(&outFmt->pb, outputPath.c_str(), AVIO_FLAG_WRITE) < 0) {
        errorOut = "Failed to open final output file";
        cleanup();
        return false;
    }
    if (avformat_write_header(outFmt, nullptr) < 0) {
        errorOut = "Failed to write final output header";
        cleanup();
        return false;
    }
    wroteHeader = true;

    videoPacket = av_packet_alloc();
    audioPacket = av_packet_alloc();
    if (!videoPacket || !audioPacket) {
        errorOut = "Failed to allocate mux packets";
        cleanup();
        return false;
    }

    auto readNextStreamPacket = [](AVFormatContext* fmt, int streamIndex, AVPacket* pkt) -> bool {
        while (av_read_frame(fmt, pkt) >= 0) {
            if (pkt->stream_index == streamIndex) {
                return true;
            }
            av_packet_unref(pkt);
        }
        return false;
    };

    bool hasVideo = readNextStreamPacket(videoFmt, videoStreamIndex, videoPacket);
    bool hasAudio = readNextStreamPacket(audioFmt, audioStreamIndex, audioPacket);
    while (hasVideo || hasAudio) {
        const bool writeVideo = !hasAudio ||
            (hasVideo &&
             av_compare_ts(
                 videoPacket->pts,
                 videoFmt->streams[videoStreamIndex]->time_base,
                 audioPacket->pts,
                 audioFmt->streams[audioStreamIndex]->time_base) <= 0);
        AVPacket* pkt = writeVideo ? videoPacket : audioPacket;
        AVStream* inStream = writeVideo ? videoFmt->streams[videoStreamIndex] : audioFmt->streams[audioStreamIndex];
        AVStream* outStream = writeVideo ? outVideo : outAudio;
        av_packet_rescale_ts(pkt, inStream->time_base, outStream->time_base);
        pkt->stream_index = outStream->index;
        pkt->pos = -1;
        if (av_interleaved_write_frame(outFmt, pkt) < 0) {
            errorOut = "Failed to mux final output";
            cleanup();
            return false;
        }
        av_packet_unref(pkt);
        if (writeVideo) {
            hasVideo = readNextStreamPacket(videoFmt, videoStreamIndex, videoPacket);
        } else {
            hasAudio = readNextStreamPacket(audioFmt, audioStreamIndex, audioPacket);
        }
    }

    cleanup();
    LOGI("[Export] mux complete: %s", outputPath.c_str());
    return true;
}

static int64_t computeAudioExportDurationMs(
    const std::vector<TimelineClipExportSpec>& clipSpecs,
    const std::vector<AudioExportClip>& audioClips) {
    int64_t durationMs = computeTimelineExportDurationMs(clipSpecs);
    for (const auto& clip : audioClips) {
        if (clip.path.empty() || clip.durationMs <= 0) continue;
        durationMs = std::max(durationMs, clip.startTimeMs + clip.durationMs);
    }
    return durationMs;
}

static std::vector<AudioExportClip> buildExportAudioClips(
    const std::vector<TimelineClipExportSpec>& clipSpecs) {
    std::vector<AudioExportClip> audioClips;
    audioClips.reserve(g_audioExportClips.size() + clipSpecs.size());
    audioClips.insert(audioClips.end(), g_audioExportClips.begin(), g_audioExportClips.end());
    for (const auto& spec : clipSpecs) {
        if (!spec.enabled ||
            spec.path.empty() ||
            spec.volumeGain <= 0.0001f ||
            isStillImagePath(spec.path)) {
            continue;
        }
        AudioExportClip clip;
        clip.path = spec.path;
        clip.startTimeMs = spec.startTimeMs;
        clip.durationMs = spec.durationMs;
        clip.volume = spec.volumeGain;
        clip.fadeInMs = spec.fadeInMs;
        clip.fadeOutMs = spec.fadeOutMs;
        clip.audioGainKeyframes = spec.audioGainKeyframes;
        clip.sourceInMs = spec.trimInMs;
        clip.sourceOutMs = spec.trimOutMs;
        clip.playbackSpeed = spec.playbackSpeed;
        clip.reversePlayback = spec.reversePlayback;
        clip.freezeFrameEnabled = spec.freezeFrameEnabled;
        clip.freezeFrameTimeMs = spec.freezeFrameTimeMs;
        clip.freezeFrameDurationMs = spec.freezeFrameDurationMs;
        clip.curveSpeedProfile = spec.curveSpeedProfile;
        clip.curveSpeedStrength = spec.curveSpeedStrength;
        audioClips.push_back(std::move(clip));
    }
    return audioClips;
}

static bool renderTimelineWithMixedAudioToMp4(
    const std::vector<std::string>& inputPaths,
    const std::vector<TimelineClipExportSpec>& renderClipSpecs,
    const std::vector<TimelineClipExportSpec>& audioSourceSpecs,
    const std::string& outputPath,
    int width,
    int height,
    int fps,
    const std::function<bool(int)>& onProgress,
    std::string& errorOut) {
    const std::string videoOnlyPath = outputPath + ".video_only.mp4";
    const std::string audioOnlyPath = outputPath + ".mixed_audio.m4a";
    std::remove(videoOnlyPath.c_str());
    std::remove(audioOnlyPath.c_str());

    const bool videoOk = exportTimelineClipsToMp4(
        inputPaths,
        videoOnlyPath,
        [&](int progress) {
            if (onProgress) {
                return onProgress(progress * 80 / 100);
            }
            return true;
        },
        errorOut,
        width,
        height,
        fps,
        renderClipSpecs.empty() ? nullptr : &renderClipSpecs);
    if (!videoOk) {
        std::remove(videoOnlyPath.c_str());
        return false;
    }

    std::vector<AudioExportClip> audioClips;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        audioClips = buildExportAudioClips(audioSourceSpecs);
    }
    if (audioClips.empty()) {
        if (std::rename(videoOnlyPath.c_str(), outputPath.c_str()) != 0) {
            errorOut = "Failed to move rendered video output";
            std::remove(videoOnlyPath.c_str());
            return false;
        }
        if (onProgress && !onProgress(100)) {
            errorOut = "Export cancelled";
            std::remove(outputPath.c_str());
            return false;
        }
        return true;
    }

    const int64_t audioDurationMs = computeAudioExportDurationMs(audioSourceSpecs, audioClips);
    if (!mixAudioClipsToAac(audioClips, audioOnlyPath, audioDurationMs, errorOut)) {
        LOGE("[Export] audio mix failed: %s", errorOut.c_str());
        std::remove(videoOnlyPath.c_str());
        return false;
    }
    if (onProgress && !onProgress(90)) {
        errorOut = "Export cancelled";
        std::remove(videoOnlyPath.c_str());
        std::remove(audioOnlyPath.c_str());
        return false;
    }
    if (!muxVideoAndAudioToMp4(videoOnlyPath, audioOnlyPath, outputPath, errorOut)) {
        std::remove(videoOnlyPath.c_str());
        std::remove(audioOnlyPath.c_str());
        return false;
    }
    std::remove(videoOnlyPath.c_str());
    std::remove(audioOnlyPath.c_str());
    if (onProgress && !onProgress(100)) {
        errorOut = "Export cancelled";
        std::remove(outputPath.c_str());
        return false;
    }
    return true;
}
#endif

JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeStartExport(
    JNIEnv* env, jobject thiz,
    jstring outputPathJava,
    jint width, jint height, jint fps) {
    if (g_isExporting.load(std::memory_order_acquire)) {
        LOGW("[Export] Export already in progress");
        return;
    }

    const char* outputPathC = env->GetStringUTFChars(outputPathJava, nullptr);
    if (!outputPathC) {
        LOGE("[Export] Failed to convert output path");
        return;
    }
    std::string outputPath(outputPathC);
    env->ReleaseStringUTFChars(outputPathJava, outputPathC);

    std::vector<std::string> inputPaths;
    std::vector<TimelineClipExportSpec> clipSpecs;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_preview) {
            LOGE("[Export] Preview not initialized");
            return;
        }
        auto timeline = g_preview->getTimeline();
        if (!timeline || timeline->clips().empty() || !timeline->clips().front()) {
            LOGE("[Export] No timeline clip available");
            return;
        }
        clipSpecs = collectTimelineClipExportSpecsLocked();
        for (const auto& clip : timeline->clips()) {
            if (!clip) continue;
            inputPaths.push_back(clip->getMediaPath());
            cacheClipExportSource(
                static_cast<int>(clip->getId()),
                clip->getMediaPath(),
                clip->getDuration());
        }
    }

    g_exportProgress.store(0, std::memory_order_release);
    g_exportCancelled.store(false, std::memory_order_release);
    g_isExporting.store(true, std::memory_order_release);
    g_isRenderingActive.store(false, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_lastExportError.clear();
    }

    LOGI("[Export] started clips=%zu output=%s requested=%dx%d@%dfps",
         inputPaths.size(), outputPath.c_str(), width, height, fps);

    if (g_exportThread.joinable()) {
        g_exportThread.join();
    }

    g_exportThread = std::thread([inputPaths, clipSpecs, outputPath, width, height, fps]() {
        std::string exportError;
        const bool success = renderTimelineWithMixedAudioToMp4(
            inputPaths,
            clipSpecs,
            clipSpecs,
            outputPath,
            width,
            height,
            fps,
            [](int progress) {
                if (g_exportCancelled.load(std::memory_order_acquire)) {
                    LOGI("[Export] cancelled");
                    return false;
                }
                g_exportProgress.store(progress, std::memory_order_release);
                callExportCallbackProgress(progress);
                return true;
            },
            exportError);
        if (!success) {
            std::lock_guard<std::mutex> lock(g_mutex);
            g_lastExportError = exportError;
            LOGE("[Export] render/mix failed: %s", exportError.c_str());
            callExportCallbackCompleted(false, exportError);
        } else {
            g_exportProgress.store(100, std::memory_order_release);
            LOGI("[Export] completed output=%s", outputPath.c_str());
            callExportCallbackCompleted(true, "");
        }
        g_isExporting.store(false, std::memory_order_release);
    });
    (void)thiz;
}

/**
 * Cancel in-progress export.
 * 
 * @param env JNI environment
 * @param thiz Java object reference
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeCancelExport(
    JNIEnv* env, jobject thiz) {
    if (!g_isExporting.load(std::memory_order_acquire)) {
        LOGW("[Export] No export in progress");
        return;
    }
    
    g_exportCancelled.store(true, std::memory_order_release);
    LOGI("[Export] cancel requested");
    (void)env;
    (void)thiz;
}

/**
 * Get export progress (0-100, or -1 if no export).
 * 
 * @param env JNI environment
 * @param thiz Java object reference
 * @return Progress percentage (0-100), or -1 if not exporting
 */
JNIEXPORT jint JNICALL
Java_com_video_engine_VideoPreviewView_nativeGetExportProgress(
    JNIEnv* env, jobject thiz) {
    if (!g_isExporting.load(std::memory_order_acquire)) {
        return -1;
    }
    
    return g_exportProgress.load(std::memory_order_acquire);
    (void)env;
    (void)thiz;
}

JNIEXPORT jstring JNICALL
Java_com_video_engine_VideoPreviewView_nativeGetLastExportError(
    JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    (void)thiz;
    return env->NewStringUTF(g_lastExportError.c_str());
}

JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeSetExportCallback(
    JNIEnv* env, jobject thiz, jobject callback) {
    std::lock_guard<std::mutex> lock(g_mutex);
    // Clear previous callback
    if (g_exportCallback) {
        env->DeleteGlobalRef(g_exportCallback);
        g_exportCallback = nullptr;
        g_onExportProgressMethod = nullptr;
        g_onExportCompletedMethod = nullptr;
    }
    // Set new callback
    if (callback) {
        g_exportCallback = env->NewGlobalRef(callback);
        jclass callbackClass = env->GetObjectClass(callback);
        g_onExportProgressMethod = env->GetMethodID(callbackClass, "onExportProgress", "(I)V");
        g_onExportCompletedMethod = env->GetMethodID(callbackClass, "onExportCompleted", "(ZLjava/lang/String;)V");
    }
    (void)thiz;
}

/**
 * Set GPU effects parameters for a clip.
 * 
 * Updates brightness, contrast, and saturation values that are applied
 * during fragment shader rendering. Changes take effect immediately
 * on the next frame render (no latency, GPU-only updates).
 * 
 * @param env JNI environment
 * @param thiz Java object reference
 * @param clipId Clip identifier
 * @param brightness Value -1.0 to +1.0 (default 0.0)
 * @param contrast Value 0.0 to 2.0 (default 1.0)
 * @param saturation Value 0.0 to 2.0 (default 1.0)
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeSetClipEffects(
    JNIEnv* env, jobject thiz,
    jint clipId,
    jfloat brightness,
    jfloat contrast,
    jfloat saturation) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_preview) {
        LOGE("[Effects] Preview not initialized");
        return;
    }
    
    // Clamp values to valid ranges
    float clampedBrightness = std::clamp(brightness, -1.0f, 1.0f);
    float clampedContrast = std::clamp(contrast, 0.0f, 2.0f);
    float clampedSaturation = std::clamp(saturation, 0.0f, 2.0f);
    
    LOGI("[Effects] clip=%d brightness=%.2f contrast=%.2f saturation=%.2f",
         clipId, clampedBrightness, clampedContrast, clampedSaturation);

    auto timeline = g_preview->getTimeline();
    if (!timeline) {
        LOGW("[Effects] Timeline not available");
        return;
    }

    bool updated = false;
    for (const auto& clip : timeline->clips()) {
        if (!clip) continue;
        if (clip->getId() != static_cast<uint32_t>(clipId)) continue;
        clip->setEffectBrightness(clampedBrightness);
        clip->setEffectContrast(clampedContrast);
        clip->setEffectSaturation(clampedSaturation);
        clip->setEffectsEnabled(true);
        updated = true;
        break;
    }

    if (!updated) {
        LOGW("[Effects] clip=%d not found in timeline", clipId);
        return;
    }

    if (g_eglDisplay != EGL_NO_DISPLAY && g_eglContext != EGL_NO_CONTEXT &&
        g_eglSurface != EGL_NO_SURFACE) {
        if (eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext)) {
            const long long currentTime = g_currentTimeMs.load(std::memory_order_acquire);
            renderTextOverlays(currentTime);
            if (!eglSwapBuffers(g_eglDisplay, g_eglSurface)) {
                LOGW("[Effects] eglSwapBuffers failed: 0x%x", eglGetError());
            }
        }
    }
    // Post scrub to render thread so effects are visible immediately
    g_pendingScrubMs.store(g_currentTimeMs.load(std::memory_order_acquire), std::memory_order_release);
}

/**
 * Move a clip to a new layer index (layer index determines z-order).
 * Triggers render graph rebuild.
 * 
 * @param env JNI environment
 * @param thiz Java object reference
 * @param clipId Clip identifier
 * @param newLayerIndex New layer index (0 = bottom, higher = top)
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeMoveLayer(
    JNIEnv* env, jobject thiz,
    jint clipId,
    jint newLayerIndex) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_preview) {
        LOGE("[Layers] Preview not initialized");
        return;
    }

    auto timeline = g_preview->getTimeline();
    if (!timeline) {
        LOGE("[Layers] Timeline not initialized");
        return;
    }

    bool updated = false;
    for (const auto& clip : timeline->clips()) {
        if (!clip) continue;
        if (clip->getId() != static_cast<uint32_t>(clipId)) continue;
        clip->setTrackZOrder(std::max(0, static_cast<int>(newLayerIndex)));
        updated = true;
        break;
    }

    if (!updated) {
        LOGW("[Layers] move clip=%d missing", clipId);
        return;
    }

    LOGI("[Layers] move clip=%d to layer=%d", clipId, newLayerIndex);

    const int64_t currentTime = g_currentTimeMs.load(std::memory_order_acquire);
    if (g_isRenderingActive.load(std::memory_order_acquire)) {
        g_pendingScrubMs.store(static_cast<long long>(currentTime), std::memory_order_release);
    } else {
        g_pendingScrubMs.store(-1, std::memory_order_release);
        g_preview->scrubToTimelineTime(currentTime);
        const int64_t scrubTime = g_preview->getPlaybackTimelineTimeMs();
        g_currentTimeMs.store(scrubTime, std::memory_order_release);
    }
}

/**
 * Toggle visibility of a clip/layer.
 * Clips with visibility=false will be skipped during rendering.
 * 
 * @param env JNI environment
 * @param thiz Java object reference
 * @param clipId Clip identifier
 * @param enabled true = visible, false = hidden
 */
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeToggleLayerVisibility(
    JNIEnv* env, jobject thiz,
    jint clipId,
    jboolean enabled) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_preview) {
        LOGE("[Layers] Preview not initialized");
        return;
    }

    auto timeline = g_preview->getTimeline();
    if (!timeline) {
        LOGE("[Layers] Timeline not initialized");
        return;
    }

    bool updated = false;
    for (const auto& clip : timeline->clips()) {
        if (!clip) continue;
        if (clip->getId() != static_cast<uint32_t>(clipId)) continue;
        clip->setEnabled(enabled == JNI_TRUE);
        updated = true;
        break;
    }

    if (!updated) {
        LOGW("[Layers] visibility clip=%d missing", clipId);
        return;
    }

    LOGI("[Layers] clip=%d visibility=%s", clipId, enabled ? "ON" : "OFF");

    const int64_t currentTime = g_currentTimeMs.load(std::memory_order_acquire);
    if (g_isRenderingActive.load(std::memory_order_acquire)) {
        g_pendingScrubMs.store(static_cast<long long>(currentTime), std::memory_order_release);
    } else {
        g_pendingScrubMs.store(-1, std::memory_order_release);
        g_preview->scrubToTimelineTime(currentTime);
        const int64_t scrubTime = g_preview->getPlaybackTimelineTimeMs();
        g_currentTimeMs.store(scrubTime, std::memory_order_release);
    }
}

/**
 * Export timeline to video file.
 * Renders all clips with text overlays at specified resolution/fps.
 * Result: WYSIWYG (what you see is what you get) - export matches preview exactly.
 * 
 * @param env JNI environment
 * @param thiz Java object reference
 * @param outputPath Output file path (e.g., "/sdcard/export.mp4")
 * @param width Output video width in pixels
 * @param height Output video height in pixels
 * @param fps Frames per second
 * @param bitrateMbps Bitrate in Mbps (converted to kbps internally)
 * @return true if export started successfully, false on error
 */
JNIEXPORT jboolean JNICALL
Java_com_video_engine_VideoPreviewView_nativeExportVideo(
    JNIEnv* env, jobject thiz,
    jstring outputPath,
    jint width,
    jint height,
    jint fps,
    jint bitrateMbps) {
    try {
        const char* pathCStr = env->GetStringUTFChars(outputPath, nullptr);
        std::string outputPathStr(pathCStr);
        env->ReleaseStringUTFChars(outputPath, pathCStr);

        std::vector<std::string> inputPaths;
        std::vector<TimelineClipExportSpec> clipSpecs;
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            if (!g_preview) {
                LOGE("[Export] Preview not initialized");
                return JNI_FALSE;
            }
            auto timeline = g_preview->getTimeline();
            if (timeline && !timeline->clips().empty() && timeline->clips().front()) {
                clipSpecs = collectTimelineClipExportSpecsLocked();
                for (const auto& clip : timeline->clips()) {
                    if (clip) {
                        inputPaths.push_back(clip->getMediaPath());
                        cacheClipExportSource(
                            static_cast<int>(clip->getId()),
                            clip->getMediaPath(),
                            clip->getDuration());
                    }
                }
            }
            if (inputPaths.empty()) {
                for (const auto& entry : g_timelineClipPaths) {
                    if (!entry.path.empty()) inputPaths.push_back(entry.path);
                }
            }
            if (inputPaths.empty()) {
                LOGE("[Export] No timeline clip available");
                return JNI_FALSE;
            }
        }

        LOGI("[Export] Starting remux export clips=%zu output=%s requested=%dx%d@%dfps bitrate=%dMbps",
             inputPaths.size(), outputPathStr.c_str(), width, height, fps, bitrateMbps);

        // Cancel any running async export thread and wait for it
        g_exportCancelled.store(true, std::memory_order_release);
        if (g_exportThread.joinable()) {
            g_exportThread.join();
        }

        g_exportProgress.store(0, std::memory_order_release);
        g_exportCancelled.store(false, std::memory_order_release);
        g_isExporting.store(true, std::memory_order_release);
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            g_lastExportError.clear();
        }

        std::string exportError;
        // Fast path: only a single plain main-video clip with no timeline edits or overlays.
        bool canFastRemux = true;
        int enabledMainVideoClips = 0;
        for (const auto& s : clipSpecs) {
            if (!s.enabled || s.path.empty()) {
                continue;
            }
            if (s.trackRole != Clip::TrackRole::MainVideo) {
                canFastRemux = false;
                LOGI("[Export] canFastRemux=false: clip=%d trackRole=%d", s.clipId, static_cast<int>(s.trackRole));
                break;
            }
            ++enabledMainVideoClips;
            const bool hasTrim =
                s.trimInMs > 0 ||
                (s.sourceDurationMs > 0 && s.trimOutMs < s.sourceDurationMs);
            if (enabledMainVideoClips > 1 ||
                isStillImagePath(s.path) ||
                s.startTimeMs != 0 ||
                hasTrim ||
                s.reversePlayback || s.freezeFrameEnabled ||
                std::abs(s.playbackSpeed - 1.0f) > 0.01f ||
                s.effectsEnabled || s.chromaEnabled) {
                canFastRemux = false;
                LOGI("[Export] canFastRemux=false: clip=%d mainCount=%d image=%d start=%lld trim=%d reverse=%d freeze=%d speed=%.2f effects=%d chroma=%d",
                     s.clipId,
                     enabledMainVideoClips,
                     isStillImagePath(s.path),
                     static_cast<long long>(s.startTimeMs),
                     hasTrim,
                     s.reversePlayback,
                     s.freezeFrameEnabled,
                     s.playbackSpeed,
                     s.effectsEnabled,
                     s.chromaEnabled);
                break;
            }
        }
        if (canFastRemux && enabledMainVideoClips != 1) {
            canFastRemux = false;
            LOGI("[Export] canFastRemux=false: enabledMainVideoClips=%d", enabledMainVideoClips);
        }
        // Also disable fast remux if there are text overlays or supplemental audio clips to mix.
        if (canFastRemux && !g_overlayCpuBitmaps.empty()) {
            canFastRemux = false;
            LOGI("[Export] canFastRemux=false: has %zu text overlays", g_overlayCpuBitmaps.size());
        }
        if (canFastRemux && !g_audioExportClips.empty()) {
            canFastRemux = false;
            LOGI("[Export] canFastRemux=false: has %zu audio clips", g_audioExportClips.size());
        }
        LOGI("[Export] canFastRemux=%d clipSpecs=%zu", canFastRemux, clipSpecs.size());

        const std::vector<TimelineClipExportSpec> exportSpecs =
            (canFastRemux || clipSpecs.empty()) ? std::vector<TimelineClipExportSpec>{} : clipSpecs;
        const bool success = renderTimelineWithMixedAudioToMp4(
            inputPaths,
            exportSpecs,
            clipSpecs,
            outputPathStr,
            width,
            height,
            fps,
            [](int progress) {
                if (g_exportCancelled.load(std::memory_order_acquire)) {
                    return false;
                }
                g_exportProgress.store(progress, std::memory_order_release);
                return true;
            },
            exportError);

        if (!success) {
            {
                std::lock_guard<std::mutex> lock(g_mutex);
                g_lastExportError = exportError.empty()
                    ? (g_exportCancelled.load(std::memory_order_acquire) ? "Export cancelled" : "Export failed")
                    : exportError;
            }
            LOGE("[Export] Export failed: %s", exportError.c_str());
            g_isExporting.store(false, std::memory_order_release);
            return JNI_FALSE;
        }

        g_exportProgress.store(100, std::memory_order_release);
        g_isExporting.store(false, std::memory_order_release);
        LOGI("[Export] Export complete: %s", outputPathStr.c_str());
        return JNI_TRUE;
    } catch (const std::exception& e) {
        g_isExporting.store(false, std::memory_order_release);
        LOGE("[Export] Exception: %s", e.what());
        return JNI_FALSE;
    }
    (void)thiz;
}

}  // extern "C"

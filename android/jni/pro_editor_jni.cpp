#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#define PRO_LOG_TAG "ProEditorJNI"
#define PRO_LOGI(...) __android_log_print(ANDROID_LOG_INFO, PRO_LOG_TAG, __VA_ARGS__)
#define PRO_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, PRO_LOG_TAG, __VA_ARGS__)
#else
#define PRO_LOGI(...) do {} while (0)
#define PRO_LOGE(...) do {} while (0)
#define ANativeWindow void
#define ANativeWindow_fromSurface(env, surface) nullptr
#define ANativeWindow_release(window) do { (void)window; } while (0)
#endif

#include <algorithm>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <sstream>

#include "engine/preview_controller.h"
#include "core/timeline.h"

extern std::mutex g_mutex;
extern ANativeWindow* g_nativeWindow;
extern int g_surfaceWidth;
extern int g_surfaceHeight;
extern std::unique_ptr<VideoEngine::PreviewController> g_preview;
extern std::atomic<long long> g_currentTimeMs;
extern std::atomic<bool> g_isRenderingActive;

static bool ensurePreviewController() {
    if (g_preview) {
        return true;
    }
    try {
        g_preview = std::make_unique<VideoEngine::PreviewController>();
        return true;
    } catch (...) {
        PRO_LOGE("Failed to create PreviewController");
        return false;
    }
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_video_engine_pro_jni_NativeEngineBridge_onSeek(
    JNIEnv* env, jobject thiz, jlong timeMs) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const auto safeTimeMs = std::max<int64_t>(0, static_cast<int64_t>(timeMs));
    g_currentTimeMs.store(safeTimeMs, std::memory_order_release);
    if (!ensurePreviewController()) {
        return;
    }
    g_preview->scrubToTimelineTime(safeTimeMs);
}

JNIEXPORT void JNICALL
Java_com_video_engine_pro_jni_NativeEngineBridge_onClipMoved(
    JNIEnv* env, jobject thiz, jstring clipIdJ, jlong newTimeMs) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!ensurePreviewController()) {
        return;
    }

    auto timeline = g_preview->getTimeline();
    if (!timeline) {
        return;
    }

    const char* rawClipId = env->GetStringUTFChars(clipIdJ, nullptr);
    if (!rawClipId) {
        return;
    }
    const std::string clipId(rawClipId);
    env->ReleaseStringUTFChars(clipIdJ, rawClipId);

    for (const auto& clip : timeline->clips()) {
        if (!clip) continue;
        if (std::to_string(clip->getId()) != clipId && clip->getMediaPath() != clipId) continue;
        clip->setTimelinePosition(std::max<int64_t>(0, static_cast<int64_t>(newTimeMs)), clip->getDuration());
        PRO_LOGI("Moved clip=%s newTime=%lld", clipId.c_str(), static_cast<long long>(newTimeMs));
        return;
    }
}

JNIEXPORT void JNICALL
Java_com_video_engine_pro_jni_NativeEngineBridge_onRenderFrame(
    JNIEnv* env, jobject thiz, jobject surface) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!surface || !ensurePreviewController()) {
        return;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        PRO_LOGE("ANativeWindow_fromSurface failed");
        return;
    }

    if (g_nativeWindow != window) {
        if (g_nativeWindow) {
            ANativeWindow_release(g_nativeWindow);
        }
        g_nativeWindow = window;
        if (!g_preview->attachSurface(g_nativeWindow)) {
            PRO_LOGE("attachSurface failed: %s", g_preview->getLastError());
            return;
        }
    } else {
        ANativeWindow_release(window);
    }

    g_preview->scrubToTimelineTime(g_currentTimeMs.load(std::memory_order_acquire));
}

JNIEXPORT void JNICALL
Java_com_video_engine_pro_jni_NativeEngineBridge_onSurfaceSizeChanged(
    JNIEnv* env, jobject thiz, jint width, jint height) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_surfaceWidth = width;
    g_surfaceHeight = height;
}

JNIEXPORT void JNICALL
Java_com_video_engine_pro_jni_NativeEngineBridge_onSurfaceDestroyed(
    JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_isRenderingActive.store(false, std::memory_order_release);
    if (g_preview) {
        g_preview->detachSurface();
    }
    if (g_nativeWindow) {
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_video_engine_pro_jni_NativeEngineBridge_onPlaybackStateChanged(
    JNIEnv* env, jobject thiz, jboolean isPlaying) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!ensurePreviewController()) {
        return;
    }

    if (isPlaying == JNI_TRUE) {
        const auto startMs = std::max<int64_t>(0, g_currentTimeMs.load(std::memory_order_acquire));
        if (g_preview->playFrom(startMs)) {
            g_isRenderingActive.store(true, std::memory_order_release);
        }
    } else {
        g_isRenderingActive.store(false, std::memory_order_release);
        g_preview->stop();
    }
}

JNIEXPORT jstring JNICALL
Java_com_video_engine_pro_jni_NativeEngineBridge_addClip(
    JNIEnv* env, jobject thiz, jstring sourcePathJ) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!ensurePreviewController()) {
        return nullptr;
    }

    auto timeline = g_preview->getTimeline();
    if (!timeline) {
        return nullptr;
    }

    const char* rawPath = env->GetStringUTFChars(sourcePathJ, nullptr);
    if (!rawPath) {
        return nullptr;
    }
    std::string sourcePath(rawPath);
    env->ReleaseStringUTFChars(sourcePathJ, rawPath);

    if (!g_preview->isReady()) {
        if (!g_preview->open(sourcePath)) {
            PRO_LOGE("open failed: %s", g_preview->getLastError());
            return nullptr;
        }
        if (g_nativeWindow && !g_preview->attachSurface(g_nativeWindow)) {
            PRO_LOGE("attachSurface after open failed: %s", g_preview->getLastError());
        }
    }

    const int64_t startTimeMs = timeline->getDuration();
    auto clip = std::make_shared<VideoEngine::Clip>(sourcePath, startTimeMs, 0);
    timeline->addClip(clip);

    std::ostringstream idBuilder;
    idBuilder << clip->getId();
    const std::string clipId = idBuilder.str();
    return env->NewStringUTF(clipId.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_video_engine_pro_jni_NativeEngineBridge_getClipDuration(
    JNIEnv* env, jobject thiz, jstring clipIdJ) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_preview) {
        return 0;
    }
    auto timeline = g_preview->getTimeline();
    if (!timeline) {
        return 0;
    }
    const char* rawClipId = env->GetStringUTFChars(clipIdJ, nullptr);
    if (!rawClipId) {
        return 0;
    }
    const std::string clipId(rawClipId);
    env->ReleaseStringUTFChars(clipIdJ, rawClipId);
    for (const auto& clip : timeline->clips()) {
        if (!clip) continue;
        if (std::to_string(clip->getId()) == clipId) {
            return static_cast<jlong>(clip->getDuration());
        }
    }
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_video_engine_pro_jni_NativeEngineBridge_getTimelineDuration(
    JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_preview) {
        return 0;
    }
    auto timeline = g_preview->getTimeline();
    return timeline ? static_cast<jlong>(timeline->getDuration()) : 0;
}

JNIEXPORT void JNICALL
Java_com_video_engine_pro_jni_NativeEngineBridge_clearTimeline(
    JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_preview) {
        return;
    }
    if (auto timeline = g_preview->getTimeline()) {
        timeline->clear();
    }
    g_preview->stop();
    g_currentTimeMs.store(0, std::memory_order_release);
}

}

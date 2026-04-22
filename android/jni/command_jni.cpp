#include <jni.h>
#include <android/log.h>
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "AndroidPreview", __VA_ARGS__)

#include <algorithm>
#include <atomic>
#include <cmath>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "engine/commands/command_manager.h"
#include "core/clip.h"
#include "core/timeline.h"
#include "native_preview_shared.h"

namespace {

struct PreviewAudioClipSpec {
    std::string path;
    int64_t startTimeMs = 0;
    int64_t durationMs = 0;
    float volume = 1.0f;
    int layerIndex = 0;
    bool visible = true;
};

struct PreviewAudioSelectionCache {
    bool valid = false;
    uint64_t audioGeneration = 0;
    int64_t timelineStartMs = 0;
    int64_t timelineEndMs = 0;
    std::string key;
    std::string json;
};

std::mutex g_previewAudioClipsMutex;
std::vector<PreviewAudioClipSpec> g_previewAudioClips;
std::atomic<uint64_t> g_previewAudioClipsGeneration{1};
std::mutex g_previewAudioSelectionCacheMutex;
PreviewAudioSelectionCache g_previewAudioSelectionCache;

std::string jsonEscape(const std::string& input) {
    std::string escaped;
    escaped.reserve(input.size());
    for (const char c : input) {
        switch (c) {
            case '\\': escaped += "\\\\"; break;
            case '"': escaped += "\\\""; break;
            case '\n': escaped += "\\n"; break;
            case '\r': escaped += "\\r"; break;
            case '\t': escaped += "\\t"; break;
            default: escaped += c; break;
        }
    }
    return escaped;
}

std::string quote(const std::string& value) {
    return "\"" + jsonEscape(value) + "\"";
}

std::string buildPreviewAudioSelectionJson(
    const std::string& key,
    const std::string& path,
    int64_t timelineStartMs,
    int64_t timelineEndMs,
    int64_t sourceInMs,
    int64_t sourceOutMs,
    float volume,
    float playbackSpeed,
    bool reversePlayback,
    bool freezeFrameEnabled,
    int64_t freezeFrameTimeMs,
    int64_t freezeFrameDurationMs,
    const std::string& curveSpeedProfile,
    float curveSpeedStrength) {
    return std::string("{") +
        "\"key\":" + quote(key) +
        ",\"path\":" + quote(path) +
        ",\"timelineStartMs\":" + std::to_string(timelineStartMs) +
        ",\"timelineEndMs\":" + std::to_string(timelineEndMs) +
        ",\"sourceInMs\":" + std::to_string(sourceInMs) +
        ",\"sourceOutMs\":" + std::to_string(sourceOutMs) +
        ",\"volume\":" + std::to_string(volume) +
        ",\"playbackSpeed\":" + std::to_string(playbackSpeed) +
        ",\"reversePlayback\":" + std::string(reversePlayback ? "true" : "false") +
        ",\"freezeFrameEnabled\":" + std::string(freezeFrameEnabled ? "true" : "false") +
        ",\"freezeFrameTimeMs\":" + std::to_string(freezeFrameTimeMs) +
        ",\"freezeFrameDurationMs\":" + std::to_string(freezeFrameDurationMs) +
        ",\"curveSpeedProfile\":" + quote(curveSpeedProfile) +
        ",\"curveSpeedStrength\":" + std::to_string(curveSpeedStrength) +
        "}";
}

void invalidatePreviewAudioSelectionCache() {
    std::lock_guard<std::mutex> lock(g_previewAudioSelectionCacheMutex);
    g_previewAudioSelectionCache = PreviewAudioSelectionCache{};
}

bool tryGetCachedPreviewAudioSelection(
    int64_t requestTimeMs,
    uint64_t audioGeneration,
    std::string* jsonOut) {
    if (!jsonOut) {
        return false;
    }
    std::lock_guard<std::mutex> lock(g_previewAudioSelectionCacheMutex);
    if (!g_previewAudioSelectionCache.valid ||
        g_previewAudioSelectionCache.audioGeneration != audioGeneration ||
        requestTimeMs < g_previewAudioSelectionCache.timelineStartMs ||
        requestTimeMs >= g_previewAudioSelectionCache.timelineEndMs ||
        g_previewAudioSelectionCache.json.empty()) {
        return false;
    }
    *jsonOut = g_previewAudioSelectionCache.json;
    return true;
}

bool tryGetCachedPreviewAudioSelectionKey(
    int64_t requestTimeMs,
    uint64_t audioGeneration,
    std::string* keyOut) {
    if (!keyOut) {
        return false;
    }
    std::lock_guard<std::mutex> lock(g_previewAudioSelectionCacheMutex);
    if (!g_previewAudioSelectionCache.valid ||
        g_previewAudioSelectionCache.audioGeneration != audioGeneration ||
        requestTimeMs < g_previewAudioSelectionCache.timelineStartMs ||
        requestTimeMs >= g_previewAudioSelectionCache.timelineEndMs ||
        g_previewAudioSelectionCache.key.empty()) {
        return false;
    }
    *keyOut = g_previewAudioSelectionCache.key;
    return true;
}

void updatePreviewAudioSelectionCache(
    uint64_t audioGeneration,
    int64_t timelineStartMs,
    int64_t timelineEndMs,
    std::string key,
    std::string json) {
    if (timelineEndMs <= timelineStartMs || json.empty()) {
        invalidatePreviewAudioSelectionCache();
        return;
    }
    std::lock_guard<std::mutex> lock(g_previewAudioSelectionCacheMutex);
    g_previewAudioSelectionCache.valid = true;
    g_previewAudioSelectionCache.audioGeneration = audioGeneration;
    g_previewAudioSelectionCache.timelineStartMs = timelineStartMs;
    g_previewAudioSelectionCache.timelineEndMs = timelineEndMs;
    g_previewAudioSelectionCache.key = std::move(key);
    g_previewAudioSelectionCache.json = std::move(json);
}

bool shouldInvalidatePreviewAudioSelectionCacheForAction(const std::string& action) {
    if (action.empty()) {
        return false;
    }
    if (action == "SEEK" ||
        action == "PLAY" ||
        action == "PAUSE" ||
        action == "SET_PREVIEW_POLICY" ||
        action == "SET_PERFORMANCE_POLICY" ||
        action == "SET_AUDIO_MASTER_CLOCK_ENABLED" ||
        action == "UPDATE_AUDIO_CLOCK_US") {
        return false;
    }
    return action.rfind("GET_", 0) != 0;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_video_engine_NativeBridge_nativeExecuteCommand(
    JNIEnv* env,
    jobject /* thiz */,
    jstring actionJ,
    jstring payloadJsonJ) {
    const char* actionChars = env->GetStringUTFChars(actionJ, nullptr);
    const char* payloadChars = env->GetStringUTFChars(payloadJsonJ, nullptr);

    const std::string action = actionChars ? actionChars : "";
    const std::string payloadJson = payloadChars ? payloadChars : "{}";

    if (actionChars) env->ReleaseStringUTFChars(actionJ, actionChars);
    if (payloadChars) env->ReleaseStringUTFChars(payloadJsonJ, payloadChars);

    VideoEngine::Commands::CommandContext context{
        &g_mutex,
        &g_preview,
        &g_currentTimeMs,
        &g_isRenderingActive,
        &g_timelineZoomMilliPxPerSecond,
    };
    auto& manager = VideoEngine::Commands::CommandManager::instance();
    manager.initialize(context);
    const auto result = manager.execute(action, payloadJson);
    if (result.success && shouldInvalidatePreviewAudioSelectionCacheForAction(action)) {
        invalidatePreviewAudioSelectionCache();
    }
    return env->NewStringUTF(result.toJson().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_video_engine_NativeBridge_nativeExecuteCommandAsync(
    JNIEnv* env,
    jobject /* thiz */,
    jstring actionJ,
    jstring payloadJsonJ) {
    const char* actionChars = env->GetStringUTFChars(actionJ, nullptr);
    const char* payloadChars = env->GetStringUTFChars(payloadJsonJ, nullptr);

    const std::string action = actionChars ? actionChars : "";
    const std::string payloadJson = payloadChars ? payloadChars : "{}";

    if (actionChars) env->ReleaseStringUTFChars(actionJ, actionChars);
    if (payloadChars) env->ReleaseStringUTFChars(payloadJsonJ, payloadChars);

    VideoEngine::Commands::CommandContext context{
        &g_mutex,
        &g_preview,
        &g_currentTimeMs,
        &g_isRenderingActive,
        &g_timelineZoomMilliPxPerSecond,
    };
    auto& manager = VideoEngine::Commands::CommandManager::instance();
    manager.initialize(context);
    manager.executeAsync(action, payloadJson);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_video_engine_NativeBridge_nativeGetRecentCommandTelemetry(
    JNIEnv* env,
    jobject /* thiz */) {
    VideoEngine::Commands::CommandContext context{
        &g_mutex,
        &g_preview,
        &g_currentTimeMs,
        &g_isRenderingActive,
        &g_timelineZoomMilliPxPerSecond,
    };
    auto& manager = VideoEngine::Commands::CommandManager::instance();
    manager.initialize(context);
    const std::string telemetryJson = manager.recentTelemetryJson();
    return env->NewStringUTF(telemetryJson.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_video_engine_NativeBridge_nativeClearRecentCommandTelemetry(
    JNIEnv* env,
    jobject /* thiz */) {
    (void)env;
    VideoEngine::Commands::CommandContext context{
        &g_mutex,
        &g_preview,
        &g_currentTimeMs,
        &g_isRenderingActive,
        &g_timelineZoomMilliPxPerSecond,
    };
    auto& manager = VideoEngine::Commands::CommandManager::instance();
    manager.initialize(context);
    manager.clearTelemetry();
}

extern "C" JNIEXPORT void JNICALL
Java_com_video_engine_NativeBridge_nativeSetPreviewAudioClips(
    JNIEnv* env,
    jobject /* thiz */,
    jobjectArray pathsArray,
    jlongArray startTimesMs,
    jlongArray durationsMs,
    jfloatArray volumes,
    jintArray layerIndices,
    jbooleanArray visibleFlags) {
    std::vector<PreviewAudioClipSpec> clips;
    if (pathsArray) {
        const jsize count = env->GetArrayLength(pathsArray);
        clips.reserve(static_cast<size_t>(count));
        jlong* starts = startTimesMs ? env->GetLongArrayElements(startTimesMs, nullptr) : nullptr;
        jlong* durs = durationsMs ? env->GetLongArrayElements(durationsMs, nullptr) : nullptr;
        jfloat* vols = volumes ? env->GetFloatArrayElements(volumes, nullptr) : nullptr;
        jint* layers = layerIndices ? env->GetIntArrayElements(layerIndices, nullptr) : nullptr;
        jboolean* visibles = visibleFlags ? env->GetBooleanArrayElements(visibleFlags, nullptr) : nullptr;

        for (jsize i = 0; i < count; ++i) {
            jstring js = reinterpret_cast<jstring>(env->GetObjectArrayElement(pathsArray, i));
            if (!js) {
                continue;
            }
            const char* cs = env->GetStringUTFChars(js, nullptr);
            PreviewAudioClipSpec clip;
            clip.path = cs ? cs : "";
            clip.startTimeMs = starts ? starts[i] : 0;
            clip.durationMs = durs ? durs[i] : 0;
            clip.volume = vols ? vols[i] : 1.0f;
            clip.layerIndex = layers ? layers[i] : 0;
            clip.visible = visibles ? visibles[i] == JNI_TRUE : true;
            clips.push_back(std::move(clip));
            if (cs) env->ReleaseStringUTFChars(js, cs);
            env->DeleteLocalRef(js);
        }

        if (starts) env->ReleaseLongArrayElements(startTimesMs, starts, JNI_ABORT);
        if (durs) env->ReleaseLongArrayElements(durationsMs, durs, JNI_ABORT);
        if (vols) env->ReleaseFloatArrayElements(volumes, vols, JNI_ABORT);
        if (layers) env->ReleaseIntArrayElements(layerIndices, layers, JNI_ABORT);
        if (visibles) env->ReleaseBooleanArrayElements(visibleFlags, visibles, JNI_ABORT);
    }

    {
        std::lock_guard<std::mutex> lock(g_previewAudioClipsMutex);
        g_previewAudioClips = std::move(clips);
    }
    g_previewAudioClipsGeneration.fetch_add(1, std::memory_order_acq_rel);
    invalidatePreviewAudioSelectionCache();
}

extern "C" JNIEXPORT void JNICALL
Java_com_video_engine_NativeBridge_nativeInvalidatePreviewAudioResolutionCache(
    JNIEnv* env,
    jobject /* thiz */) {
    (void)env;
    invalidatePreviewAudioSelectionCache();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_video_engine_NativeBridge_nativeResolvePreviewAudioSourceAt(
    JNIEnv* env,
    jobject /* thiz */,
    jlong timelineMs) {
    const int64_t requestTimeMs = std::max<int64_t>(0, static_cast<int64_t>(timelineMs));
    const uint64_t audioGeneration = g_previewAudioClipsGeneration.load(std::memory_order_acquire);
    std::string cachedJson;
    if (tryGetCachedPreviewAudioSelection(requestTimeMs, audioGeneration, &cachedJson)) {
        return env->NewStringUTF(cachedJson.c_str());
    }

    {
        std::lock_guard<std::mutex> lock(g_previewAudioClipsMutex);
        const PreviewAudioClipSpec* bestAudio = nullptr;
        for (const auto& clip : g_previewAudioClips) {
            if (!clip.visible || clip.path.empty() || clip.durationMs <= 0 || clip.volume <= 0.0001f) {
                continue;
            }
            const int64_t clipEndMs = clip.startTimeMs + clip.durationMs;
            if (requestTimeMs < clip.startTimeMs || requestTimeMs >= clipEndMs) {
                continue;
            }
            if (!bestAudio ||
                clip.layerIndex > bestAudio->layerIndex ||
                (clip.layerIndex == bestAudio->layerIndex && clip.startTimeMs > bestAudio->startTimeMs)) {
                bestAudio = &clip;
            }
        }
        if (bestAudio) {
            std::string json = buildPreviewAudioSelectionJson(
                "audio-" + bestAudio->path + "#" + std::to_string(bestAudio->startTimeMs),
                bestAudio->path,
                bestAudio->startTimeMs,
                bestAudio->startTimeMs + bestAudio->durationMs,
                0,
                bestAudio->durationMs,
                bestAudio->volume,
                1.0f,
                false,
                false,
                0,
                0,
                "linear",
                1.0f);
            updatePreviewAudioSelectionCache(
                audioGeneration,
                bestAudio->startTimeMs,
                bestAudio->startTimeMs + bestAudio->durationMs,
                "audio-" + bestAudio->path + "#" + std::to_string(bestAudio->startTimeMs),
                json);
            return env->NewStringUTF(json.c_str());
        }
    }

    std::shared_ptr<VideoEngine::Timeline> timeline;
    {
        std::lock_guard<std::mutex> previewLock(g_mutex);
        if (g_preview) {
            timeline = g_preview->getTimeline();
        }
    }

    if (!timeline) {
        return env->NewStringUTF("{}");
    }

    std::shared_ptr<VideoEngine::Clip> bestClip;
    int bestPriority = std::numeric_limits<int>::min();
    int64_t bestStartMs = std::numeric_limits<int64_t>::min();
    int bestLane = std::numeric_limits<int>::min();
    LOGD("[AudioDebug] timeline clips=%zu requestTimeMs=%lld", timeline->clips().size(), (long long)requestTimeMs);
    for (const auto& clip : timeline->clips()) {
        if (!clip) continue;
        const auto& props = clip->getProperties();
        LOGD("[AudioDebug] clip path=%s enabled=%d volume=%.2f role=%d start=%lld dur=%lld",
            clip->getMediaPath().c_str(), (int)props.enabled, props.volumeGain,
            (int)clip->getTrackRole(), (long long)clip->getStartTime(), (long long)clip->getDuration());
        if (!props.enabled || clip->getMediaPath().empty() || props.volumeGain <= 0.0001f) continue;
        const auto role = clip->getTrackRole();
        int priority = 0;
        if (role == VideoEngine::Clip::TrackRole::Audio) {
            priority = 3;
        } else if (role == VideoEngine::Clip::TrackRole::MainVideo) {
            priority = 2;
        } else {
            continue;
        }
        const int64_t startMs = clip->getStartTime();
        const int64_t durationMs = std::max<int64_t>(1, clip->getDuration());
        const int64_t endMs = startMs + durationMs;
        if (requestTimeMs < startMs || requestTimeMs >= endMs) continue;
        if (!bestClip ||
            priority > bestPriority ||
            (priority == bestPriority && startMs > bestStartMs) ||
            (priority == bestPriority && startMs == bestStartMs && clip->getTrackLane() > bestLane)) {
            bestPriority = priority;
            bestStartMs = startMs;
            bestLane = clip->getTrackLane();
            bestClip = clip;
        }
    }

    if (!bestClip) {
        return env->NewStringUTF("{}");
    }

    const auto& props = bestClip->getProperties();
    if (bestClip->getTrackRole() == VideoEngine::Clip::TrackRole::MainVideo &&
        (props.reversePlayback ||
         props.freezeFrameEnabled ||
         props.curveSpeedProfile != "linear")) {
        return env->NewStringUTF("{}");
    }

    int64_t sourceInMs = 0;
    int64_t sourceOutMs = 0;
    bestClip->getTrimPoints(sourceInMs, sourceOutMs);
    sourceInMs = std::max<int64_t>(0, sourceInMs);
    if (sourceOutMs <= sourceInMs) {
        sourceOutMs = sourceInMs + std::max<int64_t>(1, bestClip->getDuration());
    }

    const std::string keyPrefix =
        bestClip->getTrackRole() == VideoEngine::Clip::TrackRole::Audio ? "native-audio-" : "video-";
    std::string json = buildPreviewAudioSelectionJson(
        keyPrefix + std::to_string(bestClip->getId()),
        bestClip->getMediaPath(),
        bestClip->getStartTime(),
        bestClip->getStartTime() + std::max<int64_t>(1, bestClip->getDuration()),
        sourceInMs,
        sourceOutMs,
        props.volumeGain,
        std::max(0.1f, props.playbackSpeed),
        props.reversePlayback,
        props.freezeFrameEnabled,
        props.freezeFrameTimeMs,
        props.freezeFrameDurationMs,
        props.curveSpeedProfile,
        props.curveSpeedStrength);
    updatePreviewAudioSelectionCache(
        audioGeneration,
        bestClip->getStartTime(),
        bestClip->getStartTime() + std::max<int64_t>(1, bestClip->getDuration()),
        keyPrefix + std::to_string(bestClip->getId()),
        json);
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_video_engine_NativeBridge_nativeResolvePreviewAudioSourceKeyAt(
    JNIEnv* env,
    jobject /* thiz */,
    jlong timelineMs) {
    const int64_t requestTimeMs = std::max<int64_t>(0, static_cast<int64_t>(timelineMs));
    const uint64_t audioGeneration = g_previewAudioClipsGeneration.load(std::memory_order_acquire);
    std::string cachedKey;
    if (tryGetCachedPreviewAudioSelectionKey(requestTimeMs, audioGeneration, &cachedKey)) {
        return env->NewStringUTF(cachedKey.c_str());
    }
    // Cache miss: do a full resolution to populate the cache and return the key.
    std::string cachedJson;
    if (!tryGetCachedPreviewAudioSelection(requestTimeMs, audioGeneration, &cachedJson)) {
        // Trigger full resolution by calling the source resolver inline.
        jstring fullJson = Java_com_video_engine_NativeBridge_nativeResolvePreviewAudioSourceAt(
            env, nullptr, timelineMs);
        if (!fullJson) return env->NewStringUTF("");
        const char* cs = env->GetStringUTFChars(fullJson, nullptr);
        std::string jsonStr = cs ? cs : "";
        if (cs) env->ReleaseStringUTFChars(fullJson, cs);
        env->DeleteLocalRef(fullJson);
        if (jsonStr.empty() || jsonStr == "{}") return env->NewStringUTF("");
    }
    // Now try cache again after population.
    if (tryGetCachedPreviewAudioSelectionKey(requestTimeMs, audioGeneration, &cachedKey)) {
        return env->NewStringUTF(cachedKey.c_str());
    }
    return env->NewStringUTF("");
}

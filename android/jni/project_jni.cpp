// Project Save/Load JNI bindings
// To be included in native_preview.cpp or as standalone file

#include <jni.h>
#include <algorithm>
#include <cctype>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "../../engine/project.h"
#include "../../core/timeline.h"
#include "native_preview_shared.h"
#include "../../text_overlay.h"

using namespace VideoEngine; // Add this line

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "ProjectJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#else
#define LOG_TAG_DESKTOP "ProjectJNI" // Set the tag for desktop logging
#include "../../desktop_log.h"
// LOGI, LOGE, LOGD, LOGW are now defined in desktop_log.h
#endif

using namespace VideoEngine; // Add this line

namespace {
VideoEngine::Clip::TrackRole trackRoleFromString(const std::string& role) {
    if (role == "OVERLAY") return VideoEngine::Clip::TrackRole::Overlay;
    if (role == "TEXT" || role == "TEXT_STICKER") return VideoEngine::Clip::TrackRole::TextSticker;
    if (role == "AUDIO") return VideoEngine::Clip::TrackRole::Audio;
    return VideoEngine::Clip::TrackRole::MainVideo;
}

std::string normalizeTransitionType(std::string type) {
    std::transform(
        type.begin(),
        type.end(),
        type.begin(),
        [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return type;
}

std::string projectTransitionTypeFromId(int32_t typeId) {
    switch (typeId) {
        case 1: return "fade";
        case 2: return "cross";
        case 3: return "wipe";
        case 4: return "slide";
        default: return "none";
    }
}

int32_t projectTransitionTypeToId(const std::string& type) {
    const std::string normalized = normalizeTransitionType(type);
    if (normalized == "fade") {
        return 1;
    }
    if (normalized == "cross" || normalized == "crossfade" || normalized == "cross_dissolve") {
        return 2;
    }
    if (normalized == "wipe") {
        return 3;
    }
    if (normalized == "slide" || normalized == "slide_left" || normalized == "slide_right") {
        return 4;
    }
    return 0;
}
}  // namespace

struct ClipExportSource {
    int clipId = -1;
    std::string path;
    int64_t durationMs = 0;
};
extern std::vector<ClipExportSource> g_timelineClipPaths;

extern "C" {

/**
 * Save current timeline as project file (JSON).
 * Serializes all clips, text overlays, transitions, and effects.
 */
JNIEXPORT jboolean JNICALL
Java_com_video_engine_VideoPreviewView_nativeSaveProject(
    JNIEnv* env, jobject thiz,
    jstring outputPath,
    jstring projectName) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_preview) {
        LOGE("[Project] Preview not initialized");
        return JNI_FALSE;
    }
    
    try {
        const char* pathCStr = env->GetStringUTFChars(outputPath, nullptr);
        const char* nameCStr = env->GetStringUTFChars(projectName, nullptr);
        
        std::string outputPathStr(pathCStr);
        std::string projectNameStr(nameCStr);
        
        env->ReleaseStringUTFChars(outputPath, pathCStr);
        env->ReleaseStringUTFChars(projectName, nameCStr);
        
        auto timeline = g_preview->getTimeline();
        if (!timeline) {
            LOGE("[Project] Timeline not available");
            return JNI_FALSE;
        }
        
        VideoEngine::Project project(timeline);
        
        VideoEngine::Project::Metadata meta;
        meta.name = projectNameStr;
        meta.version = "1.0";
        project.setMetadata(meta);

        for (const auto& [transitionId, transition] : g_transitions) {
            VideoEngine::Project::TransitionEntry entry;
            entry.id = transitionId;
            entry.type = projectTransitionTypeFromId(transition.typeId);
            entry.fromClipId = transition.outgoingClipId;
            entry.toClipId = transition.incomingClipId;
            entry.startTimeMs = transition.startTimeMs;
            entry.durationMs = std::max<int64_t>(1, transition.durationMs);
            project.addTransition(entry);
        }
        
        if (project.saveToFile(outputPathStr)) {
            LOGI("[Project] Saved: %s -> %s", projectNameStr.c_str(), outputPathStr.c_str());
            return JNI_TRUE;
        } else {
            LOGE("[Project] Save failed: %s", project.getLastError().c_str());
            return JNI_FALSE;
        }
        
    } catch (const std::exception& e) {
        LOGE("[Project] Exception during save: %s", e.what());
        return JNI_FALSE;
    }
}

/**
 * Load project file (JSON) and apply to timeline.
 * Reconstructs all clips, text overlays, transitions, and effects.
 */
JNIEXPORT jboolean JNICALL
Java_com_video_engine_VideoPreviewView_nativeLoadProject(
    JNIEnv* env, jobject thiz,
    jstring filePath) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_preview) {
        LOGE("[Project] Preview not initialized");
        return JNI_FALSE;
    }
    
    try {
        const char* pathCStr = env->GetStringUTFChars(filePath, nullptr);
        std::string filePathStr(pathCStr);
        env->ReleaseStringUTFChars(filePath, pathCStr);
        
        auto timeline = g_preview->getTimeline();
        if (!timeline) {
            LOGE("[Project] Timeline not available");
            return JNI_FALSE;
        }
        
        VideoEngine::Project project;
        if (!project.loadFromFile(filePathStr)) {
            LOGE("[Project] Load failed: %s", project.getLastError().c_str());
            return JNI_FALSE;
        }
        
        timeline->clear();
        g_timelineClipPaths.clear();
        std::unordered_map<int64_t, int> loadedClipIdMap;
        loadedClipIdMap.reserve(project.getClips().size());

        if (g_preview) {
            g_preview->resetTimelinePreviewState();
        }
        g_transitions.clear();
        g_nextTransitionId = 1;
        
        for (const auto& clipEntry : project.getClips()) {
            LOGI("[Project] Loading clip: id=%ld path=%s", static_cast<long>(clipEntry.id), clipEntry.mediaPath.c_str());
            auto clip = std::make_shared<VideoEngine::Clip>(
                clipEntry.mediaPath,
                clipEntry.startTimeMs,
                clipEntry.durationMs
            );
            clip->setTrackRole(trackRoleFromString(clipEntry.trackType));
            clip->setTrackLane(clipEntry.trackLane);
            clip->setTrackZOrder(clipEntry.zOrder);
            clip->setTrimPoints(clipEntry.sourceInMs, clipEntry.sourceOutMs);
            clip->setOpacity(clipEntry.opacity);
            clip->setEnabled(clipEntry.enabled);
            clip->setVolumeGain(clipEntry.volumeGain);
            clip->getMutableProperties().fadeInMs = std::max<int32_t>(0, clipEntry.fadeInMs);
            clip->getMutableProperties().fadeOutMs = std::max<int32_t>(0, clipEntry.fadeOutMs);
            clip->getMutableProperties().audioGainKeyframes = clipEntry.audioGainKeyframes;
            clip->setPlaybackSpeed(clipEntry.playbackSpeed);
            clip->getMutableProperties().reversePlayback = clipEntry.reversePlayback;
            clip->getMutableProperties().freezeFrameEnabled = clipEntry.freezeFrameEnabled;
            clip->getMutableProperties().freezeFrameTimeMs = clipEntry.freezeFrameTimeMs;
            clip->getMutableProperties().freezeFrameDurationMs = clipEntry.freezeFrameDurationMs;
            clip->getMutableProperties().curveSpeedProfile = clipEntry.curveSpeedProfile;
            clip->getMutableProperties().curveSpeedStrength = clipEntry.curveSpeedStrength;
            clip->getMutableEffects().brightness = clipEntry.effects.brightness;
            clip->getMutableEffects().contrast   = clipEntry.effects.contrast;
            clip->getMutableEffects().saturation = clipEntry.effects.saturation;
            clip->getMutableEffects().enabled    = clipEntry.effects.enabled;
            clip->setChromaKeyEnabled(clipEntry.chromaKey.enabled);
            clip->setChromaKeyColor(
                clipEntry.chromaKey.color == 1
                    ? VideoEngine::Clip::ChromaKeyParams::KeyColor::Blue
                    : VideoEngine::Clip::ChromaKeyParams::KeyColor::Green);
            clip->setChromaKeySimilarity(clipEntry.chromaKey.similarity);
            clip->setChromaKeySmoothness(clipEntry.chromaKey.smoothness);
            clip->setChromaKeySpill(clipEntry.chromaKey.spill);
            timeline->addClip(clip);
            const int clipId = static_cast<int>(clip->getId());
            loadedClipIdMap[clipEntry.id] = clipId;
            g_timelineClipPaths.push_back({clipId, clipEntry.mediaPath, clipEntry.durationMs});
        }
        
        for (const auto& textEntry : project.getTextOverlays()) {
            TextOverlay textOverlay;
            textOverlay.id = textEntry.id;
            textOverlay.text = textEntry.text;
            textOverlay.x = textEntry.x;
            textOverlay.y = textEntry.y;
            textOverlay.scale = textEntry.scale;
            textOverlay.rotation = textEntry.rotation;
            textOverlay.color = textEntry.color;
            textOverlay.opacity = textEntry.opacity;
            textOverlay.fadeInMs = textEntry.fadeInMs;
            textOverlay.fadeOutMs = textEntry.fadeOutMs;
            textOverlay.zOrder = textEntry.zOrder;
            textOverlay.startTime = textEntry.startTime;
            textOverlay.endTime = textEntry.endTime;
            textOverlay.enabled = textEntry.enabled;
            textOverlay.keyframes = textEntry.keyframes;
            
            timeline->addTextOverlay(textOverlay);
            LOGI("[Project] Loaded text: id=%ld text=%s", static_cast<long>(textEntry.id), textEntry.text.c_str());
        }

        int64_t nextTransitionId = 1;
        for (const auto& transitionEntry : project.getTransitions()) {
            const auto outgoingIt = loadedClipIdMap.find(transitionEntry.fromClipId);
            const auto incomingIt = loadedClipIdMap.find(transitionEntry.toClipId);
            if (outgoingIt == loadedClipIdMap.end() || incomingIt == loadedClipIdMap.end()) {
                LOGW(
                    "[Project] Skipping transition id=%ld because clip mapping is missing (%ld -> %ld)",
                    static_cast<long>(transitionEntry.id),
                    static_cast<long>(transitionEntry.fromClipId),
                    static_cast<long>(transitionEntry.toClipId));
                continue;
            }

            const int64_t transitionId =
                transitionEntry.id > 0 ? transitionEntry.id : nextTransitionId;
            nextTransitionId = std::max(nextTransitionId, transitionId + 1);

            Transition transition;
            transition.id = transitionId;
            transition.outgoingClipId = outgoingIt->second;
            transition.incomingClipId = incomingIt->second;
            transition.typeId = projectTransitionTypeToId(transitionEntry.type);
            transition.durationMs = static_cast<int32_t>(std::max<int64_t>(1, transitionEntry.durationMs));
            transition.startTimeMs = std::max<int64_t>(0, transitionEntry.startTimeMs);
            transition.isEnabled = true;
            g_transitions[transition.id] = transition;

            if (g_preview) {
                g_preview->upsertTransition(
                    transition.id,
                    transition.outgoingClipId,
                    transition.incomingClipId,
                    transition.typeId,
                    transition.durationMs,
                    transition.startTimeMs);
            }
        }
        g_nextTransitionId = std::max<int64_t>(1, nextTransitionId);
        
        LOGI("[Project] Loaded successfully: %s", filePathStr.c_str());

        // Apply project metadata to timeline video properties
        {
            VideoEngine::VideoProperties vp;
            vp.resolution.width  = static_cast<uint32_t>(project.getMetadata().width);
            vp.resolution.height = static_cast<uint32_t>(project.getMetadata().height);
            vp.frameRate         = static_cast<uint32_t>(project.getMetadata().fps);
            vp.aspectRatio       = (project.getMetadata().height > 0)
                ? static_cast<float>(project.getMetadata().width) / project.getMetadata().height
                : 16.0f / 9.0f;
            timeline->setVideoProperties(vp);
        }

        // Seek preview to start so first frame is visible
        if (g_preview) {
            g_preview->scrubToTimelineTime(0);
        }

        return JNI_TRUE;
        
    } catch (const std::exception& e) {
        LOGE("[Project] Exception during load: %s", e.what());
        return JNI_FALSE;
    }
}

}  // extern "C"

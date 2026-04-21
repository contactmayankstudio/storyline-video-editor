// Project Save/Load JNI bindings
// To be included in native_preview.cpp or as standalone file

#include <jni.h>
#include <memory>
#include <mutex>
#include <vector>

#include "../../engine/project.h"
#include "../../core/timeline.h"
#include "../../engine/preview_controller.h" // Corrected path
#include "../../text_overlay.h"

using namespace VideoEngine; // Add this line

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "ProjectJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
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
}  // namespace

// Forward declarations (from native_preview.cpp)
extern std::mutex g_mutex;
extern std::unique_ptr<VideoEngine::PreviewController> g_preview;
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
            g_timelineClipPaths.push_back({static_cast<int>(clip->getId()), clipEntry.mediaPath, clipEntry.durationMs});
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

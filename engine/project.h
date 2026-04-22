#pragma once

#include <string>
#include <vector>
#include <memory>
#include <cstdint>
#include "../core/clip.h"
#include "../text_overlay.h"
#include "engine.h"

namespace VideoEngine {

class Timeline;

/**
 * Project: Complete timeline state saved to/loaded from JSON.
 * 
 * Serializes:
 * - All clips (video, image)
 * - All text overlays with keyframes
 * - All transitions
 * - All effects (brightness, contrast, saturation)
 * - Playback modifiers (speed, reverse, freeze, curve speed)
 * - Chroma key
 * - Metadata (name, created date, last modified)
 * 
 * Usage:
 *   Project proj(timeline);
 *   proj.saveToFile("/sdcard/myproject.vne");
 * 
 *   Project proj2;
 *   proj2.loadFromFile("/sdcard/myproject.vne");
 *   // Apply clips/overlays from proj2 via project_jni.cpp nativeLoadProject()
 */
class Project {
public:
    /**
     * Project metadata
     */
    struct Metadata {
        std::string name;
        std::string createdDate;
        std::string lastModifiedDate;
        std::string version = "1.0";
        int width = 1920;
        int height = 1080;
        int fps = 30;
    };

    /**
     * Clip entry in project (simplified, without decoded frames)
     */
    struct ClipEntry {
        int64_t id = 0;
        std::string mediaPath;
        TimeMs startTimeMs = 0;
        TimeMs durationMs = 0;
        TimeMs sourceInMs = 0;
        TimeMs sourceOutMs = 0;
        std::string trackType = "VIDEO";
        int32_t trackLane = 0;
        int32_t zOrder = 0;
        float opacity = 1.0f;
        float volumeGain = 1.0f;
        int32_t fadeInMs = 0;
        int32_t fadeOutMs = 0;
        std::vector<Clip::AudioGainKeyframe> audioGainKeyframes;
        bool enabled = true;
        float playbackSpeed = 1.0f;
        bool reversePlayback = false;
        bool freezeFrameEnabled = false;
        TimeMs freezeFrameTimeMs = 0;
        TimeMs freezeFrameDurationMs = 1000;
        std::string curveSpeedProfile = "linear";
        float curveSpeedStrength = 1.0f;
        
        // Effects for this clip
        struct {
            float brightness = 0.0f;
            float contrast = 1.0f;
            float saturation = 1.0f;
            bool enabled = true;
        } effects;

        // Chroma key
        struct {
            bool enabled = false;
            int32_t color = 0;  // 0=green, 1=blue
            float similarity = 0.35f;
            float smoothness = 0.10f;
            float spill = 0.05f;
        } chromaKey;
    };

    /**
     * Text overlay entry in project (without GPU texture)
     */
    struct TextEntry {
        int64_t id = 0;
        std::string text;
        float x = 0.5f;
        float y = 0.5f;
        float scale = 1.0f;
        float rotation = 0.0f;
        uint32_t color = 0xFFFFFFFF;
        float opacity = 1.0f;
        int32_t fadeInMs = 0;
        int32_t fadeOutMs = 0;
        int32_t zOrder = 0;
        TimeMs startTime = 0;
        TimeMs endTime = -1;
        bool enabled = true;
        
        // Keyframes
        std::vector<TextOverlay::TextKeyframe> keyframes;
    };

    /**
     * Transition entry in project
     */
    struct TransitionEntry {
        int64_t id = 0;
        std::string type;  // "crossfade", "fade", "custom"
        int64_t fromClipId = 0;
        int64_t toClipId = 0;
        TimeMs startTimeMs = 0;
        TimeMs durationMs = 300;
    };

    /**
     * Create empty project
     */
    Project() = default;

    /**
     * Create project from timeline
     */
    explicit Project(std::shared_ptr<Timeline> timeline);

    // Metadata access
    const Metadata& getMetadata() const { return metadata_; }
    void setMetadata(const Metadata& meta) { metadata_ = meta; }

    // Convenience methods for project name
    std::string getProjectName() const { return metadata_.name; }
    void setProjectName(const std::string& name) { metadata_.name = name; }

    // Clip management
    const std::vector<ClipEntry>& getClips() const { return clips_; }
    void addClip(const ClipEntry& clip) { clips_.push_back(clip); }
    void clearClips() { clips_.clear(); }

    // Text overlay management
    const std::vector<TextEntry>& getTextOverlays() const { return textOverlays_; }
    void addTextOverlay(const TextEntry& text) { textOverlays_.push_back(text); }
    void clearTextOverlays() { textOverlays_.clear(); }

    // Transition management
    const std::vector<TransitionEntry>& getTransitions() const { return transitions_; }
    void addTransition(const TransitionEntry& trans) { transitions_.push_back(trans); }
    void clearTransitions() { transitions_.clear(); }

    /**
     * Serialize project to JSON file
     * @param filePath Output file path (e.g., "/sdcard/project.vne")
     * @return true if successful
     */
    bool saveToFile(const std::string& filePath) const;

    /**
     * Deserialize project from JSON file
     * @param filePath Input file path
     * @return true if successful
     */
    bool loadFromFile(const std::string& filePath);

    /**
     * Serialize to JSON string
     */
    std::string toJSON() const;

    /**
     * Deserialize from JSON string
     */
    bool fromJSON(const std::string& jsonStr);

    /**
     * Get error message from last operation
     */
    std::string getLastError() const { return lastError_; }

private:
    Metadata metadata_;
    std::vector<ClipEntry> clips_;
    std::vector<TextEntry> textOverlays_;
    std::vector<TransitionEntry> transitions_;
    mutable std::string lastError_;

    // Helper methods
    void setError(const std::string& msg) const { lastError_ = msg; }
};

using ProjectPtr = std::shared_ptr<Project>;

}  // namespace VideoEngine

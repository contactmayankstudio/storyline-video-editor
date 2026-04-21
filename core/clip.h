#pragma once

#include <string>
#include <cstdint>
#include <memory>
#include <vector>
#include <optional>
#include <chrono>
#include <algorithm>
#include <atomic>

namespace VideoEngine {

/**
 * Represents a media source with timing and transformation properties.
 * Supports video/audio clips with extensible effect/filter chain.
 */
class Clip {
public:
    enum class MediaType {
        Video,
        Audio,
        Image,
        Unknown
    };

    enum class TrackRole {
        MainVideo = 0,
        Overlay = 1,
        TextSticker = 2,
        Audio = 3,
    };

    // Timing in milliseconds for frame-accurate operations
    using TimeMs = int64_t;

    struct ClipProperties {
        float opacity = 1.0f;           // 0.0 to 1.0
        float volumeGain = 1.0f;        // Audio gain multiplier
        float playbackSpeed = 1.0f;     // Speed multiplier (1.0 = normal)
        bool reversePlayback = false;   // Reverse playback intent flag
        bool freezeFrameEnabled = false;
        int64_t freezeFrameTimeMs = 0;
        int64_t freezeFrameDurationMs = 1000;
        bool duckingEnabled = false;
        float duckingAmount = 0.35f;    // 0.0..1.0 reduction target
        std::string curveSpeedProfile = "linear";
        float curveSpeedStrength = 1.0f;
        bool enabled = true;
    };

    /**
     * GPU effects parameters for color correction and visual effects.
     * Applied in fragment shader during YUV→RGB conversion.
     * 
     * Performance: All effects computed in single pass with no branching.
     * Why GPU-based:
     * - Color correction on GPU is 100x faster than CPU
     * - Zero memory bandwidth (operates on texture in-place)
     * - Supports real-time preview during scrubbing
     * - Scales to unlimited clips (same shader for all)
     */
    struct EffectParams {
        float brightness = 0.0f;        // Range: -1.0 to +1.0 (add to color)
        float contrast = 1.0f;          // Range: 0.0 to 2.0+ (multiply by factor)
        float saturation = 1.0f;        // Range: 0.0 to 2.0+ (0=grayscale, 1=normal)
        bool enabled = true;            // Enable/disable all effects
        bool lutEnabled = false;        // Optional 3D LUT color grading
        uint32_t lutTextureId = 0;      // OpenGL texture ID for 3D LUT (if lutEnabled)
    };

    /**
     * Chroma key parameters for green/blue screen removal.
     * Applied in fragment shader during YUV→RGB conversion.
     */
    struct ChromaKeyParams {
        enum class KeyColor {
            Green = 0,
            Blue = 1
        };
        bool enabled = false;
        KeyColor color = KeyColor::Green;
        float similarity = 0.35f;   // Distance threshold [0..1]
        float smoothness = 0.10f;   // Edge smoothing [0..1]
        float spill = 0.05f;        // Spill suppression [0..1]
    };

    /**
     * Create a clip from a media source
     * @param mediaPath Path to media file
     * @param startTimeMs Timeline insertion point (milliseconds)
     * @param durationMs Clip duration in milliseconds (0 = use source duration)
     */
    explicit Clip(const std::string& mediaPath, TimeMs startTimeMs = 0, TimeMs durationMs = 0);

    ~Clip() = default;

    // Non-copyable, but movable
    Clip(const Clip&) = delete;
    Clip& operator=(const Clip&) = delete;
    Clip(Clip&&) noexcept = default;
    Clip& operator=(Clip&&) noexcept = default;

    // ============ Identification & Metadata ============
    [[nodiscard]] const std::string& getMediaPath() const { return mediaPath_; }
    [[nodiscard]] MediaType getMediaType() const { return mediaType_; }
    [[nodiscard]] uint32_t getId() const { return id_; }

    // ============ Timing & Position ============
    /**
     * Get clip's start position on timeline (milliseconds)
     */
    [[nodiscard]] TimeMs getStartTime() const { return startTimeMs_; }

    /**
     * Get clip duration on timeline (milliseconds)
     */
    [[nodiscard]] TimeMs getDuration() const { return durationMs_; }

    /**
     * Get end time on timeline (start + duration)
     */
    [[nodiscard]] TimeMs getEndTime() const { return startTimeMs_ + durationMs_; }

    /**
     * Set clip position and duration on timeline
     */
    void setTimelinePosition(TimeMs startTimeMs, TimeMs durationMs) {
        startTimeMs_ = startTimeMs;
        durationMs_ = durationMs;
    }

    // ============ Track placement ============
    [[nodiscard]] TrackRole getTrackRole() const { return trackRole_; }
    [[nodiscard]] int getTrackLane() const { return trackLane_; }
    [[nodiscard]] int getTrackZOrder() const { return trackZOrder_; }

    void setTrackRole(TrackRole role) { trackRole_ = role; }
    void setTrackLane(int lane) { trackLane_ = std::max(0, lane); }
    void setTrackZOrder(int zOrder) { trackZOrder_ = zOrder; }

    /**
     * Get source trim points (in-point, out-point from source media)
     */
    void getTrimPoints(TimeMs& inPoint, TimeMs& outPoint) const {
        inPoint = sourceInPointMs_;
        outPoint = sourceOutPointMs_;
    }

    /**
     * Set source trim points
     */
    void setTrimPoints(TimeMs inPoint, TimeMs outPoint) {
        sourceInPointMs_ = inPoint;
        sourceOutPointMs_ = outPoint;
    }

    // ============ Properties & Effects ============
    [[nodiscard]] const ClipProperties& getProperties() const { return properties_; }
    
    ClipProperties& getMutableProperties() { return properties_; }

    void setOpacity(float value) { properties_.opacity = std::clamp(value, 0.0f, 1.0f); }
    void setVolumeGain(float value) { properties_.volumeGain = value; }
    void setPlaybackSpeed(float value) { properties_.playbackSpeed = std::max(0.1f, value); }
    void setEnabled(bool enabled) { properties_.enabled = enabled; }

    // ============ GPU Effects ============
    /**
     * Get effect parameters for GPU shader processing.
     * Effects are applied in fragment shader with no CPU overhead.
     */
    [[nodiscard]] const EffectParams& getEffects() const { return effects_; }
    
    EffectParams& getMutableEffects() { return effects_; }

    void setEffectBrightness(float value) { 
        effects_.brightness = std::clamp(value, -1.0f, 1.0f); 
    }

    void setEffectContrast(float value) { 
        effects_.contrast = std::clamp(value, 0.0f, 4.0f); 
    }

    void setEffectSaturation(float value) { 
        effects_.saturation = std::clamp(value, 0.0f, 2.0f); 
    }

    void setEffectsEnabled(bool enabled) { 
        effects_.enabled = enabled; 
    }

    void setLUTTexture(uint32_t textureId) {
        effects_.lutTextureId = textureId;
        effects_.lutEnabled = (textureId != 0);
    }

    void disableLUT() {
        effects_.lutEnabled = false;
        effects_.lutTextureId = 0;
    }

    // ============ Chroma Key ============
    [[nodiscard]] const ChromaKeyParams& getChromaKey() const { return chromaKey_; }
    ChromaKeyParams& getMutableChromaKey() { return chromaKey_; }

    void setChromaKeyEnabled(bool enabled) { chromaKey_.enabled = enabled; }
    void setChromaKeyColor(ChromaKeyParams::KeyColor color) { chromaKey_.color = color; }
    void setChromaKeySimilarity(float value) {
        chromaKey_.similarity = std::clamp(value, 0.0f, 1.0f);
    }
    void setChromaKeySmoothness(float value) {
        chromaKey_.smoothness = std::clamp(value, 0.0f, 1.0f);
    }
    void setChromaKeySpill(float value) {
        chromaKey_.spill = std::clamp(value, 0.0f, 1.0f);
    }
    // ============ Text Overlay (optional) ==========
    struct TextOverlay {
        std::string text;
        float x = 0.5f; // normalized 0..1
        float y = 0.5f; // normalized 0..1
        float scale = 1.0f;
        uint32_t color = 0xffffffffu; // ARGB
        bool enabled = false;
    };

    [[nodiscard]] const std::optional<TextOverlay>& getTextOverlay() const { return textOverlay_; }
    void setTextOverlay(const TextOverlay& t) { textOverlay_ = t; }
    void clearTextOverlay() { textOverlay_.reset(); }
    // ============ Extensibility for Effects/Filters ============
    /**
     * Reserve space for future effect/filter chains
     * Implemented in v2: effects pipeline
     */
    void addEffect(const std::string& effectId) {
        // Placeholder for effect system
        effectIds_.push_back(effectId);
    }

    [[nodiscard]] const std::vector<std::string>& getEffectIds() const { return effectIds_; }

    // ============ Audio Track Support ============
    /**
     * Clip can have multiple audio tracks (0 = primary)
     * Reserved for future: audio mixing
     */
    void setAudioTrackIndex(uint32_t index) { audioTrackIndex_ = index; }
    [[nodiscard]] uint32_t getAudioTrackIndex() const { return audioTrackIndex_; }

private:
    // Core properties
    std::string mediaPath_;
    uint32_t id_;
    MediaType mediaType_;

    // Timeline positioning (milliseconds)
    TimeMs startTimeMs_;
    TimeMs durationMs_;

    // Timeline track metadata (used by pro multi-track timeline + renderer z-order)
    TrackRole trackRole_ = TrackRole::MainVideo;
    int trackLane_ = 0;
    int trackZOrder_ = 0;

    // Source media trim points (milliseconds)
    TimeMs sourceInPointMs_ = 0;
    TimeMs sourceOutPointMs_ = 0;

    // Runtime properties
    ClipProperties properties_;
    EffectParams effects_;
    ChromaKeyParams chromaKey_;

    // Future extensibility
    std::vector<std::string> effectIds_;
    std::optional<TextOverlay> textOverlay_;
    uint32_t audioTrackIndex_ = 0;

    // Helper to detect media type from file extension
    MediaType detectMediaType(const std::string& path);

    // Static ID generator
    static std::atomic<uint32_t> nextId_;
};

using ClipPtr = std::shared_ptr<Clip>;

} // namespace VideoEngine

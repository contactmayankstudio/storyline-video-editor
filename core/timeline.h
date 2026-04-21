#pragma once
#include "clip.h"
#include "../text_overlay.h"
#include <vector>
#include <memory>
#include <cstdint>
#include <map>

namespace VideoEngine {

using TimeMs = int64_t;

// ---------------- Video / Audio project properties ----------------

struct Resolution {
    uint32_t width;
    uint32_t height;
};

struct VideoProperties {
    Resolution resolution;
    uint32_t frameRate;
    float aspectRatio;

    VideoProperties()
        : resolution{1920, 1080},
          frameRate(30),
          aspectRatio(16.0f / 9.0f) {}
};

struct AudioProperties {
    uint32_t sampleRate;
    uint8_t channels;
    uint32_t bitDepth;

    AudioProperties()
        : sampleRate(48000),
          channels(2),
          bitDepth(16) {}
};

// ---------------- Timeline ----------------

class Timeline {
public:
    Timeline();
    void clear();
    // ========== Clip Management ==========

    // clip management
    void addClip(const std::shared_ptr<Clip>& clip);
    void removeClip(const std::string& clipId);
    // ========== Text Overlay Management ==========
    /**
     * Add a text overlay to the timeline.
     * The overlay will be rendered during its startTime..endTime window.
     * @param overlay TextOverlay with text, position, timing, and styling
     * @return Overlay ID for later reference
     */
    int64_t addTextOverlay(const TextOverlay& overlay);

    /**
     * Remove a text overlay by ID.
     * @param overlayId ID returned from addTextOverlay()
     */
    void removeTextOverlay(int64_t overlayId);

    /**
     * Get all text overlays (unfiltered).
     * @return Vector of all TextOverlay objects
     */
    std::vector<TextOverlay> getAllTextOverlays() const;

    /**
     * Get all active text overlays at a given timeline position.
     * An overlay is active if: enabled && startTime <= timeMs < endTime (or endTime == -1)
     * Results are sorted by zOrder (ascending, lowest renders first).
     * @param timeMs Timeline position in milliseconds
     * @return Vector of active TextOverlay objects at this time
     */
    std::vector<TextOverlay> getActiveTextOverlaysAtTime(TimeMs timeMs) const;

    // ========== Timing & Properties ==========

    // queries
    TimeMs getDuration() const;
    TimeMs frameToMs(uint64_t frameNum) const;
    uint64_t msToFrame(TimeMs ms) const;

    const std::vector<std::shared_ptr<Clip>>& clips() const;

    /**
     * Returns true if the given clip overlaps any existing clip on the same track lane.
     */
    bool hasOverlap(const Clip& candidate, int excludeClipId = -1) const;

    /**
     * Build a flat list of clips active at timeMs, sorted by zOrder (back to front).
     */
    std::vector<std::shared_ptr<Clip>> getActiveClipsAtTime(TimeMs timeMs) const;

    // ========== Video/Audio Properties ==========
    const VideoProperties& getVideoProperties() const { return m_videoProps; }
    const AudioProperties& getAudioProperties() const { return m_audioProps; }
    void setVideoProperties(const VideoProperties& vp) { m_videoProps = vp; }
    void setAudioProperties(const AudioProperties& ap) { m_audioProps = ap; }

private:
    std::vector<std::shared_ptr<Clip>> m_clips;
        std::map<int64_t, TextOverlay> m_textOverlays;  // id -> TextOverlay
        int64_t m_nextOverlayId = 1;                     // Auto-increment ID

    VideoProperties m_videoProps;
    AudioProperties m_audioProps;
};

} // namespace VideoEngine

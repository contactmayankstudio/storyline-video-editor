#pragma once

#include <string>
#include <cstdint>
#include <vector>

namespace VideoEngine {

using TimeMs = int64_t;

/**
 * Shared keyframe struct for timeline-driven transform animation.
 * Used by both TextOverlay and video/image clip layers.
 */
struct TransformKeyframe {
    int64_t timeMs = 0;
    float posX = 0.5f;      // normalized 0..1
    float posY = 0.5f;      // normalized 0..1
    float scale = 1.0f;
    float rotation = 0.0f;  // degrees
    float opacity = 1.0f;   // 0.0..1.0
};

struct TextOverlay {
    int64_t id = -1;
    std::string text;
    float x = 0.5f; // normalized 0..1
    float y = 0.5f; // normalized 0..1
    float scale = 1.0f;
    float rotation = 0.0f; // degrees
    uint32_t color = 0xffffffffu; // RGBA
    float opacity = 1.0f; // 0.0..1.0 base opacity
    int32_t fadeInMs = 0;  // fade-in duration in ms
    int32_t fadeOutMs = 0; // fade-out duration in ms
    int32_t zOrder = 0;    // render order, higher draws later(on top)
    TimeMs startTime = 0;
    TimeMs endTime = -1; // -1 = infinite
    bool enabled = true;
    // GPU texture for bitmap-based overlay (Android bitmap -> native texture)
    unsigned int texture = 0; // GL texture id (0 = none)
    int texWidth = 0;
    int texHeight = 0;
    bool hasTexture = false;

    // Keyframes for timeline-driven animation (sorted by timeMs)
    // TextKeyframe is now an alias for the shared TransformKeyframe
    using TextKeyframe = TransformKeyframe;

    std::vector<TextKeyframe> keyframes; // sorted by timeMs
};
} // namespace VideoEngine

#pragma once

#include <string>
#include <vector>

namespace VideoEngine::Advanced {

/**
 * @brief Represents a professional LUT (Look Up Table) for color grading.
 * VN and KineMaster use these for "Filters".
 */
struct FilterPreset {
    std::string name;
    std::string lutPath; // Path to .cube or .png LUT
    float intensity = 1.0f;
};

/**
 * @brief Masking types for creative reveals and overlays.
 */
enum class MaskType {
    None,
    Linear,
    Mirror,
    Radial,
    Rectangle,
    Heart,
    Star
};

struct MaskParams {
    MaskType type = MaskType::None;
    float feather = 0.1f; // Soft edges
    float position[2] = {0.5f, 0.5f};
    float scale[2] = {1.0f, 1.0f};
    float rotation = 0.0f;
};

/**
 * @brief Blending modes for multi-layer composition.
 */
enum class BlendMode {
    Normal,
    Multiply,
    Screen,
    Overlay,
    Darken,
    Lighten,
    ColorDodge,
    ColorBurn,
    HardLight,
    SoftLight,
    Difference,
    Exclusion
};

/**
 * @brief Utility helpers backing the pro effects catalog.
 */
const std::vector<FilterPreset>& builtInFilterPresets();
MaskParams makeDefaultMask(MaskType type);
const char* blendModeName(BlendMode mode);

} // namespace VideoEngine::Advanced

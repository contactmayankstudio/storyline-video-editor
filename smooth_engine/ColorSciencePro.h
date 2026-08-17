#pragma once

#include <cstdint>

namespace VideoEngine::Advanced {

/**
 * @brief Professional Color Science (HSL & HDR).
 * 
 * Works OFFLINE via GPU Fragment Shaders.
 */
class ColorSciencePro {
public:
    struct HSLParams {
        float hue;        // -180 to 180
        float saturation; // 0 to 2
        float luminance;  // 0 to 2
    };

    /**
     * @brief Apply HSL adjustments to specific color ranges (Red, Green, Blue, etc.)
     */
    void setHSL(int colorIndex, HSLParams params);

    /**
     * @brief ACES Filmic Tone Mapping for HDR videos.
     * Makes mobile video look like a "Cinema Movie".
     */
    void applyACESMapping(bool enabled);

private:
    uint32_t m_hslLutTexture; // Generated dynamically on GPU
};

} // namespace VideoEngine::Advanced

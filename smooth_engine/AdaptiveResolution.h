#pragma once

#include <cstdint>

namespace VideoEngine::Performance {

/**
 * @brief Adaptive Resolution for Lag-Free Scrubbing.
 * 
 * Dynamically scales the preview resolution based on the 
 * scrubbing speed and system load.
 */
class AdaptiveResolution {
public:
    AdaptiveResolution(int baseWidth, int baseHeight);

    /**
     * @brief Updates the scrubbing speed and calculates new dimensions.
     * @param scrubSpeed Timeline units per second.
     */
    void updateScrubSpeed(float scrubSpeed);

    int getTargetWidth() const { return m_targetWidth; }
    int getTargetHeight() const { return m_targetHeight; }
    float getScaleFactor() const { return m_scaleFactor; }

private:
    int m_baseWidth, m_baseHeight;
    int m_targetWidth, m_targetHeight;
    float m_scaleFactor = 1.0f;
};

} // namespace VideoEngine::Performance

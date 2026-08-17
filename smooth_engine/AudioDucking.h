#pragma once

#include <cstddef>
#include <algorithm>
#include <vector>

namespace VideoEngine::Advanced {

/**
 * @brief Smart Audio Ducking.
 * 
 * Professional Feature: Automatically lowers background music volume 
 * when the person on the main track starts speaking.
 */
class AudioDucking {
public:
    void setDuckingAmount(float amount);
    void setThreshold(float threshold);
    float duckingAmount() const { return m_duckingAmount; }
    float threshold() const { return m_threshold; }

    /**
     * @brief Analyzes voice track and applies volume envelopes to music track.
     * @param voiceBuffer Buffer containing speech.
     * @param musicBuffer Buffer containing background music.
     */
    void applyDucking(float* voiceBuffer, float* musicBuffer, size_t numSamples);

private:
    float m_duckingAmount = 0.3f; // 30% volume reduction
    float m_threshold = 0.1f;    // Speech detection threshold
};

} // namespace VideoEngine::Advanced

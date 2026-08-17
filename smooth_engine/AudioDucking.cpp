#include "AudioDucking.h"
#include <algorithm>
#include <cmath>

namespace VideoEngine::Advanced {

void AudioDucking::setDuckingAmount(float amount) {
    m_duckingAmount = std::clamp(amount, 0.05f, 1.0f);
}

void AudioDucking::setThreshold(float threshold) {
    m_threshold = std::clamp(threshold, 0.0f, 0.95f);
}

void AudioDucking::applyDucking(float* voiceBuffer, float* musicBuffer, size_t numSamples) {
    if (!voiceBuffer || !musicBuffer || numSamples == 0) {
        return;
    }

    for (size_t i = 0; i < numSamples; ++i) {
        float voiceLevel = std::abs(voiceBuffer[i]);
        const float detector =
            voiceLevel <= m_threshold
                ? 0.0f
                : std::clamp(
                      (voiceLevel - m_threshold) / std::max(0.0001f, 1.0f - m_threshold),
                      0.0f,
                      1.0f);
        const float gain = 1.0f - detector * (1.0f - m_duckingAmount);
        musicBuffer[i] *= gain;
    }
}

} // namespace VideoEngine::Advanced

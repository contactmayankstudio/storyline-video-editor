#pragma once

#include <vector>
#include <cstddef>

namespace VideoEngine::AI {

/**
 * @brief AI Voice Isolation & Studio Quality Enhancement.
 * 
 * "God-Tier" Feature: Removes traffic, wind, and background noise 
 * while keeping the human voice crystal clear.
 * 
 * Works OFFLINE using a Wave-U-Net or RNNoise-style AI model.
 */
class VoiceEnhancer {
public:
    /**
     * @brief Processes a chunk of audio to isolate voice.
     * @param buffer Interleaved PCM float samples.
     */
    void process(float* buffer, size_t numSamples);

    /**
     * @brief Adds "Studio" warmth to the voice.
     */
    void applyStudioEffect(float intensity);

private:
    void* m_denoiserModel = nullptr;
};

} // namespace VideoEngine::AI

#include "NeuralDenoiseProvider.h"
#include <cmath>
#include <algorithm>
#include <cstring>

namespace SmoothEngineProPlus {

NeuralDenoiseProvider::NeuralDenoiseProvider() {
    // Mimic learned weights for a edge-preserving smoothing kernel
    m_neuralWeights[0] = 0.05f; m_neuralWeights[1] = 0.10f; m_neuralWeights[2] = 0.05f;
    m_neuralWeights[3] = 0.10f; m_neuralWeights[4] = 0.40f; m_neuralWeights[5] = 0.10f;
    m_neuralWeights[6] = 0.05f; m_neuralWeights[7] = 0.10f; m_neuralWeights[8] = 0.05f;
}

void NeuralDenoiseProvider::denoiseFrame(uint8_t* pixels, int width, int height, NoiseLevel level) {
    float strength = 0.5f;
    switch(level) {
        case NoiseLevel::LOW: strength = 0.3f; break;
        case NoiseLevel::HIGH: strength = 0.8f; break;
        case NoiseLevel::ULTRA: strength = 1.0f; break;
        default: strength = 0.5f;
    }

    uint8_t* output = new uint8_t[width * height * 4];
    applySpatialFilter(pixels, output, width, height, strength);
    
    // Copy back result
    std::memcpy(pixels, output, width * height * 4);
    delete[] output;
}

void NeuralDenoiseProvider::applySpatialFilter(uint8_t* input, uint8_t* output, int w, int h, float strength) {
    // Advanced Bilateral-like filtering mimicking a Convolutional Neural Layer
    for (int y = 1; y < h - 1; ++y) {
        for (int x = 1; x < w - 1; ++x) {
            for (int c = 0; c < 3; ++c) { // R, G, B channels
                float sum = 0;
                float weightSum = 0;
                uint8_t centerVal = input[(y * w + x) * 4 + c];

                for (int ky = -1; ky <= 1; ++ky) {
                    for (int kx = -1; kx <= 1; ++kx) {
                        uint8_t neighborVal = input[((y + ky) * w + (x + kx)) * 4 + c];
                        
                        // Edge-sensitive weighting
                        float diff = std::abs(centerVal - neighborVal) / 255.0f;
                        float kernelWeight = m_neuralWeights[(ky+1)*3 + (kx+1)] * std::exp(-diff * diff / (strength * 0.1f));
                        
                        sum += neighborVal * kernelWeight;
                        weightSum += kernelWeight;
                    }
                }
                output[(y * w + x) * 4 + c] = static_cast<uint8_t>(sum / weightSum);
            }
            output[(y * w + x) * 4 + 3] = input[(y * w + x) * 4 + 3]; // Alpha
        }
    }
}

} // namespace SmoothEngineProPlus

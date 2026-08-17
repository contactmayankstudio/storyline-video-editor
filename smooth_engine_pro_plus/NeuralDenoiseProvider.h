#ifndef NEURAL_DENOISE_PROVIDER_H
#define NEURAL_DENOISE_PROVIDER_H

#include <vector>
#include <cstdint>

namespace SmoothEngineProPlus {

/**
 * NeuralDenoiseProvider uses a lightweight spatial-temporal noise reduction 
 * algorithm that mimics a neural network's behavior for video cleanup.
 */
class NeuralDenoiseProvider {
public:
    enum class NoiseLevel { LOW, MEDIUM, HIGH, ULTRA };

    NeuralDenoiseProvider();
    
    // Process a frame buffer to remove grain/noise
    void denoiseFrame(uint8_t* pixels, int width, int height, NoiseLevel level);

private:
    // Internal weights for denoising kernels
    float m_neuralWeights[9]; 
    
    void applySpatialFilter(uint8_t* input, uint8_t* output, int w, int h, float strength);
    void applyTemporalStability(uint8_t* current, uint8_t* last, int size, float factor);
};

} // namespace SmoothEngineProPlus

#endif // NEURAL_DENOISE_PROVIDER_H

#pragma once

#include <vector>
#include <string>

namespace VideoEngine::AI {

/**
 * @brief On-Device Background Removal (Auto-Cutout).
 * 
 * Works 100% OFFLINE using mobile-optimized models (TFLite/NCNN).
 * Architecture:
 * 1. Input: YUV/RGB Frame.
 * 2. Inference: Run segmentation model (e.g., Selfie Segmentation or DeepLabV3).
 * 3. Output: Alpha Mask (Grayscale texture).
 * 4. Blend: Use the mask in fragment shader to discard background pixels.
 */
class AISegmentation {
public:
    AISegmentation(const std::string& modelPath);
    ~AISegmentation();

    /**
     * @brief Generates a mask for a person in the frame.
     * @param inputTexture OpenGL texture ID of the frame.
     * @return OpenGL texture ID of the 8-bit alpha mask.
     */
    uint32_t generateMask(uint32_t inputTexture);

private:
    // Mobile Inference Engine (e.g., TensorFlow Lite Interpreter)
    void* m_interpreter = nullptr;
};

} // namespace VideoEngine::AI

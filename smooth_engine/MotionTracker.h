#pragma once

#include <vector>
#include <cstdint>

namespace VideoEngine::AI {

/**
 * @brief Professional Motion Tracking (Object Tracking).
 * 
 * Allows users to select an object (e.g., a face or a car) and automatically
 * follow its position, scale, and rotation across frames.
 * 
 * Works OFFLINE using Siamese Networks or KCF (Kernelized Correlation Filters).
 */
class MotionTracker {
public:
    struct Rect {
        float x, y, w, h;
    };

    /**
     * @brief Initialize tracking on a specific region in the first frame.
     */
    void init(uint32_t startTex, Rect initialBox);

    /**
     * @brief Track the object in the next frame.
     * @return The new bounding box of the object.
     */
    Rect update(uint32_t nextTex);

    /**
     * @brief Get the transformation matrix to attach a sticker/text to the object.
     */
    std::vector<float> getTransformMatrix();

private:
    void* m_trackerImpl = nullptr; // Internal AI model or tracker state
};

} // namespace VideoEngine::AI

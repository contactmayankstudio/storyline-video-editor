#pragma once

#include <cstdint>

namespace VideoEngine::Performance {

/**
 * @brief Optical Flow for Super-Smooth Slow Motion.
 * 
 * Works OFFLINE by calculating motion vectors between frames.
 * Instead of duplicating frames (which looks jittery), it warps pixels
 * to create new, unique intermediate frames.
 */
class OpticalFlow {
public:
    /**
     * @brief Creates an intermediate frame between frameA and frameB.
     * @param progress 0.0 to 1.0 (e.g., 0.5 for the halfway frame).
     */
    uint32_t interpolate(uint32_t texA, uint32_t texB, float progress);

private:
    /**
     * Uses GPU compute shaders or NDK-level motion estimation.
     */
    void computeMotionVectors(uint32_t texA, uint32_t texB);
};

} // namespace VideoEngine::Performance

#ifndef FRAME_INTERPOLATOR_H
#define FRAME_INTERPOLATOR_H

#include <vector>
#include <cstdint>

namespace SmoothEngineProPlus {

/**
 * FrameInterpolator creates artificial intermediate frames for 
 * ultra-smooth slow motion (Super Slo-Mo).
 * Uses Optical Flow simulation logic.
 */
class FrameInterpolator {
public:
    FrameInterpolator();
    
    // Generates a frame between frameA and frameB at a specific progress (0.0 - 1.0)
    void interpolate(const uint8_t* frameA, const uint8_t* frameB, 
                     uint8_t* output, int w, int h, float progress);

private:
    struct MotionVector { float dx, dy; };
    
    // Estimates motion between two frames
    void estimateMotion(const uint8_t* frameA, const uint8_t* frameB, 
                        MotionVector* flow, int w, int h);
                        
    // Warps frames based on motion vectors
    void warpFrame(const uint8_t* frame, uint8_t* output, 
                   const MotionVector* flow, int w, int h, float scale);
};

} // namespace SmoothEngineProPlus

#endif // FRAME_INTERPOLATOR_H

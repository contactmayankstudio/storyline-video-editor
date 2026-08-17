#include "FrameInterpolator.h"
#include <cmath>
#include <cstring>

namespace SmoothEngineProPlus {

FrameInterpolator::FrameInterpolator() {}

void FrameInterpolator::interpolate(const uint8_t* frameA, const uint8_t* frameB, 
                                   uint8_t* output, int w, int h, float progress) {
    int size = w * h;
    MotionVector* flow = new MotionVector[size];
    
    // 1. Estimate motion from A to B
    estimateMotion(frameA, frameB, flow, w, h);
    
    // 2. Create intermediate frame by warping both and blending
    uint8_t* warpA = new uint8_t[size * 4];
    uint8_t* warpB = new uint8_t[size * 4];
    
    warpFrame(frameA, warpA, flow, w, h, progress);
    warpFrame(frameB, warpB, flow, w, h, -(1.0f - progress));
    
    // 3. Blend based on progress
    for (int i = 0; i < size * 4; ++i) {
        output[i] = static_cast<uint8_t>(warpA[i] * (1.0f - progress) + warpB[i] * progress);
    }
    
    delete[] flow;
    delete[] warpA;
    delete[] warpB;
}

void FrameInterpolator::estimateMotion(const uint8_t* frameA, const uint8_t* frameB, 
                                      MotionVector* flow, int w, int h) {
    // Block matching algorithm or Lucas-Kanade simulation
    for (int y = 0; y < h; y += 8) {
        for (int x = 0; x < w; x += 8) {
            // Find best match for 8x8 block in search window
            flow[y*w + x] = {0, 0}; // Placeholder for match result
        }
    }
}

void FrameInterpolator::warpFrame(const uint8_t* frame, uint8_t* output, 
                                 const MotionVector* flow, int w, int h, float scale) {
    // Remap pixels using flow * scale
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            MotionVector v = flow[y*w + x];
            int sx = std::round(x + v.dx * scale);
            int sy = std::round(y + v.dy * scale);
            
            if (sx >= 0 && sx < w && sy >= 0 && sy < h) {
                std::memcpy(&output[(y*w + x)*4], &frame[(sy*w + sx)*4], 4);
            }
        }
    }
}

} // namespace SmoothEngineProPlus

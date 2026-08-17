#include "SuperResolution.h"
#include <algorithm>
#include <cmath>
#include <iostream>

namespace VideoEngine::AI {

uint32_t SuperResolution::upscale(uint32_t inputTex, float scaleFactor) {
    std::cout << "[SuperResolution] Upscaling frame by " << scaleFactor << "x (Offline AI)...\n";
    
    // Real implementation:
    // 1. Pass texture to ESRGAN model on GPU.
    // 2. Output higher-resolution texture.
    
    return 3001; // Mock Upscaled Texture ID
}

int SuperResolution::recommendPreviewLongEdgePx(
    int sourceWidth,
    int sourceHeight,
    int viewportWidth,
    int viewportHeight,
    int requestedLongEdgePx) const {
    if (requestedLongEdgePx <= 0) {
        return 0;
    }
    if (sourceWidth <= 0 || sourceHeight <= 0) {
        return requestedLongEdgePx;
    }

    const int sourceLongEdge = std::max(sourceWidth, sourceHeight);
    const int viewportLongEdge = std::max(viewportWidth, viewportHeight);
    if (sourceLongEdge <= requestedLongEdgePx) {
        return sourceLongEdge;
    }

    // Only help genuinely low-resolution sources. High-res media should keep
    // the existing ghost/adaptive preview policy for performance.
    if (sourceLongEdge > 960) {
        return requestedLongEdgePx;
    }

    // For 720p-and-below assets, avoid a second quality hit from ghost preview.
    if (sourceLongEdge <= 720) {
        return sourceLongEdge;
    }

    // Mildly relax the long-edge cap for near-HD clips when the viewport can
    // actually show the extra detail.
    const int viewportBound = viewportLongEdge > 0 ? viewportLongEdge : sourceLongEdge;
    const int boosted = std::max(
        requestedLongEdgePx,
        static_cast<int>(std::lround(static_cast<float>(requestedLongEdgePx) * 1.35f)));

    return std::clamp(boosted, requestedLongEdgePx, std::min(sourceLongEdge, viewportBound));
}

} // namespace VideoEngine::AI

#pragma once

#include <cstdint>

namespace VideoEngine::AI {

/**
 * @brief AI Super Resolution (Video Upscaling).
 * 
 * Works OFFLINE to enhance low-quality 720p footage to 4K quality.
 * Uses ESRGAN or Real-ESRGAN mobile-optimized models.
 */
class SuperResolution {
public:
    /**
     * @brief Enhances a single frame.
     * @param inputTex Original low-res texture.
     * @return New high-res texture ID.
     */
    uint32_t upscale(uint32_t inputTex, float scaleFactor = 2.0f);

    /**
     * @brief Decide a safer preview long-edge limit for low-res sources.
     *
     * This does not run ESRGAN in real time. Instead it protects low-resolution
     * footage from being aggressively downscaled again by the ghost/proxy preview
     * path, which produces a visibly cleaner preview on phones.
     */
    int recommendPreviewLongEdgePx(
        int sourceWidth,
        int sourceHeight,
        int viewportWidth,
        int viewportHeight,
        int requestedLongEdgePx) const;

private:
    void* m_upscaleModel = nullptr;
};

} // namespace VideoEngine::AI

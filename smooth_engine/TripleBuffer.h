#pragma once

#include <array>
#include <mutex>
#include <memory>

#include "../preview/gpu/gl_texture.h"

namespace VideoEngine::Performance {

/**
 * @brief Triple texture ring for preview upload/present decoupling.
 *
 * This is not AHardwareBuffer zero-copy yet, but it is a real working
 * three-texture upload ring that reduces reuse of the same GL texture on
 * consecutive preview frames. That lowers the chance of GPU/driver stalls
 * when scrub or gesture updates hammer glTexSubImage2D + present.
 */
class TripleBuffer {
public:
    TripleBuffer(int width, int height);
    ~TripleBuffer();

    /**
     * Ensure all three textures exist at the requested size.
     */
    bool initialize(int width, int height);

    /**
     * @brief Acquires the current writable texture.
     */
    VideoEngine::GPU::GLTexture* acquireBackTexture();

    /**
     * Rotate the uploaded back texture to the front.
     */
    void presentBackTexture();

    /**
     * @brief Returns the texture currently used for presentation.
     */
    VideoEngine::GPU::GLTexture* frontTexture();
    const VideoEngine::GPU::GLTexture* frontTexture() const;

    /**
     * @brief Release all GL textures.
     */
    void release();

    bool isValid() const;
    int width() const { return m_width; }
    int height() const { return m_height; }

private:
    struct BufferFrame {
        std::unique_ptr<VideoEngine::GPU::GLTexture> texture;
    };

    std::array<BufferFrame, 3> m_buffers;
    int m_frontIdx = 0;
    int m_backIdx = 1;
    int m_pendingIdx = 2;
    int m_width = 0;
    int m_height = 0;
    mutable std::mutex m_mutex;
};

} // namespace VideoEngine::Performance

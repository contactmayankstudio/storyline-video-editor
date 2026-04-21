#pragma once

#include <cstdint>

namespace VideoEngine::GPU {

/**
 * OpenGL ES 3.0 texture wrapper for RGBA frame data.
 * 
 * Single-responsibility: manages GPU texture storage and updates.
 * Optimized for real-time video preview with dynamic resolution changes.
 * 
 * Example:
 *   GLTexture tex;
 *   tex.initialize(1920, 1080);
 *   tex.update(rgbaPtr);       // 1920*1080*4 bytes (RGBA)
 *   tex.bind(0);               // Bind to unit 0
 *   // ... render ...
 *   tex.release();
 */
class GLTexture {
public:
    GLTexture();
    ~GLTexture();

    GLTexture(const GLTexture&) = delete;
    GLTexture& operator=(const GLTexture&) = delete;

    /**
     * Initialize or reinitialize texture with dimensions.
     * Handles resolution changes efficiently by reallocating only when needed.
     * 
     * @param width  Frame width in pixels
     * @param height Frame height in pixels
     * @return true if successful, false on allocation failure
     */
    bool initialize(int width, int height);

    /**
     * Update texture with RGBA pixel data.
     * Data must match dimensions from initialize().
     * Uses glTexSubImage2D for efficient streaming (not reallocating).
     * 
     * @param rgbaPixels RGBA data (width * height * 4 bytes)
     *                   Format: R, G, B, A for each pixel in row-major order
     */
    void update(const uint8_t* rgbaPixels);

    /**
     * Bind texture to active texture unit for shader sampling.
     * 
     * @param textureUnit Texture unit (0-31, default 0)
     *                    Call glActiveTexture() first if using unit > 0
     */
    void bind(int textureUnit = 0) const;

    /**
     * Release GPU texture and resources.
     * Safe to call multiple times.
     */
    void release();

    /**
     * Get texture width in pixels.
     * @return width, or 0 if not initialized
     */
    int getWidth() const { return m_width; }

    /**
     * Get texture height in pixels.
     * @return height, or 0 if not initialized
     */
    int getHeight() const { return m_height; }

    /**
     * Check if texture is valid and ready for use.
     * @return true if texture ID is valid and dimensions > 0
     */
    bool isValid() const { return m_textureId != 0 && m_width > 0 && m_height > 0; }

private:
    uint32_t m_textureId;    // OpenGL texture handle
    int m_width;             // Current texture width in pixels
    int m_height;            // Current texture height in pixels

    /**
     * Internal: Allocate or reallocate GPU texture storage.
     * Called by initialize() when dimensions change.
     * 
     * @return true on success
     */
    bool allocateStorage(int width, int height);
};

}  // namespace VideoEngine::GPU


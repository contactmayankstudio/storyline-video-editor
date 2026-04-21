#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

// Forward declarations
struct AVFrame;
struct SwsContext;

namespace VideoEngine::Backend {

/**
 * RGBA frame data ready for OpenGL texture upload.
 * Pixels are tightly packed in row-major order (top-to-bottom).
 * Each pixel is 4 bytes: R, G, B, A (32-bit RGBA).
 */
struct RGBAFrame {
    int width;
    int height;
    std::vector<uint8_t> pixels;  // width * height * 4 bytes
    
    /**
     * Get total pixel count.
     */
    size_t pixelCount() const { return width * height; }
    
    /**
     * Get total buffer size in bytes.
     */
    size_t bufferSize() const { return pixelCount() * 4; }
    
    /**
     * Check if frame is valid (has data).
     */
    bool isValid() const {
        return width > 0 && height > 0 && 
               pixels.size() == bufferSize();
    }
};

/**
 * Converts FFmpeg AVFrame (YUV formats) to RGBA for GPU texture upload.
 * 
 * Designed for:
 * - Real-time video preview/playback
 * - OpenGL texture streaming
 * - Efficient batch frame conversion
 * - Thread-safe usage (no global state)
 * - Android NDK compatibility
 * 
 * Supported input formats:
 * - AV_PIX_FMT_YUV420P (planar YUV 4:2:0, 12-bit)
 * - AV_PIX_FMT_NV12 (semi-planar YUV 4:2:0, 12-bit) - common on mobile
 * - AV_PIX_FMT_YUV422P (planar YUV 4:2:2, 16-bit)
 * - AV_PIX_FMT_YUVJ420P (JPEG range YUV 4:2:0)
 * - Others supported via automatic libswscale negotiation
 * 
 * Output format:
 * - AV_PIX_FMT_RGBA (tightly packed, row-major)
 * 
 * Example usage:
 *   FrameConverter converter;
 *   RGBAFrame rgba = converter.convert(avFrame);
 *   if (rgba.isValid()) {
 *       glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, rgba.width, rgba.height, 
 *                    0, GL_RGBA, GL_UNSIGNED_BYTE, rgba.pixels.data());
 *   }
 */
class FrameConverter {
public:
    FrameConverter();
    ~FrameConverter();

    // Non-copyable
    FrameConverter(const FrameConverter&) = delete;
    FrameConverter& operator=(const FrameConverter&) = delete;

    /**
     * Convert an FFmpeg frame (YUV) to RGBA.
     * Automatically detects pixel format and initializes libswscale.
     * 
     * @param frame The AVFrame to convert (must not be null)
     * @return RGBAFrame with RGBA pixels ready for GPU upload
     *         If conversion fails, returns frame with empty pixels vector
     * 
     * Thread-safe: can call from multiple threads (each call is independent).
     */
    RGBAFrame convert(const AVFrame* frame);

    /**
     * Get last error message (if conversion failed).
     * @return Error string, or empty if no error
     */
    const char* getLastError() const { return m_lastError.c_str(); }

    /**
     * Pre-allocate conversion buffer for a specific resolution.
     * Useful for performance optimization in tight loops.
     * 
     * @param width Width in pixels
     * @param height Height in pixels
     */
    void reserve(int width, int height);

private:
    // libswscale context for color space conversion
    SwsContext* m_swsContext;
    
    // Cached dimensions (for reuse detection)
    int m_cachedWidth;
    int m_cachedHeight;
    int m_cachedPixelFormat;
    
    // Output buffer (pre-allocated for efficiency)
    std::vector<uint8_t> m_buffer;
    
    // Error tracking
    std::string m_lastError;

    /**
     * Internal: Initialize or reinitialize libswscale context.
     * 
     * @param srcWidth Source frame width
     * @param srcHeight Source frame height
     * @param srcFormat Source pixel format (AV_PIX_FMT_*)
     * @return true on success, false otherwise
     */
    bool initSwsContext(int srcWidth, int srcHeight, int srcFormat);

    /**
     * Internal: Release libswscale context.
     */
    void releaseSwsContext();

    /**
     * Internal: Set error message.
     */
    void setError(const char* fmt, ...);
};

}  // namespace VideoEngine::Backend

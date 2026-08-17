#pragma once

#include <cstdint>
#include <memory>
#include <vector>

namespace VideoEngine::GPU {

/**
 * OpenGL texture wrapper.
 * Manages texture allocation, binding, and sampling parameters.
 */
class Texture {
public:
    enum class Format {
        RGB8,      // 24-bit RGB
        RGBA8,     // 32-bit RGBA
        RGBA16F,   // 64-bit RGBA (float)
        RGBA32F,   // 128-bit RGBA (float)
        R8,        // 8-bit grayscale
        DEPTH24,   // 24-bit depth
    };

    enum class WrapMode {
        Clamp,
        Repeat,
        Mirror
    };

    enum class FilterMode {
        Nearest,
        Linear
    };

    /**
     * Create a texture from raw pixel data.
     * @param width Texture width in pixels
     * @param height Texture height in pixels
     * @param format Pixel format
     * @param data Raw pixel data (can be nullptr for uninitialized texture)
     */
    Texture(uint32_t width, uint32_t height, Format format, const void* data = nullptr);

    ~Texture();

    // Non-copyable
    Texture(const Texture&) = delete;
    Texture& operator=(const Texture&) = delete;

    /**
     * Bind texture to a texture unit.
     * @param unit Texture unit (0-15)
     */
    void bind(uint32_t unit = 0) const;

    /**
     * Unbind texture.
     */
    void unbind() const;

    /**
     * Set texture wrap mode.
     */
    void setWrapMode(WrapMode mode);

    /**
     * Set texture filter mode.
     */
    void setFilterMode(FilterMode minFilter, FilterMode magFilter);

    /**
     * Update texture data.
     */
    void updateData(const void* data);

    /**
     * Get texture width.
     */
    uint32_t getWidth() const { return m_width; }

    /**
     * Get texture height.
     */
    uint32_t getHeight() const { return m_height; }

    /**
     * Get OpenGL texture handle.
     */
    uint32_t getHandle() const { return m_handle; }

    /**
     * Get texture format.
     */
    Format getFormat() const { return m_format; }

    /**
     * Check if texture is valid.
     */
    bool isValid() const { return m_handle != 0; }

private:
    uint32_t m_handle;
    uint32_t m_width;
    uint32_t m_height;
    Format m_format;

    uint32_t getGLFormat() const;
    uint32_t getGLInternalFormat() const;
    uint32_t getGLType() const;
};

using TexturePtr = std::shared_ptr<Texture>;

/**
 * Framebuffer object for off-screen rendering.
 */
class Framebuffer {
public:
    /**
     * Create a framebuffer with color and optional depth attachment.
     * @param width Framebuffer width
     * @param height Framebuffer height
     * @param hasDepth Whether to include depth attachment
     */
    Framebuffer(uint32_t width, uint32_t height, bool hasDepth = true);

    ~Framebuffer();

    // Non-copyable
    Framebuffer(const Framebuffer&) = delete;
    Framebuffer& operator=(const Framebuffer&) = delete;

    /**
     * Bind framebuffer for rendering.
     */
    void bind() const;

    /**
     * Unbind framebuffer (render to screen).
     */
    void unbind() const;

    /**
     * Get color texture attachment.
     */
    const TexturePtr& getColorTexture() const { return m_colorTexture; }

    /**
     * Get depth texture attachment (if present).
     */
    const TexturePtr& getDepthTexture() const { return m_depthTexture; }

    /**
     * Get OpenGL framebuffer handle.
     */
    uint32_t getHandle() const { return m_handle; }

    /**
     * Check if framebuffer is valid.
     */
    bool isValid() const { return m_handle != 0; }

private:
    uint32_t m_handle;
    TexturePtr m_colorTexture;
    TexturePtr m_depthTexture;
};

using FramebufferPtr = std::shared_ptr<Framebuffer>;

/**
 * YUV420P texture wrapper for video frame storage.
 * 
 * Stores Y, U, V planes as separate OpenGL textures (GL_RED, normalized 8-bit).
 * Allows direct GPU upload from FFmpeg YUV420P frames without CPU color conversion.
 * 
 * Fragment shader applies BT.709 YUV→RGB matrix for color space conversion on GPU.
 * 
 * Why YUV on GPU?
 * - YUV420P is the FFmpeg native codec output (no CPU conversion cost)
 * - Shader conversion is GPU-parallelized (1M+ pixels simultaneously)
 * - Frees CPU for other tasks during preview
 * - Matches Storyline architecture (YUV until final export)
 * 
 * Memory layout (for 1920x1080):
 * - Y texture:  1920×1080 (2.07 MB)
 * - U texture:  960×540   (518 KB)
 * - V texture:  960×540   (518 KB)
 * - Total:      ~3.1 MB per frame (vs 5.9 MB for RGB24 CPU format)
 */
class YUVTexture {
public:
    /**
     * Create YUV texture set for a given video frame size.
     * 
     * @param width Video width (full resolution Y plane)
     * @param height Video height (full resolution Y plane)
     */
    YUVTexture(uint32_t width, uint32_t height);

    ~YUVTexture();

    // Non-copyable
    YUVTexture(const YUVTexture&) = delete;
    YUVTexture& operator=(const YUVTexture&) = delete;

    /**
     * Upload YUV420P planes from FFmpeg frame.
     * 
     * Input: YUV420P data (separate Y, U, V planes at specific resolutions)
     * - Y plane: width × height bytes
     * - U plane: (width/2) × (height/2) bytes
     * - V plane: (width/2) × (height/2) bytes
     * 
     * Each plane is uploaded to separate GL_RED texture for shader access.
     * 
     * @param yData Y plane data (width × height bytes)
     * @param uData U plane data ((width/2) × (height/2) bytes)
     * @param vData V plane data ((width/2) × (height/2) bytes)
     * @return true if upload succeeded
     */
    bool updateFromYUV420P(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData);

    /**
     * Bind all three planes to texture units for shader access.
     * 
     * Usage in shader:
     *   uniform sampler2D texY;   // Texture unit 0
     *   uniform sampler2D texU;   // Texture unit 1
     *   uniform sampler2D texV;   // Texture unit 2
     *   
     *   float y = texture(texY, texCoord).r;
     *   float u = texture(texU, texCoord).r;
     *   float v = texture(texV, texCoord).r;
     *   // Apply YUV→RGB matrix...
     */
    void bind() const;

    /**
     * Unbind all planes.
     */
    void unbind() const;

    /**
     * Get OpenGL handle of Y plane texture.
     */
    uint32_t getYTextureHandle() const { return m_yHandle; }

    /**
     * Get OpenGL handle of U plane texture.
     */
    uint32_t getUTextureHandle() const { return m_uHandle; }

    /**
     * Get OpenGL handle of V plane texture.
     */
    uint32_t getVTextureHandle() const { return m_vHandle; }

    /**
     * Check if textures are valid.
     */
    bool isValid() const { return m_yHandle != 0 && m_uHandle != 0 && m_vHandle != 0; }

    /**
     * Get Y plane width (full resolution).
     */
    uint32_t getWidth() const { return m_width; }

    /**
     * Get Y plane height (full resolution).
     */
    uint32_t getHeight() const { return m_height; }

private:
    uint32_t m_width;      // Full resolution
    uint32_t m_height;     // Full resolution

    uint32_t m_yHandle;    // Y plane texture (width × height)
    uint32_t m_uHandle;    // U plane texture ((width/2) × (height/2))
    uint32_t m_vHandle;    // V plane texture ((width/2) × (height/2))
};

using YUVTexturePtr = std::shared_ptr<YUVTexture>;

} // namespace VideoEngine::GPU

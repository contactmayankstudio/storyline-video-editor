#pragma once

#include <memory>
#include <vector>
#include <unordered_map>
#include <cstdint>
#include <glm/glm.hpp>

// Forward declares
namespace VideoEngine {
    class RenderGraph;
    class Clip;
    using TimeMs = int64_t;
    struct RenderItem;
    struct TransitionNode;
    struct TextOverlay;
}

namespace VideoEngine::GPU {

class GLContext;
class ShaderProgram;
class Texture;
class YUVTexture;
class Framebuffer;
class QuadMesh;
class TextRenderer;

using GLContextPtr = std::shared_ptr<GLContext>;
using ShaderProgramPtr = std::shared_ptr<ShaderProgram>;
using TexturePtr = std::shared_ptr<Texture>;
using YUVTexturePtr = std::shared_ptr<YUVTexture>;
using FramebufferPtr = std::shared_ptr<Framebuffer>;
using QuadMeshPtr = std::shared_ptr<QuadMesh>;
using TextRendererPtr = std::shared_ptr<TextRenderer>;

/**
 * GPU-based real-time preview renderer using OpenGL.
 * Renders RenderItems as textured quads with layer ordering and opacity effects.
 * 
 * This is a PREVIEW ONLY renderer - not for final export.
 * Uses OpenGL 3.3 core profile.
 */
class PreviewRenderer {
public:
    enum class RenderMode {
        Headless,    // Off-screen rendering to FBO
        Windowed     // On-screen rendering to window
    };

    /**
     * Create a preview renderer.
     * @param width Output framebuffer width
     * @param height Output framebuffer height
     * @param mode Rendering mode (headless or windowed)
     * @param debugMode Enable OpenGL debug output
     */
    PreviewRenderer(uint32_t width, uint32_t height, RenderMode mode = RenderMode::Headless, bool debugMode = false);

    ~PreviewRenderer();

    // Non-copyable
    PreviewRenderer(const PreviewRenderer&) = delete;
    PreviewRenderer& operator=(const PreviewRenderer&) = delete;

    /**
     * Render a frame given active clips and text overlays at timeMs.
     * Used by export pipeline.
     */
    void renderFrame(
        const std::vector<std::shared_ptr<VideoEngine::Clip>>& clips,
        const std::vector<VideoEngine::TextOverlay>& textOverlays,
        TimeMs timeMs);

    /**
     * Render a frame at the given timeline position (legacy RenderGraph path).
     */
    void renderFrame(const RenderGraph& renderGraph, TimeMs timeMs);

    /**
     * Get the framebuffer texture (for reading back pixels or displaying).
     */
    const TexturePtr& getFramebufferTexture() const;

    /**
     * Cache a YUV texture for a clip.
     * Called by PreviewController after decoding clip frames.
     * 
     * @param clipId Unique clip identifier
     * @param yuvTexture YUV texture object (already uploaded to GPU)
     */
    void cacheYUVTexture(uint32_t clipId, const YUVTexturePtr& yuvTexture) {
        m_yuvTextureCache[clipId] = yuvTexture;
    }

    /**
     * Clear YUV texture cache.
     */
    void clearYUVTextureCache() {
        m_yuvTextureCache.clear();
    }

    /**
     * Get cached YUV texture for a clip.
     * @param clipId Unique clip identifier
     * @return YUV texture or nullptr if not cached
     */
    YUVTexturePtr getYUVTexture(uint32_t clipId) const {
        auto it = m_yuvTextureCache.find(clipId);
        return (it != m_yuvTextureCache.end()) ? it->second : nullptr;
    }

    /**
     * Get text renderer instance.
     * Can be used to load font atlas or render text directly.
     */
    TextRendererPtr getTextRenderer() const {
        return m_textRenderer;
    }

    /**
     * Get output framebuffer height.
     */
    uint32_t getHeight() const { return m_height; }

    /**
     * Check if renderer is valid and ready to use.
     */
    bool isValid() const { return m_isValid; }

    /**
     * Clear framebuffer to background color.
     * @param r Red (0-1)
     * @param g Green (0-1)
     * @param b Blue (0-1)
     * @param a Alpha (0-1)
     */
    void setClearColor(float r, float g, float b, float a);

    /**
     * Swap buffers (for windowed mode).
     */
    void swapBuffers();

private:
    uint32_t m_width;
    uint32_t m_height;
    RenderMode m_mode;
    bool m_debugMode;
    bool m_isValid;

    GLContextPtr m_glContext;
    FramebufferPtr m_framebuffer;
    ShaderProgramPtr m_shaderProgram;          // Single-clip YUV→RGB shader
    ShaderProgramPtr m_crossfadeShaderProgram; // Crossfade transition shader
    QuadMeshPtr m_quadMesh;
    TextRendererPtr m_textRenderer;            // Text overlay rendering (NEW)

    glm::vec4 m_clearColor;

    // YUV texture cache (per clip ID -> YUVTexture)
    std::unordered_map<uint32_t, YUVTexturePtr> m_yuvTextureCache;

    // Placeholder texture cache (legacy)
    std::unordered_map<std::string, TexturePtr> m_textureCache;

    void initializeShaders();
    void renderRenderItems(const std::vector<VideoEngine::RenderItem>& items, TimeMs timeMs, const RenderGraph& renderGraph);
    void renderSingleClip(const VideoEngine::RenderItem& item);
    void renderCrossfadeTransition(const VideoEngine::TransitionNode& trans, TimeMs timeMs);
    void renderFadeTransition(const VideoEngine::TransitionNode& trans, TimeMs timeMs);
    void renderWipeTransition(const VideoEngine::TransitionNode& trans, TimeMs timeMs);
    void renderTextOverlays(const std::vector<VideoEngine::TextOverlay>& overlays, TimeMs timeMs);  // NEW
    void setupProjectionMatrix();
};

using PreviewRendererPtr = std::shared_ptr<PreviewRenderer>;

} // namespace VideoEngine::GPU

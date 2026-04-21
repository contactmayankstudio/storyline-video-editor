#pragma once

#include <memory>
#include <vector>
#include <string>
#include <cstdint>
#include <glm/glm.hpp>
#include <map>

#include "../text_overlay.h"

namespace VideoEngine::GPU {

// Forward declares
class ShaderProgram;
class Texture;
class Framebuffer;

using ShaderProgramPtr = std::shared_ptr<ShaderProgram>;
using TexturePtr = std::shared_ptr<Texture>;

/**
 * GPU-based text rendering using bitmap font atlas.
 * 
 * Strategy:
 * - Load a pre-computed bitmap font (PNG atlas with glyph metrics)
 * - Each character is a quad with UV coordinates into the atlas
 * - Single draw call per text overlay using instancing or batch rendering
 * - No CPU text rasterization; GPU-only rendering
 * - Supports fast multi-text rendering (many overlays per frame)
 */
class TextRenderer {
public:
    /**
     * Glyph metadata from the font atlas.
     * Generated during font loading.
     */
    struct GlyphInfo {
        float uvX0, uvY0;        // Top-left UV in atlas (normalized 0..1)
        float uvX1, uvY1;        // Bottom-right UV
        float advanceX;          // Horizontal advance to next glyph
        float advanceY;          // Vertical advance (usually 0)
        float bearingX, bearingY; // Offset from baseline
        float width, height;     // Glyph dimensions in normalized units (0..1)
        char32_t codepoint;      // Unicode codepoint this glyph represents
    };

    /**
     * Create a text renderer.
     * Must call loadFontAtlas() before rendering.
     */
    TextRenderer();

    ~TextRenderer();

    // Non-copyable
    TextRenderer(const TextRenderer&) = delete;
    TextRenderer& operator=(const TextRenderer&) = delete;

    /**
     * Load a bitmap font atlas and glyph metrics.
     * @param atlasTexturePath Path to bitmap font PNG (e.g., "assets/fonts/roboto_atlas.png")
     * @param metricsPath Path to glyph metrics file (e.g., "assets/fonts/roboto.fnt")
     *                    Format: Simple text with lines like "char=A uvX0=0.1 uvY0=0.2 ..."
     *                    Or generated programmatically via FreeType2 + custom metrics
     * @throws std::runtime_error if files cannot be loaded
     */
    void loadFontAtlas(const std::string& atlasTexturePath, const std::string& metricsPath);

    /**
     * Render a single text overlay on top of the current framebuffer.
     * Called per active text overlay during frame composition.
     * 
     * @param overlay The TextOverlay with text, position, styling
     * @param frameWidth Framebuffer width (pixels)
     * @param frameHeight Framebuffer height (pixels)
     * 
     * Applies transformations:
     * - Position (x, y) - normalized 0..1 coordinates
     * - Scale (overlay.scale)
     * - Rotation (overlay.rotation in degrees)
     * - Color (overlay.color RGBA)
     * - Opacity (overlay.opacity)
     * - Fade (computed from startTime, endTime, fadeInMs, fadeOutMs)
     */
    void renderTextOverlay(const TextOverlay& overlay, uint32_t frameWidth, uint32_t frameHeight, int64_t currentTimeMs);

    /**
     * Render multiple text overlays (batch rendering for efficiency).
     * @param overlays Vector of active TextOverlay objects (typically from getActiveTextOverlaysAtTime)
     * @param frameWidth Framebuffer width (pixels)
     * @param frameHeight Framebuffer height (pixels)
     * @param currentTimeMs Current timeline position (for fade calculations)
     */
    void renderTextOverlays(const std::vector<TextOverlay>& overlays, uint32_t frameWidth, uint32_t frameHeight, int64_t currentTimeMs);

    /**
     * Check if font is loaded.
     */
    bool isFontLoaded() const { return m_fontAtlasTexture != nullptr; }

    /**
     * Get font atlas texture (for debugging).
     */
    const TexturePtr& getFontAtlasTexture() const { return m_fontAtlasTexture; }

private:
    // Font management
    TexturePtr m_fontAtlasTexture;              // GPU texture of bitmap font
    std::map<char32_t, GlyphInfo> m_glyphs;    // Unicode → GlyphInfo
    float m_fontSizeEmPixels = 64.0f;          // Original font size (em pixels)
    float m_atlasWidth = 1024.0f, m_atlasHeight = 1024.0f;  // Atlas dimensions

    // Rendering resources
    ShaderProgramPtr m_textShaderProgram;       // Vertex + fragment shaders for text
    uint32_t m_textVAO = 0;                    // VAO for text quads
    uint32_t m_textVBO = 0;                    // VBO for quad vertices
    uint32_t m_textInstanceVBO = 0;            // (Optional) instance buffer for batch rendering

    /**
     * Build vertex data for a single character quad.
     * Returns 6 vertices (2 triangles) for the quad.
     */
    struct CharQuad {
        glm::vec2 p0, p1, p2;   // Triangle 1 positions
        glm::vec2 p3, p4, p5;   // Triangle 2 positions
        glm::vec2 uv0, uv1, uv2; // Triangle 1 UVs
        glm::vec2 uv3, uv4, uv5; // Triangle 2 UVs
    };

    /**
     * Calculate effective opacity considering fade-in/out.
     */
    float calculateEffectiveOpacity(const TextOverlay& overlay, int64_t currentTimeMs) const;

    /**
     * Initialize shader program for text rendering.
     */
    void initializeShaders();

    /**
     * Parse glyph metrics file (simple format or FNT binary).
     */
    void parseGlyphMetrics(const std::string& metricsPath);

    /**
     * Build character quads for a text string with given transformations.
     * Returns vertex data ready to upload to GPU.
     */
    std::vector<float> buildTextMesh(
        const std::string& text,
        float posX, float posY,      // Normalized position
        float scale,
        float rotation,                 // Degrees
        glm::vec4 color,               // RGBA
        uint32_t frameWidth, uint32_t frameHeight
    );

    /**
     * Render pre-built mesh using shader.
     */
    void renderMesh(const std::vector<float>& vertexData, const glm::mat4& mvp, glm::vec4 color);
};

using TextRendererPtr = std::shared_ptr<TextRenderer>;

} // namespace VideoEngine::GPU

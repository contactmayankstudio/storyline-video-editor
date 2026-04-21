#include "text_renderer.h"
#include "shader_program.h"
#include "texture.h"
#include <glm/gtc/matrix_transform.hpp>
#include <iostream>
#include <fstream>
#include <sstream>
#include <cmath>
#include <algorithm>

namespace VideoEngine::GPU {

TextRenderer::TextRenderer()
    : m_fontSizeEmPixels(64.0f),
      m_atlasWidth(1024.0f),
      m_atlasHeight(1024.0f) {
    // Initialize shader program lazily on first use
}

TextRenderer::~TextRenderer() {
    if (m_textVAO != 0) {
        glDeleteVertexArrays(1, &m_textVAO);
    }
    if (m_textVBO != 0) {
        glDeleteBuffers(1, &m_textVBO);
    }
    if (m_textInstanceVBO != 0) {
        glDeleteBuffers(1, &m_textInstanceVBO);
    }
}

void TextRenderer::loadFontAtlas(const std::string& atlasTexturePath, const std::string& metricsPath) {
    try {
        std::cout << "[TextRenderer] Loading font atlas from: " << atlasTexturePath << "\n";

        // Try to load real PNG using stb_image if available
        bool loaded = false;
#if defined(STB_IMAGE_IMPLEMENTATION) || defined(STBI_INCLUDE_STB_IMAGE_H)
        int w = 0, h = 0, channels = 0;
        unsigned char* data = stbi_load(atlasTexturePath.c_str(), &w, &h, &channels, 4);
        if (data && w > 0 && h > 0) {
            m_fontAtlasTexture = std::make_shared<Texture>(w, h, Texture::Format::RGBA8, data);
            m_atlasWidth  = static_cast<float>(w);
            m_atlasHeight = static_cast<float>(h);
            stbi_image_free(data);
            loaded = true;
            std::cout << "[TextRenderer] Loaded real font atlas: " << w << "x" << h << "\n";
        }
#endif
        if (!loaded) {
            // Fallback: white texture so text renders as solid color
            std::vector<uint8_t> white(1024 * 1024 * 4, 255);
            m_fontAtlasTexture = std::make_shared<Texture>(1024, 1024, Texture::Format::RGBA8, white.data());
            std::cout << "[TextRenderer] Font atlas not found, using white fallback\n";
        }

        parseGlyphMetrics(metricsPath);
        initializeShaders();

        std::cout << "[TextRenderer] Font atlas ready. Glyphs: " << m_glyphs.size() << "\n";
    } catch (const std::exception& e) {
        throw std::runtime_error(std::string("Failed to load font atlas: ") + e.what());
    }
}

void TextRenderer::parseGlyphMetrics(const std::string& metricsPath) {
    // Try to parse real .fnt file (Bitmap Font Generator format)
    std::ifstream fntFile(metricsPath);
    if (fntFile.is_open()) {
        std::string line;
        while (std::getline(fntFile, line)) {
            if (line.rfind("char ", 0) != 0) continue;
            // Parse: char id=X x=X y=X width=X height=X xoffset=X yoffset=X xadvance=X
            auto getVal = [&](const std::string& key) -> int {
                const std::string token = key + "=";
                const size_t pos = line.find(token);
                if (pos == std::string::npos) return 0;
                return std::stoi(line.substr(pos + token.size()));
            };
            const int id       = getVal("id");
            const int x        = getVal("x");
            const int y        = getVal("y");
            const int w        = getVal("width");
            const int h        = getVal("height");
            const int xoff     = getVal("xoffset");
            const int yoff     = getVal("yoffset");
            const int xadvance = getVal("xadvance");
            if (w <= 0 || h <= 0) continue;
            GlyphInfo g;
            g.codepoint = static_cast<char32_t>(id);
            g.uvX0      = x / m_atlasWidth;
            g.uvY0      = y / m_atlasHeight;
            g.uvX1      = (x + w) / m_atlasWidth;
            g.uvY1      = (y + h) / m_atlasHeight;
            g.width     = w / m_atlasWidth;
            g.height    = h / m_atlasHeight;
            g.bearingX  = xoff / m_atlasWidth;
            g.bearingY  = yoff / m_atlasHeight;
            g.advanceX  = xadvance / m_atlasWidth;
            g.advanceY  = 0.0f;
            m_glyphs[g.codepoint] = g;
        }
        std::cout << "[TextRenderer] Parsed " << m_glyphs.size() << " glyphs from .fnt\n";
        return;
    }

    // Fallback: uniform grid for ASCII 32-126
    for (char32_t cp = 32; cp < 127; ++cp) {
        const int col = (cp - 32) % 16;
        const int row = (cp - 32) / 16;
        const float cw = 1.0f / 16.0f;
        const float ch = 1.0f / 8.0f;
        GlyphInfo g;
        g.codepoint = cp;
        g.uvX0 = col * cw;       g.uvY0 = row * ch;
        g.uvX1 = g.uvX0 + cw * 0.9f; g.uvY1 = g.uvY0 + ch * 0.9f;
        g.width = 0.05f; g.height = 0.1f;
        g.advanceX = 0.06f; g.advanceY = 0.0f;
        g.bearingX = 0.0f;  g.bearingY = 0.08f;
        m_glyphs[cp] = g;
    }
    std::cout << "[TextRenderer] Using fallback ASCII glyph grid\n";
}

void TextRenderer::initializeShaders() {
    if (m_textShaderProgram) {
        return;  // Already initialized
    }

    // Vertex shader: position + texture coordinates
    const std::string vertexShaderSource = R"glsl(
        #version 300 es
        precision highp float;

        layout(location = 0) in vec2 position;
        layout(location = 1) in vec2 texCoord;

        out vec2 fragTexCoord;

        uniform mat4 mvp;  // Model-View-Projection matrix

        void main() {
            fragTexCoord = texCoord;
            gl_Position = mvp * vec4(position, 0.0, 1.0);
        }
    )glsl";

    // Fragment shader: sample font texture and apply color
    const std::string fragmentShaderSource = R"glsl(
        #version 300 es
        precision highp float;

        in vec2 fragTexCoord;
        out vec4 FragColor;

        uniform sampler2D fontAtlas;
        uniform vec4 textColor;  // RGBA color + opacity

        void main() {
            // Sample font atlas (should be grayscale or RGBA)
            vec4 glyphTexel = texture(fontAtlas, fragTexCoord);
            
            // Use alpha channel as coverage
            float coverage = glyphTexel.a;
            
            // Apply text color with coverage
            FragColor = textColor * coverage;
            
            // Optional: use luminance if texture is RGB
            if (coverage < 0.1) {
                coverage = dot(glyphTexel.rgb, vec3(0.299, 0.587, 0.114));
                FragColor = textColor * coverage;
            }
        }
    )glsl";

    try {
        m_textShaderProgram = std::make_shared<ShaderProgram>(vertexShaderSource, fragmentShaderSource);
        std::cout << "[TextRenderer] Text shader program compiled successfully\n";
    } catch (const std::exception& e) {
        throw std::runtime_error(std::string("Failed to compile text shaders: ") + e.what());
    }
}

float TextRenderer::calculateEffectiveOpacity(const TextOverlay& overlay, int64_t currentTimeMs) const {
    if (currentTimeMs < overlay.startTime) {
        return 0.0f;  // Not yet visible
    }

    float opacity = overlay.opacity;

    // Apply fade-in
    if (overlay.fadeInMs > 0 && currentTimeMs < overlay.startTime + overlay.fadeInMs) {
        float fadeProgress = static_cast<float>(currentTimeMs - overlay.startTime) / overlay.fadeInMs;
        opacity *= fadeProgress;
    }

    // Apply fade-out
    if (overlay.fadeOutMs > 0 && overlay.endTime > 0) {
        int64_t fadeOutStart = overlay.endTime - overlay.fadeOutMs;
        if (currentTimeMs > fadeOutStart) {
            float fadeProgress = 1.0f - (static_cast<float>(currentTimeMs - fadeOutStart) / overlay.fadeOutMs);
            opacity *= std::max(0.0f, fadeProgress);
        }
    }

    return std::clamp(opacity, 0.0f, 1.0f);
}

std::vector<float> TextRenderer::buildTextMesh(
    const std::string& text,
    float posX, float posY,
    float scale,
    float rotation,
    glm::vec4 color,
    uint32_t frameWidth, uint32_t frameHeight
) {
    std::vector<float> vertexData;
    
    if (text.empty() || m_glyphs.empty()) {
        return vertexData;
    }

    // Rotation matrix
    float cosR = std::cos(glm::radians(rotation));
    float sinR = std::sin(glm::radians(rotation));

    // Current position (normalized screen space)
    float currentX = posX;
    float currentY = posY;

    // Build quads for each character
    for (char c : text) {
        char32_t codepoint = static_cast<char32_t>(static_cast<unsigned char>(c));
        
        auto it = m_glyphs.find(codepoint);
        if (it == m_glyphs.end()) {
            codepoint = '?';  // Fallback to question mark
            it = m_glyphs.find(codepoint);
            if (it == m_glyphs.end()) {
                continue;  // Skip if no glyph
            }
        }

        const GlyphInfo& glyph = it->second;

        // Quad dimensions (scaled)
        float w = glyph.width * scale;
        float h = glyph.height * scale;

        // Positions (before rotation)
        glm::vec2 p0(currentX, currentY);
        glm::vec2 p1(currentX + w, currentY);
        glm::vec2 p2(currentX, currentY + h);
        glm::vec2 p3(currentX + w, currentY + h);

        // Apply rotation around center
        glm::vec2 center(currentX + w / 2.0f, currentY + h / 2.0f);
        auto rotate = [cosR, sinR, center](glm::vec2 p) {
            glm::vec2 rel = p - center;
            return center + glm::vec2(
                rel.x * cosR - rel.y * sinR,
                rel.x * sinR + rel.y * cosR
            );
        };

        p0 = rotate(p0);
        p1 = rotate(p1);
        p2 = rotate(p2);
        p3 = rotate(p3);

        // Triangle 1: p0, p1, p2
        vertexData.push_back(p0.x);
        vertexData.push_back(p0.y);
        vertexData.push_back(glyph.uvX0);
        vertexData.push_back(glyph.uvY0);

        vertexData.push_back(p1.x);
        vertexData.push_back(p1.y);
        vertexData.push_back(glyph.uvX1);
        vertexData.push_back(glyph.uvY0);

        vertexData.push_back(p2.x);
        vertexData.push_back(p2.y);
        vertexData.push_back(glyph.uvX0);
        vertexData.push_back(glyph.uvY1);

        // Triangle 2: p1, p3, p2
        vertexData.push_back(p1.x);
        vertexData.push_back(p1.y);
        vertexData.push_back(glyph.uvX1);
        vertexData.push_back(glyph.uvY0);

        vertexData.push_back(p3.x);
        vertexData.push_back(p3.y);
        vertexData.push_back(glyph.uvX1);
        vertexData.push_back(glyph.uvY1);

        vertexData.push_back(p2.x);
        vertexData.push_back(p2.y);
        vertexData.push_back(glyph.uvX0);
        vertexData.push_back(glyph.uvY1);

        // Advance to next character
        currentX += glyph.advanceX * scale;
    }

    return vertexData;
}

void TextRenderer::renderMesh(const std::vector<float>& vertexData, const glm::mat4& mvp, glm::vec4 color) {
    if (vertexData.empty() || !m_textShaderProgram || !m_fontAtlasTexture) {
        return;
    }

    // Create VAO if needed
    if (m_textVAO == 0) {
        glGenVertexArrays(1, &m_textVAO);
        glGenBuffers(1, &m_textVBO);
    }

    // Upload vertex data
    glBindVertexArray(m_textVAO);
    glBindBuffer(GL_ARRAY_BUFFER, m_textVBO);
    glBufferData(GL_ARRAY_BUFFER, vertexData.size() * sizeof(float), vertexData.data(), GL_DYNAMIC_DRAW);

    // Vertex layout: vec2 position, vec2 texCoord (4 floats per vertex)
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);

    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)(2 * sizeof(float)));

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindVertexArray(0);

    // Render
    m_textShaderProgram->use();
    m_textShaderProgram->setUniformMatrix4fv("mvp", glm::value_ptr(mvp));
    m_textShaderProgram->setUniform4f("textColor", color.r, color.g, color.b, color.a);

    m_fontAtlasTexture->bind(0);
    m_textShaderProgram->setUniform1i("fontAtlas", 0);

    glBindVertexArray(m_textVAO);
    glDrawArrays(GL_TRIANGLES, 0, vertexData.size() / 4);  // 4 floats per vertex
    glBindVertexArray(0);
}

void TextRenderer::renderTextOverlay(const TextOverlay& overlay, uint32_t frameWidth, uint32_t frameHeight, int64_t currentTimeMs) {
    if (!overlay.enabled || overlay.text.empty()) {
        return;
    }

    // Calculate effective opacity (including fade)
    float effectiveOpacity = calculateEffectiveOpacity(overlay, currentTimeMs);
    if (effectiveOpacity <= 0.0f) {
        return;
    }

    // Convert RGBA color and apply opacity
    uint32_t rgba = overlay.color;
    float r = ((rgba >> 24) & 0xFF) / 255.0f;
    float g = ((rgba >> 16) & 0xFF) / 255.0f;
    float b = ((rgba >> 8) & 0xFF) / 255.0f;
    float a = (rgba & 0xFF) / 255.0f * effectiveOpacity;
    glm::vec4 color(r, g, b, a);

    // Build mesh
    std::vector<float> mesh = buildTextMesh(
        overlay.text,
        overlay.x,
        overlay.y,
        overlay.scale,
        overlay.rotation,
        color,
        frameWidth,
        frameHeight
    );

    // Orthographic projection (screen space)
    glm::mat4 projection = glm::ortho(0.0f, 1.0f, 1.0f, 0.0f, -1.0f, 1.0f);
    glm::mat4 mvp = projection * glm::mat4(1.0f);

    // Render mesh
    renderMesh(mesh, mvp, color);
}

void TextRenderer::renderTextOverlays(const std::vector<TextOverlay>& overlays, uint32_t frameWidth, uint32_t frameHeight, int64_t currentTimeMs) {
    // Enable blending for text
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    // Render each overlay (sorted by zOrder by caller)
    for (const TextOverlay& overlay : overlays) {
        renderTextOverlay(overlay, frameWidth, frameHeight, currentTimeMs);
    }

    // Restore blending state (caller manages global blend state)
    glBlendFunc(GL_ONE, GL_ZERO);
    glDisable(GL_BLEND);
}

} // namespace VideoEngine::GPU

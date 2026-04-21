#include "preview_renderer.h"
#include "gl_context.h"
#include "shader_program.h"
#include "shader_loader.h"
#include "texture.h"
#include "quad_mesh.h"
#include "text_renderer.h"
#include "core/timeline.h"
#include "core/clip.h"
#include "text_overlay.h"
#include "engine/engine.h"
#include <glm/gtc/matrix_transform.hpp>
#include <glm/gtc/type_ptr.hpp>
#include <iostream>
#ifdef __ANDROID__
#include <GLES3/gl3.h>
#else
#include <GL/glew.h>
#endif

namespace VideoEngine::GPU {

// ============ PreviewRenderer Implementation ============

PreviewRenderer::PreviewRenderer(uint32_t width, uint32_t height, RenderMode mode, bool debugMode)
    : m_width(width)
    , m_height(height)
    , m_mode(mode)
    , m_debugMode(debugMode)
    , m_isValid(false)
    , m_clearColor(0.0f, 0.0f, 0.0f, 1.0f)
{
    try {
        // Create OpenGL context
        GLContext::RenderMode glRenderMode = (mode == RenderMode::Headless)
            ? GLContext::RenderMode::Headless
            : GLContext::RenderMode::Windowed;

        m_glContext = std::make_shared<GLContext>(width, height, glRenderMode, debugMode);
        if (!m_glContext->isValid()) {
            throw std::runtime_error("Failed to create OpenGL context");
        }

        m_glContext->makeCurrent();

        // Enable depth testing and blending
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // Create framebuffer for off-screen rendering
        m_framebuffer = std::make_shared<Framebuffer>(width, height, true);
        if (!m_framebuffer->isValid()) {
            throw std::runtime_error("Failed to create framebuffer");
        }

        // Initialize shaders
        initializeShaders();
        if (!m_shaderProgram || !m_shaderProgram->isValid()) {
            throw std::runtime_error("Failed to initialize shaders");
        }

        // Create quad mesh for rendering
        m_quadMesh = std::make_shared<QuadMesh>();

        // Initialize text renderer for text overlays
        m_textRenderer = std::make_shared<TextRenderer>();
        
        // Load font atlas from assets (gracefully skipped if file not present)
        try {
            m_textRenderer->loadFontAtlas("assets/fonts/roboto_atlas.png", "assets/fonts/roboto.fnt");
        } catch (const std::exception& e) {
            std::cerr << "[PreviewRenderer] Warning: Failed to load font atlas: " << e.what() << "\n";
            // Continue with default glyph metrics
        }

        setupProjectionMatrix();

        m_isValid = true;
        std::cout << "[PreviewRenderer] Initialized successfully (" 
                  << width << "x" << height << ", "
                  << (mode == RenderMode::Headless ? "headless" : "windowed")
                  << ")\n";

    } catch (const std::exception& e) {
        std::cerr << "[PreviewRenderer] Initialization failed: " << e.what() << "\n";
        m_isValid = false;
    }
}

PreviewRenderer::~PreviewRenderer() {
    if (m_glContext) {
        m_glContext->makeCurrent();
    }
    m_quadMesh.reset();
    m_shaderProgram.reset();
    m_framebuffer.reset();
    m_textureCache.clear();
    m_glContext.reset();
}

void PreviewRenderer::initializeShaders() {
    try {
        // Single-clip shader (YUV→RGB conversion with opacity)
        m_shaderProgram = ShaderProgram::createFromFiles("fullscreen.vert", "yuv_to_rgb.frag");
        std::cout << "[PreviewRenderer] Single-clip YUV shader loaded\n";
        
        // Crossfade transition shader
        m_crossfadeShaderProgram = ShaderProgram::createFromFiles("fullscreen.vert", "crossfade.frag");
        std::cout << "[PreviewRenderer] Crossfade shader loaded\n";
    } catch (const std::exception& e) {
        std::cerr << "[PreviewRenderer] Failed to load shaders from files: " << e.what() << "\n";
        throw;
    }
}

void PreviewRenderer::setupProjectionMatrix() {
    if (!m_shaderProgram) return;

    m_glContext->makeCurrent();
    m_shaderProgram->use();

    // Orthographic projection: map texture coordinates to NDC space
    float aspect = static_cast<float>(m_width) / static_cast<float>(m_height);
    glm::mat4 projection = glm::ortho(-aspect, aspect, -1.0f, 1.0f, 0.1f, 100.0f);

    m_shaderProgram->setUniformMatrix4fv("projection", glm::value_ptr(projection));

    // View matrix (identity)
    glm::mat4 view = glm::mat4(1.0f);
    m_shaderProgram->setUniformMatrix4fv("view", glm::value_ptr(view));

    // Set default sampler
    m_shaderProgram->setUniform1i("tex0", 0);
}

void PreviewRenderer::renderFrame(
    const std::vector<std::shared_ptr<VideoEngine::Clip>>& clips,
    const std::vector<VideoEngine::TextOverlay>& textOverlays,
    TimeMs timeMs) {
    if (!m_isValid) return;
    try {
        m_glContext->makeCurrent();
        m_framebuffer->bind();
        glClearColor(m_clearColor.r, m_clearColor.g, m_clearColor.b, m_clearColor.a);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glViewport(0, 0, m_width, m_height);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // Render clips back-to-front (sorted by zOrder from getActiveClipsAtTime)
        for (const auto& clip : clips) {
            if (!clip) continue;
            const uint32_t clipId = clip->getId();
            auto it = m_yuvTextureCache.find(clipId);
            if (it == m_yuvTextureCache.end() || !it->second || !it->second->isValid()) continue;
            auto& yuv = it->second;

            m_shaderProgram->use();
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, yuv->getYTextureHandle());
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, yuv->getUTextureHandle());
            glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, yuv->getVTextureHandle());
            m_shaderProgram->setUniform1i("texY", 0);
            m_shaderProgram->setUniform1i("texU", 1);
            m_shaderProgram->setUniform1i("texV", 2);
            m_shaderProgram->setUniform1f("opacity", clip->getProperties().opacity);

            const auto& fx = clip->getEffects();
            m_shaderProgram->setUniform1i("effectsEnabled", fx.enabled ? 1 : 0);
            m_shaderProgram->setUniform1f("uBrightness", fx.brightness);
            m_shaderProgram->setUniform1f("uContrast",   fx.contrast);
            m_shaderProgram->setUniform1f("uSaturation", fx.saturation);
            m_shaderProgram->setUniform1i("uLutEnabled", 0);

            const auto& ck = clip->getChromaKey();
            m_shaderProgram->setUniform1i("uChromaEnabled",   ck.enabled ? 1 : 0);
            m_shaderProgram->setUniform1f("uChromaSimilarity", ck.similarity);
            m_shaderProgram->setUniform1f("uChromaSmoothness", ck.smoothness);
            m_shaderProgram->setUniform1f("uChromaSpill",      ck.spill);
            if (ck.color == VideoEngine::Clip::ChromaKeyParams::KeyColor::Blue)
                m_shaderProgram->setUniform3f("uChromaKeyColor", 0.0f, 0.0f, 1.0f);
            else
                m_shaderProgram->setUniform3f("uChromaKeyColor", 0.0f, 1.0f, 0.0f);

            m_shaderProgram->setUniformMatrix4fv("model", glm::value_ptr(glm::mat4(1.0f)));
            m_quadMesh->render();
        }

        if (!textOverlays.empty())
            renderTextOverlays(textOverlays, timeMs);

        m_framebuffer->unbind();
        if (m_mode == RenderMode::Windowed)
            m_glContext->swapBuffers();
    } catch (const std::exception& e) {
        std::cerr << "[PreviewRenderer] Render error: " << e.what() << "\n";
    }
}

void PreviewRenderer::renderFrame(const RenderGraph& renderGraph, TimeMs timeMs) {
    if (!m_isValid) {
        return;
    }

    try {
        m_glContext->makeCurrent();
        m_framebuffer->bind();

        // Clear framebuffer
        glClearColor(m_clearColor.r, m_clearColor.g, m_clearColor.b, m_clearColor.a);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // Set viewport
        glViewport(0, 0, m_width, m_height);

        // Enable blending for compositing
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // Get visible render items at this time (sorted by layer back-to-front)
        auto visibleItems = renderGraph.getItemsAtTime(timeMs);

        if (!visibleItems.empty()) {
            renderRenderItems(visibleItems, timeMs, renderGraph);
        }

        // Text overlays are rendered via the clips+textOverlays overload in export path.
        // In preview path, text overlays are passed through native_preview.cpp JNI layer.

        m_framebuffer->unbind();

        // Swap buffers if windowed mode
        if (m_mode == RenderMode::Windowed) {
            m_glContext->swapBuffers();
        }

    } catch (const std::exception& e) {
        std::cerr << "[PreviewRenderer] Render error: " << e.what() << "\n";
    }
}

void PreviewRenderer::renderRenderItems(const std::vector<VideoEngine::RenderItem>& items, TimeMs timeMs, const RenderGraph& renderGraph) {
    m_quadMesh->bind();

    // Render each visible clip with its YUV texture
    for (const auto& item : items) {
        // Skip disabled items
        if (!item.enabled) {
            continue;
        }

        // Render single clip with YUV→RGB shader
        renderSingleClip(item);
    }

    // Check for active transitions and render them
    auto transitions = renderGraph.getTransitionsAtTime(timeMs);
    for (const auto& trans : transitions) {
        if (trans.type == VideoEngine::TransitionNode::Type::Crossfade) {
            renderCrossfadeTransition(trans, timeMs);
        } else if (trans.type == VideoEngine::TransitionNode::Type::Fade) {
            renderFadeTransition(trans, timeMs);
        } else if (trans.type == VideoEngine::TransitionNode::Type::Wipe) {
            renderWipeTransition(trans, timeMs);
        }
    }
}

void PreviewRenderer::renderSingleClip(const VideoEngine::RenderItem& item) {
    if (!item.clip) return;

    // Use single-clip YUV shader
    m_shaderProgram->use();

    // Get or create YUV texture for this clip
    uint32_t clipId = item.clip->getId();
    auto it = m_yuvTextureCache.find(clipId);
    if (it == m_yuvTextureCache.end()) {
        // For now, skip rendering if YUV texture not in cache
        // In full implementation, would decode clip and upload texture here
        std::cout << "[PreviewRenderer] YUV texture not cached for clip " << clipId << "\n";
        return;
    }

    auto yuvTexture = it->second;
    if (!yuvTexture || !yuvTexture->isValid()) {
        return;
    }

    // Bind Y, U, V planes to texture units 0, 1, 2
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, yuvTexture->getYTextureHandle());
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, yuvTexture->getUTextureHandle());
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, yuvTexture->getVTextureHandle());

    // Set YUV texture uniforms
    m_shaderProgram->setUniform1i("texY", 0);
    m_shaderProgram->setUniform1i("texU", 1);
    m_shaderProgram->setUniform1i("texV", 2);
    m_shaderProgram->setUniform1f("opacity", item.effectiveOpacity);

    // ============ GPU Effects Uniforms ============
    // Get effect parameters from clip
    const auto& effects = item.clip->getEffects();
    
    m_shaderProgram->setUniform1i("effectsEnabled", effects.enabled ? 1 : 0);
    m_shaderProgram->setUniform1f("uBrightness", effects.brightness);
    m_shaderProgram->setUniform1f("uContrast", effects.contrast);
    m_shaderProgram->setUniform1f("uSaturation", effects.saturation);
    
    // Set LUT texture if enabled
    if (effects.lutEnabled && effects.lutTextureId != 0) {
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_3D, effects.lutTextureId);
        m_shaderProgram->setUniform1i("lutTexture", 3);
        m_shaderProgram->setUniform1i("uLutEnabled", 1);
    } else {
    m_shaderProgram->setUniform1i("uLutEnabled", 0);
    }

    // ============ Chroma Key Uniforms ============
    const auto& chroma = item.clip->getChromaKey();
    m_shaderProgram->setUniform1i("uChromaEnabled", chroma.enabled ? 1 : 0);
    m_shaderProgram->setUniform1f("uChromaSimilarity", chroma.similarity);
    m_shaderProgram->setUniform1f("uChromaSmoothness", chroma.smoothness);
    m_shaderProgram->setUniform1f("uChromaSpill", chroma.spill);
    if (chroma.color == Clip::ChromaKeyParams::KeyColor::Blue) {
        m_shaderProgram->setUniform3f("uChromaKeyColor", 0.0f, 0.0f, 1.0f);
    } else {
        m_shaderProgram->setUniform3f("uChromaKeyColor", 0.0f, 1.0f, 0.0f);
    }

    // Build model matrix (fullscreen quad)
    glm::mat4 model = glm::mat4(1.0f);
    m_shaderProgram->setUniformMatrix4fv("model", glm::value_ptr(model));

    // Render quad
    m_quadMesh->render();

    std::cout << "[PreviewRenderer] Rendered clip: layer=" << item.layer
              << " opacity=" << item.effectiveOpacity
              << " clipId=" << clipId << "\n";
}

void PreviewRenderer::renderCrossfadeTransition(const VideoEngine::TransitionNode& trans, TimeMs timeMs) {
    if (!trans.fromClip || !trans.toClip) return;

    // Use crossfade shader
    m_crossfadeShaderProgram->use();

    // Get YUV textures for both clips
    uint32_t fromClipId = trans.fromClip->getId();
    uint32_t toClipId = trans.toClip->getId();

    auto itFrom = m_yuvTextureCache.find(fromClipId);
    auto itTo = m_yuvTextureCache.find(toClipId);

    if (itFrom == m_yuvTextureCache.end() || itTo == m_yuvTextureCache.end()) {
        std::cout << "[PreviewRenderer] YUV textures not cached for crossfade\n";
        return;
    }

    auto yuvFrom = itFrom->second;
    auto yuvTo = itTo->second;

    if (!yuvFrom || !yuvFrom->isValid() || !yuvTo || !yuvTo->isValid()) {
        return;
    }

    // Calculate transition progress [0..1]
    float progress = trans.evaluateProgress(timeMs);

    // Bind Clip A (fromClip) Y, U, V to texture units 0, 1, 2
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, yuvFrom->getYTextureHandle());
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, yuvFrom->getUTextureHandle());
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, yuvFrom->getVTextureHandle());

    // Bind Clip B (toClip) Y, U, V to texture units 3, 4, 5
    glActiveTexture(GL_TEXTURE3);
    glBindTexture(GL_TEXTURE_2D, yuvTo->getYTextureHandle());
    glActiveTexture(GL_TEXTURE4);
    glBindTexture(GL_TEXTURE_2D, yuvTo->getUTextureHandle());
    glActiveTexture(GL_TEXTURE5);
    glBindTexture(GL_TEXTURE_2D, yuvTo->getVTextureHandle());

    // Set uniforms for crossfade shader
    m_crossfadeShaderProgram->setUniform1i("texA_Y", 0);
    m_crossfadeShaderProgram->setUniform1i("texA_U", 1);
    m_crossfadeShaderProgram->setUniform1i("texA_V", 2);
    m_crossfadeShaderProgram->setUniform1i("texB_Y", 3);
    m_crossfadeShaderProgram->setUniform1i("texB_U", 4);
    m_crossfadeShaderProgram->setUniform1i("texB_V", 5);

    m_crossfadeShaderProgram->setUniform1f("opacityA", trans.fromClip->getProperties().opacity);
    m_crossfadeShaderProgram->setUniform1f("opacityB", trans.toClip->getProperties().opacity);
    m_crossfadeShaderProgram->setUniform1f("progress", progress);

    // Build model matrix
    glm::mat4 model = glm::mat4(1.0f);
    m_crossfadeShaderProgram->setUniformMatrix4fv("model", glm::value_ptr(model));

    // Render quad
    m_quadMesh->render();

    std::cout << "[PreviewRenderer] Rendered crossfade: layer=" << trans.layer
              << " progress=" << progress
              << " fromClip=" << fromClipId
              << " toClip=" << toClipId << "\n";
}


// Fade: outgoing fades to black, then incoming fades in from black
void PreviewRenderer::renderFadeTransition(const VideoEngine::TransitionNode& trans, TimeMs timeMs) {
    if (!trans.fromClip && !trans.toClip) return;
    const float progress = trans.evaluateProgress(timeMs);

    if (progress < 0.5f) {
        // First half: fade out fromClip
        if (!trans.fromClip) return;
        auto it = m_yuvTextureCache.find(trans.fromClip->getId());
        if (it == m_yuvTextureCache.end() || !it->second || !it->second->isValid()) return;
        m_shaderProgram->use();
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, it->second->getYTextureHandle());
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, it->second->getUTextureHandle());
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, it->second->getVTextureHandle());
        m_shaderProgram->setUniform1i("texY", 0);
        m_shaderProgram->setUniform1i("texU", 1);
        m_shaderProgram->setUniform1i("texV", 2);
        m_shaderProgram->setUniform1f("opacity", 1.0f - (progress * 2.0f));
        m_shaderProgram->setUniform1i("effectsEnabled", 0);
        m_shaderProgram->setUniform1i("uLutEnabled", 0);
        m_shaderProgram->setUniform1i("uChromaEnabled", 0);
        m_shaderProgram->setUniformMatrix4fv("model", glm::value_ptr(glm::mat4(1.0f)));
        m_quadMesh->render();
    } else {
        // Second half: fade in toClip
        if (!trans.toClip) return;
        auto it = m_yuvTextureCache.find(trans.toClip->getId());
        if (it == m_yuvTextureCache.end() || !it->second || !it->second->isValid()) return;
        m_shaderProgram->use();
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, it->second->getYTextureHandle());
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, it->second->getUTextureHandle());
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, it->second->getVTextureHandle());
        m_shaderProgram->setUniform1i("texY", 0);
        m_shaderProgram->setUniform1i("texU", 1);
        m_shaderProgram->setUniform1i("texV", 2);
        m_shaderProgram->setUniform1f("opacity", (progress - 0.5f) * 2.0f);
        m_shaderProgram->setUniform1i("effectsEnabled", 0);
        m_shaderProgram->setUniform1i("uLutEnabled", 0);
        m_shaderProgram->setUniform1i("uChromaEnabled", 0);
        m_shaderProgram->setUniformMatrix4fv("model", glm::value_ptr(glm::mat4(1.0f)));
        m_quadMesh->render();
    }
}

// Wipe: outgoing slides out left, incoming slides in from right
void PreviewRenderer::renderWipeTransition(const VideoEngine::TransitionNode& trans, TimeMs timeMs) {
    if (!trans.fromClip || !trans.toClip) return;
    const float t = trans.evaluateProgress(timeMs);

    auto itFrom = m_yuvTextureCache.find(trans.fromClip->getId());
    auto itTo   = m_yuvTextureCache.find(trans.toClip->getId());
    if (itFrom == m_yuvTextureCache.end() || itTo == m_yuvTextureCache.end()) return;
    if (!itFrom->second || !itFrom->second->isValid()) return;
    if (!itTo->second   || !itTo->second->isValid())   return;

    // Render outgoing clip shifted left by t (NDC: -2*t in X)
    {
        glm::mat4 model = glm::translate(glm::mat4(1.0f), glm::vec3(-2.0f * t, 0.0f, 0.0f));
        m_shaderProgram->use();
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, itFrom->second->getYTextureHandle());
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, itFrom->second->getUTextureHandle());
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, itFrom->second->getVTextureHandle());
        m_shaderProgram->setUniform1i("texY", 0);
        m_shaderProgram->setUniform1i("texU", 1);
        m_shaderProgram->setUniform1i("texV", 2);
        m_shaderProgram->setUniform1f("opacity", 1.0f);
        m_shaderProgram->setUniform1i("effectsEnabled", 0);
        m_shaderProgram->setUniform1i("uLutEnabled", 0);
        m_shaderProgram->setUniform1i("uChromaEnabled", 0);
        m_shaderProgram->setUniformMatrix4fv("model", glm::value_ptr(model));
        m_quadMesh->render();
    }
    // Render incoming clip shifted right by (1-t)*2 (NDC: starts at +2, moves to 0)
    {
        glm::mat4 model = glm::translate(glm::mat4(1.0f), glm::vec3(2.0f * (1.0f - t), 0.0f, 0.0f));
        m_shaderProgram->use();
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, itTo->second->getYTextureHandle());
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, itTo->second->getUTextureHandle());
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, itTo->second->getVTextureHandle());
        m_shaderProgram->setUniform1i("texY", 0);
        m_shaderProgram->setUniform1i("texU", 1);
        m_shaderProgram->setUniform1i("texV", 2);
        m_shaderProgram->setUniform1f("opacity", 1.0f);
        m_shaderProgram->setUniform1i("effectsEnabled", 0);
        m_shaderProgram->setUniform1i("uLutEnabled", 0);
        m_shaderProgram->setUniform1i("uChromaEnabled", 0);
        m_shaderProgram->setUniformMatrix4fv("model", glm::value_ptr(model));
        m_quadMesh->render();
    }
}

const TexturePtr& PreviewRenderer::getFramebufferTexture() const {
    if (m_framebuffer) {
        return m_framebuffer->getColorTexture();
    }
    static TexturePtr null;
    return null;
}

void PreviewRenderer::renderTextOverlays(const std::vector<VideoEngine::TextOverlay>& overlays, TimeMs timeMs) {
    if (!m_textRenderer || overlays.empty()) {
        return;
    }

    // Ensure blending is enabled for text
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    // Render text overlays (already sorted by zOrder by caller)
    // TextRenderer handles all transformations, color, opacity, fade timing
    m_textRenderer->renderTextOverlays(overlays, m_width, m_height, timeMs);

    std::cout << "[PreviewRenderer] Rendered " << overlays.size() << " text overlay(s) at time " << timeMs << " ms\n";
}

void PreviewRenderer::setClearColor(float r, float g, float b, float a) {
    m_clearColor = glm::vec4(r, g, b, a);
}

void PreviewRenderer::swapBuffers() {
    if (m_glContext) {
        m_glContext->swapBuffers();
    }
}

} // namespace VideoEngine::GPU

#pragma once

#include <cstdint>
#include <memory>
#include <string>

namespace VideoEngine::GPU {

/**
 * OpenGL context manager.
 * Handles GL context initialization, creation, and cleanup.
 * Supports headless rendering (off-screen) and windowed contexts.
 */
class GLContext {
public:
    enum class RenderMode {
        Headless,    // Off-screen rendering (FBO only)
        Windowed     // On-screen rendering (requires window)
    };

    /**
     * Create an OpenGL context.
     * @param width Framebuffer width in pixels
     * @param height Framebuffer height in pixels
     * @param mode Rendering mode (headless or windowed)
     * @param debugMode Enable OpenGL debug output
     */
    GLContext(uint32_t width, uint32_t height, RenderMode mode = RenderMode::Headless, bool debugMode = false);
    
    ~GLContext();

    // Non-copyable
    GLContext(const GLContext&) = delete;
    GLContext& operator=(const GLContext&) = delete;

    /**
     * Make this context current for rendering.
     * Must be called before any OpenGL operations.
     */
    void makeCurrent();

    /**
     * Release this context as current.
     * Allows other contexts or threads to use OpenGL.
     */
    void release();

    /**
     * Check if context is valid and can be used.
     */
    bool isValid() const { return m_isValid; }

    /**
     * Get framebuffer width.
     */
    uint32_t getWidth() const { return m_width; }

    /**
     * Get framebuffer height.
     */
    uint32_t getHeight() const { return m_height; }

    /**
     * Swap buffers (for windowed mode).
     */
    void swapBuffers();

    /**
     * Get native window handle (if applicable).
     */
    void* getNativeWindow() const { return m_nativeWindow; }

    /**
     * Get native GL context handle.
     */
    void* getNativeGLContext() const { return m_nativeGLContext; }

private:
    uint32_t m_width;
    uint32_t m_height;
    RenderMode m_mode;
    bool m_debugMode;
    bool m_isValid;

    void* m_nativeWindow;
    void* m_nativeGLContext;
    void* m_nativeDisplay;

    void initializeGLContext();
    void initializeGLExtensions();
    void setupDebugOutput();
};

using GLContextPtr = std::shared_ptr<GLContext>;

} // namespace VideoEngine::GPU

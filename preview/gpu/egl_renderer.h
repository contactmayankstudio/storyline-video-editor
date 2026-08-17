#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

// Forward declarations to avoid EGL/GL headers in public API
typedef void* EGLDisplay;
typedef void* EGLContext;
typedef void* EGLSurface;
struct ANativeWindow;

namespace VideoEngine::GPU {

class GLTexture;

/**
 * EGL-based OpenGL ES 3.0 renderer for Android SurfaceView.
 * 
 * Manages:
 * - EGL display, context, surface lifecycle
 * - OpenGL state and rendering
 * - Full-screen textured quad rendering
 * 
 * Designed for:
 * - 30-60 FPS real-time video preview
 * - Android NDK + OpenGL ES 3.0
 * - Clean RAII lifecycle management
 * 
 * Thread Model:
 * - Call initialize/resizeViewport/renderFrame from same thread
 * - EGL context is thread-local
 * - Not safe for concurrent rendering
 * 
 * Example usage:
 *   EGLRenderer renderer;
 *   renderer.initialize(nativeWindow);
 *   renderer.resizeViewport(1920, 1080);
 *   while (rendering) {
 *       renderer.renderFrame(texture);
 *   }
 *   renderer.shutdown();
 */
class EGLRenderer {
public:
    EGLRenderer();
    ~EGLRenderer();

    // Non-copyable
    EGLRenderer(const EGLRenderer&) = delete;
    EGLRenderer& operator=(const EGLRenderer&) = delete;

    /**
     * Initialize EGL and OpenGL ES context from Android Surface.
     * 
     * Prerequisites:
     * - ANativeWindow must be valid for lifetime of renderer
     * - Call from the thread that will render
     * 
     * @param nativeWindow Pointer from ANativeWindow_fromSurface(env, surface)
     * @return true on success, false if EGL/GL setup failed
     */
    bool initialize(ANativeWindow* nativeWindow);

    /**
     * Respond to surface size change.
     * Call when SurfaceView.Callback.surfaceChanged() fires.
     * Updates viewport and scissor rect.
     * 
     * @param width Surface width in pixels
     * @param height Surface height in pixels
     */
    void resizeViewport(int width, int height);

    /**
     * Render a frame using the provided texture.
     */
    bool renderFrame(const GLTexture& texture);

    struct Layer {
        const GLTexture* texture = nullptr;
        float opacity = 1.0f;
        float zoom = 1.0f;
        float scaleX = 1.0f;
        float scaleY = 1.0f;
        float panXPx = 0.0f;
        float panYPx = 0.0f;
        float rotationDeg = 0.0f;
        bool mirrorX = false;
        bool objectTransform = false;
        bool chromaEnabled = false;
        bool blueKey = false;
        float chromaSimilarity = 0.35f;
        float chromaSmoothness = 0.10f;
        float chromaSpill = 0.05f;
        float brightness = 0.0f;
        float contrast   = 1.0f;
        float saturation = 1.0f;
    };

    /**
     * Render multiple layers bottom-to-top with per-layer opacity and chroma key.
     * Layers are blended using GL_SRC_ALPHA / GL_ONE_MINUS_SRC_ALPHA.
     */
    bool renderLayers(const std::vector<Layer>& layers);

    /**
     * Render a transition between two layers in a single full-screen pass.
     * Transition type ids follow the Android TransitionType enum:
     * 1=Fade, 2=Cross, 3=Wipe, 4=Slide.
     */
    bool renderTransition(
        const Layer& outgoing,
        const Layer& incoming,
        int transitionType,
        float progress);

    /**
     * Render only a dirty region of the frame.
     * Coordinates are in top-left origin pixel space (Android UI style).
     * Renderer converts to GL scissor coordinates internally.
     */
    bool renderFrameRegion(
        const GLTexture& texture,
        int dirtyX,
        int dirtyY,
        int dirtyWidth,
        int dirtyHeight);

    /**
     * Acquire EGL context on the current thread for texture upload/render prep.
     * Caller must later invoke releaseContext().
     */
    bool acquireContext();

    /**
     * Release EGL context from the current thread.
     * Safe to call multiple times.
     */
    void releaseContext();

    /**
     * Shut down rendering and release all resources.
     * Safe to call multiple times.
     * After shutdown(), cannot render until re-initialize().
     */
    void shutdown();

    /**
     * Check if renderer is initialized and ready to render.
     * @return true if EGL/GL setup succeeded
     */
    bool isInitialized() const { return m_initialized; }

    /**
     * Get last error message (if operation failed).
     * @return Error string, or empty if no error
     */
    const char* getLastError() const { return m_lastError.c_str(); }

    /**
     * Set chroma key parameters for preview rendering.
     */
    void setChromaKey(bool enabled, bool blueKey, float similarity, float smoothness, float spill);

private:
    struct TransitionLayerUniformSet {
        int opacity = -1;
        int transformEnabled = -1;
        int textureSize = -1;
        int zoom = -1;
        int scale = -1;
        int panPx = -1;
        int rotationDeg = -1;
        int mirrorX = -1;
        int objectTransform = -1;
        int chromaEnabled = -1;
        int chromaKeyColor = -1;
        int chromaSimilarity = -1;
        int chromaSmoothness = -1;
        int chromaSpill = -1;
        int brightness = -1;
        int contrast = -1;
        int saturation = -1;
    };

    // EGL state
    EGLDisplay m_eglDisplay;
    EGLContext m_eglContext;
    EGLSurface m_eglSurface;
    ANativeWindow* m_nativeWindow;

    // Render state
    uint32_t m_programId;   // Linked shader program
    uint32_t m_transitionProgramId; // Linked transition shader program
    uint32_t m_vao;         // Vertex array object (quad mesh)
    uint32_t m_vbo;         // Vertex buffer object
    uint32_t m_ebo;         // Element buffer object (indices)

    int m_viewportWidth;
    int m_viewportHeight;
    bool m_initialized;

    std::string m_lastError;
    bool m_chromaEnabled = false;
    float m_chromaSimilarity = 0.35f;
    float m_chromaSmoothness = 0.10f;
    float m_chromaSpill = 0.05f;
    float m_chromaColor[3] = {0.0f, 1.0f, 0.0f};
    int m_uOpacityLoc = -1;
    int m_uChromaEnabledLoc = -1;
    int m_uChromaKeyColorLoc = -1;
    int m_uChromaSimilarityLoc = -1;
    int m_uChromaSmoothnessLoc = -1;
    int m_uChromaSpillLoc = -1;
    int m_uBrightnessLoc = -1;
    int m_uContrastLoc = -1;
    int m_uSaturationLoc = -1;
    int m_uTextureSamplerLoc = -1;
    int m_uTransformEnabledLoc = -1;
    int m_uViewportSizeLoc = -1;
    int m_uTextureSizeLoc = -1;
    int m_uZoomLoc = -1;
    int m_uScaleLoc = -1;
    int m_uPanPxLoc = -1;
    int m_uRotationDegLoc = -1;
    int m_uMirrorXLoc = -1;
    int m_uObjectTransformLoc = -1;
    int m_uTransitionViewportLoc = -1;
    int m_uTransitionProgressLoc = -1;
    int m_uTransitionTypeLoc = -1;
    int m_uTransitionTextureSamplerALoc = -1;
    int m_uTransitionTextureSamplerBLoc = -1;
    TransitionLayerUniformSet m_transitionUniformsA;
    TransitionLayerUniformSet m_transitionUniformsB;

    /**
     * Internal: Compile a shader and return shader ID.
     * @param source GLSL source code
     * @param type GL_VERTEX_SHADER or GL_FRAGMENT_SHADER
     * @return Shader ID on success, 0 on failure
     */
    uint32_t compileShader(const char* source, uint32_t type);

    /**
     * Internal: Create and link shader program.
     * @return Program ID on success, 0 on failure
     */
    uint32_t createShaderProgram(const char* fragmentSource);

    /**
     * Internal: Create full-screen quad geometry (VAO/VBO/EBO).
     * @return true on success
     */
    bool createQuadMesh();

    /**
     * Internal: Make EGL context current on this thread.
     * @return true if successful
     */
    bool makeCurrent();

    /**
     * Internal: Release EGL and OpenGL resources.
     */
    void releaseResources();

    /**
     * Internal: Set error message.
     */
    void setError(const char* fmt, ...);

    /**
     * Internal: Check for OpenGL errors and log.
     * @return true if no error, false if error occurred
     */
    bool checkGLError(const char* operation);
};

}  // namespace VideoEngine::GPU

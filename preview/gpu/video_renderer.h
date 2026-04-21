#pragma once

namespace VideoEngine::GPU {

// Forward declaration
class GLTexture;

/**
 * OpenGL ES 3.0 shader-based video frame renderer.
 * 
 * Renders a fullscreen quad with texture sampling and opacity blending.
 * 
 * Assumes OpenGL context is already current.
 * 
 * Example:
 *   VideoRenderer renderer;
 *   renderer.init();
 *   renderer.draw(texture, 1.0f);  // opacity: 1.0 = full
 *   renderer.destroy();
 */
class VideoRenderer {
public:
    VideoRenderer();
    ~VideoRenderer();

    VideoRenderer(const VideoRenderer&) = delete;
    VideoRenderer& operator=(const VideoRenderer&) = delete;

    /**
     * Compile shaders, link program, create geometry.
     * Assumes OpenGL context is current.
     * 
     * @return true if initialization succeeded
     */
    bool init();

    /**
     * Render video frame to framebuffer.
     * Draws fullscreen quad with texture and opacity.
     * 
     * @param texture Reference to GLTexture with frame data
     * @param opacity Alpha blending (0.0-1.0)
     */
    void draw(GLTexture& texture, float opacity);

    /**
     * Release GPU resources: shaders, program, geometry.
     */
    void destroy();

private:
    unsigned int program;
    unsigned int vao;
    unsigned int vbo;

    /**
     * Internal: Compile shader from source.
     * 
     * @param type GL_VERTEX_SHADER or GL_FRAGMENT_SHADER
     * @param src  GLSL source code
     * @return compiled shader ID, or 0 on error
     */
    unsigned int compileShader(unsigned int type, const char* src);

    /**
     * Internal: Link vertex and fragment shaders into program.
     * 
     * @param vs Compiled vertex shader
     * @param fs Compiled fragment shader
     * @return linked program ID, or 0 on error
     */
    unsigned int linkProgram(unsigned int vs, unsigned int fs);

    /**
     * Internal: Create VAO + VBO for fullscreen quad.
     * @return true if successful
     */
    bool createGeometry();
};

}  // namespace VideoEngine::GPU

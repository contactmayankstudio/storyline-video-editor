#include "video_renderer.h"
#include "gl_texture.h"

#include <iostream>
#include <cstring>

#ifdef __APPLE__
    #include <OpenGLES/ES3/gl.h>
#else
    #include <GLES3/gl3.h>
#endif

namespace VideoEngine::GPU {

// Embedded shaders
static const char* VERTEX_SHADER_SRC = R"(
    #version 300 es
    
    in vec2 position;
    in vec2 texCoord;
    
    out vec2 vTexCoord;
    
    void main() {
        gl_Position = vec4(position, 0.0, 1.0);
        vTexCoord = texCoord;
    }
)";

static const char* FRAGMENT_SHADER_SRC = R"(
    #version 300 es
    precision mediump float;
    
    in vec2 vTexCoord;
    out vec4 fragColor;
    
    uniform sampler2D uTexture;
    uniform float uOpacity;
    
    void main() {
        vec3 rgb = texture(uTexture, vTexCoord).rgb;
        fragColor = vec4(rgb, uOpacity);
    }
)";

VideoRenderer::VideoRenderer()
    : program(0)
    , vao(0)
    , vbo(0)
{
}

VideoRenderer::~VideoRenderer() {
    destroy();
}

bool VideoRenderer::init() {
    // Compile shaders
    unsigned int vs = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER_SRC);
    if (vs == 0) {
        std::cerr << "[VideoRenderer] Vertex shader compile failed\n";
        return false;
    }

    unsigned int fs = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SRC);
    if (fs == 0) {
        std::cerr << "[VideoRenderer] Fragment shader compile failed\n";
        glDeleteShader(vs);
        return false;
    }

    // Link program
    program = linkProgram(vs, fs);
    if (program == 0) {
        std::cerr << "[VideoRenderer] Program link failed\n";
        glDeleteShader(vs);
        glDeleteShader(fs);
        return false;
    }

    glDeleteShader(vs);
    glDeleteShader(fs);

    // Create geometry
    if (!createGeometry()) {
        std::cerr << "[VideoRenderer] Geometry creation failed\n";
        glDeleteProgram(program);
        program = 0;
        return false;
    }

    std::cout << "[VideoRenderer] Initialized (program=" << program << ")\n";
    return true;
}

void VideoRenderer::draw(GLTexture& texture, float opacity) {
    if (program == 0 || vao == 0) {
        std::cerr << "[VideoRenderer] Not initialized\n";
        return;
    }

    // Clamp opacity
    opacity = (opacity < 0.0f) ? 0.0f : (opacity > 1.0f) ? 1.0f : opacity;

    // Use program
    glUseProgram(program);

    // Bind texture to unit 0
    texture.bind(0);

    // Set uniforms
    int texLoc = glGetUniformLocation(program, "uTexture");
    if (texLoc >= 0) {
        glUniform1i(texLoc, 0);
    }

    int opacityLoc = glGetUniformLocation(program, "uOpacity");
    if (opacityLoc >= 0) {
        glUniform1f(opacityLoc, opacity);
    }

    // Draw quad
    glBindVertexArray(vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    glUseProgram(0);
}

void VideoRenderer::destroy() {
    if (vao != 0) {
        glDeleteVertexArrays(1, &vao);
        vao = 0;
    }
    if (vbo != 0) {
        glDeleteBuffers(1, &vbo);
        vbo = 0;
    }
    if (program != 0) {
        glDeleteProgram(program);
        program = 0;
    }
}

unsigned int VideoRenderer::compileShader(unsigned int type, const char* src) {
    unsigned int shader = glCreateShader(type);
    if (shader == 0) {
        std::cerr << "[VideoRenderer] glCreateShader failed\n";
        return 0;
    }

    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);

    int status;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (status == GL_FALSE) {
        int logLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &logLen);
        if (logLen > 1) {
            char* log = new char[logLen];
            glGetShaderInfoLog(shader, logLen, nullptr, log);
            std::cerr << "[VideoRenderer] Compile error:\n" << log << "\n";
            delete[] log;
        }
        glDeleteShader(shader);
        return 0;
    }

    return shader;
}

unsigned int VideoRenderer::linkProgram(unsigned int vs, unsigned int fs) {
    unsigned int prog = glCreateProgram();
    if (prog == 0) {
        std::cerr << "[VideoRenderer] glCreateProgram failed\n";
        return 0;
    }

    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);

    int status;
    glGetProgramiv(prog, GL_LINK_STATUS, &status);
    if (status == GL_FALSE) {
        int logLen = 0;
        glGetProgramiv(prog, GL_INFO_LOG_LENGTH, &logLen);
        if (logLen > 1) {
            char* log = new char[logLen];
            glGetProgramInfoLog(prog, logLen, nullptr, log);
            std::cerr << "[VideoRenderer] Link error:\n" << log << "\n";
            delete[] log;
        }
        glDeleteProgram(prog);
        return 0;
    }

    return prog;
}

bool VideoRenderer::createGeometry() {
    struct Vertex {
        float pos[2];
        float uv[2];
    };

    // Fullscreen quad as triangle strip
    Vertex verts[] = {
        {{-1.0f, -1.0f}, {0.0f, 0.0f}},
        {{ 1.0f, -1.0f}, {1.0f, 0.0f}},
        {{-1.0f,  1.0f}, {0.0f, 1.0f}},
        {{ 1.0f,  1.0f}, {1.0f, 1.0f}},
    };

    glGenVertexArrays(1, &vao);
    if (vao == 0) {
        std::cerr << "[VideoRenderer] glGenVertexArrays failed\n";
        return false;
    }

    glGenBuffers(1, &vbo);
    if (vbo == 0) {
        std::cerr << "[VideoRenderer] glGenBuffers failed\n";
        glDeleteVertexArrays(1, &vao);
        vao = 0;
        return false;
    }

    glBindVertexArray(vao);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);

    // Position attribute
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex), 
                          (void*)offsetof(Vertex, pos));

    // TexCoord attribute
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex),
                          (void*)offsetof(Vertex, uv));

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindVertexArray(0);

    return true;
}

}  // namespace VideoEngine::GPU

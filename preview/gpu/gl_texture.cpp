#include "gl_texture.h"

#include <iostream>
#include <cstring>

#ifdef __APPLE__
    #include <OpenGLES/ES3/gl.h>
#else
    #include <EGL/egl.h>
    #include <GLES3/gl3.h>
#endif

namespace VideoEngine::GPU {

namespace {
#ifndef __APPLE__
bool hasActiveGlContext() {
    return eglGetCurrentContext() != EGL_NO_CONTEXT;
}
#endif
}  // namespace

GLTexture::GLTexture()
    : m_textureId(0)
    , m_width(0)
    , m_height(0)
{
}

GLTexture::~GLTexture() {
    release();
}

bool GLTexture::initialize(int width, int height) {
    if (width <= 0 || height <= 0) {
        std::cerr << "[GLTexture] Invalid dimensions: " << width << "x" << height << "\n";
        return false;
    }

    // If texture already exists with same dimensions, keep it (no reallocation)
    if (m_textureId != 0 && m_width == width && m_height == height) {
        return true;
    }

    // Reallocate storage for new dimensions
    return allocateStorage(width, height);
}

bool GLTexture::allocateStorage(int width, int height) {
    // Release old texture if it exists
    if (m_textureId != 0) {
        glDeleteTextures(1, &m_textureId);
        m_textureId = 0;
    }

    m_width = width;
    m_height = height;

    // Create new texture
    glGenTextures(1, &m_textureId);
    if (m_textureId == 0) {
        std::cerr << "[GLTexture] glGenTextures failed\n";
        return false;
    }

    glBindTexture(GL_TEXTURE_2D, m_textureId);

    // Configure texture filtering and wrapping
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    // Allocate GPU storage for RGBA data
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, m_width, m_height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, nullptr);

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        std::cerr << "[GLTexture] glTexImage2D failed: 0x" << std::hex << err << "\n";
        glDeleteTextures(1, &m_textureId);
        m_textureId = 0;
        glBindTexture(GL_TEXTURE_2D, 0);
        return false;
    }

    glBindTexture(GL_TEXTURE_2D, 0);
    std::cout << "[GLTexture] Allocated RGBA texture " << m_textureId 
              << " (" << m_width << "x" << m_height << ")\n";

    return true;
}

void GLTexture::update(const uint8_t* rgbaPixels) {
    if (!rgbaPixels) {
        std::cerr << "[GLTexture] NULL RGBA pixel data\n";
        return;
    }

    if (!isValid()) {
        std::cerr << "[GLTexture] Texture not initialized\n";
        return;
    }

    glBindTexture(GL_TEXTURE_2D, m_textureId);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    // Stream data to texture using glTexSubImage2D (efficient for repeated updates)
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, m_width, m_height,
                    GL_RGBA, GL_UNSIGNED_BYTE, rgbaPixels);

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        std::cerr << "[GLTexture] glTexSubImage2D failed: 0x" << std::hex << err << "\n";
    }

    glBindTexture(GL_TEXTURE_2D, 0);
}

void GLTexture::bind(int textureUnit) const {
    if (!isValid()) {
        std::cerr << "[GLTexture] Cannot bind invalid texture\n";
        return;
    }

    if (textureUnit < 0 || textureUnit > 31) {
        std::cerr << "[GLTexture] Invalid texture unit: " << textureUnit << "\n";
        textureUnit = 0;
    }

    glActiveTexture(GL_TEXTURE0 + textureUnit);
    glBindTexture(GL_TEXTURE_2D, m_textureId);
}

void GLTexture::release() {
    if (m_textureId != 0) {
        const uint32_t releasedTextureId = m_textureId;
#ifndef __APPLE__
        if (hasActiveGlContext()) {
            glDeleteTextures(1, &m_textureId);
            std::cout << "[GLTexture] Released RGBA texture " << releasedTextureId << "\n";
        } else {
            std::cout
                << "[GLTexture] Dropping texture handle without active GL context "
                << releasedTextureId << "\n";
        }
#else
        glDeleteTextures(1, &m_textureId);
        std::cout << "[GLTexture] Released RGBA texture " << releasedTextureId << "\n";
#endif
        m_textureId = 0;
    }
    m_width = 0;
    m_height = 0;
}

}  // namespace VideoEngine::GPU

#include "texture.h"
#ifdef __ANDROID__
#include <GLES3/gl3.h>
#else
#include <GL/glew.h>
#endif
#include <iostream>

namespace VideoEngine::GPU {

// ============ Texture Implementation ============

Texture::Texture(uint32_t width, uint32_t height, Format format, const void* data)
    : m_handle(0)
    , m_width(width)
    , m_height(height)
    , m_format(format)
{
    glGenTextures(1, &m_handle);
    if (!m_handle) {
        std::cerr << "[Texture] Failed to allocate texture handle\n";
        return;
    }

    glBindTexture(GL_TEXTURE_2D, m_handle);

    uint32_t glFormat = getGLFormat();
    uint32_t glInternalFormat = getGLInternalFormat();
    uint32_t glType = getGLType();

    glTexImage2D(GL_TEXTURE_2D, 0, glInternalFormat, width, height, 0, glFormat, glType, data);

    // Set default parameters
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    glBindTexture(GL_TEXTURE_2D, 0);

    std::cout << "[Texture] Created " << width << "x" << height << " texture (handle=" << m_handle << ")\n";
}

Texture::~Texture() {
    if (m_handle) {
        glDeleteTextures(1, &m_handle);
    }
}

void Texture::bind(uint32_t unit) const {
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(GL_TEXTURE_2D, m_handle);
}

void Texture::unbind() const {
    glBindTexture(GL_TEXTURE_2D, 0);
}

void Texture::setWrapMode(WrapMode mode) {
    glBindTexture(GL_TEXTURE_2D, m_handle);

    uint32_t glMode;
    switch (mode) {
        case WrapMode::Clamp:  glMode = GL_CLAMP_TO_EDGE; break;
        case WrapMode::Repeat: glMode = GL_REPEAT; break;
        case WrapMode::Mirror: glMode = GL_MIRRORED_REPEAT; break;
        default: glMode = GL_CLAMP_TO_EDGE;
    }

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, glMode);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, glMode);
    glBindTexture(GL_TEXTURE_2D, 0);
}

void Texture::setFilterMode(FilterMode minFilter, FilterMode magFilter) {
    glBindTexture(GL_TEXTURE_2D, m_handle);

    uint32_t glMinFilter = (minFilter == FilterMode::Nearest) ? GL_NEAREST : GL_LINEAR;
    uint32_t glMagFilter = (magFilter == FilterMode::Nearest) ? GL_NEAREST : GL_LINEAR;

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, glMinFilter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, glMagFilter);
    glBindTexture(GL_TEXTURE_2D, 0);
}

void Texture::updateData(const void* data) {
    glBindTexture(GL_TEXTURE_2D, m_handle);
    uint32_t glFormat = getGLFormat();
    uint32_t glType = getGLType();
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, m_width, m_height, glFormat, glType, data);
    glBindTexture(GL_TEXTURE_2D, 0);
}

uint32_t Texture::getGLFormat() const {
    switch (m_format) {
        case Format::RGB8:      return GL_RGB;
        case Format::RGBA8:     return GL_RGBA;
        case Format::RGBA16F:   return GL_RGBA;
        case Format::RGBA32F:   return GL_RGBA;
        case Format::R8:        return GL_RED;
        case Format::DEPTH24:   return GL_DEPTH_COMPONENT;
        default:                return GL_RGBA;
    }
}

uint32_t Texture::getGLInternalFormat() const {
    switch (m_format) {
        case Format::RGB8:      return GL_RGB8;
        case Format::RGBA8:     return GL_RGBA8;
        case Format::RGBA16F:   return GL_RGBA16F;
        case Format::RGBA32F:   return GL_RGBA32F;
        case Format::R8:        return GL_R8;
        case Format::DEPTH24:   return GL_DEPTH_COMPONENT24;
        default:                return GL_RGBA8;
    }
}

uint32_t Texture::getGLType() const {
    switch (m_format) {
        case Format::RGB8:      return GL_UNSIGNED_BYTE;
        case Format::RGBA8:     return GL_UNSIGNED_BYTE;
        case Format::RGBA16F:   return GL_HALF_FLOAT;
        case Format::RGBA32F:   return GL_FLOAT;
        case Format::R8:        return GL_UNSIGNED_BYTE;
        case Format::DEPTH24:   return GL_UNSIGNED_INT;
        default:                return GL_UNSIGNED_BYTE;
    }
}

// ============ Framebuffer Implementation ============

Framebuffer::Framebuffer(uint32_t width, uint32_t height, bool hasDepth)
    : m_handle(0)
{
    // Create color texture
    m_colorTexture = std::make_shared<Texture>(width, height, Texture::Format::RGBA8, nullptr);
    if (!m_colorTexture->isValid()) {
        std::cerr << "[Framebuffer] Failed to create color texture\n";
        return;
    }

    // Create depth texture if requested
    if (hasDepth) {
        m_depthTexture = std::make_shared<Texture>(width, height, Texture::Format::DEPTH24, nullptr);
        if (!m_depthTexture->isValid()) {
            std::cerr << "[Framebuffer] Failed to create depth texture\n";
            return;
        }
    }

    // Create framebuffer object
    glGenFramebuffers(1, &m_handle);
    if (!m_handle) {
        std::cerr << "[Framebuffer] Failed to allocate framebuffer\n";
        return;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, m_handle);

    // Attach color texture
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, m_colorTexture->getHandle(), 0);

    // Attach depth texture if present
    if (m_depthTexture) {
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, m_depthTexture->getHandle(), 0);
    }

    uint32_t status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        std::cerr << "[Framebuffer] Framebuffer not complete (status=0x" << std::hex << status << ")\n";
        glDeleteFramebuffers(1, &m_handle);
        m_handle = 0;
    } else {
        std::cout << "[Framebuffer] Created " << width << "x" << height 
                  << " FBO with " << (hasDepth ? "depth" : "no depth") << " (handle=" << m_handle << ")\n";
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

Framebuffer::~Framebuffer() {
    if (m_handle) {
        glDeleteFramebuffers(1, &m_handle);
    }
}

void Framebuffer::bind() const {
    glBindFramebuffer(GL_FRAMEBUFFER, m_handle);
}

void Framebuffer::unbind() const {
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

// ============ YUVTexture Implementation ============

YUVTexture::YUVTexture(uint32_t width, uint32_t height)
    : m_width(width)
    , m_height(height)
    , m_yHandle(0)
    , m_uHandle(0)
    , m_vHandle(0)
{
    // Create Y plane texture (full resolution)
    glGenTextures(1, &m_yHandle);
    if (!m_yHandle) {
        std::cerr << "[YUVTexture] Failed to allocate Y texture handle\n";
        return;
    }
    
    glBindTexture(GL_TEXTURE_2D, m_yHandle);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RED, width, height, 0, GL_RED, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    
    // Create U plane texture (half resolution)
    uint32_t halfWidth = width / 2;
    uint32_t halfHeight = height / 2;
    
    glGenTextures(1, &m_uHandle);
    if (!m_uHandle) {
        std::cerr << "[YUVTexture] Failed to allocate U texture handle\n";
        glDeleteTextures(1, &m_yHandle);
        m_yHandle = 0;
        return;
    }
    
    glBindTexture(GL_TEXTURE_2D, m_uHandle);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RED, halfWidth, halfHeight, 0, GL_RED, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    
    // Create V plane texture (half resolution)
    glGenTextures(1, &m_vHandle);
    if (!m_vHandle) {
        std::cerr << "[YUVTexture] Failed to allocate V texture handle\n";
        glDeleteTextures(1, &m_yHandle);
        glDeleteTextures(1, &m_uHandle);
        m_yHandle = 0;
        m_uHandle = 0;
        return;
    }
    
    glBindTexture(GL_TEXTURE_2D, m_vHandle);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RED, halfWidth, halfHeight, 0, GL_RED, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    
    glBindTexture(GL_TEXTURE_2D, 0);
    
    std::cout << "[GPU Preview] texture updated (Y: " << width << "x" << height 
              << ", U/V: " << halfWidth << "x" << halfHeight << ")\n";
}

YUVTexture::~YUVTexture() {
    if (m_yHandle) glDeleteTextures(1, &m_yHandle);
    if (m_uHandle) glDeleteTextures(1, &m_uHandle);
    if (m_vHandle) glDeleteTextures(1, &m_vHandle);
}

bool YUVTexture::updateFromYUV420P(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData) {
    if (!isValid()) {
        std::cerr << "[YUVTexture] Texture not valid\n";
        return false;
    }

    if (!yData || !uData || !vData) {
        std::cerr << "[YUVTexture] Input data is null\n";
        return false;
    }

    // Update Y plane (full resolution)
    glBindTexture(GL_TEXTURE_2D, m_yHandle);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, m_width, m_height, GL_RED, GL_UNSIGNED_BYTE, yData);

    // Update U plane (half resolution)
    uint32_t halfWidth = m_width / 2;
    uint32_t halfHeight = m_height / 2;
    
    glBindTexture(GL_TEXTURE_2D, m_uHandle);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, halfWidth, halfHeight, GL_RED, GL_UNSIGNED_BYTE, uData);

    // Update V plane (half resolution)
    glBindTexture(GL_TEXTURE_2D, m_vHandle);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, halfWidth, halfHeight, GL_RED, GL_UNSIGNED_BYTE, vData);

    glBindTexture(GL_TEXTURE_2D, 0);

    return true;
}

void YUVTexture::bind() const {
    // Bind Y plane to texture unit 0
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, m_yHandle);
    
    // Bind U plane to texture unit 1
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, m_uHandle);
    
    // Bind V plane to texture unit 2
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, m_vHandle);
    
    // Reset to unit 0
    glActiveTexture(GL_TEXTURE0);
}

void YUVTexture::unbind() const {
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, 0);
    
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, 0);
    
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, 0);
    
    glActiveTexture(GL_TEXTURE0);
}

} // namespace VideoEngine::GPU

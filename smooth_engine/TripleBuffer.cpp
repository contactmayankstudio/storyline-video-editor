#include "TripleBuffer.h"

#include <iostream>

namespace VideoEngine::Performance {

TripleBuffer::TripleBuffer(int width, int height) {
    m_width = width > 0 ? width : 0;
    m_height = height > 0 ? height : 0;
}

TripleBuffer::~TripleBuffer() {
    release();
}

bool TripleBuffer::initialize(int width, int height) {
    if (width <= 0 || height <= 0) {
        return false;
    }

    std::lock_guard<std::mutex> lock(m_mutex);
    bool ok = true;
    for (auto& frame : m_buffers) {
        if (!frame.texture) {
            frame.texture = std::make_unique<VideoEngine::GPU::GLTexture>();
        }
        if (!frame.texture->initialize(width, height)) {
            ok = false;
            break;
        }
    }
    if (!ok) {
        for (auto& frame : m_buffers) {
            if (frame.texture) {
                frame.texture->release();
            }
        }
        m_width = 0;
        m_height = 0;
        return false;
    }

    m_width = width;
    m_height = height;
    return true;
}

VideoEngine::GPU::GLTexture* TripleBuffer::acquireBackTexture() {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_buffers[m_backIdx].texture.get();
}

void TripleBuffer::presentBackTexture() {
    std::lock_guard<std::mutex> lock(m_mutex);
    const int oldFront = m_frontIdx;
    m_frontIdx = m_backIdx;
    m_backIdx = m_pendingIdx;
    m_pendingIdx = oldFront;
}

VideoEngine::GPU::GLTexture* TripleBuffer::frontTexture() {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_buffers[m_frontIdx].texture.get();
}

const VideoEngine::GPU::GLTexture* TripleBuffer::frontTexture() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_buffers[m_frontIdx].texture.get();
}

void TripleBuffer::release() {
    std::lock_guard<std::mutex> lock(m_mutex);
    for (auto& frame : m_buffers) {
        if (frame.texture) {
            frame.texture->release();
        }
        frame.texture.reset();
    }
    m_width = 0;
    m_height = 0;
}

bool TripleBuffer::isValid() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_width <= 0 || m_height <= 0) {
        return false;
    }
    for (const auto& frame : m_buffers) {
        if (!frame.texture || !frame.texture->isValid()) {
            return false;
        }
    }
    return true;
}

} // namespace VideoEngine::Performance

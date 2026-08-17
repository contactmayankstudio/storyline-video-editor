#include "TexturePool.h"

namespace VideoEngine::Performance {

std::shared_ptr<GPU::Texture> TexturePool::acquire(uint32_t width, uint32_t height, GPU::Texture::Format format) {
    std::lock_guard<std::mutex> lock(m_mutex);
    
    TextureKey key{width, height, format};
    auto& pool = m_availableTextures[key];
    
    if (!pool.empty()) {
        auto texture = pool.back();
        pool.pop_back();
        return texture;
    }
    
    // Pool is empty, create a real reusable texture immediately.
    return std::make_shared<GPU::Texture>(width, height, format, nullptr);
}

void TexturePool::release(std::shared_ptr<GPU::Texture> texture) {
    if (!texture) return;
    
    std::lock_guard<std::mutex> lock(m_mutex);
    
    TextureKey key{texture->getWidth(), texture->getHeight(), texture->getFormat()};
    m_availableTextures[key].push_back(texture);
}

void TexturePool::clear() {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_availableTextures.clear();
}

} // namespace VideoEngine::Performance

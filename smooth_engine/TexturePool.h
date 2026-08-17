#pragma once

#include <vector>
#include <memory>
#include <mutex>
#include <map>
#include "backend/gpu/texture.h"

namespace VideoEngine::Performance {

/**
 * @brief TexturePool reduces GPU memory allocation overhead by reusing textures.
 * 
 * Instead of creating and destroying OpenGL textures for every frame or clip,
 * the pool keeps a set of already allocated textures that can be checked out
 * and returned.
 */
class TexturePool {
public:
    static TexturePool& getInstance() {
        static TexturePool instance;
        return instance;
    }

    /**
     * @brief Acquire a texture of specific dimensions and format.
     * If a matching texture is in the pool, it is returned. Otherwise, a new one is created.
     */
    std::shared_ptr<GPU::Texture> acquire(uint32_t width, uint32_t height, GPU::Texture::Format format);

    /**
     * @brief Return a texture to the pool for later reuse.
     */
    void release(std::shared_ptr<GPU::Texture> texture);

    /**
     * @brief Clear all cached textures.
     */
    void clear();

private:
    TexturePool() = default;
    ~TexturePool() = default;

    struct TextureKey {
        uint32_t width;
        uint32_t height;
        GPU::Texture::Format format;

        bool operator<(const TextureKey& other) const {
            if (width != other.width) return width < other.width;
            if (height != other.height) return height < other.height;
            return format < other.format;
        }
    };

    std::map<TextureKey, std::vector<std::shared_ptr<GPU::Texture>>> m_availableTextures;
    std::mutex m_mutex;
};

} // namespace VideoEngine::Performance

#include "VulkanRenderer.h"

#include <algorithm>
#include <iostream>

namespace VideoEngine::Backend {

void VulkanRenderer::init() {
    if (isReady()) {
        return;
    }
    std::cout << "[VulkanRenderer] Initializing Vulkan backend stub\n";
    m_vkInstance = reinterpret_cast<void*>(0x1);
    m_vkDevice = reinterpret_cast<void*>(0x1);
}

void VulkanRenderer::shutdown() {
    if (!isReady()) {
        return;
    }
    std::cout << "[VulkanRenderer] Shutdown\n";
    m_vkDevice = nullptr;
    m_vkInstance = nullptr;
}

bool VulkanRenderer::isReady() const {
    return m_vkInstance != nullptr && m_vkDevice != nullptr;
}

bool VulkanRenderer::shouldWarmForPreview(
    int surfaceWidth,
    int surfaceHeight,
    std::size_t textureCount) const {
    if (!isReady()) {
        return false;
    }
    const int safeWidth = std::max(surfaceWidth, 0);
    const int safeHeight = std::max(surfaceHeight, 0);
    const std::size_t safeTextures = std::max<std::size_t>(textureCount, 0U);
    return safeTextures >= 3 && (safeWidth * safeHeight) >= (1280 * 720);
}

void VulkanRenderer::submitRenderCommands(const std::vector<uint32_t>& textureBatch) {
    std::cout << "[VulkanRenderer] submitRenderCommands textures=" << textureBatch.size() << "\n";
}

} // namespace VideoEngine::Backend

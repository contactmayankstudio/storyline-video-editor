#include "preview_controller.h"
#include "backend/ffmpeg/video_decoder.h"
#include "backend/gpu/texture.h"
#include "backend/gpu/preview_renderer.h"
#include "core/clip.h"
#include "engine.h"

#include <iostream>

namespace VideoEngine {

using namespace Backend;
using namespace GPU;

PreviewController::PreviewController()
    : width(1920), height(1080) {
}

PreviewController::~PreviewController() {
    close();
}

void PlaybackController::play() {
    isPlaying = true;
    // Start render timer and audio playback
}

void PlaybackController::pause() {
    isPlaying = false;
    // Pause render timer and audio playback
}

void PlaybackController::stop() {
    isPlaying = false;
    currentPosition = startPosition;
    // Stop render timer and audio playback
}

void PlaybackController::setLoop(bool loop) {
    isLooping = loop;
}

void PlaybackController::seekToMs(int64_t ms) {
    currentPosition = ms;
    // Adjust playback position
}

bool PreviewController::initRenderer(uint32_t w, uint32_t h) {
    width = w;
    height = h;
    
    try {
        renderer = std::make_shared<PreviewRenderer>(w, h, PreviewRenderer::RenderMode::Headless);
        if (!renderer || !renderer->isValid()) {
            std::cerr << "[PreviewController] Failed to initialize renderer\n";
            return false;
        }
        
        std::cout << "[PreviewController] Renderer initialized: " << w << "x" << h << "\n";
        return true;
    } catch (const std::exception& e) {
        std::cerr << "[PreviewController] Renderer initialization exception: " << e.what() << "\n";
        return false;
    }
}

bool PreviewController::renderFrame(const RenderGraph& renderGraph, int64_t timeMs) {
    if (!renderer || !renderer->isValid()) {
        std::cerr << "[PreviewController] Renderer not initialized\n";
        return false;
    }

    try {
        // Get visible items at this time
        auto visibleItems = renderGraph.getItemsAtTime(timeMs);

        // Decode and cache YUV textures for all visible clips
        for (const auto& item : visibleItems) {
            if (!item.clip) continue;

            // Decode clip at timeline time and cache texture
            if (!decodeAndCacheClip(item.clip, timeMs)) {
                std::cerr << "[PreviewController] Failed to decode clip at time " << timeMs << "\n";
                // Continue rendering with other clips
            }

            // Log GPU effects for this clip
            const auto& effects = item.clip->getEffects();
            if (effects.enabled) {
                std::cout << "[GPU FX] brightness=" << effects.brightness
                          << " contrast=" << effects.contrast
                          << " saturation=" << effects.saturation
                          << " LUT enabled=" << (effects.lutEnabled ? "true" : "false") << "\n";
            }
        }

        // Render the frame via PreviewRenderer
        // PreviewRenderer will query the texture cache we just populated
        renderer->renderFrame(renderGraph, timeMs);

        return true;
    } catch (const std::exception& e) {
        std::cerr << "[PreviewController] Render exception: " << e.what() << "\n";
        return false;
    }
}

bool PreviewController::decodeAndCacheClip(const std::shared_ptr<Clip>& clip, int64_t timeMs) {
    if (!clip || !renderer) {
        return false;
    }

    uint32_t clipId = clip->getId();

    // Check if already cached
    auto cachedTexture = renderer->getYUVTexture(clipId);
    if (cachedTexture && cachedTexture->isValid()) {
        // Already cached, skip re-decoding
        return true;
    }

    // Get or create decoder for this clip
    VideoDecoder* decoder = getOrCreateDecoder(clip);
    if (!decoder) {
        return false;
    }

    // Convert timeline time to clip-local time
    // timeMs is timeline position; convert to source video offset
    int64_t clipStartTimeMs = clip->getStartTime();
    int64_t clipLocalMs = timeMs - clipStartTimeMs;

    // Clamp to clip duration
    if (clipLocalMs < 0) {
        clipLocalMs = 0;
    }

    // Decode YUV frame at this time
    YUVFrame yuvFrame;
    if (!decoder->decodeFrameAt(clipLocalMs, yuvFrame)) {
        std::cerr << "[PreviewController] Failed to decode YUV frame for clip " << clipId 
                  << " at time " << clipLocalMs << " ms\n";
        return false;
    }

    // Create or update YUV texture
    auto yuvTexture = std::make_shared<YUVTexture>(yuvFrame.width, yuvFrame.height);
    if (!yuvTexture || !yuvTexture->isValid()) {
        std::cerr << "[PreviewController] Failed to create YUV texture\n";
        return false;
    }

    // Upload YUV planes to GPU
    if (!yuvTexture->updateFromYUV420P(yuvFrame.getYPlane(), yuvFrame.getUPlane(), yuvFrame.getVPlane())) {
        std::cerr << "[PreviewController] Failed to upload YUV texture\n";
        return false;
    }

    // Cache the texture in renderer
    renderer->cacheYUVTexture(clipId, yuvTexture);

    std::cout << "[PreviewController] Cached YUV texture for clip " << clipId
              << " at time " << clipLocalMs << " ms (timeline " << timeMs << " ms)\n";

    return true;
}

void PreviewController::clearTextureCache() {
    if (renderer) {
        renderer->clearYUVTextureCache();
    }
}

void PreviewController::close() {
    clearTextureCache();

    // Close all decoders
    decoders.clear();

    // Close renderer
    if (renderer) {
        renderer.reset();
    }

    std::cout << "[PreviewController] Closed\n";
}

VideoDecoder* PreviewController::getOrCreateDecoder(const std::shared_ptr<Clip>& clip) {
    if (!clip) {
        return nullptr;
    }

    uint32_t clipId = clip->getId();
    auto it = decoders.find(clipId);

    if (it != decoders.end()) {
        return it->second.get();
    }

    // Create new decoder for this clip
    auto decoder = std::make_unique<VideoDecoder>();
    if (!decoder->open(clip->getMediaPath())) {
        std::cerr << "[PreviewController] Failed to open clip: " << clip->getMediaPath() << "\n";
        return nullptr;
    }

    VideoDecoder* result = decoder.get();
    decoders[clipId] = std::move(decoder);

    std::cout << "[PreviewController] Created decoder for clip " << clipId
              << ": " << clip->getMediaPath() << "\n";

    return result;
}

}  // namespace VideoEngine

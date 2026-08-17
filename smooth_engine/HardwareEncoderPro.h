#pragma once

#include <cstdint>
#include <string>

namespace VideoEngine::Backend {

/**
 * @brief Professional Hardware Encoder (MediaCodec Pro).
 * 
 * Specifically optimized for Android to provide the fastest possible 
 * export speeds (10x faster than software) with high-quality HEVC/AV1.
 */
class HardwareEncoderPro {
public:
    struct Config {
        int width;
        int height;
        int bitrate;
        int fps;
        std::string mimeType = "video/hevc"; // H.265 for pro quality
    };

    void init(const Config& config);
    
    /**
     * @brief Encodes a frame from an OpenGL texture.
     * This is the fastest way: GPU -> Hardware Encoder (Zero Copy).
     */
    void encodeFrame(uint32_t textureId, int64_t ptsUs);

    void finish();

private:
    void* m_mediaCodec = nullptr;
    void* m_inputSurface = nullptr;
};

} // namespace VideoEngine::Backend

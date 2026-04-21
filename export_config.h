#pragma once

#include <string>
#include <cstdint>

namespace VideoEngine {

/**
 * Export configuration for video rendering.
 * Specifies target resolution, frame rate, codec, and audio settings.
 */
struct ExportConfig {
    // Output file path (absolute)
    std::string outputPath;
    
    // Video dimensions
    int32_t width = 1920;     // pixels
    int32_t height = 1080;    // pixels
    
    // Frame rate
    int32_t fps = 30;         // frames per second
    
    // Bitrate (auto-calculated, but can be overridden)
    int32_t bitrate = 5000;   // kbps
    
    // Audio settings
    bool includeAudio = true;
    int32_t audioSampleRate = 48000;  // Hz
    
    // Codec
    std::string videoCodec = "h264";  // h264, h265, etc.
    std::string audioCodec = "aac";   // aac, mp3, etc.
    
    /**
     * Calculate recommended bitrate based on resolution.
     * @return Bitrate in kbps
     */
    int32_t calculateBitrate() const {
        // Resolution-based heuristics
        if (width >= 3840) {  // 4K
            return 15000;
        } else if (width >= 1920) {  // 1080p
            return 5000;
        } else if (width >= 1280) {  // 720p
            return 2500;
        } else {  // 480p or lower
            return 1500;
        }
    }
    
    /**
     * Get human-readable resolution label.
     */
    std::string getResolutionLabel() const {
        if (width >= 3840 && height >= 2160) {
            return "4K";
        } else if (width >= 1920 && height >= 1080) {
            return "1080p";
        } else if (width >= 1280 && height >= 720) {
            return "720p";
        } else {
            return "Custom";
        }
    }
};

}  // namespace VideoEngine

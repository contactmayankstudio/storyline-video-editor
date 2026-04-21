#ifndef VIDEO_ENGINE_EXPORT_CONFIG_H
#define VIDEO_ENGINE_EXPORT_CONFIG_H

#include <string>
#include <cstdint>

namespace VideoEngine {

/**
 * Export configuration for video rendering pipeline.
 * 
 * Specifies resolution, frame rate, bitrate, codecs, and output location
 * for the export process.
 */
struct ExportConfig {
    // Output parameters
    std::string outputPath;         // Absolute path to output file
    int32_t width = 1920;           // Output width (pixels)
    int32_t height = 1080;          // Output height (pixels)
    int32_t fps = 30;               // Output frame rate
    int32_t bitrate = 8000;         // Bitrate in kbps (auto-calculated)
    
    // Audio parameters
    int32_t audioSampleRate = 48000;  // Sample rate (Hz)
    int32_t audioChannels = 2;        // Mono=1, Stereo=2
    
    // Codec selection
    std::string videoCodec = "h264";  // "h264", "hevc", "vp9"
    std::string audioCodec = "aac";   // "aac", "opus"
    
    /**
     * Auto-calculate bitrate based on resolution and fps.
     * 
     * Formula: width * height * fps * quality_factor
     * Quality factor: 0.1 for SD, 0.15 for HD, 0.2 for 4K
     */
    int32_t calculateBitrate() const {
        // Pixels per second
        int32_t pixelsPerSecond = width * height * fps;
        
        // Quality factor based on resolution
        float qualityFactor = 0.1f;  // Default for SD
        if (width >= 1920 && height >= 1080) {
            qualityFactor = 0.2f;  // 4K quality
        } else if (width >= 1280 && height >= 720) {
            qualityFactor = 0.15f;  // HD quality
        }
        
        // Target bitrate in kbps
        int32_t calculatedBitrate = (int32_t)(pixelsPerSecond * qualityFactor / 1000);
        
        // Clamp to reasonable range
        if (calculatedBitrate < 500) calculatedBitrate = 500;
        if (calculatedBitrate > 50000) calculatedBitrate = 50000;
        
        return calculatedBitrate;
    }
    
    /**
     * Get human-readable resolution label.
     */
    std::string getResolutionLabel() const {
        if (width == 1920 && height == 1080) return "1080p";
        if (width == 1280 && height == 720) return "720p";
        if (width == 3840 && height == 2160) return "4K";
        if (width == 2560 && height == 1440) return "1440p";
        return std::to_string(width) + "x" + std::to_string(height);
    }
};

}  // namespace VideoEngine

#endif  // VIDEO_ENGINE_EXPORT_CONFIG_H

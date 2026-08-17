#pragma once

#include <string>
#include <memory>
#include <functional>
#include <cstdint>
#include "../../export_config.h"
#include "../../smooth_engine/HardwareEncoderPro.h"

namespace VideoEngine {

// Forward declarations
class Timeline;
struct RenderGraph;

namespace GPU {
    class PreviewRenderer;
    using PreviewRendererPtr = std::shared_ptr<PreviewRenderer>;
}

/**
 * ExportController: Render timeline with text overlays to video file.
 * 
 * Usage:
 *   ExportController exporter(timeline, config);
 *   exporter.export(progress_callback);
 * 
 * Architecture:
 * - Uses PreviewRenderer in headless mode (same pipeline as preview)
 * - Iterates through all frames at configured FPS
 * - Reads back framebuffer (RGBA) after each render
 * - Converts RGBA to YUV420P
 * - Encodes with FFmpeg (libx264, libx265, etc)
 * 
 * Text overlays automatically included in export (same rendering path as preview).
 * Result: WYSIWYG (what you see is what you get) between preview and export.
 */
class ExportController {
public:
    using ProgressCallback = std::function<void(int frameNum, int totalFrames, const std::string& stage)>;

    /**
     * Create export controller.
     * @param timeline Timeline with clips and text overlays
     * @param config Export settings (resolution, fps, codec, bitrate)
     */
    ExportController(std::shared_ptr<Timeline> timeline, const ExportConfig& config);

    ~ExportController();

    // Non-copyable
    ExportController(const ExportController&) = delete;
    ExportController& operator=(const ExportController&) = delete;

    /**
     * Export timeline to video file.
     * 
     * @param progress Callback invoked for each frame (frameNum, totalFrames, stage)
     *                 Stages: "Initialize", "Rendering", "Encoding", "Finalize"
     * @return true if export succeeded, false on error
     * @throw std::runtime_error on fatal errors (file I/O, codec, etc)
     */
    bool exportToVideo(ProgressCallback progress = nullptr);

    /**
     * Get error message from last failed export.
     */
    std::string getLastError() const { return m_lastError; }

    /**
     * Get estimated total frames to export.
     */
    uint64_t getTotalFrames() const { return m_totalFrames; }

    /**
     * Get estimated export duration in seconds.
     */
    double getEstimatedDurationSeconds() const {
        return m_totalFrames / static_cast<double>(m_config.fps);
    }

    /**
     * Cancel export (can be called from progress callback).
     * Next frame render will exit gracefully.
     */
    void cancelExport() { m_cancelRequested = true; }

    /**
     * Check if export is in progress.
     */
    bool isExporting() const { return m_isExporting; }

private:
    std::shared_ptr<Timeline> m_timeline;
    ExportConfig m_config;
    GPU::PreviewRendererPtr m_renderer;
    
    std::string m_lastError;
    uint64_t m_totalFrames = 0;
    bool m_isExporting = false;
    bool m_cancelRequested = false;
    int m_encodeFrameNumber = 0;  // per-export frame counter (not static)
    std::unique_ptr<VideoEngine::Backend::HardwareEncoderPro> m_hardwareEncoder;
    bool m_hardwareEncoderPrepared = false;

    /**
     * Read framebuffer pixels as RGBA8 (4 bytes per pixel).
     * @return Pixel data (width × height × 4 bytes) or empty on error
     */
    std::vector<uint8_t> readFramebufferRGBA();

    /**
     * Convert RGBA8 frame to YUV420P (3 separate planes).
     * Input:  RGBA8 (width × height × 4 bytes)
     * Output: YUV420P (width × height + width/2 × height/2 × 2 bytes)
     * 
     * @param rgbaPixels RGBA pixel data
     * @param yBuffer Output Y plane buffer (width × height)
     * @param uBuffer Output U plane buffer (width/2 × height/2)
     * @param vBuffer Output V plane buffer (width/2 × height/2)
     */
    void convertRGBAtoYUV420P(
        const std::vector<uint8_t>& rgbaPixels,
        std::vector<uint8_t>& yBuffer,
        std::vector<uint8_t>& uBuffer,
        std::vector<uint8_t>& vBuffer
    );

    /**
     * Initialize FFmpeg encoder (internal).
     * Sets up codec, fileoutput, formats, etc.
     */
    bool initializeFFmpegEncoder();

    /**
     * Encode a single YUV420P frame.
     */
    bool encodeYUVFrame(
        const uint8_t* yData,
        const uint8_t* uData,
        const uint8_t* vData
    );

    /**
     * Finalize FFmpeg encoder (write trailers, close file).
     */
    bool finalizeFFmpegEncoder();
    void prepareHardwareEncoderBackend();
    void releaseHardwareEncoderBackend();
    void writeAutoCaptionSidecar();
    std::string resolveAutoCaptionSourcePath() const;

    // FFmpeg opaque context
    struct FFmpegContext;
    std::unique_ptr<FFmpegContext> m_ffmpegCtx;
};

using ExportControllerPtr = std::shared_ptr<ExportController>;

} // namespace VideoEngine

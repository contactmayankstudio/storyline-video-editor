#pragma once

#include <string>
#include <memory>
#include <functional>
#include <stdexcept>
#include "engine/engine.h"

namespace VideoEngine::Backend {

/**
 * Encapsulates FFmpeg decoding, compositing, and encoding.
 * 
 * Public API hides FFmpeg implementation details.
 * Thread-safe for independent renderer instances (one renderer per thread).
 * 
 * Usage:
 *   FFmpegRenderer renderer(timeline_props);
 *   renderer.render(renderGraph, output_path, progress_callback);
 */
class FFmpegRenderer {
public:
    struct RenderConfig {
        std::string outputCodec = "libx264";      // H.264
        std::string outputPixelFormat = "yuv420p"; // YUV 4:2:0
        int bitrate = 5000;                        // kbps
        int preset = 3;                            // 0=slow/best, 10=fast/lower-quality
    };

    using ProgressCallback = std::function<void(int frameNum, int totalFrames)>;

    /**
     * Construct renderer with timeline resolution and frame rate.
     * Initializes FFmpeg global state once per process.
     */
    explicit FFmpegRenderer(uint32_t width, uint32_t height, uint32_t fps);

    ~FFmpegRenderer();

    // Non-copyable, movable
    FFmpegRenderer(const FFmpegRenderer&) = delete;
    FFmpegRenderer& operator=(const FFmpegRenderer&) = delete;
    FFmpegRenderer(FFmpegRenderer&&) noexcept = default;
    FFmpegRenderer& operator=(FFmpegRenderer&&) noexcept = default;

    /**
     * Render RenderGraph to output video file.
     * 
     * @param renderGraph Pre-built graph from Timeline
     * @param outputPath Output file path (e.g., "out.mp4")
     * @param config Output encoding settings
     * @param progress Optional callback for frame progress reporting
     * @throws std::runtime_error on FFmpeg errors (file I/O, codec, etc)
     */
    void render(const RenderGraph& renderGraph,
                const std::string& outputPath,
                const RenderConfig& config,
                ProgressCallback progress = nullptr);

    // Convenience: render with default config
    void render(const RenderGraph& renderGraph,
                const std::string& outputPath);

private:
    uint32_t width_;
    uint32_t height_;
    uint32_t fps_;

    // FFmpeg opaque context handles (forward-declared, defined in cpp)
    struct FFmpegContext;
    std::unique_ptr<FFmpegContext> ctx_;

    // Internal: initialize global FFmpeg state (once per process)
    static bool initializeFFmpeg();
    static bool ffmpegInitialized_;

    // Internal: decode a single frame from a clip at a given time
    struct DecodedFrame {
        uint8_t* data[4] = {};      // RGBA or YUV planes
        int linesize[4] = {};
        int width = 0;
        int height = 0;
    };

    // Decode frame from clip at given time offset within the clip
    DecodedFrame decodeClipFrame(const ClipPtr& clip, TimeMs offsetInClipMs);

    // Composite multiple frames (RGBA) with alpha blending
    // Writes result to outFrame (must be allocated)
    void compositeFrames(const std::vector<DecodedFrame>& frames,
                        uint8_t* outFrame, int outLinesize);

    // Convert YUV frame to RGBA for compositing
    DecodedFrame yuvToRgba(const DecodedFrame& yuvFrame);

    // Convert RGBA frame to output codec format
    DecodedFrame rgbaToOutput(const DecodedFrame& rgbaFrame,
                             const std::string& outputPixelFormat);
};

class FFmpegRenderException : public std::runtime_error {
public:
    explicit FFmpegRenderException(const std::string& msg)
        : std::runtime_error(msg) {}
};

} // namespace VideoEngine::Backend

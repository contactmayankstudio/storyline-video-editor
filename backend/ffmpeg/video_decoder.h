#pragma once

#include <string>
#include <vector>
#include <cstdint>

// Forward declarations to avoid including FFmpeg headers in public API
struct AVFormatContext;
struct AVCodecContext;
struct AVFrame;
struct SwsContext;

namespace VideoEngine::Backend {

/**
 * Decoded preview frame with tightly packed pixels.
 * The preview pipeline now prefers RGBA to avoid an extra CPU-side copy.
 */
struct DecodedFrame {
    uint32_t width;
    uint32_t height;
    int64_t ptsMs = 0;
    std::vector<uint8_t> rgb;  // Preview pixels: RGBA32 preferred, RGB24 fallback
};

/**
 * Decoded video frame with YUV420P planes (kept on GPU, not CPU-converted).
 * 
 * YUV420P layout:
 * - Y plane: width × height bytes (one byte per pixel)
 * - U plane: (width/2) × (height/2) bytes (one byte per 2×2 block)
 * - V plane: (width/2) × (height/2) bytes (one byte per 2×2 block)
 * 
 * Total size: width × height × 1.5 bytes (packed contiguously: Y..., U..., V...)
 * 
 * Designed for:
 * - Direct GPU upload (avoid CPU RGB conversion cost)
 * - Shader-based YUV→RGB conversion (BT.709 color matrix)
 * - Minimal CPU-GPU data movement
 */
struct YUVFrame {
    uint32_t width;
    uint32_t height;
    int64_t ptsMs = 0;
    std::vector<uint8_t> planes;  // YUV420P packed: Y plane, U plane, V plane
    
    /**
     * Get pointer to Y plane.
     * @return Pointer to first byte of Y plane
     */
    uint8_t* getYPlane() { return planes.data(); }
    const uint8_t* getYPlane() const { return planes.data(); }
    
    /**
     * Get size of Y plane in bytes.
     * @return width × height
     */
    size_t getYPlaneSize() const { return static_cast<size_t>(width) * height; }
    
    /**
     * Get pointer to U plane (starts after Y plane).
     * @return Pointer to first byte of U plane
     */
    uint8_t* getUPlane() { return planes.data() + getYPlaneSize(); }
    const uint8_t* getUPlane() const { return planes.data() + getYPlaneSize(); }
    
    /**
     * Get size of U plane in bytes.
     * @return (width/2) × (height/2)
     */
    size_t getUPlaneSize() const { return (static_cast<size_t>(width) * height) / 4; }
    
    /**
     * Get pointer to V plane (starts after Y and U planes).
     * @return Pointer to first byte of V plane
     */
    uint8_t* getVPlane() { return planes.data() + getYPlaneSize() + getUPlaneSize(); }
    const uint8_t* getVPlane() const { return planes.data() + getYPlaneSize() + getUPlaneSize(); }
    
    /**
     * Get size of V plane in bytes.
     * @return (width/2) × (height/2)
     */
    size_t getVPlaneSize() const { return (static_cast<size_t>(width) * height) / 4; }
};

/**
 * FFmpeg-based video decoder for decoding frames from video files.
 * 
 * Designed for:
 * - Single-frame-per-call decoding pattern
 * - RAII resource cleanup
 * - Thread-safe usage (no global state)
 * - Android NDK compatibility
 * 
 * Example usage:
 *   VideoDecoder decoder;
 *   if (decoder.open("video.mp4")) {
 *       DecodedFrame frame;
 *       while (decoder.decodeNextFrame(frame)) {
 *           // Process frame with frame.width, frame.height, frame.rgb
 *       }
 *       decoder.close();
 *   }
 */
class VideoDecoder {
public:
    VideoDecoder();
    ~VideoDecoder();

    // Non-copyable
    VideoDecoder(const VideoDecoder&) = delete;
    VideoDecoder& operator=(const VideoDecoder&) = delete;

    /**
     * Open and prepare a video file for decoding.
     * 
     * @param path Absolute or relative path to video file
     * @return true if file opened and video stream found, false otherwise
     */
    bool open(const std::string& path);

    /**
     * Decode the next frame from the video file.
     * 
     * @param out Reference to DecodedFrame struct to fill with decoded data
     * @return true if a frame was decoded, false if EOF or error
     */
    bool decodeNextFrame(DecodedFrame& out);

    /**
     * Decode a frame at a specific timeline time (raw YUV420P, kept on CPU for GPU upload).
     * 
     * CRITICAL FOR GPU PIPELINE:
     * - Returns YUV420P planes, NOT RGB (avoid CPU conversion cost)
     * - Reuses internal AVFrame buffer (no per-call allocation)
     * - Designed for fast scrubbing: seek + decode + GPU upload
     * - Use seekForPreview() + decodeFrameAt() for timeline scrubbing
     * 
     * Performance:
     * - FFmpeg seek: 5-10ms (seekForPreview flag)
     * - Decode: 2-5ms (hardware-accelerated if available)
     * - Return immediately after decode (GPU upload happens in caller)
     * 
     * YUV420P output:
     * - Y plane: width × height (full resolution luminance)
     * - U plane: (width/2) × (height/2) (downsampled chroma)
     * - V plane: (width/2) × (height/2) (downsampled chroma)
     * 
     * GPU shader receives all 3 planes in separate textures, applies BT.709 matrix.
     * 
     * @param timeMs Target time in milliseconds
     * @param outFrame Reference to YUVFrame to fill with decoded planes
     * @return true if frame decoded successfully, false on seek/decode error
     */
    bool decodeFrameAt(int64_t timeMs, YUVFrame& outFrame);

    /**
     * Seek to a specific time in the video file (accurate seek).
     * Used for export, precise frame positioning, timeline operations.
     * Slower but reliable - seeks to nearest keyframe and buffers frames.
     * 
     * @param timeMs Target time in milliseconds
     * @return true if seek succeeded
     */
    bool seekTo(int64_t timeMs);

    /**
     * Seek to a specific time for preview/scrubbing (fast approximate seek).
     * Optimized for real-time UI scrubbing, Storyline-style previews.
     * Uses AVSEEK_FLAG_ANY for instant feedback - may decode non-keyframe.
     * 
     * Trade-off: Fast response, slight visual artifacts possible (acceptable for preview).
     * Use this for: Timeline scrubbing, frame preview, quick positioning.
     * Do NOT use this for: Export, frame-accurate operations.
     * 
     * @param timeMs Target time in milliseconds
     * @return true if seek succeeded
     */
    bool seekForPreview(int64_t timeMs);

    /**
     * Seek to nearest keyframe before/at target for ultra-fast scrubbing.
     * This intentionally does NOT advance toward exact target time.
     * The next decoded frame will be keyframe-aligned for low CPU load.
     *
     * Use case:
     * - High-velocity timeline dragging where responsiveness matters
     * - Show keyframes only and skip expensive frame-accurate decode
     *
     * @param timeMs Target timeline time in milliseconds
     * @return true if seek succeeded
     */
    bool seekToKeyframeForPreview(int64_t timeMs);

    /**
     * Configure preview downscaling for low-latency editing.
     * 0 disables scaling and keeps source resolution.
     *
     * @param maxLongEdgePx Maximum output long edge in pixels (for example 640 for ~360p).
     */
    void setPreviewScaleLimit(int maxLongEdgePx);

    /**
     * Close the video file and release all resources.
     */
    void close();

    /**
     * Check if a video file is currently open.
     * @return true if decoder is ready to decode frames
     */
    bool isOpen() const;

    /**
     * Get video duration in seconds (if available).
     * @return duration in seconds, or -1 if not available
     */
    double getDuration() const;

    /**
     * Get frames per second of the video.
     * @return FPS value, or 0 if not available
     */
    double getFps() const;

    /**
     * Get video width in pixels.
     * @return width, or 0 if not available
     */
    int getWidth() const;

    /**
     * Get video height in pixels.
     * @return height, or 0 if not available
     */
    int getHeight() const;

    /**
     * Get last error message, if any.
     */
    const char* getLastError() const { return m_lastError.c_str(); }

private:
    // FFmpeg context pointers
    AVFormatContext* m_formatContext;
    AVCodecContext* m_codecContext;
    int m_videoStreamIndex;
    
    // Decoding resources
    AVFrame* m_decodedFrame;
    AVFrame* m_rgbFrame;
    SwsContext* m_swsContext;
    int m_swsSourceWidth;
    int m_swsSourceHeight;
    int m_swsSourcePixelFormat;
    int m_swsOutputWidth;
    int m_swsOutputHeight;
    int m_previewScaleLimitLongEdgePx;
    
    // Metadata
    double m_fps;
    double m_duration;
    bool m_isOpen;
    int64_t m_pendingSeekTargetMs;
    std::string m_lastError;

    /**
     * Internal: Find video stream index in the format context.
     * @return Stream index or -1 if not found
     */
    int findVideoStream();

    /**
     * Internal: Initialize libswscale context for RGB24 conversion.
     * @return true on success
     */
    bool initializeSwsContext(int srcWidth, int srcHeight, int srcPixelFormat);

    /**
     * Internal: Convert decoded frame to RGB24.
     * @param srcFrame The decoded frame from decoder
     * @param out The output DecodedFrame structure
     * @return true on success
     */
    bool convertToRGB24(AVFrame* srcFrame, DecodedFrame& out);

    /**
     * Internal: Release all FFmpeg resources.
     */
    void releaseResources();

    /**
     * Internal: Set error message.
     */
    void setError(const char* fmt, ...);
};

}  // namespace VideoEngine::Backend

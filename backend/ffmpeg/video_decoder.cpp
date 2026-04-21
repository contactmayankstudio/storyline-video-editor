#include "video_decoder.h"

#include <iostream>
#include <cstring>
#include <stdexcept>
#include <cstdarg>

// FFmpeg headers (only when available for this ABI)
#if defined(VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE)
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>
}
#endif

namespace VideoEngine::Backend {

#if defined(VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE)

namespace {
int64_t resolveFramePtsMs(const AVFrame* frame, const AVStream* stream) {
    if (!frame || !stream) {
        return 0;
    }
    int64_t timestamp = frame->best_effort_timestamp;
    if (timestamp == AV_NOPTS_VALUE) {
        timestamp = frame->pts;
    }
    if (timestamp == AV_NOPTS_VALUE) {
        return 0;
    }
    return av_rescale_q(timestamp, stream->time_base, AVRational{1, 1000});
}
}  // namespace

VideoDecoder::VideoDecoder()
    : m_formatContext(nullptr)
    , m_codecContext(nullptr)
    , m_videoStreamIndex(-1)
    , m_decodedFrame(nullptr)
    , m_rgbFrame(nullptr)
    , m_swsContext(nullptr)
    , m_swsSourceWidth(0)
    , m_swsSourceHeight(0)
    , m_swsSourcePixelFormat(-1)
    , m_swsOutputWidth(0)
    , m_swsOutputHeight(0)
    , m_previewScaleLimitLongEdgePx(0)
    , m_fps(0.0)
    , m_duration(0.0)
    , m_isOpen(false)
    , m_pendingSeekTargetMs(-1)
{
}

VideoDecoder::~VideoDecoder() {
    close();
}

bool VideoDecoder::open(const std::string& path) {
    if (m_isOpen) {
        close();
    }

    // Open input file
    m_formatContext = avformat_alloc_context();
    if (!m_formatContext) {
        setError("Failed to allocate format context");
        return false;
    }

    if (avformat_open_input(&m_formatContext, path.c_str(), nullptr, nullptr) != 0) {
        setError("Failed to open file: %s", path.c_str());
        avformat_free_context(m_formatContext);
        m_formatContext = nullptr;
        return false;
    }

    // Find streams
    if (avformat_find_stream_info(m_formatContext, nullptr) < 0) {
        setError("Failed to find stream information");
        avformat_close_input(&m_formatContext);
        m_formatContext = nullptr;
        return false;
    }

    // Find video stream
    m_videoStreamIndex = findVideoStream();
    if (m_videoStreamIndex < 0) {
        setError("No video stream found");
        avformat_close_input(&m_formatContext);
        m_formatContext = nullptr;
        return false;
    }

    AVStream* videoStream = m_formatContext->streams[m_videoStreamIndex];

    // Get codec parameters
    const AVCodec* codec = avcodec_find_decoder(videoStream->codecpar->codec_id);
    if (!codec) {
        setError("Codec not found for stream");
        avformat_close_input(&m_formatContext);
        m_formatContext = nullptr;
        return false;
    }

    // Create codec context
    m_codecContext = avcodec_alloc_context3(codec);
    if (!m_codecContext) {
        setError("Failed to allocate codec context");
        avformat_close_input(&m_formatContext);
        m_formatContext = nullptr;
        return false;
    }

    // Copy codec parameters to context
    if (avcodec_parameters_to_context(m_codecContext, videoStream->codecpar) < 0) {
        setError("Failed to copy codec parameters");
        avcodec_free_context(&m_codecContext);
        avformat_close_input(&m_formatContext);
        m_formatContext = nullptr;
        return false;
    }

    // Use 2 threads for preview decode — enough for smooth playback, avoids CPU contention
    m_codecContext->thread_count = 2;
    m_codecContext->thread_type = FF_THREAD_FRAME;

    // Open codec
    if (avcodec_open2(m_codecContext, codec, nullptr) < 0) {
        setError("Failed to open codec");
        avcodec_free_context(&m_codecContext);
        avformat_close_input(&m_formatContext);
        m_formatContext = nullptr;
        return false;
    }

    // Allocate frames
    m_decodedFrame = av_frame_alloc();
    m_rgbFrame = av_frame_alloc();
    if (!m_decodedFrame || !m_rgbFrame) {
        setError("Failed to allocate frames");
        releaseResources();
        return false;
    }

    // Initialize libswscale context
    if (!initializeSwsContext(
            m_codecContext->width,
            m_codecContext->height,
            m_codecContext->pix_fmt)) {
        if (m_lastError.empty()) {
            setError("Failed to initialize swscale context");
        }
        releaseResources();
        return false;
    }

    // Calculate metadata
    m_fps = av_q2d(videoStream->r_frame_rate);
    if (m_formatContext->duration > 0) {
        m_duration = static_cast<double>(m_formatContext->duration) / AV_TIME_BASE;
    }

    m_isOpen = true;
    std::cout << "[VideoDecoder] Opened " << path 
              << " (" << m_codecContext->width << "x" << m_codecContext->height
              << " @ " << m_fps << " fps, duration: " << m_duration << "s)\n";

    return true;
}

bool VideoDecoder::decodeNextFrame(DecodedFrame& out) {
    if (!m_isOpen) {
        setError("Decoder not open");
        return false;
    }

    AVPacket packet;
    av_init_packet(&packet);
    packet.data = nullptr;
    packet.size = 0;

    bool frameDecoded = false;
    const int64_t pendingSeekTargetMs = m_pendingSeekTargetMs;
    AVStream* videoStream = m_formatContext->streams[m_videoStreamIndex];

    // Read packets until we get a complete video frame
    while (av_read_frame(m_formatContext, &packet) >= 0) {
        if (packet.stream_index == m_videoStreamIndex) {
            // Send packet to decoder
            if (avcodec_send_packet(m_codecContext, &packet) < 0) {
                setError("Error sending packet to decoder");
                av_packet_unref(&packet);
                return false;
            }

            // Try to receive decoded frame
            int ret = avcodec_receive_frame(m_codecContext, m_decodedFrame);
            if (ret == 0) {
                const int64_t framePtsMs =
                    resolveFramePtsMs(m_decodedFrame, videoStream);
                
                // If we are seeking, skip frames until we are within 1ms of target or past it
                if (pendingSeekTargetMs >= 0) {
                    if (framePtsMs < (pendingSeekTargetMs - 1)) {
                        av_packet_unref(&packet);
                        continue;
                    }
                    // We found or passed the target!
                    m_pendingSeekTargetMs = -1;
                }

                // Frame decoded successfully
                if (convertToRGB24(m_decodedFrame, out)) {
                    out.ptsMs = framePtsMs;
                    frameDecoded = true;
                }
                av_packet_unref(&packet);
                break;
            } else if (ret == AVERROR(EAGAIN)) {
                // More data needed, continue reading
                av_packet_unref(&packet);
                continue;
            } else if (ret == AVERROR_EOF) {
                // End of stream
                av_packet_unref(&packet);
                break;
            } else {
                // Error
                setError("Error decoding frame");
                av_packet_unref(&packet);
                break;
            }
        }

        av_packet_unref(&packet);
    }

    if (!frameDecoded) {
        // Flush decoder on EOF
        if (avcodec_send_packet(m_codecContext, nullptr) >= 0) {
            if (avcodec_receive_frame(m_codecContext, m_decodedFrame) == 0) {
                const int64_t framePtsMs =
                    resolveFramePtsMs(m_decodedFrame, videoStream);
                frameDecoded = convertToRGB24(m_decodedFrame, out);
                if (frameDecoded) {
                    out.ptsMs = framePtsMs;
                    m_pendingSeekTargetMs = -1;
                }
            }
        }
    }

    return frameDecoded;
}

void VideoDecoder::close() {
    releaseResources();
    m_isOpen = false;
    m_pendingSeekTargetMs = -1;
}

bool VideoDecoder::isOpen() const {
    return m_isOpen;
}

double VideoDecoder::getDuration() const {
    return m_duration;
}

double VideoDecoder::getFps() const {
    return m_fps;
}

int VideoDecoder::getWidth() const {
    return m_codecContext ? m_codecContext->width : 0;
}

int VideoDecoder::getHeight() const {
    return m_codecContext ? m_codecContext->height : 0;
}

int VideoDecoder::findVideoStream() {
    if (!m_formatContext) return -1;

    for (unsigned int i = 0; i < m_formatContext->nb_streams; ++i) {
        if (m_formatContext->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            return i;
        }
    }

    return -1;
}

bool VideoDecoder::initializeSwsContext(int srcWidth, int srcHeight, int srcPixelFormat) {
    if (!m_codecContext || !m_rgbFrame) return false;

    if (m_swsContext) {
        sws_freeContext(m_swsContext);
        m_swsContext = nullptr;
    }

    if (m_rgbFrame->data[0]) {
        av_free(m_rgbFrame->data[0]);
        m_rgbFrame->data[0] = nullptr;
    }

    int dstWidth = srcWidth;
    int dstHeight = srcHeight;
    if (m_previewScaleLimitLongEdgePx > 0 && srcWidth > 0 && srcHeight > 0) {
        const int longEdge = std::max(srcWidth, srcHeight);
        if (longEdge > m_previewScaleLimitLongEdgePx) {
            const double scale =
                static_cast<double>(m_previewScaleLimitLongEdgePx) /
                static_cast<double>(longEdge);
            dstWidth = std::max(2, static_cast<int>(srcWidth * scale));
            dstHeight = std::max(2, static_cast<int>(srcHeight * scale));
        }
    }

    m_swsContext = sws_getContext(
        srcWidth,
        srcHeight,
        static_cast<AVPixelFormat>(srcPixelFormat),
        dstWidth,
        dstHeight,
        AV_PIX_FMT_RGBA,
        SWS_FAST_BILINEAR,
        nullptr,
        nullptr,
        nullptr
    );

    if (!m_swsContext) {
        setError("Failed to create SwsContext");
        return false;
    }

    // Allocate packed RGBA buffer for m_rgbFrame so preview can upload directly.
    int bufferSize = av_image_get_buffer_size(
        AV_PIX_FMT_RGBA,
        dstWidth,
        dstHeight,
        1
    );

    if (bufferSize < 0) {
        setError("Failed to get buffer size");
        sws_freeContext(m_swsContext);
        m_swsContext = nullptr;
        return false;
    }

    uint8_t* buffer = static_cast<uint8_t*>(av_malloc(bufferSize));
    if (!buffer) {
        setError("Failed to allocate buffer");
        sws_freeContext(m_swsContext);
        m_swsContext = nullptr;
        return false;
    }

    av_image_fill_arrays(
        m_rgbFrame->data,
        m_rgbFrame->linesize,
        buffer,
        AV_PIX_FMT_RGBA,
        dstWidth,
        dstHeight,
        1
    );

    m_swsSourceWidth = srcWidth;
    m_swsSourceHeight = srcHeight;
    m_swsSourcePixelFormat = srcPixelFormat;
    m_swsOutputWidth = dstWidth;
    m_swsOutputHeight = dstHeight;

    return true;
}

bool VideoDecoder::convertToRGB24(AVFrame* srcFrame, DecodedFrame& out) {
    if (!srcFrame || !m_rgbFrame || !m_codecContext) {
        setError("Preview conversion context not initialized");
        return false;
    }

    if (!m_swsContext ||
        m_swsSourceWidth != srcFrame->width ||
        m_swsSourceHeight != srcFrame->height ||
        m_swsSourcePixelFormat != srcFrame->format) {
        if (!initializeSwsContext(srcFrame->width, srcFrame->height, srcFrame->format)) {
            setError("Failed to reinitialize preview converter for frame format %d", srcFrame->format);
            return false;
        }
        std::cout << "[VideoDecoder] Preview converter source format="
                  << srcFrame->format << " size="
                  << srcFrame->width << "x" << srcFrame->height << "\n";
    }

    // Convert frame to packed RGBA in one pass.
    int height = sws_scale(
        m_swsContext,
        srcFrame->data,
        srcFrame->linesize,
        0,
        srcFrame->height,
        m_rgbFrame->data,
        m_rgbFrame->linesize
    );

    if (height <= 0) {
        setError("Failed to scale frame");
        return false;
    }

    // Copy packed RGBA data to output.
    out.width = static_cast<uint32_t>(std::max(1, m_swsOutputWidth));
    out.height = static_cast<uint32_t>(std::max(1, m_swsOutputHeight));

    const int rowBytes = out.width * 4;  // RGBA32 = 4 bytes per pixel
    const int bufferSize = rowBytes * out.height;
    out.rgb.resize(bufferSize);

    const uint8_t* srcBase = m_rgbFrame->data[0];
    const int srcStride = m_rgbFrame->linesize[0];
    if (!srcBase || srcStride == 0) {
        setError("Preview frame buffer is invalid");
        return false;
    }

    for (int y = 0; y < out.height; ++y) {
        const uint8_t* srcRow = srcStride > 0
            ? (srcBase + (y * srcStride))
            : (srcBase + ((out.height - 1 - y) * (-srcStride)));
        std::memcpy(out.rgb.data() + (y * rowBytes), srcRow, rowBytes);
    }

    return true;
}

void VideoDecoder::releaseResources() {
    if (m_rgbFrame && m_rgbFrame->data[0]) {
        av_free(m_rgbFrame->data[0]);
    }

    if (m_rgbFrame) {
        av_frame_free(&m_rgbFrame);
    }

    if (m_decodedFrame) {
        av_frame_free(&m_decodedFrame);
    }

    if (m_swsContext) {
        sws_freeContext(m_swsContext);
        m_swsContext = nullptr;
    }

    if (m_codecContext) {
        avcodec_free_context(&m_codecContext);
    }

    if (m_formatContext) {
        avformat_close_input(&m_formatContext);
        m_formatContext = nullptr;
    }

    m_videoStreamIndex = -1;
    m_pendingSeekTargetMs = -1;
    m_swsSourceWidth = 0;
    m_swsSourceHeight = 0;
    m_swsSourcePixelFormat = -1;
    m_swsOutputWidth = 0;
    m_swsOutputHeight = 0;
}

void VideoDecoder::setPreviewScaleLimit(int maxLongEdgePx) {
    m_previewScaleLimitLongEdgePx = std::max(0, maxLongEdgePx);
    // Force converter re-init on next frame decode.
    m_swsSourceWidth = 0;
    m_swsSourceHeight = 0;
}

bool VideoDecoder::seekTo(int64_t timeMs) {
    if (!m_isOpen || !m_formatContext) {
        setError("Decoder not open");
        return false;
    }

    AVStream* videoStream = m_formatContext->streams[m_videoStreamIndex];
    int64_t seekTarget = av_rescale_q(
        timeMs,
        AVRational{1, 1000},
        videoStream->time_base);

    // Accurate seek: AVSEEK_FLAG_BACKWARD ensures we get reliable keyframe
    // Safe for export and frame-accurate operations
    int ret = av_seek_frame(m_formatContext, m_videoStreamIndex, seekTarget, AVSEEK_FLAG_BACKWARD);
    if (ret < 0) {
        setError("Seek failed");
        return false;
    }

    // Flush codec buffers after seek
    if (m_codecContext) {
        avcodec_flush_buffers(m_codecContext);
    }

    m_pendingSeekTargetMs = timeMs;
    return true;
}

bool VideoDecoder::seekForPreview(int64_t timeMs) {
    if (!m_isOpen || !m_formatContext) {
        setError("Decoder not open");
        return false;
    }

    AVStream* videoStream = m_formatContext->streams[m_videoStreamIndex];
    int64_t seekTarget = av_rescale_q(
        timeMs,
        AVRational{1, 1000},
        videoStream->time_base);

    // Reliable seek for preview/scrubbing:
    // AVSEEK_FLAG_BACKWARD ensures we seek to a keyframe BEFORE or AT target time.
    // This allows decodeNextFrame to skip forward accurately to the exact target.
    int ret = av_seek_frame(m_formatContext, m_videoStreamIndex, seekTarget, AVSEEK_FLAG_BACKWARD);
    if (ret < 0) {
        setError("Preview seek failed");
        return false;
    }

    // Flush codec buffers after seek
    if (m_codecContext) {
        avcodec_flush_buffers(m_codecContext);
    }

    m_pendingSeekTargetMs = timeMs;
    return true;
}

bool VideoDecoder::seekToKeyframeForPreview(int64_t timeMs) {
    if (!m_isOpen || !m_formatContext) {
        setError("Decoder not open");
        return false;
    }

    AVStream* videoStream = m_formatContext->streams[m_videoStreamIndex];
    const int64_t seekTarget = av_rescale_q(
        timeMs,
        AVRational{1, 1000},
        videoStream->time_base);

    // Keyframe-only seek for high-speed scrubbing.
    // Keep AVSEEK_FLAG_BACKWARD and clear pending target so decodeNextFrame()
    // returns earliest frame from the keyframe region immediately.
    const int ret = av_seek_frame(
        m_formatContext,
        m_videoStreamIndex,
        seekTarget,
        AVSEEK_FLAG_BACKWARD);
    if (ret < 0) {
        setError("Keyframe preview seek failed");
        return false;
    }

    if (m_codecContext) {
        avcodec_flush_buffers(m_codecContext);
    }
    m_pendingSeekTargetMs = -1;
    return true;
}

bool VideoDecoder::decodeFrameAt(int64_t timeMs, YUVFrame& outFrame) {
    if (!m_isOpen || !m_formatContext || !m_codecContext) {
        setError("Decoder not open");
        return false;
    }

    // Step 1: Fast approximate seek to timeline position
    if (!seekForPreview(timeMs)) {
        setError("Failed to seek to %lld ms", static_cast<long long>(timeMs));
        return false;
    }

    // Step 2: Decode one frame at this position
    // This uses the internal m_decodedFrame buffer (reused, no allocation)
    AVPacket packet;
    av_init_packet(&packet);
    packet.data = nullptr;
    packet.size = 0;

    bool frameDecoded = false;
    int ret = 0;
    AVStream* videoStream = m_formatContext->streams[m_videoStreamIndex];

    while (av_read_frame(m_formatContext, &packet) >= 0) {
        if (packet.stream_index == m_videoStreamIndex) {
            // Send packet to decoder
            ret = avcodec_send_packet(m_codecContext, &packet);
            if (ret < 0) {
                setError("Failed to send packet");
                av_packet_unref(&packet);
                break;
            }

            // Receive decoded frame
            ret = avcodec_receive_frame(m_codecContext, m_decodedFrame);
            if (ret == 0) {
                const int64_t resolvedPtsMs =
                    resolveFramePtsMs(m_decodedFrame, videoStream);
                const int64_t framePtsMs =
                    resolvedPtsMs > 0 ? resolvedPtsMs : timeMs;
                if (framePtsMs + 1 < timeMs) {
                    av_packet_unref(&packet);
                    continue;
                }
                outFrame.ptsMs = framePtsMs;
                frameDecoded = true;
                m_pendingSeekTargetMs = -1;
                av_packet_unref(&packet);
                break;
            } else if (ret != AVERROR(EAGAIN)) {
                setError("Failed to receive frame");
                av_packet_unref(&packet);
                break;
            }
        }
        av_packet_unref(&packet);
    }

    if (!frameDecoded) {
        setError("Failed to decode frame at %lld ms", static_cast<long long>(timeMs));
        return false;
    }

    // Step 3: Copy YUV420P planes to output (reuse buffer, no per-call allocation)
    uint32_t width = m_codecContext->width;
    uint32_t height = m_codecContext->height;

    outFrame.width = width;
    outFrame.height = height;

    // Calculate total buffer size: Y + U + V planes
    // YUV420P: width*height + (width/2)*(height/2) + (width/2)*(height/2)
    //        = width*height + width*height/4 + width*height/4
    //        = width*height * 1.5
    size_t totalSize = (width * height * 3) / 2;
    if (outFrame.planes.size() != totalSize) {
        outFrame.planes.resize(totalSize);
    }

    // Verify source frame is YUV420P
    if (m_decodedFrame->format != AV_PIX_FMT_YUV420P) {
        setError("Frame is not YUV420P");
        return false;
    }

    // Copy Y plane (full resolution)
    size_t yPlaneSize = width * height;
    uint8_t* outY = outFrame.planes.data();
    uint8_t* srcY = m_decodedFrame->data[0];
    for (uint32_t i = 0; i < height; ++i) {
        std::memcpy(outY + i * width, srcY + i * m_decodedFrame->linesize[0], width);
    }

    // Copy U plane (half resolution)
    uint32_t halfWidth = width / 2;
    uint32_t halfHeight = height / 2;
    size_t uPlaneSize = halfWidth * halfHeight;
    uint8_t* outU = outFrame.planes.data() + yPlaneSize;
    uint8_t* srcU = m_decodedFrame->data[1];
    for (uint32_t i = 0; i < halfHeight; ++i) {
        std::memcpy(outU + i * halfWidth, srcU + i * m_decodedFrame->linesize[1], halfWidth);
    }

    // Copy V plane (half resolution)
    uint8_t* outV = outFrame.planes.data() + yPlaneSize + uPlaneSize;
    uint8_t* srcV = m_decodedFrame->data[2];
    for (uint32_t i = 0; i < halfHeight; ++i) {
        std::memcpy(outV + i * halfWidth, srcV + i * m_decodedFrame->linesize[2], halfWidth);
    }

    std::cout << "[GPU Preview] frame decoded at " << timeMs << " ms (size=" << width << "x" << height << ")\n";
    return true;
}

void VideoDecoder::setError(const char* fmt, ...) {
    char buffer[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    m_lastError = buffer;
    std::cerr << "[VideoDecoder] " << m_lastError << "\n";
}

#else

VideoDecoder::VideoDecoder()
    : m_formatContext(nullptr)
    , m_codecContext(nullptr)
    , m_videoStreamIndex(-1)
    , m_decodedFrame(nullptr)
    , m_rgbFrame(nullptr)
    , m_swsContext(nullptr)
    , m_swsSourceWidth(0)
    , m_swsSourceHeight(0)
    , m_swsSourcePixelFormat(-1)
    , m_swsOutputWidth(0)
    , m_swsOutputHeight(0)
    , m_previewScaleLimitLongEdgePx(0)
    , m_fps(0.0)
    , m_duration(0.0)
    , m_isOpen(false)
    , m_pendingSeekTargetMs(-1)
{
}

VideoDecoder::~VideoDecoder() {
    close();
}

bool VideoDecoder::open(const std::string& path) {
    (void)path;
    setError("FFmpeg not available for this ABI");
    return false;
}

bool VideoDecoder::decodeNextFrame(DecodedFrame& out) {
    (void)out;
    setError("FFmpeg not available for this ABI");
    return false;
}

bool VideoDecoder::decodeFrameAt(int64_t timeMs, YUVFrame& outFrame) {
    (void)timeMs;
    (void)outFrame;
    setError("FFmpeg not available for this ABI");
    return false;
}

bool VideoDecoder::seekTo(int64_t timeMs) {
    (void)timeMs;
    setError("FFmpeg not available for this ABI");
    return false;
}

bool VideoDecoder::seekForPreview(int64_t timeMs) {
    (void)timeMs;
    setError("FFmpeg not available for this ABI");
    return false;
}

bool VideoDecoder::seekToKeyframeForPreview(int64_t timeMs) {
    (void)timeMs;
    setError("FFmpeg not available for this ABI");
    return false;
}

void VideoDecoder::setPreviewScaleLimit(int maxLongEdgePx) {
    (void)maxLongEdgePx;
}

void VideoDecoder::close() {
    m_isOpen = false;
    m_pendingSeekTargetMs = -1;
}

bool VideoDecoder::isOpen() const {
    return m_isOpen;
}

double VideoDecoder::getDuration() const {
    return 0.0;
}

double VideoDecoder::getFps() const {
    return 0.0;
}

int VideoDecoder::getWidth() const {
    return 0;
}

int VideoDecoder::getHeight() const {
    return 0;
}

int VideoDecoder::findVideoStream() {
    return -1;
}

bool VideoDecoder::initializeSwsContext(int srcWidth, int srcHeight, int srcPixelFormat) {
    (void)srcWidth;
    (void)srcHeight;
    (void)srcPixelFormat;
    return false;
}

bool VideoDecoder::convertToRGB24(AVFrame* srcFrame, DecodedFrame& out) {
    (void)srcFrame;
    (void)out;
    return false;
}

void VideoDecoder::releaseResources() {
}

void VideoDecoder::setError(const char* fmt, ...) {
    char buffer[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    m_lastError = buffer;
    std::cerr << "[VideoDecoder] " << m_lastError << "\n";
}

#endif

}  // namespace VideoEngine::Backend

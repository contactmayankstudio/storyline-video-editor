#include "frame_converter.h"

#include <cstdio>
#include <cstdarg>
#include <algorithm>

// FFmpeg headers (only when available for this ABI)
#if defined(VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE)
extern "C" {
#include <libswscale/swscale.h>
#include <libavutil/pixdesc.h>
#include <libavformat/avformat.h>  // For error strings
}
#endif

namespace VideoEngine::Backend {

#if defined(VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE)

FrameConverter::FrameConverter()
    : m_swsContext(nullptr)
    , m_cachedWidth(-1)
    , m_cachedHeight(-1)
    , m_cachedPixelFormat(-1)
{
}

FrameConverter::~FrameConverter() {
    releaseSwsContext();
}

RGBAFrame FrameConverter::convert(const AVFrame* frame) {
    RGBAFrame result;
    result.width = 0;
    result.height = 0;
    m_lastError.clear();

    if (!frame) {
        setError("Frame pointer is null");
        return result;
    }

    if (frame->width <= 0 || frame->height <= 0) {
        setError("Invalid frame dimensions: %dx%d", frame->width, frame->height);
        return result;
    }

    result.width = frame->width;
    result.height = frame->height;

    // Initialize or reinitialize swscale if format/size changed
    if (m_cachedWidth != frame->width || 
        m_cachedHeight != frame->height || 
        m_cachedPixelFormat != frame->format) {
        
        if (!initSwsContext(frame->width, frame->height, frame->format)) {
            return result;  // Error message set by initSwsContext
        }
    }

    // Allocate output buffer if needed
    size_t requiredSize = frame->width * frame->height * 4;
    if (m_buffer.size() < requiredSize) {
        try {
            m_buffer.resize(requiredSize);
        } catch (const std::bad_alloc& e) {
            setError("Memory allocation failed: %zu bytes", requiredSize);
            return result;
        }
    }

    // Set up output frame data
    uint8_t* outData[4] = { m_buffer.data(), nullptr, nullptr, nullptr };
    int outLinesize[4] = { frame->width * 4, 0, 0, 0 };

    // Perform color space conversion
    int lineCount = sws_scale(
        m_swsContext,
        frame->data,
        frame->linesize,
        0,
        frame->height,
        outData,
        outLinesize
    );

    if (lineCount != frame->height) {
        setError("libswscale conversion failed: only %d/%d lines converted",
                 lineCount, frame->height);
        return result;
    }

    // Copy converted data to result
    result.pixels = std::vector<uint8_t>(
        m_buffer.begin(),
        m_buffer.begin() + requiredSize
    );

    return result;
}

void FrameConverter::reserve(int width, int height) {
    size_t requiredSize = width * height * 4;
    if (m_buffer.capacity() < requiredSize) {
        try {
            m_buffer.reserve(requiredSize);
        } catch (const std::bad_alloc&) {
            // Silently fail - will allocate on-demand during convert()
        }
    }
}

bool FrameConverter::initSwsContext(int srcWidth, int srcHeight, int srcFormat) {
    releaseSwsContext();

    // Validate pixel format
    if (srcFormat < 0) {
        setError("Invalid pixel format: %d", srcFormat);
        return false;
    }

    // Log the source format (for debugging)
    const char* formatName = av_get_pix_fmt_name(static_cast<AVPixelFormat>(srcFormat));
    if (!formatName) formatName = "unknown";

    // Create swscale context: YUV → RGBA conversion
    // Using SWS_BICUBIC for quality (can be tuned to SWS_FAST_BILINEAR for speed)
    m_swsContext = sws_getContext(
        srcWidth, srcHeight, static_cast<AVPixelFormat>(srcFormat),
        srcWidth, srcHeight, AV_PIX_FMT_RGBA,
        SWS_BICUBIC,  // Scaling algorithm
        nullptr, nullptr, nullptr
    );

    if (!m_swsContext) {
        setError("Failed to create libswscale context for %s → RGBA (%dx%d)",
                 formatName, srcWidth, srcHeight);
        return false;
    }

    // Update cached dimensions
    m_cachedWidth = srcWidth;
    m_cachedHeight = srcHeight;
    m_cachedPixelFormat = srcFormat;

    return true;
}

void FrameConverter::releaseSwsContext() {
    if (m_swsContext) {
        sws_freeContext(m_swsContext);
        m_swsContext = nullptr;
    }
    m_cachedWidth = -1;
    m_cachedHeight = -1;
    m_cachedPixelFormat = -1;
}

void FrameConverter::setError(const char* fmt, ...) {
    char buffer[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    m_lastError = buffer;
}

#else

FrameConverter::FrameConverter()
    : m_swsContext(nullptr)
    , m_cachedWidth(-1)
    , m_cachedHeight(-1)
    , m_cachedPixelFormat(-1)
{
}

FrameConverter::~FrameConverter() {
}

RGBAFrame FrameConverter::convert(const AVFrame* frame) {
    (void)frame;
    RGBAFrame result;
    result.width = 0;
    result.height = 0;
    setError("FFmpeg not available for this ABI");
    return result;
}

void FrameConverter::reserve(int width, int height) {
    (void)width;
    (void)height;
}

bool FrameConverter::initSwsContext(int srcWidth, int srcHeight, int srcFormat) {
    (void)srcWidth;
    (void)srcHeight;
    (void)srcFormat;
    return false;
}

void FrameConverter::releaseSwsContext() {
}

void FrameConverter::setError(const char* fmt, ...) {
    char buffer[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    m_lastError = buffer;
}

#endif

}  // namespace VideoEngine::Backend

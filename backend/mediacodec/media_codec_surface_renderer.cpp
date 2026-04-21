#include "backend/mediacodec/media_codec_surface_renderer.h"
#include <android/log.h>

#define LOG_TAG "MediaCodecSurfaceRenderer"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace video_engine {

MediaCodecSurfaceRenderer::MediaCodecSurfaceRenderer()
    : m_mediaCodec(nullptr),
      m_nativeWindow(nullptr),
      m_width(0),
      m_height(0),
      m_initialized(false),
      m_running(false) {}

MediaCodecSurfaceRenderer::~MediaCodecSurfaceRenderer() {
    release();
}

bool MediaCodecSurfaceRenderer::init(
    ANativeWindow* window,
    int width,
    int height,
    const std::string& mimeType,
    const std::vector<uint8_t>& csd0,
    const std::vector<uint8_t>& csd1) {
    if (m_initialized) {
        ALOGI("MediaCodecSurfaceRenderer already initialized.");
        return true;
    }

    if (!window || width <= 0 || height <= 0 || mimeType.empty()) {
        ALOGE("Invalid parameters for MediaCodecSurfaceRenderer::init.");
        return false;
    }

    m_nativeWindow = window;
    m_width = width;
    m_height = height;
    m_mimeType = mimeType;

    m_mediaCodec = AMediaCodec_createDecoderByType(m_mimeType.c_str());
    if (!m_mediaCodec) {
        ALOGE("Failed to create MediaCodec decoder for MIME type: %s", m_mimeType.c_str());
        return false;
    }

    AMediaFormat* format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, m_mimeType.c_str());
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, m_width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, m_height);
    if (!csd0.empty()) {
        AMediaFormat_setBuffer(format, "csd-0", csd0.data(), csd0.size());
    }
    if (!csd1.empty()) {
        AMediaFormat_setBuffer(format, "csd-1", csd1.data(), csd1.size());
    }

    media_status_t status = AMediaCodec_configure(m_mediaCodec, format, m_nativeWindow, nullptr, 0);
    AMediaFormat_delete(format);

    if (status != AMEDIA_OK) {
        ALOGE("Failed to configure MediaCodec decoder: %d", status);
        AMediaCodec_delete(m_mediaCodec);
        m_mediaCodec = nullptr;
        return false;
    }

    m_initialized = true;
    ALOGI("MediaCodecSurfaceRenderer initialized successfully for %s (%dx%d).", m_mimeType.c_str(), m_width, m_height);
    return true;
}

bool MediaCodecSurfaceRenderer::start() {
    if (!m_initialized) {
        ALOGE("MediaCodecSurfaceRenderer not initialized. Cannot start.");
        return false;
    }
    if (m_running) {
        ALOGI("MediaCodecSurfaceRenderer already running.");
        return true;
    }

    media_status_t status = AMediaCodec_start(m_mediaCodec);
    if (status != AMEDIA_OK) {
        ALOGE("Failed to start MediaCodec decoder: %d", status);
        return false;
    }
    m_running = true;
    ALOGI("MediaCodecSurfaceRenderer started.");
    return true;
}

bool MediaCodecSurfaceRenderer::stop() {
    if (!m_running) {
        ALOGI("MediaCodecSurfaceRenderer not running. Cannot stop.");
        return true;
    }

    media_status_t status = AMediaCodec_stop(m_mediaCodec);
    if (status != AMEDIA_OK) {
        ALOGE("Failed to stop MediaCodec decoder: %d", status);
        return false;
    }
    m_running = false;
    ALOGI("MediaCodecSurfaceRenderer stopped.");
    return true;
}

void MediaCodecSurfaceRenderer::release() {
    if (m_running) {
        stop();
    }
    if (m_mediaCodec) {
        AMediaCodec_delete(m_mediaCodec);
        m_mediaCodec = nullptr;
        ALOGI("MediaCodecSurfaceRenderer released.");
    }
    if (m_nativeWindow) {
        // ANativeWindow is owned by the caller (via JNI Surface), don't release here.
        m_nativeWindow = nullptr;
    }
    m_initialized = false;
}

bool MediaCodecSurfaceRenderer::feedEncodedData(const uint8_t* data, size_t size, int64_t presentationTimeUs, int flags) {
    if (!m_running || !m_mediaCodec) {
        ALOGE("MediaCodec not running or not initialized. Cannot feed data.");
        return false;
    }

    ssize_t bufidx = AMediaCodec_dequeueInputBuffer(m_mediaCodec, 10000);
    if (bufidx >= 0) {
        size_t bufsize;
        uint8_t* buf = AMediaCodec_getInputBuffer(m_mediaCodec, bufidx, &bufsize);
        if (!buf || bufsize < size) {
            ALOGE("Input buffer too small (need %zu, have %zu)", size, bufsize);
            AMediaCodec_queueInputBuffer(m_mediaCodec, bufidx, 0, 0, presentationTimeUs, 0);
            return false;
        }
        std::memcpy(buf, data, size);
        AMediaCodec_queueInputBuffer(m_mediaCodec, bufidx, 0, size, presentationTimeUs, flags);
    } else if (bufidx != AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
        ALOGE("Error dequeueing input buffer: %zd", bufidx);
        return false;
    }

    AMediaCodecBufferInfo info;
    for (;;) {
        ssize_t outbufidx = AMediaCodec_dequeueOutputBuffer(m_mediaCodec, &info, 0);
        if (outbufidx >= 0) {
            const bool render = (info.size != 0);
            AMediaCodec_releaseOutputBuffer(m_mediaCodec, outbufidx, render);
        } else if (outbufidx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
            break;
        } else if (outbufidx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat* newFormat = AMediaCodec_getOutputFormat(m_mediaCodec);
            ALOGI("Output format changed: %s", AMediaFormat_toString(newFormat));
            AMediaFormat_delete(newFormat);
        } else if (outbufidx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            continue;
        } else {
            ALOGE("Error dequeueing output buffer: %zd", outbufidx);
            break;
        }
    }

    return true;
}

} // namespace video_engine

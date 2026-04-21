#pragma once

#ifdef __ANDROID__
#include <jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/native_window.h>
#else
// Desktop stubs
struct ANativeWindow;
struct AMediaCodec;
struct AMediaFormat;
#endif
#include <vector>
#include <string>
#include <memory>

namespace video_engine {

/**
 * @brief Handles MediaCodec decoding and rendering to an ANativeWindow.
 * This component takes encoded video data and uses Android's MediaCodec
 * to decode and render it directly to a provided surface (ANativeWindow).
 * It's intended for hardware-accelerated preview playback.
 */
class MediaCodecSurfaceRenderer {
public:
    MediaCodecSurfaceRenderer();
    ~MediaCodecSurfaceRenderer();

    /**
     * @brief Initializes the MediaCodec decoder.
     * @param window The ANativeWindow to render decoded frames to.
     * @param width The width of the video stream.
     * @param height The height of the video stream.
     * @param mimeType The MIME type of the video stream (e.g., "video/avc", "video/hevc").
     * @return True if initialization is successful, false otherwise.
     */
    bool init(
        ANativeWindow* window,
        int width,
        int height,
        const std::string& mimeType,
        const std::vector<uint8_t>& csd0 = {},
        const std::vector<uint8_t>& csd1 = {});

    /**
     * @brief Starts the MediaCodec decoder.
     * @return True if start is successful, false otherwise.
     */
    bool start();

    /**
     * @brief Stops the MediaCodec decoder.
     * @return True if stop is successful, false otherwise.
     */
    bool stop();

    /**
     * @brief Releases the MediaCodec decoder and associated resources.
     */
    void release();

    /**
     * @brief Feeds encoded video data to the MediaCodec decoder.
     * This is a skeleton method. In a real implementation, you'd queue actual encoded packets.
     * @param data Pointer to the encoded video data.
     * @param size Size of the encoded video data.
     * @param presentationTimeUs Presentation timestamp of the data in microseconds.
     * @param flags MediaCodec buffer flags (e.g., AMEDIACODEC_BUFFER_FLAG_KEY_FRAME).
     * @return True if the data was successfully queued, false otherwise.
     */
    bool feedEncodedData(const uint8_t* data, size_t size, int64_t presentationTimeUs, int flags);

    ANativeWindow* nativeWindow() const { return m_nativeWindow; }
    int width() const { return m_width; }
    int height() const { return m_height; }
    const std::string& mimeType() const { return m_mimeType; }
    bool isRunning() const { return m_running; }

private:
    AMediaCodec* m_mediaCodec;
    ANativeWindow* m_nativeWindow;
    int m_width;
    int m_height;
    std::string m_mimeType;
    bool m_initialized;
    bool m_running;

    // Dummy data for skeleton feedEncodedData
    uint8_t m_dummyNalu[4] = {0x00, 0x00, 0x00, 0x01};
};

} // namespace video_engine

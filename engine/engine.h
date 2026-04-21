#pragma once

#include <memory>
#include <string>
#include <vector>
#include <atomic>
#include <thread>
#include <functional>

#ifdef __ANDROID__
#include <android/native_window.h>
#else
struct ANativeWindow;
inline void ANativeWindow_release(ANativeWindow*) {}
inline void ANativeWindow_acquire(ANativeWindow*) {}
#endif

namespace video_engine {
class MediaCodecSurfaceRenderer;
} // namespace video_engine

namespace VideoEngine {

class Project;
class PreviewController;
class ExportController;
/**
 * @brief The main entry point and orchestrator for the video engine.
 * Manages the project, preview, export, and other core functionalities.
 */
class Engine {
public:
    Engine();
    ~Engine();

    /**
     * @brief Initializes the video engine.
     * @return True if initialization is successful, false otherwise.
     */
    bool init();

    /**
     * @brief Sets the native window for GL-based preview rendering.
     * This is typically used for software decoding + OpenGL rendering.
     * @param nativeWindow A pointer to the platform-specific native window (e.g., EGLNativeWindowType on Android).
     */
    void setPreviewSurface(void* nativeWindow);
    bool startPreview();
    bool playFromPreviewMs(int64_t timeMs);
    void stopPreview();
    void seekPreview(int64_t timeMs);
    void resizePreview(int width, int height);
    void setPreviewChroma(bool enabled, int color, float similarity, float smoothness, float spill);

    // --- MediaCodec Preview Pipeline Skeleton Methods ---

    /**
     * @brief Initializes the MediaCodec hardware decoder for preview.
     * This sets up a MediaCodec instance to decode to a specific ANativeWindow.
     * @param window The ANativeWindow to render to.
     * @param width The width of the video stream.
     * @param height The height of the video stream.
     * @param mimeType The MIME type of the video stream (e.g., "video/avc", "video/hevc").
     * @return True if successful, false otherwise.
     */
    bool initMediaCodecPreview(ANativeWindow* window, int width, int height, const std::string& mimeType);

    /**
     * @brief Starts the MediaCodec hardware preview.
     * @return True if successful, false otherwise.
     */
    bool startMediaCodecPreview();

    /**
     * @brief Stops the MediaCodec hardware preview.
     * @return True if successful, false otherwise.
     */
    bool stopMediaCodecPreview();

    /**
     * @brief Releases all resources associated with the MediaCodec hardware preview.
     */
    void releaseMediaCodecPreview();

    /**
     * @brief Placeholder to feed encoded data to the MediaCodec decoder.
     * In a real system, this would be called by an FFmpeg demuxer providing raw NALUs.
     * @param data Pointer to encoded data.
     * @param size Size of data.
     * @param pts Presentation timestamp in microseconds.
     * @param flags MediaCodec buffer flags.
     */
    void feedMediaCodecData(const uint8_t* data, size_t size, int64_t pts, int flags);
    void setProxyPreviewEnabled(bool enabled);
    void startPreviewFeedLoop();
    void stopPreviewFeedLoop();
    void setPreviewSourcePath(const std::string& path);
    void setPreviewStartMs(int64_t timeMs);
    void setPreviewApproxSeek(bool enabled);
    void setPreviewSpeed(double speed);
    void setAudioClockCallback(std::function<void(int64_t)> callback);

private:
    std::unique_ptr<Project> m_project;
    PreviewController* m_previewController;
    ExportController* m_exportController;
    ANativeWindow* m_pendingPreviewWindow;
    bool m_previewSurfaceAttached;

    // MediaCodec specific member
    video_engine::MediaCodecSurfaceRenderer* m_mediaCodecRenderer;
    std::atomic<bool> m_proxyPreviewEnabled{false};
    std::atomic<bool> m_previewFeedRunning{false};
    std::thread m_previewFeedThread;
    std::thread m_previewRenderThread;
    std::atomic<bool> m_previewRenderRunning{false};
    std::string m_previewSourcePath;
    std::atomic<int64_t> m_previewStartMs{0};
    std::atomic<bool> m_previewSeekRequested{false};
    std::atomic<bool> m_previewApproxSeek{false};
    std::atomic<double> m_previewSpeed{1.0};
    std::function<void(int64_t)> m_audioClockCallback;

    // Add other engine-wide members as needed
};

} // namespace VideoEngine

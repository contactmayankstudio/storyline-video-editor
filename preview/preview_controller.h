#pragma once

#include <string>
#include <memory>
#include <cstdint>
#include <atomic>
#include <thread>
#include <mutex>
#include <deque>
#include <map>
#include <unordered_map>
#include <vector>
#include <condition_variable>
#include <chrono>
#include <cmath>
#include <algorithm>
#include <array>

#include "core/clip.h"
#include "backend/ffmpeg/video_decoder.h"
#include "gpu/egl_renderer.h"
#include "preview/transform_engine.h"
#include "smooth_engine/AdaptiveResolution.h"
#include "smooth_engine/AudioEnginePro.h"
#include "smooth_engine/FrameBudgetController.h"
#include "smooth_engine/FramePrefetcher.h"
#include "smooth_engine/ProxyManager.h"
#include "smooth_engine/SmartCache.h"
#include "smooth_engine/SuperResolution.h"
#include "smooth_engine/ThermalManager.h"
#include "smooth_engine/TripleBuffer.h"
#include "smooth_engine/VulkanRenderer.h"

// Forward declarations
struct ANativeWindow;

namespace VideoEngine::Backend {
    class VideoDecoder;
    class FrameConverter;
}

namespace VideoEngine::GPU {
    class GLTexture;
}

namespace VideoEngine {

class Timeline;

/**
 * Real-time video preview controller.
 * 
 * Orchestrates the complete preview pipeline:
 *   VideoDecoder → FrameConverter → GLTexture → EGLRenderer
 * 
 * Designed for:
 * - Storyline-style video editor preview
 * - 30 FPS playback on Android NDK + OpenGL ES 3.0
 * - Single-threaded simplified design (decode + render on same thread)
 * - Clean integration with timeline playback later
 * 
 * Lifecycle:
 *   1. Create instance
 *   2. open(videoPath) - load video file
 *   3. attachSurface(window) - bind to Android Surface
 *   4. start() - begin playback
 *   5. stop() - pause playback
 *   6. seekTo(ms) - jump to time
 *   7. destroy() or ~PreviewController() - cleanup
 * 
 * Example usage:
 *   auto preview = std::make_unique<PreviewController>();
 *   if (preview->open("video.mp4")) {
 *       preview->attachSurface(nativeWindow);
 *       preview->start();
 *       // ... render loop or background thread handles playback ...
 *       preview->stop();
 *   }
 */
class PreviewController {
public:
    PreviewController();
    ~PreviewController();

    // Non-copyable
    PreviewController(const PreviewController&) = delete;
    PreviewController& operator=(const PreviewController&) = delete;

    /**
     * Open and load a video file.
     * Prepares decoder and extracts metadata.
     * 
     * @param videoPath Absolute or relative path to video file
     * @return true if file loaded and video stream found
     */
    bool open(const std::string& videoPath);

    /**
     * Attach a SurfaceView surface for rendering.
     * Creates EGL context and prepares OpenGL for display.
     * 
     * Prerequisites:
     * - Must call open() first
     * - ANativeWindow must remain valid during playback
     * 
     * @param window ANativeWindow from ANativeWindow_fromSurface(env, surface)
     * @return true if EGL initialization succeeded
     */
    bool attachSurface(ANativeWindow* window);
    bool isRendererInitialized() const { return m_renderer != nullptr && m_texture != nullptr; }

    /**
     * Resize surface viewport (call on SurfaceView.Callback.surfaceChanged).
     * Updates rendering viewport.
     * 
     * @param width Surface width in pixels
     * @param height Surface height in pixels
     */
    void resizeSurface(int width, int height);

    /**
     * Detach and release surface resources.
     * Called when SurfaceView is destroyed.
     */
    void detachSurface();

    /**
     * Start or resume video playback.
     * Begins decoding and rendering frames.
     * If already playing, does nothing.
     */
    void start();

    /**
     * Stop / pause video playback.
     * Safe to call multiple times.
     */
    void stop();

    /**
     * Seek to a specific timestamp in the video.
     * Note: Linear decoder only; seeks are approximated.
     * 
     * @param timeMs Target time in milliseconds
     */
    void seekTo(int64_t timeMs);

    /**
     * Seek to an exact timeline time, render the first frame immediately,
     * then continue playback from that same position.
     *
     * @param timeMs Target timeline time in milliseconds
     * @return true if seek + first-frame render succeeded
     */
    bool playFrom(int64_t timeMs);

    /**
     * Render a single frame at the current playback time.
     * Called by render loop (typically 30-60 fps).
     * 
     * @return true if a frame was rendered, false if end-of-video or error
     */
    bool renderFrame();

    /**
     * Get video metadata.
     */
    int getVideoWidth() const;
    int getVideoHeight() const;
    double getVideoFps() const;
    int64_t getVideoDurationMs() const;

    /**
     * Get current playback position.
     * @return Current time in milliseconds
     */
    int64_t getCurrentTimeMs() const;
    int64_t getPlaybackTimelineTimeMs() const;
    int64_t preferredRenderSleepMs() const;

    /**
     * Check if playback is active.
     * @return true if start() was called and stop() not yet called
     */
    bool isPlaying() const { return m_isPlaying.load(); }

    /**
     * Check if video is loaded and ready.
     * @return true if open() succeeded
     */
    bool isReady() const { return m_decoder != nullptr; }

    /**
     * Get last error message.
     * @return Error string, or empty if no error
     */
    const char* getLastError() const { return m_lastError.c_str(); }

    /**
     * Get the timeline associated with this preview.
     */
    std::shared_ptr<Timeline> getTimeline() { return m_timeline; }

    /**
     * Explicit cleanup (optional, destructor handles it).
     * Stops playback and releases all resources.
     */
    void destroy();

    /**
     * Scrub instantly to a timeline time (Storyline-style).
     * Cancels playback, seeks to correct clip, decodes and renders one frame.
     * Thread-safe, no sleep.
     *
     * @param timelineMs Timeline time in milliseconds
     */
    void scrubToTimelineTime(int64_t timelineMs);

    /**
     * Low-resolution ghost preview policy.
     * When enabled, decoded preview frames are downscaled before GPU upload.
     */
    void setGhostPreviewEnabled(bool enabled);
    void setGhostPreviewLongEdgePx(int longEdgePx);

    /**
     * Adaptive frame-drop policy for overload handling.
     * Audio timeline remains continuous; video frames may be dropped in preview.
     */
    void setAdaptiveFrameDropPolicy(bool enabled, int targetFps, int minFps);
    void setLoopingLiveTransitionsEnabled(bool enabled);
    void setAdaptiveProxyEnabled(bool enabled);

    /**
     * Dirty-region redraw controls.
     * Enabled mode redraws only changed screen rects for overlay/sticker edits.
     */
    void setDirtyRegionRedrawEnabled(bool enabled);
    void setClipPreviewTransform(
        int clipId,
        float zoom,
        float scaleX,
        float scaleY,
        float panXPx,
        float panYPx,
        float rotationDeg,
        bool mirrorX,
        bool immediate = false);
    float getClipPreviewMinZoom(int clipId);
    std::array<float, 7> getClipPreviewTransformValues(int clipId);
    std::array<float, 7> computeNormalizedPreviewTransform(
        int clipId,
        float zoom,
        float scaleX,
        float scaleY,
        float panXPx,
        float panYPx,
        float rotationDeg,
        bool mirrorX);
    std::array<float, 3> computeScaleGesturePreviewTransform(
        int clipId,
        float baseZoom,
        float basePanXPx,
        float basePanYPx,
        float scaleAccumulator,
        float focusOffsetXPx,
        float focusOffsetYPx);
    std::array<float, 2> computeDragPanPreviewTransform(
        int clipId,
        float currentZoom,
        float currentPanXPx,
        float currentPanYPx,
        float deltaXPx,
        float deltaYPx);
    std::array<float, 7> beginPreviewTransformGesture(
        int clipId,
        float zoom,
        float scaleX,
        float scaleY,
        float panXPx,
        float panYPx,
        float rotationDeg,
        bool mirrorX,
        float centroidOffsetXPx,
        float centroidOffsetYPx,
        float spanPx,
        float angleDeg,
        int mode,
        float edgeSignX,
        float edgeSignY,
        bool allowRotation);
    std::array<float, 7> updatePreviewTransformGesture(
        float centroidOffsetXPx,
        float centroidOffsetYPx,
        float spanPx,
        float angleDeg);
    void endPreviewTransformGesture();
    std::array<float, 3> computeCornerHandlePreviewTransform(
        int clipId,
        float baseZoom,
        float basePanXPx,
        float basePanYPx,
        float deltaXPx,
        float deltaYPx,
        float cornerSignX,
        float cornerSignY);
    std::array<float, 3> computeDoubleTapPreviewTransform(
        int clipId,
        float currentZoom,
        float currentPanXPx,
        float currentPanYPx,
        float tapOffsetXPx,
        float tapOffsetYPx);
    void clearClipPreviewTransform(int clipId);
    void clearClipPreviewTransforms();
    void upsertTransition(
        int64_t transitionId,
        int outgoingClipId,
        int incomingClipId,
        int typeId,
        int durationMs,
        int64_t startTimeMs);
    void removeTransition(int64_t transitionId);
    bool setClipEffects(int clipId, float brightness, float contrast, float saturation);
    void resetTimelinePreviewState();
    void invalidateVisualState();

    /**
     * Notify the preview controller that a proxy has finished building for a clip.
     * Evicts the old decoder and texture for that clip so the next renderFrame()
     * call will re-open the clip using the newly available proxy path.
     *
     * Call this from the BUILD_CLIP_PROXY completion handler in command_manager.cpp.
     *
     * @param clipId The clip ID whose proxy is now ready.
     */
    void notifyProxyReady(int clipId);

    /**
     * Predictive frame caching controls for scrubbing.
     * Engine pre-decodes around current scrub position.
     */
    void setPredictiveCachingPolicy(
        bool enabled,
        int lookAroundMs,
        int sampleStepMs,
        int cacheMaxFrames);

    /**
     * External audio-master clock controls.
     * When enabled and clock updates are fresh, video preview follows audio PTS.
     */
    void setAudioMasterClockEnabled(bool enabled);
    void updateAudioMasterClockUs(int64_t ptsUs);

    /**
     * Recompose using last uploaded frame without decoding new video data.
     */
    bool redrawCachedFrame();
    bool redrawCachedFrameRegion(int x, int y, int width, int height);

    // Chroma key controls (applied in GL shader)
    Clip::ChromaKeyParams& getMutableChromaKey() { return m_chromaKey; }
    const Clip::ChromaKeyParams& getChromaKey() const { return m_chromaKey; }

private:
    struct QueuedFrame {
        Backend::DecodedFrame frame;
        int64_t ptsMs = 0;
    };

    using ClipPreviewTransform = TransformState;

    struct ClipTransition {
        int64_t id = -1;
        int outgoingClipId = -1;
        int incomingClipId = -1;
        int typeId = 0;
        int durationMs = 0;
        int64_t startTimeMs = 0;
        bool enabled = true;

        bool isActive(int64_t timelineMs) const {
            if (!enabled || durationMs <= 0) {
                return false;
            }
            const int64_t elapsedMs = timelineMs - startTimeMs;
            return elapsedMs >= 0 && elapsedMs < durationMs;
        }

        float progressAt(int64_t timelineMs) const {
            if (!isActive(timelineMs)) {
                return -1.0f;
            }
            return static_cast<float>(timelineMs - startTimeMs) /
                static_cast<float>(std::max(1, durationMs));
        }
    };

    // Timeline state
    std::shared_ptr<Timeline> m_timeline;

    // Component instances
    std::unique_ptr<Backend::VideoDecoder> m_decoder;
    std::unique_ptr<Backend::FrameConverter> m_converter;
    GPU::GLTexture* m_texture = nullptr;  // Non-owning alias to TripleBuffer front texture
    std::unique_ptr<VideoEngine::Performance::TripleBuffer> m_tripleBuffer;
    std::unique_ptr<GPU::EGLRenderer> m_renderer;

    // Per-clip decoders and textures for multi-track compositing
    struct ClipDecodeState {
        std::unique_ptr<Backend::VideoDecoder> decoder;
        std::unique_ptr<GPU::GLTexture> texture;
        std::string openPath;
        int64_t mediaDurationMs = 0;
        int64_t lastRenderedSourceMs = -1;
        Backend::DecodedFrame lastDecodedFrame;
        bool hasLastDecodedFrame = false;
        int64_t approximateFrameMs = 33;
        int64_t sequentialDecodeWindowMs = 420;
    };
    std::map<int, ClipDecodeState> m_clipDecoders; // clipId -> state
    TransformEngine m_transformEngine;
    std::map<int64_t, ClipTransition> m_transitions;

    // Playback state
    std::atomic<bool> m_isPlaying;
    std::atomic<int64_t> m_currentTimeMs;
    int64_t m_lastRenderTimeMs;
    int64_t m_lastRenderedSourceMs = -1;
    int m_lastRenderedClipId = -1;
    double m_frameIntervalMs;  // 1000.0 / fps
    int64_t m_playbackAnchorTimeMs;
    std::chrono::steady_clock::time_point m_playbackAnchorWallClock;
    std::deque<QueuedFrame> m_frameQueue;
    std::thread m_decodeThread;

    // Surface state
    ANativeWindow* m_nativeWindow;
    int m_surfaceWidth;
    int m_surfaceHeight;

    // Metadata cache
    int m_videoWidth;
    int m_videoHeight;
    double m_videoFps;
    int64_t m_videoDurationMs;

    std::atomic<bool> m_decodeRunning;
    std::atomic<bool> m_reachedEos;
    size_t m_maxQueuedFrames;

    // Error tracking
    std::string m_lastError;
    Clip::ChromaKeyParams m_chromaKey;
    bool m_ghostPreviewEnabled;
    int m_ghostPreviewLongEdgePx;
    bool m_adaptiveFrameDropEnabled;
    int m_targetPreviewFps;
    bool m_loopingLiveTransitionsEnabled = true;
    bool m_adaptiveProxyEnabled = true;
    int m_minPreviewFps;
    int m_frameDropOverloadScore;
    int m_budgetPreviewFps = 30;
    int m_budgetPreviewLongEdgePx = 640;
    bool m_budgetBypassOverlayComposition = false;
    bool m_budgetPredictivePrefetchAllowed = true;
    std::unique_ptr<VideoEngine::Performance::AdaptiveResolution> m_adaptiveResolution;
    int m_dynamicPreviewScaleLimitPx = 0;
    std::unique_ptr<VideoEngine::DeepPro::AudioEnginePro> m_audioSyncEngine;
    std::unique_ptr<VideoEngine::Performance::FrameBudgetController> m_frameBudgetController;
    std::unique_ptr<VideoEngine::Performance::FramePrefetcher> m_framePrefetcher;
    std::unique_ptr<VideoEngine::DeepPro::ProxyManager> m_proxyManager;
    std::unique_ptr<VideoEngine::Performance::SmartCache> m_smartCache;
    std::unique_ptr<VideoEngine::AI::SuperResolution> m_superResolution;
    std::unique_ptr<VideoEngine::Android::ThermalManager> m_thermalManager;
    std::unique_ptr<VideoEngine::Backend::VulkanRenderer> m_vulkanRenderer;

    /**
     * Internal: Set error message with formatted string.
     */
    void setError(const char* fmt, ...);
    void clearError();
    bool ensurePrimaryTripleBufferLocked(int width, int height);
    void refreshPrimaryTextureAliasLocked();
    void releasePrimaryTextureLocked();

    /**
     * Internal: Decode next frame, convert to RGBA, upload to texture, render.
     * @return true if frame rendered, false if error or EOF
     */
    bool processFrame();
    bool primePlaybackAtLocked(int64_t targetTimeMs);
    void clearQueuedFramesLocked();
    void startDecodeWorkerLocked();
    void stopDecodeWorkerLocked();
    void decodeWorkerLoop();
    int64_t playbackTimelineTimeMsLocked() const;
    int64_t preferredRenderSleepMsLocked() const;

    /**
     * Internal: Calculate sleep duration to maintain target FPS.
     * @param elapsedMs Time since last frame render
     * @return Sleep duration in milliseconds, or 0 if no sleep needed
     */
    int64_t calculateFrameDelay(int64_t elapsedMs) const;

    /**
     * Internal: Clamp timeline time to valid media range.
     */
    int64_t clampTimeMs(int64_t timeMs) const;
    int64_t clampDecodableTimeMs(int64_t timeMs, int64_t durationMs) const;

    /**
     * Internal: Upload and render a decoded frame without advancing decoder state.
     */
    bool renderDecodedFrame(
        const Backend::DecodedFrame& decodedFrame,
        int64_t timelineMs,
        bool presentFrame = true);
    bool decodeClipFrameLocked(
        const std::shared_ptr<Clip>& clip,
        ClipDecodeState& state,
        int64_t sourceSeekMs,
        bool keyframeOnlyScrub,
        Backend::DecodedFrame* decodedFrameOut,
        int64_t* renderedSourceMsOut);
    bool renderTimelineFrameLocked(
        int64_t timelineMs,
        bool keyframeOnlyScrub,
        bool allowPredictiveCache,
        bool updatePredictiveCache,
        int64_t* renderedTimelineMs);
    bool renderTransitionFrameLocked(
        const ClipTransition& transition,
        int64_t timelineMs,
        bool keyframeOnlyScrub,
        int64_t* renderedTimelineMs);
    bool switchDecoderSourceLocked(const std::shared_ptr<Clip>& clip);
    int64_t clampTimelineTimeMsLocked(int64_t timeMs) const;
    int64_t mapClipTimelineToSourceMs(
        const std::shared_ptr<Clip>& clip,
        int64_t timelineMs,
        bool ignoreFreeze) const;
    bool shouldUseKeyframeOnlyScrubLocked(int64_t requestTimelineMs);
    bool redrawTextureLocked(int x, int y, int width, int height, bool useDirtyRegion);
    void cachePredictiveFrameLocked(Backend::DecodedFrame&& frame, int64_t ptsMs);
    bool tryRenderFromPredictiveCacheLocked(
        int64_t requestTimeMs,
        int64_t* renderedTimeMs,
        bool presentFrame);
    void clearPredictiveCacheLocked();
    void cancelPredictivePrefetchLocked(bool clearCache);
    void requestPredictivePrefetchLocked(int64_t centerMs);
    void predictivePrefetchLoop();
    void stopPredictivePrefetchWorker();
    VideoEngine::Performance::FrameBudgetInput makeFrameBudgetInputLocked(int64_t renderCostMs) const;
    void applyFrameBudgetDecisionLocked(
        const VideoEngine::Performance::FrameBudgetDecision& decision);
    void resetFrameBudgetLocked();
    void updateAdaptiveOverloadScoreLocked(int64_t renderCostMs);
    bool shouldBypassOverlayCompositionLocked() const;
    int64_t playbackCompositeReuseWindowMsLocked(int64_t approximateFrameMs) const;
    void maybeQueueProxyBuildLocked(const std::shared_ptr<Clip>& clip);
    void maybeQueueProxyBuildForSourceLocked(
        const std::string& sourcePath,
        int width,
        int height,
        double fps);
    bool shouldAutoRequestPreviewProxyLocked(
        const std::string& sourcePath,
        int width,
        int height,
        double fps) const;
    void syncClipProxyPathLocked(const std::shared_ptr<Clip>& clip);
    void rebuildSmartCacheHintsLocked();
    int resolvePreviewScaleLimitLocked() const;
    int resolveSuperResolutionPreviewScaleLimitLocked(int baseLimitPx, int sourceWidth, int sourceHeight) const;
    void applyPreviewScaleLimitLocked();
    void updateAdaptivePreviewScaleLocked(int64_t scrubVelocityMsPerSec);
    ClipPreviewTransform clipPreviewTransformLocked(int clipId) const;
    bool hasClipPreviewTransformLocked(const std::shared_ptr<Clip>& clip) const;
    float clipPreviewMinZoomLocked(int clipId) const;
    float clipPreviewMaxZoomLocked(int clipId) const;
    void normalizeClipPreviewTransformLocked(int clipId, ClipPreviewTransform& transform) const;
    void markVisualStateDirtyLocked();
    const ClipTransition* findActiveTransitionLocked(int64_t timelineMs) const;
    std::shared_ptr<Clip> findTimelineClipByIdLocked(int clipId) const;
    bool uploadClipFrameToTextureLocked(
        ClipDecodeState& state,
        const Backend::DecodedFrame& frame,
        int64_t renderedSourceMs);
    GPU::EGLRenderer::Layer buildLayerForClipLocked(
        const std::shared_ptr<Clip>& clip,
        GPU::GLTexture* texture) const;

    mutable std::mutex m_playbackMutex;

    bool m_dirtyRegionRedrawEnabled = true;
    bool m_predictiveCachingEnabled = true;
    int m_predictiveLookAroundBaseMs = 2000;
    int m_predictiveSampleStepBaseMs = 120;
    int m_predictiveCacheBaseMaxFrames = 40;
    int m_predictiveLookAroundMs = 2000;
    int m_predictiveSampleStepMs = 120;
    int m_predictiveCacheMaxFrames = 40;
    bool m_hasDecodedFrame = false;
    uint64_t m_visualStateVersion = 1;
    uint64_t m_lastRenderedVisualStateVersion = 0;
    std::string m_openVideoPath;
    int64_t m_lastPlaybackPrefetchRequestMs = std::numeric_limits<int64_t>::min();

    std::map<int64_t, Backend::DecodedFrame> m_predictiveFrameCache;
    std::deque<int64_t> m_predictiveFrameOrder;

    std::mutex m_prefetchMutex;
    std::condition_variable m_prefetchCv;
    std::thread m_prefetchThread;
    bool m_prefetchExit = false;
    uint64_t m_prefetchRequestedGeneration = 0;
    uint64_t m_prefetchProcessedGeneration = 0;
    int64_t m_prefetchCenterMs = 0;
    std::string m_prefetchVideoPath;
    int m_prefetchLookAroundMs = 2000;
    int m_prefetchSampleStepMs = 120;
    int m_prefetchScaleLimitPx = 0;

    bool m_audioMasterClockEnabled = false;
    bool m_audioMasterClockValid = false;
    int64_t m_audioMasterClockUs = 0;
    int64_t m_audioMasterClockStaleAfterMs = 250;
    std::chrono::steady_clock::time_point m_audioMasterClockWallClock;

    bool m_hasLastScrubRequestSample = false;
    int64_t m_lastScrubRequestTimelineMs = 0;
    std::chrono::steady_clock::time_point m_lastScrubRequestWallClock;
    int64_t m_keyframeScrubVelocityThresholdMsPerSec = 2000;  // was 6000 — lower = faster scrub response
    int64_t m_keyframeScrubMinDeltaMs = 50;                   // was 80
    std::vector<uint8_t> m_rgbaScratch;
};

}  // namespace VideoEngine

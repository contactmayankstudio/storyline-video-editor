#pragma once

#include <memory>
#include <vector>
#include <map>
#include <queue>
#include <mutex>
#include <thread>
#include <condition_variable>
#include <atomic>
#include <unordered_map>
#include "../core/clip.h"
#include "../backend/ffmpeg/video_decoder.h"
#include "RingBuffer.h"

namespace VideoEngine::Performance {

/**
 * @brief Represents a decoded frame ready for rendering.
 */
struct CachedFrame {
    uint32_t clipId;
    int64_t timelineMs;
    int64_t sourcePtsMs;
    Backend::DecodedFrame frame;
};

/**
 * @brief FramePrefetcher manages background decoding of video clips.
 * 
 * It predicts the next required frames based on playback speed and direction,
 * and ensures they are decoded and cached BEFORE the render loop needs them.
 */
class FramePrefetcher {
public:
    FramePrefetcher();
    ~FramePrefetcher();

    /**
     * @brief Start prefetching for a set of clips.
     * @param clips The clips to prefetch.
     * @param startTimeMs The current playback time.
     * @param speed The playback speed.
     */
    void start(const std::vector<std::shared_ptr<Clip>>& clips, int64_t startTimeMs, float speed = 1.0f);
    
    /**
     * @brief Stop all background prefetching.
     */
    void stop();

    /**
     * @brief Update the current playback time to adjust prefetch window.
     */
    void updatePlaybackTime(int64_t timeMs);

    /**
     * @brief Try to get a pre-decoded frame for a specific clip and time.
     * @return The frame if cached, nullptr otherwise.
     */
    std::shared_ptr<CachedFrame> getFrame(uint32_t clipId, int64_t timeMs);

private:
    struct ClipDecoderState {
        std::unique_ptr<Backend::VideoDecoder> decoder;
        std::string openPath;
        int64_t mediaDurationMs = 0;
        int64_t approximateFrameMs = 33;
    };

    void workerLoop();
    static bool isEligibleClip(const std::shared_ptr<Clip>& clip);
    static std::string resolveDecoderPath(const std::shared_ptr<Clip>& clip);
    static int64_t clampSourceTimeMs(int64_t sourceMs, int64_t durationMs);
    static int64_t mapTimelineToSourceMs(const std::shared_ptr<Clip>& clip, int64_t timelineMs, int64_t mediaDurationMs);
    bool ensureDecoder(uint32_t clipId, const std::shared_ptr<Clip>& clip, ClipDecoderState& state);

    std::atomic<bool> m_running{false};
    std::atomic<int64_t> m_currentTimeMs{0};
    std::atomic<float> m_speed{1.0f};
    
    std::vector<std::shared_ptr<Clip>> m_activeClips;
    std::mutex m_clipsMutex;

    // Cache: clipId -> RingBuffer of frames
    std::map<uint32_t, std::shared_ptr<RingBuffer<std::shared_ptr<CachedFrame>>>> m_frameCache;
    std::mutex m_cacheMutex;
    std::unordered_map<uint32_t, ClipDecoderState> m_decoderStates;

    std::thread m_workerThread;
    std::condition_variable m_cv;
};

} // namespace VideoEngine::Performance

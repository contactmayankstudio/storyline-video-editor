#pragma once

#include <atomic>
#include <condition_variable>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace VideoEngine::Performance {

/**
 * @brief Smart Background Rendering (SmartCache).
 * 
 * Works OFFLINE by using the phone's idle GPU cycles to pre-render 
 * complex timeline segments.
 */
class SmartCache {
public:
    ~SmartCache();

    /**
     * @brief Mark a segment of the timeline as "Heavy" (e.g., 4 layers + blur).
     */
    void markHeavySegment(int64_t startMs, int64_t endMs);

    /**
     * @brief Start background rendering when the user is not interacting.
     */
    void startBackgroundRender();

    /**
     * @brief Get the pre-rendered clip if available.
     */
    std::string getCachedSegment(int64_t timeMs);

    void stopBackgroundRender();
    void reset();

private:
    struct Segment {
        int64_t startMs = 0;
        int64_t endMs = 0;
    };

    void workerLoop();

    std::vector<Segment> m_heavySegments;
    std::map<int64_t, std::string> m_cacheIndex;
    std::mutex m_mutex;
    std::condition_variable m_cv;
    std::thread m_worker;
    std::atomic<bool> m_running{false};
    bool m_dirty = false;
};

} // namespace VideoEngine::Performance

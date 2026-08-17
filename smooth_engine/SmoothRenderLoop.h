#pragma once

#include <chrono>
#include <functional>
#include <thread>
#include <atomic>

namespace VideoEngine::Performance {

/**
 * @brief A high-precision render loop for smooth video playback.
 * 
 * Traditional sleep-based loops are often jittery because OS sleep 
 * granularity is not precise enough for 60fps (16.67ms).
 * SmoothRenderLoop uses a combination of precise timing and adaptive 
 * yielding to ensure frames are delivered exactly when needed.
 */
class SmoothRenderLoop {
public:
    using RenderCallback = std::function<void(int64_t timestampMs)>;

    SmoothRenderLoop(double targetFps = 30.0);
    ~SmoothRenderLoop();

    /**
     * @brief Start the loop.
     * @param callback Function to call for each frame.
     * @param startPtsMs Initial playback time.
     */
    void start(RenderCallback callback, int64_t startPtsMs = 0);
    
    /**
     * @brief Stop the loop.
     */
    void stop();

    /**
     * @brief Change playback speed.
     */
    void setSpeed(float speed);

    /**
     * @brief Shared precision wait helper for engine loops that need
     * sub-millisecond frame pacing without spinning for the whole budget.
     */
    static void preciseWaitUntil(std::chrono::steady_clock::time_point targetTime);

private:
    void loop();

    std::atomic<bool> m_running{false};
    std::atomic<float> m_speed{1.0f};
    double m_targetFps;
    int64_t m_currentPtsMs;
    RenderCallback m_callback;
    std::thread m_thread;
};

} // namespace VideoEngine::Performance

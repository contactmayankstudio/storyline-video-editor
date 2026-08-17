#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <thread>

namespace VideoEngine::Performance {

/**
 * @brief DisplaySync for 90Hz/120Hz smooth playback.
 * 
 * Syncs the engine's render loop with the Android Choreographer 
 * to ensure frames are presented exactly when the display refreshes.
 */
class DisplaySync {
public:
    using FrameCallback = std::function<void(int64_t frameTimeNanos)>;

    DisplaySync() = default;
    ~DisplaySync();

    void start(FrameCallback callback);
    void stop();

private:
    void workerLoop();

    FrameCallback m_callback;
    std::atomic<bool> m_running{false};
    std::mutex m_mutex;
    std::thread m_worker;
};

} // namespace VideoEngine::Performance

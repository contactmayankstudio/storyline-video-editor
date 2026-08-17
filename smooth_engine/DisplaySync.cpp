#include "DisplaySync.h"

#include <chrono>
#include <thread>

namespace VideoEngine::Performance {

DisplaySync::~DisplaySync() {
    stop();
}

void DisplaySync::start(FrameCallback callback) {
    stop();
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_callback = std::move(callback);
    }
    m_running.store(true);
    m_worker = std::thread(&DisplaySync::workerLoop, this);
}

void DisplaySync::stop() {
    if (!m_running.exchange(false)) {
        return;
    }
    if (m_worker.joinable()) {
        m_worker.join();
    }
}

void DisplaySync::workerLoop() {
    using clock = std::chrono::steady_clock;
    constexpr auto frameInterval = std::chrono::microseconds(16667);

    auto nextTick = clock::now();
    while (m_running.load()) {
        nextTick += frameInterval;
        std::this_thread::sleep_until(nextTick);

        FrameCallback callback;
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            callback = m_callback;
        }
        if (!callback || !m_running.load()) {
            continue;
        }
        const int64_t frameTimeNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(
            clock::now().time_since_epoch()).count();
        callback(frameTimeNanos);
    }
}

} // namespace VideoEngine::Performance

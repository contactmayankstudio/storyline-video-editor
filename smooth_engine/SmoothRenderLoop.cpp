#include "SmoothRenderLoop.h"
#include <thread>

namespace VideoEngine::Performance {

SmoothRenderLoop::SmoothRenderLoop(double targetFps)
    : m_targetFps(targetFps), m_currentPtsMs(0) {
}

SmoothRenderLoop::~SmoothRenderLoop() {
    stop();
}

void SmoothRenderLoop::start(RenderCallback callback, int64_t startPtsMs) {
    stop();
    m_callback = callback;
    m_currentPtsMs = startPtsMs;
    m_running.store(true);
    m_thread = std::thread(&SmoothRenderLoop::loop, this);
}

void SmoothRenderLoop::stop() {
    m_running.store(false);
    if (m_thread.joinable()) {
        m_thread.join();
    }
}

void SmoothRenderLoop::setSpeed(float speed) {
    m_speed.store(speed);
}

void SmoothRenderLoop::preciseWaitUntil(std::chrono::steady_clock::time_point targetTime) {
    auto now = std::chrono::steady_clock::now();
    if (now >= targetTime) {
        return;
    }

    auto waitDuration = targetTime - now;
    if (waitDuration > std::chrono::milliseconds(2)) {
        std::this_thread::sleep_for(waitDuration - std::chrono::milliseconds(1));
    }

    while (std::chrono::steady_clock::now() < targetTime) {
        std::this_thread::yield();
    }
}

void SmoothRenderLoop::loop() {
    auto frameDuration = std::chrono::duration<double, std::milli>(1000.0 / m_targetFps);
    auto nextFrameTime = std::chrono::steady_clock::now();

    while (m_running.load()) {
        auto startTime = std::chrono::steady_clock::now();

        // 1. Execute rendering
        if (m_callback) {
            m_callback(m_currentPtsMs);
        }

        // 2. Advance time based on speed
        float speed = m_speed.load();
        m_currentPtsMs += static_cast<int64_t>(frameDuration.count() * speed);

        // 3. High-precision sync
        nextFrameTime += std::chrono::duration_cast<std::chrono::steady_clock::duration>(frameDuration);
        
        auto now = std::chrono::steady_clock::now();
        if (now < nextFrameTime) {
            preciseWaitUntil(nextFrameTime);
        } else {
            // Frame drop or lag detected - realign clock to catch up
            nextFrameTime = std::chrono::steady_clock::now();
        }
    }
}

} // namespace VideoEngine::Performance

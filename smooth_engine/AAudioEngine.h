#pragma once

#include <cstddef>
#include <cstdint>

namespace VideoEngine::Android {

/**
 * @brief Zero-Latency AAudio Engine.
 * 
 * Android's default audio has high latency. This engine uses the NDK AAudio 
 * API (or Oboe) for real-time, low-latency playback required for pro editing.
 */
class AAudioEngine {
public:
    ~AAudioEngine();

    bool start(int sampleRate, int channelCount);
    void stop();

    /**
     * @brief Feed audio data to the low-latency stream.
     */
    bool write(const float* samples, size_t numFrames, int timeoutMs = 0);
    bool isRunning() const { return m_stream != nullptr; }
    int framesPerBurst() const { return m_framesPerBurst; }
    int sampleRate() const { return m_sampleRate; }
    int channelCount() const { return m_channelCount; }

private:
    void* m_stream = nullptr;
    int m_sampleRate = 0;
    int m_channelCount = 0;
    int m_framesPerBurst = 0;
};

} // namespace VideoEngine::Android

#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace VideoEngine::DeepPro {

/**
 * @brief Professional Audio Pipeline (Logic Pro / Audition level).
 */
class AudioEnginePro {
public:
    /**
     * @brief Sample-Accurate Peak Analysis.
     * Generates waveform data for the UI to display.
     */
    std::vector<float> generateWaveform(const std::string& path);

    /**
     * @brief Real-time Audio Filters (VST-style).
     * 1. Noise Reduction (using FFT/Spectral Subtraction).
     * 2. Parametric EQ (High/Low pass).
     * 3. Compressor/Limiter (To avoid peaking).
     */
    void applyFilter(float* buffer, size_t numSamples);

    /**
     * @brief Audio-Video Latency Compensation.
     * Deep engine logic to ensure audio matches visual frame exactly,
     * even when the GPU is lagging.
     */
    void syncClock(int64_t videoPtsUs);
    void observeClocks(int64_t audioPtsUs, int64_t videoPtsUs);

    float lastDriftMs() const { return m_lastDriftUs / 1000.0f; }
    float playbackRateCorrectionPpm() const { return m_playbackRateCorrectionPpm; }
    int64_t smoothedAudioClockUs() const { return m_smoothedAudioClockUs; }

private:
    int64_t m_lastVideoPtsUs = 0;
    int64_t m_audioClockUs = 0;
    int64_t m_smoothedAudioClockUs = 0;
    int64_t m_smoothedDriftUs = 0;
    int64_t m_lastDriftUs = 0;
    float m_playbackRateCorrectionPpm = 0.0f;
};

} // namespace VideoEngine::DeepPro

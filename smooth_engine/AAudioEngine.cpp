#include "AAudioEngine.h"

#if defined(__ANDROID__) && __has_include(<aaudio/AAudio.h>) && defined(__ANDROID_API__) && (__ANDROID_API__ >= 26)
#include <aaudio/AAudio.h>
#define VIDEO_ENGINE_HAS_AAUDIO 1
#else
#define VIDEO_ENGINE_HAS_AAUDIO 0
#endif

#include <algorithm>

namespace VideoEngine::Android {

AAudioEngine::~AAudioEngine() {
    stop();
}

bool AAudioEngine::start(int sampleRate, int channelCount) {
    stop();

#if !VIDEO_ENGINE_HAS_AAUDIO
    (void)sampleRate;
    (void)channelCount;
    return false;
#else
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || !builder) {
        return false;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setSampleRate(builder, std::max(8000, sampleRate));
    AAudioStreamBuilder_setChannelCount(builder, std::clamp(channelCount, 1, 2));

    AAudioStream* stream = nullptr;
    const aaudio_result_t openResult = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (openResult != AAUDIO_OK || !stream) {
        return false;
    }

    if (AAudioStream_requestStart(stream) != AAUDIO_OK) {
        AAudioStream_close(stream);
        return false;
    }

    m_stream = stream;
    m_sampleRate = AAudioStream_getSampleRate(stream);
    m_channelCount = AAudioStream_getChannelCount(stream);
    m_framesPerBurst = static_cast<int>(AAudioStream_getFramesPerBurst(stream));
    return true;
#endif
}

void AAudioEngine::stop() {
#if VIDEO_ENGINE_HAS_AAUDIO
    auto* stream = reinterpret_cast<AAudioStream*>(m_stream);
    if (stream) {
        AAudioStream_requestStop(stream);
        AAudioStream_close(stream);
    }
#endif
    m_stream = nullptr;
    m_sampleRate = 0;
    m_channelCount = 0;
    m_framesPerBurst = 0;
}

bool AAudioEngine::write(const float* samples, size_t numFrames, int timeoutMs) {
#if !VIDEO_ENGINE_HAS_AAUDIO
    (void)samples;
    (void)numFrames;
    (void)timeoutMs;
    return false;
#else
    auto* stream = reinterpret_cast<AAudioStream*>(m_stream);
    if (!stream || !samples || numFrames == 0 || m_channelCount <= 0) {
        return false;
    }
    const aaudio_result_t written = AAudioStream_write(
        stream,
        samples,
        static_cast<int32_t>(numFrames),
        timeoutMs * 1000LL);
    return written >= 0;
#endif
}

} // namespace VideoEngine::Android

#include "AudioEnginePro.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

#if defined(VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE) || __has_include(<libavformat/avformat.h>)
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/samplefmt.h>
}
#define VIDEO_ENGINE_AUDIO_FFMPEG 1
#else
#define VIDEO_ENGINE_AUDIO_FFMPEG 0
#endif

namespace VideoEngine::DeepPro {

namespace {

constexpr int kTargetWaveformBuckets = 256;
constexpr int kSamplesPerPeakWindow = 2048;

#if VIDEO_ENGINE_AUDIO_FFMPEG
float sampleToFloat(const uint8_t* data, AVSampleFormat format) {
    switch (format) {
        case AV_SAMPLE_FMT_U8:
            return (static_cast<float>(*data) - 128.0f) / 128.0f;
        case AV_SAMPLE_FMT_S16:
            return static_cast<float>(*reinterpret_cast<const int16_t*>(data)) / 32768.0f;
        case AV_SAMPLE_FMT_S32:
            return static_cast<float>(*reinterpret_cast<const int32_t*>(data)) / 2147483648.0f;
        case AV_SAMPLE_FMT_FLT:
            return *reinterpret_cast<const float*>(data);
        case AV_SAMPLE_FMT_DBL:
            return static_cast<float>(*reinterpret_cast<const double*>(data));
        default:
            return 0.0f;
    }
}
#else
float sampleToFloat(const uint8_t* data, int format) {
    (void)data;
    (void)format;
    return 0.0f;
}
#endif

std::vector<float> compressWaveform(const std::vector<float>& rawPeaks) {
    if (rawPeaks.empty()) {
        return std::vector<float>(kTargetWaveformBuckets, 0.0f);
    }
    if (static_cast<int>(rawPeaks.size()) <= kTargetWaveformBuckets) {
        std::vector<float> waveform(kTargetWaveformBuckets, 0.0f);
        std::copy(rawPeaks.begin(), rawPeaks.end(), waveform.begin());
        return waveform;
    }

    std::vector<float> waveform(kTargetWaveformBuckets, 0.0f);
    const double scale = static_cast<double>(rawPeaks.size()) / static_cast<double>(kTargetWaveformBuckets);
    for (int bucket = 0; bucket < kTargetWaveformBuckets; ++bucket) {
        const size_t start = static_cast<size_t>(std::floor(bucket * scale));
        const size_t end = static_cast<size_t>(std::min<double>(rawPeaks.size(), std::ceil((bucket + 1) * scale)));
        float peak = 0.0f;
        for (size_t i = start; i < end; ++i) {
            peak = std::max(peak, rawPeaks[i]);
        }
        waveform[bucket] = std::clamp(peak, 0.0f, 1.0f);
    }
    return waveform;
}

}  // namespace

std::vector<float> AudioEnginePro::generateWaveform(const std::string& path) {
#if !VIDEO_ENGINE_AUDIO_FFMPEG
    (void)path;
    return std::vector<float>(kTargetWaveformBuckets, 0.0f);
#else
    AVFormatContext* formatCtx = nullptr;
    if (avformat_open_input(&formatCtx, path.c_str(), nullptr, nullptr) < 0) {
        return std::vector<float>(kTargetWaveformBuckets, 0.0f);
    }

    const auto formatGuard = [&]() { avformat_close_input(&formatCtx); };
    if (avformat_find_stream_info(formatCtx, nullptr) < 0) {
        formatGuard();
        return std::vector<float>(kTargetWaveformBuckets, 0.0f);
    }

    const int streamIndex = av_find_best_stream(formatCtx, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (streamIndex < 0) {
        formatGuard();
        return std::vector<float>(kTargetWaveformBuckets, 0.0f);
    }

    AVStream* stream = formatCtx->streams[streamIndex];
    const AVCodec* codec = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!codec) {
        formatGuard();
        return std::vector<float>(kTargetWaveformBuckets, 0.0f);
    }

    AVCodecContext* codecCtx = avcodec_alloc_context3(codec);
    if (!codecCtx) {
        formatGuard();
        return std::vector<float>(kTargetWaveformBuckets, 0.0f);
    }

    const auto codecGuard = [&]() {
        avcodec_free_context(&codecCtx);
        formatGuard();
    };

    if (avcodec_parameters_to_context(codecCtx, stream->codecpar) < 0 ||
        avcodec_open2(codecCtx, codec, nullptr) < 0) {
        codecGuard();
        return std::vector<float>(kTargetWaveformBuckets, 0.0f);
    }

    AVPacket* packet = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();
    if (!packet || !frame) {
        if (packet) av_packet_free(&packet);
        if (frame) av_frame_free(&frame);
        codecGuard();
        return std::vector<float>(kTargetWaveformBuckets, 0.0f);
    }

    const auto frameGuard = [&]() {
        av_packet_free(&packet);
        av_frame_free(&frame);
        codecGuard();
    };

    std::vector<float> rawPeaks;
    rawPeaks.reserve(kTargetWaveformBuckets);
    float currentPeak = 0.0f;
    int samplesInWindow = 0;

    auto consumeFrame = [&](AVFrame* decodedFrame) {
        const AVSampleFormat sampleFmt = static_cast<AVSampleFormat>(decodedFrame->format);
        const AVSampleFormat baseFmt = av_get_packed_sample_fmt(sampleFmt);
        const int bytesPerSample = av_get_bytes_per_sample(baseFmt);
        if (bytesPerSample <= 0) return;

        const bool planar = av_sample_fmt_is_planar(sampleFmt) != 0;
        const int channels = std::max(1, decodedFrame->channels);
        const int sampleCount = decodedFrame->nb_samples;
        for (int sampleIndex = 0; sampleIndex < sampleCount; ++sampleIndex) {
            float samplePeak = 0.0f;
            for (int channel = 0; channel < channels; ++channel) {
                const uint8_t* samplePtr = nullptr;
                if (planar) {
                    samplePtr = decodedFrame->data[channel] + (sampleIndex * bytesPerSample);
                } else {
                    samplePtr = decodedFrame->data[0] + ((sampleIndex * channels + channel) * bytesPerSample);
                }
                samplePeak = std::max(samplePeak, std::abs(sampleToFloat(samplePtr, baseFmt)));
            }

            currentPeak = std::max(currentPeak, samplePeak);
            samplesInWindow += 1;
            if (samplesInWindow >= kSamplesPerPeakWindow) {
                rawPeaks.push_back(std::clamp(currentPeak, 0.0f, 1.0f));
                currentPeak = 0.0f;
                samplesInWindow = 0;
            }
        }
    };

    while (av_read_frame(formatCtx, packet) >= 0) {
        if (packet->stream_index == streamIndex) {
            if (avcodec_send_packet(codecCtx, packet) >= 0) {
                while (avcodec_receive_frame(codecCtx, frame) >= 0) {
                    consumeFrame(frame);
                    av_frame_unref(frame);
                }
            }
        }
        av_packet_unref(packet);
    }

    avcodec_send_packet(codecCtx, nullptr);
    while (avcodec_receive_frame(codecCtx, frame) >= 0) {
        consumeFrame(frame);
        av_frame_unref(frame);
    }

    if (samplesInWindow > 0) {
        rawPeaks.push_back(std::clamp(currentPeak, 0.0f, 1.0f));
    }

    const std::vector<float> waveform = compressWaveform(rawPeaks);
    frameGuard();
    return waveform;
#endif
}

void AudioEnginePro::applyFilter(float* buffer, size_t numSamples) {
    if (!buffer || numSamples == 0) return;

    float previous = 0.0f;
    constexpr float alpha = 0.42f;
    constexpr float limiterThreshold = 0.92f;
    for (size_t i = 0; i < numSamples; ++i) {
        float sample = buffer[i];
        sample = (alpha * sample) + ((1.0f - alpha) * previous);
        previous = sample;

        if (sample > limiterThreshold) {
            sample = limiterThreshold + ((sample - limiterThreshold) * 0.25f);
        } else if (sample < -limiterThreshold) {
            sample = -limiterThreshold + ((sample + limiterThreshold) * 0.25f);
        }
        buffer[i] = std::clamp(sample, -1.0f, 1.0f);
    }
}

void AudioEnginePro::syncClock(int64_t videoPtsUs) {
    m_lastVideoPtsUs = videoPtsUs;
    m_lastDriftUs = videoPtsUs - m_audioClockUs;
    const float driftMs = static_cast<float>(m_lastDriftUs) / 1000.0f;
    m_playbackRateCorrectionPpm = std::clamp(driftMs * 8.0f, -250.0f, 250.0f);
    m_audioClockUs = videoPtsUs;
}

void AudioEnginePro::observeClocks(int64_t audioPtsUs, int64_t videoPtsUs) {
    m_audioClockUs = std::max<int64_t>(0, audioPtsUs);
    m_lastVideoPtsUs = std::max<int64_t>(0, videoPtsUs);
    m_lastDriftUs = m_lastVideoPtsUs - m_audioClockUs;

    if (m_smoothedAudioClockUs == 0) {
        m_smoothedAudioClockUs = m_audioClockUs;
        m_smoothedDriftUs = m_lastDriftUs;
    } else {
        m_smoothedDriftUs = static_cast<int64_t>(
            (static_cast<double>(m_smoothedDriftUs) * 0.82) +
            (static_cast<double>(m_lastDriftUs) * 0.18));
        const int64_t driftBiasUs = m_smoothedDriftUs / 6;
        const int64_t targetClockUs = m_audioClockUs + driftBiasUs;
        m_smoothedAudioClockUs = static_cast<int64_t>(
            (static_cast<double>(m_smoothedAudioClockUs) * 0.65) +
            (static_cast<double>(targetClockUs) * 0.35));
        if (m_smoothedAudioClockUs < m_audioClockUs - 5000) {
            m_smoothedAudioClockUs = m_audioClockUs - 5000;
        }
        if (m_smoothedAudioClockUs > m_audioClockUs + 50000) {
            m_smoothedAudioClockUs = m_audioClockUs + 50000;
        }
    }

    const float driftMs = static_cast<float>(m_smoothedDriftUs) / 1000.0f;
    m_playbackRateCorrectionPpm = std::clamp(driftMs * 6.0f, -300.0f, 300.0f);
}

}  // namespace VideoEngine::DeepPro

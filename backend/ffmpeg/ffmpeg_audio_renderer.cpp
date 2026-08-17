#include "ffmpeg_audio_renderer.h"
#include "../../smooth_engine/AudioDucking.h"
#include <cstring>
#include <algorithm>
#include <iostream>
#include <cmath>
#include <map>

// FFmpeg C headers
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/avutil.h>
#include <libavutil/frame.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
#include <libavutil/samplefmt.h>
}

namespace VideoEngine::Backend {

bool AudioRenderer::ffmpegAudioInitialized_ = false;

struct AudioRenderer::FFmpegAudioContext {
    // Decoder state (reused per clip)
    AVFormatContext* decodeFormatCtx = nullptr;
    AVCodecContext* decodeCodecCtx = nullptr;
    const AVCodec* decodeCodec = nullptr;
    int audioStreamIdx = -1;
    AVFrame* decodeFrame = nullptr;

    // Encoder state
    AVCodecContext* encodeCodecCtx = nullptr;
    const AVCodec* encodeCodec = nullptr;
    AVFrame* encodeFrame = nullptr;
    AVPacket* encodePacket = nullptr;
    int encodeFrameCount = 0;

    // Resampler (created per decode session)
    SwrContext* resamplerCtx = nullptr;

    ~FFmpegAudioContext() {
        if (encodePacket) {
            av_packet_free(&encodePacket);
            encodePacket = nullptr;
        }

        if (encodeFrame) {
            av_frame_free(&encodeFrame);
            encodeFrame = nullptr;
        }

        if (encodeCodecCtx) {
            avcodec_free_context(&encodeCodecCtx);
            encodeCodecCtx = nullptr;
        }

        if (decodeFrame) {
            av_frame_free(&decodeFrame);
            decodeFrame = nullptr;
        }

        if (decodeCodecCtx) {
            avcodec_free_context(&decodeCodecCtx);
            decodeCodecCtx = nullptr;
        }

        if (decodeFormatCtx) {
            avformat_close_input(&decodeFormatCtx);
            decodeFormatCtx = nullptr;
        }

        if (resamplerCtx) {
            swr_free(&resamplerCtx);
            resamplerCtx = nullptr;
        }
    }
};

AudioRenderer::AudioRenderer()
    : AudioRenderer(AudioConfig()) {}

AudioRenderer::AudioRenderer(const AudioConfig& config)
    : config_(config) {
    if (!initializeFFmpegAudio()) {
        throw AudioRendererException("Failed to initialize FFmpeg audio");
    }
    ctx_ = std::make_unique<FFmpegAudioContext>();

    // Initialize audio encoder
    ctx_->encodeCodec = avcodec_find_encoder_by_name(config_.outputCodec.c_str());
    if (!ctx_->encodeCodec) {
        throw AudioRendererException("Audio codec not found: " + config_.outputCodec);
    }

    ctx_->encodeCodecCtx = avcodec_alloc_context3(ctx_->encodeCodec);
    if (!ctx_->encodeCodecCtx) {
        throw AudioRendererException("Could not allocate audio codec context");
    }

    // Configure encoder
    ctx_->encodeCodecCtx->sample_rate = config_.sampleRate;
    av_channel_layout_from_mask(&ctx_->encodeCodecCtx->ch_layout, (config_.channels == 2) ? AV_CH_LAYOUT_STEREO : AV_CH_LAYOUT_MONO);
    ctx_->encodeCodecCtx->sample_fmt = AV_SAMPLE_FMT_FLTP;
    ctx_->encodeCodecCtx->bit_rate = config_.bitrate * 1000;
    ctx_->encodeCodecCtx->time_base = {1, config_.sampleRate};

    // Open encoder
    int ret = avcodec_open2(ctx_->encodeCodecCtx, ctx_->encodeCodec, nullptr);
    if (ret < 0) {
        char errbuf[256];
        av_strerror(ret, errbuf, sizeof(errbuf));
        throw AudioRendererException(std::string("Could not open audio encoder: ") + errbuf);
    }

    // Allocate encode frame
    ctx_->encodeFrame = av_frame_alloc();
    if (!ctx_->encodeFrame) {
        throw AudioRendererException("Could not allocate encode frame");
    }

    ctx_->encodeFrame->sample_rate = config_.sampleRate;
    av_channel_layout_copy(&ctx_->encodeFrame->ch_layout, &ctx_->encodeCodecCtx->ch_layout);
    ctx_->encodeFrame->format = AV_SAMPLE_FMT_FLTP;
    ctx_->encodeFrame->nb_samples = ctx_->encodeCodecCtx->frame_size;

    ret = av_frame_get_buffer(ctx_->encodeFrame, 0);
    if (ret < 0) {
        throw AudioRendererException("Could not allocate frame buffer");
    }

    // Allocate encode packet
    ctx_->encodePacket = av_packet_alloc();
    if (!ctx_->encodePacket) {
        throw AudioRendererException("Could not allocate packet");
    }

    // Allocate decode frame
    ctx_->decodeFrame = av_frame_alloc();
    if (!ctx_->decodeFrame) {
        throw AudioRendererException("Could not allocate decode frame");
    }

    std::cout << "[AudioRenderer] Initialized - " << config_.sampleRate << "Hz "
              << config_.channels << "ch, codec: " << config_.outputCodec << "\n";
}

AudioRenderer::~AudioRenderer() = default;

bool AudioRenderer::initializeFFmpegAudio() {
    if (ffmpegAudioInitialized_) return true;
    // Modern FFmpeg (v4.0+) auto-registers codecs
    ffmpegAudioInitialized_ = true;
    return true;
}

AudioRenderer::DecodedAudioSegment AudioRenderer::decodeAudioClip(
    const std::string& clipFilePath,
    TimeMs startOffsetMs,
    TimeMs durationMs) {

    std::cout << "[AudioRenderer] Decoding clip: " << clipFilePath << "\n";

    DecodedAudioSegment result;
    result.startTimeMs = startOffsetMs;
    result.sampleRate = config_.sampleRate;
    result.channels = config_.channels;

    // Open input file
    AVFormatContext* inputCtx = nullptr;
    int ret = avformat_open_input(&inputCtx, clipFilePath.c_str(), nullptr, nullptr);
    if (ret < 0) {
        char errbuf[256];
        av_strerror(ret, errbuf, sizeof(errbuf));
        throw AudioRendererException(std::string("Could not open audio file: ") + errbuf);
    }

    // Find audio stream
    ret = avformat_find_stream_info(inputCtx, nullptr);
    if (ret < 0) {
        avformat_close_input(&inputCtx);
        throw AudioRendererException("Could not find stream info");
    }

    int audioStreamIdx = av_find_best_stream(inputCtx, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (audioStreamIdx < 0) {
        avformat_close_input(&inputCtx);
        throw AudioRendererException("No audio stream found in file");
    }

    AVStream* audioStream = inputCtx->streams[audioStreamIdx];
    const AVCodec* codec = avcodec_find_decoder(audioStream->codecpar->codec_id);
    if (!codec) {
        avformat_close_input(&inputCtx);
        throw AudioRendererException("Codec not found for audio stream");
    }

    AVCodecContext* codecCtx = avcodec_alloc_context3(codec);
    if (!codecCtx) {
        avformat_close_input(&inputCtx);
        throw AudioRendererException("Could not allocate codec context");
    }

    avcodec_parameters_to_context(codecCtx, audioStream->codecpar);
    ret = avcodec_open2(codecCtx, codec, nullptr);
    if (ret < 0) {
        char errbuf[256];
        av_strerror(ret, errbuf, sizeof(errbuf));
        avcodec_free_context(&codecCtx);
        avformat_close_input(&inputCtx);
        throw AudioRendererException(std::string("Could not open codec: ") + errbuf);
    }

    // Seek to start offset if specified
    if (startOffsetMs > 0) {
        int64_t ts = av_rescale_q(startOffsetMs, {1, 1000},
                                 audioStream->time_base);
        av_seek_frame(inputCtx, audioStreamIdx, ts, AVSEEK_FLAG_BACKWARD);
    }

    // Create resampler
    SwrContext* resamplerCtx = swr_alloc();
    if (!resamplerCtx) {
        avcodec_free_context(&codecCtx);
        avformat_close_input(&inputCtx);
        throw AudioRendererException("Could not allocate resampler");
    }

    AVChannelLayout out_ch_layout;
    av_channel_layout_from_mask(&out_ch_layout, (config_.channels == 2) ? AV_CH_LAYOUT_STEREO : AV_CH_LAYOUT_MONO);

    av_opt_set_chlayout(resamplerCtx, "in_chlayout", &codecCtx->ch_layout, 0);
    av_opt_set_chlayout(resamplerCtx, "out_chlayout", &out_ch_layout, 0);
    av_opt_set_int(resamplerCtx, "in_sample_rate", codecCtx->sample_rate, 0);
    av_opt_set_int(resamplerCtx, "out_sample_rate", config_.sampleRate, 0);
    av_opt_set_sample_fmt(resamplerCtx, "in_sample_fmt", codecCtx->sample_fmt, 0);
    av_opt_set_sample_fmt(resamplerCtx, "out_sample_fmt", AV_SAMPLE_FMT_FLT, 0);

    ret = swr_init(resamplerCtx);
    if (ret < 0) {
        char errbuf[256];
        av_strerror(ret, errbuf, sizeof(errbuf));
        swr_free(&resamplerCtx);
        avcodec_free_context(&codecCtx);
        avformat_close_input(&inputCtx);
        throw AudioRendererException(std::string("Could not init resampler: ") + errbuf);
    }

    // Decode audio frames
    std::vector<std::vector<float>> allSamples(config_.channels);
    TimeMs currentTimeMs = startOffsetMs;

    AVPacket* packet = av_packet_alloc();
    if (!packet) {
        swr_free(&resamplerCtx);
        avcodec_free_context(&codecCtx);
        avformat_close_input(&inputCtx);
        throw AudioRendererException("Could not allocate packet");
    }

    int frameCount = 0;
    while (av_read_frame(inputCtx, packet) >= 0) {
        if (packet->stream_index != audioStreamIdx) {
            av_packet_unref(packet);
            continue;
        }

        ret = avcodec_send_packet(codecCtx, packet);
        if (ret < 0) {
            av_packet_unref(packet);
            continue;
        }

        while (avcodec_receive_frame(codecCtx, ctx_->decodeFrame) == 0) {
            // Check duration limit
            if (durationMs > 0) {
                TimeMs frameTimeMs = (frameCount * 1000) / codecCtx->sample_rate;
                if (frameTimeMs >= durationMs) {
                    break;
                }
            }

            // Extract and resample
            int nb_samples = ctx_->decodeFrame->nb_samples;
            uint8_t* resampledData[2] = {nullptr, nullptr};

            ret = av_samples_alloc(resampledData, nullptr, config_.channels,
                                  nb_samples, AV_SAMPLE_FMT_FLT, 0);
            if (ret < 0) {
                continue;
            }

            int outSamples = swr_convert(resamplerCtx,
                                        resampledData, nb_samples,
                                        (const uint8_t**)ctx_->decodeFrame->data,
                                        ctx_->decodeFrame->nb_samples);

            if (outSamples > 0) {
                float* chData[2];
                chData[0] = reinterpret_cast<float*>(resampledData[0]);
                chData[1] = (config_.channels > 1) ?
                    reinterpret_cast<float*>(resampledData[1]) : nullptr;

                for (int ch = 0; ch < config_.channels; ++ch) {
                    if (chData[ch]) {
                        allSamples[ch].insert(allSamples[ch].end(),
                                             chData[ch], chData[ch] + outSamples);
                    }
                }

                currentTimeMs += (outSamples * 1000) / config_.sampleRate;
                frameCount++;
            }

            av_freep(&resampledData[0]);
        }

        av_packet_unref(packet);

        // Check duration limit (outer loop)
        if (durationMs > 0 && currentTimeMs - startOffsetMs >= durationMs) {
            break;
        }
    }

    // Flush decoder
    avcodec_send_packet(codecCtx, nullptr);
    while (avcodec_receive_frame(codecCtx, ctx_->decodeFrame) == 0) {
        int nb_samples = ctx_->decodeFrame->nb_samples;
        uint8_t* resampledData[2] = {nullptr, nullptr};

        av_samples_alloc(resampledData, nullptr, config_.channels,
                        nb_samples, AV_SAMPLE_FMT_FLT, 0);

        int outSamples = swr_convert(resamplerCtx,
                                    resampledData, nb_samples,
                                    (const uint8_t**)ctx_->decodeFrame->data,
                                    ctx_->decodeFrame->nb_samples);

        if (outSamples > 0) {
            float* chData[2];
            chData[0] = reinterpret_cast<float*>(resampledData[0]);
            chData[1] = (config_.channels > 1) ?
                reinterpret_cast<float*>(resampledData[1]) : nullptr;

            for (int ch = 0; ch < config_.channels; ++ch) {
                if (chData[ch]) {
                    allSamples[ch].insert(allSamples[ch].end(),
                                         chData[ch], chData[ch] + outSamples);
                }
            }
        }

        av_freep(&resampledData[0]);
    }

    // Cleanup
    av_packet_free(&packet);
    swr_free(&resamplerCtx);
    avcodec_free_context(&codecCtx);
    avformat_close_input(&inputCtx);

    result.samples = allSamples;
    result.endTimeMs = currentTimeMs;

    std::cout << "[AudioRenderer] Decoded: " << allSamples[0].size()
              << " samples, duration: " << (currentTimeMs - startOffsetMs) << "ms\n";

    return result;
}

void AudioRenderer::applyEffects(
    DecodedAudioSegment& segment,
    TimeMs clipStartTimeMs,
    const std::vector<std::shared_ptr<Effect>>& effects) {

    for (const auto& effect : effects) {
        if (!effect) continue;

        if (effect->type == Effect::Type::Opacity) {
            auto* opacityEffect = dynamic_cast<OpacityEffect*>(effect.get());
            if (!opacityEffect) continue;

            TimeMs clipDurationMs = segment.endTimeMs - segment.startTimeMs;

            // Apply volume ramp
            for (size_t sampleIdx = 0; sampleIdx < segment.samples[0].size(); ++sampleIdx) {
                TimeMs sampleTimeMs = segment.startTimeMs +
                    (sampleIdx * 1000) / segment.sampleRate;

                float opacity = opacityEffect->evaluateAtTime(
                    sampleTimeMs, clipStartTimeMs, clipDurationMs);

                // Apply to all channels
                for (int ch = 0; ch < segment.channels; ++ch) {
                    if (sampleIdx < segment.samples[ch].size()) {
                        segment.samples[ch][sampleIdx] *= opacity;
                    }
                }
            }
        }
    }
}

AudioRenderer::AudioFrame AudioRenderer::mixAudioSegments(
    const std::vector<DecodedAudioSegment>& segments,
    TimeMs startTimeMs,
    TimeMs endTimeMs) {

    AudioFrame result;
    result.sampleRate = config_.sampleRate;
    result.channels = config_.channels;
    result.ptsMs = startTimeMs;

    // Calculate output sample count
    TimeMs durationMs = endTimeMs - startTimeMs;
    int numSamples = (durationMs * config_.sampleRate) / 1000;

    // Initialize output buffer
    result.samples.resize(config_.channels);
    for (int ch = 0; ch < config_.channels; ++ch) {
        result.samples[ch].resize(numSamples, 0.0f);
    }

    std::vector<std::vector<float>> controllerMix(config_.channels, std::vector<float>(numSamples, 0.0f));
    std::vector<std::vector<float>> backgroundMix(config_.channels, std::vector<float>(numSamples, 0.0f));
    bool hasDuckingController = false;
    float duckingAmount = 1.0f;

    auto mixInto = [&](const DecodedAudioSegment& segment, std::vector<std::vector<float>>& target) {
        if (segment.endTimeMs <= startTimeMs || segment.startTimeMs >= endTimeMs) {
            return;
        }
        TimeMs overlapStart = std::max(segment.startTimeMs, startTimeMs);
        TimeMs overlapEnd = std::min(segment.endTimeMs, endTimeMs);
        TimeMs overlapDurationMs = overlapEnd - overlapStart;
        int srcStartSample = ((overlapStart - segment.startTimeMs) * segment.sampleRate) / 1000;
        int outStartSample = ((overlapStart - startTimeMs) * config_.sampleRate) / 1000;
        int numOverlapSamples = (overlapDurationMs * config_.sampleRate) / 1000;

        for (int ch = 0; ch < std::min(config_.channels, segment.channels); ++ch) {
            for (int i = 0; i < numOverlapSamples; ++i) {
                const int srcIdx = srcStartSample + i;
                const int outIdx = outStartSample + i;
                if (srcIdx < static_cast<int>(segment.samples[ch].size()) &&
                    outIdx < static_cast<int>(target[ch].size())) {
                    target[ch][outIdx] += segment.samples[ch][srcIdx];
                }
            }
        }
    };

    for (const auto& segment : segments) {
        if (segment.duckingController) {
            hasDuckingController = true;
            duckingAmount = std::min(duckingAmount, std::clamp(segment.duckingAmount, 0.05f, 1.0f));
            mixInto(segment, controllerMix);
        } else {
            mixInto(segment, backgroundMix);
        }
    }

    if (hasDuckingController) {
        VideoEngine::Advanced::AudioDucking ducking;
        ducking.setDuckingAmount(duckingAmount);
        for (int ch = 0; ch < config_.channels; ++ch) {
            ducking.applyDucking(
                controllerMix[ch].data(),
                backgroundMix[ch].data(),
                static_cast<size_t>(numSamples));
        }
    }

    for (int ch = 0; ch < config_.channels; ++ch) {
        for (int i = 0; i < numSamples; ++i) {
            result.samples[ch][i] = controllerMix[ch][i] + backgroundMix[ch][i];
        }
    }

    // Soft-clip to prevent clipping
    for (int ch = 0; ch < config_.channels; ++ch) {
        for (auto& sample : result.samples[ch]) {
            // Simple soft-clip: tanh-like compression
            if (sample > 1.0f) {
                sample = 1.0f - expf(-(sample - 1.0f));
            } else if (sample < -1.0f) {
                sample = -(1.0f - expf(-((-sample) - 1.0f)));
            }
        }
    }

    return result;
}

std::shared_ptr<AudioRenderer::EncodedAudioPacket> AudioRenderer::encodeAudioFrame(
    const AudioFrame& frame,
    int64_t ptsMs) {

    int numSamples = frame.samples[0].size();

    // Prepare encode frame
    ctx_->encodeFrame->nb_samples = numSamples;
    ctx_->encodeFrame->pts = (ptsMs * config_.sampleRate) / 1000;

    // Copy samples to frame (FLTP format: planar float)
    for (int ch = 0; ch < frame.channels && ch < config_.channels; ++ch) {
        float* dstData = reinterpret_cast<float*>(ctx_->encodeFrame->data[ch]);
        for (int i = 0; i < numSamples; ++i) {
            dstData[i] = (i < (int)frame.samples[ch].size()) ?
                frame.samples[ch][i] : 0.0f;
        }
    }

    // Send frame to encoder
    int ret = avcodec_send_frame(ctx_->encodeCodecCtx, ctx_->encodeFrame);
    if (ret < 0) {
        return nullptr;  // Frame buffered
    }

    // Receive packet
    av_packet_unref(ctx_->encodePacket);
    ret = avcodec_receive_packet(ctx_->encodeCodecCtx, ctx_->encodePacket);
    if (ret != 0) {
        return nullptr;  // No packet yet or error
    }

    auto packet = std::make_shared<EncodedAudioPacket>();
    packet->data.assign(ctx_->encodePacket->data,
                       ctx_->encodePacket->data + ctx_->encodePacket->size);
    packet->ptsMs = ptsMs;
    packet->durationMs = (numSamples * 1000) / config_.sampleRate;

    ctx_->encodeFrameCount++;

    return packet;
}

std::vector<std::shared_ptr<AudioRenderer::EncodedAudioPacket>>
AudioRenderer::flushAudioEncoder() {

    std::vector<std::shared_ptr<EncodedAudioPacket>> packets;

    // Send null frame to signal end
    avcodec_send_frame(ctx_->encodeCodecCtx, nullptr);

    // Receive all remaining packets
    while (true) {
        av_packet_unref(ctx_->encodePacket);
        int ret = avcodec_receive_packet(ctx_->encodeCodecCtx, ctx_->encodePacket);
        if (ret != 0) break;

        auto packet = std::make_shared<EncodedAudioPacket>();
        packet->data.assign(ctx_->encodePacket->data,
                           ctx_->encodePacket->data + ctx_->encodePacket->size);
        packet->ptsMs = ctx_->encodePacket->pts;
        packet->durationMs = ctx_->encodePacket->duration;

        packets.push_back(packet);
    }

    return packets;
}

std::vector<std::shared_ptr<AudioRenderer::EncodedAudioPacket>>
AudioRenderer::generateAudioTrack(const Timeline& timeline,
                                  const std::string& outputFile,
                                  ProgressCallback progress) {

    std::cout << "[AudioRenderer] Generating audio track for timeline...\n";

    std::vector<std::shared_ptr<EncodedAudioPacket>> allPackets;

    const auto& clips = timeline.clips();
    if (clips.empty()) {
        std::cout << "[AudioRenderer] No clips in timeline\n";
        return allPackets;
    }

    // Collect audio clip data
    std::map<TimeMs, DecodedAudioSegment> decodedClips;

    for (size_t i = 0; i < clips.size(); ++i) {
        const auto& clip = clips[i];
        if (!clip) continue;
        // Skip non-audio/non-video clips (text stickers etc.)
        const auto role = clip->getTrackRole();
        if (role != VideoEngine::Clip::TrackRole::MainVideo &&
            role != VideoEngine::Clip::TrackRole::Overlay &&
            role != VideoEngine::Clip::TrackRole::Audio) continue;

        const std::string& path = clip->getMediaPath();
        if (path.empty()) continue;

        try {
            VideoEngine::Clip::TimeMs srcIn = 0, srcOut = 0;
            clip->getTrimPoints(srcIn, srcOut);
            const TimeMs trimDuration = (srcOut > srcIn) ? (srcOut - srcIn) : clip->getDuration();
            DecodedAudioSegment seg = decodeAudioClip(path, srcIn, trimDuration);
            seg.startTimeMs = clip->getStartTime();
            seg.endTimeMs   = clip->getStartTime() + clip->getDuration();
            seg.trackRole = role;
            seg.duckingController = clip->getProperties().duckingEnabled;
            seg.duckingAmount = std::clamp(clip->getProperties().duckingAmount, 0.05f, 1.0f);
            const float vol =
                clip->getProperties().duckingRestoreVolumeValid
                    ? clip->getProperties().duckingRestoreVolumeGain
                    : clip->getProperties().volumeGain;
            if (vol != 1.0f) {
                for (auto& ch : seg.samples)
                    for (auto& s : ch) s *= vol;
            }
            decodedClips[clip->getStartTime()] = std::move(seg);
        } catch (const std::exception& e) {
            std::cout << "[AudioRenderer] Skipping clip (no audio): " << e.what() << "\n";
        }

        if (progress) progress(static_cast<int>(i + 1), static_cast<int>(clips.size()));
    }

    // Get timeline duration
    TimeMs totalDurationMs = timeline.getDuration();
    if (totalDurationMs <= 0) {
        std::cout << "[AudioRenderer] Invalid timeline duration\n";
        return allPackets;
    }

    // Generate audio for each frame chunk
    int numAudioFrames = (totalDurationMs * config_.sampleRate) / 1000;
    int frameSamplesPerPacket = config_.sampleRate / 10;  // 100ms chunks

    for (int frameIdx = 0; frameIdx < numAudioFrames;
         frameIdx += frameSamplesPerPacket) {

        TimeMs frameTimeMs = (frameIdx * 1000) / config_.sampleRate;
        TimeMs nextFrameTimeMs = std::min(
            frameTimeMs + (frameSamplesPerPacket * 1000) / config_.sampleRate,
            totalDurationMs);

        // Mix audio for this chunk
        std::vector<DecodedAudioSegment> visibleSegments;
        for (auto& [startMs, seg] : decodedClips) {
            if (frameTimeMs < seg.endTimeMs && nextFrameTimeMs > seg.startTimeMs)
                visibleSegments.push_back(seg);
        }

        AudioFrame mixedFrame = mixAudioSegments(visibleSegments, frameTimeMs, nextFrameTimeMs);

        // Encode
        auto packet = encodeAudioFrame(mixedFrame, frameTimeMs);
        if (packet) {
            allPackets.push_back(packet);
        }
    }

    // Flush encoder
    auto finalPackets = flushAudioEncoder();
    allPackets.insert(allPackets.end(), finalPackets.begin(), finalPackets.end());

    // Write to file if requested
    if (!outputFile.empty() && !allPackets.empty()) {
        std::cout << "[AudioRenderer] Audio track generated: " << allPackets.size() << " packets\n";
    }

    return allPackets;
}

} // namespace VideoEngine::Backend

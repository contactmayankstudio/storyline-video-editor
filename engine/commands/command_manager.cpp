#include "engine/commands/command_manager.h"

#ifdef __ANDROID__
#include <android/log.h>
#define CM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "AndroidPreview", __VA_ARGS__)
#else
#define CM_LOGI(...) do {} while(0)
#endif

#ifdef __ANDROID__
#include <android/log.h>
#define CM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "AndroidPreview", __VA_ARGS__)
#else
#define CM_LOGI(...) do {} while(0)
#endif

#include <algorithm>
#include <array>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <functional>
#include <map>
#include <mutex>
#include <sstream>
#include <thread>
#include <vector>
#include <utility>
#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>

#include "native_preview_shared.h"
#include "core/clip.h"
#include "core/timeline.h"
#include "backend/ffmpeg/video_decoder.h"

#if defined(VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE)
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/imgutils.h>
#include <libavutil/samplefmt.h>
#include <libswscale/swscale.h>
}
#endif

namespace VideoEngine::Commands {

namespace {

std::atomic<bool> g_ghostPreviewEnabled{true};
std::atomic<int> g_ghostPreviewLongEdgePx{640};
std::atomic<bool> g_adaptiveFrameDropEnabled{true};
std::atomic<int> g_targetPreviewFps{30};
std::atomic<int> g_minPreviewFps{15};
std::atomic<bool> g_dirtyRegionRedrawEnabled{true};
std::atomic<bool> g_predictiveCachingEnabled{true};
std::atomic<int> g_predictiveLookAroundMs{2000};
std::atomic<int> g_predictiveSampleStepMs{120};
std::atomic<int> g_predictiveCacheMaxFrames{40};

struct ClipProxyState {
    bool building = false;
    bool ready = false;
    bool failed = false;
    int progress = 0;
    std::string sourcePath;
    std::string proxyPath;
    std::string message;
};

std::mutex g_proxyStateMutex;
std::map<int, ClipProxyState> g_proxyStates;
std::mutex g_keyframeMutex;
std::map<int, std::vector<int64_t>> g_clipKeyframes;
constexpr int64_t kDefaultStillImageDurationMs = 5000;

std::string normalizedExtension(std::string path) {
    const size_t dotPos = path.find_last_of('.');
    if (dotPos == std::string::npos) {
        return {};
    }
    std::string ext = path.substr(dotPos + 1);
    std::transform(
        ext.begin(),
        ext.end(),
        ext.begin(),
        [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return ext;
}

bool isStillImagePath(const std::string& path) {
    const std::string ext = normalizedExtension(path);
    return ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp" ||
        ext == "bmp" || ext == "gif" || ext == "tif" || ext == "tiff";
}

void boostCommandThreadPriority() {
    sched_param sp{};
    const int maxPrio = sched_get_priority_max(SCHED_FIFO);
    if (maxPrio > 0) {
        sp.sched_priority = std::max(1, maxPrio - 3);
        if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &sp) == 0) {
            return;
        }
    }
    setpriority(PRIO_PROCESS, 0, -6);
}

void applyPreviewPolicyLocked(std::unique_ptr<VideoEngine::PreviewController>* previewRef) {
    if (!previewRef || !(*previewRef)) {
        return;
    }
    (*previewRef)->setGhostPreviewEnabled(g_ghostPreviewEnabled.load());
    (*previewRef)->setGhostPreviewLongEdgePx(g_ghostPreviewLongEdgePx.load());
    (*previewRef)->setAdaptiveFrameDropPolicy(
        g_adaptiveFrameDropEnabled.load(),
        g_targetPreviewFps.load(),
        g_minPreviewFps.load());
    (*previewRef)->setDirtyRegionRedrawEnabled(g_dirtyRegionRedrawEnabled.load());
    (*previewRef)->setPredictiveCachingPolicy(
        g_predictiveCachingEnabled.load(),
        g_predictiveLookAroundMs.load(),
        g_predictiveSampleStepMs.load(),
        g_predictiveCacheMaxFrames.load());
}

void updateProxyState(
    int clipId,
    const std::function<void(ClipProxyState&)>& mutate) {
    std::lock_guard<std::mutex> lock(g_proxyStateMutex);
    mutate(g_proxyStates[clipId]);
}

ClipProxyState snapshotProxyState(int clipId) {
    std::lock_guard<std::mutex> lock(g_proxyStateMutex);
    auto it = g_proxyStates.find(clipId);
    if (it != g_proxyStates.end()) {
        return it->second;
    }
    return ClipProxyState{};
}

#if defined(VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE)
int64_t framePtsUs(const AVFrame* frame, const AVRational& timeBase) {
    if (!frame) return 0;
    int64_t ts = frame->best_effort_timestamp;
    if (ts == AV_NOPTS_VALUE) ts = frame->pts;
    if (ts == AV_NOPTS_VALUE) return 0;
    return av_rescale_q(ts, timeBase, AVRational{1, AV_TIME_BASE});
}

bool buildProxyVideo(
    const std::string& inputPath,
    const std::string& outputPath,
    int maxLongEdgePx,
    int targetFps,
    const std::function<void(int)>& onProgress,
    std::string& errorOut) {
    AVFormatContext* inFmt = nullptr;
    AVCodecContext* decCtx = nullptr;
    AVCodecContext* encCtx = nullptr;
    AVFormatContext* outFmt = nullptr;
    SwsContext* sws = nullptr;
    AVFrame* decoded = nullptr;
    AVFrame* yuv = nullptr;
    AVPacket* packet = nullptr;
    AVPacket* inputPacket = nullptr;
    AVStream* inStream = nullptr;
    AVStream* outStream = nullptr;
    int videoStreamIndex = -1;
    bool success = false;

    auto cleanup = [&]() {
        if (packet) av_packet_free(&packet);
        if (inputPacket) av_packet_free(&inputPacket);
        if (decoded) av_frame_free(&decoded);
        if (yuv) av_frame_free(&yuv);
        if (sws) sws_freeContext(sws);
        if (decCtx) avcodec_free_context(&decCtx);
        if (encCtx) avcodec_free_context(&encCtx);
        if (outFmt) {
            if (!(outFmt->oformat->flags & AVFMT_NOFILE) && outFmt->pb) {
                avio_closep(&outFmt->pb);
            }
            avformat_free_context(outFmt);
        }
        if (inFmt) {
            avformat_close_input(&inFmt);
        }
        if (!success) {
            std::remove(outputPath.c_str());
        }
    };

    if (avformat_open_input(&inFmt, inputPath.c_str(), nullptr, nullptr) < 0) {
        errorOut = "Failed to open input";
        cleanup();
        return false;
    }
    if (avformat_find_stream_info(inFmt, nullptr) < 0) {
        errorOut = "Failed to read stream info";
        cleanup();
        return false;
    }

    for (unsigned int i = 0; i < inFmt->nb_streams; ++i) {
        if (inFmt->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            videoStreamIndex = static_cast<int>(i);
            break;
        }
    }
    if (videoStreamIndex < 0) {
        errorOut = "No video stream found";
        cleanup();
        return false;
    }

    inStream = inFmt->streams[videoStreamIndex];
    const AVCodec* decoder = avcodec_find_decoder(inStream->codecpar->codec_id);
    if (!decoder) {
        errorOut = "Decoder unavailable";
        cleanup();
        return false;
    }
    decCtx = avcodec_alloc_context3(decoder);
    if (!decCtx ||
        avcodec_parameters_to_context(decCtx, inStream->codecpar) < 0 ||
        avcodec_open2(decCtx, decoder, nullptr) < 0) {
        errorOut = "Failed to init decoder";
        cleanup();
        return false;
    }

    int srcW = decCtx->width;
    int srcH = decCtx->height;
    if (srcW <= 0 || srcH <= 0) {
        errorOut = "Invalid source dimensions";
        cleanup();
        return false;
    }
    int dstW = srcW;
    int dstH = srcH;
    if (maxLongEdgePx > 0) {
        const int longEdge = std::max(srcW, srcH);
        if (longEdge > maxLongEdgePx) {
            const double scale = static_cast<double>(maxLongEdgePx) / static_cast<double>(longEdge);
            dstW = std::max(2, static_cast<int>(srcW * scale));
            dstH = std::max(2, static_cast<int>(srcH * scale));
        }
    }
    if (dstW % 2 != 0) --dstW;
    if (dstH % 2 != 0) --dstH;
    dstW = std::max(2, dstW);
    dstH = std::max(2, dstH);

    const AVCodec* encoder = avcodec_find_encoder(AV_CODEC_ID_H264);
    if (!encoder) {
        encoder = avcodec_find_encoder(AV_CODEC_ID_MPEG4);
    }
    if (!encoder) {
        errorOut = "Encoder unavailable";
        cleanup();
        return false;
    }

    if (avformat_alloc_output_context2(&outFmt, nullptr, nullptr, outputPath.c_str()) < 0 || !outFmt) {
        errorOut = "Failed to create output container";
        cleanup();
        return false;
    }
    outStream = avformat_new_stream(outFmt, nullptr);
    if (!outStream) {
        errorOut = "Failed to create output stream";
        cleanup();
        return false;
    }

    encCtx = avcodec_alloc_context3(encoder);
    if (!encCtx) {
        errorOut = "Failed to alloc encoder context";
        cleanup();
        return false;
    }
    const int fps = std::max(1, targetFps);
    encCtx->codec_type = AVMEDIA_TYPE_VIDEO;
    encCtx->codec_id = encoder->id;
    encCtx->width = dstW;
    encCtx->height = dstH;
    encCtx->pix_fmt = AV_PIX_FMT_YUV420P;
    encCtx->time_base = AVRational{1, fps};
    encCtx->framerate = AVRational{fps, 1};
    encCtx->gop_size = fps;
    encCtx->max_b_frames = 0;
    encCtx->bit_rate = std::clamp<int64_t>(
        static_cast<int64_t>(dstW) * static_cast<int64_t>(dstH) * fps / 12,
        350000,
        3000000);
    if (outFmt->oformat->flags & AVFMT_GLOBALHEADER) {
        encCtx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }

    AVDictionary* codecOpts = nullptr;
    if (encoder->id == AV_CODEC_ID_H264) {
        av_dict_set(&codecOpts, "preset", "ultrafast", 0);
        av_dict_set(&codecOpts, "tune", "zerolatency", 0);
    }
    if (avcodec_open2(encCtx, encoder, &codecOpts) < 0) {
        av_dict_free(&codecOpts);
        errorOut = "Failed to open encoder";
        cleanup();
        return false;
    }
    av_dict_free(&codecOpts);
    if (avcodec_parameters_from_context(outStream->codecpar, encCtx) < 0) {
        errorOut = "Failed to copy encoder params";
        cleanup();
        return false;
    }
    outStream->time_base = encCtx->time_base;

    if (!(outFmt->oformat->flags & AVFMT_NOFILE)) {
        if (avio_open(&outFmt->pb, outputPath.c_str(), AVIO_FLAG_WRITE) < 0) {
            errorOut = "Failed to open output file";
            cleanup();
            return false;
        }
    }
    if (avformat_write_header(outFmt, nullptr) < 0) {
        errorOut = "Failed to write output header";
        cleanup();
        return false;
    }

    sws = sws_getContext(
        srcW,
        srcH,
        decCtx->pix_fmt,
        dstW,
        dstH,
        AV_PIX_FMT_YUV420P,
        SWS_BILINEAR,
        nullptr,
        nullptr,
        nullptr);
    if (!sws) {
        errorOut = "Failed to create scaler";
        cleanup();
        return false;
    }

    decoded = av_frame_alloc();
    yuv = av_frame_alloc();
    packet = av_packet_alloc();
    inputPacket = av_packet_alloc();
    if (!decoded || !yuv || !packet || !inputPacket) {
        errorOut = "Failed to alloc frame/packet";
        cleanup();
        return false;
    }
    yuv->format = AV_PIX_FMT_YUV420P;
    yuv->width = dstW;
    yuv->height = dstH;
    if (av_frame_get_buffer(yuv, 32) < 0) {
        errorOut = "Failed to alloc output frame buffer";
        cleanup();
        return false;
    }

    int64_t nextPts = 0;
    int64_t lastEncodedPtsUs = -1;
    const int64_t minFrameDeltaUs = static_cast<int64_t>(1000000.0 / fps);
    const int64_t totalDurationUs = inFmt->duration > 0 ? inFmt->duration : 0;
    int lastProgress = -1;

    auto writeEncodedPackets = [&](bool flush) -> bool {
        if (flush) {
            if (avcodec_send_frame(encCtx, nullptr) < 0) {
                errorOut = "Failed to flush encoder";
                return false;
            }
        }
        while (true) {
            int ret = avcodec_receive_packet(encCtx, packet);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                break;
            }
            if (ret < 0) {
                errorOut = "Failed to receive encoded packet";
                return false;
            }
            av_packet_rescale_ts(packet, encCtx->time_base, outStream->time_base);
            packet->stream_index = outStream->index;
            if (av_interleaved_write_frame(outFmt, packet) < 0) {
                av_packet_unref(packet);
                errorOut = "Failed to write packet";
                return false;
            }
            av_packet_unref(packet);
        }
        return true;
    };

    while (av_read_frame(inFmt, inputPacket) >= 0) {
        if (inputPacket->stream_index != videoStreamIndex) {
            av_packet_unref(inputPacket);
            continue;
        }
        if (avcodec_send_packet(decCtx, inputPacket) < 0) {
            av_packet_unref(inputPacket);
            errorOut = "Failed to send packet to decoder";
            cleanup();
            return false;
        }
        av_packet_unref(inputPacket);

        while (avcodec_receive_frame(decCtx, decoded) == 0) {
            const int64_t ptsUs = framePtsUs(decoded, inStream->time_base);
            if (lastEncodedPtsUs >= 0 && ptsUs > 0 && (ptsUs - lastEncodedPtsUs) < minFrameDeltaUs) {
                continue;
            }
            if (av_frame_make_writable(yuv) < 0) {
                errorOut = "Output frame not writable";
                cleanup();
                return false;
            }
            sws_scale(
                sws,
                decoded->data,
                decoded->linesize,
                0,
                decoded->height,
                yuv->data,
                yuv->linesize);
            yuv->pts = nextPts++;
            if (avcodec_send_frame(encCtx, yuv) < 0) {
                errorOut = "Failed to send frame to encoder";
                cleanup();
                return false;
            }
            if (!writeEncodedPackets(false)) {
                cleanup();
                return false;
            }

            if (ptsUs > 0) {
                lastEncodedPtsUs = ptsUs;
            }
            if (totalDurationUs > 0) {
                int progress = static_cast<int>((std::max<int64_t>(0, ptsUs) * 100) / totalDurationUs);
                progress = std::clamp(progress, 0, 99);
                if (progress != lastProgress) {
                    onProgress(progress);
                    lastProgress = progress;
                }
            }
        }
    }

    // Drain decoder.
    avcodec_send_packet(decCtx, nullptr);
    while (avcodec_receive_frame(decCtx, decoded) == 0) {
        if (av_frame_make_writable(yuv) < 0) break;
        sws_scale(
            sws,
            decoded->data,
            decoded->linesize,
            0,
            decoded->height,
            yuv->data,
            yuv->linesize);
        yuv->pts = nextPts++;
        if (avcodec_send_frame(encCtx, yuv) < 0) break;
        if (!writeEncodedPackets(false)) {
            cleanup();
            return false;
        }
    }

    if (!writeEncodedPackets(true)) {
        cleanup();
        return false;
    }
    if (av_write_trailer(outFmt) < 0) {
        errorOut = "Failed to finalize output file";
        cleanup();
        return false;
    }
    onProgress(100);
    success = true;
    cleanup();
    return true;
}

AVSampleFormat normalizeSampleFormat(AVSampleFormat fmt) {
    switch (fmt) {
        case AV_SAMPLE_FMT_U8P: return AV_SAMPLE_FMT_U8;
        case AV_SAMPLE_FMT_S16P: return AV_SAMPLE_FMT_S16;
        case AV_SAMPLE_FMT_S32P: return AV_SAMPLE_FMT_S32;
        case AV_SAMPLE_FMT_FLTP: return AV_SAMPLE_FMT_FLT;
        case AV_SAMPLE_FMT_DBLP: return AV_SAMPLE_FMT_DBL;
        default: return fmt;
    }
}

float sampleAbsNormalized(const uint8_t* samplePtr, AVSampleFormat normalizedFmt) {
    if (!samplePtr) return 0.0f;
    switch (normalizedFmt) {
        case AV_SAMPLE_FMT_U8: {
            const auto value = static_cast<int>(*reinterpret_cast<const uint8_t*>(samplePtr)) - 128;
            return std::abs(static_cast<float>(value) / 128.0f);
        }
        case AV_SAMPLE_FMT_S16: {
            const auto value = *reinterpret_cast<const int16_t*>(samplePtr);
            return std::abs(static_cast<float>(value) / 32768.0f);
        }
        case AV_SAMPLE_FMT_S32: {
            const auto value = *reinterpret_cast<const int32_t*>(samplePtr);
            return std::abs(static_cast<float>(value) / 2147483648.0f);
        }
        case AV_SAMPLE_FMT_FLT: {
            const auto value = *reinterpret_cast<const float*>(samplePtr);
            return std::abs(value);
        }
        case AV_SAMPLE_FMT_DBL: {
            const auto value = *reinterpret_cast<const double*>(samplePtr);
            return std::abs(static_cast<float>(value));
        }
        default:
            return 0.0f;
    }
}

struct PeakMapFileHeader {
    char magic[8];
    uint32_t version;
    uint32_t bucketMs;
    uint32_t sampleRate;
    uint32_t peakCount;
    uint64_t durationMs;
};

constexpr std::array<char, 8> kPeakMapMagic = {'V', 'E', 'P', 'E', 'A', 'K', '1', '\0'};
constexpr uint32_t kPeakMapVersion = 1;

bool writeAudioPeakMapFile(
    const std::string& outputPath,
    int bucketMs,
    int sampleRate,
    int64_t durationMs,
    const std::vector<uint8_t>& peaks,
    std::string& errorOut) {
    std::ofstream output(outputPath, std::ios::binary | std::ios::trunc);
    if (!output.is_open()) {
        errorOut = "Failed to open peak map output file";
        return false;
    }

    PeakMapFileHeader header{};
    std::memcpy(header.magic, kPeakMapMagic.data(), kPeakMapMagic.size());
    header.version = kPeakMapVersion;
    header.bucketMs = static_cast<uint32_t>(std::max(1, bucketMs));
    header.sampleRate = static_cast<uint32_t>(std::max(1, sampleRate));
    header.peakCount = static_cast<uint32_t>(peaks.size());
    header.durationMs = static_cast<uint64_t>(std::max<int64_t>(0, durationMs));

    output.write(reinterpret_cast<const char*>(&header), sizeof(header));
    if (!peaks.empty()) {
        output.write(reinterpret_cast<const char*>(peaks.data()), static_cast<std::streamsize>(peaks.size()));
    }
    if (!output.good()) {
        errorOut = "Failed to write peak map output";
        output.close();
        std::remove(outputPath.c_str());
        return false;
    }
    output.close();
    return true;
}

bool buildAudioPeakMap(
    const std::string& inputPath,
    const std::string& outputPath,
    int bucketMs,
    const std::function<void(int)>& onProgress,
    int* outPeakCount,
    int64_t* outDurationMs,
    int* outSampleRate,
    std::string& errorOut) {
    AVFormatContext* inFmt = nullptr;
    AVCodecContext* decCtx = nullptr;
    AVFrame* frame = nullptr;
    AVPacket* packet = nullptr;
    int audioStreamIndex = -1;
    AVStream* audioStream = nullptr;
    bool success = false;

    auto cleanup = [&]() {
        if (packet) av_packet_free(&packet);
        if (frame) av_frame_free(&frame);
        if (decCtx) avcodec_free_context(&decCtx);
        if (inFmt) avformat_close_input(&inFmt);
        if (!success) {
            std::remove(outputPath.c_str());
        }
    };

    if (avformat_open_input(&inFmt, inputPath.c_str(), nullptr, nullptr) < 0) {
        errorOut = "Failed to open audio input";
        cleanup();
        return false;
    }
    if (avformat_find_stream_info(inFmt, nullptr) < 0) {
        errorOut = "Failed to read audio stream info";
        cleanup();
        return false;
    }

    for (unsigned int i = 0; i < inFmt->nb_streams; ++i) {
        if (inFmt->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            audioStreamIndex = static_cast<int>(i);
            audioStream = inFmt->streams[i];
            break;
        }
    }
    if (audioStreamIndex < 0 || !audioStream) {
        errorOut = "No audio stream found";
        cleanup();
        return false;
    }

    const AVCodec* decoder = avcodec_find_decoder(audioStream->codecpar->codec_id);
    if (!decoder) {
        errorOut = "Audio decoder unavailable";
        cleanup();
        return false;
    }
    decCtx = avcodec_alloc_context3(decoder);
    if (!decCtx ||
        avcodec_parameters_to_context(decCtx, audioStream->codecpar) < 0 ||
        avcodec_open2(decCtx, decoder, nullptr) < 0) {
        errorOut = "Failed to init audio decoder";
        cleanup();
        return false;
    }

    frame = av_frame_alloc();
    packet = av_packet_alloc();
    if (!frame || !packet) {
        errorOut = "Failed to alloc audio decode buffers";
        cleanup();
        return false;
    }

    const int sampleRate = std::max(1, decCtx->sample_rate);
    const int channels = std::max(
        1,
        decCtx->ch_layout.nb_channels > 0
            ? static_cast<int>(decCtx->ch_layout.nb_channels)
            : 1);
    const AVSampleFormat sampleFmt = static_cast<AVSampleFormat>(decCtx->sample_fmt);
    const AVSampleFormat normalizedFmt = normalizeSampleFormat(sampleFmt);
    const int bytesPerSample = av_get_bytes_per_sample(normalizedFmt);
    if (bytesPerSample <= 0) {
        errorOut = "Unsupported audio sample format";
        cleanup();
        return false;
    }

    const bool planar = av_sample_fmt_is_planar(sampleFmt) != 0;
    const int bucketSizeMs = std::max(1, bucketMs);
    const int samplesPerBucket = std::max(1, (sampleRate * bucketSizeMs) / 1000);
    const int64_t totalDurationUs = inFmt->duration > 0
        ? inFmt->duration
        : (audioStream->duration > 0
              ? av_rescale_q(audioStream->duration, audioStream->time_base, AVRational{1, AV_TIME_BASE})
              : 0);

    std::vector<uint8_t> peaks;
    peaks.reserve(2048);
    int sampleCounter = 0;
    float bucketPeak = 0.0f;
    int lastProgress = -1;
    int64_t decodedSamples = 0;

    auto consumeFrame = [&](const AVFrame* decodedFrame) {
        if (!decodedFrame) return;
        for (int sampleIndex = 0; sampleIndex < decodedFrame->nb_samples; ++sampleIndex) {
            float samplePeak = 0.0f;
            for (int ch = 0; ch < channels; ++ch) {
                const uint8_t* basePtr = planar ? decodedFrame->data[ch] : decodedFrame->data[0];
                if (!basePtr) continue;
                const uint8_t* samplePtr = nullptr;
                if (planar) {
                    samplePtr = basePtr + (sampleIndex * bytesPerSample);
                } else {
                    samplePtr = basePtr + ((sampleIndex * channels + ch) * bytesPerSample);
                }
                samplePeak = std::max(samplePeak, sampleAbsNormalized(samplePtr, normalizedFmt));
            }
            bucketPeak = std::max(bucketPeak, samplePeak);
            sampleCounter++;
            decodedSamples++;
            if (sampleCounter >= samplesPerBucket) {
                peaks.push_back(static_cast<uint8_t>(std::clamp<int>(
                    static_cast<int>(std::round(bucketPeak * 255.0f)),
                    0,
                    255)));
                sampleCounter = 0;
                bucketPeak = 0.0f;
            }
        }

        if (totalDurationUs > 0) {
            const int64_t decodedUs = (decodedSamples * AV_TIME_BASE) / sampleRate;
            int progress = static_cast<int>((decodedUs * 100) / totalDurationUs);
            progress = std::clamp(progress, 0, 99);
            if (progress != lastProgress) {
                onProgress(progress);
                lastProgress = progress;
            }
        }
    };

    while (av_read_frame(inFmt, packet) >= 0) {
        if (packet->stream_index != audioStreamIndex) {
            av_packet_unref(packet);
            continue;
        }
        if (avcodec_send_packet(decCtx, packet) < 0) {
            av_packet_unref(packet);
            errorOut = "Failed to send packet to audio decoder";
            cleanup();
            return false;
        }
        av_packet_unref(packet);

        while (avcodec_receive_frame(decCtx, frame) == 0) {
            consumeFrame(frame);
        }
    }

    avcodec_send_packet(decCtx, nullptr);
    while (avcodec_receive_frame(decCtx, frame) == 0) {
        consumeFrame(frame);
    }

    if (sampleCounter > 0) {
        peaks.push_back(static_cast<uint8_t>(std::clamp<int>(
            static_cast<int>(std::round(bucketPeak * 255.0f)),
            0,
            255)));
    }

    const int64_t durationMs = totalDurationUs > 0
        ? (totalDurationUs / 1000)
        : static_cast<int64_t>((decodedSamples * 1000) / sampleRate);

    if (!writeAudioPeakMapFile(outputPath, bucketSizeMs, sampleRate, durationMs, peaks, errorOut)) {
        cleanup();
        return false;
    }

    if (outPeakCount) *outPeakCount = static_cast<int>(peaks.size());
    if (outDurationMs) *outDurationMs = durationMs;
    if (outSampleRate) *outSampleRate = sampleRate;
    onProgress(100);
    success = true;
    cleanup();
    return true;
}

bool readAudioPeakMapFile(
    const std::string& peakPath,
    PeakMapFileHeader* headerOut,
    std::vector<uint8_t>* peaksOut,
    std::string& errorOut) {
    if (!headerOut || !peaksOut) {
        errorOut = "Invalid peak map output buffers";
        return false;
    }

    std::ifstream input(peakPath, std::ios::binary);
    if (!input.is_open()) {
        errorOut = "Failed to open peak map file";
        return false;
    }

    PeakMapFileHeader header{};
    input.read(reinterpret_cast<char*>(&header), sizeof(header));
    if (!input.good()) {
        errorOut = "Failed to read peak map header";
        return false;
    }
    if (std::memcmp(header.magic, kPeakMapMagic.data(), kPeakMapMagic.size()) != 0 ||
        header.version != kPeakMapVersion) {
        errorOut = "Invalid peak map format";
        return false;
    }

    std::vector<uint8_t> peaks;
    peaks.resize(header.peakCount);
    if (header.peakCount > 0) {
        input.read(reinterpret_cast<char*>(peaks.data()), static_cast<std::streamsize>(peaks.size()));
        if (!input.good()) {
            errorOut = "Failed to read peak map payload";
            return false;
        }
    }

    *headerOut = header;
    *peaksOut = std::move(peaks);
    return true;
}
#else
bool buildProxyVideo(
    const std::string& inputPath,
    const std::string& outputPath,
    int maxLongEdgePx,
    int targetFps,
    const std::function<void(int)>& onProgress,
    std::string& errorOut) {
    (void)inputPath;
    (void)outputPath;
    (void)maxLongEdgePx;
    (void)targetFps;
    (void)onProgress;
    errorOut = "FFmpeg proxy build unavailable for this ABI";
    return false;
}

bool buildAudioPeakMap(
    const std::string& inputPath,
    const std::string& outputPath,
    int bucketMs,
    const std::function<void(int)>& onProgress,
    int* outPeakCount,
    int64_t* outDurationMs,
    int* outSampleRate,
    std::string& errorOut) {
    (void)inputPath;
    (void)outputPath;
    (void)bucketMs;
    (void)onProgress;
    if (outPeakCount) *outPeakCount = 0;
    if (outDurationMs) *outDurationMs = 0;
    if (outSampleRate) *outSampleRate = 0;
    errorOut = "FFmpeg audio peak map unavailable for this ABI";
    return false;
}

struct PeakMapFileHeader {
    char magic[8];
    uint32_t version;
    uint32_t bucketMs;
    uint32_t sampleRate;
    uint32_t peakCount;
    uint64_t durationMs;
};

bool readAudioPeakMapFile(
    const std::string& peakPath,
    PeakMapFileHeader* headerOut,
    std::vector<uint8_t>* peaksOut,
    std::string& errorOut) {
    (void)peakPath;
    if (headerOut) {
        *headerOut = PeakMapFileHeader{};
    }
    if (peaksOut) {
        peaksOut->clear();
    }
    errorOut = "FFmpeg audio peak map unavailable for this ABI";
    return false;
}
#endif

std::string jsonEscape(const std::string& input) {
    std::string out;
    out.reserve(input.size() + 16);
    for (char c : input) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '"': out += "\\\""; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += c; break;
        }
    }
    return out;
}

std::string quote(const std::string& value) {
    return "\"" + jsonEscape(value) + "\"";
}

bool isCoalescibleAsyncAction(const std::string& action) {
    return action == "SEEK" ||
        action == "SET_TIMELINE_ZOOM" ||
        action == "UPDATE_AUDIO_CLOCK_US" ||
        action == "SET_AUDIO_MASTER_CLOCK_ENABLED" ||
        action == "SET_PREVIEW_POLICY" ||
        action == "SET_PERFORMANCE_POLICY";
}

long long currentEpochMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::system_clock::now().time_since_epoch())
        .count();
}

constexpr size_t kMaxRecentTelemetryEntries = 400;

void scheduleClipWarmupAsync(
    int clipId,
    const std::string& sourcePath,
    VideoEngine::Clip::TrackRole trackRole) {
    if (clipId < 0 || sourcePath.empty()) {
        return;
    }
    if (trackRole != VideoEngine::Clip::TrackRole::MainVideo &&
        trackRole != VideoEngine::Clip::TrackRole::Overlay) {
        return;
    }
    const std::string payload =
        "{\"clipId\":" + std::to_string(clipId) +
        ",\"sourcePath\":" + quote(sourcePath) +
        ",\"maxLongEdgePx\":360,\"targetFps\":24}";
    CommandManager::instance().executeAsync("BUILD_CLIP_PROXY", payload);
}

std::string extractStringValue(const std::string& json, const std::string& key) {
    const std::string token = "\"" + key + "\"";
    const size_t keyPos = json.find(token);
    if (keyPos == std::string::npos) return "";
    const size_t colonPos = json.find(':', keyPos + token.size());
    if (colonPos == std::string::npos) return "";
    const size_t firstQuote = json.find('"', colonPos + 1);
    if (firstQuote == std::string::npos) return "";
    std::string value;
    bool escaping = false;
    for (size_t i = firstQuote + 1; i < json.size(); ++i) {
        const char c = json[i];
        if (escaping) {
            value += c;
            escaping = false;
            continue;
        }
        if (c == '\\') {
            escaping = true;
            continue;
        }
        if (c == '"') {
            return value;
        }
        value += c;
    }
    return "";
}

bool extractInt64Value(const std::string& json, const std::string& key, int64_t& outValue) {
    const std::string token = "\"" + key + "\"";
    const size_t keyPos = json.find(token);
    if (keyPos == std::string::npos) return false;
    const size_t colonPos = json.find(':', keyPos + token.size());
    if (colonPos == std::string::npos) return false;
    size_t pos = colonPos + 1;
    while (pos < json.size() && std::isspace(static_cast<unsigned char>(json[pos]))) ++pos;
    size_t end = pos;
    if (end < json.size() && (json[end] == '-' || json[end] == '+')) ++end;
    while (end < json.size() && std::isdigit(static_cast<unsigned char>(json[end]))) ++end;
    if (end == pos) return false;
    try {
        outValue = std::stoll(json.substr(pos, end - pos));
        return true;
    } catch (...) {
        return false;
    }
}

bool extractDoubleValue(const std::string& json, const std::string& key, double& outValue) {
    const std::string token = "\"" + key + "\"";
    const size_t keyPos = json.find(token);
    if (keyPos == std::string::npos) return false;
    const size_t colonPos = json.find(':', keyPos + token.size());
    if (colonPos == std::string::npos) return false;
    size_t pos = colonPos + 1;
    while (pos < json.size() && std::isspace(static_cast<unsigned char>(json[pos]))) ++pos;
    size_t end = pos;
    if (end < json.size() && (json[end] == '-' || json[end] == '+')) ++end;
    while (end < json.size() && (std::isdigit(static_cast<unsigned char>(json[end])) || json[end] == '.')) ++end;
    if (end == pos) return false;
    try {
        outValue = std::stod(json.substr(pos, end - pos));
        return true;
    } catch (...) {
        return false;
    }
}

bool extractBoolValue(const std::string& json, const std::string& key, bool& outValue) {
    const std::string token = "\"" + key + "\"";
    const size_t keyPos = json.find(token);
    if (keyPos == std::string::npos) return false;
    const size_t colonPos = json.find(':', keyPos + token.size());
    if (colonPos == std::string::npos) return false;
    size_t pos = colonPos + 1;
    while (pos < json.size() && std::isspace(static_cast<unsigned char>(json[pos]))) ++pos;
    if (json.compare(pos, 4, "true") == 0) {
        outValue = true;
        return true;
    }
    if (json.compare(pos, 5, "false") == 0) {
        outValue = false;
        return true;
    }
    return false;
}

VideoEngine::Clip* findClip(VideoEngine::Timeline* timeline, int clipId) {
    if (!timeline) return nullptr;
    for (const auto& clip : timeline->clips()) {
        if (clip && static_cast<int>(clip->getId()) == clipId) {
            return clip.get();
        }
    }
    return nullptr;
}

std::shared_ptr<VideoEngine::Clip> cloneClip(const VideoEngine::Clip& source) {
    auto copy = std::make_shared<VideoEngine::Clip>(
        source.getMediaPath(),
        source.getStartTime(),
        source.getDuration());
    VideoEngine::Clip::TimeMs inPoint = 0;
    VideoEngine::Clip::TimeMs outPoint = 0;
    source.getTrimPoints(inPoint, outPoint);
    copy->setTrimPoints(inPoint, outPoint);
    copy->getMutableProperties() = source.getProperties();
    copy->getMutableEffects() = source.getEffects();
    copy->getMutableChromaKey() = source.getChromaKey();
    copy->setAudioTrackIndex(source.getAudioTrackIndex());
    copy->setTrackRole(source.getTrackRole());
    copy->setTrackLane(source.getTrackLane());
    copy->setTrackZOrder(source.getTrackZOrder());
    return copy;
}

std::shared_ptr<VideoEngine::Clip> cloneClipRange(
    const VideoEngine::Clip& source,
    VideoEngine::Clip::TimeMs timelineStartMs,
    VideoEngine::Clip::TimeMs durationMs,
    VideoEngine::Clip::TimeMs sourceInMs,
    VideoEngine::Clip::TimeMs sourceOutMs) {
    auto copy = std::make_shared<VideoEngine::Clip>(
        source.getMediaPath(),
        timelineStartMs,
        durationMs);
    copy->setTrimPoints(sourceInMs, sourceOutMs);
    copy->getMutableProperties() = source.getProperties();
    copy->getMutableEffects() = source.getEffects();
    copy->getMutableChromaKey() = source.getChromaKey();
    copy->setAudioTrackIndex(source.getAudioTrackIndex());
    copy->setTrackRole(source.getTrackRole());
    copy->setTrackLane(source.getTrackLane());
    copy->setTrackZOrder(source.getTrackZOrder());
    return copy;
}

std::shared_ptr<VideoEngine::Clip> findClipShared(VideoEngine::Timeline* timeline, int clipId) {
    if (!timeline) return nullptr;
    for (const auto& clip : timeline->clips()) {
        if (clip && static_cast<int>(clip->getId()) == clipId) {
            return clip;
        }
    }
    return nullptr;
}

void normalizePrimaryTrack(VideoEngine::Timeline* timeline) {
    if (!timeline) return;
    std::vector<std::shared_ptr<VideoEngine::Clip>> clips;
    for (const auto& clip : timeline->clips()) {
        if (!clip) continue;
        if (clip->getTrackRole() != VideoEngine::Clip::TrackRole::MainVideo) continue;
        clips.push_back(clip);
    }
    std::stable_sort(
        clips.begin(),
        clips.end(),
        [](const std::shared_ptr<VideoEngine::Clip>& a, const std::shared_ptr<VideoEngine::Clip>& b) {
            if (!a || !b) return static_cast<bool>(a);
            if (a->getStartTime() == b->getStartTime()) {
                return a->getId() < b->getId();
            }
            return a->getStartTime() < b->getStartTime();
        });

    int64_t nextStartMs = 0;
    for (const auto& clip : clips) {
        if (!clip) continue;
        clip->setTimelinePosition(nextStartMs, clip->getDuration());
        nextStartMs += clip->getDuration();
    }
}

int64_t primaryTrackDuration(VideoEngine::Timeline* timeline) {
    if (!timeline) return 0;
    int64_t endMs = 0;
    for (const auto& clip : timeline->clips()) {
        if (!clip) continue;
        if (clip->getTrackRole() != VideoEngine::Clip::TrackRole::MainVideo) continue;
        endMs = std::max(endMs, clip->getStartTime() + std::max<int64_t>(1, clip->getDuration()));
    }
    return endMs;
}

VideoEngine::Clip::TrackRole parseTrackRoleString(const std::string& role) {
    std::string normalized = role;
    std::transform(
        normalized.begin(),
        normalized.end(),
        normalized.begin(),
        [](unsigned char c) { return static_cast<char>(std::toupper(c)); });
    if (normalized == "OVERLAY") return VideoEngine::Clip::TrackRole::Overlay;
    if (normalized == "TEXT" || normalized == "TEXT_STICKER") return VideoEngine::Clip::TrackRole::TextSticker;
    if (normalized == "AUDIO") return VideoEngine::Clip::TrackRole::Audio;
    return VideoEngine::Clip::TrackRole::MainVideo;
}

std::string trackRoleToString(VideoEngine::Clip::TrackRole role) {
    switch (role) {
        case VideoEngine::Clip::TrackRole::MainVideo: return "VIDEO";
        case VideoEngine::Clip::TrackRole::Overlay: return "OVERLAY";
        case VideoEngine::Clip::TrackRole::TextSticker: return "TEXT";
        case VideoEngine::Clip::TrackRole::Audio: return "AUDIO";
        default: return "VIDEO";
    }
}

std::string mediaTypeToString(VideoEngine::Clip::MediaType mediaType) {
    switch (mediaType) {
        case VideoEngine::Clip::MediaType::Video: return "VIDEO";
        case VideoEngine::Clip::MediaType::Audio: return "AUDIO";
        case VideoEngine::Clip::MediaType::Image: return "IMAGE";
        case VideoEngine::Clip::MediaType::Unknown:
        default:
            return "UNKNOWN";
    }
}

int64_t probeDurationMsForPath(const std::string& path) {
    if (isStillImagePath(path)) {
        return kDefaultStillImageDurationMs;
    }
    VideoEngine::Backend::VideoDecoder decoder;
    if (!decoder.open(path)) {
        return 0;
    }
    const double durationSeconds = decoder.getDuration();
    decoder.close();
    if (durationSeconds <= 0.0) {
        return 0;
    }
    return static_cast<int64_t>(durationSeconds * 1000.0);
}

std::string boolDataJson(const std::string& key, bool value) {
    return "{\"" + key + "\":" + std::string(value ? "true" : "false") + "}";
}

class SeekCommand final : public EditorCommand {
public:
    explicit SeekCommand(int64_t timeMs) : EditorCommand("SEEK"), m_timeMs(timeMs) {}

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        (*context.currentTimeMs).store(m_timeMs);
        g_pendingScrubMs.store(static_cast<long long>(m_timeMs), std::memory_order_release);
        return CommandResult::ok(action(), "Seek complete", "{\"timeMs\":" + std::to_string(m_timeMs) + "}");
    }

private:
    int64_t m_timeMs;
};

class LoadVideoCommand final : public EditorCommand {
public:
    explicit LoadVideoCommand(std::string path)
        : EditorCommand("LOAD_VIDEO"), m_path(std::move(path)) {}

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        if (!(*context.preview)->open(m_path)) {
            return CommandResult::fail(action(), (*context.preview)->getLastError());
        }
        applyPreviewPolicyLocked(context.preview);
        if (g_nativeWindow) {
            releaseOuterEglForPreviewAttachLocked();
            if (!(*context.preview)->attachSurface(g_nativeWindow)) {
                return CommandResult::fail(action(), (*context.preview)->getLastError());
            }
        }
        return CommandResult::ok(
            action(),
            "Video loaded",
            "{\"loaded\":true,\"durationMs\":" + std::to_string((*context.preview)->getVideoDurationMs()) + "}");
    }

private:
    std::string m_path;
};

class AddClipCommand final : public EditorCommand {
public:
    AddClipCommand(
        std::string path,
        VideoEngine::Clip::TrackRole trackRole,
        int trackLane,
        int trackZOrder,
        int64_t requestedStartTimeMs)
        : EditorCommand("ADD_CLIP"),
          m_path(std::move(path)),
          m_trackRole(trackRole),
          m_trackLane(std::max(0, trackLane)),
          m_trackZOrder(trackZOrder),
          m_requestedStartTimeMs(requestedStartTimeMs) {}

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        if (!(*context.preview)->isReady()) {
            if (!(*context.preview)->open(m_path)) {
                return CommandResult::fail(action(), (*context.preview)->getLastError());
            }
            applyPreviewPolicyLocked(context.preview);
            if (g_nativeWindow) {
                // Release outer EGL first so PreviewController can own the window surface
                releaseOuterEglForPreviewAttachLocked();
                // Re-attach surface — renderer may have been invalidated by EGL release
                (*context.preview)->attachSurface(g_nativeWindow);
                // Seek to current time to render first frame
                g_pendingScrubMs.store(
                    static_cast<long long>(context.currentTimeMs ? context.currentTimeMs->load() : 0),
                    std::memory_order_release);
            }
        }
        int64_t durationMs = probeDurationMsForPath(m_path);
        if (durationMs <= 0) {
            durationMs = (*context.preview)->getVideoDurationMs();
        }
        durationMs = std::max<int64_t>(1, durationMs);
        int64_t startTimeMs = std::max<int64_t>(0, m_requestedStartTimeMs);
        if (m_requestedStartTimeMs < 0) {
            startTimeMs = primaryTrackDuration(timeline.get());
            if (m_trackRole != VideoEngine::Clip::TrackRole::MainVideo) {
                startTimeMs = context.currentTimeMs ? std::max<int64_t>(0, context.currentTimeMs->load()) : 0;
            }
        }
        auto clip = std::make_shared<VideoEngine::Clip>(m_path, startTimeMs, durationMs);
        clip->setTrackRole(m_trackRole);
        clip->setTrackLane(m_trackLane);
        clip->setTrackZOrder(m_trackZOrder);
        timeline->addClip(clip);
        if (m_trackRole == VideoEngine::Clip::TrackRole::MainVideo) {
            normalizePrimaryTrack(timeline.get());
            // Always render first frame after adding a video clip (fixes black preview)
            if (g_nativeWindow && (*context.preview)->isReady()) {
                const int64_t seekMs = context.currentTimeMs ? context.currentTimeMs->load() : 0;
                g_pendingScrubMs.store(static_cast<long long>(seekMs), std::memory_order_release);
            }
        }
        scheduleClipWarmupAsync(clip->getId(), m_path, m_trackRole);
        return CommandResult::ok(
            action(),
            "Clip added",
            "{\"clipId\":" + std::to_string(clip->getId()) +
            ",\"durationMs\":" + std::to_string(clip->getDuration()) +
            ",\"startTimeMs\":" + std::to_string(clip->getStartTime()) +
            ",\"trackType\":" + quote(trackRoleToString(clip->getTrackRole())) +
            ",\"lane\":" + std::to_string(clip->getTrackLane()) +
            ",\"zOrder\":" + std::to_string(clip->getTrackZOrder()) +
            ",\"proxyWarmupScheduled\":" + std::string(
                (m_trackRole == VideoEngine::Clip::TrackRole::MainVideo ||
                    m_trackRole == VideoEngine::Clip::TrackRole::Overlay)
                    ? "true"
                    : "false") +
            "}");
    }

private:
    std::string m_path;
    VideoEngine::Clip::TrackRole m_trackRole;
    int m_trackLane;
    int m_trackZOrder;
    int64_t m_requestedStartTimeMs;
};

class PlayCommand final : public EditorCommand {
public:
    explicit PlayCommand(int64_t timeMs) : EditorCommand("PLAY"), m_timeMs(timeMs) {}

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        (*context.currentTimeMs).store(m_timeMs);
        if (!(*context.preview)->playFrom(m_timeMs)) {
            const std::string firstError = (*context.preview)->getLastError();
            const bool componentsMissing =
                firstError.rfind("Components not initialized", 0) == 0;
            if (g_nativeWindow && componentsMissing) {
                releaseOuterEglForPreviewAttachLocked();
                if ((*context.preview)->attachSurface(g_nativeWindow) &&
                    (*context.preview)->playFrom(m_timeMs)) {
                    (*context.renderingActive).store(true);
                    return CommandResult::ok(
                        action(),
                        "Playback started",
                        "{\"timeMs\":" + std::to_string(m_timeMs) + "}");
                }
            }
            return CommandResult::fail(action(), (*context.preview)->getLastError());
        }
        (*context.renderingActive).store(true);
        return CommandResult::ok(action(), "Playback started", "{\"timeMs\":" + std::to_string(m_timeMs) + "}");
    }

private:
    int64_t m_timeMs;
};

class PauseCommand final : public EditorCommand {
public:
    PauseCommand() : EditorCommand("PAUSE") {}

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        (*context.preview)->stop();
        (*context.renderingActive).store(false);
        return CommandResult::ok(action(), "Playback paused");
    }
};

class GetDurationCommand final : public EditorCommand {
public:
    GetDurationCommand() : EditorCommand("GET_DURATION") {}

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        return CommandResult::ok(
            action(),
            "Duration queried",
            "{\"durationMs\":" + std::to_string((*context.preview)->getVideoDurationMs()) + "}");
    }
};

class GetPlaybackTimeCommand final : public EditorCommand {
public:
    GetPlaybackTimeCommand() : EditorCommand("GET_CURRENT_PLAYBACK_TIME") {}

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        return CommandResult::ok(
            action(),
            "Playback time queried",
            "{\"timeMs\":" + std::to_string((*context.preview)->getCurrentTimeMs()) + "}");
    }
};

class SetTimelineZoomCommand final : public EditorCommand {
public:
    explicit SetTimelineZoomCommand(double pxPerSecond)
        : EditorCommand("SET_TIMELINE_ZOOM"),
          m_pxPerSecond(std::clamp(pxPerSecond, 48.0, 3200.0)) {}

    CommandResult execute(CommandContext& context) override {
        if (!context.timelineZoomMilliPxPerSecond) {
            return CommandResult::fail(action(), "Timeline zoom state not initialized");
        }
        const int milliPxPerSecond = static_cast<int>(m_pxPerSecond * 1000.0);
        context.timelineZoomMilliPxPerSecond->store(milliPxPerSecond);
        return CommandResult::ok(
            action(),
            "Timeline zoom updated",
            "{\"pxPerSecond\":" + std::to_string(m_pxPerSecond) + "}");
    }

private:
    double m_pxPerSecond;
};

class GetTimelineZoomCommand final : public EditorCommand {
public:
    GetTimelineZoomCommand() : EditorCommand("GET_TIMELINE_ZOOM") {}

    CommandResult execute(CommandContext& context) override {
        if (!context.timelineZoomMilliPxPerSecond) {
            return CommandResult::fail(action(), "Timeline zoom state not initialized");
        }
        const double pxPerSecond =
            static_cast<double>(context.timelineZoomMilliPxPerSecond->load()) / 1000.0;
        return CommandResult::ok(
            action(),
            "Timeline zoom queried",
            "{\"pxPerSecond\":" + std::to_string(pxPerSecond) + "}");
    }
};

class GetTimelineLayoutCommand final : public EditorCommand {
public:
    GetTimelineLayoutCommand() : EditorCommand("GET_TIMELINE_LAYOUT") {}

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }

        std::ostringstream clipsJson;
        clipsJson << "[";
        bool first = true;
        for (const auto& clip : timeline->clips()) {
            if (!clip) continue;
            const auto proxyState = snapshotProxyState(clip->getId());
            const std::string originalSourcePath = !proxyState.sourcePath.empty()
                ? proxyState.sourcePath
                : clip->getMediaPath();
            const auto& props = clip->getProperties();
            const auto& effects = clip->getEffects();
            const auto& chroma = clip->getChromaKey();
            const bool effectsEnabled =
                effects.enabled &&
                (std::fabs(effects.brightness) > 0.001f ||
                 std::fabs(effects.contrast - 1.0f) > 0.001f ||
                 std::fabs(effects.saturation - 1.0f) > 0.001f ||
                 effects.lutEnabled);
            VideoEngine::Clip::TimeMs sourceInMs = 0;
            VideoEngine::Clip::TimeMs sourceOutMs = 0;
            clip->getTrimPoints(sourceInMs, sourceOutMs);
            if (sourceOutMs <= sourceInMs) {
                sourceOutMs = sourceInMs + clip->getDuration();
            }

            if (!first) clipsJson << ",";
            first = false;
            clipsJson << "{"
                      << "\"clipId\":" << clip->getId() << ","
                      << "\"startTimeMs\":" << clip->getStartTime() << ","
                      << "\"durationMs\":" << clip->getDuration() << ","
                      << "\"sourceInMs\":" << sourceInMs << ","
                      << "\"sourceOutMs\":" << sourceOutMs << ","
                      << "\"sourcePath\":" << quote(clip->getMediaPath()) << ","
                      << "\"originalSourcePath\":" << quote(originalSourcePath) << ","
                      << "\"mediaType\":" << quote(mediaTypeToString(clip->getMediaType())) << ","
                      << "\"trackType\":" << quote(trackRoleToString(clip->getTrackRole())) << ","
                      << "\"trackLane\":" << clip->getTrackLane() << ","
                      << "\"zOrder\":" << clip->getTrackZOrder() << ","
                      << "\"enabled\":" << (props.enabled ? "true" : "false") << ","
                      << "\"opacity\":" << props.opacity << ","
                      << "\"volumeGain\":" << props.volumeGain << ","
                      << "\"duckingEnabled\":" << (props.duckingEnabled ? "true" : "false") << ","
                      << "\"duckingAmount\":" << props.duckingAmount << ","
                      << "\"playbackSpeed\":" << props.playbackSpeed << ","
                      << "\"reversePlayback\":" << (props.reversePlayback ? "true" : "false") << ","
                      << "\"freezeFrameEnabled\":" << (props.freezeFrameEnabled ? "true" : "false") << ","
                      << "\"freezeFrameTimeMs\":" << props.freezeFrameTimeMs << ","
                      << "\"freezeFrameDurationMs\":" << props.freezeFrameDurationMs << ","
                      << "\"curveSpeedProfile\":" << quote(props.curveSpeedProfile) << ","
                      << "\"curveSpeedStrength\":" << props.curveSpeedStrength << ","
                      << "\"effectsEnabled\":" << (effectsEnabled ? "true" : "false") << ","
                      << "\"brightness\":" << effects.brightness << ","
                      << "\"contrast\":" << effects.contrast << ","
                      << "\"saturation\":" << effects.saturation << ","
                      << "\"lutEnabled\":" << (effects.lutEnabled ? "true" : "false") << ","
                      << "\"chromaEnabled\":" << (chroma.enabled ? "true" : "false") << ","
                      << "\"chromaColor\":" << (chroma.color == VideoEngine::Clip::ChromaKeyParams::KeyColor::Blue ? 1 : 0) << ","
                      << "\"chromaSimilarity\":" << chroma.similarity << ","
                      << "\"chromaSmoothness\":" << chroma.smoothness << ","
                      << "\"chromaSpill\":" << chroma.spill
                      << "}";
        }
        clipsJson << "]";

        const std::string dataJson =
            "{\"durationMs\":" + std::to_string(timeline->getDuration()) +
            ",\"clips\":" + clipsJson.str() + "}";
        return CommandResult::ok(action(), "Timeline layout fetched", dataJson);
    }
};

class SetClipTrackCommand final : public EditorCommand {
public:
    SetClipTrackCommand(int clipId, VideoEngine::Clip::TrackRole role, int lane, int zOrder)
        : EditorCommand("SET_CLIP_TRACK"),
          m_clipId(clipId),
          m_trackRole(role),
          m_trackLane(std::max(0, lane)),
          m_trackZOrder(zOrder) {}

    bool canUndo() const override { return true; }

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail(action(), "Clip not found");
        }

        m_oldTrackRole = clip->getTrackRole();
        m_oldTrackLane = clip->getTrackLane();
        m_oldTrackZOrder = clip->getTrackZOrder();
        clip->setTrackRole(m_trackRole);
        clip->setTrackLane(m_trackLane);
        clip->setTrackZOrder(m_trackZOrder);
        if (m_oldTrackRole == VideoEngine::Clip::TrackRole::MainVideo ||
            m_trackRole == VideoEngine::Clip::TrackRole::MainVideo) {
            normalizePrimaryTrack(timeline.get());
        }

        return CommandResult::ok(
            action(),
            "Clip track updated",
            "{\"clipId\":" + std::to_string(m_clipId) +
            ",\"trackType\":" + quote(trackRoleToString(m_trackRole)) +
            ",\"trackLane\":" + std::to_string(m_trackLane) +
            ",\"zOrder\":" + std::to_string(m_trackZOrder) + "}");
    }

    CommandResult undo(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail("UNDO", "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail("UNDO", "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail("UNDO", "Clip not found");
        }
        clip->setTrackRole(m_oldTrackRole);
        clip->setTrackLane(m_oldTrackLane);
        clip->setTrackZOrder(m_oldTrackZOrder);
        if (m_oldTrackRole == VideoEngine::Clip::TrackRole::MainVideo ||
            m_trackRole == VideoEngine::Clip::TrackRole::MainVideo) {
            normalizePrimaryTrack(timeline.get());
        }
        return CommandResult::ok("UNDO", "Clip track reverted", "{\"clipId\":" + std::to_string(m_clipId) + "}");
    }

private:
    int m_clipId;
    VideoEngine::Clip::TrackRole m_trackRole;
    int m_trackLane;
    int m_trackZOrder;
    VideoEngine::Clip::TrackRole m_oldTrackRole = VideoEngine::Clip::TrackRole::MainVideo;
    int m_oldTrackLane = 0;
    int m_oldTrackZOrder = 0;
};

class SetPreviewPolicyCommand final : public EditorCommand {
public:
    SetPreviewPolicyCommand(
        bool ghostEnabled,
        int ghostLongEdgePx,
        bool adaptiveEnabled,
        int targetFps,
        int minFps)
        : EditorCommand("SET_PREVIEW_POLICY"),
          m_ghostEnabled(ghostEnabled),
          m_ghostLongEdgePx(std::max(0, ghostLongEdgePx)),
          m_adaptiveEnabled(adaptiveEnabled),
          m_targetFps(std::max(1, targetFps)),
          m_minFps(std::max(1, minFps)) {}

    CommandResult execute(CommandContext& context) override {
        const int clampedMinFps = std::min(m_minFps, m_targetFps);
        g_ghostPreviewEnabled.store(m_ghostEnabled);
        g_ghostPreviewLongEdgePx.store(m_ghostLongEdgePx);
        g_adaptiveFrameDropEnabled.store(m_adaptiveEnabled);
        g_targetPreviewFps.store(m_targetFps);
        g_minPreviewFps.store(clampedMinFps);

        std::lock_guard<std::mutex> lock(*context.previewMutex);
        applyPreviewPolicyLocked(context.preview);
        return CommandResult::ok(
            action(),
            "Preview policy updated",
            "{\"ghostPreviewEnabled\":" + std::string(m_ghostEnabled ? "true" : "false") +
            ",\"ghostLongEdgePx\":" + std::to_string(m_ghostLongEdgePx) +
            ",\"adaptiveFrameDropEnabled\":" + std::string(m_adaptiveEnabled ? "true" : "false") +
            ",\"targetPreviewFps\":" + std::to_string(m_targetFps) +
            ",\"minPreviewFps\":" + std::to_string(clampedMinFps) + "}");
    }

private:
    bool m_ghostEnabled;
    int m_ghostLongEdgePx;
    bool m_adaptiveEnabled;
    int m_targetFps;
    int m_minFps;
};

class GetPreviewPolicyCommand final : public EditorCommand {
public:
    GetPreviewPolicyCommand() : EditorCommand("GET_PREVIEW_POLICY") {}

    CommandResult execute(CommandContext& /* context */) override {
        const bool ghostEnabled = g_ghostPreviewEnabled.load();
        const int ghostLongEdgePx = g_ghostPreviewLongEdgePx.load();
        const bool adaptiveEnabled = g_adaptiveFrameDropEnabled.load();
        const int targetFps = g_targetPreviewFps.load();
        const int minFps = g_minPreviewFps.load();
        return CommandResult::ok(
            action(),
            "Preview policy queried",
            "{\"ghostPreviewEnabled\":" + std::string(ghostEnabled ? "true" : "false") +
            ",\"ghostLongEdgePx\":" + std::to_string(ghostLongEdgePx) +
            ",\"adaptiveFrameDropEnabled\":" + std::string(adaptiveEnabled ? "true" : "false") +
            ",\"targetPreviewFps\":" + std::to_string(targetFps) +
            ",\"minPreviewFps\":" + std::to_string(minFps) + "}");
    }
};

class SetPerformancePolicyCommand final : public EditorCommand {
public:
    SetPerformancePolicyCommand(
        bool dirtyRegionEnabled,
        bool predictiveEnabled,
        int predictiveLookAroundMs,
        int predictiveSampleStepMs,
        int predictiveCacheMaxFrames)
        : EditorCommand("SET_PERFORMANCE_POLICY"),
          m_dirtyRegionEnabled(dirtyRegionEnabled),
          m_predictiveEnabled(predictiveEnabled),
          m_predictiveLookAroundMs(std::clamp(predictiveLookAroundMs, 200, 8000)),
          m_predictiveSampleStepMs(std::clamp(predictiveSampleStepMs, 16, 1000)),
          m_predictiveCacheMaxFrames(std::clamp(predictiveCacheMaxFrames, 4, 240)) {}

    CommandResult execute(CommandContext& context) override {
        g_dirtyRegionRedrawEnabled.store(m_dirtyRegionEnabled);
        g_predictiveCachingEnabled.store(m_predictiveEnabled);
        g_predictiveLookAroundMs.store(m_predictiveLookAroundMs);
        g_predictiveSampleStepMs.store(m_predictiveSampleStepMs);
        g_predictiveCacheMaxFrames.store(m_predictiveCacheMaxFrames);

        std::lock_guard<std::mutex> lock(*context.previewMutex);
        applyPreviewPolicyLocked(context.preview);
        return CommandResult::ok(
            action(),
            "Performance policy updated",
            "{\"dirtyRegionEnabled\":" + std::string(m_dirtyRegionEnabled ? "true" : "false") +
            ",\"predictiveCachingEnabled\":" + std::string(m_predictiveEnabled ? "true" : "false") +
            ",\"predictiveLookAroundMs\":" + std::to_string(m_predictiveLookAroundMs) +
            ",\"predictiveSampleStepMs\":" + std::to_string(m_predictiveSampleStepMs) +
            ",\"predictiveCacheMaxFrames\":" + std::to_string(m_predictiveCacheMaxFrames) + "}");
    }

private:
    bool m_dirtyRegionEnabled;
    bool m_predictiveEnabled;
    int m_predictiveLookAroundMs;
    int m_predictiveSampleStepMs;
    int m_predictiveCacheMaxFrames;
};

class GetPerformancePolicyCommand final : public EditorCommand {
public:
    GetPerformancePolicyCommand() : EditorCommand("GET_PERFORMANCE_POLICY") {}

    CommandResult execute(CommandContext&) override {
        return CommandResult::ok(
            action(),
            "Performance policy queried",
            "{\"dirtyRegionEnabled\":" + std::string(g_dirtyRegionRedrawEnabled.load() ? "true" : "false") +
            ",\"predictiveCachingEnabled\":" + std::string(g_predictiveCachingEnabled.load() ? "true" : "false") +
            ",\"predictiveLookAroundMs\":" + std::to_string(g_predictiveLookAroundMs.load()) +
            ",\"predictiveSampleStepMs\":" + std::to_string(g_predictiveSampleStepMs.load()) +
            ",\"predictiveCacheMaxFrames\":" + std::to_string(g_predictiveCacheMaxFrames.load()) + "}");
    }
};

class SetAudioMasterClockEnabledCommand final : public EditorCommand {
public:
    explicit SetAudioMasterClockEnabledCommand(bool enabled)
        : EditorCommand("SET_AUDIO_MASTER_CLOCK_ENABLED"),
          m_enabled(enabled) {}

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        (*context.preview)->setAudioMasterClockEnabled(m_enabled);
        return CommandResult::ok(
            action(),
            "Audio master clock mode updated",
            "{\"enabled\":" + std::string(m_enabled ? "true" : "false") + "}");
    }

private:
    bool m_enabled = false;
};

class UpdateAudioClockUsCommand final : public EditorCommand {
public:
    explicit UpdateAudioClockUsCommand(int64_t ptsUs)
        : EditorCommand("UPDATE_AUDIO_CLOCK_US"),
          m_ptsUs(std::max<int64_t>(0, ptsUs)) {}

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        (*context.preview)->updateAudioMasterClockUs(m_ptsUs);
        return CommandResult::ok(
            action(),
            "Audio master clock updated",
            "{\"ptsUs\":" + std::to_string(m_ptsUs) + "}");
    }

private:
    int64_t m_ptsUs = 0;
};

class BuildAudioPeakMapCommand final : public EditorCommand {
public:
    BuildAudioPeakMapCommand(
        std::string sourcePath,
        std::string outputPath,
        int bucketMs)
        : EditorCommand("BUILD_AUDIO_PEAK_MAP"),
          m_sourcePath(std::move(sourcePath)),
          m_outputPath(std::move(outputPath)),
          m_bucketMs(std::clamp(bucketMs, 1, 2000)) {}

    CommandResult execute(CommandContext&) override {
        if (m_sourcePath.empty()) {
            return CommandResult::fail(action(), "Missing source path");
        }
        std::string outputPath = m_outputPath;
        if (outputPath.empty()) {
            outputPath = m_sourcePath + ".peaks";
        }
        int peakCount = 0;
        int64_t durationMs = 0;
        int sampleRate = 0;
        std::string error;
        const bool ok = buildAudioPeakMap(
            m_sourcePath,
            outputPath,
            m_bucketMs,
            [](int /* progress */) {},
            &peakCount,
            &durationMs,
            &sampleRate,
            error);
        if (!ok) {
            return CommandResult::fail(action(), error.empty() ? "Audio peak map build failed" : error);
        }
        return CommandResult::ok(
            action(),
            "Audio peak map built",
            "{\"sourcePath\":" + quote(m_sourcePath) +
            ",\"peakMapPath\":" + quote(outputPath) +
            ",\"bucketMs\":" + std::to_string(m_bucketMs) +
            ",\"sampleRate\":" + std::to_string(sampleRate) +
            ",\"peakCount\":" + std::to_string(peakCount) +
            ",\"durationMs\":" + std::to_string(durationMs) + "}");
    }

private:
    std::string m_sourcePath;
    std::string m_outputPath;
    int m_bucketMs;
};

class GetAudioPeakRangeCommand final : public EditorCommand {
public:
    GetAudioPeakRangeCommand(
        std::string peakMapPath,
        int startIndex,
        int maxPoints)
        : EditorCommand("GET_AUDIO_PEAK_RANGE"),
          m_peakMapPath(std::move(peakMapPath)),
          m_startIndex(std::max(0, startIndex)),
          m_maxPoints(std::clamp(maxPoints, 1, 4096)) {}

    CommandResult execute(CommandContext&) override {
        if (m_peakMapPath.empty()) {
            return CommandResult::fail(action(), "Missing peak map path");
        }

        PeakMapFileHeader header{};
        std::vector<uint8_t> peaks;
        std::string error;
        if (!readAudioPeakMapFile(m_peakMapPath, &header, &peaks, error)) {
            return CommandResult::fail(
                action(),
                error.empty() ? "Failed to read audio peak map" : error);
        }

        const int total = static_cast<int>(peaks.size());
        const int safeStart = std::clamp(m_startIndex, 0, std::max(0, total));
        const int safeEnd = std::min(total, safeStart + m_maxPoints);

        std::ostringstream peaksJson;
        peaksJson << "[";
        for (int i = safeStart; i < safeEnd; ++i) {
            if (i > safeStart) peaksJson << ",";
            peaksJson << static_cast<int>(peaks[i]);
        }
        peaksJson << "]";

        return CommandResult::ok(
            action(),
            "Audio peak range read",
            "{\"peakMapPath\":" + quote(m_peakMapPath) +
            ",\"bucketMs\":" + std::to_string(header.bucketMs) +
            ",\"sampleRate\":" + std::to_string(header.sampleRate) +
            ",\"peakCount\":" + std::to_string(header.peakCount) +
            ",\"durationMs\":" + std::to_string(header.durationMs) +
            ",\"startIndex\":" + std::to_string(safeStart) +
            ",\"returnedCount\":" + std::to_string(safeEnd - safeStart) +
            ",\"peaks\":" + peaksJson.str() + "}");
    }

private:
    std::string m_peakMapPath;
    int m_startIndex;
    int m_maxPoints;
};

class BuildClipProxyCommand final : public EditorCommand {
public:
    BuildClipProxyCommand(
        int clipId,
        std::string sourcePath,
        std::string outputPath,
        int maxLongEdgePx,
        int targetFps)
        : EditorCommand("BUILD_CLIP_PROXY"),
          m_clipId(clipId),
          m_sourcePath(std::move(sourcePath)),
          m_outputPath(std::move(outputPath)),
          m_maxLongEdgePx(std::max(240, maxLongEdgePx)),
          m_targetFps(std::max(10, targetFps)) {}

    CommandResult execute(CommandContext& context) override {
        std::string sourcePath = m_sourcePath;
        if (sourcePath.empty()) {
            std::lock_guard<std::mutex> lock(*context.previewMutex);
            if (!context.preview || !(*context.preview)) {
                return CommandResult::fail(action(), "Preview not initialized");
            }
            auto timeline = (*context.preview)->getTimeline();
            auto clip = findClipShared(timeline.get(), m_clipId);
            if (clip) {
                sourcePath = clip->getMediaPath();
            }
        }
        if (sourcePath.empty()) {
            return CommandResult::fail(action(), "Missing source path");
        }

        std::string outputPath = m_outputPath;
        if (outputPath.empty()) {
            outputPath = sourcePath + ".proxy_" + std::to_string(m_maxLongEdgePx) + ".mp4";
        }

        const auto existing = snapshotProxyState(m_clipId);
        if (existing.building && existing.proxyPath == outputPath) {
            return CommandResult::ok(
                action(),
                "Proxy build already running",
                "{\"clipId\":" + std::to_string(m_clipId) +
                ",\"sourcePath\":" + quote(sourcePath) +
                ",\"proxyPath\":" + quote(outputPath) + "}");
        }

        updateProxyState(m_clipId, [&](ClipProxyState& state) {
            state.building = true;
            state.ready = false;
            state.failed = false;
            state.progress = 0;
            state.sourcePath = sourcePath;
            state.proxyPath = outputPath;
            state.message = "Building proxy";
        });

        const int clipId = m_clipId;
        const int maxLongEdgePx = m_maxLongEdgePx;
        const int targetFps = m_targetFps;
        std::thread([clipId, sourcePath, outputPath, maxLongEdgePx, targetFps]() {
            std::string error;
            const bool ok = buildProxyVideo(
                sourcePath,
                outputPath,
                maxLongEdgePx,
                targetFps,
                [clipId](int progress) {
                    updateProxyState(clipId, [&](ClipProxyState& state) {
                        state.progress = std::clamp(progress, 0, 100);
                    });
                },
                error);
            updateProxyState(clipId, [&](ClipProxyState& state) {
                state.building = false;
                if (ok) {
                    state.ready = true;
                    state.failed = false;
                    state.progress = 100;
                    state.proxyPath = outputPath;
                    state.message = "Proxy ready";
                    return;
                }

                // Graceful fallback: keep preview pipeline alive on the source clip.
                // Preview decoder still honors ghost-scale limits even without a remuxed proxy.
                state.ready = true;
                state.failed = false;
                state.progress = 100;
                state.proxyPath = sourcePath;
                state.message = error.empty()
                    ? "Proxy fallback to source"
                    : ("Proxy fallback to source: " + error);
            });
        }).detach();

        return CommandResult::ok(
            action(),
            "Proxy build started",
            "{\"clipId\":" + std::to_string(m_clipId) +
            ",\"sourcePath\":" + quote(sourcePath) +
            ",\"proxyPath\":" + quote(outputPath) +
            ",\"maxLongEdgePx\":" + std::to_string(m_maxLongEdgePx) +
            ",\"targetFps\":" + std::to_string(m_targetFps) + "}");
    }

private:
    int m_clipId;
    std::string m_sourcePath;
    std::string m_outputPath;
    int m_maxLongEdgePx;
    int m_targetFps;
};

class GetClipProxyStatusCommand final : public EditorCommand {
public:
    explicit GetClipProxyStatusCommand(int clipId)
        : EditorCommand("GET_CLIP_PROXY_STATUS"),
          m_clipId(clipId) {}

    CommandResult execute(CommandContext& /* context */) override {
        const auto state = snapshotProxyState(m_clipId);
        const bool known = !state.sourcePath.empty() || !state.proxyPath.empty();
        return CommandResult::ok(
            action(),
            known ? "Proxy status queried" : "Proxy status unavailable",
            "{\"clipId\":" + std::to_string(m_clipId) +
            ",\"known\":" + std::string(known ? "true" : "false") +
            ",\"building\":" + std::string(state.building ? "true" : "false") +
            ",\"ready\":" + std::string(state.ready ? "true" : "false") +
            ",\"failed\":" + std::string(state.failed ? "true" : "false") +
            ",\"progress\":" + std::to_string(state.progress) +
            ",\"sourcePath\":" + quote(state.sourcePath) +
            ",\"proxyPath\":" + quote(state.proxyPath) +
            ",\"message\":" + quote(state.message) + "}");
    }

private:
    int m_clipId;
};

class ActivateClipProxyCommand final : public EditorCommand {
public:
    explicit ActivateClipProxyCommand(int clipId)
        : EditorCommand("ACTIVATE_CLIP_PROXY"),
          m_clipId(clipId) {}

    CommandResult execute(CommandContext& context) override {
        const auto state = snapshotProxyState(m_clipId);
        if (!state.ready || state.proxyPath.empty()) {
            return CommandResult::fail(action(), "Proxy is not ready");
        }
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        const int64_t currentTimeMs = context.currentTimeMs ? context.currentTimeMs->load() : 0;
        if (!(*context.preview)->open(state.proxyPath)) {
            return CommandResult::fail(action(), (*context.preview)->getLastError());
        }
        applyPreviewPolicyLocked(context.preview);
        g_pendingScrubMs.store(static_cast<long long>(currentTimeMs), std::memory_order_release);
        return CommandResult::ok(
            action(),
            "Proxy activated for preview",
            "{\"clipId\":" + std::to_string(m_clipId) +
            ",\"proxyPath\":" + quote(state.proxyPath) + "}");
    }

private:
    int m_clipId;
};

class DeleteClipCommand final : public EditorCommand {
public:
    explicit DeleteClipCommand(int clipId) : EditorCommand("DELETE"), m_clipId(clipId) {}

    bool canUndo() const override { return true; }

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        auto clip = findClipShared(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail(action(), "Clip not found");
        }
        m_backup = clip;
        timeline->removeClip(std::to_string(m_clipId));
        normalizePrimaryTrack(timeline.get());
        return CommandResult::ok(action(), "Clip deleted", "{\"clipId\":" + std::to_string(m_clipId) +
            ",\"timelineEmpty\":" + (timeline->clips().empty() ? "true" : "false") + "}");
    }

    CommandResult undo(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview) || !m_backup) {
            return CommandResult::fail(action(), "Undo state missing");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        timeline->addClip(m_backup);
        normalizePrimaryTrack(timeline.get());
        return CommandResult::ok("UNDO", "Clip restored", "{\"clipId\":" + std::to_string(m_clipId) + "}");
    }

private:
    int m_clipId;
    std::shared_ptr<VideoEngine::Clip> m_backup;
};

class SplitClipCommand final : public EditorCommand {
public:
    SplitClipCommand(int clipId, int64_t timeMs)
        : EditorCommand("SPLIT"), m_clipId(clipId), m_timeMs(timeMs) {}

    bool canUndo() const override { return true; }

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }

        auto originalClip = findClipShared(timeline.get(), m_clipId);
        if (!originalClip) {
            return CommandResult::fail(action(), "Clip not found");
        }

        const int64_t clipStart = originalClip->getStartTime();
        const int64_t clipDuration = originalClip->getDuration();
        const int64_t clipEnd = clipStart + clipDuration;
        // Clamp to valid range with 1ms margin to handle timing drift
        m_timeMs = std::max(clipStart + 1, std::min(m_timeMs, clipEnd - 1));
        if (m_timeMs <= clipStart || m_timeMs >= clipEnd) {
            return CommandResult::fail(action(), "Split time outside clip range");
        }

        VideoEngine::Clip::TimeMs sourceInMs = 0;
        VideoEngine::Clip::TimeMs sourceOutMs = 0;
        originalClip->getTrimPoints(sourceInMs, sourceOutMs);
        if (sourceOutMs <= sourceInMs) {
            sourceOutMs = sourceInMs + clipDuration;
        }

        const int64_t leftDuration = m_timeMs - clipStart;
        const int64_t rightDuration = clipEnd - m_timeMs;
        const int64_t splitSourceMs = sourceInMs + leftDuration;

        m_originalClip = originalClip;
        m_leftClip = cloneClipRange(
            *originalClip,
            clipStart,
            leftDuration,
            sourceInMs,
            splitSourceMs);
        m_rightClip = cloneClipRange(
            *originalClip,
            m_timeMs,
            rightDuration,
            splitSourceMs,
            sourceOutMs);

        timeline->removeClip(std::to_string(m_clipId));
        timeline->addClip(m_leftClip);
        timeline->addClip(m_rightClip);
        normalizePrimaryTrack(timeline.get());

        return CommandResult::ok(
            action(),
            "Clip split",
            "{\"originalClipId\":" + std::to_string(m_clipId) +
            ",\"leftClipId\":" + std::to_string(m_leftClip->getId()) +
            ",\"rightClipId\":" + std::to_string(m_rightClip->getId()) +
            ",\"timeMs\":" + std::to_string(m_timeMs) + "}");
    }

    CommandResult undo(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail("UNDO", "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline || !m_originalClip || !m_leftClip || !m_rightClip) {
            return CommandResult::fail("UNDO", "Split undo state missing");
        }
        timeline->removeClip(std::to_string(m_leftClip->getId()));
        timeline->removeClip(std::to_string(m_rightClip->getId()));
        timeline->addClip(m_originalClip);
        normalizePrimaryTrack(timeline.get());
        return CommandResult::ok(
            "UNDO",
            "Clip split reverted",
            "{\"clipId\":" + std::to_string(m_originalClip->getId()) + "}");
    }

private:
    int m_clipId;
    int64_t m_timeMs;
    std::shared_ptr<VideoEngine::Clip> m_originalClip;
    std::shared_ptr<VideoEngine::Clip> m_leftClip;
    std::shared_ptr<VideoEngine::Clip> m_rightClip;
};

class TrimClipCommand final : public EditorCommand {
public:
    TrimClipCommand(int clipId, int64_t timeMs, std::string edge)
        : EditorCommand("TRIM_CLIP"),
          m_clipId(clipId),
          m_timeMs(timeMs),
          m_edge(std::move(edge)) {}

    bool canUndo() const override { return true; }

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail(action(), "Clip not found");
        }

        m_oldStartTimeMs = clip->getStartTime();
        m_oldDurationMs = clip->getDuration();
        clip->getTrimPoints(m_oldSourceInMs, m_oldSourceOutMs);
        if (m_oldSourceOutMs <= m_oldSourceInMs) {
            m_oldSourceOutMs = m_oldSourceInMs + m_oldDurationMs;
        }

        const int64_t clipStart = m_oldStartTimeMs;
        const int64_t clipEnd = clipStart + m_oldDurationMs;
        if (m_timeMs <= clipStart || m_timeMs >= clipEnd) {
            return CommandResult::fail(action(), "Trim time outside clip range");
        }

        if (m_edge == "start") {
            const int64_t deltaMs = m_timeMs - clipStart;
            const int64_t newDurationMs = clipEnd - m_timeMs;
            clip->setTimelinePosition(m_timeMs, newDurationMs);
            clip->setTrimPoints(m_oldSourceInMs + deltaMs, m_oldSourceOutMs);
        } else if (m_edge == "end") {
            const int64_t removedMs = clipEnd - m_timeMs;
            const int64_t newDurationMs = m_timeMs - clipStart;
            clip->setTimelinePosition(clipStart, newDurationMs);
            clip->setTrimPoints(m_oldSourceInMs, m_oldSourceOutMs - removedMs);
        } else {
            return CommandResult::fail(action(), "Unsupported trim edge");
        }
        normalizePrimaryTrack(timeline.get());

        return CommandResult::ok(
            action(),
            "Clip trimmed",
            "{\"clipId\":" + std::to_string(m_clipId) +
            ",\"timeMs\":" + std::to_string(m_timeMs) +
            ",\"edge\":" + quote(m_edge) + "}");
    }

    CommandResult undo(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail("UNDO", "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail("UNDO", "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail("UNDO", "Clip not found");
        }
        clip->setTimelinePosition(m_oldStartTimeMs, m_oldDurationMs);
        clip->setTrimPoints(m_oldSourceInMs, m_oldSourceOutMs);
        normalizePrimaryTrack(timeline.get());
        return CommandResult::ok(
            "UNDO",
            "Clip trim reverted",
            "{\"clipId\":" + std::to_string(m_clipId) + "}");
    }

private:
    int m_clipId;
    int64_t m_timeMs;
    std::string m_edge;
    int64_t m_oldStartTimeMs = 0;
    int64_t m_oldDurationMs = 0;
    int64_t m_oldSourceInMs = 0;
    int64_t m_oldSourceOutMs = 0;
};

class MoveClipCommand final : public EditorCommand {
public:
    MoveClipCommand(int clipId, int64_t newTimeMs)
        : EditorCommand("MOVE_CLIP"), m_clipId(clipId), m_newTimeMs(newTimeMs) {}

    bool canUndo() const override { return true; }

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail(action(), "Clip not found");
        }
        m_oldStartTimeMs = clip->getStartTime();
        clip->setTimelinePosition(m_newTimeMs, clip->getDuration());
        normalizePrimaryTrack(timeline.get());
        return CommandResult::ok(action(), "Clip moved",
                                 "{\"clipId\":" + std::to_string(m_clipId) +
                                 ",\"newTimeMs\":" + std::to_string(m_newTimeMs) + "}");
    }

    CommandResult undo(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail(action(), "Clip not found");
        }
        clip->setTimelinePosition(m_oldStartTimeMs, clip->getDuration());
        normalizePrimaryTrack(timeline.get());
        return CommandResult::ok("UNDO", "Clip move reverted",
                                 "{\"clipId\":" + std::to_string(m_clipId) +
                                 ",\"timeMs\":" + std::to_string(m_oldStartTimeMs) + "}");
    }

private:
    int m_clipId;
    int64_t m_newTimeMs;
    int64_t m_oldStartTimeMs = 0;
};

class UpdateClipTimingCommand final : public EditorCommand {
public:
    UpdateClipTimingCommand(
        int clipId,
        int64_t newStartTimeMs,
        int64_t newDurationMs,
        int64_t newSourceInMs,
        int64_t newSourceOutMs,
        int64_t originalStartTimeMs,
        int64_t originalDurationMs,
        int64_t originalSourceInMs,
        int64_t originalSourceOutMs,
        bool previewOnly,
        bool applyMagnetic)
        : EditorCommand("UPDATE_CLIP_TIMING"),
          m_clipId(clipId),
          m_newStartTimeMs(newStartTimeMs),
          m_newDurationMs(std::max<int64_t>(1, newDurationMs)),
          m_newSourceInMs(newSourceInMs),
          m_newSourceOutMs(std::max(newSourceInMs + 1, newSourceOutMs)),
          m_originalStartTimeMs(originalStartTimeMs),
          m_originalDurationMs(std::max<int64_t>(1, originalDurationMs)),
          m_originalSourceInMs(originalSourceInMs),
          m_originalSourceOutMs(std::max(originalSourceInMs + 1, originalSourceOutMs)),
          m_previewOnly(previewOnly),
          m_applyMagnetic(applyMagnetic) {}

    bool canUndo() const override { return !m_previewOnly; }

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail(action(), "Clip not found");
        }

        clip->setTimelinePosition(std::max<int64_t>(0, m_newStartTimeMs), m_newDurationMs);
        clip->setTrimPoints(std::max<int64_t>(0, m_newSourceInMs), m_newSourceOutMs);
        const bool shouldApplyMagnetic =
            !m_previewOnly &&
            (m_applyMagnetic || clip->getTrackRole() == VideoEngine::Clip::TrackRole::MainVideo);
        if (shouldApplyMagnetic) {
            normalizePrimaryTrack(timeline.get());
        }
        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);
        return CommandResult::ok(
            action(),
            m_previewOnly ? "Clip timing preview updated" : "Clip timing updated",
            "{\"clipId\":" + std::to_string(m_clipId) +
            ",\"startTimeMs\":" + std::to_string(m_newStartTimeMs) +
            ",\"durationMs\":" + std::to_string(m_newDurationMs) + "}");
    }

    CommandResult undo(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail("UNDO", "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail("UNDO", "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail("UNDO", "Clip not found");
        }

        clip->setTimelinePosition(std::max<int64_t>(0, m_originalStartTimeMs), m_originalDurationMs);
        clip->setTrimPoints(std::max<int64_t>(0, m_originalSourceInMs), m_originalSourceOutMs);
        const bool shouldApplyMagnetic =
            m_applyMagnetic || clip->getTrackRole() == VideoEngine::Clip::TrackRole::MainVideo;
        if (shouldApplyMagnetic) {
            normalizePrimaryTrack(timeline.get());
        }
        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);
        return CommandResult::ok(
            "UNDO",
            "Clip timing reverted",
            "{\"clipId\":" + std::to_string(m_clipId) + "}");
    }

private:
    int m_clipId;
    int64_t m_newStartTimeMs;
    int64_t m_newDurationMs;
    int64_t m_newSourceInMs;
    int64_t m_newSourceOutMs;
    int64_t m_originalStartTimeMs;
    int64_t m_originalDurationMs;
    int64_t m_originalSourceInMs;
    int64_t m_originalSourceOutMs;
    bool m_previewOnly = false;
    bool m_applyMagnetic = false;
};

class SpeedCommand final : public EditorCommand {
public:
    SpeedCommand(int clipId, double speed)
        : EditorCommand("SPEED"), m_clipId(clipId), m_speed(speed) {}

    bool canUndo() const override { return true; }

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail(action(), "Clip not found");
        }
        m_previousSpeed = clip->getProperties().playbackSpeed;
        clip->setPlaybackSpeed(static_cast<float>(m_speed));
        return CommandResult::ok(action(), "Speed updated",
                                 "{\"clipId\":" + std::to_string(m_clipId) +
                                 ",\"speed\":" + std::to_string(m_speed) + "}");
    }

    CommandResult undo(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail(action(), "Clip not found");
        }
        clip->setPlaybackSpeed(static_cast<float>(m_previousSpeed));
        return CommandResult::ok("UNDO", "Speed reverted",
                                 "{\"clipId\":" + std::to_string(m_clipId) +
                                 ",\"speed\":" + std::to_string(m_previousSpeed) + "}");
    }

private:
    int m_clipId;
    double m_speed;
    double m_previousSpeed = 1.0;
};

class SetClipVolumeCommand final : public EditorCommand {
public:
    SetClipVolumeCommand(int clipId, double volume)
        : EditorCommand("SET_CLIP_VOLUME"),
          m_clipId(clipId),
          m_volume(static_cast<float>(volume)) {}

    bool canUndo() const override { return true; }

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail(action(), "Clip not found");
        }
        m_previousVolume = clip->getProperties().volumeGain;
        clip->setVolumeGain(std::clamp(m_volume, 0.0f, 4.0f));
        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);
        return CommandResult::ok(
            action(),
            "Clip volume updated",
            "{\"clipId\":" + std::to_string(m_clipId) +
                ",\"volume\":" + std::to_string(clip->getProperties().volumeGain) + "}");
    }

    CommandResult undo(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail("UNDO", "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail("UNDO", "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail("UNDO", "Clip not found");
        }
        clip->setVolumeGain(std::clamp(m_previousVolume, 0.0f, 4.0f));
        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);
        return CommandResult::ok(
            "UNDO",
            "Clip volume reverted",
            "{\"clipId\":" + std::to_string(m_clipId) +
                ",\"volume\":" + std::to_string(clip->getProperties().volumeGain) + "}");
    }

private:
    int m_clipId;
    float m_volume = 1.0f;
    float m_previousVolume = 1.0f;
};

class AddKeyframeCommand final : public EditorCommand {
public:
    AddKeyframeCommand(int clipId, int64_t timeMs)
        : EditorCommand("KEYFRAME_ADD"),
          m_clipId(clipId),
          m_timeMs(timeMs) {}

    bool canUndo() const override { return m_added; }

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        auto clip = findClipShared(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail(action(), "Clip not found");
        }

        const int64_t clipStart = clip->getStartTime();
        const int64_t clipEnd = clip->getEndTime();
        const int64_t clampedTime = std::clamp<int64_t>(m_timeMs, clipStart, std::max<int64_t>(clipStart, clipEnd - 1));
        m_timeMs = clampedTime;

        {
            std::lock_guard<std::mutex> keyframeLock(g_keyframeMutex);
            auto& keyframes = g_clipKeyframes[m_clipId];
            auto existing = std::lower_bound(keyframes.begin(), keyframes.end(), m_timeMs);
            if (existing != keyframes.end() && *existing == m_timeMs) {
                m_added = false;
            } else {
                m_insertIndex = static_cast<int>(existing - keyframes.begin());
                keyframes.insert(existing, m_timeMs);
                m_added = true;
            }
        }

        return CommandResult::ok(
            action(),
            m_added ? "Keyframe added" : "Keyframe already exists",
            "{\"clipId\":" + std::to_string(m_clipId) +
                ",\"timeMs\":" + std::to_string(m_timeMs) +
                ",\"added\":" + std::string(m_added ? "true" : "false") + "}");
    }

    CommandResult undo(CommandContext& /* context */) override {
        if (!m_added) {
            return CommandResult::fail("UNDO", "No keyframe inserted");
        }
        std::lock_guard<std::mutex> keyframeLock(g_keyframeMutex);
        auto it = g_clipKeyframes.find(m_clipId);
        if (it == g_clipKeyframes.end()) {
            return CommandResult::fail("UNDO", "Keyframe state missing");
        }
        auto& keyframes = it->second;
        if (m_insertIndex < 0 || m_insertIndex >= static_cast<int>(keyframes.size())) {
            return CommandResult::fail("UNDO", "Undo keyframe index invalid");
        }
        keyframes.erase(keyframes.begin() + m_insertIndex);
        return CommandResult::ok(
            "UNDO",
            "Keyframe removed",
            "{\"clipId\":" + std::to_string(m_clipId) +
                ",\"timeMs\":" + std::to_string(m_timeMs) + "}");
    }

private:
    int m_clipId;
    int64_t m_timeMs;
    bool m_added = false;
    int m_insertIndex = -1;
};

class ReverseClipCommand final : public EditorCommand {
public:
    ReverseClipCommand(int clipId, bool enabled, bool hasExplicitState)
        : EditorCommand("REVERSE_CLIP"),
          m_clipId(clipId),
          m_enabled(enabled),
          m_hasExplicitState(hasExplicitState) {}

    bool canUndo() const override { return true; }

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail(action(), "Clip not found");
        }
        auto& props = clip->getMutableProperties();
        m_previousReverse = props.reversePlayback;
        const bool nextState = m_hasExplicitState ? m_enabled : !props.reversePlayback;
        props.reversePlayback = nextState;
        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);
        return CommandResult::ok(
            action(),
            nextState ? "Reverse enabled" : "Reverse disabled",
            "{\"clipId\":" + std::to_string(m_clipId) +
                ",\"reverse\":" + std::string(nextState ? "true" : "false") + "}");
    }

    CommandResult undo(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail("UNDO", "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail("UNDO", "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail("UNDO", "Clip not found");
        }
        clip->getMutableProperties().reversePlayback = m_previousReverse;
        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);
        return CommandResult::ok(
            "UNDO",
            "Reverse reverted",
            "{\"clipId\":" + std::to_string(m_clipId) +
                ",\"reverse\":" + std::string(m_previousReverse ? "true" : "false") + "}");
    }

private:
    int m_clipId;
    bool m_enabled = false;
    bool m_hasExplicitState = false;
    bool m_previousReverse = false;
};

class FreezeFrameCommand final : public EditorCommand {
public:
    FreezeFrameCommand(int clipId, int64_t timeMs, int64_t durationMs)
        : EditorCommand("FREEZE_FRAME"),
          m_clipId(clipId),
          m_timeMs(timeMs),
          m_durationMs(durationMs) {}

    bool canUndo() const override { return true; }

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail(action(), "Clip not found");
        }
        auto& props = clip->getMutableProperties();
        m_previousEnabled = props.freezeFrameEnabled;
        m_previousTimeMs = props.freezeFrameTimeMs;
        m_previousDurationMs = props.freezeFrameDurationMs;

        const int64_t clipStart = clip->getStartTime();
        const int64_t clipEnd = clip->getEndTime();
        props.freezeFrameEnabled = true;
        props.freezeFrameTimeMs = std::clamp<int64_t>(m_timeMs, clipStart, std::max<int64_t>(clipStart, clipEnd - 1));
        props.freezeFrameDurationMs = std::max<int64_t>(100, m_durationMs);
        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);
        return CommandResult::ok(
            action(),
            "Freeze frame updated",
            "{\"clipId\":" + std::to_string(m_clipId) +
                ",\"timeMs\":" + std::to_string(props.freezeFrameTimeMs) +
                ",\"durationMs\":" + std::to_string(props.freezeFrameDurationMs) + "}");
    }

    CommandResult undo(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail("UNDO", "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail("UNDO", "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail("UNDO", "Clip not found");
        }
        auto& props = clip->getMutableProperties();
        props.freezeFrameEnabled = m_previousEnabled;
        props.freezeFrameTimeMs = m_previousTimeMs;
        props.freezeFrameDurationMs = m_previousDurationMs;
        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);
        return CommandResult::ok(
            "UNDO",
            "Freeze frame reverted",
            "{\"clipId\":" + std::to_string(m_clipId) + "}");
    }

private:
    int m_clipId;
    int64_t m_timeMs;
    int64_t m_durationMs;
    bool m_previousEnabled = false;
    int64_t m_previousTimeMs = 0;
    int64_t m_previousDurationMs = 1000;
};

class AudioDuckingCommand final : public EditorCommand {
public:
    AudioDuckingCommand(int clipId, bool enabled, double amount)
        : EditorCommand("AUDIO_DUCKING"),
          m_clipId(clipId),
          m_enabled(enabled),
          m_amount(static_cast<float>(amount)) {}

    bool canUndo() const override { return true; }

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        auto primary = findClipShared(timeline.get(), m_clipId);
        if (!primary) {
            return CommandResult::fail(action(), "Clip not found");
        }

        const int64_t primaryStart = primary->getStartTime();
        const int64_t primaryEnd = primary->getEndTime();
        const float targetAmount = std::clamp(m_amount, 0.05f, 1.0f);
        m_previousClipStates.clear();

        for (const auto& clip : timeline->clips()) {
            if (!clip) continue;
            const int clipId = static_cast<int>(clip->getId());
            auto& props = clip->getMutableProperties();
            m_previousClipStates.push_back(
                ClipState{clipId, props.volumeGain, props.duckingEnabled, props.duckingAmount});

            if (clipId == m_clipId) {
                props.duckingEnabled = m_enabled;
                props.duckingAmount = targetAmount;
                continue;
            }
            const bool overlaps = clip->getStartTime() < primaryEnd && clip->getEndTime() > primaryStart;
            if (!overlaps) continue;
            if (m_enabled) {
                props.volumeGain = std::clamp(props.volumeGain * targetAmount, 0.0f, 4.0f);
            } else {
                props.volumeGain = std::clamp(1.0f, 0.0f, 4.0f);
            }
        }

        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);
        return CommandResult::ok(
            action(),
            m_enabled ? "Audio ducking enabled" : "Audio ducking disabled",
            "{\"clipId\":" + std::to_string(m_clipId) +
                ",\"enabled\":" + std::string(m_enabled ? "true" : "false") +
                ",\"amount\":" + std::to_string(targetAmount) + "}");
    }

    CommandResult undo(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail("UNDO", "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail("UNDO", "Timeline not initialized");
        }

        for (const auto& state : m_previousClipStates) {
            auto clip = findClipShared(timeline.get(), state.clipId);
            if (!clip) continue;
            auto& props = clip->getMutableProperties();
            props.volumeGain = state.volumeGain;
            props.duckingEnabled = state.duckingEnabled;
            props.duckingAmount = state.duckingAmount;
        }

        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);
        return CommandResult::ok("UNDO", "Audio ducking reverted",
                                 "{\"clipId\":" + std::to_string(m_clipId) + "}");
    }

private:
    struct ClipState {
        int clipId = -1;
        float volumeGain = 1.0f;
        bool duckingEnabled = false;
        float duckingAmount = 0.35f;
    };

    int m_clipId;
    bool m_enabled = false;
    float m_amount = 0.35f;
    std::vector<ClipState> m_previousClipStates;
};

class CurveSpeedCommand final : public EditorCommand {
public:
    CurveSpeedCommand(int clipId, std::string profile, double strength)
        : EditorCommand("CURVE_SPEED"),
          m_clipId(clipId),
          m_profile(std::move(profile)),
          m_strength(static_cast<float>(strength)) {}

    bool canUndo() const override { return true; }

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail(action(), "Clip not found");
        }

        auto& props = clip->getMutableProperties();
        m_previousProfile = props.curveSpeedProfile;
        m_previousStrength = props.curveSpeedStrength;
        m_previousSpeed = props.playbackSpeed;

        props.curveSpeedProfile = m_profile.empty() ? "linear" : m_profile;
        props.curveSpeedStrength = std::clamp(m_strength, 0.1f, 4.0f);

        float effectiveSpeed = props.curveSpeedStrength;
        if (props.curveSpeedProfile == "ease_in") {
            effectiveSpeed = std::max(0.1f, props.curveSpeedStrength * 0.85f);
        } else if (props.curveSpeedProfile == "ease_out") {
            effectiveSpeed = std::max(0.1f, props.curveSpeedStrength * 1.15f);
        } else if (props.curveSpeedProfile == "hyperlapse") {
            effectiveSpeed = std::max(1.0f, props.curveSpeedStrength);
        }
        clip->setPlaybackSpeed(effectiveSpeed);

        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);
        return CommandResult::ok(
            action(),
            "Curve speed updated",
            "{\"clipId\":" + std::to_string(m_clipId) +
                ",\"profile\":" + quote(props.curveSpeedProfile) +
                ",\"strength\":" + std::to_string(props.curveSpeedStrength) +
                ",\"effectiveSpeed\":" + std::to_string(effectiveSpeed) + "}");
    }

    CommandResult undo(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail("UNDO", "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail("UNDO", "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail("UNDO", "Clip not found");
        }
        auto& props = clip->getMutableProperties();
        props.curveSpeedProfile = m_previousProfile;
        props.curveSpeedStrength = m_previousStrength;
        clip->setPlaybackSpeed(m_previousSpeed);
        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);
        return CommandResult::ok(
            "UNDO",
            "Curve speed reverted",
            "{\"clipId\":" + std::to_string(m_clipId) + "}");
    }

private:
    int m_clipId;
    std::string m_profile;
    float m_strength = 1.0f;
    std::string m_previousProfile = "linear";
    float m_previousStrength = 1.0f;
    float m_previousSpeed = 1.0f;
};

class DuplicateClipCommand final : public EditorCommand {
public:
    explicit DuplicateClipCommand(int clipId)
        : EditorCommand("DUPLICATE_CLIP"),
          m_clipId(clipId) {}

    bool canUndo() const override { return true; }

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        auto sourceClip = findClipShared(timeline.get(), m_clipId);
        if (!sourceClip) {
            return CommandResult::fail(action(), "Clip not found");
        }

        m_sourceClip = sourceClip;
        m_createdClip = cloneClip(*sourceClip);
        m_createdClip->setTimelinePosition(sourceClip->getEndTime(), sourceClip->getDuration());
        timeline->addClip(m_createdClip);
        normalizePrimaryTrack(timeline.get());
        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);

        return CommandResult::ok(
            action(),
            "Clip duplicated",
            "{\"sourceClipId\":" + std::to_string(m_clipId) +
                ",\"newClipId\":" + std::to_string(m_createdClip->getId()) + "}");
    }

    CommandResult undo(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail("UNDO", "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline || !m_createdClip) {
            return CommandResult::fail("UNDO", "Duplicate undo state missing");
        }
        timeline->removeClip(std::to_string(m_createdClip->getId()));
        normalizePrimaryTrack(timeline.get());
        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);
        return CommandResult::ok(
            "UNDO",
            "Duplicate reverted",
            "{\"clipId\":" + std::to_string(m_createdClip->getId()) + "}");
    }

private:
    int m_clipId;
    std::shared_ptr<VideoEngine::Clip> m_sourceClip;
    std::shared_ptr<VideoEngine::Clip> m_createdClip;
};

class ReplaceClipSourceCommand final : public EditorCommand {
public:
    ReplaceClipSourceCommand(int clipId, std::string videoPath)
        : EditorCommand("REPLACE_CLIP_SOURCE"),
          m_clipId(clipId),
          m_videoPath(std::move(videoPath)) {}

    bool canUndo() const override { return true; }

    CommandResult execute(CommandContext& context) override {
        if (m_videoPath.empty()) {
            return CommandResult::fail(action(), "Missing replacement path");
        }

        const int64_t sourceDurationMs = probeDurationMsForPath(m_videoPath);
        if (sourceDurationMs <= 0) {
            return CommandResult::fail(action(), "Replacement media duration unavailable");
        }

        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        auto oldClip = findClipShared(timeline.get(), m_clipId);
        if (!oldClip) {
            return CommandResult::fail(action(), "Clip not found");
        }

        const int64_t oldStartMs = oldClip->getStartTime();
        const int64_t oldDurationMs = oldClip->getDuration();
        int64_t oldSourceInMs = 0;
        int64_t oldSourceOutMs = 0;
        oldClip->getTrimPoints(oldSourceInMs, oldSourceOutMs);
        if (oldSourceOutMs <= oldSourceInMs) {
            oldSourceOutMs = oldSourceInMs + oldDurationMs;
        }

        int64_t newDurationMs = std::max<int64_t>(1, std::min<int64_t>(oldDurationMs, sourceDurationMs));
        int64_t newSourceInMs = std::clamp<int64_t>(
            oldSourceInMs,
            0,
            std::max<int64_t>(0, sourceDurationMs - 1));
        int64_t newSourceOutMs = newSourceInMs + newDurationMs;
        if (newSourceOutMs > sourceDurationMs) {
            newSourceOutMs = sourceDurationMs;
            newSourceInMs = std::max<int64_t>(0, newSourceOutMs - newDurationMs);
        }
        if (newSourceOutMs <= newSourceInMs) {
            newSourceInMs = 0;
            newSourceOutMs = std::max<int64_t>(1, sourceDurationMs);
        }
        newDurationMs = std::max<int64_t>(1, newSourceOutMs - newSourceInMs);

        m_oldClip = oldClip;
        m_newClip = std::make_shared<VideoEngine::Clip>(m_videoPath, oldStartMs, newDurationMs);
        m_newClip->setTrimPoints(newSourceInMs, newSourceOutMs);
        m_newClip->getMutableProperties() = oldClip->getProperties();
        m_newClip->getMutableEffects() = oldClip->getEffects();
        m_newClip->getMutableChromaKey() = oldClip->getChromaKey();
        m_newClip->setAudioTrackIndex(oldClip->getAudioTrackIndex());

        timeline->removeClip(std::to_string(m_clipId));
        timeline->addClip(m_newClip);
        normalizePrimaryTrack(timeline.get());
        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);

        return CommandResult::ok(
            action(),
            "Clip replaced",
            "{\"oldClipId\":" + std::to_string(m_clipId) +
                ",\"newClipId\":" + std::to_string(m_newClip->getId()) +
                ",\"durationMs\":" + std::to_string(newDurationMs) + "}");
    }

    CommandResult undo(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail("UNDO", "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline || !m_oldClip || !m_newClip) {
            return CommandResult::fail("UNDO", "Replace undo state missing");
        }

        timeline->removeClip(std::to_string(m_newClip->getId()));
        timeline->addClip(m_oldClip);
        normalizePrimaryTrack(timeline.get());
        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);

        return CommandResult::ok(
            "UNDO",
            "Clip replace reverted",
            "{\"clipId\":" + std::to_string(m_oldClip->getId()) + "}");
    }

private:
    int m_clipId;
    std::string m_videoPath;
    std::shared_ptr<VideoEngine::Clip> m_oldClip;
    std::shared_ptr<VideoEngine::Clip> m_newClip;
};

class ExtractAudioCommand final : public EditorCommand {
public:
    explicit ExtractAudioCommand(int clipId)
        : EditorCommand("EXTRACT_AUDIO"), m_clipId(clipId) {}

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        auto clip = findClipShared(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail(action(), "Clip not found");
        }
        const std::string sourcePath = clip->getMediaPath();
        if (sourcePath.empty()) {
            return CommandResult::fail(action(), "Clip source path unavailable");
        }
        return CommandResult::ok(
            action(),
            "Audio source resolved",
            "{\"clipId\":" + std::to_string(m_clipId) +
                ",\"sourcePath\":" + quote(sourcePath) +
                ",\"durationMs\":" + std::to_string(clip->getDuration()) + "}");
    }

private:
    int m_clipId;
};

class SetClipEffectsCommand final : public EditorCommand {
public:
    SetClipEffectsCommand(int clipId, double brightness, double contrast, double saturation)
        : EditorCommand("SET_CLIP_EFFECTS"),
          m_clipId(clipId),
          m_brightness(static_cast<float>(brightness)),
          m_contrast(static_cast<float>(contrast)),
          m_saturation(static_cast<float>(saturation)) {}

    bool canUndo() const override { return true; }

    CommandResult execute(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            CM_LOGI("[Effects] SET_CLIP_EFFECTS clip=%d NOT FOUND", m_clipId);
            return CommandResult::fail(action(), "Clip not found");
        }
        m_previousBrightness = clip->getEffects().brightness;
        m_previousContrast = clip->getEffects().contrast;
        m_previousSaturation = clip->getEffects().saturation;
        clip->setEffectBrightness(std::clamp(m_brightness, -1.0f, 1.0f));
        clip->setEffectContrast(std::clamp(m_contrast, 0.0f, 2.0f));
        clip->setEffectSaturation(std::clamp(m_saturation, 0.0f, 2.0f));
        clip->setEffectsEnabled(true);
        CM_LOGI("[Effects] SET_CLIP_EFFECTS clip=%d b=%.3f c=%.3f s=%.3f",
            m_clipId, m_brightness, m_contrast, m_saturation);
        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);
        return CommandResult::ok(action(), "Clip effects updated",
                                 "{\"clipId\":" + std::to_string(m_clipId) + "}");
    }

    CommandResult undo(CommandContext& context) override {
        std::lock_guard<std::mutex> lock(*context.previewMutex);
        if (!context.preview || !(*context.preview)) {
            return CommandResult::fail(action(), "Preview not initialized");
        }
        auto timeline = (*context.preview)->getTimeline();
        if (!timeline) {
            return CommandResult::fail(action(), "Timeline not initialized");
        }
        VideoEngine::Clip* clip = findClip(timeline.get(), m_clipId);
        if (!clip) {
            return CommandResult::fail(action(), "Clip not found");
        }
        clip->setEffectBrightness(m_previousBrightness);
        clip->setEffectContrast(m_previousContrast);
        clip->setEffectSaturation(m_previousSaturation);
        clip->setEffectsEnabled(true);
        g_pendingScrubMs.store(static_cast<long long>(context.currentTimeMs->load()), std::memory_order_release);
        return CommandResult::ok("UNDO", "Clip effects reverted",
                                 "{\"clipId\":" + std::to_string(m_clipId) + "}");
    }

private:
    int m_clipId;
    float m_brightness;
    float m_contrast;
    float m_saturation;
    float m_previousBrightness = 0.0f;
    float m_previousContrast = 1.0f;
    float m_previousSaturation = 1.0f;
};

class TransitionCommand final : public EditorCommand {
public:
    TransitionCommand(
        std::string mode,
        int64_t transitionId,
        int outgoingClipId,
        int incomingClipId,
        int typeId,
        int durationMs,
        int64_t startTimeMs)
        : EditorCommand("TRANSITION"),
          m_mode(std::move(mode)),
          m_transitionId(transitionId),
          m_outgoingClipId(outgoingClipId),
          m_incomingClipId(incomingClipId),
          m_typeId(typeId),
          m_durationMs(durationMs),
          m_startTimeMs(startTimeMs) {}

    bool canUndo() const override { return true; }

    CommandResult execute(CommandContext&) override {
        if (m_mode == "add") {
            m_createdTransition.id = g_nextTransitionId++;
            m_createdTransition.outgoingClipId = m_outgoingClipId;
            m_createdTransition.incomingClipId = m_incomingClipId;
            m_createdTransition.typeId = m_typeId;
            m_createdTransition.durationMs = m_durationMs;
            m_createdTransition.startTimeMs = m_startTimeMs;
            m_createdTransition.isEnabled = true;
            g_transitions[m_createdTransition.id] = m_createdTransition;
            return CommandResult::ok(
                action(),
                "Transition added",
                "{\"transitionId\":" + std::to_string(m_createdTransition.id) + "}");
        }

        if (m_mode == "update") {
            auto it = g_transitions.find(m_transitionId);
            if (it == g_transitions.end()) {
                return CommandResult::fail(action(), "Transition not found");
            }
            m_previousTransition = it->second;
            it->second.typeId = m_typeId;
            it->second.durationMs = m_durationMs;
            return CommandResult::ok(
                action(),
                "Transition updated",
                "{\"transitionId\":" + std::to_string(m_transitionId) + "}");
        }

        if (m_mode == "remove") {
            auto it = g_transitions.find(m_transitionId);
            if (it == g_transitions.end()) {
                return CommandResult::fail(action(), "Transition not found");
            }
            m_previousTransition = it->second;
            g_transitions.erase(it);
            return CommandResult::ok(
                action(),
                "Transition removed",
                "{\"transitionId\":" + std::to_string(m_transitionId) + "}");
        }

        return CommandResult::fail(action(), "Unsupported transition mode");
    }

    CommandResult undo(CommandContext&) override {
        if (m_mode == "add") {
            g_transitions.erase(m_createdTransition.id);
            return CommandResult::ok("UNDO", "Transition add reverted",
                                     "{\"transitionId\":" + std::to_string(m_createdTransition.id) + "}");
        }
        if (m_mode == "update") {
            if (m_previousTransition.id <= 0) {
                return CommandResult::fail("UNDO", "Missing transition snapshot");
            }
            g_transitions[m_previousTransition.id] = m_previousTransition;
            return CommandResult::ok("UNDO", "Transition update reverted",
                                     "{\"transitionId\":" + std::to_string(m_previousTransition.id) + "}");
        }
        if (m_mode == "remove") {
            if (m_previousTransition.id <= 0) {
                return CommandResult::fail("UNDO", "Missing transition snapshot");
            }
            g_transitions[m_previousTransition.id] = m_previousTransition;
            return CommandResult::ok("UNDO", "Transition restored",
                                     "{\"transitionId\":" + std::to_string(m_previousTransition.id) + "}");
        }
        return CommandResult::fail("UNDO", "Unsupported transition mode");
    }

private:
    std::string m_mode;
    int64_t m_transitionId;
    int m_outgoingClipId;
    int m_incomingClipId;
    int m_typeId;
    int m_durationMs;
    int64_t m_startTimeMs;
    Transition m_createdTransition{};
    Transition m_previousTransition{};
};

class NotImplementedCommand final : public EditorCommand {
public:
    explicit NotImplementedCommand(std::string action)
        : EditorCommand(std::move(action)) {}

    CommandResult execute(CommandContext&) override {
        return CommandResult::fail(action(), "Command scaffolded but not implemented");
    }
};

} // namespace

std::string CommandResult::toJson() const {
    std::ostringstream out;
    out << "{"
        << "\"success\":" << (success ? "true" : "false") << ","
        << "\"action\":" << quote(action) << ","
        << "\"message\":" << quote(message) << ","
        << "\"data\":" << (dataJson.empty() ? "{}" : dataJson)
        << "}";
    return out.str();
}

CommandResult CommandResult::ok(const std::string& action, const std::string& message, const std::string& dataJson) {
    return CommandResult{true, action, message, dataJson};
}

CommandResult CommandResult::fail(const std::string& action, const std::string& message, const std::string& dataJson) {
    return CommandResult{false, action, message, dataJson};
}

CommandManager& CommandManager::instance() {
    static CommandManager manager;
    return manager;
}

CommandManager::CommandManager() = default;

CommandManager::~CommandManager() {
    {
        std::lock_guard<std::mutex> lock(m_queueMutex);
        m_shutdown = true;
    }
    m_queueCv.notify_all();
    if (m_worker.joinable()) {
        m_worker.join();
    }
}

void CommandManager::initialize(CommandContext context) {
    m_context = context;
    ensureWorkerRunning();
}

void CommandManager::ensureWorkerRunning() {
    if (m_workerStarted) return;
    m_workerStarted = true;
    m_worker = std::thread(&CommandManager::workerLoop, this);
}

CommandResult CommandManager::execute(const std::string& action, const std::string& payloadJson) {
    ensureWorkerRunning();
    if (action == "UNDO") return undo();
    if (action == "REDO") return redo();

    auto command = buildCommand(action, payloadJson);
    if (!command) {
        const CommandResult result = CommandResult::fail(action, "Unsupported command");
        appendTelemetryEvent("unsupported", action, payloadJson, false, 0, &result, 0);
        return result;
    }

    auto promise = std::make_shared<std::promise<CommandResult>>();
    std::future<CommandResult> future = promise->get_future();
    size_t queueDepth = 0;
    {
        std::lock_guard<std::mutex> lock(m_queueMutex);
        queueDepth = m_queue.size() + 1;
        m_queue.push_back(PendingCommand{
            QueueOperation::Execute,
            std::move(command),
            promise,
            action,
            payloadJson,
            false,
            queueDepth,
        });
    }
    appendTelemetryEvent("enqueued", action, payloadJson, false, queueDepth);
    m_queueCv.notify_one();
    return future.get();
}

void CommandManager::executeAsync(const std::string& action, const std::string& payloadJson) {
    ensureWorkerRunning();
    if (action == "UNDO" || action == "REDO") {
        return;
    }

    auto command = buildCommand(action, payloadJson);
    if (!command) {
        const CommandResult result = CommandResult::fail(action, "Unsupported command");
        appendTelemetryEvent("unsupported", action, payloadJson, true, 0, &result, 0);
        return;
    }

    size_t queueDepth = 0;
    {
        std::lock_guard<std::mutex> lock(m_queueMutex);
        if (isCoalescibleAsyncAction(action)) {
            for (auto it = m_queue.rbegin(); it != m_queue.rend(); ++it) {
                if (it->operation == QueueOperation::Execute &&
                    !it->promise &&
                    it->command &&
                    it->command->action() == action) {
                    it->command = std::move(command);
                    it->action = action;
                    it->payloadJson = payloadJson;
                    it->async = true;
                    it->queueDepthAtEnqueue = m_queue.size();
                    appendTelemetryEvent("coalesced", action, payloadJson, true, m_queue.size());
                    m_queueCv.notify_one();
                    return;
                }
            }
        }
        queueDepth = m_queue.size() + 1;
        m_queue.push_back(PendingCommand{
            QueueOperation::Execute,
            std::move(command),
            nullptr,
            action,
            payloadJson,
            true,
            queueDepth,
        });
    }
    appendTelemetryEvent("enqueued", action, payloadJson, true, queueDepth);
    m_queueCv.notify_one();
}

CommandResult CommandManager::undo() {
    ensureWorkerRunning();
    auto promise = std::make_shared<std::promise<CommandResult>>();
    std::future<CommandResult> future = promise->get_future();
    size_t queueDepth = 0;
    {
        std::lock_guard<std::mutex> lock(m_queueMutex);
        queueDepth = m_queue.size() + 1;
        m_queue.push_back(PendingCommand{QueueOperation::Undo, nullptr, promise, "UNDO", "{}", false, queueDepth});
    }
    appendTelemetryEvent("enqueued", "UNDO", "{}", false, queueDepth);
    m_queueCv.notify_one();
    return future.get();
}

CommandResult CommandManager::redo() {
    ensureWorkerRunning();
    auto promise = std::make_shared<std::promise<CommandResult>>();
    std::future<CommandResult> future = promise->get_future();
    size_t queueDepth = 0;
    {
        std::lock_guard<std::mutex> lock(m_queueMutex);
        queueDepth = m_queue.size() + 1;
        m_queue.push_back(PendingCommand{QueueOperation::Redo, nullptr, promise, "REDO", "{}", false, queueDepth});
    }
    appendTelemetryEvent("enqueued", "REDO", "{}", false, queueDepth);
    m_queueCv.notify_one();
    return future.get();
}

std::string CommandManager::recentTelemetryJson() const {
    std::lock_guard<std::mutex> lock(m_telemetryMutex);
    std::ostringstream out;
    out << "[";
    for (size_t index = 0; index < m_telemetryEntries.size(); ++index) {
        if (index > 0) {
            out << ",";
        }
        out << m_telemetryEntries[index];
    }
    out << "]";
    return out.str();
}

void CommandManager::clearTelemetry() {
    std::lock_guard<std::mutex> lock(m_telemetryMutex);
    m_telemetryEntries.clear();
}

void CommandManager::appendTelemetryEvent(
    const std::string& phase,
    const std::string& action,
    const std::string& payloadJson,
    bool async,
    size_t queueDepth,
    const CommandResult* result,
    long long durationMs) {
    std::ostringstream out;
    out << "{"
        << "\"timestampMs\":" << currentEpochMs() << ","
        << "\"phase\":" << quote(phase) << ","
        << "\"action\":" << quote(action) << ","
        << "\"async\":" << (async ? "true" : "false") << ","
        << "\"queueDepth\":" << queueDepth << ","
        << "\"payload\":" << (payloadJson.empty() ? "{}" : payloadJson);
    if (durationMs >= 0) {
        out << ",\"durationMs\":" << durationMs;
    }
    if (result) {
        out << ",\"success\":" << (result->success ? "true" : "false") << ","
            << "\"message\":" << quote(result->message) << ","
            << "\"resultData\":" << (result->dataJson.empty() ? "{}" : result->dataJson);
    }
    out << "}";

    std::lock_guard<std::mutex> lock(m_telemetryMutex);
    m_telemetryEntries.push_back(out.str());
    while (m_telemetryEntries.size() > kMaxRecentTelemetryEntries) {
        m_telemetryEntries.pop_front();
    }
}

void CommandManager::workerLoop() {
    boostCommandThreadPriority();
    while (true) {
        PendingCommand pending;
        {
            std::unique_lock<std::mutex> lock(m_queueMutex);
            m_queueCv.wait(lock, [&]() { return m_shutdown || !m_queue.empty(); });
            if (m_shutdown && m_queue.empty()) {
                return;
            }
            pending = std::move(m_queue.front());
            m_queue.pop_front();
        }

        if (pending.operation == QueueOperation::Execute && pending.command) {
            const auto startedAt = std::chrono::steady_clock::now();
            CommandResult result = pending.command->execute(m_context);
            appendTelemetryEvent(
                "completed",
                pending.action.empty() ? pending.command->action() : pending.action,
                pending.payloadJson,
                pending.async,
                pending.queueDepthAtEnqueue,
                &result,
                std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - startedAt)
                    .count());
            if (result.success && pending.command->canUndo()) {
                std::lock_guard<std::mutex> lock(m_queueMutex);
                m_undoStack.push_back(std::move(pending.command));
                m_redoStack.clear();
            }
            if (pending.promise) {
                pending.promise->set_value(result);
            }
            continue;
        }

        if (pending.operation == QueueOperation::Undo) {
            std::unique_ptr<EditorCommand> command;
            {
                std::lock_guard<std::mutex> lock(m_queueMutex);
                if (m_undoStack.empty()) {
                    CommandResult result = CommandResult::fail("UNDO", "Nothing to undo");
                    appendTelemetryEvent(
                        "completed",
                        "UNDO",
                        pending.payloadJson,
                        pending.async,
                        pending.queueDepthAtEnqueue,
                        &result,
                        0);
                    if (pending.promise) {
                        pending.promise->set_value(result);
                    }
                    continue;
                }
                command = std::move(m_undoStack.back());
                m_undoStack.pop_back();
            }
            const auto startedAt = std::chrono::steady_clock::now();
            CommandResult result = command->undo(m_context);
            appendTelemetryEvent(
                "completed",
                "UNDO",
                pending.payloadJson,
                pending.async,
                pending.queueDepthAtEnqueue,
                &result,
                std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - startedAt)
                    .count());
            if (result.success) {
                std::lock_guard<std::mutex> lock(m_queueMutex);
                m_redoStack.push_back(std::move(command));
            }
            if (pending.promise) {
                pending.promise->set_value(result);
            }
            continue;
        }

        if (pending.operation == QueueOperation::Redo) {
            std::unique_ptr<EditorCommand> command;
            {
                std::lock_guard<std::mutex> lock(m_queueMutex);
                if (m_redoStack.empty()) {
                    CommandResult result = CommandResult::fail("REDO", "Nothing to redo");
                    appendTelemetryEvent(
                        "completed",
                        "REDO",
                        pending.payloadJson,
                        pending.async,
                        pending.queueDepthAtEnqueue,
                        &result,
                        0);
                    if (pending.promise) {
                        pending.promise->set_value(result);
                    }
                    continue;
                }
                command = std::move(m_redoStack.back());
                m_redoStack.pop_back();
            }
            const auto startedAt = std::chrono::steady_clock::now();
            CommandResult result = command->execute(m_context);
            appendTelemetryEvent(
                "completed",
                "REDO",
                pending.payloadJson,
                pending.async,
                pending.queueDepthAtEnqueue,
                &result,
                std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - startedAt)
                    .count());
            if (result.success) {
                std::lock_guard<std::mutex> lock(m_queueMutex);
                m_undoStack.push_back(std::move(command));
            }
            if (pending.promise) {
                pending.promise->set_value(result);
            }
            continue;
        }

        if (pending.promise) {
            CommandResult result = CommandResult::fail("COMMAND", "Invalid queue operation");
            appendTelemetryEvent(
                "completed",
                "COMMAND",
                pending.payloadJson,
                pending.async,
                pending.queueDepthAtEnqueue,
                &result,
                0);
            pending.promise->set_value(result);
        }
    }
}

std::unique_ptr<EditorCommand> CommandManager::buildCommand(const std::string& action, const std::string& payloadJson) {
    if (action == "SEEK") {
        int64_t timeMs = 0;
        extractInt64Value(payloadJson, "timeMs", timeMs);
        return std::make_unique<SeekCommand>(timeMs);
    }
    if (action == "LOAD_VIDEO") {
        return std::make_unique<LoadVideoCommand>(extractStringValue(payloadJson, "videoPath"));
    }
    if (action == "ADD_CLIP") {
        int64_t trackLane = 0;
        int64_t zOrder = 0;
        int64_t startTimeMs = -1;
        extractInt64Value(payloadJson, "trackLane", trackLane);
        extractInt64Value(payloadJson, "zOrder", zOrder);
        extractInt64Value(payloadJson, "startTimeMs", startTimeMs);
        const std::string trackType = extractStringValue(payloadJson, "trackType");
        return std::make_unique<AddClipCommand>(
            extractStringValue(payloadJson, "videoPath"),
            parseTrackRoleString(trackType),
            static_cast<int>(trackLane),
            static_cast<int>(zOrder),
            startTimeMs);
    }
    if (action == "PLAY") {
        int64_t timeMs = 0;
        extractInt64Value(payloadJson, "timeMs", timeMs);
        return std::make_unique<PlayCommand>(timeMs);
    }
    if (action == "PAUSE") {
        return std::make_unique<PauseCommand>();
    }
    if (action == "GET_DURATION") {
        return std::make_unique<GetDurationCommand>();
    }
    if (action == "GET_CURRENT_PLAYBACK_TIME") {
        return std::make_unique<GetPlaybackTimeCommand>();
    }
    if (action == "SET_TIMELINE_ZOOM") {
        double pxPerSecond = 120.0;
        extractDoubleValue(payloadJson, "pxPerSecond", pxPerSecond);
        return std::make_unique<SetTimelineZoomCommand>(pxPerSecond);
    }
    if (action == "GET_TIMELINE_ZOOM") {
        return std::make_unique<GetTimelineZoomCommand>();
    }
    if (action == "GET_TIMELINE_LAYOUT") {
        return std::make_unique<GetTimelineLayoutCommand>();
    }
    if (action == "SET_CLIP_TRACK") {
        int64_t clipId = -1;
        int64_t trackLane = 0;
        int64_t zOrder = 0;
        extractInt64Value(payloadJson, "clipId", clipId);
        extractInt64Value(payloadJson, "trackLane", trackLane);
        extractInt64Value(payloadJson, "zOrder", zOrder);
        const std::string trackType = extractStringValue(payloadJson, "trackType");
        return std::make_unique<SetClipTrackCommand>(
            static_cast<int>(clipId),
            parseTrackRoleString(trackType),
            static_cast<int>(trackLane),
            static_cast<int>(zOrder));
    }
    if (action == "SET_PREVIEW_POLICY") {
        bool ghostEnabled = g_ghostPreviewEnabled.load();
        int64_t ghostLongEdgePx = g_ghostPreviewLongEdgePx.load();
        bool adaptiveEnabled = g_adaptiveFrameDropEnabled.load();
        int64_t targetFps = g_targetPreviewFps.load();
        int64_t minFps = g_minPreviewFps.load();
        extractBoolValue(payloadJson, "ghostPreviewEnabled", ghostEnabled);
        extractInt64Value(payloadJson, "ghostLongEdgePx", ghostLongEdgePx);
        extractBoolValue(payloadJson, "adaptiveFrameDropEnabled", adaptiveEnabled);
        extractInt64Value(payloadJson, "targetPreviewFps", targetFps);
        extractInt64Value(payloadJson, "minPreviewFps", minFps);
        return std::make_unique<SetPreviewPolicyCommand>(
            ghostEnabled,
            static_cast<int>(ghostLongEdgePx),
            adaptiveEnabled,
            static_cast<int>(targetFps),
            static_cast<int>(minFps));
    }
    if (action == "GET_PREVIEW_POLICY") {
        return std::make_unique<GetPreviewPolicyCommand>();
    }
    if (action == "SET_PERFORMANCE_POLICY") {
        bool dirtyRegionEnabled = g_dirtyRegionRedrawEnabled.load();
        bool predictiveCachingEnabled = g_predictiveCachingEnabled.load();
        int64_t predictiveLookAroundMs = g_predictiveLookAroundMs.load();
        int64_t predictiveSampleStepMs = g_predictiveSampleStepMs.load();
        int64_t predictiveCacheMaxFrames = g_predictiveCacheMaxFrames.load();
        extractBoolValue(payloadJson, "dirtyRegionEnabled", dirtyRegionEnabled);
        extractBoolValue(payloadJson, "predictiveCachingEnabled", predictiveCachingEnabled);
        extractInt64Value(payloadJson, "predictiveLookAroundMs", predictiveLookAroundMs);
        extractInt64Value(payloadJson, "predictiveSampleStepMs", predictiveSampleStepMs);
        extractInt64Value(payloadJson, "predictiveCacheMaxFrames", predictiveCacheMaxFrames);
        return std::make_unique<SetPerformancePolicyCommand>(
            dirtyRegionEnabled,
            predictiveCachingEnabled,
            static_cast<int>(predictiveLookAroundMs),
            static_cast<int>(predictiveSampleStepMs),
            static_cast<int>(predictiveCacheMaxFrames));
    }
    if (action == "GET_PERFORMANCE_POLICY") {
        return std::make_unique<GetPerformancePolicyCommand>();
    }
    if (action == "SET_AUDIO_MASTER_CLOCK_ENABLED") {
        bool enabled = false;
        extractBoolValue(payloadJson, "enabled", enabled);
        return std::make_unique<SetAudioMasterClockEnabledCommand>(enabled);
    }
    if (action == "UPDATE_AUDIO_CLOCK_US") {
        int64_t ptsUs = 0;
        extractInt64Value(payloadJson, "ptsUs", ptsUs);
        return std::make_unique<UpdateAudioClockUsCommand>(ptsUs);
    }
    if (action == "BUILD_AUDIO_PEAK_MAP") {
        int64_t bucketMs = 20;
        extractInt64Value(payloadJson, "bucketMs", bucketMs);
        return std::make_unique<BuildAudioPeakMapCommand>(
            extractStringValue(payloadJson, "sourcePath"),
            extractStringValue(payloadJson, "peakMapPath"),
            static_cast<int>(bucketMs));
    }
    if (action == "GET_AUDIO_PEAK_RANGE") {
        int64_t startIndex = 0;
        int64_t maxPoints = 1024;
        extractInt64Value(payloadJson, "startIndex", startIndex);
        extractInt64Value(payloadJson, "maxPoints", maxPoints);
        return std::make_unique<GetAudioPeakRangeCommand>(
            extractStringValue(payloadJson, "peakMapPath"),
            static_cast<int>(startIndex),
            static_cast<int>(maxPoints));
    }
    if (action == "BUILD_CLIP_PROXY") {
        int64_t clipId = -1;
        int64_t maxLongEdgePx = 640;
        int64_t targetFps = 30;
        extractInt64Value(payloadJson, "clipId", clipId);
        extractInt64Value(payloadJson, "maxLongEdgePx", maxLongEdgePx);
        extractInt64Value(payloadJson, "targetFps", targetFps);
        return std::make_unique<BuildClipProxyCommand>(
            static_cast<int>(clipId),
            extractStringValue(payloadJson, "sourcePath"),
            extractStringValue(payloadJson, "outputPath"),
            static_cast<int>(maxLongEdgePx),
            static_cast<int>(targetFps));
    }
    if (action == "GET_CLIP_PROXY_STATUS") {
        int64_t clipId = -1;
        extractInt64Value(payloadJson, "clipId", clipId);
        return std::make_unique<GetClipProxyStatusCommand>(static_cast<int>(clipId));
    }
    if (action == "ACTIVATE_CLIP_PROXY") {
        int64_t clipId = -1;
        extractInt64Value(payloadJson, "clipId", clipId);
        return std::make_unique<ActivateClipProxyCommand>(static_cast<int>(clipId));
    }
    if (action == "DELETE" || action == "DELETE_CLIP") {
        int64_t clipId = -1;
        extractInt64Value(payloadJson, "clipId", clipId);
        return std::make_unique<DeleteClipCommand>(static_cast<int>(clipId));
    }
    if (action == "MOVE_CLIP") {
        int64_t clipId = -1;
        int64_t newTimeMs = 0;
        extractInt64Value(payloadJson, "clipId", clipId);
        extractInt64Value(payloadJson, "newTimeMs", newTimeMs);
        return std::make_unique<MoveClipCommand>(static_cast<int>(clipId), newTimeMs);
    }
    if (action == "UPDATE_CLIP_TIMING") {
        int64_t clipId = -1;
        int64_t newStartTimeMs = 0;
        int64_t newDurationMs = 1;
        int64_t newSourceInMs = 0;
        int64_t newSourceOutMs = 1;
        int64_t originalStartTimeMs = 0;
        int64_t originalDurationMs = 1;
        int64_t originalSourceInMs = 0;
        int64_t originalSourceOutMs = 1;
        bool previewOnly = false;
        bool applyMagnetic = false;
        extractInt64Value(payloadJson, "clipId", clipId);
        extractInt64Value(payloadJson, "newStartTimeMs", newStartTimeMs);
        extractInt64Value(payloadJson, "newDurationMs", newDurationMs);
        extractInt64Value(payloadJson, "newSourceInMs", newSourceInMs);
        extractInt64Value(payloadJson, "newSourceOutMs", newSourceOutMs);
        extractInt64Value(payloadJson, "originalStartTimeMs", originalStartTimeMs);
        extractInt64Value(payloadJson, "originalDurationMs", originalDurationMs);
        extractInt64Value(payloadJson, "originalSourceInMs", originalSourceInMs);
        extractInt64Value(payloadJson, "originalSourceOutMs", originalSourceOutMs);
        extractBoolValue(payloadJson, "previewOnly", previewOnly);
        extractBoolValue(payloadJson, "applyMagnetic", applyMagnetic);
        return std::make_unique<UpdateClipTimingCommand>(
            static_cast<int>(clipId),
            newStartTimeMs,
            newDurationMs,
            newSourceInMs,
            newSourceOutMs,
            originalStartTimeMs,
            originalDurationMs,
            originalSourceInMs,
            originalSourceOutMs,
            previewOnly,
            applyMagnetic);
    }
    if (action == "KEYFRAME_ADD") {
        int64_t clipId = -1;
        int64_t timeMs = 0;
        extractInt64Value(payloadJson, "clipId", clipId);
        extractInt64Value(payloadJson, "timeMs", timeMs);
        return std::make_unique<AddKeyframeCommand>(static_cast<int>(clipId), timeMs);
    }
    if (action == "REVERSE_CLIP" || action == "REVERSE") {
        int64_t clipId = -1;
        bool enabled = false;
        extractInt64Value(payloadJson, "clipId", clipId);
        const bool hasEnabled = extractBoolValue(payloadJson, "enabled", enabled);
        return std::make_unique<ReverseClipCommand>(static_cast<int>(clipId), enabled, hasEnabled);
    }
    if (action == "FREEZE_FRAME") {
        int64_t clipId = -1;
        int64_t timeMs = 0;
        int64_t durationMs = 1000;
        extractInt64Value(payloadJson, "clipId", clipId);
        extractInt64Value(payloadJson, "timeMs", timeMs);
        extractInt64Value(payloadJson, "durationMs", durationMs);
        return std::make_unique<FreezeFrameCommand>(static_cast<int>(clipId), timeMs, durationMs);
    }
    if (action == "AUDIO_DUCKING") {
        int64_t clipId = -1;
        bool enabled = false;
        double amount = 0.35;
        extractInt64Value(payloadJson, "clipId", clipId);
        extractBoolValue(payloadJson, "enabled", enabled);
        extractDoubleValue(payloadJson, "amount", amount);
        return std::make_unique<AudioDuckingCommand>(static_cast<int>(clipId), enabled, amount);
    }
    if (action == "CURVE_SPEED") {
        int64_t clipId = -1;
        double strength = 1.0;
        extractInt64Value(payloadJson, "clipId", clipId);
        extractDoubleValue(payloadJson, "strength", strength);
        return std::make_unique<CurveSpeedCommand>(
            static_cast<int>(clipId),
            extractStringValue(payloadJson, "profile"),
            strength);
    }
    if (action == "SPEED") {
        int64_t clipId = -1;
        double speed = 1.0;
        extractInt64Value(payloadJson, "clipId", clipId);
        extractDoubleValue(payloadJson, "speed", speed);
        return std::make_unique<SpeedCommand>(static_cast<int>(clipId), speed);
    }
    if (action == "SET_CLIP_VOLUME") {
        int64_t clipId = -1;
        double volume = 1.0;
        extractInt64Value(payloadJson, "clipId", clipId);
        extractDoubleValue(payloadJson, "volume", volume);
        return std::make_unique<SetClipVolumeCommand>(static_cast<int>(clipId), volume);
    }
    if (action == "DUPLICATE_CLIP") {
        int64_t clipId = -1;
        extractInt64Value(payloadJson, "clipId", clipId);
        return std::make_unique<DuplicateClipCommand>(static_cast<int>(clipId));
    }
    if (action == "REPLACE_CLIP_SOURCE") {
        int64_t clipId = -1;
        extractInt64Value(payloadJson, "clipId", clipId);
        return std::make_unique<ReplaceClipSourceCommand>(
            static_cast<int>(clipId),
            extractStringValue(payloadJson, "videoPath"));
    }
    if (action == "EXTRACT_AUDIO") {
        int64_t clipId = -1;
        extractInt64Value(payloadJson, "clipId", clipId);
        return std::make_unique<ExtractAudioCommand>(static_cast<int>(clipId));
    }
    if (action == "TRIM_CLIP") {
        int64_t clipId = -1;
        int64_t timeMs = 0;
        extractInt64Value(payloadJson, "clipId", clipId);
        extractInt64Value(payloadJson, "timeMs", timeMs);
        return std::make_unique<TrimClipCommand>(
            static_cast<int>(clipId),
            timeMs,
            extractStringValue(payloadJson, "edge"));
    }
    if (action == "SET_CLIP_EFFECTS") {
        int64_t clipId = -1;
        double brightness = 0.0;
        double contrast = 1.0;
        double saturation = 1.0;
        extractInt64Value(payloadJson, "clipId", clipId);
        extractDoubleValue(payloadJson, "brightness", brightness);
        extractDoubleValue(payloadJson, "contrast", contrast);
        extractDoubleValue(payloadJson, "saturation", saturation);
        return std::make_unique<SetClipEffectsCommand>(
            static_cast<int>(clipId), brightness, contrast, saturation);
    }
    if (action == "TRANSITION") {
        const std::string mode = extractStringValue(payloadJson, "mode");
        int64_t transitionId = -1;
        int64_t outgoingClipId = -1;
        int64_t incomingClipId = -1;
        int64_t typeId = 0;
        int64_t durationMs = 300;
        int64_t startTimeMs = 0;
        extractInt64Value(payloadJson, "transitionId", transitionId);
        extractInt64Value(payloadJson, "outgoingClipId", outgoingClipId);
        extractInt64Value(payloadJson, "incomingClipId", incomingClipId);
        extractInt64Value(payloadJson, "typeId", typeId);
        extractInt64Value(payloadJson, "durationMs", durationMs);
        extractInt64Value(payloadJson, "startTimeMs", startTimeMs);
        return std::make_unique<TransitionCommand>(
            mode,
            transitionId,
            static_cast<int>(outgoingClipId),
            static_cast<int>(incomingClipId),
            static_cast<int>(typeId),
            static_cast<int>(durationMs),
            startTimeMs);
    }
    if (action == "SPLIT") {
        int64_t clipId = -1;
        int64_t timeMs = 0;
        extractInt64Value(payloadJson, "clipId", clipId);
        extractInt64Value(payloadJson, "timeMs", timeMs);
        return std::make_unique<SplitClipCommand>(static_cast<int>(clipId), timeMs);
    }
    if (action == "SET_CHROMA_KEY") {
        int64_t clipId = -1;
        extractInt64Value(payloadJson, "clipId", clipId);
        bool enabled = false;
        extractBoolValue(payloadJson, "enabled", enabled);
        int64_t colorInt = 0;
        extractInt64Value(payloadJson, "color", colorInt);
        double similarity = 0.35, smoothness = 0.10, spill = 0.05;
        extractDoubleValue(payloadJson, "similarity", similarity);
        extractDoubleValue(payloadJson, "smoothness", smoothness);
        extractDoubleValue(payloadJson, "spill", spill);

        // Inline command — no undo needed for now
        struct SetChromaKeyCommand final : public EditorCommand {
            int m_clipId; bool m_enabled; int m_color;
            float m_similarity, m_smoothness, m_spill;
            SetChromaKeyCommand(int id, bool en, int col, float sim, float smo, float sp)
                : EditorCommand("SET_CHROMA_KEY"), m_clipId(id), m_enabled(en), m_color(col),
                  m_similarity(sim), m_smoothness(smo), m_spill(sp) {}
            bool canUndo() const override { return false; }
            CommandResult execute(CommandContext& ctx) override {
                std::lock_guard<std::mutex> lock(*ctx.previewMutex);
                auto timeline = (*ctx.preview)->getTimeline();
                auto clip = findClipShared(timeline.get(), m_clipId);
                if (!clip) return CommandResult::fail(action(), "Clip not found");
                clip->setChromaKeyEnabled(m_enabled);
                clip->setChromaKeyColor(m_color == 1
                    ? VideoEngine::Clip::ChromaKeyParams::KeyColor::Blue
                    : VideoEngine::Clip::ChromaKeyParams::KeyColor::Green);
                clip->setChromaKeySimilarity(m_similarity);
                clip->setChromaKeySmoothness(m_smoothness);
                clip->setChromaKeySpill(m_spill);
                return CommandResult::ok(action(), "Chroma key updated");
            }
            CommandResult undo(CommandContext&) override { return CommandResult::fail("UNDO",""); }
        };
        return std::make_unique<SetChromaKeyCommand>(
            static_cast<int>(clipId), enabled, static_cast<int>(colorInt),
            static_cast<float>(similarity), static_cast<float>(smoothness), static_cast<float>(spill));
    }
    if (action == "APPLY_LUT") {
        int64_t clipId = -1;
        extractInt64Value(payloadJson, "clipId", clipId);
        const std::string lut = extractStringValue(payloadJson, "lut");

        struct ApplyLutCommand final : public EditorCommand {
            int m_clipId; std::string m_lut;
            float m_prevBrightness = 0.f, m_prevContrast = 1.f, m_prevSaturation = 1.f;
            ApplyLutCommand(int id, std::string l)
                : EditorCommand("APPLY_LUT"), m_clipId(id), m_lut(std::move(l)) {}
            bool canUndo() const override { return true; }
            CommandResult execute(CommandContext& ctx) override {
                std::lock_guard<std::mutex> lock(*ctx.previewMutex);
                auto timeline = (*ctx.preview)->getTimeline();
                auto clip = findClipShared(timeline.get(), m_clipId);
                if (!clip) return CommandResult::fail(action(), "Clip not found");
                auto& fx = clip->getMutableEffects();
                m_prevBrightness = fx.brightness;
                m_prevContrast   = fx.contrast;
                m_prevSaturation = fx.saturation;
                if (m_lut == "warm") {
                    clip->setEffectBrightness(0.05f); clip->setEffectContrast(1.1f); clip->setEffectSaturation(1.2f);
                } else if (m_lut == "cool") {
                    clip->setEffectBrightness(-0.05f); clip->setEffectContrast(1.05f); clip->setEffectSaturation(0.85f);
                } else if (m_lut == "vintage") {
                    clip->setEffectBrightness(-0.1f); clip->setEffectContrast(0.9f); clip->setEffectSaturation(0.7f);
                } else if (m_lut == "b&w" || m_lut == "bw") {
                    clip->setEffectBrightness(0.0f); clip->setEffectContrast(1.1f); clip->setEffectSaturation(0.0f);
                } else {
                    clip->setEffectBrightness(0.0f); clip->setEffectContrast(1.0f); clip->setEffectSaturation(1.0f);
                }
                clip->setEffectsEnabled(true);
                g_pendingScrubMs.store(static_cast<long long>(ctx.currentTimeMs->load()), std::memory_order_release);
                return CommandResult::ok(action(), "LUT applied",
                    "{\"clipId\":" + std::to_string(m_clipId) + ",\"lut\":" + quote(m_lut) + "}");
            }
            CommandResult undo(CommandContext& ctx) override {
                std::lock_guard<std::mutex> lock(*ctx.previewMutex);
                auto timeline = (*ctx.preview)->getTimeline();
                auto clip = findClipShared(timeline.get(), m_clipId);
                if (!clip) return CommandResult::fail("UNDO", "Clip not found");
                clip->setEffectBrightness(m_prevBrightness);
                clip->setEffectContrast(m_prevContrast);
                clip->setEffectSaturation(m_prevSaturation);
                g_pendingScrubMs.store(static_cast<long long>(ctx.currentTimeMs->load()), std::memory_order_release);
                return CommandResult::ok("UNDO", "LUT reverted");
            }
        };
        return std::make_unique<ApplyLutCommand>(static_cast<int>(clipId), lut);
    }
    return nullptr;
}

} // namespace VideoEngine::Commands

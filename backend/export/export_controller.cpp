#include "export_controller.h"
#include "../gpu/preview_renderer.h"
#include "../../core/timeline.h"
#include "../../core/clip.h"
#include "../../text_overlay.h"
#include "../ffmpeg/ffmpeg_audio_renderer.h"
#include "../../engine/engine.h"
#include "../../smooth_engine/AutoCaptions.h"
#include <iostream>
#include <cstring>
#include <algorithm>
#include <fstream>
#include <unistd.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/pixdesc.h>
#include <libavutil/opt.h>
#include <libswscale/swscale.h>
}

namespace VideoEngine {

namespace {
bool exportFileExists(const std::string& path) {
    return !path.empty() && access(path.c_str(), F_OK) == 0;
}

std::string replaceExtension(const std::string& path, const std::string& newExtension) {
    const std::size_t slashPos = path.find_last_of("/\\");
    const std::size_t dotPos = path.find_last_of('.');
    if (dotPos == std::string::npos || (slashPos != std::string::npos && dotPos < slashPos)) {
        return path + newExtension;
    }
    return path.substr(0, dotPos) + newExtension;
}

std::string formatSrtTimestamp(int64_t timeMs) {
    const int64_t clampedMs = std::max<int64_t>(0, timeMs);
    const int64_t hours = clampedMs / 3600000LL;
    const int64_t minutes = (clampedMs / 60000LL) % 60LL;
    const int64_t seconds = (clampedMs / 1000LL) % 60LL;
    const int64_t millis = clampedMs % 1000LL;
    char buffer[32];
    std::snprintf(
        buffer,
        sizeof(buffer),
        "%02lld:%02lld:%02lld,%03lld",
        static_cast<long long>(hours),
        static_cast<long long>(minutes),
        static_cast<long long>(seconds),
        static_cast<long long>(millis));
    return buffer;
}
}  // namespace

/**
 * FFmpeg context wrapper (opaque to prevent FFmpeg includes in header).
 */
struct ExportController::FFmpegContext {
    AVFormatContext* formatCtx = nullptr;
    AVStream* videoStream = nullptr;
    const AVCodec* codec = nullptr;
    AVCodecContext* codecCtx = nullptr;
    SwsContext* swsCtx = nullptr;
    AVFrame* frame = nullptr;
    AVPacket* pkt = nullptr;

    // Audio mux
    AVStream* audioStream = nullptr;
    const AVCodec* audioCodec = nullptr;
    AVCodecContext* audioCodecCtx = nullptr;
    AVFrame* audioFrame = nullptr;
    AVPacket* audioPkt = nullptr;
    int64_t audioNextPts = 0;

    ~FFmpegContext() {
        if (frame) av_frame_free(&frame);
        if (pkt) av_packet_free(&pkt);
        if (audioFrame) av_frame_free(&audioFrame);
        if (audioPkt) av_packet_free(&audioPkt);
        if (swsCtx) sws_freeContext(swsCtx);
        if (codecCtx) avcodec_free_context(&codecCtx);
        if (audioCodecCtx) avcodec_free_context(&audioCodecCtx);
        if (formatCtx) {
            if (!(formatCtx->oformat->flags & AVFMT_NOFILE))
                avio_closep(&formatCtx->pb);
            avformat_free_context(formatCtx);
        }
    }
};

// ============ ExportController Implementation ============

ExportController::ExportController(std::shared_ptr<Timeline> timeline, const ExportConfig& config)
    : m_timeline(timeline), m_config(config), m_ffmpegCtx(std::make_unique<FFmpegContext>())
{
    if (!m_timeline) {
        throw std::runtime_error("ExportController: timeline cannot be null");
    }

    // Calculate total frames
    TimeMs durationMs = m_timeline->getDuration();
    m_totalFrames = static_cast<uint64_t>(durationMs) * m_config.fps / 1000;

    std::cout << "ExportController initialized:\n"
              << "  Resolution: " << m_config.width << "x" << m_config.height << "\n"
              << "  FPS: " << m_config.fps << "\n"
              << "  Total frames: " << m_totalFrames << "\n"
              << "  Duration: " << (durationMs / 1000.0) << " seconds\n"
              << "  Output: " << m_config.outputPath << "\n"
              << std::endl;
}

ExportController::~ExportController() = default;

bool ExportController::exportToVideo(ProgressCallback progress) {
    if (m_isExporting) {
        m_lastError = "Export already in progress";
        return false;
    }

    m_isExporting = true;
    m_cancelRequested = false;
    m_encodeFrameNumber = 0;

    try {
        // Initialize stage
        if (progress) progress(0, m_totalFrames, "Initialize");

        // Create headless GPU renderer
        m_renderer = std::make_shared<GPU::PreviewRenderer>(
            m_config.width, m_config.height,
            GPU::PreviewRenderer::RenderMode::Headless,
            false  // no debug output
        );

        if (!m_renderer->isValid()) {
            throw std::runtime_error("Failed to create headless renderer");
        }

        prepareHardwareEncoderBackend();

        // Initialize FFmpeg encoder
        if (!initializeFFmpegEncoder()) {
            throw std::runtime_error("Failed to initialize FFmpeg encoder: " + m_lastError);
        }

        // Pre-decode all audio from timeline clips
        Backend::AudioRenderer audioRenderer;
        auto audioPackets = audioRenderer.generateAudioTrack(*m_timeline, "");
        size_t audioPacketIdx = 0;

        // Main render loop
        TimeMs durationMs = m_timeline->getDuration();
        TimeMs frameIntervalMs = 1000 / m_config.fps;

        for (uint64_t frameNum = 0; frameNum < m_totalFrames; ++frameNum) {
            if (m_cancelRequested) break;

            TimeMs currentTimeMs = frameNum * frameIntervalMs;
            if (currentTimeMs >= durationMs) currentTimeMs = durationMs - 1;

            auto activeClips = m_timeline->getActiveClipsAtTime(currentTimeMs);
            auto activeTexts = m_timeline->getActiveTextOverlaysAtTime(currentTimeMs);
            m_renderer->renderFrame(activeClips, activeTexts, currentTimeMs);

            if (m_hardwareEncoderPrepared) {
                auto framebufferTexture = m_renderer->getFramebufferTexture();
                if (framebufferTexture && framebufferTexture->isValid()) {
                    m_hardwareEncoder->encodeFrame(
                        framebufferTexture->getHandle(),
                        currentTimeMs * 1000LL);
                }
            }

            auto rgbaPixels = readFramebufferRGBA();
            if (rgbaPixels.empty())
                throw std::runtime_error("Failed to read framebuffer");

            std::vector<uint8_t> yBuffer(m_config.width * m_config.height);
            std::vector<uint8_t> uBuffer(m_config.width / 2 * m_config.height / 2);
            std::vector<uint8_t> vBuffer(m_config.width / 2 * m_config.height / 2);
            convertRGBAtoYUV420P(rgbaPixels, yBuffer, uBuffer, vBuffer);

            if (!encodeYUVFrame(yBuffer.data(), uBuffer.data(), vBuffer.data()))
                throw std::runtime_error("Failed to encode frame " + std::to_string(frameNum));

            // Write interleaved audio packets up to current video time
            auto& ctx = m_ffmpegCtx;
            if (ctx->audioStream && ctx->audioCodecCtx) {
                while (audioPacketIdx < audioPackets.size()) {
                    const auto& ap = audioPackets[audioPacketIdx];
                    if (!ap) { ++audioPacketIdx; continue; }
                    // Only write audio that belongs before or at current video time
                    if (ap->ptsMs > currentTimeMs + frameIntervalMs) break;
                    AVPacket* rawPkt = av_packet_alloc();
                    if (rawPkt) {
                        rawPkt->data = ap->data.data();
                        rawPkt->size = static_cast<int>(ap->data.size());
                        rawPkt->pts  = av_rescale_q(ap->ptsMs, {1, 1000}, ctx->audioStream->time_base);
                        rawPkt->dts  = rawPkt->pts;
                        rawPkt->duration = av_rescale_q(ap->durationMs, {1, 1000}, ctx->audioStream->time_base);
                        rawPkt->stream_index = ctx->audioStream->index;
                        av_interleaved_write_frame(ctx->formatCtx, rawPkt);
                        rawPkt->data = nullptr; rawPkt->size = 0;
                        av_packet_free(&rawPkt);
                    }
                    ++audioPacketIdx;
                }
            }

            if (progress) progress(frameNum, m_totalFrames, "Rendering");
        }

        // Finalize FFmpeg encoder
        if (progress) progress(m_totalFrames, m_totalFrames, "Finalize");

        if (!finalizeFFmpegEncoder()) {
            throw std::runtime_error("Failed to finalize FFmpeg encoder: " + m_lastError);
        }

        writeAutoCaptionSidecar();

        std::cout << "Export complete: " << m_config.outputPath << std::endl;

        releaseHardwareEncoderBackend();
        m_isExporting = false;
        return true;

    } catch (const std::exception& e) {
        m_lastError = e.what();
        std::cerr << "Export error: " << m_lastError << std::endl;
        releaseHardwareEncoderBackend();
        m_isExporting = false;
        return false;
    }
}

void ExportController::prepareHardwareEncoderBackend() {
    releaseHardwareEncoderBackend();

    const bool hardwareCodecRequested =
        m_config.videoCodec == "h264" ||
        m_config.videoCodec == "avc" ||
        m_config.videoCodec == "hevc" ||
        m_config.videoCodec == "h265" ||
        m_config.videoCodec == "av1";
    if (!hardwareCodecRequested) {
        return;
    }

    VideoEngine::Backend::HardwareEncoderPro::Config config;
    config.width = static_cast<int>(m_config.width);
    config.height = static_cast<int>(m_config.height);
    config.bitrate = static_cast<int>(m_config.bitrate > 0 ? m_config.bitrate * 1000 : m_config.calculateBitrate() * 1000);
    config.fps = static_cast<int>(m_config.fps);
    config.mimeType =
        (m_config.videoCodec == "hevc" || m_config.videoCodec == "h265")
            ? "video/hevc"
            : (m_config.videoCodec == "av1" ? "video/av01" : "video/avc");

    m_hardwareEncoder = std::make_unique<VideoEngine::Backend::HardwareEncoderPro>();
    m_hardwareEncoder->init(config);
    m_hardwareEncoderPrepared = true;
    std::cout << "[ExportController] HardwareEncoderPro prepared for " << config.mimeType << "\n";
}

void ExportController::releaseHardwareEncoderBackend() {
    if (!m_hardwareEncoderPrepared || !m_hardwareEncoder) {
        m_hardwareEncoder.reset();
        m_hardwareEncoderPrepared = false;
        return;
    }
    m_hardwareEncoder->finish();
    m_hardwareEncoder.reset();
    m_hardwareEncoderPrepared = false;
}

std::string ExportController::resolveAutoCaptionSourcePath() const {
    if (!m_timeline) {
        return {};
    }
    int bestPriority = std::numeric_limits<int>::max();
    std::string bestPath;
    for (const auto& clip : m_timeline->clips()) {
        if (!clip || clip->getMediaPath().empty()) {
            continue;
        }
        if (clip->getMediaType() == Clip::MediaType::Image) {
            continue;
        }
        if (!exportFileExists(clip->getMediaPath())) {
            continue;
        }
        int priority = 99;
        switch (clip->getTrackRole()) {
            case Clip::TrackRole::Audio: priority = 0; break;
            case Clip::TrackRole::MainVideo: priority = 1; break;
            case Clip::TrackRole::Overlay: priority = 2; break;
            default: break;
        }
        if (priority < bestPriority) {
            bestPriority = priority;
            bestPath = clip->getMediaPath();
        }
    }
    return bestPath;
}

void ExportController::writeAutoCaptionSidecar() {
    const std::string sourcePath = resolveAutoCaptionSourcePath();
    if (sourcePath.empty()) {
        return;
    }

    VideoEngine::AI::AutoCaptions autoCaptions;
    const auto captions = autoCaptions.generate(sourcePath);
    if (captions.empty()) {
        return;
    }

    const std::string captionPath = replaceExtension(m_config.outputPath, ".srt");
    std::ofstream out(captionPath, std::ios::out | std::ios::trunc);
    if (!out.is_open()) {
        std::cerr << "[ExportController] AutoCaptions sidecar open failed: " << captionPath << "\n";
        return;
    }

    int index = 1;
    for (const auto& caption : captions) {
        out << index++ << "\n"
            << formatSrtTimestamp(caption.startMs)
            << " --> "
            << formatSrtTimestamp(std::max<int64_t>(caption.endMs, caption.startMs + 1))
            << "\n"
            << caption.text
            << "\n\n";
    }
    out.close();
    std::cout << "[ExportController] AutoCaptions sidecar ready: " << captionPath << "\n";
}

std::vector<uint8_t> ExportController::readFramebufferRGBA() {
    // Allocate output buffer (RGBA8, 4 bytes per pixel)
    size_t bufferSize = m_config.width * m_config.height * 4;
    std::vector<uint8_t> pixelData(bufferSize);

    // Bind framebuffer and read pixels
    auto tex = m_renderer->getFramebufferTexture();
    if (!tex || !tex->isValid()) {
        std::cerr << "Invalid framebuffer texture" << std::endl;
        return {};
    }

    tex->bind(0);

    glReadPixels(
        0, 0,
        m_config.width, m_config.height,
        GL_RGBA,
        GL_UNSIGNED_BYTE,
        pixelData.data()
    );

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        std::cerr << "glReadPixels error: " << err << std::endl;
        return {};
    }

    return pixelData;
}

void ExportController::convertRGBAtoYUV420P(
    const std::vector<uint8_t>& rgbaPixels,
    std::vector<uint8_t>& yBuffer,
    std::vector<uint8_t>& uBuffer,
    std::vector<uint8_t>& vBuffer)
{
    // BT.709 conversion matrix (for HD video)
    // Y  = 0.2126*R + 0.7152*G + 0.0722*B + 16
    // Cb = -0.1146*R - 0.3854*G + 0.5*B + 128
    // Cr = 0.5*R - 0.4542*G - 0.0458*B + 128

    constexpr float Wr = 0.2126f;
    constexpr float Wg = 0.7152f;
    constexpr float Wb = 0.0722f;

    auto clamp = [](int val) -> uint8_t {
        return static_cast<uint8_t>(std::max(0, std::min(255, val)));
    };

    // Process Y plane (full resolution)
    for (uint32_t y = 0; y < m_config.height; ++y) {
        for (uint32_t x = 0; x < m_config.width; ++x) {
            uint32_t pixelIdx = (y * m_config.width + x) * 4;
            uint8_t r = rgbaPixels[pixelIdx + 0];
            uint8_t g = rgbaPixels[pixelIdx + 1];
            uint8_t b = rgbaPixels[pixelIdx + 2];

            float Y = Wr * r + Wg * g + Wb * b + 16.0f;
            yBuffer[y * m_config.width + x] = clamp(static_cast<int>(Y));
        }
    }

    // Process U and V planes (half resolution, 4:2:0 chroma subsampling)
    for (uint32_t y = 0; y < m_config.height / 2; ++y) {
        for (uint32_t x = 0; x < m_config.width / 2; ++x) {
            // Sample 2x2 block from RGBA
            uint32_t idx00 = ((y * 2) * m_config.width + (x * 2)) * 4;
            uint32_t idx10 = ((y * 2) * m_config.width + (x * 2 + 1)) * 4;
            uint32_t idx01 = (((y * 2 + 1) * m_config.width) + (x * 2)) * 4;
            uint32_t idx11 = (((y * 2 + 1) * m_config.width) + (x * 2 + 1)) * 4;

            // Average pixels
            uint8_t r = (rgbaPixels[idx00 + 0] + rgbaPixels[idx10 + 0] +
                        rgbaPixels[idx01 + 0] + rgbaPixels[idx11 + 0]) / 4;
            uint8_t g = (rgbaPixels[idx00 + 1] + rgbaPixels[idx10 + 1] +
                        rgbaPixels[idx01 + 1] + rgbaPixels[idx11 + 1]) / 4;
            uint8_t b = (rgbaPixels[idx00 + 2] + rgbaPixels[idx10 + 2] +
                        rgbaPixels[idx01 + 2] + rgbaPixels[idx11 + 2]) / 4;

            // Cb = -0.1146*R - 0.3854*G + 0.5*B + 128
            float Cb = -0.1146f * r - 0.3854f * g + 0.5f * b + 128.0f;
            // Cr = 0.5*R - 0.4542*G - 0.0458*B + 128
            float Cr = 0.5f * r - 0.4542f * g - 0.0458f * b + 128.0f;

            uBuffer[y * m_config.width / 2 + x] = clamp(static_cast<int>(Cb));
            vBuffer[y * m_config.width / 2 + x] = clamp(static_cast<int>(Cr));
        }
    }
}

bool ExportController::initializeFFmpegEncoder() {
    try {
        auto& ctx = m_ffmpegCtx;

        // Create format context
        int ret = avformat_alloc_output_context2(&ctx->formatCtx, nullptr, nullptr, m_config.outputPath.c_str());
        if (ret < 0 || !ctx->formatCtx) {
            m_lastError = "Failed to create output format context";
            return false;
        }

        // Find codec by name
        ctx->codec = avcodec_find_encoder_by_name(m_config.videoCodec.c_str());
        if (!ctx->codec) {
            m_lastError = "Codec not found: " + m_config.videoCodec;
            return false;
        }

        // Create video stream
        ctx->videoStream = avformat_new_stream(ctx->formatCtx, nullptr);
        if (!ctx->videoStream) {
            m_lastError = "Failed to create video stream";
            return false;
        }

        // Allocate codec context
        ctx->codecCtx = avcodec_alloc_context3(ctx->codec);
        if (!ctx->codecCtx) {
            m_lastError = "Failed to allocate codec context";
            return false;
        }

        // Set codec parameters
        ctx->codecCtx->codec_type = AVMEDIA_TYPE_VIDEO;
        ctx->codecCtx->pix_fmt = AV_PIX_FMT_YUV420P;
        ctx->codecCtx->width = m_config.width;
        ctx->codecCtx->height = m_config.height;
        ctx->codecCtx->bit_rate = m_config.bitrate * 1000;  // Convert kbps to bps
        ctx->codecCtx->time_base = {1, m_config.fps};
        ctx->codecCtx->framerate = {m_config.fps, 1};
        ctx->codecCtx->gop_size = 10;  // Keyframe every 10 frames

        // Copy parameters to stream
        avcodec_parameters_from_context(ctx->videoStream->codecpar, ctx->codecCtx);

        // Open codec
        ret = avcodec_open2(ctx->codecCtx, ctx->codec, nullptr);
        if (ret < 0) {
            m_lastError = "Failed to open codec";
            return false;
        }

        // Open output file
        if (!(ctx->formatCtx->oformat->flags & AVFMT_NOFILE)) {
            ret = avio_open(&ctx->formatCtx->pb, m_config.outputPath.c_str(), AVIO_FLAG_WRITE);
            if (ret < 0) {
                m_lastError = "Failed to open output file: " + m_config.outputPath;
                return false;
            }
        }

        // ---- Audio stream setup ----
        ctx->audioCodec = avcodec_find_encoder(AV_CODEC_ID_AAC);
        if (ctx->audioCodec) {
            ctx->audioStream = avformat_new_stream(ctx->formatCtx, nullptr);
            ctx->audioCodecCtx = avcodec_alloc_context3(ctx->audioCodec);
            if (ctx->audioStream && ctx->audioCodecCtx) {
                ctx->audioCodecCtx->codec_type  = AVMEDIA_TYPE_AUDIO;
                ctx->audioCodecCtx->sample_fmt  = AV_SAMPLE_FMT_FLTP;
                ctx->audioCodecCtx->sample_rate = 48000;
                ctx->audioCodecCtx->bit_rate    = 128000;
#if LIBAVUTIL_VERSION_INT >= AV_VERSION_INT(57, 28, 100)
                av_channel_layout_default(&ctx->audioCodecCtx->ch_layout, 2);
#else
                ctx->audioCodecCtx->channels       = 2;
                ctx->audioCodecCtx->channel_layout = AV_CH_LAYOUT_STEREO;
#endif
                if (avcodec_open2(ctx->audioCodecCtx, ctx->audioCodec, nullptr) == 0) {
                    avcodec_parameters_from_context(ctx->audioStream->codecpar, ctx->audioCodecCtx);
                    ctx->audioStream->time_base = {1, 48000};
                    ctx->audioPkt   = av_packet_alloc();
                    ctx->audioFrame = av_frame_alloc();
                    if (ctx->audioFrame) {
                        ctx->audioFrame->format      = AV_SAMPLE_FMT_FLTP;
                        ctx->audioFrame->sample_rate = 48000;
                        ctx->audioFrame->nb_samples  = ctx->audioCodecCtx->frame_size > 0
                                                        ? ctx->audioCodecCtx->frame_size : 1024;
#if LIBAVUTIL_VERSION_INT >= AV_VERSION_INT(57, 28, 100)
                        av_channel_layout_copy(&ctx->audioFrame->ch_layout, &ctx->audioCodecCtx->ch_layout);
#else
                        ctx->audioFrame->channels       = 2;
                        ctx->audioFrame->channel_layout = AV_CH_LAYOUT_STEREO;
#endif
                        av_frame_get_buffer(ctx->audioFrame, 0);
                    }
                }
            }
        }

        // Write header (after all streams are added)
        ret = avformat_write_header(ctx->formatCtx, nullptr);
        if (ret < 0) {
            m_lastError = "Failed to write format header";
            return false;
        }

        // Allocate video frame and packet
        ctx->frame = av_frame_alloc();
        ctx->pkt = av_packet_alloc();
        if (!ctx->frame || !ctx->pkt) {
            m_lastError = "Failed to allocate frame or packet";
            return false;
        }

        ctx->frame->format = AV_PIX_FMT_YUV420P;
        ctx->frame->width = m_config.width;
        ctx->frame->height = m_config.height;

        ret = av_frame_get_buffer(ctx->frame, 32);
        if (ret < 0) {
            m_lastError = "Failed to allocate frame buffer";
            return false;
        }

        std::cout << "FFmpeg encoder initialized:\n"
                  << "  Codec: " << m_config.videoCodec << "\n"
                  << "  Bitrate: " << m_config.bitrate << " kbps\n"
                  << "  Format: YUV420P\n" << std::endl;

        return true;

    } catch (const std::exception& e) {
        m_lastError = std::string("Exception in initializeFFmpegEncoder: ") + e.what();
        return false;
    }
}

bool ExportController::encodeYUVFrame(
    const uint8_t* yData,
    const uint8_t* uData,
    const uint8_t* vData)
{
    auto& ctx = m_ffmpegCtx;

    // Copy YUV planes to frame
    std::memcpy(ctx->frame->data[0], yData, m_config.width * m_config.height);
    std::memcpy(ctx->frame->data[1], uData, m_config.width / 2 * m_config.height / 2);
    std::memcpy(ctx->frame->data[2], vData, m_config.width / 2 * m_config.height / 2);

    // Set frame properties
    ctx->frame->pts = m_encodeFrameNumber++;

    // Send frame to encoder
    int ret = avcodec_send_frame(ctx->codecCtx, ctx->frame);
    if (ret < 0) {
        m_lastError = "Failed to send frame to encoder";
        return false;
    }

    // Receive and write packets
    while (true) {
        ret = avcodec_receive_packet(ctx->codecCtx, ctx->pkt);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            break;  // No more packets available
        }
        if (ret < 0) {
            m_lastError = "Failed to receive packet from encoder";
            return false;
        }

        // Set packet stream index and write
        ctx->pkt->stream_index = ctx->videoStream->index;
        av_packet_rescale_ts(ctx->pkt, ctx->codecCtx->time_base, ctx->videoStream->time_base);
        ret = av_interleaved_write_frame(ctx->formatCtx, ctx->pkt);
        if (ret < 0) {
            m_lastError = "Failed to write packet to file";
            return false;
        }

        av_packet_unref(ctx->pkt);
    }

    return true;
}

bool ExportController::finalizeFFmpegEncoder() {
    auto& ctx = m_ffmpegCtx;

    try {
        // Flush encoder
        int ret = avcodec_send_frame(ctx->codecCtx, nullptr);
        if (ret < 0 && ret != AVERROR_EOF) {
            m_lastError = "Failed to flush encoder";
            return false;
        }

        // Receive remaining packets
        while (true) {
            ret = avcodec_receive_packet(ctx->codecCtx, ctx->pkt);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                break;
            }
            if (ret < 0) {
                m_lastError = "Failed to receive final packet";
                return false;
            }

            ctx->pkt->stream_index = ctx->videoStream->index;
            av_packet_rescale_ts(ctx->pkt, ctx->codecCtx->time_base, ctx->videoStream->time_base);
            av_interleaved_write_frame(ctx->formatCtx, ctx->pkt);
            av_packet_unref(ctx->pkt);
        }

        // Write trailer
        ret = av_write_trailer(ctx->formatCtx);
        if (ret < 0) {
            m_lastError = "Failed to write trailer";
            return false;
        }

        std::cout << "FFmpeg encoder finalized successfully" << std::endl;
        return true;

    } catch (const std::exception& e) {
        m_lastError = std::string("Exception in finalizeFFmpegEncoder: ") + e.what();
        return false;
    }
}

}  // namespace VideoEngine

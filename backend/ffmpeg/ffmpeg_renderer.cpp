#include "ffmpeg_renderer.h"
#include <cstring>
#include <algorithm>
#include <iostream>
#include <cmath>

// FFmpeg C headers
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libavutil/avutil.h>
#include <libavutil/frame.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
}

namespace VideoEngine::Backend {

bool FFmpegRenderer::ffmpegInitialized_ = false;

struct FFmpegRenderer::FFmpegContext {
    // Output file context
    AVFormatContext* formatCtx = nullptr;
    AVStream* videoStream = nullptr;

    // Codec and encoding context
    AVCodecContext* codecCtx = nullptr;
    const AVCodec* codec = nullptr;

    // Frames
    AVFrame* frame = nullptr;        // YUV420p frame for encoding
    AVFrame* rgbFrame = nullptr;     // RGB intermediate frame
    uint8_t* rgbBuffer = nullptr;
    int rgbBufferSize = 0;

    // Color space conversion
    SwsContext* swsCtx = nullptr;

    // Packet for writing
    AVPacket* packet = nullptr;

    ~FFmpegContext() {
        if (packet) {
            av_packet_free(&packet);
            packet = nullptr;
        }

        if (frame) {
            av_frame_free(&frame);
            frame = nullptr;
        }

        if (rgbFrame) {
            av_frame_free(&rgbFrame);
            rgbFrame = nullptr;
        }

        if (rgbBuffer) {
            av_free(rgbBuffer);
            rgbBuffer = nullptr;
        }

        if (swsCtx) {
            sws_freeContext(swsCtx);
            swsCtx = nullptr;
        }

        if (codecCtx) {
            avcodec_free_context(&codecCtx);
            codecCtx = nullptr;
        }

        if (formatCtx) {
            if (!(formatCtx->oformat->flags & AVFMT_NOFILE)) {
                avio_closep(&formatCtx->pb);
            }
            avformat_free_context(formatCtx);
            formatCtx = nullptr;
        }
    }
};

FFmpegRenderer::FFmpegRenderer(uint32_t width, uint32_t height, uint32_t fps)
    : width_(width), height_(height), fps_(fps) {
    if (!initializeFFmpeg()) {
        throw FFmpegRenderException("Failed to initialize FFmpeg");
    }
    ctx_ = std::make_unique<FFmpegContext>();
}

FFmpegRenderer::~FFmpegRenderer() = default;

bool FFmpegRenderer::initializeFFmpeg() {
    if (ffmpegInitialized_) return true;
    // Modern FFmpeg (v4.0+) auto-registers codecs/muxers
    ffmpegInitialized_ = true;
    return true;
}

void FFmpegRenderer::render(const RenderGraph& renderGraph,
                           const std::string& outputPath) {
    RenderConfig defaultConfig;
    render(renderGraph, outputPath, defaultConfig, nullptr);
}

void FFmpegRenderer::render(const RenderGraph& renderGraph,
                           const std::string& outputPath,
                           const RenderConfig& config,
                           ProgressCallback progress) {
    if (width_ == 0 || height_ == 0 || fps_ == 0) {
        throw FFmpegRenderException("Invalid timeline resolution or frame rate");
    }

    std::cout << "[FFmpegRenderer] Starting encode...\n";
    std::cout << "[FFmpegRenderer] Resolution: " << width_ << "x" << height_ << "\n";
    std::cout << "[FFmpegRenderer] FPS: " << fps_ << "\n";
    std::cout << "[FFmpegRenderer] Output: " << outputPath << "\n";

    // ============ Initialize output format ============
    avformat_alloc_output_context2(&ctx_->formatCtx, nullptr, "mp4", outputPath.c_str());
    if (!ctx_->formatCtx) {
        throw FFmpegRenderException("Could not create output format context");
    }

    std::cout << "[FFmpegRenderer] Output format: " << ctx_->formatCtx->oformat->name << "\n";

    // ============ Find and setup codec ============
    ctx_->codec = avcodec_find_encoder_by_name(config.outputCodec.c_str());
    if (!ctx_->codec) {
        throw FFmpegRenderException("Codec not found: " + config.outputCodec);
    }

    std::cout << "[FFmpegRenderer] Codec: " << ctx_->codec->name << "\n";

    // Create codec context
    ctx_->codecCtx = avcodec_alloc_context3(ctx_->codec);
    if (!ctx_->codecCtx) {
        throw FFmpegRenderException("Could not allocate codec context");
    }

    // Setup codec parameters
    ctx_->codecCtx->width = width_;
    ctx_->codecCtx->height = height_;
    ctx_->codecCtx->time_base = {1, static_cast<int>(fps_)};
    ctx_->codecCtx->framerate = {static_cast<int>(fps_), 1};
    ctx_->codecCtx->pix_fmt = AV_PIX_FMT_YUV420P;
    ctx_->codecCtx->bit_rate = config.bitrate * 1000;  // kbps -> bps

    // x264 preset (0=slow/high-quality, 10=fast/lower-quality)
    if (config.outputCodec == "libx264") {
        int crf = 28 - config.preset;  // CRF: 18 (high) to 28 (low)
        av_opt_set(ctx_->codecCtx->priv_data, "crf", std::to_string(crf).c_str(), 0);
    }

    // ============ Create video stream ============
    ctx_->videoStream = avformat_new_stream(ctx_->formatCtx, ctx_->codec);
    if (!ctx_->videoStream) {
        throw FFmpegRenderException("Could not create video stream");
    }

    ctx_->videoStream->codecpar->codec_id = ctx_->codec->id;
    ctx_->videoStream->codecpar->codec_type = AVMEDIA_TYPE_VIDEO;
    ctx_->videoStream->codecpar->width = width_;
    ctx_->videoStream->codecpar->height = height_;
    ctx_->videoStream->codecpar->format = AV_PIX_FMT_YUV420P;
    ctx_->videoStream->time_base = {1, static_cast<int>(fps_)};

    // Open codec
    int ret = avcodec_open2(ctx_->codecCtx, ctx_->codec, nullptr);
    if (ret < 0) {
        char errbuf[256];
        av_strerror(ret, errbuf, sizeof(errbuf));
        throw FFmpegRenderException(std::string("Could not open codec: ") + errbuf);
    }

    avcodec_parameters_from_context(ctx_->videoStream->codecpar, ctx_->codecCtx);

    std::cout << "[FFmpegRenderer] Codec opened successfully\n";

    // ============ Allocate frames ============
    ctx_->frame = av_frame_alloc();
    ctx_->rgbFrame = av_frame_alloc();
    ctx_->packet = av_packet_alloc();

    if (!ctx_->frame || !ctx_->rgbFrame || !ctx_->packet) {
        throw FFmpegRenderException("Could not allocate frame buffers");
    }

    // Setup YUV420P frame
    ctx_->frame->format = AV_PIX_FMT_YUV420P;
    ctx_->frame->width = width_;
    ctx_->frame->height = height_;

    ret = av_frame_get_buffer(ctx_->frame, 32);
    if (ret < 0) {
        throw FFmpegRenderException("Could not allocate frame buffer");
    }

    // Allocate RGB buffer for compositing
    ctx_->rgbBufferSize = av_image_get_buffer_size(AV_PIX_FMT_RGB24, width_, height_, 1);
    ctx_->rgbBuffer = static_cast<uint8_t*>(av_malloc(ctx_->rgbBufferSize));
    if (!ctx_->rgbBuffer) {
        throw FFmpegRenderException("Could not allocate RGB buffer");
    }

    // Setup RGB frame
    ctx_->rgbFrame->format = AV_PIX_FMT_RGB24;
    ctx_->rgbFrame->width = width_;
    ctx_->rgbFrame->height = height_;
    av_image_fill_arrays(ctx_->rgbFrame->data, ctx_->rgbFrame->linesize,
                        ctx_->rgbBuffer, AV_PIX_FMT_RGB24, width_, height_, 1);

    std::cout << "[FFmpegRenderer] Frames allocated\n";

    // ============ Initialize color space conversion ============
    ctx_->swsCtx = sws_getContext(width_, height_, AV_PIX_FMT_RGB24,
                                 width_, height_, AV_PIX_FMT_YUV420P,
                                 SWS_BICUBIC, nullptr, nullptr, nullptr);
    if (!ctx_->swsCtx) {
        throw FFmpegRenderException("Could not initialize color space conversion");
    }

    // ============ Open output file ============
    if (!(ctx_->formatCtx->oformat->flags & AVFMT_NOFILE)) {
        ret = avio_open(&ctx_->formatCtx->pb, outputPath.c_str(), AVIO_FLAG_WRITE);
        if (ret < 0) {
            char errbuf[256];
            av_strerror(ret, errbuf, sizeof(errbuf));
            throw FFmpegRenderException(std::string("Could not open output file: ") + errbuf);
        }
    }

    // ============ Write file header ============
    ret = avformat_write_header(ctx_->formatCtx, nullptr);
    if (ret < 0) {
        char errbuf[256];
        av_strerror(ret, errbuf, sizeof(errbuf));
        throw FFmpegRenderException(std::string("Could not write header: ") + errbuf);
    }

    std::cout << "[FFmpegRenderer] Header written, starting frame encode loop...\n";

    // ============ Frame encoding loop ============
    // Calculate total frames from render items
    TimeMs totalDurationMs = 0;
    const auto& allItems = renderGraph.getAllItems();
    for (const auto& item : allItems) {
        if (item.endMs > totalDurationMs) {
            totalDurationMs = item.endMs;
        }
    }

    if (totalDurationMs <= 0) {
        throw FFmpegRenderException("Invalid timeline duration: no items or zero duration");
    }

    uint64_t totalFrames = (totalDurationMs * fps_) / 1000;

    std::cout << "[FFmpegRenderer] Total duration: " << totalDurationMs << "ms\n";
    std::cout << "[FFmpegRenderer] Total frames to encode: " << totalFrames << "\n";

    for (uint64_t frameNum = 0; frameNum < totalFrames; ++frameNum) {
        TimeMs frameTimeMs = (frameNum * 1000) / fps_;

        // Get visible items at this frame
        const auto& visibleItems = renderGraph.getItemsAtTime(frameTimeMs);

        // For now: create a simple test pattern (gradient)
        // In full implementation: decode clips, composite, apply effects
        uint8_t* rgb = ctx_->rgbBuffer;
        for (uint32_t y = 0; y < height_; ++y) {
            for (uint32_t x = 0; x < width_; ++x) {
                // Simple test pattern: gradient based on visible items count
                uint8_t intensity = static_cast<uint8_t>(
                    (visibleItems.size() > 0) ? 200 : 50
                );
                if (visibleItems.size() > 1) intensity = 100;

                // Add some variation based on position
                uint8_t r = static_cast<uint8_t>((x * 255) / width_);
                uint8_t g = static_cast<uint8_t>((y * 255) / height_);
                uint8_t b = intensity;

                rgb[0] = r;
                rgb[1] = g;
                rgb[2] = b;
                rgb += 3;
            }
        }

        // Convert RGB to YUV420P
        const uint8_t* srcData[] = {ctx_->rgbFrame->data[0]};
        int srcLinesize[] = {ctx_->rgbFrame->linesize[0]};
        sws_scale(ctx_->swsCtx, srcData, srcLinesize, 0, height_,
                 ctx_->frame->data, ctx_->frame->linesize);

        // Set frame presentation timestamp
        ctx_->frame->pts = frameNum;

        // Encode frame
        ret = avcodec_send_frame(ctx_->codecCtx, ctx_->frame);
        if (ret < 0) {
            char errbuf[256];
            av_strerror(ret, errbuf, sizeof(errbuf));
            throw FFmpegRenderException(std::string("Error sending frame: ") + errbuf);
        }

        // Receive encoded packets
        while (avcodec_receive_packet(ctx_->codecCtx, ctx_->packet) == 0) {
            av_packet_rescale_ts(ctx_->packet, ctx_->codecCtx->time_base,
                               ctx_->videoStream->time_base);
            ctx_->packet->stream_index = ctx_->videoStream->index;

            ret = av_interleaved_write_frame(ctx_->formatCtx, ctx_->packet);
            if (ret < 0) {
                char errbuf[256];
                av_strerror(ret, errbuf, sizeof(errbuf));
                throw FFmpegRenderException(std::string("Error writing frame: ") + errbuf);
            }

            av_packet_unref(ctx_->packet);
        }

        // Progress callback
        if (progress && frameNum % std::max(1UL, totalFrames / 100) == 0) {
            progress(frameNum, totalFrames);
        }

        if (frameNum % 30 == 0) {
            std::cout << "[FFmpegRenderer] Encoded " << frameNum << "/" << totalFrames
                     << " frames (" << (frameNum * 100 / totalFrames) << "%)\n";
        }
    }

    std::cout << "[FFmpegRenderer] Flushing encoder...\n";

    // ============ Flush encoder ============
    avcodec_send_frame(ctx_->codecCtx, nullptr);  // Signal end of input

    while (avcodec_receive_packet(ctx_->codecCtx, ctx_->packet) == 0) {
        av_packet_rescale_ts(ctx_->packet, ctx_->codecCtx->time_base,
                           ctx_->videoStream->time_base);
        ctx_->packet->stream_index = ctx_->videoStream->index;
        av_interleaved_write_frame(ctx_->formatCtx, ctx_->packet);
        av_packet_unref(ctx_->packet);
    }

    std::cout << "[FFmpegRenderer] Writing trailer...\n";

    // ============ Write file trailer ============
    ret = av_write_trailer(ctx_->formatCtx);
    if (ret < 0) {
        char errbuf[256];
        av_strerror(ret, errbuf, sizeof(errbuf));
        throw FFmpegRenderException(std::string("Error writing trailer: ") + errbuf);
    }

    std::cout << "[FFmpegRenderer] Encode complete! Output: " << outputPath << "\n";

    if (progress) {
        progress(totalFrames, totalFrames);
    }
}

FFmpegRenderer::DecodedFrame FFmpegRenderer::decodeClipFrame(
    const ClipPtr& clip, TimeMs offsetInClipMs) {
    DecodedFrame frame = {};
    if (!clip) return frame;
    // Decoding is handled by VideoDecoder + PreviewController pipeline.
    // This path is unused in the current architecture.
    (void)offsetInClipMs;
    return frame;
}

void FFmpegRenderer::compositeFrames(const std::vector<DecodedFrame>& frames,
                                    uint8_t* outFrame, int outLinesize) {
    // Compositing is handled by PreviewRenderer (GPU path).
    (void)frames; (void)outFrame; (void)outLinesize;
}

FFmpegRenderer::DecodedFrame FFmpegRenderer::yuvToRgba(
    const DecodedFrame& yuvFrame) {
    // YUV→RGB conversion is done in GPU shader (yuv_to_rgb.frag).
    (void)yuvFrame;
    return {};
}

FFmpegRenderer::DecodedFrame FFmpegRenderer::rgbaToOutput(
    const DecodedFrame& rgbaFrame,
    const std::string& outputPixelFormat) {
    // Format conversion for export is handled by ExportController via libswscale.
    (void)rgbaFrame; (void)outputPixelFormat;
    return {};
}

} // namespace VideoEngine::Backend

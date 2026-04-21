#!/bin/bash
# Quick Reference: FFmpeg Encoder Usage

# ============ Build ============
cd /home/am/video_engine_core
rm -rf build && mkdir build && cd build
cmake ..
make -j

# ============ Run Tests ============
./video_engine
# Output: build/output.mp4 (test pattern video)

# ============ Inspect Output ============
ffprobe -v error -select_streams v:0 \
  -show_entries stream=width,height,r_frame_rate,duration,codec_name \
  output.mp4

# Expected Output:
# h264
# 1920
# 1080
# 30/1
# ~8.0 seconds

# ============ C++ API Usage ============
# Header:
#   #include "backend/ffmpeg/ffmpeg_renderer.h"
#   using namespace VideoEngine::Backend;

# Basic Usage:
#   Timeline timeline;
#   timeline.addClip(clip1);
#   timeline.addClip(clip2);
#
#   RenderGraph graph;
#   graph.buildFromTimeline(timeline);
#
#   FFmpegRenderer renderer(1920, 1080, 30);
#   FFmpegRenderer::RenderConfig config;
#   config.bitrate = 5000;      // kbps
#   config.preset = 3;          // 0=slow/best to 10=fast
#
#   renderer.render(graph, "output.mp4", config,
#                  [](int frame, int total) {
#                      printf("Frame %d/%d\n", frame, total);
#                  });

# ============ Configuration ============
# RenderConfig struct:
#   - outputCodec: "libx264" (H.264), "libx265" (HEVC), etc.
#   - outputPixelFormat: "yuv420p" (default, best compatibility)
#   - bitrate: 5000 (kbps, quality control)
#   - preset: 3 (0=slow/high-quality, 10=fast/lower-quality)

# ============ Key Features ============
# ✓ H.264 MP4 encoding at any resolution
# ✓ 1920×1080 @ 30fps (or configurable in constructor)
# ✓ Frame-accurate timing from RenderGraph
# ✓ RGB24 → YUV420P color space conversion
# ✓ Progress callback support
# ✓ Comprehensive error handling with av_strerror()
# ✓ Complete resource cleanup (no memory leaks)

# ============ Error Handling ============
# All errors thrown as FFmpegRenderException
#   try {
#       renderer.render(graph, "output.mp4");
#   } catch (const FFmpegRenderException& e) {
#       std::cerr << "Encode failed: " << e.what() << std::endl;
#   }

# ============ Output Files ============
# Location: /home/am/video_engine_core/build/output.mp4
# Size: ~115 KB (240 frames @ 5 Mbps)
# Codec: H.264 (libx264)
# Duration: 8 seconds
# Format: ISO 14496-12 MP4 Base Media

# ============ Compilation ============
# Dependencies:
#   - libavformat (file I/O)
#   - libavcodec (encoder)
#   - libswscale (color space conversion)
#   - libavutil (utilities)
# 
# Automatically detected by CMake via pkg-config
# Set FFMPEG_AVAILABLE=1 if found


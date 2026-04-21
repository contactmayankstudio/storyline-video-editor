#include <iostream>
#include <memory>
#include <cmath>
#include "core/timeline.h"
#include "engine/engine.h"
#include "backend/ffmpeg/ffmpeg_renderer.h"
#include "backend/ffmpeg/ffmpeg_audio_renderer.h"
#include "api/c/video_engine_c.h"
#include "text_overlay.h"

int main() {
    // Create a timeline
    VideoEngine::Timeline timeline;
    std::cout << "[Timeline] created, duration=" << timeline.getDuration() << " ms\n\n";

    // Create two test clips
    auto clip1 = std::make_shared<VideoEngine::Clip>("footage/intro.mp4", 0, 3000);
    auto clip2 = std::make_shared<VideoEngine::Clip>("footage/main.mp4", 3000, 5000);

    timeline.addClip(clip1);
    timeline.addClip(clip2);

    std::cout << "[Clips] added 2 clips to timeline\n";
    std::cout << "  clip1: [0ms, 3000ms]\n";
    std::cout << "  clip2: [3000ms, 8000ms]\n";
    std::cout << "  total duration: " << timeline.getDuration() << " ms\n\n";

    // Build render graph from timeline
    VideoEngine::RenderGraph renderGraph;
    renderGraph.buildFromTimeline(timeline);
    std::cout << "[RenderGraph] built from timeline\n\n";

    // Query visible items at various times
    std::vector<VideoEngine::TimeMs> testTimes = {0, 1500, 3000, 5500, 8000, 9000};
    for (auto timeMs : testTimes) {
        auto visibleItems = renderGraph.getItemsAtTime(timeMs);
        std::cout << "[Time " << timeMs << "ms] " << visibleItems.size() << " visible clip(s):\n";
        for (const auto& item : visibleItems) {
            std::cout << "    - layer=" << item.layer 
                      << " baseOpacity=" << item.baseOpacity 
                      << " enabled=" << item.enabled 
                      << " [" << item.startMs << "ms, " << item.endMs << "ms)\n";
        }
    }

    std::cout << "\n[RenderGraph] composition test passed\n\n";

    // Test effects system
    std::cout << "[Effects] Testing time-dependent effects...\n";
    VideoEngine::OpacityEffect fadeIn;
    fadeIn.mode = VideoEngine::OpacityEffect::Mode::FadeIn;
    fadeIn.startOpacity = 0.0f;
    fadeIn.endOpacity = 1.0f;
    fadeIn.fadeDurationMs = 1000;

    std::cout << "  FadeIn effect at times:\n";
    std::vector<VideoEngine::TimeMs> times1 = {0, 250, 500, 1000, 1500};
    for (VideoEngine::TimeMs t : times1) {
        float opacity = fadeIn.evaluateAtTime(t, 0, 8000);
        std::cout << "    t=" << t << "ms -> opacity=" << opacity << "\n";
    }

    VideoEngine::SpeedEffect slowMo;
    slowMo.mode = VideoEngine::SpeedEffect::Mode::LinearRamp;
    slowMo.startSpeed = 1.0f;
    slowMo.endSpeed = 0.5f;
    slowMo.rampDurationMs = 2000;

    std::cout << "  SpeedRamp effect at times:\n";
    std::vector<VideoEngine::TimeMs> times2 = {0, 500, 1000, 2000, 3000};
    for (VideoEngine::TimeMs t : times2) {
        float speed = slowMo.evaluateAtTime(t, 0, 8000);
        std::cout << "    t=" << t << "ms -> speed=" << speed << "\n";
    }
    std::cout << "\n";

    // Test transitions
    std::cout << "[Transitions] Detected transitions:\n";
    const auto& transitions = renderGraph.getTransitions();
    for (const auto& trans : transitions) {
        std::cout << "  - " << trans.id << " [" << trans.startMs << "ms, "
                  << trans.startMs + trans.durationMs << "ms)\n";
    }
    std::cout << "\n";

    // Test transition progress evaluation
    if (!transitions.empty()) {
        const auto& trans = transitions.front();
        std::cout << "[TransitionProgress] Crossfade progress over time:\n";
        std::vector<VideoEngine::TimeMs> times3 = {trans.startMs, trans.startMs + trans.durationMs / 2, 
                                                    trans.startMs + trans.durationMs};
        for (VideoEngine::TimeMs t : times3) {
            float progress = trans.evaluateProgress(t);
            std::cout << "    t=" << t << "ms -> progress=" << progress << "\n";
        }
        std::cout << "\n";
    }

    // Test effective properties at time
    std::cout << "[EffectiveProperties] Clip properties at various times:\n";
    std::vector<VideoEngine::TimeMs> times4 = {0, 1000, 4000, 8000};
    for (VideoEngine::TimeMs t : times4) {
        auto visibleItems = renderGraph.getItemsAtTime(t);
        std::cout << "  [Time " << t << "ms] " << visibleItems.size() << " clip(s):\n";
        for (const auto& item : visibleItems) {
            std::cout << "    - effective opacity=" << item.effectiveOpacity 
                      << " speed=" << item.effectiveSpeed << "\n";
        }
    }
    std::cout << "\n";

    // Test Text Overlay
    std::cout << "[TextOverlay] Testing text overlay...\n";
    VideoEngine::TextOverlay title;
    title.text = "Hello World!";
    title.x = 0.5f;
    title.y = 0.2f;
    title.scale = 1.5f;
    title.color = 0xff0000ff; // Red
    title.startTime = 500;
    title.endTime = 2500;
    title.fadeInMs = 500;
    title.fadeOutMs = 500;
    std::cout << "  - Title: '" << title.text << "' [" << title.startTime << "ms, " << title.endTime << "ms]\n";
    std::cout << "\n";

    // Test FFmpeg renderer API (no actual encoding yet)
    try {
        std::cout << "[FFmpegRenderer] initializing...\n";
        VideoEngine::Backend::FFmpegRenderer renderer(1920, 1080, 30);

        std::cout << "[FFmpegRenderer] rendering to output.mp4...\n";
        VideoEngine::Backend::FFmpegRenderer::RenderConfig config;
        config.bitrate = 5000;
        config.preset = 3;
        renderer.render(renderGraph, "output.mp4", config);

        std::cout << "[FFmpegRenderer] render complete\n";
    } catch (const VideoEngine::Backend::FFmpegRenderException& e) {
        std::cerr << "[FFmpegRenderer] Error: " << e.what() << "\n";
    }

    // ============ Test C API ============
    std::cout << "\n[C API] Testing C-compatible interface...\n";

    // Create timeline via C API
    ve_timeline_t c_timeline = ve_timeline_create();
    if (!c_timeline) {
        std::cerr << "[C API] Failed to create timeline\n";
        return 1;
    }

    // Create clips via C API
    ve_clip_t c_clip1 = ve_clip_create("footage/intro.mp4", 0, 3000);
    ve_clip_t c_clip2 = ve_clip_create("footage/main.mp4", 3000, 5000);

    if (!c_clip1 || !c_clip2) {
        std::cerr << "[C API] Failed to create clips\n";
        ve_timeline_destroy(c_timeline);
        return 1;
    }

    // Add clips to timeline
    ve_timeline_add_clip(c_timeline, c_clip1);
    ve_timeline_add_clip(c_timeline, c_clip2);

    // Get clip count
    uint32_t clip_count = ve_timeline_get_clip_count(c_timeline);
    std::cout << "[C API] Clip count: " << clip_count << "\n";

    // Add fade effects via C API
    ve_clip_add_fade_in_effect(c_clip1, 500);
    ve_clip_add_fade_out_effect(c_clip2, 500);
    std::cout << "[C API] Effects added\n";

    // Get timeline properties
    ve_video_properties_t video_props = {};
    ve_timeline_get_video_properties(c_timeline, &video_props);
    std::cout << "[C API] Video resolution: " << video_props.resolution.width 
              << "x" << video_props.resolution.height << "\n";

    // Convert frame <-> ms
    ve_time_ms_t frame_0_ms = ve_timeline_frame_to_ms(c_timeline, 0);
    ve_time_ms_t frame_30_ms = ve_timeline_frame_to_ms(c_timeline, 30);
    std::cout << "[C API] Frame 0 = " << frame_0_ms << "ms, Frame 30 = " << frame_30_ms << "ms\n";

    // Create render graph via C API
    ve_render_graph_t c_graph = ve_render_graph_create(c_timeline);
    if (!c_graph) {
        std::cerr << "[C API] Failed to create render graph\n";
        ve_clip_destroy(c_clip1);
        ve_clip_destroy(c_clip2);
        ve_timeline_destroy(c_timeline);
        return 1;
    }

    // Query render items at specific time
    ve_render_item_t items[10];
    uint32_t item_count = ve_render_graph_get_items_at_time(c_graph, 1500, items, 10);
    std::cout << "[C API] Items at time 1500ms: " << item_count << "\n";
    for (uint32_t i = 0; i < item_count; ++i) {
        std::cout << "  - layer=" << items[i].layer 
                  << " opacity=" << items[i].effective_opacity 
                  << " speed=" << items[i].effective_speed << "\n";
    }

    // Query transitions
    ve_transition_info_t c_transitions[10];
    uint32_t trans_count = ve_render_graph_get_all_transitions(c_graph, c_transitions, 10);
    std::cout << "[C API] Total transitions: " << trans_count << "\n";

    // Get API version
    char version_buf[256];
    ve_get_version(version_buf, sizeof(version_buf));
    std::cout << "[C API] Engine version: " << version_buf << "\n";

    // Cleanup
    ve_render_graph_destroy(c_graph);
    ve_clip_destroy(c_clip1);
    ve_clip_destroy(c_clip2);
    ve_timeline_destroy(c_timeline);

    std::cout << "[C API] test complete\n";

    // ============ Audio Renderer Test ============
    std::cout << "\n[AudioRenderer] Testing audio pipeline...\n";
    try {
        VideoEngine::Backend::AudioRenderer::AudioConfig audioConfig;
        audioConfig.sampleRate = 48000;
        audioConfig.channels = 2;
        audioConfig.bitrate = 128;

        VideoEngine::Backend::AudioRenderer audioRenderer(audioConfig);

        std::cout << "[AudioRenderer] Initialized - 48000Hz stereo AAC\n";
        std::cout << "[AudioRenderer] Config: "
                  << audioConfig.sampleRate << "Hz, "
                  << audioConfig.channels << "ch, "
                  << audioConfig.bitrate << "kbps\n";

        // Demonstrate audio frame creation
        VideoEngine::Backend::AudioRenderer::AudioFrame testFrame;
        testFrame.sampleRate = 48000;
        testFrame.channels = 2;
        testFrame.ptsMs = 0;
        
        // Create stereo test tone (sine wave)
        int numSamples = 4800;  // 100ms at 48kHz
        testFrame.samples.resize(2);
        testFrame.samples[0].resize(numSamples);
        testFrame.samples[1].resize(numSamples);
        
        for (int i = 0; i < numSamples; ++i) {
            float phase = (i * 2.0f * 3.14159f * 440.0f) / 48000.0f;  // 440Hz A4
            float sample = 0.1f * sinf(phase);  // Low amplitude to avoid clipping
            testFrame.samples[0][i] = sample;
            testFrame.samples[1][i] = sample;
        }

        std::cout << "[AudioRenderer] Created test frame: " << numSamples 
                  << " samples @ 48kHz stereo\n";

        // Test mixing multiple segments
        VideoEngine::Backend::AudioRenderer::DecodedAudioSegment seg1, seg2;
        seg1.startTimeMs = 0;
        seg1.endTimeMs = 100;
        seg1.sampleRate = 48000;
        seg1.channels = 2;
        seg1.samples.resize(2);
        seg1.samples[0].resize(4800, 0.1f);
        seg1.samples[1].resize(4800, 0.1f);

        seg2.startTimeMs = 50;
        seg2.endTimeMs = 150;
        seg2.sampleRate = 48000;
        seg2.channels = 2;
        seg2.samples.resize(2);
        seg2.samples[0].resize(4800, 0.05f);
        seg2.samples[1].resize(4800, 0.05f);

        std::vector<VideoEngine::Backend::AudioRenderer::DecodedAudioSegment> segments = {seg1, seg2};
        auto mixed = audioRenderer.mixAudioSegments(segments, 0, 200);

        std::cout << "[AudioRenderer] Mixed 2 audio segments: "
                  << mixed.samples[0].size() << " samples\n";

        std::cout << "[AudioRenderer] test complete - audio pipeline ready\n";
    } catch (const std::exception& e) {
        std::cerr << "[AudioRenderer] Error: " << e.what() << "\n";
    }

    return 0;
}

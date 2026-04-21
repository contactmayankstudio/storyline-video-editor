#pragma once

/**
 * Example: GPU Preview Renderer Usage
 * 
 * This file demonstrates how to integrate and use the GPU-based OpenGL preview renderer.
 * It is NOT part of the build - it's for reference and documentation purposes.
 */

#include <iostream>
#include <memory>
#include "backend/gpu/gpu_backend.h"
#include "engine/engine.h"

namespace VideoEngine::Examples {

/**
 * Example 1: Basic headless preview rendering
 */
void example_headless_rendering() {
    std::cout << "\n=== Example 1: Headless Preview Rendering ===\n";

    // Create timeline
    auto timeline = std::make_shared<Timeline>();
    
    // Add some clips
    auto clip1 = std::make_shared<Clip>("footage/intro.mp4", 0, 3000);
    auto clip2 = std::make_shared<Clip>("footage/main.mp4", 3000, 5000);
    timeline->addClip(clip1);
    timeline->addClip(clip2);
    
    std::cout << "Timeline created with 2 clips\n";

    // Build render graph
    RenderGraph renderGraph;
    renderGraph.buildFromTimeline(*timeline);
    
    std::cout << "RenderGraph built\n";

    // Create GPU preview renderer (headless mode)
    auto renderer = std::make_shared<GPU::PreviewRenderer>(
        1920,  // width
        1080,  // height
        GPU::PreviewRenderer::RenderMode::Headless,
        false  // debug mode disabled
    );

    if (!renderer->isValid()) {
        std::cerr << "Failed to initialize GPU renderer\n";
        return;
    }

    std::cout << "GPU renderer initialized (headless)\n";

    // Render at specific time points
    std::vector<TimeMs> timePoints = {0, 1500, 3000, 5500};
    
    for (auto timeMs : timePoints) {
        renderer->renderFrame(renderGraph, timeMs);
        std::cout << "Rendered frame at " << timeMs << " ms\n";
        
        // Get output texture
        auto texture = renderer->getFramebufferTexture();
        if (texture) {
            std::cout << "  Output texture: " << texture->getWidth() 
                      << "x" << texture->getHeight() << "\n";
        }
    }

    std::cout << "Headless rendering complete\n";
}

/**
 * Example 2: Real-time preview window with continuous rendering
 */
void example_windowed_rendering() {
    std::cout << "\n=== Example 2: Windowed Preview Rendering ===\n";

    // Create timeline
    auto timeline = std::make_shared<Timeline>();
    auto clip = std::make_shared<Clip>("footage/video.mp4", 0, 8000);
    timeline->addClip(clip);

    RenderGraph renderGraph;
    renderGraph.buildFromTimeline(*timeline);

    // Create windowed GPU renderer
    auto renderer = std::make_shared<GPU::PreviewRenderer>(
        1280,  // width
        720,   // height
        GPU::PreviewRenderer::RenderMode::Windowed,
        true   // debug mode enabled
    );

    if (!renderer->isValid()) {
        std::cerr << "Failed to initialize GPU renderer\n";
        return;
    }

    // Set clear color (dark gray background)
    renderer->setClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    std::cout << "GPU renderer initialized (windowed, 1280x720)\n";

    // Simulate rendering loop at 30 fps for 8 seconds
    const int fps = 30;
    const int frameDurationMs = 1000 / fps;
    const TimeMs totalDurationMs = 8000;

    int frameCount = 0;
    for (TimeMs timeMs = 0; timeMs < totalDurationMs; timeMs += frameDurationMs) {
        renderer->renderFrame(renderGraph, timeMs);
        renderer->swapBuffers();  // Display on screen
        
        frameCount++;
        if (frameCount % 30 == 0) {
            std::cout << "Rendered " << frameCount << " frames at " << timeMs << " ms\n";
        }
    }

    std::cout << "Windowed rendering complete (" << frameCount << " frames)\n";
}

/**
 * Example 3: Rendering with effect evaluation
 */
void example_effects_rendering() {
    std::cout << "\n=== Example 3: Rendering with Effects ===\n";

    auto timeline = std::make_shared<Timeline>();
    
    auto clip = std::make_shared<Clip>("footage/clip.mp4", 0, 4000);
    
    // Add opacity effect (fade in)
    auto fadeInEffect = std::make_shared<OpacityEffect>();
    fadeInEffect->mode = OpacityEffect::Mode::FadeIn;
    fadeInEffect->fadeStartMs = 0;
    fadeInEffect->fadeDurationMs = 1000;
    fadeInEffect->startOpacity = 0.0f;
    fadeInEffect->endOpacity = 1.0f;
    
    // Note: In a full implementation, effects would be added to clips
    // and evaluated by RenderGraph when querying items

    timeline->addClip(clip);

    RenderGraph renderGraph;
    renderGraph.buildFromTimeline(*timeline);

    auto renderer = std::make_shared<GPU::PreviewRenderer>(1920, 1080);

    // Render frames showing opacity fade-in
    for (TimeMs timeMs = 0; timeMs <= 2000; timeMs += 100) {
        renderer->renderFrame(renderGraph, timeMs);
        
        auto items = renderGraph.getItemsAtTime(timeMs);
        if (!items.empty()) {
            std::cout << "Frame at " << timeMs << " ms: "
                      << "opacity=" << items[0].effectiveOpacity << "\n";
        }
    }

    std::cout << "Effects rendering complete\n";
}

/**
 * Example 4: Batch rendering to disk
 */
void example_batch_rendering_to_disk() {
    std::cout << "\n=== Example 4: Batch Rendering (Texture Readback) ===\n";

    auto timeline = std::make_shared<Timeline>();
    auto clip = std::make_shared<Clip>("footage/sequence.mp4", 0, 2000);
    timeline->addClip(clip);

    RenderGraph renderGraph;
    renderGraph.buildFromTimeline(*timeline);

    auto renderer = std::make_shared<GPU::PreviewRenderer>(
        1920, 1080, 
        GPU::PreviewRenderer::RenderMode::Headless
    );

    // Render and readback frames for every 100ms
    for (TimeMs timeMs = 0; timeMs <= 2000; timeMs += 100) {
        renderer->renderFrame(renderGraph, timeMs);
        
        auto texture = renderer->getFramebufferTexture();
        if (!texture) {
            std::cerr << "Failed to get output texture\n";
            continue;
        }

        std::cout << "Frame " << (timeMs / 100) 
                  << ": Rendered to texture (" 
                  << texture->getWidth() << "x" << texture->getHeight() << ")\n";

        // In a real implementation, you would:
        // 1. Bind the texture
        // 2. Use glReadPixels() to read RGBA data
        // 3. Encode as PNG/JPG using a library (e.g., stb_image_write)
        // Example:
        //   std::vector<uint8_t> pixels(1920 * 1080 * 4);
        //   glReadPixels(0, 0, 1920, 1080, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
        //   stbi_write_png("frame_00001.png", 1920, 1080, 4, pixels.data(), 1920 * 4);
    }

    std::cout << "Batch rendering complete\n";
}

/**
 * Example 5: Multi-layer composition
 */
void example_multilayer_rendering() {
    std::cout << "\n=== Example 5: Multi-Layer Composition ===\n";

    auto timeline = std::make_shared<Timeline>();
    
    // Layer 0 (background)
    auto bgClip = std::make_shared<Clip>("footage/background.mp4", 0, 5000);
    
    // Layer 1 (foreground overlay)
    auto overlayClip = std::make_shared<Clip>("footage/overlay.mp4", 1000, 4000);

    timeline->addClip(bgClip);
    timeline->addClip(overlayClip);

    RenderGraph renderGraph;
    renderGraph.buildFromTimeline(*timeline);

    auto renderer = std::make_shared<GPU::PreviewRenderer>(1920, 1080);

    // Render various points in the timeline
    std::vector<TimeMs> timePoints = {0, 500, 1500, 3000, 5500};
    
    for (auto timeMs : timePoints) {
        renderer->renderFrame(renderGraph, timeMs);
        
        auto items = renderGraph.getItemsAtTime(timeMs);
        std::cout << "At " << timeMs << " ms: " << items.size() << " visible items\n";
        
        for (const auto& item : items) {
            std::cout << "  - Layer " << item.layer 
                      << " (opacity=" << item.effectiveOpacity << ")\n";
        }
    }

    std::cout << "Multi-layer rendering complete\n";
}

} // namespace VideoEngine::Examples

/*
 * How to use these examples:
 * 
 * 1. Uncomment the example function call in main.cpp
 * 2. Ensure dependencies are installed:
 *    - sudo apt-get install libgl1-mesa-dev libx11-dev libglm-dev
 * 3. Build: cmake .. && make
 * 4. Run with display (for windowed mode):
 *    - export DISPLAY=:0
 *    - ./video_engine
 * 
 * For headless rendering, DISPLAY variable is not required.
 */

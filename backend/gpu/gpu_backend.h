#pragma once

/**
 * GPU Backend Header
 * Convenience header to include all GPU rendering components.
 * 
 * Usage:
 *   #include "backend/gpu/gpu_backend.h"
 *   
 *   auto renderer = std::make_shared<VideoEngine::GPU::PreviewRenderer>(
 *       1920, 1080,
 *       VideoEngine::GPU::PreviewRenderer::RenderMode::Headless
 *   );
 *   
 *   renderer->renderFrame(renderGraph, timeMs);
 */

#include "backend/gpu/gl_context.h"
#include "backend/gpu/shader_program.h"
#include "backend/gpu/shader_loader.h"
#include "backend/gpu/texture.h"
#include "backend/gpu/quad_mesh.h"
#include "backend/gpu/preview_renderer.h"

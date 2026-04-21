# GPU-Based Real-Time Preview Renderer (OpenGL)

## Overview

The GPU-based preview renderer provides real-time, hardware-accelerated rendering for video composition preview using OpenGL 3.3 core profile. This renderer is designed for **PREVIEW ONLY** purposes and integrates seamlessly with the existing RenderGraph composition system.

### Key Features

- **OpenGL 3.3 Core Profile**: Modern GPU rendering with GLSL shaders
- **Off-Screen Rendering**: Headless mode for batch processing and testing
- **Windowed Rendering**: Real-time preview in an X11 window (Linux)
- **Layer Ordering**: Clips rendered back-to-front by layer
- **Opacity Support**: Per-clip opacity effects with shader-based blending
- **No FFmpeg**: Pure GPU rendering, no video codec dependencies
- **Android NDK Compatible**: Architecture designed for cross-platform adaptation

---

## Architecture

### Component Hierarchy

```
PreviewRenderer (main interface)
├── GLContext (GL initialization & management)
├── Framebuffer (FBO for off-screen rendering)
│   ├── Texture (color attachment)
│   └── Texture (depth attachment)
├── ShaderProgram (GLSL compilation & uniforms)
├── QuadMesh (vertex/index buffers for quad rendering)
└── TextureCache (clip textures - placeholder for now)
```

### Data Flow

```
RenderGraph (timeline composition)
    ↓
PreviewRenderer::renderFrame(renderGraph, timeMs)
    ↓
GLContext::makeCurrent()
    ↓
Framebuffer::bind()
    ↓
ShaderProgram::use() + Texture::bind() + QuadMesh::render()
    ↓
FBO color texture (output)
```

---

## Component Details

### GLContext

**Purpose**: Manages OpenGL context lifecycle and X11/GLX integration.

**Features**:
- GLX 1.3+ initialization
- Supports headless (pbuffer) and windowed (X11 window) modes
- GL 3.3 core context creation with fallback to compatibility mode
- Configurable debug output

**Usage**:
```cpp
auto context = std::make_shared<VideoEngine::GPU::GLContext>(
    1920, 1080,
    VideoEngine::GPU::GLContext::RenderMode::Headless,
    true  // debug mode
);
context->makeCurrent();
```

**Linux Dependencies**:
- X11/Xlib (X11 windowing)
- GLX (OpenGL extension to X)
- libGL (OpenGL library)

---

### ShaderProgram

**Purpose**: Manages GLSL shader compilation, linking, and uniform updates.

**Built-in Shaders**:
- **Vertex Shader**: Transforms quads with projection/view/model matrices
- **Fragment Shader**: Applies texture sampling and opacity blending

**Uniform Support**:
```glsl
uniform mat4 projection;   // Orthographic projection
uniform mat4 view;         // View matrix (typically identity)
uniform mat4 model;        // Per-item model transform
uniform sampler2D tex0;    // Color texture
uniform float opacity;     // Item opacity [0..1]
```

**Supported Uniform Types**:
- `setUniform1f()`, `setUniform1i()`
- `setUniform2f()`, `setUniform3f()`, `setUniform4f()`
- `setUniformMatrix4fv()`, `setUniformMatrix3fv()`

---

### Texture & Framebuffer

**Texture**:
- Wraps GL texture objects
- Supports multiple formats (RGB8, RGBA8, RGBA16F, RGBA32F, R8, DEPTH24)
- Configurable wrap modes (Clamp, Repeat, Mirror)
- Configurable filter modes (Nearest, Linear)

**Framebuffer**:
- Off-screen rendering target
- Color + depth attachments
- Returns color texture for readback or display

**Usage**:
```cpp
auto fbo = std::make_shared<VideoEngine::GPU::Framebuffer>(1920, 1080, true);
fbo->bind();
// ... render ...
fbo->unbind();
auto colorTexture = fbo->getColorTexture();
```

---

### QuadMesh

**Purpose**: VAO/VBO for rendering textured quads (each clip).

**Geometry**:
- 4 vertices in normalized device coordinates (NDC)
- Quad covers [-1, 1] in both X and Y
- Includes texture coordinates [0, 1]

**Per-Vertex Format**:
```
Position:  vec3 (x, y, z in NDC)
TexCoord:  vec2 (u, v)
```

---

### PreviewRenderer

**Purpose**: Main rendering interface - composes clips and renders frame.

**Public API**:
```cpp
PreviewRenderer(uint32_t width, uint32_t height, 
                RenderMode mode = RenderMode::Headless,
                bool debugMode = false);

void renderFrame(const RenderGraph& renderGraph, TimeMs timeMs);
const TexturePtr& getFramebufferTexture() const;
void setClearColor(float r, float g, float b, float a);
void swapBuffers();
bool isValid() const;
```

**Rendering Pipeline**:
1. Query `renderGraph.getItemsAtTime(timeMs)` → sorted RenderItems
2. Bind framebuffer
3. Clear with background color
4. For each visible item (back-to-front):
   - Bind texture
   - Calculate model transform (position, scale, layer offset)
   - Set opacity uniform
   - Render quad
5. Unbind framebuffer
6. Swap buffers (windowed mode only)

**Output**: 
- Color texture accessible via `getFramebufferTexture()`
- Can be read back via glReadPixels() for disk output
- Can be displayed in windowed mode

---

## Integration Example

### Basic Preview Rendering

```cpp
#include "backend/gpu/gpu_backend.h"
#include "engine/engine.h"

// Create timeline and add clips
VideoEngine::Timeline timeline;
auto clip1 = std::make_shared<VideoEngine::Clip>("video.mp4", 0, 3000);
timeline.addClip(clip1);

// Build composition graph
VideoEngine::RenderGraph renderGraph;
renderGraph.buildFromTimeline(timeline);

// Create GPU renderer
auto renderer = std::make_shared<VideoEngine::GPU::PreviewRenderer>(
    1920, 1080,
    VideoEngine::GPU::PreviewRenderer::RenderMode::Headless
);

// Render at specific time
VideoEngine::TimeMs timeMs = 1500;  // 1.5 seconds
renderer->renderFrame(renderGraph, timeMs);

// Get output texture
auto colorTexture = renderer->getFramebufferTexture();
```

### Real-Time Preview Loop

```cpp
// Render sequence at 30 fps
for (VideoEngine::TimeMs timeMs = 0; timeMs < 8000; timeMs += 33) {
    renderer->renderFrame(renderGraph, timeMs);
    
    // Option 1: Read pixels for output
    auto texture = renderer->getFramebufferTexture();
    std::vector<uint8_t> pixels(1920 * 1080 * 4);
    glReadPixels(0, 0, 1920, 1080, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
    
    // Option 2: Display in window (windowed mode)
    // renderer->swapBuffers();
}
```

---

## Building

### CMake Configuration

Add to `CMakeLists.txt`:

```cmake
# GPU Backend with OpenGL
find_package(OpenGL REQUIRED)
find_package(X11 REQUIRED)
find_package(glm REQUIRED)

target_sources(video_engine PRIVATE
    backend/gpu/gl_context.cpp
    backend/gpu/shader_program.cpp
    backend/gpu/texture.cpp
    backend/gpu/quad_mesh.cpp
    backend/gpu/preview_renderer.cpp
)

target_include_directories(video_engine PUBLIC
    ${OPENGL_INCLUDE_DIR}
    ${X11_INCLUDE_DIR}
)

target_link_libraries(video_engine
    ${OPENGL_LIBRARIES}
    ${X11_LIBRARIES}
    glm::glm
)

# Link to GLX (usually part of OPENGL_LIBRARIES, but explicit for clarity)
target_link_libraries(video_engine GLX)
```

### Linux Dependencies

```bash
# Debian/Ubuntu
sudo apt-get install libgl1-mesa-dev libx11-dev libglm-dev

# Fedora/RHEL
sudo dnf install mesa-libGL-devel libX11-devel glm-devel

# Arch
sudo pacman -S mesa libx11 glm
```

---

## Shader Customization

### Extending the Fragment Shader

For advanced effects (e.g., color grading, blur):

```glsl
#version 330 core

in VS_OUT {
    vec2 texCoord;
} fs_in;

uniform sampler2D tex0;
uniform float opacity;
uniform float brightness;
uniform vec3 colorTint;

out vec4 FragColor;

void main() {
    vec4 texColor = texture(tex0, fs_in.texCoord);
    
    // Apply brightness
    texColor.rgb *= brightness;
    
    // Apply color tint
    texColor.rgb *= colorTint;
    
    // Apply opacity
    texColor.a *= opacity;
    
    FragColor = texColor;
}
```

---

## Performance Considerations

1. **Texture Caching**: Currently uses placeholder textures. Implement proper clip texture loading/caching for production.
2. **Batch Rendering**: Consider GPU instancing for many clips at similar z-depth.
3. **Async Readback**: Use PBO (pixel buffer objects) for non-blocking texture readback.
4. **MRT (Multiple Render Targets)**: Render to multiple textures for post-processing chains.

---

## Limitations & Future Work

### Current Limitations

- Placeholder texture system (no actual clip texture loading)
- No transition/blend mode support in shaders
- No video decoding (use RenderGraph output)
- Basic layer ordering (no z-buffer depth compositing)

### Future Enhancements

1. **Real Texture Loading**: Integrate with FFmpeg or hwdecoder for GPU-accelerated video
2. **Advanced Blending**: Per-layer blend modes (multiply, screen, overlay, etc.)
3. **Effects Pipeline**: Custom shaders for color correction, filters, animations
4. **Audio Preview**: Waveform rendering overlay
5. **Android Support**: NDK/EGL backend alongside X11/GLX
6. **Compute Shaders**: GPU-based effects and transitions

---

## Debugging

### Enable Debug Output

```cpp
auto renderer = std::make_shared<VideoEngine::GPU::PreviewRenderer>(
    1920, 1080,
    VideoEngine::GPU::PreviewRenderer::RenderMode::Headless,
    true  // debugMode = true
);
```

### Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| "No suitable GLX framebuffer configuration" | Missing GL support | Install Mesa: `sudo apt-get install libgl1-mesa-dev` |
| Black screen | Wrong blend mode or clear color | Check `setClearColor()` and shader transparency |
| Texture not displaying | No texture loading implementation | Use placeholder or implement texture loader |
| Context creation fails | GLX not available | Ensure X11 is running (`echo $DISPLAY` should show `:0` or similar) |

---

## Architecture Notes

### Why Separate Components?

1. **GLContext**: Isolates platform-specific initialization (GLX/X11/EGL)
2. **ShaderProgram**: Centralizes shader management and uniform handling
3. **Texture/Framebuffer**: Reusable GPU resource abstractions
4. **QuadMesh**: Dedicated quad rendering (extensible to other geometries)
5. **PreviewRenderer**: High-level composition API

This modular design enables:
- Testing individual components in isolation
- Swapping GLX for EGL (Android) with minimal changes
- Adding new shader effects without touching rendering pipeline
- Reusing components for other GPU-based tasks (effects, filters)

---

## Thread Safety

⚠️ **OpenGL is not thread-safe**. All rendering must occur in the same thread that created the context. To render from multiple threads:

```cpp
// Thread A: Create context and renderer
auto renderer = std::make_shared<VideoEngine::GPU::PreviewRenderer>(...);

// Thread B: Must call makeCurrent() before rendering
void renderThread() {
    renderer->m_glContext->makeCurrent();  // Make context current in this thread
    renderer->renderFrame(renderGraph, timeMs);
    renderer->m_glContext->release();
}
```

---

## License & References

- OpenGL 3.3 Specification: https://www.khronos.org/opengl/wiki/OpenGL_3.3
- GLX Specification: https://www.khronos.org/opengl/wiki/GLX
- GLM Math Library: https://github.com/g-truc/glm

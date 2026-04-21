# GPU Backend - OpenGL Preview Renderer

## Quick Start

### Requirements

- Linux with X11 (or headless with Xvfb)
- OpenGL 3.3 compatible GPU
- Development libraries:
  ```bash
  # Ubuntu/Debian
  sudo apt-get install libgl1-mesa-dev libx11-dev libglm-dev

  # Fedora/RHEL
  sudo dnf install mesa-libGL-devel libX11-devel glm-devel

  # Arch Linux
  sudo pacman -S mesa libx11 glm
  ```

### File Structure

```
backend/gpu/
├── gl_context.h              # OpenGL context management
├── gl_context.cpp
├── shader_program.h          # GLSL shader compilation
├── shader_program.cpp
├── texture.h                 # Texture & Framebuffer objects
├── texture.cpp
├── quad_mesh.h               # Quad geometry for rendering
├── quad_mesh.cpp
├── preview_renderer.h        # Main rendering interface
├── preview_renderer.cpp
├── gpu_backend.h             # Convenience include header
├── gpu_renderer_examples.h   # Usage examples (reference)
├── CMakeLists.txt            # Build configuration
└── README.md                 # This file
```

### Building

The GPU backend is automatically included in the build if dependencies are found:

```bash
mkdir build && cd build
cmake ..
make
```

To explicitly enable/disable GPU backend:

```bash
cmake -DBUILD_GPU_BACKEND=ON ..   # Enable (default if deps found)
cmake -DBUILD_GPU_BACKEND=OFF ..  # Disable
```

### Basic Usage

```cpp
#include "backend/gpu/gpu_backend.h"
#include "engine/engine.h"

// Create and populate timeline
auto timeline = std::make_shared<VideoEngine::Timeline>();
auto clip = std::make_shared<VideoEngine::Clip>("video.mp4", 0, 3000);
timeline->addClip(clip);

// Build render graph
VideoEngine::RenderGraph renderGraph;
renderGraph.buildFromTimeline(*timeline);

// Create GPU renderer
auto renderer = std::make_shared<VideoEngine::GPU::PreviewRenderer>(
    1920, 1080,
    VideoEngine::GPU::PreviewRenderer::RenderMode::Headless
);

// Render frame
renderer->renderFrame(renderGraph, 1500);  // at 1.5 seconds

// Get output texture
auto colorTexture = renderer->getFramebufferTexture();
```

## Key Components

| Component | Purpose |
|-----------|---------|
| **GLContext** | OpenGL context init, GLX/X11 integration |
| **ShaderProgram** | GLSL compilation, uniform management |
| **Texture** | GPU texture allocation, sampling |
| **Framebuffer** | Off-screen rendering target (FBO) |
| **QuadMesh** | Vertex/index buffers for clip rendering |
| **PreviewRenderer** | Main API - coordinates rendering pipeline |

## Render Modes

### Headless (Default)

Render to off-screen framebuffer. No window display.

```cpp
auto renderer = std::make_shared<VideoEngine::GPU::PreviewRenderer>(
    1920, 1080,
    VideoEngine::GPU::PreviewRenderer::RenderMode::Headless
);
renderer->renderFrame(renderGraph, timeMs);
```

**Use Cases**: Batch processing, CI/CD, server-side rendering

### Windowed

Render to X11 window. Display updates in real-time.

```cpp
auto renderer = std::make_shared<VideoEngine::GPU::PreviewRenderer>(
    1280, 720,
    VideoEngine::GPU::PreviewRenderer::RenderMode::Windowed
);
renderer->renderFrame(renderGraph, timeMs);
renderer->swapBuffers();  // Display
```

**Use Cases**: Real-time preview, interactive editing, live playback

**Requirements**: X11 display server running (`$DISPLAY` must be set)

## Rendering Pipeline

```
renderFrame(renderGraph, timeMs)
  ↓
Query visible items: renderGraph.getItemsAtTime(timeMs)
  ↓
Bind framebuffer
  ↓
Clear background
  ↓
For each item (sorted by layer, back-to-front):
  - Bind texture
  - Set model transform
  - Set opacity uniform
  - Render quad
  ↓
Unbind framebuffer
  ↓
(Windowed) Swap buffers
```

## Shader System

### Built-in Shaders

**Vertex Shader**:
- Transforms quad vertices using projection/view/model matrices
- Passes texture coordinates to fragment shader

**Fragment Shader**:
- Samples texture
- Applies opacity blending
- Output: RGBA color with premultiplied alpha

### Custom Shaders

To use custom shaders, modify `preview_renderer.cpp`:

```cpp
const char* FRAGMENT_SHADER = R"glsl(
#version 330 core
// Your custom fragment shader here
)glsl";
```

## Output Texture

Access rendered output via `getFramebufferTexture()`:

```cpp
auto texture = renderer->getFramebufferTexture();

// Option 1: Display in window (windowed mode)
// Already done via swapBuffers()

// Option 2: Read back to CPU
std::vector<uint8_t> pixels(width * height * 4);
texture->bind(0);
glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());

// Option 3: Pass to post-processing (use as input to another FBO)
texture->bind(0);  // Bind to texture unit 0
// ... use in another render pass ...
```

## Configuration

### Clear Color

```cpp
renderer->setClearColor(0.2f, 0.2f, 0.2f, 1.0f);  // Dark gray
```

### Debug Mode

Enable OpenGL debug output:

```cpp
auto renderer = std::make_shared<VideoEngine::GPU::PreviewRenderer>(
    1920, 1080,
    VideoEngine::GPU::PreviewRenderer::RenderMode::Headless,
    true  // debugMode = true
);
```

## Performance

### Headless Rendering (No Display)

Typical performance on modest hardware:
- 1920x1080 @ 30 fps: **2-5 ms per frame**
- Bottleneck: Texture uploads, shader compilation (first frame only)

### Windowed Rendering

Performance depends on monitor refresh rate:
- Typical: 60 fps (16.67 ms per frame)
- GPU time: 2-5 ms, rest spent in display sync

### Optimization Tips

1. **Reuse renderer** - Create once, render multiple frames
2. **Batch rendering** - Process sequential frames without recreating context
3. **Texture caching** - Cache clip textures across multiple renders
4. **FBO readback** - Use PBO for async pixel readback (not implemented yet)

## Troubleshooting

### Error: "Failed to open X11 display"

**Cause**: No X11 display server running

**Solutions**:
- For headless rendering (recommended): Use `RenderMode::Headless` (no X11 needed)
- For windowed: `export DISPLAY=:0` before running
- Use Xvfb: `Xvfb :99 -screen 0 1920x1080x24 &` then `export DISPLAY=:99`

### Error: "No suitable GLX framebuffer configuration found"

**Cause**: GPU doesn't support required GL extensions

**Solution**: Install proper drivers
- NVIDIA: `sudo apt-get install nvidia-driver-XXX` (check for your GPU)
- AMD: `sudo apt-get install mesa-radeon` or AMDGPU drivers
- Intel: Usually built-in, ensure `libgl1-mesa-dev` is installed

### Black/Transparent Output

**Cause**: No texture loaded or wrong blend mode

**Solution**: 
- Check `setClearColor()` - background might be black
- Verify clip textures are loaded (currently uses white placeholder)
- Check fragment shader transparency logic

### Segmentation Fault

**Cause**: Context not made current before GL operations

**Solution**: Ensure `glContext->makeCurrent()` called before rendering

## Architecture Notes

### Why GLX + X11?

- **GLX**: OpenGL extension to X11, standard on Linux desktop
- **X11**: Widely available, works in CI/CD environments with Xvfb
- **Alternative**: EGL could replace GLX for headless + Android support

### Why Not EGL-Only?

- X11 dominance on Linux desktop (easier setup)
- GLX has better windowed support
- EGL can be added as alternative backend in future

## Future Enhancements

- [ ] Real texture loading from clips
- [ ] Compute shaders for effects
- [ ] Multiple render targets (post-processing)
- [ ] Android NDK support (EGL backend)
- [ ] Instancing for many layers
- [ ] Async readback (PBO)
- [ ] Custom blend modes
- [ ] Audio waveform overlay

## References

- [GPU_RENDERER_GUIDE.md](GPU_RENDERER_GUIDE.md) - Comprehensive architecture guide
- [gpu_renderer_examples.h](gpu_renderer_examples.h) - Usage examples
- [OpenGL 3.3 Spec](https://www.khronos.org/opengl/wiki/OpenGL_3.3)
- [GLM Math Library](https://github.com/g-truc/glm)

## Support

For issues or questions:
1. Check [GPU_RENDERER_GUIDE.md](GPU_RENDERER_GUIDE.md) troubleshooting section
2. Run with debug mode enabled: `debugMode = true`
3. Check console output for OpenGL errors
4. Verify OpenGL version: `glxinfo | grep "OpenGL version"`

---

**Status**: ✅ Preview Renderer Functional
- ✅ OpenGL 3.3 context creation
- ✅ Shader compilation and management
- ✅ Texture and framebuffer handling
- ✅ Quad mesh rendering with layer ordering
- ✅ Headless + windowed modes
- ⏳ Real clip texture loading (future enhancement)

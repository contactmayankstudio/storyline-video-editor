# GLSL Shaders for GPU Preview Renderer

## Overview

The GPU preview renderer uses a two-stage GLSL 3.3 core shader pipeline for rendering video compositions with layer ordering and opacity effects.

**Location**: `backend/gpu/shaders/`

---

## Shader Files

### fullscreen.vert - Vertex Shader

**Purpose**: Transform fullscreen quad geometry using MVP matrix transformation.

**Key Features**:
- Quad vertices in normalized device coordinates (NDC)
- MVP transformation for flexible positioning
- Passes texture coordinates and world position to fragment shader

**Input**:
```glsl
layout(location = 0) in vec3 position;      // Vertex position
layout(location = 1) in vec2 texCoord;      // Texture coordinate
```

**Uniforms**:
```glsl
uniform mat4 projection;    // Orthographic projection matrix
uniform mat4 view;          // View matrix (typically identity)
uniform mat4 model;         // Per-item model transform
```

**Output**:
```glsl
out VS_OUT {
    vec2 texCoord;  // Texture coordinate for fragment shader
    vec3 worldPos;  // World position for effects
} vs_out;
```

### fullscreen.frag - Fragment Shader

**Purpose**: Apply texture sampling, opacity blending, and fade effects.

**Key Features**:
- Texture sampling with bilinear filtering
- Per-clip opacity control
- Fade-in effect (transparent → opaque)
- Fade-out effect (opaque → transparent)
- Alpha compositing for proper layer blending

**Uniforms**:
```glsl
uniform sampler2D tex0;         // Color texture (unit 0)
uniform float opacity;          // Clip opacity [0..1]
uniform int fadeMode;           // 0=none, 1=fade-in, 2=fade-out
uniform float fadeProgress;     // Fade progress [0..1]
```

**Fade Modes**:
- `0`: No fade effect
- `1`: Fade-in (start transparent, fade to opaque)
- `2`: Fade-out (start opaque, fade to transparent)

**Output**:
```glsl
out vec4 FragColor;  // Final RGBA color with alpha blending
```

---

## Rendering Pipeline

### MVP Transformation

```
Vertex Position (NDC)
    ↓
model transform (position, scale, layer)
    ↓
view transform (camera, typically identity)
    ↓
projection transform (orthographic)
    ↓
Screen space position
```

### Opacity Blending

```
Texture Color (RGBA)
    ↓
Apply base opacity: color.a *= opacity
    ↓
Apply fade effect: color.a *= fadeProgress
    ↓
Clamp alpha [0, 1]
    ↓
Output with alpha blending enabled
```

---

## Usage Example

```cpp
#include "backend/gpu/gpu_backend.h"

// Create renderer
auto renderer = std::make_shared<VideoEngine::GPU::PreviewRenderer>(1920, 1080);

// Shaders loaded automatically from backend/gpu/shaders/
// - fullscreen.vert
// - fullscreen.frag

// Render frame
renderer->renderFrame(renderGraph, timeMs);
```

---

## Customizing Shaders

### Adding New Effects

Edit `fullscreen.frag` to add new effects:

```glsl
// Example: Add brightness control
uniform float brightness;

void main() {
    vec4 texColor = texture(tex0, fs_in.texCoord);
    texColor.rgb *= brightness;  // Apply brightness
    texColor.a *= opacity;        // Apply opacity
    FragColor = texColor;
}
```

Then set the uniform from C++:

```cpp
m_shaderProgram->setUniform1f("brightness", 1.5f);
```

### Color Correction Example

```glsl
// Example: Hue shift
uniform vec3 colorTint;

void main() {
    vec4 texColor = texture(tex0, fs_in.texCoord);
    texColor.rgb *= colorTint;  // Tint color
    texColor.a *= opacity;
    FragColor = texColor;
}
```

### Advanced Blending

```glsl
// Example: Screen blend mode for overlays
uniform int blendMode;  // 0=normal, 1=screen, 2=multiply

void main() {
    vec4 texColor = texture(tex0, fs_in.texCoord);
    
    if (blendMode == 1) {
        // Screen blend: 1 - (1-a) * (1-b)
        vec3 invColor = vec3(1.0) - texColor.rgb;
        texColor.rgb = vec3(1.0) - invColor * (1.0 - opacity);
    } else if (blendMode == 2) {
        // Multiply blend
        texColor.rgb *= opacity;
    } else {
        // Normal blend (default)
        texColor.a *= opacity;
    }
    
    FragColor = texColor;
}
```

---

## OpenGL 3.3 Core Requirements

### Compatibility Notes

✅ **Supported**:
- `#version 330 core` directive
- Input/output blocks with `VS_OUT`, `FS_IN`
- `layout` qualifiers
- `texture()` function with samplers
- Matrix uniforms

❌ **Not Supported in Core Profile**:
- Fixed function pipeline
- `gl_Color`, `gl_Normal`
- `gl_TexCoord` built-ins
- Legacy `uniform` arrays

### GLSL Extensions Used

- **None** - Fully core profile compatible
- No extensions required
- Works on all GL 3.3+ implementations

---

## Shader Compilation Errors

### Common Issues

| Error | Cause | Solution |
|-------|-------|----------|
| `#version not first` | Comment before version | Move `#version 330 core` to line 1 |
| `layout invalid` | Using compatibility profile | Ensure core context (GL 3.3+) |
| `uniform type mismatch` | Wrong type in C++ | Check uniform type matches shader |
| `texture() undefined` | Wrong version/profile | Use `#version 330 core` |

### Debugging

Enable shader debug output:

```cpp
auto renderer = std::make_shared<VideoEngine::GPU::PreviewRenderer>(
    1920, 1080,
    VideoEngine::GPU::PreviewRenderer::RenderMode::Headless,
    true  // debugMode = true
);
```

Compilation errors appear in console output.

---

## Performance Tips

1. **Minimize texture lookups** - Each `texture()` call is expensive
2. **Use lower precision** - `mediump` if supported by target
3. **Avoid branching** - Use conditionals sparingly (GPUs like linear code)
4. **Combine operations** - Reduce number of uniforms set per frame
5. **Profile effects** - Measure shader time on target hardware

---

## Cross-Platform Considerations

✅ **Fully Portable**:
- No platform-specific directives
- GLSL 3.3 core widely supported
- Works on Linux, Windows, macOS (with GL context)
- Android compatible (requires EGL, future enhancement)

⚠️ **Platform-Specific**:
- Shader file loading path (handled by ShaderLoader)
- OpenGL context creation (platform-specific via GLContext)

---

## Future Enhancements

### Planned Additions

1. **Compute Shaders** - For parallel processing and effects
2. **Tessellation** - Dynamic geometry generation
3. **Post-Processing** - Blur, color correction, etc.
4. **Screen-Space Effects** - SSAO, reflections

### Advanced Effects

```glsl
// Future: Chromatic aberration
uniform float chromaAmount;

vec4 sampleChroma(sampler2D tex, vec2 uv) {
    vec2 offset = chromaAmount * (uv - 0.5);
    float r = texture(tex, uv + offset).r;
    float g = texture(tex, uv).g;
    float b = texture(tex, uv - offset).b;
    return vec4(r, g, b, texture(tex, uv).a);
}
```

---

## Shader File Loading

### Search Paths (in order)

1. `$VIDEOENGINE_SHADER_PATH` (environment variable)
2. `./backend/gpu/shaders/` (relative to CWD)
3. `../backend/gpu/shaders/`
4. `../../backend/gpu/shaders/`
5. `./shaders/`
6. Installed location: `${CMAKE_INSTALL_PREFIX}/share/video_engine/shaders/`

### Setting Shader Path

```bash
# Environment variable
export VIDEOENGINE_SHADER_PATH=/path/to/shaders

# Run application
./video_engine
```

### Debugging Shader Loading

Monitor console output:

```
[ShaderLoader] Found shader directory: ./backend/gpu/shaders
[ShaderLoader] Loaded fullscreen.vert (1234 bytes)
[ShaderLoader] Loaded fullscreen.frag (2567 bytes)
[ShaderProgram] Compiled and linked successfully (handle=123)
```

---

## References

- [GLSL 3.30 Specification](https://www.khronos.org/registry/OpenGL/specs/gl/GLSLangSpec.3.30.pdf)
- [OpenGL 3.3 API Reference](https://www.khronos.org/opengl/wiki/OpenGL_3.3)
- [GLSL Best Practices](https://www.khronos.org/opengl/wiki/OpenGL_Shading_Language)

---

## Changelog

| Date | Version | Changes |
|------|---------|---------|
| Feb 2026 | 1.0 | Initial GLSL 3.3 shaders with opacity and fade effects |

---

**Status**: ✅ Production Ready  
**OpenGL Version**: 3.3 Core  
**GLSL Version**: 3.30  
**Platform Support**: Cross-platform

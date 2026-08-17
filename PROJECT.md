# Project: storyline C++ Video Engine Advanced Features

## Architecture
- `core/`: Core data structures (`clip.h`, `keyframe.h`, `timeline.h`, `track.h`).
- `engine/`: Timeline command handling and project serialization (`project.h`, `project.cpp`, `command_manager.h`).
- `preview/`: Real-time OpenGL preview controller and GL ES renderer (`preview_controller.h/cpp`, `gpu/egl_renderer.cpp`).
- `backend/`: Media demuxing, decoding, GL shaders, and FFmpeg audio rendering (`ffmpeg/ffmpeg_audio_renderer.cpp`, `gpu/shaders/yuv_to_rgb.frag`).
- `smooth_engine/`: Audio engine, proxy manager, speed ramping (`AudioEnginePro.cpp`, `ProxyManager.cpp`, `SpeedRamping.cpp`).
- `android/`: Android JNI bindings (`NativeBridge.kt`, `native_preview.cpp`) and Gradle build setup (`cd android && ./gradlew :app:assembleDebug`).

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | R1.1 Proxy Path Resolution | Update PreviewController to check and load clip->getPreviewProxyPath() when valid | M2 | ORIGINAL_REQUEST.md |
| 2 | R1.2 Proxy Cache Invalidation | Clear predictive cache, reallocate textures, and invalidate visual state upon proxy completion | M2 | ORIGINAL_REQUEST.md |
| 3 | R1.3 Export Path Isolation | Enforce strict original media path (clip->getMediaPath()) in ExportController pipeline | M2 | ORIGINAL_REQUEST.md |
| 4 | R1.4 Normalized Spatial Coordinates | Convert pixel offsets (panXPx/panYPx) to normalized [0.0, 1.0] coordinates for scale-agnostic rendering | M2 | ORIGINAL_REQUEST.md |
| 5 | R2.1 Real-Time Audio Time-Stretch | Implement real-time audio time-stretching in AudioEnginePro matching preview speed | M3 | ORIGINAL_REQUEST.md |
| 6 | R2.2 Audio Export Speed Matching | Inject FFmpeg atempo filter in AudioRenderer decode/export pipeline | M3 | ORIGINAL_REQUEST.md |
| 7 | R2.3 Timeline-to-Source Video Export | Map timeline export frames to source timestamps using speed curves | M3 | ORIGINAL_REQUEST.md |
| 8 | R3.1 Clip Blend & Mask Properties | Add blendMode enum and MaskParams struct to ClipProperties in core/clip.h | M4 | ORIGINAL_REQUEST.md |
| 9 | R3.2 OpenGL Blend Modes | Implement glBlendFunc switching (ADD, SCREEN, MULTIPLY) in egl_renderer.cpp | M4 | ORIGINAL_REQUEST.md |
| 10 | R3.3 Shader Mask Sampling | Update fragment shaders with sampler2D maskSampler for alpha/luma sampling and inversion | M4 | ORIGINAL_REQUEST.md |
| 11 | R4.1 Reusable Keyframe Header | Extract TransformKeyframe struct to core/keyframe.h with alias in text_overlay.h | M1 | ORIGINAL_REQUEST.md |
| 12 | R4.2 Clip Keyframe Vector | Add spatial base fields and transformKeyframes vector to ClipProperties in core/clip.h | M1 | ORIGINAL_REQUEST.md |
| 13 | R4.3 Project JSON Serialization | Implement JSON serialization/deserialization for spatial properties and keyframe vectors | M1 | ORIGINAL_REQUEST.md |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Keyframe Animation Base & Spatial Model | Header extraction, core/clip.h keyframe vector, JSON serialization | none | PLANNED |
| M2 | Proxy Editing System Integration | PreviewController proxy path resolution, cache clear, original export isolation, normalized coords | M1 | PLANNED |
| M3 | Speed Control (Audio & Export) | AudioEnginePro time-stretch, FFmpeg atempo audio export, timeline-to-source video export | none | PLANNED |
| M4 | Masking & Blending Modes | Clip blend/mask properties, glBlendFunc switching in egl_renderer, fragment shader alpha/luma sampling | M1 | PLANNED |
| M5 | E2E Testing & Final Verification | Comprehensive E2E test suite (Tiers 1-5), gradlew compilation check, final integration hardening | M1, M2, M3, M4 | PLANNED |

## Interface Contracts
### Keyframe Base (M1) ↔ Clip / Project (M1, M2, M4)
- `struct TransformKeyframe` defined in `core/keyframe.h`: `int64_t timeMs`, `float posX, posY`, `float scaleX, scaleY`, `float rotation`, `float opacity`.
- `ClipProperties` in `core/clip.h` holds `std::vector<TransformKeyframe> transformKeyframes`.
- `Project::toJSON()` and `Project::fromJSON()` handle keyframe JSON array (`"transformKeyframes"`).

### Proxy Controller (M2) ↔ Export Controller (M2)
- `PreviewController` uses `clip->getPreviewProxyPath()` if valid; `ExportController` strictly calls `clip->getMediaPath()`.
- Coordinates rendered using `normalizedX = panXPx / frameWidth`, `normalizedY = panYPx / frameHeight`.

### Mask & Blend (M4) ↔ EGL Renderer (M4)
- `BlendMode::Normal` -> `glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)`
- `BlendMode::Add` -> `glBlendFunc(GL_SRC_ALPHA, GL_ONE)`
- `BlendMode::Screen` -> `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_COLOR)`
- Fragment shader uniform `uMaskType`: 0 = Alpha, 1 = Luma.

## Code Layout
- `core/keyframe.h` (New header for TransformKeyframe)
- `core/clip.h` (Updated clip data model)
- `text_overlay.h` (Alias TextKeyframe to TransformKeyframe)
- `engine/project.h`, `engine/project.cpp` (JSON serialization)
- `preview/preview_controller.h`, `preview/preview_controller.cpp` (Proxy logic)
- `preview/gpu/egl_renderer.cpp` (GL blend modes & shaders)
- `backend/gpu/shaders/yuv_to_rgb.frag` (Shader mask sampling)
- `smooth_engine/AudioEnginePro.cpp` (Real-time audio time-stretch)
- `backend/ffmpeg/ffmpeg_audio_renderer.cpp` (FFmpeg atempo export filter)
- `smooth_engine/ExportController.cpp` / `SpeedRamping.cpp` (Export speed curve mapping)

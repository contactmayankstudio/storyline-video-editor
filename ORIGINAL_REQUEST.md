# Original User Request

## 2026-08-11T05:58:43Z

<USER_REQUEST>
Implement four advanced professional video editing features (Proxy Editing, Speed Control for Audio/Export, Masking & Blending modes, and Keyframe Animation Base) into the storyline C++ video engine.

Working directory: /home/am/storyline
Integrity mode: development

## Requirements

### R1. Proxy Editing System Integration
Update `PreviewController` to use `clip->getPreviewProxyPath()` when available and clear cache upon proxy generation completion. Ensure the export pipeline strictly uses the original media path. Apply resolution-independent normalized coordinates for rendering.

### R2. Speed Control (Audio & Export)
Implement real-time audio time-stretching in `AudioEnginePro` to match video playback speed. Apply FFmpeg `atempo` filters in audio export, and ensure video export correctly uses timeline-to-source mapping.

### R3. Masking & Blending
Introduce `blendMode` properties and mask support in clips. Implement OpenGL blend modes (`glBlendFunc`) in `egl_renderer.cpp` and update the fragment shader to support texture masks with alpha/luma sampling.

### R4. Keyframe Animation Base
Extract a common `TransformKeyframe` structure from `text_overlay.h` to make it reusable. Update `core/clip.h` to store spatial properties and a vector of transform keyframes. Ensure proper project serialization (JSON) for these properties.

## Acceptance Criteria

### Compilation & Core Integration
- [ ] Code compiles without errors (`cd android && ./gradlew :app:assembleDebug`).
- [ ] No regressions in standard timeline playback.

### Feature Verification
- [ ] **Proxy**: Preview correctly loads the proxy file instead of the 4K original when `previewProxyPath_` is set.
- [ ] **Speed**: Audio stays in sync with video speed curve during preview, and export produces correct length outputs.
- [ ] **Masking**: Setting a clip's `blendMode` to `ADD` or `SCREEN` visibly alters compositing during playback.
- [ ] **Keyframes**: Clip entry serializes and deserializes the new `transformKeyframes` structure correctly to/from JSON project files.
</USER_REQUEST>

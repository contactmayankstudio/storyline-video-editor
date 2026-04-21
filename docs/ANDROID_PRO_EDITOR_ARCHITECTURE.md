# Android Pro Editor Architecture

This slice adds a native-first Android editor shell for a CapCut/VN-style workflow.

## Kotlin layer

- `android/app/src/main/kotlin/com/video/engine/pro/model/ProjectSession.kt`
  - JSON-serializable project state.
  - Stores four synchronized tracks: `VIDEO`, `OVERLAY`, `TEXT`, `AUDIO`.
  - Stores clip timing, source trim, z-order, selected clip, playhead time, and zoom scale.

- `android/app/src/main/kotlin/com/video/engine/pro/timeline/MultiTrackTimelineView.kt`
  - Four fixed timeline rows.
  - Horizontal scroll is synchronized across all rows.
  - Playhead is a center needle, not a moving cursor.
  - Timestamp is derived from `scrollOffset + viewportCenter`.
  - Pinch zoom changes `pxPerSecond` without changing the playhead anchor time.

- `android/app/src/main/kotlin/com/video/engine/pro/preview/NativePreviewGLSurfaceView.kt`
  - `GLSurfaceView` with GLES 3.0.
  - Surface lifecycle is forwarded to JNI.
  - Rendering requests stay on the GL thread for low-latency preview.

- `android/app/src/main/kotlin/com/video/engine/pro/jni/NativeEngineBridge.kt`
  - Thin JNI contract:
    - `onSeek(timeMs)`
    - `onClipMoved(clipId, newTimeMs)`
    - `onRenderFrame(surface)`
    - `onSurfaceSizeChanged(width, height)`
    - `onSurfaceDestroyed()`
    - `onPlaybackStateChanged(isPlaying)`

- `android/app/src/main/kotlin/com/video/engine/pro/ProEditorActivity.kt`
  - Runnable Android host for the architecture slice.
  - Wires preview, timeline, playhead label, and playback toggle.

## JNI / C++

- `android/jni/pro_editor_jni.cpp`
  - Kotlin `Surface` arrives in JNI as a framework object.
  - Native converts it with `ANativeWindow_fromSurface(env, surface)`.
  - The existing `PreviewController` attaches to the native window and renders with EGL/OpenGL ES.
  - `onSeek()` scrubs to a precise timeline time.
  - `onClipMoved()` mutates clip timeline position in native state.

## Zero-latency preview contract

1. `GLSurfaceView` owns the GL thread.
2. Kotlin calls `NativeEngineBridge.onRenderFrame(holder.surface)`.
3. JNI converts the `Surface` to `ANativeWindow*`.
4. C++ engine binds or rebinds the surface with EGL.
5. The engine renders directly into the preview surface with OpenGL ES.

This avoids bitmap readback and keeps preview on the native GPU path.

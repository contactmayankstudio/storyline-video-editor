# Android JNI Implementation Complete

## Overview
`android/jni/native_preview.cpp` provides a complete JNI bridge exposing the video preview engine to Android SurfaceView.

## Architecture

### Global State (Thread-Safe)
- **EGL Context**: Thread-local (`g_eglDisplay`, `g_eglContext`, `g_eglSurface`, `g_nativeWindow`)
- **Preview Controller**: Global mutex-protected (`g_preview`)
- **Surface Dimensions**: Tracked for viewport updates

### EGL Setup
- **Display**: `EGL_DEFAULT_DISPLAY` from default device
- **Config Attributes**: 
  - `EGL_WINDOW_BIT` (renders to ANativeWindow)
  - 8-bit RGB channels
  - OpenGL ES 3.0
- **Context**: Version 3.0
- **Surface**: Window surface from Android Surface object

## JNI Function Signatures

### Lifecycle Management

**`nativeSurfaceCreated(Surface surface)`**
- Called when SurfaceView surface is first created
- Extracts ANativeWindow from Surface
- Initializes EGL context and surface
- Creates PreviewController and loads test video (`/data/local/tmp/test.mp4`)
- Initializes GL resources
- Thread-safe with mutex lock

**`nativeSurfaceChanged(int width, int height)`**
- Called when surface size changes
- Updates surface dimensions
- Sets OpenGL viewport to match surface size
- Thread-safe with mutex lock

**`nativeSurfaceDestroyed()`**
- Called when surface is destroyed
- Closes PreviewController
- Terminates EGL context
- Releases ANativeWindow reference
- Thread-safe with mutex lock

### Rendering

**`nativeRenderFrame(long timeMs)`**
- Called repeatedly from Java render loop (e.g., via Handler or GameThread)
- Makes EGL context current
- Clears framebuffer
- Calls `PreviewController::renderAtTime(timeMs)` to decode and render
- Swaps display buffers via `eglSwapBuffers()`
- Thread-safe with mutex lock
- **No threading** - must be called from consistent thread

### Metadata Access

**`nativeGetVideoWidth()` → int**
- Returns decoded video width in pixels
- Returns 0 if not initialized

**`nativeGetVideoHeight()` → int**
- Returns decoded video height in pixels
- Returns 0 if not initialized

## Usage Example (Java)

```java
public class PreviewSurface extends SurfaceView implements SurfaceHolder.Callback {
    static {
        System.loadLibrary("video_engine");  // Load native library
    }

    private Runnable mRenderTask = new Runnable() {
        long mStartTime = System.currentTimeMillis();
        
        @Override
        public void run() {
            long nowMs = System.currentTimeMillis() - mStartTime;
            nativeRenderFrame(nowMs);
            
            mHandler.postDelayed(this, 33);  // ~30 FPS
        }
    };

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        nativeSurfaceCreated(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        nativeSurfaceChanged(w, h);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        nativeSurfaceDestroyed();
    }

    // Native methods
    private native void nativeSurfaceCreated(Surface surface);
    private native void nativeSurfaceChanged(int width, int height);
    private native void nativeSurfaceDestroyed();
    private native void nativeRenderFrame(long timeMs);
    private native int nativeGetVideoWidth();
    private native int nativeGetVideoHeight();
}
```

## Thread Safety

- **EGL State**: Thread-local storage ensures each thread has independent EGL context
- **Shared State**: Global `g_preview` protected by `std::mutex` with RAII lock guards
- **Expected Usage Pattern**: All JNI functions called from same thread (e.g., render thread), mutex protects shared data

## Logging

All operations logged via Android NDK `__android_log_print()`:
- Info: Surface lifecycle, EGL initialization, video metadata
- Error: Initialization failures, rendering errors, null pointer checks
- Log tag: `"VideoEngine"`

## Dependencies

- **Headers**: `<jni.h>`, `<android/native_window.h>`, `<EGL/egl.h>`, `<GLES3/gl3.h>`
- **Libraries**: 
  - `libc++` (C++ runtime)
  - `libEGL` (EGL)
  - `libGLESv3` (OpenGL ES 3.0)
  - `libffmpeg` (compiled FFmpeg)

## Build Integration

Add to `CMakeLists.txt`:
```cmake
add_library(video_engine SHARED
    android/jni/native_preview.cpp
    engine/preview_controller.cpp
    backend/ffmpeg/video_decoder.cpp
    preview/gpu/gl_texture.cpp
    preview/gpu/video_renderer.cpp
    # ... other sources
)

target_link_libraries(video_engine
    avformat avcodec swscale avutil
    EGL GLESv3
)
```

## Test Video

Hardcoded test video path: `/data/local/tmp/test.mp4`

To test:
```bash
adb push test.mp4 /data/local/tmp/test.mp4
```

## Architecture Diagram

```
Java SurfaceView
      ↓
SurfaceHolder.Callback
      ↓
   JNI Bridge (native_preview.cpp)
      ↓
   PreviewController (orchestrator)
      ↓
   ┌─────────────────┬──────────────┬─────────────┐
   ↓                 ↓              ↓             ↓
VideoDecoder     GLTexture    VideoRenderer   EGL Context
(FFmpeg)         (GLES 3.0)    (Shaders)      (ANativeWindow)
```

## Known Limitations

1. **Linear Playback Only**: No seeking support (MVP)
2. **Test Video Hardcoded**: Must be at `/data/local/tmp/test.mp4`
3. **No Audio**: Video rendering only
4. **Single Instance**: Global state allows only one active preview
5. **Frame Rate Control**: Java must call `nativeRenderFrame()` at desired timing

## Future Enhancements

- [ ] Implement seeking via `nativeSeekToTime(long timeMs)`
- [ ] Support multiple concurrent instances
- [ ] Video file path configurable via Java
- [ ] Audio playback integration
- [ ] Custom frame timing control

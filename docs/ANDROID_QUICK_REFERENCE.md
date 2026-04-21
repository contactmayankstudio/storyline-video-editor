# Android NDK OpenGL ES - Quick Reference Card

## JNI Method Summary

| Method | Signature | Thread | Purpose |
|--------|-----------|--------|---------|
| `nativeInitPreview` | `(Surface) → void` | Main (JNI) | Init EGL + start render thread |
| `nativeSetSurfaceSize` | `(int w, int h) → void` | Main (JNI) | Update viewport |
| `nativeReleasePreview` | `() → void` | Main (JNI) | Stop thread + cleanup EGL |
| `nativeLoadVideo` | `(String path) → bool` | Main (JNI) | Load video file |
| `nativeSeekPreview` | `(long ms) → void` | Main (JNI) | Seek + render 1 frame |
| `nativeStartPlayback` | `(long ms) → void` | Main (JNI) | Enable rendering loop |
| `nativeStopPlayback` | `() → void` | Main (JNI) | Disable rendering loop |
| `nativePauseRendering` | `() → void` | Main (JNI) | Release EGL context |
| `nativeResumeRendering` | `() → void` | Main (JNI) | Re-acquire EGL context |

## Activity Lifecycle Integration

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    previewView = VideoPreviewView(this)        // Constructor
    setContentView(previewView)                  // surfaceCreated() called
}

override fun onStart() {
    super.onStart()
    // Surface is created, EGL initialized
}

override fun onResume() {
    super.onResume()
    previewView.onResume()                       // nativeResumeRendering()
}

override fun onPause() {
    previewView.onPause()                        // nativePauseRendering()
    super.onPause()
}

override fun onStop() {
    super.onStop()
    // Still holding EGL resources
}

override fun onDestroy() {
    previewView.stopPlayback()                   // nativeStopPlayback()
    super.onDestroy()                            // surfaceDestroyed() called
                                                  // nativeReleasePreview() called
}
```

## EGL Initialization Sequence

```
ANativeWindow_fromSurface(surface)
    ↓
eglGetDisplay(EGL_DEFAULT_DISPLAY)
    ↓
eglInitialize(display, &major, &minor)
    ↓
eglChooseConfig(display, attribs, &config, ...)
    ↓
eglCreateContext(display, config, nullptr, contextAttribs)
    ↓
eglCreateWindowSurface(display, config, nativeWindow, nullptr)
    ↓
eglMakeCurrent(display, surface, surface, context)
    ↓
glClearColor(0, 0, 0, 1)
    ↓
glViewport(0, 0, width, height)
    ↓
READY FOR RENDERING
```

## EGL Cleanup Sequence

```
eglMakeCurrent(display, NO_SURFACE, NO_SURFACE, NO_CONTEXT)
    ↓
eglDestroySurface(display, surface)
    ↓
eglDestroyContext(display, context)
    ↓
eglTerminate(display)
    ↓
ANativeWindow_release(nativeWindow)
```

## Render Loop Pattern

```cpp
void renderThreadProc() {
    while (!g_shouldExit) {
        // 1. Check rendering state (lock-free)
        if (!g_isRenderingActive) {
            sleep(10ms);  // Idle
            continue;
        }
        
        // 2. Lock + render (short critical section)
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            
            eglMakeCurrent(display, surface, surface, context);
            g_preview->renderFrame();
            eglSwapBuffers(display, surface);
        }
        
        // 3. Sleep (outside lock)
        sleep(1ms);  // Target 60fps = 16.67ms/frame
    }
}
```

## Common Mistakes ❌

```cpp
// BAD: Hold lock while sleeping
{
    lock_guard<mutex> lock(g_mutex);
    if (!rendering) {
        sleep(10ms);  // Lock held!
    }
}

// GOOD: Check with atomic, lock only for EGL
if (!rendering.load(memory_order_acquire)) {
    sleep(10ms);  // No lock
}
{
    lock_guard<mutex> lock(g_mutex);
    eglMakeCurrent(...);
}

// BAD: Call EGL from wrong thread
nativeRenderFrame() {
    eglMakeCurrent(...);  // Wrong! Called from JNI thread
    glDraw...();
}

// GOOD: Only JNI thread triggers render thread
nativeStartPlayback() {
    g_isRenderingActive = true;  // Render thread will handle
}

// BAD: Hold EGL context while paused
onPause() {
    // Don't eglMakeCurrent() - already released
}

// GOOD: Release context explicitly
onPause() {
    eglMakeCurrent(NO_CONTEXT);  // Let other apps use GPU
}
```

## Logcat Filtering

```bash
# View only AndroidPreview logs
adb logcat | grep "AndroidPreview"

# Real-time tail
adb logcat -c && adb logcat | grep "AndroidPreview"

# Save to file
adb logcat | grep "AndroidPreview" > android_logs.txt
```

## Expected Log Output

```
[AndroidPreview] EGL initialized: 1.4
[AndroidPreview] EGL context created and made current
[AndroidPreview] PreviewController initialized
[AndroidPreview] SurfaceSize: 1080x2340
[AndroidPreview] Video loaded: /sdcard/video.mp4 - SUCCESS
[AndroidPreview] Playback started @ 0ms
[AndroidPreview] Frame rendered @ 1000ms     # From seekToTime()
[AndroidPreview] Frame rendered @ 2000ms
[AndroidPreview] Rendering paused            # From onPause()
[AndroidPreview] Rendering resumed           # From onResume()
[AndroidPreview] Playback stopped
[AndroidPreview] EGL released
```

## Build & Deploy

```bash
# Build Android app + NDK native library
cd /home/am/video_engine_core/android
./gradlew build

# Install APK on device
./gradlew installDebug

# View native library location
find build -name "*.so"
# Output: build/.../libnative_preview.so

# Check device ABI
adb shell getprop ro.product.cpu.abi

# Monitor performance
adb shell dumpsys SurfaceFlinger | grep composition
```

## Performance Targets

| Metric | Target | Notes |
|--------|--------|-------|
| Frame rate | 60fps | = 16.67ms per frame |
| Frame latency | 16-32ms | One to two frames |
| Seek latency | 50-200ms | Depends on codec |
| Memory | <50MB | Scales with resolution |
| GPU util | 70-90% | With effects |
| CPU util | <20% | Most work on GPU |

## Thread Safety Checklist

```
✓ All EGL calls inside std::lock_guard<g_mutex>
✓ All GL calls after eglMakeCurrent()
✓ Atomic<bool> for state flags (no lock needed)
✓ Only release context on pause (not while paused)
✓ Render thread sleeps with NO locks held
✓ JNI calls must return quickly (don't block)
✓ Mutex unlocked before sleep
✓ Render thread joins before EGL terminate
```

## Debugging Tips

### App Crashes with "Signal 11"

Usually EGL not initialized:
```bash
adb logcat | grep "SIGSEGV"
# Check: Did "EGL initialized" log appear?
```

### No Rendering (Black Screen)

Check surface creation:
```bash
adb logcat | grep "AndroidPreview"
# Should see: "EGL context created"
#            "SurfaceSize: WIDTH x HEIGHT"
```

### Choppy Playback

Monitor frame drops:
```bash
adb shell dumpsys SurfaceFlinger --list
adb shell dumpsys SurfaceFlinger
# Look for composition performance
```

### High Memory Usage

Check decoded frames:
```bash
adb shell dumpsys meminfo | grep "libnative"
# Should be < 100MB for 1080p
```

---

## Key Concepts

**SurfaceView**: Direct GPU rendering, best for video
**EGL**: Platform layer between OpenGL and Android
**Render Thread**: Dedicated thread for 60fps rendering
**Mutex**: Protects EGL/global state
**Atomic**: Lock-free flags for state
**ANativeWindow**: Native handle to Surface
**eglMakeCurrent**: Activate context for rendering

---

## Files Reference

```
/android/
  ├─ build.gradle              # Gradle build config + NDK
  ├─ CMakeLists.txt            # CMake for native compilation
  ├─ settings.gradle           # Gradle settings + plugin repos
  ├─ local.properties          # Android SDK path
  ├─ gradle/wrapper/           # Gradle wrapper
  ├─ app/
  │  └─ src/main/
  │     ├─ kotlin/com/video/engine/
  │     │  ├─ VideoPreviewView.kt    # SurfaceView class
  │     │  └─ MainActivity.kt        # Example activity
  │     ├─ AndroidManifest.xml
  │     └─ res/
  └─ jni/
     └─ native_preview.cpp     # JNI + rendering loop

/ANDROID_RENDERING_LOOP_IMPLEMENTATION.md   # Full architecture
/ANDROID_INTEGRATION_QUICKSTART.md           # Usage guide
/ANDROID_ARCHITECTURE_DECISIONS.md           # Why this design
```

---

**Version**: 1.0  
**Date**: February 2026  
**Status**: Production Ready

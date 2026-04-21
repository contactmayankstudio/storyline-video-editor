# Timeline Scrubbing - Quick Reference

## The Flow

```
User drags timeline
    ↓
TimelineManager.handleScroll()
    ↓ (Calculate timeMs, throttle at 50ms)
↓
TimelineManager.OnScrubListener.onScrub(timeMs)
    ↓
MainActivity.onTimelineScrub(timeMs)
    ↓
NativeBridge.seekToTime(previewView, timeMs)
    ↓ (Log: "Scrub -> native seek XXXXX ms")
↓
previewView.seekToTime(timeMs)
    ↓ (JNI call)
↓
[JNI] Java_com_video_engine_VideoPreviewView_nativeSeekPreview()
    ↓
native_preview.cpp: nativeSeekPreview(timelineMs)
    ├─ Lock mutex
    ├─ eglMakeCurrent() [Acquire GL context]
    ├─ g_preview->scrubToTimelineTime(timelineMs)
    │  ├─ m_decoder->seekForPreview(timelineMs)
    │  ├─ m_decoder->decodeNextFrame() [Decode 1 frame]
    │  ├─ m_converter->convert() [YUV → RGBA]
    │  ├─ m_texture->update() [Upload to GPU]
    │  └─ m_renderer->renderFrame() [Draw to surface]
    │
    ├─ eglSwapBuffers() [Display frame]
    ├─ Log: "[Preview] seekTo XXXXX ms - frame rendered"
    └─ Unlock mutex
    
    ↓
Frame visible on screen (20-60ms latency)
```

---

## Key Components

### Android (Kotlin)

**TimelineManager:**
- `OnScrubListener` interface
- `setScrubListener()` to register callback
- `handleScroll()` throttles at 50ms
- Calls `listener.onScrub(timelineMs)` when throttle allows

**MainActivity:**
- `setupTimeline()` registers scrub listener
- `onTimelineScrub(timeMs)` handles scrub events
- Forwards to `NativeBridge.seekToTime()`

**NativeBridge:**
```kotlin
fun seekToTime(previewView: VideoPreviewView, timelineMs: Long) {
    Log.d(TAG, "Scrub -> native seek ${timelineMs} ms")
    previewView.seekToTime(timelineMs)
}
```

### Native (C++)

**native_preview.cpp:**
```cpp
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeSeekPreview(
    JNIEnv* env, jobject thiz, jlong timelineMs) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_preview || g_eglDisplay == EGL_NO_DISPLAY) return;
    eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext);
    g_preview->scrubToTimelineTime(timelineMs);
    eglSwapBuffers(g_eglDisplay, g_eglSurface);
    LOGI("[Preview] seekTo %lldms - frame rendered", timelineMs);
}
```

**PreviewController::scrubToTimelineTime():**
1. Stop playback
2. Seek decoder (fast, ~AVSEEK_FLAG_ANY)
3. Decode one frame (~10-50ms)
4. Convert YUV→RGBA (~2-5ms)
5. Upload to GPU (~1-2ms)
6. Render frame (~5-10ms)
7. Update time

---

## Why Throttling?

**Without throttle:** User drags → 100+ JNI calls/sec → Decoder queue fills → Jitter

**With 50ms throttle:** Max 20 seeks/sec → Decoder keeps up → Smooth scrubbing

---

## Debug Output

Check these tags in Logcat:

```
[TIMELINE]    - TimelineManager scroll events
[NativeBridge] - JNI wrapper logging
[Scrub]       - JNI scrubbing operation
[Preview]     - Native decoder/renderer
[AndroidPreview] - EGL initialization
```

---

## Performance Targets

- **Scrub response:** <100ms from drag to frame
- **Decode latency:** 10-50ms
- **Render latency:** 5-15ms
- **Max seeks/sec:** ~20 (50ms throttle)
- **Smooth UI:** No frame drops, no ANR

---

## Testing

1. Load video
2. Drag timeline left/right
3. Observe frame updates
4. Check Logcat:
   ```
   [TIMELINE] Scrub -> timeline scroll to 5000ms
   [NativeBridge] Scrub -> native seek 5000 ms
   [Preview] seekTo 5000ms - frame rendered
   ```
5. Drag rapidly - should feel smooth, not jittery

---

## Files Changed

- **NativeBridge.kt** (NEW)
- **TimelineManager.kt** (MODIFIED)
- **MainActivity.kt** (MODIFIED)
- **native_preview.cpp** (MODIFIED - fixed method call)

---

## Common Issues

| Issue | Cause | Fix |
|-------|-------|-----|
| No frame update on scrub | EGL context not current | Check eglMakeCurrent() |
| Jittery scrubbing | Throttle too small | Increase to 50ms |
| Crashes during scrub | Race condition | Verify mutex lock |
| Wrong frame shown | Decoder seeking to keyframe | Expected (fast seek not accurate) |
| Decoder latency >100ms | Low-end device | Normal for older phones |

---

## Key APIs

```kotlin
// TimelineManager
fun setScrubListener(listener: OnScrubListener?)
interface OnScrubListener {
    fun onScrub(timelineMs: Long)
}

// NativeBridge
fun seekToTime(previewView: VideoPreviewView, timelineMs: Long)

// VideoPreviewView
fun seekToTime(timelineMs: Long)
```

```cpp
// native_preview.cpp
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeSeekPreview(
    JNIEnv* env, jobject thiz, jlong timelineMs);

// PreviewController
void scrubToTimelineTime(int64_t timelineMs);
```

---

## Performance Breakdown

| Phase | Duration | Notes |
|-------|----------|-------|
| Throttle check | <1ms | Simple timestamp comparison |
| JNI overhead | 1-2ms | Method dispatch + lock |
| Decoder seek | 5-20ms | Find nearest keyframe |
| Decode frame | 5-40ms | H.264/H.265 decompression |
| Convert YUV→RGBA | 2-5ms | Vectorized math (NEON) |
| Upload texture | 1-2ms | DMA transfer to GPU |
| Render frame | 5-10ms | GPU parallelized |
| Buffer swap | <1ms | Wait for vsync |
| **TOTAL** | **20-80ms** | Typical: 30-60ms |

---

**Version:** 1.0  
**Date:** 2026-02-03

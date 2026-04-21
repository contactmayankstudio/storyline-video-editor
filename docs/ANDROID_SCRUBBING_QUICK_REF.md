# Android Scrubbing - Quick Reference Card

## One-Minute Setup

```bash
# 1. Build native library
cd /home/am/video_engine_core/android
./gradlew build

# 2. Install APK
./gradlew installDebug

# 3. Launch PreviewActivity
adb shell am start -n com.video.engine/.PreviewActivity

# 4. View scrubbing logs
adb logcat | grep "\[Scrub\]"
```

---

## Key JNI Methods (Updated)

| Method | Signature | Purpose | Timing |
|--------|-----------|---------|--------|
| `getDuration()` | `jlong getDuration()` | Get video duration in ms | After `loadVideo()` |
| `seekToTime(ms)` | `void seekToTime(long ms)` | Render frame at time | On SeekBar drag |
| `loadVideo(path)` | `boolean loadVideo(String)` | Load video file | Before scrubbing |
| `startPlayback(ms)` | `void startPlayback(long ms)` | Start continuous playback | On play button |
| `stopPlayback()` | `void stopPlayback()` | Stop playback | On pause/scrub start |

---

## Android UI Code (PreviewActivity.kt)

### SeekBar Listener Setup

```kotlin
seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (fromUser) {
            val timeMs = (progress.toLong() * videoDurationMs) / 1000L
            previewView.seekToTime(timeMs)  // Frame render
        }
    }
    
    override fun onStartTrackingTouch(seekBar: SeekBar) {
        previewView.stopPlayback()  // Enter scrub mode
    }
    
    override fun onStopTrackingTouch(seekBar: SeekBar) {
        // Scrubbing done
    }
})
```

### Load Video with Duration

```kotlin
private fun loadVideo() {
    previewView.loadVideo(videoPath)
    
    // Get duration
    videoDurationMs = previewView.getDuration()
    
    // Configure seekbar
    seekBar.max = (videoDurationMs / 1000).toInt()
    
    // Render first frame
    previewView.seekToTime(0)
}
```

---

## Native Code (C++)

### nativeGetDuration() Implementation

```cpp
JNIEXPORT jlong JNICALL
Java_com_video_engine_VideoPreviewView_nativeGetDuration(
    JNIEnv* env, jobject thiz) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_preview) return 0L;
    
    long long durationMs = g_preview->getDuration();
    LOGI("[Duration] duration=%lldms", (long long)durationMs);
    
    return (jlong)durationMs;
}
```

### nativeSeekPreview() for Scrubbing

```cpp
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeSeekPreview(
    JNIEnv* env, jobject thiz, jlong timelineMs) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_preview || g_eglDisplay == EGL_NO_DISPLAY) return;
    
    eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext);
    g_preview->seekPreview(timelineMs);  // Seek + decode + render
    eglSwapBuffers(g_eglDisplay, g_eglSurface);
    
    LOGI("[Scrub] time=%lldms", (long long)timelineMs);
}
```

---

## Latency Profile

```
Scrubbing seek latency (typical):

├─ JNI call + mutex lock:    1-2ms
├─ eglMakeCurrent():         1-3ms
├─ FFmpeg seek:              5-10ms
├─ Decode frame:             3-5ms
├─ GPU upload:               1-2ms
├─ Render:                   2-3ms
├─ eglSwapBuffers():         5-10ms
└─ Total:                    20-35ms ← User perceives as instant
```

**Expected feel**: Smooth, responsive, instant.

---

## Log Tags

```
[Scrub]             SeekBar scrubbing events
[Duration]          Duration retrieval
[AndroidPreview]    Preview lifecycle
```

### View Logs

```bash
# Scrubbing events only
adb logcat | grep "\[Scrub\]"

# All preview logs
adb logcat | grep "AndroidPreview\|Scrub\|Duration"

# Real-time (with color)
adb logcat -C | grep "Scrub"
```

---

## Common Patterns

### Pattern 1: Initialize & Scrub

```kotlin
// 1. Create view
val previewView = VideoPreviewView(context)
container.addView(previewView)

// 2. Load video
previewView.loadVideo("/sdcard/video.mp4")

// 3. Get duration
val durationMs = previewView.getDuration()
seekBar.max = (durationMs / 1000).toInt()

// 4. Scrub via seekbar listener
seekBar.setOnSeekBarChangeListener { progress, _ ->
    val timeMs = (progress.toLong() * durationMs) / 1000L
    previewView.seekToTime(timeMs)
}
```

### Pattern 2: Debounced Scrubbing

```kotlin
private var lastSeekTimeMs = 0L

override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
    if (!fromUser) return
    
    val timeMs = (progress.toLong() * videoDurationMs) / 1000L
    
    // Skip if time change < 100ms
    if (Math.abs(timeMs - lastSeekTimeMs) < 100) return
    
    previewView.seekToTime(timeMs)
    lastSeekTimeMs = timeMs
}
```

### Pattern 3: Playback Toggle

```kotlin
var isPlaying = false

playButton.setOnClickListener {
    if (isPlaying) {
        previewView.stopPlayback()
    } else {
        previewView.startPlayback(currentTimeMs)
    }
    isPlaying = !isPlaying
}

// Stop playback when scrubbing starts
seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onStartTrackingTouch(seekBar: SeekBar) {
        if (isPlaying) {
            previewView.stopPlayback()
            isPlaying = false
        }
    }
})
```

---

## Permissions (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />

<!-- For Android 12+ (required) -->
<uses-feature
    android:name="android.hardware.opengles.version"
    android:required="true"
    android:glEsVersion="0x00030000" />
```

---

## Activity Declaration (AndroidManifest.xml)

```xml
<activity
    android:name=".PreviewActivity"
    android:exported="true"
    android:label="@string/activity_preview_label"
    android:configChanges="orientation|screenSize|keyboardHidden" />
```

---

## File Overview

| File | Size | Purpose |
|------|------|---------|
| PreviewActivity.kt | 270 lines | Scrubbing UI |
| VideoPreviewView.kt | 260 lines | EGL + OpenGL ES |
| native_preview.cpp | 630+ lines | JNI bridge + render thread |
| AndroidManifest.xml | 50 lines | App config |
| build.gradle | 60 lines | NDK setup |
| CMakeLists.txt | 140 lines | Native build |

---

## Troubleshooting

### getDuration() returns 0?

```kotlin
previewView.loadVideo(videoPath)
Thread.sleep(100)  // Wait for init
val duration = previewView.getDuration()
if (duration > 0) {
    seekBar.max = (duration / 1000).toInt()
}
```

### Scrubbing is slow (> 100ms)?

```bash
# Check GPU
adb shell "dumpsys gfxinfo" | grep -i fps

# View native logs
adb logcat | grep "AndroidPreview"

# Profile with timing
adb logcat -v time | grep "\[Scrub\]"
```

### SeekBar max is 0?

```kotlin
val duration = previewView.getDuration()
if (duration == 0L) {
    Log.e(TAG, "Failed to load video duration")
    return
}
seekBar.max = (duration / 1000).toInt()
```

### Orientation change freezes UI?

**Already handled** by `android:configChanges="orientation|screenSize"` in manifest.

---

## Performance Targets

- **Seek latency**: < 50ms (feel instant)
- **GPU utilization**: 40-60% during scrubbing
- **Memory overhead**: ~10-20MB
- **Battery impact**: Low (on-demand, not continuous)

---

## Build & Deploy Quick Command

```bash
cd /home/am/video_engine_core/android && \
./gradlew installDebug && \
adb shell am start -n com.video.engine/.PreviewActivity && \
adb logcat | grep "\[Scrub\]"
```

---

## Reference Docs

- **[ANDROID_TIMELINE_SCRUBBING.md](ANDROID_TIMELINE_SCRUBBING.md)** - Deep dive on scrubbing architecture
- **[ANDROID_SCRUBBING_IMPLEMENTATION.md](ANDROID_SCRUBBING_IMPLEMENTATION.md)** - Complete implementation guide
- **[ANDROID_RENDERING_LOOP_IMPLEMENTATION.md](ANDROID_RENDERING_LOOP_IMPLEMENTATION.md)** - EGL lifecycle details
- **[ANDROID_QUICK_REFERENCE.md](ANDROID_QUICK_REFERENCE.md)** - JNI cheat sheet

---

## Version Info

- **Android API**: 21+ (supports 99% of devices)
- **NDK Version**: r21+ (with C++17)
- **OpenGL ES**: 3.0 (required)
- **FFmpeg**: 4.0+ (from engine build)

---

**Last Updated**: Phase 2 Scrubbing Complete

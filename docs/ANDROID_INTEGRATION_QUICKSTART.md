# Android OpenGL Integration - Quick Start Guide

## Setup & Build

### 1. Prerequisites

```bash
# Install Android SDK/NDK (via Android Studio or command line)
# https://developer.android.com/studio/command-line/

# Minimum SDK: API 21 (Android 5.0)
# Target SDK: API 33+ (Android 13+)
# NDK: r21+
```

### 2. Build for Android

```bash
cd /home/am/video_engine_core

# Create build directory
mkdir -p android/build
cd android/build

# Configure with Android NDK
cmake -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-21 \
      -DCMAKE_BUILD_TYPE=Release \
      ..

# Build
make -j$(nproc)

# Output: libnative_preview.so (in android/build/lib/)
```

### 3. Gradle Build

```bash
cd android

# Build APK
./gradlew build

# Install on device
./gradlew installDebug

# Run
./gradlew run
```

---

## Usage in Your Activity

### Minimal Example

```kotlin
// MainActivity.kt
import com.video.engine.VideoPreviewView

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: VideoPreviewView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Create preview view
        previewView = VideoPreviewView(this)
        
        // Add to layout
        val container = FrameLayout(this).apply {
            addView(previewView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        setContentView(container)
        
        // Request permissions (Android 6.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadVideo()
        }
    }
    
    private fun loadVideo() {
        val videoPath = "/sdcard/DCIM/Camera/video.mp4"
        previewView.loadVideo(videoPath)
        previewView.startPlayback(0)  // Start from 0ms
    }
    
    override fun onResume() {
        super.onResume()
        previewView.onResume()
    }
    
    override fun onPause() {
        previewView.onPause()
        super.onPause()
    }
    
    override fun onDestroy() {
        previewView.stopPlayback()
        super.onDestroy()
    }
}
```

---

## VideoPreviewView API

### Loading & Playback

```kotlin
// Load video file
previewView.loadVideo("/sdcard/video.mp4")

// Start playback from specific time (milliseconds)
previewView.startPlayback(0)      // From beginning
previewView.startPlayback(5000)   // From 5 seconds

// Stop playback and pause rendering
previewView.stopPlayback()

// Seek to specific time and render that frame
previewView.seekToTime(3000)  // Jump to 3 seconds (no playback)

// Pause rendering (keep EGL context, pause video)
previewView.onPause()

// Resume rendering
previewView.onResume()

// Get video duration (after loading)
val duration = previewView.getDuration()  // milliseconds
```

### Lifecycle Management

```kotlin
// Call in Activity.onResume()
override fun onResume() {
    super.onResume()
    previewView.onResume()  // Restores EGL context
}

// Call in Activity.onPause()
override fun onPause() {
    previewView.onPause()   // Releases EGL context
    super.onPause()
}

// Call in Activity.onDestroy()
override fun onDestroy() {
    previewView.stopPlayback()
    super.onDestroy()
}
```

---

## Common Use Cases

### Play Video Thumbnail (1 frame)

```kotlin
previewView.loadVideo("/sdcard/video.mp4")
previewView.seekToTime(0)  // Render frame at 0ms (no playback loop)
// Frame is rendered and displayed, then preview is idle
```

### Scrubbing / Seeking

```kotlin
// User dragging seekbar (e.g., 0-100)
seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (fromUser) {
            val durationMs = previewView.getDuration()
            val targetMs = (progress / 100.0 * durationMs).toLong()
            previewView.seekToTime(targetMs)  // Jump to frame, don't auto-play
        }
    }
    
    override fun onStartTrackingTouch(seekBar: SeekBar) {}
    override fun onStopTrackingTouch(seekBar: SeekBar) {}
})
```

### Video Playback with Controls

```kotlin
playButton.setOnClickListener {
    previewView.startPlayback(0)  // Play from current position
}

pauseButton.setOnClickListener {
    previewView.stopPlayback()  // Pause
}

// For resume, just call startPlayback again from current time
resumeButton.setOnClickListener {
    val currentTime = 5000L  // Get from playback state
    previewView.startPlayback(currentTime)
}
```

### Full-Screen Preview Activity

```kotlin
class PreviewActivity : AppCompatActivity() {
    private lateinit var previewView: VideoPreviewView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Hide system UI for immersive view
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        
        previewView = VideoPreviewView(this)
        setContentView(previewView)
        
        val videoPath = intent.getStringExtra("video_path")
        previewView.loadVideo(videoPath)
        previewView.startPlayback(0)
    }
    
    override fun onResume() {
        super.onResume()
        previewView.onResume()
    }
    
    override fun onPause() {
        previewView.onPause()
        super.onPause()
    }
}
```

---

## Troubleshooting

### App Crashes on `loadVideo()`

**Cause:** File not found or permission denied

**Fix:**
```kotlin
// Check file exists
val file = File(videoPath)
if (!file.exists()) {
    Log.e("Preview", "File not found: $videoPath")
    return
}

// Check permissions
if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
    != PackageManager.PERMISSION_GRANTED) {
    requestPermissions(...)
    return
}
```

### Black Screen on Preview

**Cause:** Surface not created, or EGL initialization failed

**Fix:**
```kotlin
// Ensure loadVideo() called AFTER surface is ready
previewView.post {
    previewView.loadVideo(videoPath)
    previewView.startPlayback(0)
}
```

### Video Plays but Freezes/Stutters

**Cause:** Frame rate drops due to:
- 60fps target on slow device
- No dedicated render thread
- Video codec not hardware-accelerated

**Fix:**
```kotlin
// Reduce video bitrate (re-encode in lower quality)
// Or add dedicated render thread (see architecture doc)
// Or check device GPU: adb shell dumpsys SurfaceFlinger | grep composition
```

### App Crashes on Screen Rotation

**Cause:** Surface destroyed/recreated, EGL context lost

**Fix:** Already handled in VideoPreviewView, but ensure:
```kotlin
// Disable rotation in manifest if not needed
<activity android:screenOrientation="portrait" />

// Or handle rotation in activity
override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    // VideoPreviewView handles this automatically
}
```

### JNI "UnsatisfiedLinkError"

**Cause:** libnative_preview.so not found or wrong ABI

**Fix:**
```gradle
// build.gradle
android {
    ndkVersion "21.3.6528147"  // Your NDK version
    defaultConfig {
        ndk {
            abiFilters 'arm64-v8a', 'armeabi-v7a'
        }
    }
}
```

Verify SO exists:
```bash
./gradlew build
find build -name "*.so"
# Should see: build/.../libnative_preview.so
```

---

## Performance Tuning

### Target Frame Rates

```kotlin
// 60fps for smooth video
previewView.startPlayback(0)  // Renders at 60fps

// For lower-end devices, reduce video complexity:
// 1. Lower resolution (720p instead of 1080p)
// 2. Lower bitrate
// 3. Use H.264 (faster than H.265)
```

### Memory Usage

```kotlin
// Video engine holds decoded frames in GPU memory (~8MB per frame)
// For 1080p@60fps: ~1 decoded frame = ~8MB VRAM
// Multiple videos: Memory scales per video loaded

// To reduce: Close unused videos
previewView.stopPlayback()  // Releases resources
```

### GPU Utilization

```kotlin
// Monitor with Android Profiler
// adb shell dumpsys SurfaceFlinger | grep composition
// Should show "composition" mode (not "ERROR")

// Check GPU load: adb shell top -b | grep com.video
```

---

## Architecture Decisions

### Why SurfaceView?

✅ **SurfaceView (used)**
- Dedicated surface, best for GPU
- Lower latency
- Better 60fps performance
- Used by YouTube, KineMaster, VLC

❌ **TextureView (not used)**
- Requires UI thread synchronization
- Extra composition overhead
- Higher latency

### Why JNI?

✅ **JNI (used)**
- Direct C++ access to FFmpeg, OpenGL
- Maximum performance
- Low latency

❌ **Pure Kotlin (not used)**
- FFmpeg not available on Android
- No direct GPU control
- Slower

### Engine Core Unchanged

The C++ video engine (`core/`, `engine/`, `backend/ffmpeg/`, `backend/gpu/`) runs unchanged on Android:
- Same PreviewController interface
- Same GPU rendering pipeline
- Same FFmpeg decoding
- Only added JNI glue in `android/jni/native_preview.cpp`

This means the engine is truly portable:
- Desktop (FFmpeg OpenGL)
- iOS (FFmpeg Metal)
- Android (FFmpeg OpenGL ES 3.0)

---

## Next Steps

1. **Try the example**: Build and run Android app
2. **Load your video**: Point to actual video file
3. **Add controls**: Play/pause buttons, seekbar
4. **Monitor performance**: Use Android Profiler
5. **Optimize for your device**: Adjust video codec/bitrate
6. **Add effects**: The GPU pipeline supports real-time effects (see GPU_RENDERER_GUIDE.md)

---

## Files Reference

| File | Purpose |
|------|---------|
| `VideoPreviewView.kt` | Custom SurfaceView, lifecycle management |
| `MainActivity.kt` | Example activity showing usage |
| `native_preview.cpp` | JNI bridge to C++ engine |
| `CMakeLists.txt` | NDK build configuration |
| `build.gradle` | Gradle build configuration |
| `AndroidManifest.xml` | App permissions, activities |

---

## Further Reading

- [SurfaceView vs TextureView](https://developer.android.com/reference/android/view/SurfaceView)
- [EGL Documentation](https://www.khronos.org/egl/)
- [OpenGL ES 3.0 Specification](https://www.khronos.org/opengles/)
- [Android NDK Overview](https://developer.android.com/ndk)
- [JNI Type Mappings](https://docs.oracle.com/javase/8/docs/technotes/guides/jni/spec/types.html)

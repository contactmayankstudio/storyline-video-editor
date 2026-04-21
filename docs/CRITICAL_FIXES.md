# Video Editor Critical Bug Fixes and Improvements

## 1. Fix NativeBridge.executeCommand Main-Thread Blocking

**Problem**: The `executeCommand` method blocks the main thread with a `CountDownLatch` for up to 8 seconds. If a native command takes longer, it returns a timeout result while the background thread continues executing, leading to inconsistent state and potential ANRs.

**Solution**: Convert synchronous commands to fully asynchronous. Update UI callers to handle async results using coroutines.

### Patch: android/app/src/main/kotlin/com/video/engine/NativeBridge.kt

```kotlin
// Remove the blocking latch logic
fun executeCommand(action: String, params: Map<String, Any> = emptyMap()): CommandResult {
    // Always async now - return immediately with queued status
    executeCommandAsync(action, params)
    return CommandResult(
        success = true,
        action = action,
        message = "Command queued asynchronously",
        data = JSONObject().put("queued", true)
    )
}

// Keep executeCommandAsync as is, but ensure all callers use async patterns
```

### UI Caller Updates

For query commands like `GET_TIMELINE_LAYOUT`, wrap with coroutine:

```kotlin
suspend fun getTimelineLayout(): JSONObject? {
    return suspendCoroutine { continuation ->
        executeCommandAsync("GET_TIMELINE_LAYOUT") { result ->
            continuation.resume(result.data)
        }
    }
}
```

## 2. Validate Export Audio Clip Arrays

**Problem**: `nativeSetExportAudioClips` iterates over `pathsArray` length but reads other arrays without length validation, risking crashes on mismatched arrays.

**Solution**: Add length checks and log warnings for mismatches.

### Patch: android/jni/native_preview.cpp

```cpp
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeSetExportAudioClips(
    JNIEnv* env, jobject thiz,
    jobjectArray pathsArray,
    jlongArray startTimesMs,
    jlongArray durationsMs,
    jfloatArray volumes) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_audioExportClips.clear();
    if (!pathsArray) return;
    
    const jsize pathsCount = env->GetArrayLength(pathsArray);
    const jsize startsCount = startTimesMs ? env->GetArrayLength(startTimesMs) : 0;
    const jsize dursCount = durationsMs ? env->GetArrayLength(durationsMs) : 0;
    const jsize volsCount = volumes ? env->GetArrayLength(volumes) : 0;
    
    if (startsCount != pathsCount || dursCount != pathsCount || volsCount != pathsCount) {
        LOGE("[Export] Array length mismatch: paths=%d starts=%d durs=%d vols=%d",
             (int)pathsCount, (int)startsCount, (int)dursCount, (int)volsCount);
        return;
    }
    
    // Rest of the function unchanged...
}
```

## 3. Add Export Completion Callback

**Problem**: Export progress is polled from Kotlin, but completion/failure events are not pushed back.

**Solution**: Add a JNI callback interface for export events.

### New Interface: android/app/src/main/kotlin/com/video/engine/ExportCallback.kt

```kotlin
interface ExportCallback {
    fun onExportProgress(progress: Int)
    fun onExportCompleted(success: Boolean, outputPath: String?, error: String?)
}
```

### Update VideoPreviewView.kt

```kotlin
private var exportCallback: ExportCallback? = null

fun startExport(
    outputPath: String, width: Int, height: Int, fps: Int,
    audioPaths: Array<String> = emptyArray(),
    audioStartMs: LongArray = LongArray(0),
    audioDurMs: LongArray = LongArray(0),
    audioVols: FloatArray = FloatArray(0),
    callback: ExportCallback? = null
) {
    exportCallback = callback
    // ... existing code
}
```

### Update native_preview.cpp Export Thread

```cpp
g_exportThread = std::thread([inputPaths, clipSpecs, outputPath, width, height, fps, callback]() {
    // ... existing render code ...
    const bool success = renderTimelineWithMixedAudioToMp4(...);
    if (success) {
        // Call back to Java on completion
        if (callback) {
            JNIEnv* env = getJNIEnv();
            if (env) {
                jstring pathStr = env->NewStringUTF(outputPath.c_str());
                env->CallVoidMethod(callback, onCompletedMethod, JNI_TRUE, pathStr, nullptr);
                env->DeleteLocalRef(pathStr);
            }
        }
    } else {
        // Call back with error
        if (callback) {
            JNIEnv* env = getJNIEnv();
            if (env) {
                jstring errorStr = env->NewStringUTF(exportError.c_str());
                env->CallVoidMethod(callback, onCompletedMethod, JNI_FALSE, nullptr, errorStr);
                env->DeleteLocalRef(errorStr);
            }
        }
    }
    g_isExporting.store(false, std::memory_order_release);
});
```

## 4. Audio/Video Sync Design Doc

### Current Architecture Issues

- **Dual Clock Problem**: Video uses native atomic clock (`g_currentTimeMs`), audio uses Android `MediaPlayer` clock
- **Sync Mechanism**: Audio pushes PTS to native via `updateAudioClockUs()`, but this is reactive and can drift
- **Threading**: Audio runs on Kotlin handler thread, video on native render thread

### Proposed Single Clock Architecture

1. **Authoritative Clock**: Move to native-side clock only (`g_currentTimeMs`)
2. **Audio Slave**: Make `PreviewAudioPlayer` poll native clock and seek `MediaPlayer` to match
3. **Sync Loop**: Run audio sync on a dedicated thread at 30Hz

### Implementation

```kotlin
class PreviewAudioPlayer(...) {
    private val syncThread = HandlerThread("AudioSync").apply { start() }
    private val syncHandler = Handler(syncThread.looper)
    
    private val syncRunnable = object : Runnable {
        override fun run() {
            val nativeTimeMs = NativeBridge.getCurrentPlaybackTimeFast()
            val audioTimeMs = mediaPlayer?.currentPosition?.toLong() ?: 0L
            
            val driftMs = nativeTimeMs - audioTimeMs
            if (Math.abs(driftMs) > 50) { // 50ms tolerance
                mediaPlayer?.seekTo(nativeTimeMs.toInt())
            }
            
            syncHandler.postDelayed(this, 33) // ~30Hz
        }
    }
    
    fun startSync() {
        syncHandler.post(syncRunnable)
    }
    
    fun stopSync() {
        syncHandler.removeCallbacks(syncRunnable)
    }
}
```

### Benefits

- Eliminates drift by making audio reactive to video clock
- Reduces JNI calls (poll instead of push)
- More predictable sync behavior

## 5. Preview Implementation Consolidation

**Problem**: Two preview paths exist (`native_preview.cpp` and `engine/engine.cpp`)

**Recommendation**: 
- Keep `native_preview.cpp` as the active implementation
- Remove or deprecate `engine/engine.cpp` preview code
- Update any legacy references to use the native preview path

This reduces maintenance burden and ensures consistent behavior.</content>
<parameter name="filePath">/home/am/storyline/CRITICAL_FIXES.md
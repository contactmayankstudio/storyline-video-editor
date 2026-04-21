# Audio/Video Synchronization Design Doc

## Current Architecture

The app uses a dual-clock model for A/V sync during preview playback:

### Video Clock (Native)
- **Location**: `native_preview.cpp` render thread
- **Mechanism**: Dedicated render thread polls `g_currentTimeMs` atomic variable
- **Update**: Incremented by render loop based on wall-clock time and playback speed
- **Sync Source**: Authoritative timeline clock for video rendering

### Audio Clock (Kotlin)
- **Location**: `PreviewAudioPlayer.kt` audio thread
- **Mechanism**: `MediaPlayer` plays audio clips, position calculated back to timeline time
- **Update**: Ticker runs every 33ms to check `currentTimelineTimeMs()` and dispatch to native
- **Sync Source**: Derived from `MediaPlayer.currentPosition` mapped back to timeline

### Synchronization Flow
1. User starts playback → `nativeStartPlayback(timeMs)` sets `g_currentTimeMs`
2. Render thread begins continuous rendering, updating `g_currentTimeMs` with wall-clock progression
3. Audio player ticker checks timeline time every 33ms
4. If audio source changes, switches `MediaPlayer` and seeks to correct position
5. Dispatches current audio PTS to native via `nativeUpdateAudioClockUs()` for video sync

## Problems with Current Approach

1. **Polling-Based**: 33ms ticker introduces ~16-33ms latency in audio source switching
2. **Dual Clocks**: Video and audio maintain separate timeline calculations, risking drift
3. **No Hardware Sync**: Relies on software PTS dispatch, not MediaSync or A/V sync hardware
4. **Seek Latency**: `MediaPlayer` seeks are asynchronous, causing temporary sync loss
5. **Thread Overhead**: Audio thread ticker adds CPU load for frequent timeline queries

## Recommended Improvements

### Option 1: Single Authoritative Clock (Preferred)
- Move all timeline progression to native side
- Audio player becomes a slave that receives timeline updates
- Use `MediaPlayer` position callbacks for fine-grained sync

**Implementation**:
- Native render thread owns `g_currentTimeMs` as the single source of truth
- Add JNI callback to notify Kotlin audio player of timeline position changes
- Audio player seeks reactively to match native timeline
- Use `MediaPlayer.OnSeekCompleteListener` and position updates for sync

### Option 2: Hardware Sync Enhancement
- Use Android's `MediaSync` API (API 23+) for A/V sync
- Keep dual clocks but synchronize via hardware timestamps
- Requires MediaCodec integration for video

### Option 3: Callback-Driven Audio
- Replace polling ticker with `MediaPlayer` position listener
- Audio player calculates timeline position and pushes to native more frequently
- Reduces latency from 33ms to ~10-20ms

## Migration Plan

1. **Phase 1**: Add native→Kotlin timeline position callback
2. **Phase 2**: Modify audio player to react to timeline updates instead of polling
3. **Phase 3**: Optimize seek behavior and add buffering for smooth transitions
4. **Phase 4**: Add drift detection and correction logic

## Benefits
- Eliminates sync drift between video and audio
- Reduces latency in audio source switching
- Simplifies architecture with single timeline clock
- Enables smoother playback and better user experience
        }
    }

    fun startPlayback(timelineMs: Long) {
        // ... existing playback start code ...
        startAudioSync()
    }

    fun pause() {
        // ... existing pause code ...
        stopAudioSync()
    }

    fun seekTo(timelineMs: Long, continuePlaying: Boolean) {
        // ... existing seek code ...
        if (continuePlaying) {
            startAudioSync()
        } else {
            stopAudioSync()
        }
    }

    private fun startAudioSync() {
        if (isSyncActive) return
        isSyncActive = true
        syncHandler.post(syncRunnable)
        Log.d(TAG, "Audio sync started")
    }

    private fun stopAudioSync() {
        isSyncActive = false
        syncHandler.removeCallbacks(syncRunnable)
        Log.d(TAG, "Audio sync stopped")
    }

    fun release() {
        stopAudioSync()
        syncThread.quitSafely()
        // ... existing release code ...
    }
}
```

### Benefits

- **Eliminates Drift**: Audio is always chasing the video clock
- **Reduces JNI Calls**: Poll instead of push reduces overhead
- **Handles Interruptions**: Sync loop recovers from MediaPlayer delays
- **Configurable Tolerance**: 50ms default, adjustable for quality vs. performance

### Potential Issues

- **Seek Overhead**: Frequent seeks may cause audio glitches
- **Thread Management**: Additional thread increases complexity
- **Battery Impact**: 30Hz polling on background thread

### Alternatives Considered

1. **Push from Native**: Have native render thread push time updates to Kotlin
   - Pro: More responsive
   - Con: Increases JNI call frequency

2. **MediaPlayer Clock Master**: Make MediaPlayer the master and have video chase
   - Pro: Leverages MediaPlayer's built-in sync
   - Con: Video seeking is slower than audio seeking

3. **Hybrid Approach**: Use push for playback, poll for scrubbing
   - Pro: Balances responsiveness
   - Con: More complex logic

The proposed single clock with polling is recommended as it provides good sync quality with manageable complexity.</content>
<parameter name="filePath">/home/am/storyline/AV_SYNC_DESIGN.md
# Timeline UX Implementation - Technical Guide

## Complete Integration Architecture

---

## Part 1: Android UI Layer (MainActivity.kt)

### 1.1 Variables to Add

```kotlin
private var selectedClipId: Int = -1
private var timelineManager: TimelineManager? = null
private var deleteButton: Button? = null
private var splitButton: Button? = null
```

### 1.2 Layout References in onCreate()

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    
    // Timeline UI
    timelineRecyclerView = findViewById(R.id.timelineRecyclerView)
    timelineCurrentTimeText = findViewById(R.id.timelineCurrentTimeText)
    deleteButton = findViewById(R.id.deleteClipButton)
    splitButton = findViewById(R.id.splitClipButton)
    
    // ... other setup
    setupTimeline()
    setupDeleteButton()
    setupSplitButton()
}
```

### 1.3 Timeline Setup Function

```kotlin
private fun setupTimeline() {
    val timelineManager = TimelineManager(
        recyclerView = timelineRecyclerView!!,
        timeDisplay = timelineCurrentTimeText!!,
        clips = timeline.getAllClips() as MutableList<TimelineClip>
    )
    
    this.timelineManager = timelineManager
    timelineManager.setupRecyclerView()
    
    // Set interaction listener
    timelineManager.setListener(object : TimelineManager.OnScrubListener {
        override fun onScrub(timelineMs: Long) {
            handleTimelineScrub(timelineMs)
        }
        
        override fun onClipSelected(clipId: Int) {
            handleClipSelected(clipId)
        }
        
        override fun onClipDragStart(clipId: Int) {
            Log.d("[Timeline]", "drag start: ID=$clipId")
        }
        
        override fun onClipDragEnd(clipId: Int) {
            Log.d("[Timeline]", "drag end: ID=$clipId")
            // Update native timeline with new clip order
            NativeBridge.updateClipOrder(previewView!!)
        }
    })
    
    Log.d("[Timeline]", "Timeline initialized")
}

private fun handleTimelineScrub(timelineMs: Long) {
    currentTimeMs = timelineMs
    updateTimeDisplay(timelineMs)
    
    // Throttle seeks to 50ms
    val now = SystemClock.uptimeMillis()
    if (now - lastNativeSeekMs > 50) {
        NativeBridge.seekToTime(previewView!!, timelineMs)
        lastNativeSeekMs = now
    }
    
    Log.d("[Timeline]", "seek to: timeMs=$timelineMs")
}

private fun handleClipSelected(clipId: Int) {
    selectedClipId = clipId
    
    if (clipId != -1) {
        // Clip selected
        deleteButton?.visibility = View.VISIBLE
        splitButton?.visibility = View.VISIBLE
        
        // Update preview to show this clip
        NativeBridge.setActiveClip(previewView!!, clipId)
        
        // Update effects UI to show this clip's effects
        updateEffectsUIForClip(clipId)
        
        Log.d("[Timeline]", "clip selected: ID=$clipId")
    } else {
        // No clip selected
        deleteButton?.visibility = View.GONE
        splitButton?.visibility = View.GONE
    }
}

private fun updateEffectsUIForClip(clipId: Int) {
    // Get clip's effects from native layer
    // Update effects UI sliders to show current values
    // When user changes sliders, effects apply to this clip
}
```

### 1.4 Delete Button Handler

```kotlin
private fun setupDeleteButton() {
    deleteButton?.setOnClickListener {
        if (selectedClipId != -1) {
            // Delete from UI
            timelineManager?.deleteSelectedClip()
            
            // Delete from native
            NativeBridge.deleteClip(previewView!!, selectedClipId)
            
            // Reset selection
            selectedClipId = -1
            deleteButton?.visibility = View.GONE
            splitButton?.visibility = View.GONE
            
            Log.d("[Timeline]", "delete: ID=$selectedClipId")
        }
    }
}
```

### 1.5 Split Button Handler

```kotlin
private fun setupSplitButton() {
    splitButton?.setOnClickListener {
        if (selectedClipId != -1) {
            // Split at current playhead position
            val splitTimeMs = currentTimeMs
            
            // Split in UI
            timelineManager?.splitSelectedClip(selectedClipId, splitTimeMs)
            
            // Split in native
            NativeBridge.splitClip(previewView!!, selectedClipId, splitTimeMs)
            
            Log.d("[Timeline]", "split: ID=$selectedClipId at timeMs=$splitTimeMs")
        }
    }
}
```

---

## Part 2: TimelineManager Enhancement

### 2.1 Add to TimelineManager.kt

```kotlin
interface OnScrubListener {
    fun onScrub(timelineMs: Long)
    fun onClipSelected(clipId: Int)
    fun onClipDragStart(clipId: Int)
    fun onClipDragEnd(clipId: Int)
}

class TimelineManager(...) {
    
    private var scrubListener: OnScrubListener? = null
    
    fun setListener(listener: OnScrubListener?) {
        scrubListener = listener
    }
    
    fun deleteSelectedClip() {
        adapter.deleteClip(selectedClipId)
        scrubListener?.onClipSelected(-1)
    }
    
    fun splitSelectedClip(clipId: Int, timeMs: Long) {
        adapter.splitClip(clipId, timeMs)
    }
    
    // ... rest of TimelineManager
}
```

### 2.2 Adapter Interaction Listener

In TimelineAdapter.kt, enhance the onClipInteractionListener:

```kotlin
adapter.setInteractionListener(object : TimelineAdapter.OnClipInteractionListener {
    override fun onClipClick(clipId: Int) {
        Log.d("[Timeline]", "UI: clip $clipId clicked")
        adapter.selectClip(clipId)
        scrubListener?.onClipSelected(clipId)
    }

    override fun onClipLongPress(clipId: Int, position: Int) {
        Log.d("[Timeline]", "UI: clip $clipId long-pressed")
        scrubListener?.onClipDragStart(clipId)
    }

    override fun onStartDrag(viewHolder: RecyclerView.ViewHolder) {
        // Enable drag-to-reorder if ItemTouchHelper available
        // or handle custom drag logic
    }
})
```

---

## Part 3: Native JNI Integration

### 3.1 Add to NativeBridge.kt

```kotlin
object NativeBridge {
    
    fun setActiveClip(previewView: VideoPreviewView, clipId: Int) {
        Log.d("[NativeBridge]", "setActiveClip: clipId=$clipId")
        previewView.setActiveClip(clipId)
    }
    
    fun deleteClip(previewView: VideoPreviewView, clipId: Int) {
        Log.d("[NativeBridge]", "deleteClip: clipId=$clipId")
        previewView.deleteClip(clipId)
    }
    
    fun splitClip(previewView: VideoPreviewView, clipId: Int, timeMs: Long) {
        Log.d("[NativeBridge]", "splitClip: clipId=$clipId at timeMs=$timeMs")
        previewView.splitClip(clipId, timeMs)
    }
    
    fun updateClipOrder(previewView: VideoPreviewView) {
        Log.d("[NativeBridge]", "updateClipOrder")
        previewView.updateClipOrder()
    }
}
```

### 3.2 Add JNI Declarations to VideoPreviewView.kt

```kotlin
class VideoPreviewView : SurfaceView, SurfaceHolder.Callback {
    
    // ... existing code
    
    private external fun nativeSetActiveClip(clipId: Int)
    private external fun nativeDeleteClip(clipId: Int)
    private external fun nativeSplitClip(clipId: Int, timeMs: Long)
    private external fun nativeUpdateClipOrder()
    
    fun setActiveClip(clipId: Int) {
        Log.d("[JNI]", "setActiveClip: clipId=$clipId")
        nativeSetActiveClip(clipId)
    }
    
    fun deleteClip(clipId: Int) {
        Log.d("[JNI]", "deleteClip: clipId=$clipId")
        nativeDeleteClip(clipId)
    }
    
    fun splitClip(clipId: Int, timeMs: Long) {
        Log.d("[JNI]", "splitClip: clipId=$clipId at timeMs=$timeMs")
        nativeSplitClip(clipId, timeMs)
    }
    
    fun updateClipOrder() {
        Log.d("[JNI]", "updateClipOrder")
        nativeUpdateClipOrder()
    }
}
```

---

## Part 4: Native C++ Implementation (native_preview.cpp)

### 4.1 Add Global Variables

```cpp
// Selected clip
static std::atomic<int32_t> g_activeClipId{-1};

// Timeline clips management
static std::vector<Clip> g_timelineClips;

// Clip ordering
static std::vector<int32_t> g_clipOrder;
```

### 4.2 Add JNI Handlers

```cpp
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeSetActiveClip(
    JNIEnv* env, jobject thiz, jint clipId) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    g_activeClipId.store(clipId, std::memory_order_release);
    
    if (g_preview) {
        g_preview->setActiveClip(clipId);
    }
    
    LOGI("[Timeline] native: active clip set to %d", clipId);
}

JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeDeleteClip(
    JNIEnv* env, jobject thiz, jint clipId) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (g_preview) {
        g_preview->deleteClip(clipId);
        
        // Remove clip from order list
        auto it = std::find(g_clipOrder.begin(), g_clipOrder.end(), clipId);
        if (it != g_clipOrder.end()) {
            g_clipOrder.erase(it);
        }
    }
    
    LOGI("[Timeline] native: clip %d deleted", clipId);
}

JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeSplitClip(
    JNIEnv* env, jobject thiz, jint clipId, jlong timeMs) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (g_preview) {
        g_preview->splitClip(clipId, timeMs);
        
        // New clips will be added to g_timelineClips
        // Update g_clipOrder accordingly
    }
    
    LOGI("[Timeline] native: clip %d split at %lld ms", clipId, (long long)timeMs);
}

JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeUpdateClipOrder(
    JNIEnv* env, jobject thiz) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (g_preview) {
        // Reorder clips in timeline based on g_clipOrder
        g_preview->updateClipOrder(g_clipOrder);
    }
    
    LOGI("[Timeline] native: clip order updated");
}
```

### 4.3 PreviewController Methods

In your PreviewController (native), add:

```cpp
class PreviewController {
    
public:
    void setActiveClip(int clipId) {
        m_activeClipId = clipId;
        LOGI("[Controller] Active clip: %d", clipId);
    }
    
    void deleteClip(int clipId) {
        // Remove from timeline
        auto it = std::find_if(m_timelineClips.begin(), m_timelineClips.end(),
            [clipId](const Clip& c) { return c.id == clipId; });
        
        if (it != m_timelineClips.end()) {
            m_timelineClips.erase(it);
        }
        
        LOGI("[Controller] Clip deleted: %d", clipId);
    }
    
    void splitClip(int clipId, long timeMs) {
        // Find clip
        auto it = std::find_if(m_timelineClips.begin(), m_timelineClips.end(),
            [clipId](const Clip& c) { return c.id == clipId; });
        
        if (it != m_timelineClips.end()) {
            Clip& originalClip = *it;
            long offsetInClip = timeMs - originalClip.startTimeMs;
            
            if (offsetInClip > 0 && offsetInClip < originalClip.durationMs) {
                // Create first clip (0 to offset)
                Clip clip1 = originalClip;
                clip1.durationMs = offsetInClip;
                
                // Create second clip (offset to end)
                Clip clip2 = originalClip;
                clip2.id = m_nextClipId++;
                clip2.startTimeMs = originalClip.startTimeMs + offsetInClip;
                clip2.durationMs = originalClip.durationMs - offsetInClip;
                
                // Replace original with two clips
                *it = clip1;
                m_timelineClips.insert(it + 1, clip2);
                
                LOGI("[Controller] Clip %d split at %lld ms -> clips %d, %d",
                     clipId, timeMs, clip1.id, clip2.id);
            }
        }
    }
    
    void updateClipOrder(const std::vector<int>& newOrder) {
        // Reorder clips based on new order
        std::vector<Clip> reordered;
        for (int clipId : newOrder) {
            auto it = std::find_if(m_timelineClips.begin(), m_timelineClips.end(),
                [clipId](const Clip& c) { return c.id == clipId; });
            if (it != m_timelineClips.end()) {
                reordered.push_back(*it);
            }
        }
        m_timelineClips = reordered;
        
        LOGI("[Controller] Clip order updated: %zu clips", m_timelineClips.size());
    }
};
```

---

## Part 5: Testing Checklist

### 5.1 Selection Testing

```bash
# Test 1: Tap clip
adb logcat -s "[Timeline]"
# Expected:
# [Timeline] clip selected: ID=1
# [NativeBridge] setActiveClip: clipId=1
# [JNI] setActiveClip: clipId=1

# Test 2: Delete button appears
# [Verify] Delete button visible on screen

# Test 3: Effects UI updates
# [Verify] Effects UI sliders show clip 1's current values
```

### 5.2 Scrubbing Testing

```bash
# Test: Drag timeline left/right
# Expected:
# [Timeline] seek to: timeMs=5000
# [Timeline] seek to: timeMs=5050
# [Timeline] seek to: timeMs=5100
# (Multiple seeks, throttled)

# [Verify] Preview frame updates in real-time
# [Verify] Time display updates: 00:05
```

### 5.3 Delete Testing

```bash
# Test: Select clip 2, tap Delete
# Expected:
# [Timeline] clip selected: ID=2
# [Timeline] delete: ID=2
# [NativeBridge] deleteClip: clipId=2
# [JNI] deleteClip: clipId=2
# [Timeline] native: clip 2 deleted

# [Verify] Clip 2 disappears from timeline
# [Verify] Delete button disappears
# [Verify] Preview updates to clip 1 or 3
```

### 5.4 Split Testing

```bash
# Test: Select clip 2, seek to middle, tap Split
# Expected:
# [Timeline] clip selected: ID=2
# [Timeline] seek to: timeMs=4000
# [Timeline] split: ID=2 at timeMs=4000
# [NativeBridge] splitClip: clipId=2 at timeMs=4000
# [JNI] splitClip: clipId=2 at timeMs=4000
# [Timeline] native: clip 2 split at 4000 ms

# [Verify] Clip 2 becomes two clips (2a, 2b) on timeline
# [Verify] Proportions correct (if original 5000ms, should be ~2000ms + ~3000ms)
```

### 5.5 Drag-to-Reorder Testing

```bash
# Test: Long-press clip 2, drag to position 3
# Expected:
# [Timeline] clip drag start: ID=2
# [Timeline] drag end: ID=2
# [NativeBridge] updateClipOrder
# [JNI] updateClipOrder
# [Timeline] native: clip order updated

# [Verify] Clip 2 moves to new position
# [Verify] Other clips shift accordingly
# [Verify] Native timeline reflects new order
```

---

## Part 6: Integration with Effects

### 6.1 Effects Apply to Selected Clip Only

```kotlin
// In Effects UI slider listeners
brightnessSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        val brightness = progress.toFloat() / 100.0f - 1.0f  // -1.0 to +1.0
        
        // Apply to SELECTED clip only
        if (selectedClipId != -1) {
            NativeBridge.setClipEffects(previewView!!, selectedClipId, brightness, contrast, saturation)
        }
    }
    // ...
})
```

### 6.2 On Clip Selection, Update Effects UI

```kotlin
private fun updateEffectsUIForClip(clipId: Int) {
    // Get current effects from native layer
    val effects = NativeBridge.getClipEffects(previewView!!, clipId)
    
    // Update UI sliders to show these values
    brightnessSlider.progress = (effects.brightness * 100).toInt() + 100
    contrastSlider.progress = (effects.contrast * 100).toInt()
    saturationSlider.progress = (effects.saturation * 100).toInt()
    
    // No-op listeners during update (don't trigger native seeks)
}
```

---

## Part 7: Integration with Export

### 7.1 Export All Clips in Timeline Order

```cpp
void exportThreadProc() {
    // Export uses g_timelineClips in order
    for (const auto& clip : g_timelineClips) {
        for (long timeMs = clip.startTimeMs; timeMs < clip.endTimeMs; timeMs += 33) {
            // Render frame at timeMs with:
            // - Selected clip's video
            // - Selected clip's effects (brightness/contrast/saturation)
            // - Text overlays visible at this time
            
            // Encode frame
            encoder.encodeFrame(frameData);
        }
    }
}
```

---

## Summary

All pieces working together:

```
User Interface (MainActivity)
        ↓
Timeline Management (TimelineManager + Adapter)
        ↓
JNI Bridge (NativeBridge)
        ↓
JNI Calls (VideoPreviewView)
        ↓
Native C++ (native_preview.cpp)
        ↓
PreviewController (handles clips/order/deletion/splitting)
        ↓
GPU Rendering (with selected clip's effects + overlays)
        ↓
Display + Export
```

Each layer has clear responsibilities and debug logging for troubleshooting.


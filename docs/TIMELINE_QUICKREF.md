# Timeline UX - Quick Reference Card

## The Big Picture in 2 Minutes

### What is Timeline UX?

The interface that lets users:
1. Select which clip to edit ← MOST IMPORTANT
2. Scrub through the timeline to find moments
3. Delete clips they don't want
4. Split clips at specific times
5. Reorder clips (drag to rearrange)

### Why It Matters

- **50% of app quality** comes from timeline UX
- **VN** dominates (2.8B downloads) largely due to superior timeline UX
- **Users judge quality by feel**, not features
- **Selection clarity** is the #1 UX principle

### The Core Principle

```
Visual feedback: INSTANT (< 16ms)
Native work: ASYNC (doesn't block UI)

This decoupling = perceived smoothness
```

---

## The Architecture (5 Layers)

```
┌─ Android UI ─────────────────────────┐
│ MainActivity, TimelineManager         │
│ Selection highlighting, delete/split  │
└──┬──────────────────────────────────┘
   │ JNI Calls
┌──▼──────────────────────────────────┐
│ JNI Bridge (NativeBridge, wrapper)   │
│ Type-safe method marshaling          │
└──┬──────────────────────────────────┘
   │ External functions
┌──▼──────────────────────────────────┐
│ Native C++ (native_preview.cpp)      │
│ JNI handlers, global state           │
└──┬──────────────────────────────────┘
   │ Updates state
┌──▼──────────────────────────────────┐
│ PreviewController                    │
│ setActiveClip, deleteClip, splitClip │
└──┬──────────────────────────────────┘
   │ Render thread reads
┌──▼──────────────────────────────────┐
│ GPU Rendering                        │
│ Uses active clip's effects + overlays│
└──────────────────────────────────────┘
```

---

## The 4 Operations

### 1. Select Clip

```
User taps clip
  ↓
handleClipSelected(clipId)
  ├─ Show [Delete] [Split] buttons
  ├─ Call NativeBridge.setActiveClip(clipId)
  └─ Update effects UI
  ↓
Preview shows clip with its effects
```

### 2. Delete Clip

```
User taps [Delete] button
  ↓
timelineManager.deleteSelectedClip()  (UI)
NativeBridge.deleteClip(clipId)       (JNI)
PreviewController.deleteClip(clipId)  (Native)
  ↓
Clip removed from timeline
```

### 3. Split Clip

```
User seeks to middle, taps [Split]
  ↓
timelineManager.splitSelectedClip(timeMs)  (UI)
NativeBridge.splitClip(clipId, timeMs)     (JNI)
PreviewController.splitClip(clipId, timeMs)(Native)
  ↓
One clip becomes two clips
```

### 4. Reorder Clip

```
User long-presses clip, drags to new position
  ↓
Visual feedback immediate (smooth drag)
Native updates queued (background)
  ↓
Clip moves to new position
```

---

## Implementation Checklist

### Android UI (1-2 hours)
- [ ] Add deleteButton, splitButton references
- [ ] Implement setupTimeline()
- [ ] Implement handleTimelineScrub()
- [ ] Implement handleClipSelected()
- [ ] Implement setupDeleteButton()
- [ ] Implement setupSplitButton()

### JNI Bridge (30 mins)
- [ ] Add JNI declarations to VideoPreviewView
- [ ] Add wrapper functions
- [ ] Add methods to NativeBridge

### Native C++ (1-2 hours)
- [ ] Add global variables (g_activeClipId, g_timelineClips)
- [ ] Add 4 JNI handlers
- [ ] Implement 4 PreviewController methods
- [ ] Add logging

### Testing (30 mins)
- [ ] Test selection (clip highlighted, buttons appear)
- [ ] Test delete (clip removed)
- [ ] Test split (one clip becomes two)
- [ ] Test reorder (drag works smoothly)

---

## Key Code Snippets

### MainActivity: Selection Handler

```kotlin
private fun handleClipSelected(clipId: Int) {
    selectedClipId = clipId
    
    if (clipId != -1) {
        deleteButton?.visibility = View.VISIBLE
        splitButton?.visibility = View.VISIBLE
        NativeBridge.setActiveClip(previewView!!, clipId)
    } else {
        deleteButton?.visibility = View.GONE
        splitButton?.visibility = View.GONE
    }
}
```

### VideoPreviewView: JNI Declarations

```kotlin
private external fun nativeSetActiveClip(clipId: Int)
private external fun nativeDeleteClip(clipId: Int)
private external fun nativeSplitClip(clipId: Int, timeMs: Long)

fun setActiveClip(clipId: Int) {
    nativeSetActiveClip(clipId)
}
```

### Native C++: JNI Handler

```cpp
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeSetActiveClip(
    JNIEnv* env, jobject thiz, jint clipId) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    g_activeClipId.store(clipId, std::memory_order_release);
    
    if (g_preview) {
        g_preview->setActiveClip(clipId);
    }
    
    LOGI("[Timeline] setActiveClip: %d", clipId);
}
```

---

## Debug Logging

Filter to see all timeline operations:

```bash
adb logcat -s "[Timeline]"
```

Expected output:
```
[Timeline] clip selected: ID=1
[Timeline] seek to: timeMs=1500
[Timeline] delete: ID=1
[Timeline] split: ID=2 at timeMs=3000
```

---

## Performance Targets

| Operation | Target | Tool |
|-----------|--------|------|
| UI response | <16ms | systrace, profile |
| Timeline scroll | 60fps | frame counter |
| Seek latency | <100ms | logging |
| Delete time | <100ms | logging |

---

## Common Mistakes to Avoid

❌ **Block UI on native calls**  
✅ Do native work async, update UI when ready

❌ **Race conditions on g_activeClipId**  
✅ Use mutex or atomic with proper memory ordering

❌ **Forget to update UI when native changes**  
✅ Update UI immediately, queue native work

❌ **No validation on split timeMs**  
✅ Check that split position is inside clip

---

## Why This Works

### Principle 1: Responsiveness > Correctness
Visual feedback must be instant. Native work can be async.

### Principle 2: Selection = Command Center
Everything in editor flows from "which clip is selected".

### Principle 3: Decouple Feedback from Execution
Visual update (instant) ≠ Native update (async)

### Principle 4: Thread Safety First
Use mutexes for shared data. Atomics for lock-free reads.

### Principle 5: Logging = Debugging
Every operation should log its state.

---

## Related Systems

Timeline UX connects to:

```
Timeline Selection
    ↓
    ├─→ Effects UI (controls selected clip only)
    ├─→ Text Overlay (adds to selected clip)
    ├─→ Delete (removes selected clip)
    ├─→ Export (renders all clips in order)
    └─→ Preview (shows selected clip's content)
```

---

## Next Level

After implementing basics:

1. **Multi-select** - Tap multiple clips, group operations
2. **Undo/Redo** - Remember all operations
3. **Smooth animations** - Transition between selections
4. **Snap to grid** - Align clips to timeline grid
5. **Keyboard shortcuts** - Delete = D, Split = S, etc.

---

## Resources

| Document | Purpose |
|----------|---------|
| TIMELINE_UX_PHILOSOPHY.md | Why timeline matters |
| TIMELINE_UX_PROFESSIONAL.md | How it works |
| TIMELINE_IMPLEMENTATION_TECHNICAL.md | Exact code |
| TIMELINE_COMPLETE_GUIDE.md | Step-by-step guide |
| TIMELINE_DELIVERY_SUMMARY.md | Full summary |

---

## TL;DR

**Timeline UX = Foundation of great editor**

1. **Select:** User taps clip → visual highlight → controls appear
2. **Delete:** [Delete] button → native removes clip
3. **Split:** Seek + [Split] → one clip becomes two
4. **Reorder:** Long-press + drag → clips move

**Key:** Visual feedback instant. Native work async. Everything coordinated.

**Result:** Users feel in control. App feels professional. Ratings improve.

**Implementation:** 4-6 hours with provided architecture.

**Impact:** 50% of overall app quality.

Start building. Make it smooth. Watch users love your app.


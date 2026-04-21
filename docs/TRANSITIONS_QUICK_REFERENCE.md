# GPU Transitions - Quick Reference & Integration Checklist

## What's Done ✅

### Kotlin UI Layer (100%)
- [x] Transition data model (`TransitionType` enum, `Transition` data class)
- [x] `TransitionStore` for in-memory management  
- [x] `TransitionPanel` bottom sheet editor (8 types, duration slider, presets)
- [x] MainAct ivity integration (`showTransitionEditor()`, `deleteTransition()`)
- [x] Timeline visual markers (green indicator on clips with transitions)
- [x] Timeline interaction (tap to edit, long-press to delete)
- [x] Logging (`[TRANSITION]` format)

### Native Layer (50%)
- [x] Transition struct in C++ with `getProgress()` method
- [x] Global storage (`g_transitions` map, `g_nextTransitionId`)
- [x] JNI bindings (`nativeAddTransition`, `nativeUpdateTransition`, `nativeRemoveTransition`)
- [x] Kotlin JNI wrappers in `VideoPreviewView.kt`
- [ ] **GPU shader program** (8 transition effects)
- [ ] **Render loop integration** (active transition detection, shader binding)
- [ ] **Export pipeline** (ensure transition rendering in export)

---

## Key Files & Line Numbers

| Component | File | Lines | Status |
|-----------|------|-------|--------|
| Transition Model | `transition/Transition.kt` | 1-90 | ✅ |
| Transition Editor | `transition/TransitionPanel.kt` | 1-204 | ✅ |
| MainActivity Integration | `MainActivity.kt` | 900-960 | ✅ |
| Timeline Markers | `TimelineAdapter.kt` | 30-130 | ✅ |
| JNI Wrappers | `VideoPreviewView.kt` | 699-760 | ✅ |
| Native Storage | `native_preview.cpp` | 60-105 | ✅ |
| Native JNI Funcs | `native_preview.cpp` | 468-514 | ✅ |
| **Shader (TODO)** | **native_preview.cpp** | **TBD** | ⏳ |
| **Render Loop (TODO)** | **native_preview.cpp** | **~420-440** | ⏳ |

---

## How to Use

### 1. User wants to add transition

```kotlin
// In timeline: User taps green marker between two clips
TimelineAdapter.onTransitionTapped(transitionId)
  ↓
MainActivity.showTransitionEditor(outClipId, inClipId)
  ↓
TransitionPanel(activity, previewView, transition)
  ↓
  User selects type + duration + taps Done
  ↓
MainActivity callback:
  - TransitionStore.add(transition)
  - pv.addTransition(transition)  [JNI call]
  - Log "[TRANSITION] added type=CROSS duration=300ms"
```

### 2. During playback

```cpp
// In native_preview.cpp render thread (~line 420)

// Current time from playback
int64_t currentTimeMs = ...;

// Check all transitions
for (auto& [id, trans] : g_transitions) {
    float prog = trans.getProgress(currentTimeMs);
    if (prog >= 0.0f && prog <= 1.0f) {
        // TRANSITION ACTIVE!
        // prog = 0.0 (start) to 1.0 (end)
        //
        // TODO: Bind shader, set uniforms, render
    }
}
```

### 3. Data flow

```
Java UI                    Kotlin/JNI                Native (C++)
═════════════          ═════════════════          ═══════════════

User taps
transition ────→ TransitionPanel ────→ nativeAddTransition
marker           (edit/select)         (RPC)
                                          ↓
                                    g_transitions.insert({id, trans})
                                          ↓
                                    LOGI("[TRANSITION] add ...")
                                          ↓
                 Returns nativeId ←─────────
              
During
scrub/play ────→ MainActivity ────→ nativeSeekPreview
                scrubToTime()       (RPC)
                                          ↓
                                    render loop:
                                      for (trans)
                                        prog = trans.getProgress(time)
                                        if (prog >= 0 && prog <= 1)
                                          render blend shader
                                          ↓
                                    eglSwapBuffers
                                          ↓
                 User sees blend ←─────────
```

---

## GPU Shader Integration (TODO)

### Step 1: Create Transition Shader Program

In `native_preview.cpp` (after text overlay shader at ~line 100):

```cpp
// Global shader program
GLuint g_transitionProgram = 0;
GLint g_transitionTypeUniform = -1;
GLint g_transitionProgressUniform = -1;

static bool initTransitionShaders() {
    // Vertex shader (full-screen quad)
    const char* vs = R"(
        #version 300 es
        layout(location=0) in vec2 aPos;
        layout(location=1) in vec2 aUV;
        out vec2 vUV;
        void main() {
            vUV = aUV;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    )";

    // Fragment shader (8 transition effects)
    const char* fs = R"(
        #version 300 es
        precision mediump float;
        uniform sampler2D uOutTex;
        uniform sampler2D uInTex;
        uniform int uType;
        uniform float uProgress;
        in vec2 vUV;
        out vec4 fragColor;

        // NONE(0): instant cut
        // FADE(1): fade out/in black
        // CROSS(2): crossfade
        // SLIDE_LEFT(3): slide left
        // SLIDE_RIGHT(4): slide right
        // DITHER(5): noise dither
        // CIRCLE(6): circular wipe
        // ZIGZAG(7): zigzag pattern

        void main() {
            vec4 out_col = texture(uOutTex, vUV);
            vec4 in_col = texture(uInTex, vUV);

            vec4 result;
            if (uType == 0) {
                result = mix(out_col, in_col, step(0.5, uProgress));
            } else if (uType == 1) {
                // FADE
                if (uProgress < 0.5) {
                    result = mix(out_col, vec4(0.0), uProgress * 2.0);
                } else {
                    result = mix(vec4(0.0), in_col, (uProgress - 0.5) * 2.0);
                }
            } else if (uType == 2) {
                // CROSS
                result = mix(out_col, in_col, uProgress);
            } else if (uType == 3) {
                // SLIDE_LEFT
                vec2 uv_out = vUV + vec2(-uProgress, 0.0);
                vec2 uv_in = vUV + vec2(1.0 - uProgress, 0.0);
                result = mix(texture(uOutTex, uv_out), texture(uInTex, uv_in), uProgress);
            } else if (uType == 4) {
                // SLIDE_RIGHT  
                vec2 uv_out = vUV + vec2(uProgress, 0.0);
                vec2 uv_in = vUV + vec2(uProgress - 1.0, 0.0);
                result = mix(texture(uOutTex, uv_out), texture(uInTex, uv_in), uProgress);
            } else if (uType == 5) {
                // DITHER
                float noise = fract(sin(dot(vUV * 50.0, vec2(12.9898, 78.233))) * 43758.5453);
                result = mix(out_col, in_col, step(uProgress, noise));
            } else if (uType == 6) {
                // CIRCLE: radial wipe
                float dist = length(vUV - 0.5) * 1.414;
                result = mix(out_col, in_col, smoothstep(uProgress - 0.1, uProgress, dist));
            } else {
                // ZIGZAG or default
                result = mix(out_col, in_col, uProgress);
            }
            fragColor = result;
        }
    )";

    // Compile & link
    GLuint v = compileShader(GL_VERTEX_SHADER, vs);
    GLuint f = compileShader(GL_FRAGMENT_SHADER, fs);
    if (!v || !f) return false;

    g_transitionProgram = glCreateProgram();
    glAttachShader(g_transitionProgram, v);
    glAttachShader(g_transitionProgram, f);
    glLinkProgram(g_transitionProgram);

    GLint ok;
    glGetProgramiv(g_transitionProgram, GL_LINK_STATUS, &ok);
    if (!ok) {
        LOGE("[Transition] Shader link failed");
        glDeleteProgram(g_transitionProgram);
        g_transitionProgram = 0;
        return false;
    }

    glDeleteShader(v);
    glDeleteShader(f);

    g_transitionTypeUniform = glGetUniformLocation(g_transitionProgram, "uType");
    g_transitionProgressUniform = glGetUniformLocation(g_transitionProgram, "uProgress");

    LOGI("[Transition] Shader initialized");
    return true;
}
```

### Step 2: Add to Render Loop

In render thread function (after `g_preview->scrubToTimelineTime(currentTimeMs)` at ~line 420):

```cpp
// Check for active transition
Transition* activeTransition = nullptr;
for (auto& [id, trans] : g_transitions) {
    if (!trans.isEnabled) continue;
    float prog = trans.getProgress(currentTimeMs);
    if (prog >= 0.0f && prog <= 1.0f && prog <= 1.0f) {
        activeTransition = &trans;
        break;
    }
}

if (activeTransition) {
    // Transition is active
    LOGD("[GPU] Transition active type=%d prog=%.2f", 
         activeTransition->typeId, activeTransition->getProgress(currentTimeMs));
    
    // TODO: Bind shader, set textures, render
    // (Requires getting clip textures from PreviewController)
    // This is where GPU-based blend happens
    
} else {
    // Normal rendering - single clip
    renderTextOverlays(currentTimeMs);
}
```

### Step 3: Call from initializeEGL()

Add after `initTextOverlayGL()`:

```cpp
if (!initTransitionShaders()) {
    LOGW("[Transition] Shader init failed, transitions will not render");
}
```

---

## Testing Script

```bash
# After making changes:

# 1. Build C++
./gradlew clean :app:assembleDebug

# 2. Check build output
adb logcat | grep "TRANSITION\|Transition"

# 3. On device:
#    - Open app
#    - Create 2+ clips (Timeline Manager)
#    - Tap between clips → TransitionPanel opens
#    - Select CROSS, 300ms → Done
#    - Green marker appears
#    - Scrub timeline → Check logs for "[GPU TRANSITION]"
#    - Export video → Check if MP4 includes transition

# 4. Logs to watch for:
#    [TRANSITION] add id=1 type=2 duration=300ms between 1 -> 2
#    [GPU TRANSITION] active id=1 type=2 progress=0.50
```

---

## Troubleshooting

| Issue | Cause | Solution |
|-------|-------|----------|
| TransitionPanel doesn't open | JNI call failed or disabled | Check `nativeAddTransition` in logcat |
| Green markers don't appear | `TransitionStore.add()` not called | Verify `MainActivity.showTransitionEditor()` is called |
| No transition active logs | Render loop not checking transitions | Implement render loop logic (Step 2 above) |
| Transitions not visible in preview | Shader not binding | Confirm `initTransitionShaders()` called in `initializeEGL()` |
| Export doesn't include transitions | Export renderer not updated | Add transition check to export render loop |

---

## Commit Message

```
feat: Add professional GPU-accelerated transitions

- Implement Kotlin UI: TransitionPanel editor with 8 effects
- Add native storage: Transition struct, JNI bindings
- Wire timeline: Green transition markers, tap to edit
- Add MainActivity integration: show/delete transitions
- Add logging: [TRANSITION] format

Ready for: GPU shader implementation & export integration

TODOs:
- Implement transition fragment shaders (8 effects)
- Integrate shaders into render loop vertex
- Test preview & export with transitions
- Add transition easing functions (phase 2)
- Transition preview in editor panel (phase 3)
```

---

## Performance Budget

| Operation | Time | Count/sec |
|-----------|------|-----------|
| Add transition | <1ms | 1 |
| Update transition | <1ms | ~5 (user dragging slider) |
| Delete transition | <1ms | 1 |
| Check active transition | <0.1ms | 60 (per frame) |
| **Render blend (GPU)** | **1-2ms** | **60** |
| **Timeline adapter update** | **<5ms** | **1** |

Total GPU time during transition: ~2-3ms @ 60fps (sustainable)

---

## Quick Win: Test Native Structure

Before implementing shader, verify native side works:

```bash
# In MainActivity, temporarily add:
previewView?.let { pv ->
    val t = com.video.engine.transition.Transition(
        type = com.video.engine.transition.TransitionType.CROSS,
        durationMs = 500,
        outgoingClipId = 1,
        incomingClipId = 2,
        startTimeMs = 1000
    )
    val id = pv.addTransition(t)
    Log.d("[TEST]", "Transition added with ID=$id")
}

# Check logcat:
# adb logcat | grep "TRANSITION.*add"
# Should see: [TRANSITION] add id=1 type=2 duration=500ms between 1 -> 2
```

If this logs correctly, native side is working. Shader integration is next.

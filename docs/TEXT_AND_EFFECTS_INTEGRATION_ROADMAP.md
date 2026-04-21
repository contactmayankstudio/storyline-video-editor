# Text & Effects Integration Roadmap
**Status:** Implementation Phase  
**Date:** 2026-02-07  
**Version:** 1.0 - VN/KineMaster Parity

---

## Overview

This roadmap guides comprehensive integration of 8 interconnected features:

1. **GPU Text Rendering** - TextRenderer → PreviewRenderer integration
2. **Android Text UI** - Add Text dialog + dialog flow
3. **Touch Interaction** - Drag, pinch, rotate gestures
4. **Timeline Clips** - TextClip model + visibility logic
5. **Text Styling** - Color, opacity, shadow effects
6. **Multi-Layer** - Z-order + layering system
7. **Export Pipeline** - Text compositing in final video
8. **Project System** - JSON save/load + serialization
9. **Effects UI** - Real-time GPU effect sliders

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     Android UI Layer (Kotlin)               │
│  TextOverlayView (drag/pinch) | EffectsPanel (sliders)      │
└────────────────────┬────────────────────────────────────────┘
                     │ JNI Calls
                     ▼
┌─────────────────────────────────────────────────────────────┐
│              JNI Bridge (native_preview.cpp)                │
│  nativeAddText() | nativeUpdateText() | nativeSetEffects() │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│         Native C++ Engine Layer (OpenGL ES 3.0)             │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ Timeline (clips + text + effects)                    │  │
│  └───────────────┬──────────────────────────────────────┘  │
│                  │                                          │
│  ┌──────────────▼──────────────────────────────────────┐  │
│  │ PreviewRenderer (frame composition)                 │  │
│  │  - Video quad render (YUV→RGB)                      │  │
│  │  - TextRenderer (text mesh + shader)                │  │
│  │  - Effects (brightness, contrast, saturation)       │  │
│  │  - Multi-layer compositing                          │  │
│  └───────────────┬──────────────────────────────────────┘  │
│                  │                                          │
│  ┌──────────────▼──────────────────────────────────────┐  │
│  │ GPU Pipeline (EGL + OpenGL ES)                      │  │
│  │  - Shaders (YUV→RGB, text, effects)                 │  │
│  │  - Textures (video, font atlas)                     │  │
│  │  - Framebuffer (FBO for export)                     │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## Task 1: GPU Text Rendering Integration ✅ EXISTING

**Status:** Exists but needs PreviewRenderer integration  
**Files:** `backend/gpu/text_renderer.h/cpp`, `text_overlay.h`

### What Exists:
- ✅ `TextRenderer` class with glyph atlas loading
- ✅ `buildTextMesh()` - character quad generation
- ✅ `renderTextOverlay()` - single overlay rendering
- ✅ `TextOverlay` struct (text, position, scale, rotation, color, opacity, timing)
- ✅ Blending setup (GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
- ✅ Fade-in/fade-out calculation

### What Needs Integration:

**Into `PreviewRenderer::renderFrame()`:**
```cpp
// After video quads rendered, before buffer swap:
if (m_textRenderer) {
    // Get visible text overlays at current time
    auto visibleTexts = timeline.getVisibleTextOverlaysAtTime(timeMs);
    if (!visibleTexts.empty()) {
        // Sort by z-order (layerIndex)
        std::sort(visibleTexts.begin(), visibleTexts.end(),
            [](const TextOverlay& a, const TextOverlay& b) {
                return a.zOrder < b.zOrder;
            });
        
        // Render each text overlay
        m_textRenderer->renderTextOverlays(
            visibleTexts, 
            m_width, m_height,
            timeMs
        );
    }
}
```

**Extend PreviewRenderer header:**
```cpp
#include "text_renderer.h"

class PreviewRenderer {
private:
    TextRendererPtr m_textRenderer;  // NEW
    
public:
    void initializeTextRenderer();   // NEW
    void renderTextAtTime(const std::vector<TextOverlay>& overlays, TimeMs timeMs);  // NEW
};
```

**Implementation steps:**
1. Add `#include "text_renderer.h"` to `preview_renderer.h`
2. Add `m_textRenderer = std::make_shared<TextRenderer>();` in constructor
3. Call `m_textRenderer->loadFontAtlas()` in initialization
4. Call `m_textRenderer->renderTextOverlays()` in `renderFrame()` after video render
5. Verify blending state is correct (GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA enabled)

---

## Task 2: Android Text Add Dialog UI

**Files:** `android/app/src/main/kotlin/com/video/engine/MainActivity.kt`

### Current State:
- ✅ `VideoPreviewView` has JNI bindings for `nativeAddTextOverlay()`
- ⚠️ No UI button/dialog for adding text

### Implementation:
Add button to toolbar + dialog handler:

```kotlin
// In setupToolbarButtons()
val addTextBtn = ImageButton(this).apply {
    setImageResource(android.R.drawable.ic_menu_edit)
    setOnClickListener { showAddTextDialog() }
}

private fun showAddTextDialog() {
    val input = EditText(this).apply {
        hint = "Enter text..."
    }
    
    AlertDialog.Builder(this)
        .setTitle("Add Text")
        .setView(input)
        .setPositiveButton("OK") { _, _ ->
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                val id = nextTextOverlayId++
                val startTimeMs = currentTimeMs
                val endTimeMs = currentTimeMs + 3000  // Default 3 second duration
                
                previewView?.addTextOverlay(
                    id,
                    text,
                    x = 0.5f, y = 0.5f,  // Center
                    scale = 1.0f, rotation = 0.0f,
                    color = 0xFFFFFFFF.toInt(),  // White
                    fontSize = 36f,
                    startTimeMs = startTimeMs.toInt(),
                    endTimeMs = endTimeMs.toInt()
                )
                
                // Generate text bitmap for texture
                val bitmap = createTextBitmap(text)
                previewView?.setTextOverlayBitmap(id, bitmap.pixels, bitmap.width, bitmap.height)
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

private fun createTextBitmap(text: String): Bitmap {
    val paint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 36f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    
    val width = (paint.measureText(text)).toInt() + 16
    val height = 64
    
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(0x00000000, android.graphics.PorterDuff.Mode.CLEAR)
    canvas.drawText(text, 8f, 48f, paint)
    
    return bitmap
}
```

---

## Task 3: Touch Interaction (Drag, Pinch, Rotate)

**Files:** `android/app/src/main/kotlin/com/video/engine/VideoPreviewView.kt` (PARTIALLY DONE)

### Current State:
- ✅ Single-finger drag for move
- ✅ Two-finger pinch for scale
- ✅ Two-finger rotate for rotation
- ⚠️ Native backend incomplete (JNI methods present but C++ not implemented)

### JNI Methods Needed (in native_preview.cpp):

```cpp
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeUpdateTextScale(
    JNIEnv* env, jobject thiz, jint textId, jfloat scaleFactor) {
    // Find text overlay by id
    // Update scale
    // Trigger render
}

JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeUpdateTextRotation(
    JNIEnv* env, jobject thiz, jint textId, jfloat rotationDeg) {
    // Find text overlay by id
    // Update rotation (degrees)
    // Trigger render
}
```

### Implementation:
1. Global text overlay map (already referenced in code)
2. Update scale/rotation/position in map
3. Call `nativeSeekPreview()` to re-render

---

## Task 4: Timeline TextClip Model & Visibility Logic

**Files:** `core/timeline.h`, `core/timeline.cpp` (NEW methods)

### New Method Required:

```cpp
// In Timeline class
std::vector<TextOverlay> getVisibleTextOverlaysAtTime(int64_t timelineMs) const {
    std::vector<TextOverlay> visible;
    
    for (const auto& text : m_textOverlays) {
        // Check if text is active at this time
        if (text.enabled && 
            timelineMs >= text.startTime && 
            (text.endTime < 0 || timelineMs <= text.endTime)) {
            visible.push_back(text);
        }
    }
    
    // Sort by zOrder for rendering order
    std::sort(visible.begin(), visible.end(),
        [](const TextOverlay& a, const TextOverlay& b) {
            return a.zOrder < b.zOrder;
        });
    
    return visible;
}
```

### Storage:
```cpp
private:
    std::vector<TextOverlay> m_textOverlays;
    std::map<int64_t, TextOverlay> m_textOverlayMap;  // id → overlay
```

---

## Task 5: Text Styling (Color, Opacity, Shadow)

**Extend TextOverlay struct:**

```cpp
struct TextOverlay {
    // ... existing fields ...
    
    // Styling
    uint32_t color = 0xFFFFFFFFu;      // RGBA (white)
    float opacity = 1.0f;               // 0.0..1.0
    
    // Shadow (optional)
    bool shadowEnabled = false;
    float shadowOffsetX = 2.0f;
    float shadowOffsetY = 2.0f;
    uint32_t shadowColor = 0x00000080u; // Black with alpha
    
    // Fade timing
    int32_t fadeInMs = 0;
    int32_t fadeOutMs = 0;
};
```

**Rendering logic in TextRenderer:**

```cpp
void TextRenderer::renderTextOverlay(const TextOverlay& overlay, ..., int64_t currentTimeMs) {
    // ... existing code ...
    
    // If shadow enabled, render shadow first
    if (overlay.shadowEnabled) {
        glm::vec4 shadowColor(
            ((overlay.shadowColor >> 24) & 0xFF) / 255.0f,
            ((overlay.shadowColor >> 16) & 0xFF) / 255.0f,
            ((overlay.shadowColor >> 8) & 0xFF) / 255.0f,
            (overlay.shadowColor & 0xFF) / 255.0f
        );
        
        auto shadowMesh = buildTextMesh(
            overlay.text,
            overlay.x + overlay.shadowOffsetX / m_width,
            overlay.y + overlay.shadowOffsetY / m_height,
            overlay.scale,
            overlay.rotation,
            shadowColor,
            frameWidth, frameHeight
        );
        renderMesh(shadowMesh, mvp, shadowColor);
    }
    
    // Render main text
    // ... existing code ...
}
```

---

## Task 6: Multiple Text Layers & Z-Order

**Implementation:**

1. Add `layerIndex` field to TextOverlay (already exists as `zOrder`)
2. Sort visible text by layerIndex before rendering:
```cpp
std::sort(visibleTexts.begin(), visibleTexts.end(),
    [](const TextOverlay& a, const TextOverlay& b) {
        return a.zOrder < b.zOrder;  // Lower zOrder renders first
    });
```

3. JNI method to set z-order:
```cpp
JNIEXPORT void JNICALL
Java_com_video_engine_VideoPreviewView_nativeSetTextZOrder(
    JNIEnv* env, jobject thiz, jint textId, jint zOrder) {
    auto it = g_textOverlays.find(textId);
    if (it != g_textOverlays.end()) {
        it->second.zOrder = zOrder;
    }
}
```

---

## Task 7: Export Pipeline (Video + Text)

**Core Requirement:** Use PreviewRenderer to render frames for export

**Implementation:**

```cpp
// In ExportController or similar
bool ExportController::exportVideo(
    const std::string& outputPath,
    const Timeline& timeline,
    uint32_t width, uint32_t height, uint32_t fps) {
    
    // Create PreviewRenderer in headless mode
    GPU::PreviewRenderer renderer(width, height, 
        GPU::PreviewRenderer::RenderMode::Headless);
    
    // Open FFmpeg encoder
    Backend::VideoEncoder encoder(outputPath, width, height, fps);
    
    // Get total duration
    int64_t durationMs = timeline.getTotalDurationMs();
    int64_t frameIntervalMs = 1000 / fps;
    
    // Render each frame
    for (int64_t timeMs = 0; timeMs < durationMs; timeMs += frameIntervalMs) {
        // Render frame (includes text, effects, transitions)
        RenderGraph rg = timeline.buildRenderGraph(timeMs);
        renderer.renderFrame(rg, timeMs);
        
        // Read back pixels from FBO
        auto pixelBuffer = renderer.readFramebuffer();
        
        // Convert RGBA → YUV420P (via FrameConverter)
        auto yuvFrame = frameConverter.convertRGBToYUV(pixelBuffer);
        
        // Encode frame
        encoder.encodeFrame(yuvFrame);
    }
    
    encoder.finalize();
    return true;
}
```

**Key Points:**
- ✅ PreviewRenderer already supports headless (FBO) mode
- ✅ Text rendering included in `renderFrame()`
- ✅ Use same RenderGraph logic as preview
- ✅ glReadPixels for RGBA readback
- ✅ FrameConverter handles YUV conversion

---

## Task 8: Project Save/Load System

**File Structure:**

```
project.veproj (JSON)
{
  "version": "1.0",
  "name": "My Video Project",
  "settings": {
    "width": 1080,
    "height": 1920,
    "fps": 30,
    "duration_ms": 15000
  },
  "clips": [
    {
      "id": 1,
      "path": "videos/clip1.mp4",
      "start_time_ms": 0,
      "layer": 0,
      "effects": {
        "brightness": 0.0,
        "contrast": 1.0,
        "saturation": 1.0
      }
    }
  ],
  "text_overlays": [
    {
      "id": 1,
      "text": "Hello World",
      "x": 0.5,
      "y": 0.3,
      "scale": 1.5,
      "rotation": 0.0,
      "color": "#FFFFFF",
      "opacity": 1.0,
      "start_time_ms": 1000,
      "end_time_ms": 4000,
      "layer": 0,
      "shadow": {
        "enabled": false,
        "offset_x": 2.0,
        "offset_y": 2.0
      }
    }
  ],
  "transitions": [
    {
      "id": 1,
      "type": "crossfade",
      "out_clip": 1,
      "in_clip": 2,
      "duration_ms": 500,
      "start_time_ms": 10000
    }
  ]
}
```

**C++ Implementation:**

```cpp
// engine.h/cpp
class Engine {
public:
    bool saveProject(const std::string& projectPath);
    bool loadProject(const std::string& projectPath);
    
private:
    nlohmann::json toJSON() const;
    bool fromJSON(const nlohmann::json& j);
};
```

**JNI Bridge:**

```cpp
JNIEXPORT jboolean JNICALL
Java_com_video_engine_VideoPreviewView_nativeSaveProject(
    JNIEnv* env, jobject thiz, jstring pathJ) {
    const char* path = env->GetStringUTFChars(pathJ, nullptr);
    bool success = g_engine->saveProject(path);
    env->ReleaseStringUTFChars(pathJ, path);
    return success ? JNI_TRUE : JNI_FALSE;
}
```

---

## Task 9: Effects UI Sliders

**New UI Panel (EffectsPanel.kt):**

```kotlin
class EffectsPanel(context: Context, private val previewView: VideoPreviewView) : LinearLayout(context) {
    
    init {
        orientation = VERTICAL
        
        // Brightness slider
        addSlider("Brightness", -1.0f, 1.0f, 0.0f) { value ->
            previewView.setClipEffects(currentClipId, value, contrastValue, saturationValue)
        }
        
        // Contrast slider
        addSlider("Contrast", 0.0f, 2.0f, 1.0f) { value ->
            previewView.setClipEffects(currentClipId, brightnessValue, value, saturationValue)
        }
        
        // Saturation slider
        addSlider("Saturation", 0.0f, 2.0f, 1.0f) { value ->
            previewView.setClipEffects(currentClipId, brightnessValue, contrastValue, value)
        }
        
        // Reset button
        addResetButton {
            previewView.setClipEffects(currentClipId, 0.0f, 1.0f, 1.0f)
        }
    }
    
    private fun addSlider(label: String, min: Float, max: Float, default: Float, onChange: (Float) -> Unit) {
        // Implementation...
    }
}
```

**Integration:**
- Add EffectsPanel to MainActivity
- Show/hide when clip selected
- Update JNI call on slider move
- No lag (GPU-only processing)

---

## Implementation Order (Dependency Chain)

```
1. GPU Text Rendering Integration
   ├─ Integrate TextRenderer into PreviewRenderer
   ├─ Add getVisibleTextOverlaysAtTime() to Timeline
   └─ Verify text renders in preview

2. Android Text Add UI
   ├─ Add button + dialog to MainActivity
   ├─ Create text bitmap (Kotlin Canvas)
   └─ Call nativeAddTextOverlay() + setTextOverlayBitmap()

3. Touch Interaction (Partially Done)
   ├─ Implement JNI methods for scale/rotation/position
   ├─ Store in global text overlay map
   └─ Trigger nativeSeekPreview() to re-render

4. Timeline TextClip Model
   ├─ Extend Timeline with text overlay list
   ├─ Implement getVisibleTextOverlaysAtTime()
   └─ Sort by zOrder

5. Text Styling
   ├─ Extend TextOverlay fields (shadow, fade)
   ├─ Update rendering logic
   └─ Add JNI setters

6. Multi-Layer Z-Order
   ├─ Sort visible texts by zOrder
   ├─ Add JNI setTextZOrder()
   └─ Test layering

7. Export Pipeline
   ├─ Create ExportController
   ├─ Use PreviewRenderer in headless mode
   ├─ Read back + convert pixels
   └─ Encode with FFmpeg

8. Project Save/Load
   ├─ Define JSON schema
   ├─ Implement toJSON() / fromJSON()
   ├─ Add JNI methods
   └─ Test round-trip (save + load)

9. Effects UI Sliders
   ├─ Create EffectsPanel
   ├─ Wire sliders to JNI
   ├─ Implement nativeSetClipEffects()
   └─ Test real-time GPU updates
```

---

## Files to Create/Modify

| File | Status | Purpose |
|------|--------|---------|
| `backend/gpu/preview_renderer.h` | Modify | Add TextRenderer, text render methods |
| `backend/gpu/preview_renderer.cpp` | Modify | Integrate text rendering in renderFrame() |
| `core/timeline.h` | Modify | Add text overlay list + getVisibleAtTime() |
| `core/timeline.cpp` | Modify | Implement text visibility logic |
| `android/app/src/main/kotlin/.../MainActivity.kt` | Modify | Add text dialog + UI setup |
| `android/app/src/main/kotlin/.../VideoPreviewView.kt` | Exists | Already has JNI bindings |
| `engine/export_controller.h` | Create | Export with text rendering |
| `engine/export_controller.cpp` | Create | Implement export pipeline |
| `engine/project.h` | Create | Project model + serialization |
| `engine/project.cpp` | Create | JSON save/load |
| `android/app/.../EffectsPanel.kt` | Create | Sliders for brightness/contrast |

---

## Testing Strategy

**Unit Tests:**
- TextRenderer mesh generation (character counts, UV correctness)
- Timeline visibility logic (text appears/disappears correctly)
- Z-order sorting (correct layer order)

**Integration Tests:**
- Add text via UI → appears in preview
- Drag text → position updates
- Scrub timeline → text shows/hides correctly
- Export video → text in output file

**Visual Tests:**
- Text appears at correct position
- Multiple texts render without overlap issues
- Shadow renders correctly
- Style changes (color, opacity) apply instantly
- Transitions between clips work while text visible

---

## Performance Targets

- **Preview**: 60fps with 1-5 text overlays (GPU-only)
- **Export**: 30fps (real-time or faster depending on hardware)
- **Text mesh generation**: <1ms per overlay (happens once on add)
- **Memory**: <5MB per 50 text overlays

---

## Success Criteria

- ✅ User can add text via UI dialog
- ✅ Text appears on video in preview
- ✅ Drag/pinch/rotate works without lag
- ✅ Text shows/hides with timeline position
- ✅ Multiple texts render in correct order
- ✅ Export outputs video with text composited
- ✅ Project can save/load with all text properties
- ✅ Effects sliders control GPU effects in real-time

---

## Notes & Considerations

1. **Font**: Default system font works. For production, use FreeType2 + atlas generation.
2. **Performance**: GPU rendering is key - no CPU text rasterization.
3. **Thread Safety**: All GPU calls on same thread (already handled by EGL context).
4. **Android Specifics**: Use `android.graphics.Paint.drawText()` for Kotlin bitmap generation (test on device).
5. **Export**: Use same PreviewRenderer logic - ensures WYSIWYG (what you see is what you get).


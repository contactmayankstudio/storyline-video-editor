# Export Feature - Quick Reference

## JNI Export Handlers

Location: `android/jni/native_preview.cpp`

### `nativeStartExport(outputPath, width, height, fps)`
**Purpose:** Start export with specified parameters
**Parameters:**
- `outputPath` (String): Absolute path to output file
- `width` (int): Output width in pixels (e.g., 1920)
- `height` (int): Output height in pixels (e.g., 1080)
- `fps` (int): Frame rate (e.g., 30)

**Flow:**
1. Create ExportConfig with parameters
2. Auto-calculate bitrate
3. Reset g_exportProgress to 0
4. Spawn background export thread
5. Return immediately (non-blocking)

**Debug Log:**
```
[Export] started resolution=1920x1080 fps=30 bitrate=12441 output=/path/video.mp4
```

### `nativeCancelExport()`
**Purpose:** Request export cancellation
**Flow:**
1. Set g_exportCancelled flag to true
2. Export thread checks flag and stops
3. Logs cancellation

**Debug Log:**
```
[Export] cancel requested
```

### `nativeGetExportProgress()`
**Returns:** int (0-100 if exporting, -1 if not)
**Purpose:** Poll current export progress
**Used by:** MainActivity.kt, polled every 500ms

**Debug Usage:**
```bash
adb logcat | grep "\[Export\]"
```

## ExportConfig struct

Location: `android/jni/export_config.h`

```cpp
struct ExportConfig {
    std::string outputPath;      // Output file path
    int32_t width = 1920;        // Output width
    int32_t height = 1080;       // Output height
    int32_t fps = 30;            // Frame rate
    int32_t bitrate = 8000;      // Bitrate in kbps (auto-calculated)
    int32_t audioSampleRate = 48000;
    int32_t audioChannels = 2;
    std::string videoCodec = "h264";
    std::string audioCodec = "aac";
    
    // Helper methods
    int32_t calculateBitrate() const;      // Auto-calc from width/height/fps
    std::string getResolutionLabel() const; // "1080p", "720p", etc.
};
```

## Export State Globals

Location: `android/jni/native_preview.cpp`

```cpp
std::atomic<int32_t> g_exportProgress(0);           // 0-100%
std::atomic<bool> g_isExporting(false);             // Currently exporting?
std::atomic<bool> g_exportCancelled(false);         // User cancelled?
VideoEngine::ExportConfig g_currentExportConfig;    // Current export params
std::thread g_exportThread;                         // Background export thread
```

## Export UI Flow (MainActivity.kt)

```
setupExportButton()
    │
    └─► Export Button Click
        ├─ Show resolution dialog (720p/1080p/4K)
        ├─ Show FPS dialog (24/30/60)
        ├─ File save dialog → get output path
        ├─ nativeStartExport(path, width, height, fps)
        │
        └─ Show progress dialog
           ├─ Poll nativeGetExportProgress() every 500ms
           ├─ Update progress bar
           ├─ If progress == 100 → export done
           └─ Cancel button → nativeCancelExport()
           
        └─ Export complete
           ├─ Hide progress dialog
           └─ Show success dialog
              ├─ Play (open video)
              ├─ Share (system share)
              └─ Save (copy to gallery)
```

## Resolution Presets

| Preset | Dimensions | Bitrate (30fps) | Use Case |
|--------|-----------|-----------------|----------|
| 720p | 1280x720 | 4.1 Mbps | Web/streaming |
| 1080p | 1920x1080 | 12.4 Mbps | Professional |
| 4K | 3840x2160 | 50 Mbps | Archive/high quality |

## Bitrate Calculation

Formula: `width * height * fps * quality_factor / 1000`

Quality factors by resolution:
- SD (< 720p): 0.1
- HD (720p-1080p): 0.15
- 4K (2160p+): 0.2

Range: 500-50,000 kbps

**Examples:**
- 1920x1080@30fps: 1920*1080*30*0.2/1000 = 12.4 Mbps
- 1280x720@30fps: 1280*720*30*0.15/1000 = 4.1 Mbps

## Compilation

Build project with CMake:
```bash
cd /home/am/video_engine_core
cmake --build build --config Release -j$(nproc)
```

Required headers:
- `export_config.h` - ExportConfig struct definition
- `text_overlay.h` - TextOverlay struct (for compositing)
- `preview_controller.h` - Frame rendering API

## Debug Commands

Filter export logs:
```bash
adb logcat | grep "\[Export\]"
```

Monitor export state:
```bash
adb logcat | grep -E "\[Export\]|\[Text\]"
```

Clear logcat:
```bash
adb logcat -c
```

## Key Implementation Notes

### Thread Safety
- All g_export* variables are `std::atomic` (lock-free)
- No mutex needed for export state
- Export thread runs independently of render thread
- Text overlay access protected by g_mutex (separate from export)

### Current Status
- ✅ ExportConfig struct complete
- ✅ Export state globals added
- ✅ JNI handlers implemented (demo with 5-second progress)
- ✅ Android UI integration verified
- ⏳ Real FFmpeg integration (TODO)

### TODO for Real Export
1. Calculate total frames from timeline duration
2. Loop over each frame time
3. Call `g_preview->scrubToTimelineTime(ms)` for each frame
4. Render with all effects + text overlays active
5. Encode frame via FFmpeg encoder
6. Mix and encode audio stream
7. Mux video + audio into output file

### Performance
- Export runs in separate thread (doesn't block UI/preview)
- Memory: ~50-100 MB (frame buffer + encoder buffer)
- CPU: Variable (depends on codec and resolution)
- Bitrate: Auto-tuned for quality/size balance

## Integration Points

### Text Overlays
Export pipeline composes text overlays like preview:
1. Filter overlays by `startTime <= frameTime <= endTime`
2. Render as GPU quads with transforms
3. Composite onto frame before encoding

### GPU Effects
All effects from preview are included in export:
- Color correction
- Filters
- Transitions
- Blending modes

### Audio
Audio handled by preview controller:
- Get mixed audio at current timeline time
- Encode and mux with video

## Error Handling

### "Export already in progress"
- Only one export at a time
- Solution: Cancel current export first, or wait for completion

### Progress not updating
- UI polling every 500ms
- Export thread should update g_exportProgress frequently
- Check: `adb logcat | grep "progress"`

### File not created
- Check output path is valid and writable
- Verify permissions: `WRITE_EXTERNAL_STORAGE`
- Check disk space available

### Audio missing
- Verify: Audio codec selected (AAC or Opus)
- Check: Audio mixing implemented in export thread

## See Also
- [Full Export Implementation Guide](EXPORT_IMPLEMENTATION_COMPLETE.md)
- [Text Overlay Quick Reference](TEXT_OVERLAY_QUICKREF.md)
- [GPU Effects Quick Reference](GPU_EFFECTS_QUICKREF.md)


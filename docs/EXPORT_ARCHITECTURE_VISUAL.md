# Export Architecture - Visual Guide

## End-to-End Export Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         USER INTERACTION LAYER                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  [Export Button] ──► Resolution Dialog ──► FPS Dialog ──► File Save Dialog  │
│       (MainActivity)     (720p/1080p/4K)    (24/30/60)    (output path)      │
│                                                                               │
│  Show Progress Dialog                                                         │
│  ├─ Progress Bar (0-100%)                                                    │
│  ├─ Cancel Button                                                            │
│  └─ Status Text (e.g., "Exporting: 45%")                                    │
│                                                                               │
│  On Complete:                                                                │
│  ├─ Play ──► Opens video in system player                                   │
│  ├─ Share ──► System share sheet                                            │
│  └─ Save ──► Copy to device gallery                                         │
│                                                                               │
└────────────────────────────────────────────────────────────────────────────┬─┘
                                    │
                                    │ onClick/polling
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        JNI BRIDGE LAYER (Kotlin)                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  NativeBridge.kt                                                            │
│  ├─ startExport(pv, path, w, h, fps)   ──► calls nativeStartExport         │
│  ├─ cancelExport(pv)                   ──► calls nativeCancelExport        │
│  └─ getExportProgress(pv)              ──► calls nativeGetExportProgress   │
│                                                                               │
│  VideoPreviewView.kt                                                        │
│  ├─ external fun nativeStartExport(...)                                    │
│  ├─ external fun nativeCancelExport()                                      │
│  └─ external fun nativeGetExportProgress(): Int                            │
│                                                                               │
└────────────────────────────────────────────────────────────────────────────┬─┘
                                    │ JNI Call
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    NATIVE ENGINE LAYER (C++)                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  native_preview.cpp                                                         │
│                                                                               │
│  nativeStartExport()                                                        │
│  ├─ Validate: no export in progress                                        │
│  ├─ Extract output path from JNI string                                    │
│  ├─ Create ExportConfig struct                                             │
│  │  ├─ Set: width, height, fps                                            │
│  │  ├─ Calculate: bitrate = calculateBitrate()                            │
│  │  └─ Set: audioSampleRate, codecs                                       │
│  ├─ Reset state:                                                           │
│  │  ├─ g_exportProgress = 0                                               │
│  │  ├─ g_exportCancelled = false                                          │
│  │  └─ g_isExporting = true                                               │
│  ├─ Log: "[Export] started resolution=1920x1080 fps=30 bitrate=12441..."  │
│  └─ Spawn background thread ──────────┐                                   │
│                                         │                                   │
│  nativeCancelExport()                  │                                   │
│  ├─ Check: g_isExporting == true       │                                   │
│  ├─ Set: g_exportCancelled = true      │                                   │
│  └─ Log: "[Export] cancel requested"   │                                   │
│                                         │                                   │
│  nativeGetExportProgress()             │                                   │
│  └─ Return: g_exportProgress (0-100)   │                                   │
│      or -1 if not exporting            │                                   │
│                                         │                                   │
│  ┌───────────────────────────────────────┴────────────────────────────────┐│
│  │ EXPORT THREAD (Background, async)                                      ││
│  │                                                                         ││
│  │ exportThreadProc()                                                     ││
│  │ │                                                                      ││
│  │ └─► For each 10% increment:                                           ││
│  │     ├─ Check g_exportCancelled flag                                  ││
│  │     │  └─ If true: break loop, return                               ││
│  │     ├─ Update: g_exportProgress = p                                 ││
│  │     ├─ Log: "[Export] progress 10%", "20%", etc.                    ││
│  │     └─ Sleep: 500ms (simulated work)                                ││
│  │                                                                      ││
│  │ On completion:                                                       ││
│  │ ├─ If not cancelled:                                               ││
│  │ │  ├─ Set: g_exportProgress = 100                                 ││
│  │ │  └─ Log: "[Export] completed output=/path/video.mp4"           ││
│  │ ├─ Set: g_isExporting = false                                     ││
│  │ └─ Thread exits (ready for next export)                           ││
│  │                                                                      ││
│  └──────────────────────────────────────────────────────────────────────┘│
│                                                                               │
│  Global State (atomic, thread-safe)                                        │
│  ├─ std::atomic<int32_t> g_exportProgress      [0-100%]                   │
│  ├─ std::atomic<bool> g_isExporting             [true/false]              │
│  ├─ std::atomic<bool> g_exportCancelled         [true/false]              │
│  ├─ VideoEngine::ExportConfig g_currentExportConfig                        │
│  └─ std::thread g_exportThread                                            │
│                                                                               │
└────────────────────────────────────────────────────────────────────────────┬─┘
                                    │ Output
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          OUTPUT FILE                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  /sdcard/DCIM/movie.mp4                                                     │
│  ├─ Codec: H.264 (configurable)                                            │
│  ├─ Resolution: 1920x1080 (user selected)                                  │
│  ├─ Framerate: 30 fps (user selected)                                      │
│  ├─ Bitrate: 12,441 kbps (auto-calculated)                                │
│  ├─ Duration: Same as project timeline                                     │
│  ├─ Video Tracks: All clips composited with transitions                    │
│  ├─ Effects: All GPU effects applied                                       │
│  ├─ Text Overlays: All active overlays at their timeline positions         │
│  └─ Audio: Mixed audio from all tracks                                     │
│                                                                               │
└─────────────────────────────────────────────────────────────────────────────┘
```

## State Diagram

```
┌──────┐
│ Idle │
└──┬───┘
   │
   │ nativeStartExport(path, w, h, fps)
   │ ├─ Create ExportConfig
   │ ├─ Reset progress to 0
   │ ├─ Spawn export thread
   │ └─ g_isExporting = true
   ▼
┌──────────────┐
│  Exporting   │ ◄─ Export thread running in background
├──────────────┤
│ • g_isExporting = true     │
│ • g_exportProgress = 0-100 │
│ • Polling from UI every    │
│   500ms: getExportProgress │
└──┬───────────┬─────────────┘
   │           │
   │ Completed │ Cancelled
   │ (100%)    │ (user clicks cancel)
   │           │
   │ nativeCancelExport()
   │ └─ g_exportCancelled = true
   │
   ▼           ▼
┌──────┐    ┌───────────┐
│ Done │    │ Cancelled │
└──────┘    └───────────┘
   │           │
   │ Export    │
   │ thread    │
   │ checks    │
   │ flag and  │
   │ returns   │
   │           │
   └─► g_isExporting = false
       │
       └─► Ready for next export
```

## Thread Interaction Diagram

```
MAIN THREAD                          RENDER THREAD              EXPORT THREAD
(UI, JNI calls)                      (GL rendering)             (background)

    │                                    │                           │
    │ User clicks Export                 │                           │
    ├─► nativeStartExport()              │                           │
    │   ├─ Create config                 │                           │
    │   └─ Spawn export thread           │─────────────────────────► │ Start
    │       (pass closure)               │                           │
    │       g_isExporting=true           │                           │
    │                                    │                           │
    │ Progress dialog starts             │                           │
    ├─► getExportProgress() every 500ms  │                           │
    │   └─ Read g_exportProgress         │                           │
    │       (atomic, lock-free)          │                           │
    │   └─ Update UI                     │                           │
    │                                    │                           │
    │                                    │                           │ Update
    │                                    │                           │ progress
    │                                    │                           │ every
    │                                    │                           │ 500ms
    │                                    │                           │
    │ (polling continues)                │                           │
    │                                    │                           │
    │ User clicks Cancel                 │                           │
    ├─► nativeCancelExport()             │                           │
    │   └─ Set g_exportCancelled=true    │                           │
    │                                    │                           │ Check
    │ Progress dialog shows Cancel Btn   │                           │ flag
    │                                    │                           │
    │ Progress reaches 100%              │                           │
    ├─► getExportProgress() returns 100  │                           │ Clean
    │   └─ Show success dialog           │                           │ up &
    │   └─ Offer Play/Share/Save         │                           │ exit
    │                                    │                           │
    │                                    │                           ▼
    │                                    │                    Thread exits
    │                                    │                    g_isExporting=false
    │                                    │
    │ No export in progress              │
    ├─► getExportProgress() returns -1   │
    │   └─ No progress bar shown         │
    │                                    │
```

## Memory Layout

```
Stack (Main Thread)
├─ JNI parameters
├─ Local variables
└─ Return addresses

Heap
├─ ExportConfig struct
│  ├─ outputPath: std::string
│  ├─ width: int32_t (4 bytes)
│  ├─ height: int32_t (4 bytes)
│  ├─ fps: int32_t (4 bytes)
│  ├─ bitrate: int32_t (4 bytes)
│  └─ ... (more fields)
│
├─ Atomic variables (lock-free)
│  ├─ g_exportProgress: atomic<int32_t> (4 bytes)
│  ├─ g_isExporting: atomic<bool> (1 byte)
│  └─ g_exportCancelled: atomic<bool> (1 byte)
│
├─ Export thread storage
│  ├─ Stack: ~1 MB
│  └─ Local variables
│
├─ OpenGL Framebuffer (if needed for capturing frames)
│  └─ 1920x1080x4 = 8.1 MB (RGBA format)
│
└─ FFmpeg Encoder buffers (when implemented)
   └─ 10-50 MB depending on codec

Total Memory (demo): ~10-60 MB
Total Memory (with frame capture): ~60-110 MB
```

## Progress Update Sequence

```
Time    Export Thread                 UI Thread
────    ──────────────                ─────────
0ms     Start
        Update g_exportProgress=0     
        Log: [Export] progress 0%     
        Sleep 500ms                   
                                      User sees: 0%
500ms   Update g_exportProgress=10    
        Log: [Export] progress 10%    
        Sleep 500ms                   Poll: getExportProgress() → 10
                                      Update UI: 10%
1000ms  Update g_exportProgress=20    
        Log: [Export] progress 20%    
        Sleep 500ms                   Poll: getExportProgress() → 20
                                      Update UI: 20%
1500ms  Update g_exportProgress=30    
        Log: [Export] progress 30%    
        Sleep 500ms                   Poll: getExportProgress() → 30
                                      Update UI: 30%
...
4500ms  Update g_exportProgress=100   
        Log: [Export] completed       
        Set g_isExporting=false       Poll: getExportProgress() → 100
                                      Update UI: 100%
                                      Show success dialog
```

## File I/O Timeline

```
User selects export options
     │
     ├─► nativeStartExport(path, w, h, fps)
     │   └─ Receives JNI string
     │   └─ Extract C++ string: std::string outputPath = ...
     │   └─ Store in ExportConfig.outputPath
     │
     └─ Spawn export thread
        └─ Closure captures outputPath
           
Export thread (background)
     │
     ├─ Initialize FFmpeg encoder (when implemented)
     │  └─ Open file for writing: outputPath
     │  └─ Setup video codec parameters
     │
     ├─ For each frame:
     │  ├─ Render frame via GPU
     │  ├─ Encode via FFmpeg
     │  └─ Write encoded data to file
     │
     └─ Finalize file
        └─ Close file handle
        └─ File is now ready for playback
           
UI thread
     │
     └─ Poll getExportProgress()
        └─ When 100%:
           ├─ File exists at outputPath
           ├─ User can Play/Share/Save
           └─ File ready for use
```

## Bitrate Calculation Example

```
Input: 1920x1080 resolution, 30 fps

calculateBitrate():
  │
  ├─ pixelsPerSecond = 1920 * 1080 * 30 = 62,208,000
  │
  ├─ qualityFactor:
  │  └─ width=1920, height=1080 → qualityFactor = 0.2 (4K quality)
  │
  ├─ calculatedBitrate = 62,208,000 * 0.2 / 1000 = 12,441.6 kbps
  │
  └─ Return: 12,441 kbps
  
Result: 1920x1080 @ 30fps → 12.4 Mbps bitrate
File size estimate: 12.4 Mbps * 60s = 93 MB per minute
```

## See Also
- [Export Implementation Complete](EXPORT_IMPLEMENTATION_COMPLETE.md)
- [Export Quick Reference](EXPORT_QUICKREF.md)


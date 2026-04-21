# Crash-Safety Quick Testing Guide

## Pre-Test Setup
1. Build project: `cmake --build build --config Release`
2. Deploy to device: `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`
3. Clear app data: `adb shell pm clear com.video.engine`

---

## Quick Tests (5 minutes each)

### Test 1: Low-End Device Quality Adaptation
```bash
# On any device, check logcat for quality preset
adb logcat | grep -i "quality\|resolution\|DeviceDetector"
```
**Expected Output:**
```
I/VideoEngine: Device RAM: 1500 MB
I/VideoEngine: Quality Preset: Low (540p @ 24fps)
```

### Test 2: Click Guard (Prevent Double Export)
1. Tap "Export" button
2. **IMMEDIATELY** tap "Export" again (before dialog appears)
3. **Expected**: Only one export dialog opens, no duplicate exports

### Test 3: Lifecycle Safety (Background Export)
1. Tap "Export" → Select settings → Start export
2. Wait ~2 seconds (export in progress)
3. Press HOME button (app backgrounded)
4. **Expected**: Notification bar shows "Exporting Video" with progress
5. Swipe notification to see full export details
6. **Expected**: Export completes even with app backgrounded

### Test 4: Rotation During Export
1. Tap "Export" → Select settings → Start export
2. Wait ~2 seconds
3. Rotate device 90° (change orientation)
4. **Expected**: UI re-orients, progress dialog re-appears, export continues uninterrupted

### Test 5: Pause/Resume During Export
1. Tap "Export" → Start export
2. Wait ~2 seconds
3. Press power button (lock screen)
4. Press power button again (unlock)
5. **Expected**: App resumes, progress dialog visible, export continues

### Test 6: Error Dialog (User-Friendly)
1. Unplug device from USB cable (or disable write permission)
2. Tap "Export" → Select output location → Start export
3. **Expected**: Error dialog appears with user-friendly message, NOT stack trace
4. Example message: "Export Failed: Could not write to storage. Check permissions."

---

## Crash Handler Inspection

Check if crash occurred and what context was captured:
```bash
adb shell am force-stop com.video.engine  # Force crash (for testing)
adb shell am start com.video.engine  # Restart app
adb shell content query --uri content://com.video.engine.crashprovider  # Check crash context
```

Or check SharedPreferences directly:
```bash
adb shell run-as com.video.engine cat /data/data/com.video.engine/shared_prefs/crash_handler.xml
```

**Expected Output:**
```xml
<map>
  <string name="last_action">startExport</string>
  <string name="last_frame_time">12345</string>
  <string name="export_state">exporting</string>
  <string name="device_info">Pixel 4, Android 11, RAM: 6GB</string>
</map>
```

---

## Logcat Filter for Stability Issues

Monitor all crash-safety related logs:
```bash
adb logcat | grep -E "CrashHandler|DeviceDetector|ExportService|ERROR|EXCEPTION"
```

---

## Performance Metrics to Monitor

After successful export on low-end device:
```bash
adb shell dumpsys meminfo com.video.engine | head -20
```

**Expected**: Memory usage reasonable (< 500MB), no OOM messages

---

## Manifest Verification

Verify service and permissions are declared:
```bash
adb shell dumpsys package com.video.engine | grep -E "Service|Permission"
```

**Expected Output:**
```
Service:
  com.video.engine.ExportService
Permissions:
  android.permission.POST_NOTIFICATIONS
  android.permission.WAKE_LOCK
```

---

## Build Verification

```bash
cd /home/am/video_engine_core
cmake --build build --config Release -j$(nproc)
```

**Expected**: `[100%] Built target video_engine` with NO errors

---

## Known Limitations

1. **DeviceDetector only detects RAM via ActivityManager** - Not 100% accurate on all devices
   - Workaround: Manually set quality preset in settings if auto-detection is wrong

2. **ExportService uses dataSync foregroundServiceType** - Respects battery saver on Android 12+
   - Note: Long exports may still be killed in battery saver mode
   - Workaround: User can disable battery saver during export

3. **Crash context only saved if CrashHandler initialized**
   - Pre-init crashes (very rare) won't be logged
   - Workaround: All initialization happens in MainActivity.onCreate() before any user action

---

## Common Issues & Solutions

| Issue | Cause | Solution |
|-------|-------|----------|
| Export dialog doesn't appear | CrashHandler.setLastAction() not called | Check MainActivity.setupExportDialog() |
| Multiple exports run concurrently | Click guard not initialized | Check `isExporting` flag in MainActivity.onCreate() |
| Notification doesn't show | POST_NOTIFICATIONS not granted (Android 13+) | Request permission in onCreate() |
| Export continues but app foreground service crashes | ExportService lifecycle issue | Check onDestroy() stops service properly |
| CrashHandler returns null | Context not passed to getInstance() | Always use `CrashHandler.getInstance(context)` |

---

## Success Criteria

✅ All tests pass on both high-end and low-end devices
✅ Build completes with `[100%] Built target video_engine`
✅ No ANR (Application Not Responding) errors in stress tests
✅ CrashHandler captures device info after crashes
✅ User sees friendly error dialogs instead of stack traces
✅ Background export (via ExportService) completes even when app backgrounded
✅ Lifecycle events (pause/resume/rotate) don't interrupt export
✅ Memory usage stays reasonable on low-end devices

---

**Ready to test!** 🚀

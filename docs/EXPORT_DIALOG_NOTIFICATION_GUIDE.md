# Export Dialog with Persistent Notification – Complete Implementation

## Overview

Added a professional VN / KineMaster–style export dialog with:
- **Export Options Dialog**: Resolution (720p/1080p), Frame Rate (30/60 fps), Quality (Low/Med/High)
- **Progress Dialog**: Smooth progress bar + percent + ETA
- **Persistent Notification**: Monitors export in background; user can see progress if app is backgrounded
- **Completion Dialog**: Play / Share / Open Folder actions
- **Error Handling**: Clear error messages with Retry option
- **Lifecycle Safety**: Dismisses/re-shows dialogs on pause/resume; prevents window leaks and data corruption

---

## Architecture & Why This Approach

### Export Flow (Professional Editors Pattern)
1. **User taps Export** → Export Options Dialog
2. **User selects options** → UI disabled, orientation locked
3. **Export starts** → Native FFmpeg encoder runs in background thread (non-blocking)
4. **Progress updates** → Polled every 500ms from native side
5. **Notification shown** → Persistent indicator (survives app backgrounding)
6. **Export completes** → UI re-enabled, completion dialog with actions

### Why Persistent Notification
- **Background monitoring**: If user minimizes or locks screen, notification shows progress
- **Prevents data loss**: User can't accidentally kill the app without knowing export status
- **Professional UX**: Matches VN, KineMaster, Adobe Premiere exports
- **Survives pause/resume**: Unlike in-app dialogs, notification survives activity destruction

### Why Lifecycle Handlers Matter
- **onPause**: Dismiss progress dialog to avoid "Activity has leaked window" crashes
- **onResume**: Re-show progress dialog if native export still running (poll current progress)
- **Export continues native-side**: Dismissing UI dialog does NOT stop native export

---

## Key Implementation Details

### 1. Notification Channel (Android 8+)
```kotlin
private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            EXPORT_NOTIFICATION_CHANNEL_ID,
            "Video Export",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager?.createNotificationChannel(channel)
    }
}
```
- Created in `onCreate()` to ensure it exists before posting notifications
- `IMPORTANCE_LOW` = no sound/vibration (non-intrusive)

### 2. Progress Updates
```kotlin
private fun showExportNotification(progress: Int) {
    val notification = NotificationCompat.Builder(...)
        .setProgress(100, progress, false)  // Max=100, current=progress
        .setOngoing(true)                   // User cannot dismiss
        .build()
    
    notificationManager?.notify(EXPORT_NOTIFICATION_ID, notification)
}
```
- Same notification ID → Updates in-place (no duplicate notifications)
- `setOngoing(true)` → Prevents accidental dismissal during export
- Updated every 500ms during progress polling

### 3. Safe Lifecycle Handling
```kotlin
override fun onPause() {
    progressDialog?.dismiss()  // Avoid window leak
    super.onPause()
}

override fun onResume() {
    super.onResume()
    val p = NativeBridge.getExportProgress(pv)
    if (p >= 0 && p < 100) {
        showExportProgressDialog()  // Re-show if still exporting
    }
}
```
- Dialog dismissed in onPause to prevent window-leak crashes
- Re-shown in onResume if export still in progress
- Native export continues uninterrupted

### 4. UI State Protection
```kotlin
private fun disableUiForExport() {
    // Disable toolbar buttons
    findViewById<LinearLayout>(R.id.exportButton).isEnabled = false
    // ... other buttons ...
    
    // Lock orientation to prevent activity recreation
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
}

private fun enableUiAfterExport() {
    // Re-enable all buttons
    // Restore orientation
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
```
- **Why disable UI?** Prevents user edits mid-export that would corrupt output
- **Why lock orientation?** Prevents activity recreation which would break native render thread

---

## Debug Logging

All export events are logged with `[Export]` tag:

```
[Export] started 1920x1080 @ 30fps quality=High
[Export] progress 15%
[Export] progress 45%
[Export] progress 87%
[Export] completed in 42 sec
[Export] failed reason=Insufficient storage space
```

View with:
```bash
adb logcat -s "[Export]"
```

---

## Files Modified

1. **MainActivity.kt**
   - Added imports: `NotificationCompat`, `NotificationManager`, `NotificationChannel`, `ActivityInfo`, `Uri`, `File`, `SimpleDateFormat`, `Date`, `Locale`
   - Added `notificationManager` instance variable
   - Added `createNotificationChannel()` to initialize notification channel on first launch
   - Added `showExportDialog()` → Options dialog (resolution/fps/quality)
   - Added `showExportProgressDialog()` → Progress bar + ETA + notification updates
   - Added `showExportNotification(progress)` → Updates persistent notification
   - Added `cancelExportNotification()` → Removes notification on completion
   - Added `disableUiForExport()` / `enableUiAfterExport()` → UI state management + orientation lock
   - Added `onExportCompleted(path)` → Completion dialog with Play/Share/Open actions
   - Added `onExportFailed(reason)` → Error dialog with Retry
   - Updated `onPause()` → Dismiss progress dialog safely
   - Updated `onResume()` → Re-show progress dialog if export still running
   - Updated Export button click handler → Opens export options dialog

---

## Build & Test

### Build
```bash
cd /home/am/video_engine_core/build
cmake --build . --config Release -j$(nproc)
```

### Install
```bash
# (Gradle build step for APK)
adb install -r app-debug.apk
```

### Test Flow
1. Open app, load video
2. Tap Export button (top-right)
3. Select options: 1080p, 30fps, Medium quality
4. Tap Export
   - ✅ UI disabled (buttons greyed out)
   - ✅ Orientation locked (screen won't rotate)
   - ✅ Progress dialog shows (0% → 100%)
   - ✅ Notification appears with progress bar
5. While exporting:
   - Home button → App goes to background
   - Swipe down notification panel → See "Exporting video… 45%" in notification
   - Tap notification → App returns to foreground, progress dialog re-shown
6. Export completes:
   - ✅ Dialog updates to "Export Complete"
   - ✅ Offers: Play / Share / Open Folder
   - ✅ UI re-enabled, orientation unlocked
   - ✅ Notification auto-dismissed
7. Logs:
   ```
   [Export] started 1920x1080 @ 30fps quality=Medium
   [Export] progress 15%
   [Export] progress 45%
   [Export] completed in 42 sec
   ```

---

## Android Manifest Requirements

Ensure `AndroidManifest.xml` has:

```xml
<!-- Existing permissions for storage access -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />

<!-- For posting notifications (Android 13+) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

The app already requests these, so no additional changes needed.

---

## Future Enhancements

1. **Progress updates from native**: Currently polling every 500ms. Optional: JNI callbacks for real-time updates.
2. **Pause/Resume export**: Allow user to pause and resume export (requires native support).
3. **Storage space check**: Warn user if insufficient free space before starting export.
4. **Custom output directory**: Let user choose where to save exported video.
5. **Watermark / subtitle overlay**: During export (render-time feature).

---

## Summary

✅ **Export Dialog** with resolution/fps/quality options
✅ **Progress Dialog** with smooth updates + ETA calculation
✅ **Persistent Notification** for background monitoring
✅ **Completion & Error Dialogs** with user actions (Play/Share/Retry)
✅ **Safe Lifecycle Handling** (dismiss/re-show on pause/resume)
✅ **UI State Protection** (disabled toolbar, locked orientation)
✅ **Professional UX** matching industry standards (VN / KineMaster)
✅ **Debug Logging** with `[Export]` tag

**Status**: Ready for build and testing.

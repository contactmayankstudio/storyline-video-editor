# Firebase Remote Config

Storyline already has in-app defaults for ops telemetry. Remote Config is used to override those values from Firebase without shipping a new APK.

Current client parameters:

- `ops_perf_tracing_enabled`
  - type: `Boolean`
  - default: `true`
  - use: turns Firebase Performance traces for playback/import/export on or off
- `ops_health_heartbeat_interval_ms`
  - type: `Number`
  - default: `45000`
  - use: controls how often Live App Health writes heartbeat updates
- `ops_force_flush_actions`
  - type: `String`
  - default: `playback_started,playback_paused,crop_opened,crop_closed,video_clip_imported,overlay_clip_imported,layer_clip_imported,audio_clip_imported,export_dialog_opened,export_started,export_completed,export_failed,project_saved,project_loaded,video_clip_deleted,audio_clip_deleted`
  - use: action names that should force an immediate Firestore health write

How to publish in Firebase console:

1. Open Firebase Console for project `storyline-cbd6a`.
2. Go to `Remote Config`.
3. Open the menu in the top-right of the parameters page.
4. Choose `Publish from a file`.
5. Select [`remoteconfig.template.json`](/home/am/storyline/remoteconfig.template.json).
6. Publish.

Important:

- If Remote Config is blank, Storyline still works because these defaults are already baked into [`OpsReporter.kt`](/home/am/storyline/android/app/src/main/kotlin/com/video/engine/OpsReporter.kt).
- Remote Config values override the in-app defaults only after `fetchAndActivate()` succeeds.

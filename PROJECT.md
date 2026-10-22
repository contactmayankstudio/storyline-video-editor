# Project: Storyline Live AR Camera & Mast Status Maker Live Preview

## Architecture
- **Mast Status Maker: Real 9:16 Animated Live Video Preview Engine (R1)**:
  - `StatusTemplateLivePreviewView`: Hardware-accelerated 2D Canvas custom view enforcing strict 9:16 aspect ratio with rounded corner geometry.
  - Live photo slideshow: Procedural & asset-based aesthetic portrait model photo sequence transitioning across template slots with Ken Burns zoom/pan, beat shake impact, RGB glitch slice, and flash cuts.
  - Beat-synchronized animation loop: Synchronized with BGM audio playback via `template.bpm` and `MediaPlayer.currentPosition`.
  - Neon audio visualizer: Live pulsing equalizer waveform bars reacting to sub-bass beat impacts and harmonic oscillations.
  - Kinetic glowing lyrical typography: Dynamic text overlays with elastic pop bounce, beat-synced kinetic scaling, dual-pass neon glow blur, and karaoke shimmer sweeps.
  - 1-Click "Select Photos & Create Status" flow: Direct transition from preview dialog to `StatusProjectBuilder` and `StatusQuickExportActivity` without forcing timeline editing.
- **Fun Camera: Smart Face Action Triggers (R2)**:
  - `FaceLandmarkTracker`: ML Kit face tracking (`com.google.mlkit:face-detection:16.1.7`) with fast performance mode, landmark mode ALL, classification mode ALL. Normalized [0..1] coordinates, mirrored front-camera handling, exponential smoothing.
  - Enhanced `SmoothedFaceData`: Bounds, left/right eyes, nose, mouth center, left/right cheeks, mouth corners, individual winking eye indicators, continuous smile probability.
  - Gesture triggers:
    - Mouth Open (`isMouthOpen`, `mouthOpenRatio`): Cascading particle fountain (Rainbow beam / Fire burst / Golden hearts) from mouth center.
    - Smile (`smilingProbability > 0.65`): Kawaii Heart Eyes with specular glint, Rosy Cheeks Blush radial gradient, and orbiting Sparkle Halo.
    - Wink (`isWinking`, specific closed eye): High-intensity camera flash starburst with expanding shockwave and diamond shards.
  - Zero-allocation Canvas particle system: 300-particle reusable pool, velocity/acceleration physics, and 60 FPS animation loop.
- **Fun Camera: Snapchat-Style Pro Shutter & Gestures (R3)**:
  - `CameraShutterButton`: Custom view featuring 360° gradient circular progress ring (`SweepGradient`), inner shape morphing (white circle to red rounded square), hold-to-record video, and drag-to-zoom (1.0x to 5.0x).
  - Viewfinder gestures:
    - Tap-to-focus: Animated scaling yellow bracket ring with crosshairs + Camera2 AF trigger & metering region.
    - Double-tap flip: Viewfinder double tap invoking `flipCamera()` with haptic feedback.
    - Swipe filter switch: Horizontal fling gesture cycling lenses with floating animated lens name pill.
- **Fun Camera: Beauty Glow & Cinematic Filter FX (R4)**:
  - Real-Time Beauty Soft Glow: Warm peach radiance gradient, eye brightening catchlight circles, and cheek blush.
  - 90s VHS Camcorder HUD: Horizontal CRT scanlines, blinking `● REC 📼`, tape status, and retro monospace date/time timestamp.
  - Cyberpunk Neon & Vintage: Dual-tone cyan/magenta gradient, horizontal chromatic glitch slice, HUD brackets, and warm 35mm grain.
  - Front Screen Night Ring-Light Flash: Warm ivory (3200K) inward-feathering screen border with elevated screen brightness.
  - Photo capture layer baking: `drawCaptureLayers()` in `CameraFilterOverlayView` invoked by `FunCameraActivity.capturePhoto()` to bake all active AR lens props, gesture particles, and visual styles onto the saved JPEG.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | 9:16 Animated Video Preview Player | Hardware-accelerated `StatusTemplateLivePreviewView` replacing static emoji card | M1 | ORIGINAL_REQUEST §R1 |
| 2 | Model Portrait Photo Slideshow | Aesthetic model portrait photo slideshow transitioning per template slot durations | M1 | ORIGINAL_REQUEST §R1 |
| 3 | Beat-Synced Ken Burns & Transition FX | Ken Burns zoom/pan, beat shake impact, RGB glitch, and flash cuts synced to BPM | M1 | ORIGINAL_REQUEST §R1 |
| 4 | Live Pulsing Neon Equalizer Visualizer | Symmetrical neon audio waveform visualizer pulsing to BGM sub-bass beats | M1 | ORIGINAL_REQUEST §R1 |
| 5 | Kinetic Glowing Lyrical Typography | Elastic pop-in, beat-synced kinetic scaling, dual-pass neon glow, and karaoke shimmer | M1 | ORIGINAL_REQUEST §R1 |
| 6 | 1-Click Direct Status Quick Export Flow | Direct flow from preview modal to `StatusProjectBuilder` & `StatusQuickExportActivity` | M1 | ORIGINAL_REQUEST §R1 |
| 7 | ML Kit Face Landmark & Gesture Detection | Enhanced `FaceLandmarkTracker` with cheeks, mouth center, winking eye & smile ratio | M2 | ORIGINAL_REQUEST §R2 |
| 8 | Mouth Open Particle Bursts | Rainbow beam, fire burst, and golden hearts bursting from mouth aperture | M2 | ORIGINAL_REQUEST §R2 |
| 9 | Smile Trigger (Heart Eyes, Blush, Halo) | Kawaii Heart Eyes, Rosy Cheeks Blush, and Sparkle Halo triggered when smile > 0.65 | M2 | ORIGINAL_REQUEST §R2 |
| 10 | Wink Trigger (Camera Flash Starburst) | 8-point rotating starburst, shockwave ring, and sparkles anchored to winking eye | M2 | ORIGINAL_REQUEST §R2 |
| 11 | Zero-Allocation Canvas Particle Engine | High-performance 300-particle pool with physics integration for 60fps rendering | M2 | ORIGINAL_REQUEST §R2 |
| 12 | 360° Gradient Radial Shutter Ring | `CameraShutterButton` with circular sweep progress ring and morphing inner shape | M3 | ORIGINAL_REQUEST §R3 |
| 13 | Shutter Drag-to-Zoom | Smooth thumb drag upward zooming 1.0x to 5.0x via Camera2 API | M3 | ORIGINAL_REQUEST §R3 |
| 14 | Viewfinder Swipe Lens Switch & Pill | Horizontal fling cycling lenses with animated floating lens name pill | M3 | ORIGINAL_REQUEST §R3 |
| 15 | Double-Tap Viewfinder Camera Flip | Double tap on viewfinder flipping front/back camera with haptic feedback | M3 | ORIGINAL_REQUEST §R3 |
| 16 | Tap-to-Focus Ring & Camera2 AF | Scaling yellow focus bracket ring + Camera2 `CONTROL_AF_TRIGGER_START` & metering | M3 | ORIGINAL_REQUEST §R3 |
| 17 | Real-Time Beauty Soft Glow | Soft skin smoothing, warm peach tone, and eye brightening overlay | M4 | ORIGINAL_REQUEST §R4 |
| 18 | 90s VHS Camcorder HUD | Retro monospace timestamp, blinking `● REC 📼`, tape status, and CRT scanlines | M4 | ORIGINAL_REQUEST §R4 |
| 19 | Cyberpunk Neon & Vintage Film FX | Chromatic glitch styling, cyan/magenta telemetry HUD, and warm 35mm grain | M4 | ORIGINAL_REQUEST §R4 |
| 20 | Front Screen Night Ring-Light Flash | Soft warm ivory screen border illuminating selfies in low light | M4 | ORIGINAL_REQUEST §R4 |
| 21 | Photo Capture Layer Baking | Comprehensive overlay baking onto captured JPEG bitmap | M4 | ORIGINAL_REQUEST §R4 |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Mast Status Maker 9:16 Live Preview Engine | `StatusTemplateLivePreviewView.kt`, `dialog_status_template_live_preview.xml`, `StatusMakerActivity.kt` preview modal integration, 1-Click quick export flow | none | DONE |
| M2 | Fun Camera Smart Face Action Triggers & Particles | `FaceLandmarkTracker.kt`, `SmoothedFaceData`, Zero-allocation particle engine, mouth open bursts, smile Kawaii triggers, wink star flash | none | DONE |
| M3 | Fun Camera Snapchat-Style Shutter & Gestures | `CameraShutterButton.kt`, `FunCameraActivity.kt` viewfinder gestures (swipe, double-tap, tap-to-focus, drag-to-zoom), layout updates | none | DONE |
| M4 | Fun Camera Beauty Glow, Cinematic FX & Photo Baking | `CameraFilterOverlayView.kt` filter implementations (Beauty, VHS, Cyberpunk, Night flash), `drawCaptureLayers`, `FunCameraActivity.capturePhoto` baking | M2, M3 | DONE |
| M5 | Comprehensive Code Verification & Forensic Audit | Unit tests for data models & math, static analysis, syntax verification, reviewer approval, challenger verification, forensic integrity audit | M1, M2, M3, M4 | IN_PROGRESS |

## Interface Contracts

### Status Maker Live Preview ↔ StatusMakerActivity
- `StatusTemplateLivePreviewView.bindTemplate(template: StatusTemplate, mp: MediaPlayer?)`: Prepares template slots, sample photos, BPM synchronization, and starts render loop.
- `StatusTemplateLivePreviewView.release()`: Cleans up animators, recycled bitmaps, and audio references.
- `StatusMakerActivity.showTemplateLivePreviewDialog(template: StatusTemplate)`: Inflates `dialog_status_template_live_preview.xml`, binds view, and wires CTA to `pickPhotosLauncher`.

### Face Tracking ↔ Camera Filter Overlay
- `FaceLandmarkTracker.processFrame(bmp: Bitmap, isFrontFacing: Boolean, onFaceDetected: (SmoothedFaceData) -> Unit)`: Extracts landmarks, computes gesture ratios/probabilities, and returns smoothed face data.
- `CameraFilterOverlayView.faceData: SmoothedFaceData`: Consumed by `onDraw()` to drive particle emitters, eye glints, cheek blush, and halo.

### Camera Shutter ↔ FunCameraActivity
- `CameraShutterButton.onPhotoCapture`: Invoked on tap in photo mode -> calls `capturePhoto()`.
- `CameraShutterButton.onRecordStart` / `onRecordStop`: Invoked on tap in video mode or hold-to-record -> calls `startVideoRecording()` / `stopVideoRecording()`.
- `CameraShutterButton.onDragZoom`: Invoked during vertical thumb drag -> calls `applyZoom(zoomRatio)`.

### Viewfinder Gestures ↔ Camera2 API
- `FunCameraActivity.triggerAutoFocus(x: Float, y: Float)`: Converts view touch to sensor coordinate `MeteringRectangle` and triggers `CONTROL_AF_TRIGGER_START`.
- `FunCameraActivity.applyZoom(zoomRatio: Float)`: Updates `CONTROL_ZOOM_RATIO` or `SCALER_CROP_REGION` on `previewRequestBuilder` and resubmits to `captureSession`.
- `FunCameraActivity.flipCamera()`: Re-initializes camera with toggled lens facing (`LENS_FACING_FRONT` vs `LENS_FACING_BACK`).

## Code Layout
- `android/app/src/main/kotlin/com/video/engine/status/`:
  - `StatusTemplateLivePreviewView.kt`: Custom 9:16 view rendering animated portrait slideshow, Ken Burns, beat shake, RGB glitch, neon visualizer, kinetic lyrics.
  - `StatusMakerActivity.kt`: Updated modal inflation and lifecycle management.
- `android/app/src/main/res/layout/`:
  - `dialog_status_template_live_preview.xml`: 9:16 preview dialog layout with live preview view, metadata chips, and CTA button.
  - `activity_fun_camera.xml`: Updated shutter button (`CameraShutterButton`) and floating lens pill container.
- `android/app/src/main/kotlin/com/video/engine/camera/`:
  - `FaceLandmarkTracker.kt`: Extended `SmoothedFaceData`, cheek and mouth corner landmarks, winking eye tracking, continuous smile probability.
  - `CameraShutterButton.kt`: Snapchat-style 360° gradient radial progress ring, drag-to-zoom, morphing inner shutter.
  - `CameraFilterOverlayView.kt`: Particle system, gesture effect routines (`drawMouthOpenEffects`, `drawSmileEffects`, `drawWinkEffects`), filter routines (`drawBeautyRadiance`, `drawVhs90sHud`, `drawCyberpunkHud`, `drawWarmSunset`, `drawRuleOfThirdsGrid`, `drawFocusRing`, `drawNightFlashBorder`), and photo capture baking (`drawCaptureLayers`).
  - `FunCameraActivity.kt`: Viewfinder gesture integration (tap-to-focus, double-tap flip, swipe lens switch with pill), Camera2 AF & zoom integration, and photo baking.

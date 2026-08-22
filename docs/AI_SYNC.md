# Storyline AI Sync & Collaboration Bridge

> **Purpose**: This document bridges collaboration between **Antigravity (Local IDE & Build Agent)** and **ChatGPT (Architecture & Roadmap Planner)** via the GitHub repository (`sarojshahu12-max/storyline`).

---

## 📌 Project Overview
- **Repository**: `sarojshahu12-max/storyline`
- **Current Branch**: `main`
- **Application**: Storyline — Professional Mobile Video Editor for Android
- **Target Device**: Redmi 9A (`M2006C3LI`, 2GB RAM, Android 10)
- **Primary Design Identity**: Premium Dark Workspace (`#07090D` / `#0D1117`), Storyline Blue Accent (`#388BFD`), 10dp rounded corners, restrained modern UI.

---

## ✅ Phase Status

Use these statuses strictly:

- **IMPLEMENTED** = code/UI exists in the repository.
- **BUILD VERIFIED** = project successfully built for the relevant change.
- **DEVICE VERIFIED** = manually exercised on the physical Redmi 9A.
- **COMPLETE** = implementation + build + required device verification are all confirmed.
- **UNVERIFIED** = not yet manually tested on the physical device.

Never mark a phase **COMPLETE** only because the code exists.

---

## 🚀 Phase Summary

### Phase 1: Professional Premium UI/UX Design System
**Status: IMPLEMENTED**
- Standardized `DesignSystem.kt`, `colors.xml`, `dimens.xml`, `styles.xml`.
- Professional dark elevation palette (`surface_base`, `surface_card`, `surface_elevated`, `surface_floating`).
- Unified typography hierarchy (Headline, Title, Body, Caption).

### Phase 1.5: Editor Shell Premium Polish
**Status: IMPLEMENTED**
- **Top Bar**: Solid Storyline Blue primary `Export` button (`#388BFD`), 32dp vertical alignment for back/ratio/export.
- **Playback Controls**: Centered 44dp dominant Play/Pause button with secondary 34dp Undo/Redo buttons.
- **Bottom Toolbar**: Destructive red styling for Delete (`#F85149`), primary highlight for Add/Media, compact professional buttons.

### Phase 2: Premium Timeline UX Polish
**Status: IMPLEMENTED**
- **Track Hierarchy**: `T1` (Text), `O1` (Overlay), `V1` (Video), `A1` (Audio) with compact badges.
- **Smart Clip Labels**: Aggressive filename cleanup — displays compact corner pill badges (`Video 01`, `Overlay 01`, `Main Hoon`) leaving video thumbnails 100% visible and unblocked.
- **Audio Waveforms**: Distinct audio track styling with waveform visualization.
- **Ruler**: Clean `00:00`, `00:01`, `00:02` second markers.

### Phase 3 & 3.1: Professional Timeline Interaction & Editing UX
**Status: IMPLEMENTED — DEVICE VERIFICATION PENDING**
- **Clip Selection & Handles**: Crisp 1.5dp blue outline (`#388BFD`) with touch-friendly white grab handles.
- **Functional Split**: Playhead-position clip splitting with preservation of relevant clip metadata.
- **Drag & Reorder**: Horizontal dragging with boundaries, snap indicators, and negative timestamp prevention.
- **Undo / Redo**: Lightweight `UndoDomain.EDITOR` tracking for move, trim, split, and delete operations.
- **Duration Consistency**: Unified clip/project/ruler/playhead time calculation.
- **Empty State**: `Add media` direct-to-import state implemented.
- **Toolbar Label**: `Transit` renamed to `Transitions`.

### Phase 4: Premium Media & Import UX
**Status: IMPLEMENTED — DEVICE VERIFICATION PENDING**

Implementation currently includes:
- **Custom Bottom Sheet (`MediaPickerSheet.kt`)**: dark bottom sheet with `Video`, `Photo`, `Audio` tabs.
- **Async Media Loading**: 2-thread MediaStore background executor.
- **Thumbnail Cache**: 20MB `LruCache` for decoded bitmaps.
- **Video & Photo Grid**: 3-column grid with downsampled thumbnails, duration pills and multi-select support.
- **Audio Studio List**: audio rows with icon, title, artist and duration.
- **Track Routing**: toolbar `Media`, `Overlay`, `Audio` and per-track `+` actions open the picker with the correct target context.
- **Fallback System Picker**: `Browse Files` / SAF fallback path.

### Phase 4 Visual QA Notes
From current UI review, these items require verification or follow-up rather than being assumed complete:
- Export resolution labels must match actual output (do not label 540p as HD).
- Watermark default/state must match the intended Storyline product policy.
- Media thumbnails must be validated on the low-RAM physical device.
- Import routing must be verified for V1/O1/A1.
- Multi-select behavior must be manually verified.
- Empty media state and bottom-sheet scrolling must be manually verified.

---

## 🧪 Phase 5: Real Device Manual QA
**Status: PLANNED**

### Target Device
- Redmi 9A / `M2006C3LI`
- Android 10
- Physical device only; do not substitute an emulator when the physical device is connected.

### Required Manual QA

#### App & Navigation
- Launch app.
- Home screen.
- New project.
- Back navigation.
- Editor entry/exit.

#### Editor
- Preview.
- Play/Pause.
- Undo/Redo.
- Current time/total duration.
- Canvas ratio.

#### Timeline
- Video import.
- Overlay import.
- Audio import.
- Clip selection.
- Trim handles.
- Split.
- Delete.
- Move/reorder.
- Playhead drag.
- Horizontal timeline scroll.
- Timeline responsiveness.

#### Media Picker
- Video tab.
- Photo tab.
- Audio tab.
- Thumbnail loading.
- Multi-select.
- Add to timeline.
- Browse Files/SAF fallback.
- Correct target-track routing.

#### Tool Sheets
- Canvas.
- Trim.
- Speed.
- Rotate/Flip.
- Reverse.
- Chroma Key.
- Color.
- Effects.
- Transitions.
- Graphics.
- Text.
- Audio.
- Overlay.
- Pro Studio.
- Export.

#### Export
- At least one real export on the physical device.
- Verify output file creation.
- Verify duration.
- Verify audio presence when expected.
- Verify edited timeline state is represented.
- Verify 720p/1080p labels match actual output when available.
- Verify watermark state matches product policy.

### QA Reporting Rules
Every test must be classified as:

- `PASS` = manually verified on physical device.
- `FAIL` = manually tested and incorrect.
- `UNVERIFIED` = not manually tested.

Do not report a feature as `VERIFIED` based only on source-code inspection.

For every bug, record:
- Bug ID
- Severity
- Area
- Reproduction steps
- Observed result
- Expected result
- Status (`OPEN`, `FIXED`, `RETESTED`)

### Performance Observation
Record only observed behavior for:
- Startup
- Timeline scrolling
- Playhead scrubbing
- Playback
- Media thumbnail loading
- Bottom-sheet interaction
- Export
- Memory/crash behavior

Do not invent FPS/RAM figures.

---

## 🛠️ Architecture Reference for ChatGPT
1. **Presentation Layer**:
   - `MainActivity.kt` (~16.6k lines): Central editor activity.
   - `TimelineCanvasView.kt` (~1.9k lines): High-performance single-canvas timeline renderer.
   - `MediaPickerSheet.kt`: Premium bottom-sheet media browser with asynchronous caching.
   - `VideoPreviewView.kt`: Custom GL surface host for native GPU frames.
2. **Native Video Engine (C++ / JNI)**:
   - `NativeBridge.kt`: JNI bridge connecting Kotlin to C++ timeline commands such as `SPLIT`, `UPDATE_CLIP_TIMING`, `SET_CLIP_TRACK`, `GET_TIMELINE_LAYOUT`.
   - `engine/`: Core timeline rendering, FFmpeg export pipeline, shader filters.
3. **CI / CD**:
   - `.github/workflows/main.yml` runs Android CI on pushes/PRs and also supports manual dispatch.
   - Debug artifact: `storyline-debug-apk`.
   - Play release artifact: `storyline-play-release-apk` for non-PR builds.

---

## 💬 Instructions for ChatGPT / Antigravity for Next Steps
1. Never assume a phase is complete without the required verification level.
2. Keep the existing Storyline design language intact (`#388BFD` blue accent, dark workstation surfaces).
3. Do not change working C++ video rendering or FFmpeg export logic unless explicitly needed.
4. Keep memory usage low for budget Android devices (Redmi 9A, 2GB RAM).
5. Prefer small, targeted fixes over broad rewrites.
6. For UI changes, test the actual physical device before declaring completion.
7. Update this file after each phase with implementation status and verification status.
8. Keep known issues explicit; do not hide or silently overwrite them.

---

## Current Roadmap

**Next required step: Phase 5 — Real Device Manual QA.**

Phase 6 will only be planned after Phase 5 results are recorded here.
# Storyline AI Sync & Collaboration Bridge

> **Purpose**: This document bridges collaboration between **Antigravity (Local IDE & Build Agent)** and **ChatGPT (Architecture & Roadmap Planner)** via the GitHub repository (`sarojshahu12-max/storyline`).

---

## 📌 Project Overview
- **Repository**: `sarojshahu12-max/storyline`
- **Current Branch**: `main`
- **Application**: Storyline — Professional Mobile Video Editor for Android
- **Target Device Tested**: Redmi 9A (`M2006C3LI`, 2GB RAM, Android 10)
- **Primary Design Identity**: Premium Dark Workspace (`#07090D` / `#0D1117`), Storyline Blue Accent (`#388BFD`), 10dp rounded corners, restrained modern UI.

---

## 🚀 Completed Phases Summary

### Phase 1: Professional Premium UI/UX Design System
- Standardized `DesignSystem.kt`, `colors.xml`, `dimens.xml`, `styles.xml`.
- Professional dark elevation palette (`surface_base`, `surface_card`, `surface_elevated`, `surface_floating`).
- Unified typography hierarchy (Headline, Title, Body, Caption).

### Phase 1.5: Editor Shell Premium Polish
- **Top Bar**: Solid Storyline Blue primary `Export` button (`#388BFD`), 32dp vertical alignment for back/ratio/export.
- **Playback Controls**: Centered 44dp dominant Play/Pause button with secondary 34dp Undo/Redo buttons.
- **Bottom Toolbar**: Destructive red styling for Delete (`#F85149`), primary highlight for Add/Media, compact professional buttons.

### Phase 2: Premium Timeline UX Polish
- **Track Hierarchy**: `T1` (Text), `O1` (Overlay), `V1` (Video), `A1` (Audio) with compact badges.
- **Smart Clip Labels**: Aggressive filename cleanup — displays compact corner pill badges (`Video 01`, `Overlay 01`, `Main Hoon`) leaving video thumbnails 100% visible and unblocked.
- **Audio Waveforms**: Distinct audio track styling (`#5E3318`) with waveform visualization.
- **Ruler**: Clean `00:00`, `00:01`, `00:02` second markers.

### Phase 3 & 3.1: Professional Timeline Interaction & Editing UX
- **Clip Selection & Handles**: Crisp 1.5dp blue outline (`#388BFD`) with touch-friendly white grab handles (`dp(8)` touch padding).
- **Functional Split**: Playhead position clip splitting with preserved keyframes, transforms, volume, speed, filters, and metadata.
- **Drag & Reorder**: Smooth horizontal dragging with boundaries, snap indicators, and negative timestamp prevention.
- **Undo / Redo**: Lightweight `UndoDomain.EDITOR` tracking for move, trim, split, and delete operations.
- **Duration Consistency**: Unified mathematical time calculation (`formatClipDuration`) matching `formatAutomationTime` across project clock, clip labels, ruler, and playhead.
- **Empty State**: Polished `"Add media"` state with direct tap-to-import.
- **Toolbar Label**: Renamed `"Transit"` to `"Transitions"`.

---

## 🛠️ Architecture Reference for ChatGPT
1. **Presentation Layer**:
   - `MainActivity.kt` (~16.5k lines): Central editor activity.
   - `TimelineCanvasView.kt` (~1.9k lines): High-performance single-canvas timeline renderer.
   - `VideoPreviewView.kt`: Custom GL surface host for native GPU frames.
2. **Native Video Engine (C++ / JNI)**:
   - `NativeBridge.kt`: JNI bridge connecting Kotlin to C++ timeline commands (`SPLIT`, `UPDATE_CLIP_TIMING`, `SET_CLIP_TRACK`, `GET_TIMELINE_LAYOUT`, etc.).
   - `engine/`: Core timeline rendering, FFmpeg export pipeline, shader filters.
3. **CI / CD**:
   - `.github/workflows/main.yml`: Automated GitHub Actions build producing `storyline-debug-apk` and `storyline-play-release-apk`.

---

## 💬 Instructions for ChatGPT for Next Phase (Phase 4 / Feature Steps)
When drafting the next prompt for the user:
1. Specify clear, targeted requirements (e.g. Phase 4: Audio Studio / Transitions / Video Effects / Export Pipeline).
2. Keep the existing Storyline design language intact (`#388BFD` blue accent, dark workstation surfaces).
3. Do not change working C++ video rendering or FFmpeg export logic unless explicitly needed.
4. Keep memory usage low for budget Android devices (Redmi 9A, 2GB RAM).

# Storyline Video Editor 🎬
**High-Performance Native Android Video Processing Engine**

Package ID: `com.storyline.videoeditor`

## Tech Stack & Architecture
- **Core Engine:** C++17, Android NDK, CMake
- **Bridge Layer:** Zero-copy JNI architecture via `AHardwareBuffer` / Direct ByteBuffer
- **UI & Controls:** Android SDK, Kotlin, Jetpack Compose
- **Performance:** Hardware-accelerated frame rendering, zero memory leaks, minimal battery footprint

## Key Features
- Real-time video trimming, filtering, and frame manipulation directly in native C++
- Low-latency export pipeline bypassing JVM garbage collection overhead
- Modular `.so` native library packaging
- Multi-track timeline with audio waveform visualization
- Text overlay engine with custom typography rendering
- Smooth 60 FPS preview playback on mid-range devices

## Project Structure
```
storyline/
├── android/          # Android app (Kotlin + Jetpack Compose)
├── core/             # C++17 native engine (timeline, clips, text overlay)
├── engine/           # Rendering & smooth playback engine
├── smooth_engine/    # Performance-optimized engine variant
├── lib/              # Shared native libraries
├── backend/          # Cloud backend services
├── api/              # REST API layer
├── admin-panel/      # Web admin dashboard
└── scripts/          # Build & deployment automation
```

## Build
```bash
./build_release.sh
```

## License
All rights reserved © 2026 Mayank Studio

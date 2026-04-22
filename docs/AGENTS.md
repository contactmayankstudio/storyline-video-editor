# Repository Guidelines

## Project Structure & Module Organization
This repository is native-first:

- `backend/`, `core/`, `preview/`, `engine/`, `api/`: native C++ video engine, FFmpeg decode path, GPU preview, and engine-facing APIs.
- `android/`: Kotlin app shell, Android resources, JNI bridge, and device integration for the Storyline editor.
- Root-level docs such as `NATIVE_BUILD_GUIDE.md`, `PLAYBACK_*`, and `TIMELINE_*` document prior implementation decisions.

Use the root project for engine work and `android/` for app/editor work.

## Build, Test, and Development Commands
- Native engine configure/build:
  ```bash
  cd /home/am/video_engine_core
  cmake -S . -B build_check && cmake --build build_check
  ```
  Use this for non-Android validation only.
- Android app debug build:
  ```bash
  cd /home/am/storyline/android
  ./gradlew :app:assembleDebug
  ```
- Install on connected device:
  ```bash
  ./gradlew :app:installDebug
  ```

## Coding Style & Naming Conventions
- C++: 4-space indentation, `m_memberName` for members, `CamelCase` for classes, `snake_case` only where already established by external APIs.
- Kotlin/Android: use idiomatic Kotlin, keep UI/controller responsibilities separated, and prefer resource-driven strings/colors/layouts.
- Prefer small, focused patches. Keep JNI, engine, and Android UI changes modular.

## Testing Guidelines
- For native changes, always run `:app:assembleDebug` because Android JNI/CMake is the real integration path.
- Validate playback/timeline changes on-device when touching preview, seek, or render code.

## Commit & Pull Request Guidelines
- This repository currently has no Git commit history on `main`, so use clear imperative commit messages, for example: `Fix preview playFrom clock drift`.
- PRs should include:
  - scope summary
  - affected paths (for example `preview/preview_controller.cpp`)
  - build/test commands run
  - screenshots or screen recordings for UI/timeline changes

## Security & Configuration Tips
- Do not commit local build outputs, APKs, device IDs, or secrets.
- Keep Android-specific assumptions inside the bridge/native layers; avoid hardcoding device-specific behavior in the editor UI.

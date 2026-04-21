# Repository Guidelines

## Project Structure & Module Organization
This repository has two active layers:

- `backend/`, `core/`, `preview/`, `engine/`, `api/`: native C++ video engine, FFmpeg decode path, GPU preview, and engine-facing APIs.
- `my_editor/`: Flutter mobile editor app. Main UI code lives in `my_editor/lib/`, Android bridge code in `my_editor/android/`, and tests in `my_editor/test/`.
- Root-level docs such as `NATIVE_BUILD_GUIDE.md`, `PLAYBACK_*`, and `TIMELINE_*` document prior implementation decisions.

Use the root project for native engine work and `my_editor/` for Flutter/editor work.

## Build, Test, and Development Commands
- Native engine configure/build:
  ```bash
  cd /home/am/video_engine_core
  cmake -S . -B build_check && cmake --build build_check
  ```
  Use this for non-Android validation only.
- Android app debug build:
  ```bash
  cd /home/am/video_engine_core/my_editor/android
  ./gradlew :app:assembleDebug
  ```
- Install on connected device:
  ```bash
  ./gradlew :app:installDebug
  ```
- Flutter app run/analyze/test:
  ```bash
  cd /home/am/video_engine_core/my_editor
  flutter run
  flutter analyze
  flutter test
  ```

## Coding Style & Naming Conventions
- C++: 4-space indentation, `m_memberName` for members, `CamelCase` for classes, `snake_case` only where already established by external APIs.
- Dart/Flutter: follow `flutter_lints`; use `UpperCamelCase` for types, `lowerCamelCase` for methods/fields, and keep widgets/controllers split by responsibility.
- Prefer small, focused patches. Keep JNI, engine, and Flutter changes modular.

## Testing Guidelines
- Flutter tests use `flutter_test`; place new tests under `my_editor/test/` and name them `*_test.dart`.
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
- Keep Android-specific assumptions inside the bridge/native layers; avoid hardcoding device-specific behavior in Flutter UI.

# Repository Guidelines

## Project Structure & Module Organization
- `android/` contains the Android app, Gradle build, resources, and Kotlin sources under `android/app/src/main/kotlin/com/video/engine`.
- `engine/` contains native timeline/export command handling and core video engine code.
- `admin-panel/`, `apk-host/`, and `api/` hold the web admin UI, APK hosting site, and serverless endpoints.
- `aws/`, `functions/`, `automation/`, `mcp/`, and `scripts/` contain cloud control-plane code, Firebase functions, automation helpers, MCP tooling, and operational scripts.
- `docs/` stores implementation notes and setup guides. Root-level sample media files are for local testing only; do not rename or delete them casually.

## Build, Test, and Development Commands
- `cd android && ./gradlew :app:assembleDebug` builds the Android debug APK.
- `cd android && ./gradlew :app:installDebug` installs the current debug build on a connected device.
- `cd android && ./gradlew :app:compileDebugKotlin` is the fastest Kotlin compile sanity check.
- `cd android && ./gradlew testDebugUnitTest` runs local JVM unit tests.
- `cd android && ./gradlew connectedDebugAndroidTest` runs device/instrumentation tests.
- `./build_release.sh` runs the repo’s release packaging flow.
- `./verify_text_overlay_integration.sh` is a useful smoke-check script for overlay-related changes.

## Coding Style & Naming Conventions
- Use 4-space indentation in Kotlin, C++, shell, and JS.
- Kotlin classes and views use `PascalCase`; methods and properties use `camelCase`; constants use `UPPER_SNAKE_CASE`.
- Match existing naming in `MainActivity.kt`, controllers, and engine commands before introducing new patterns.
- Keep comments short and explain only non-obvious logic. Prefer small, targeted changes over broad rewrites.

## Testing Guidelines
- Android unit tests use JUnit 4; instrumentation uses AndroidX JUnit and Espresso.
- Name tests after the behavior under test, for example `importFromPath_clearsPendingState`.
- For editor or playback changes, verify on-device behavior and capture a screenshot or log artifact when possible.
- Re-run the smallest relevant check first, then a full APK build before pushing.

## Commit & Pull Request Guidelines
- Follow the existing history: short imperative summaries such as `Fix blank reset duplication` or optional conventional prefixes like `fix:` / `feat:`.
- Keep commits focused and avoid mixing Android, web, and cloud changes unless they ship one feature.
- PRs should include: what changed, risk/regression notes, linked issue/report, and screenshots for UI changes.

## Security & Configuration Tips
- Never commit tokens, service-account JSON, or local `.env` files. Use GitHub, Vercel, Firebase, and AWS secret stores.
- When working with hosted update or admin flows, verify environment-backed URLs rather than hardcoding production secrets.

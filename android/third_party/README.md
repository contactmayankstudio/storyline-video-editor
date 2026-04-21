


Run the helper scripts in `android/scripts/` to download/build dependencies:

- `build-ffmpeg-android.sh` — cross-compile FFmpeg for Android ABIs (armeabi-v7a, arm64-v8a).
- `fetch-glm.sh` — download GLM headers (header-only).

Built FFmpeg will be placed in `android/third_party/ffmpeg/<ABI>` with `include/` and `lib/` subfolders.
GLM will be placed in `android/third_party/glm`.

After building/downloading, run the Android Gradle build from the `android/` directory:

```bash
./gradlew :app:installDebug
```

If you prefer prebuilt FFmpeg for Android, place headers into `android/third_party/ffmpeg/include` and libs into `android/third_party/ffmpeg/lib/<ABI>/`.

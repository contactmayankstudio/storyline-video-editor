# Storyline Video Engine - Developer Quickstart Guide

Welcome to the **Storyline Video Editor & Native C++ Engine** source package!
This guide walks you through importing the project, building it, rebranding, and creating release packages.

---

## 📋 1. Prerequisites & Environment Setup

Before opening the project, ensure your development machine has the following tools installed:

- **Android Studio:** Hedgehog (2023.1.1) or newer (Koala / Ladybug recommended)
- **JDK:** Java 17 (recommended: Android Studio bundled OpenJDK 17)
- **Android SDK:**
  - Build Tools: `36.0.0` or `34.0.0`
  - Compile SDK: `36` (Android 14/15/16 ready)
  - Minimum SDK: `24` (Android 7.0+)
- **Android NDK:** Version `26.3.11579264` (installed via Android Studio SDK Manager → SDK Tools → NDK (Side by side))
- **CMake:** Version `3.22.1` or higher

---

## 🛠️ 2. Opening & Building in Android Studio

1. Open **Android Studio**.
2. Click **Open** and select the `android/` directory inside this repository (`storyline/android`).
3. Allow Gradle to sync dependencies. Gradle will download the required Jetpack components.
4. Select the build variant:
   - On the left sidebar, open the **Build Variants** tab.
   - Choose `playDebug` for local development & testing.
   - Choose `playRelease` for production builds.
5. Connect your Android device via USB (with USB Debugging enabled) or start an emulator with OpenGL ES 3.0 support.
6. Click **Run (▶)** to compile and launch the app.

---

## 🏷️ 3. How to Rebrand the App

### A. Change the Application ID (Package Name)
1. Open `android/app/build.gradle`.
2. Locate `defaultConfig`:
   ```groovy
   defaultConfig {
       applicationId = "com.yourbrand.videoeditor" // Change this to your unique package ID
       minSdk = 24
       targetSdk = 36
       versionCode = 1
       versionName = "1.0.0"
   }
   ```
3. Sync Gradle.

### B. Change App Name & Display Labels
1. Open `android/app/src/main/res/values/strings.xml`.
2. Change the `app_name` string:
   ```xml
   <string name="app_name">Your Brand Name</string>
   ```

### C. Change App Icon
1. In Android Studio, right-click `android/app/src/main/res`.
2. Select **New → Image Asset**.
3. Choose your foreground image and background color for the Adaptive Icon.
4. Click **Finish**.

### D. Configure Monetization (AdMob)
Open `android/app/build.gradle` and update your AdMob App ID and Ad Unit IDs in the `resValue` configuration:
```groovy
resValue "string", "admob_app_id", "ca-app-pub-XXXXXXXXXXXXXXXX~XXXXXXXXXX"
resValue "string", "admob_banner_unit_id", "ca-app-pub-XXXXXXXXXXXXXXXX/XXXXXXXXXX"
resValue "string", "admob_rewarded_hd_unlock_unit_id", "ca-app-pub-XXXXXXXXXXXXXXXX/XXXXXXXXXX"
resValue "string", "admob_interstitial_post_export_unit_id", "ca-app-pub-XXXXXXXXXXXXXXXX/XXXXXXXXXX"
```

---

## 🏗️ 4. Architecture Overview

- **Native C++ Engine (`preview/`, `engine/`, `backend/`):**
  - High-performance real-time video decoder powered by multi-threaded FFmpeg 6.0 with ARM NEON SIMD routines.
  - Zero-drift audio master clock synchronizer.
  - Predictive frame prefetching and adaptive resolution scaling for fluid 30fps/60fps 4K playback.
- **JNI Bridge (`android/jni/`):**
  - Thread-safe JNI bindings (`native_preview.cpp`) connecting Android UI callbacks to the C++ core.
- **UI & Timeline (`android/app/src/main/kotlin/com/video/engine/`):**
  - Custom canvas timeline view (`TimelineCanvasView.kt`) with multi-track rendering (Video, Overlay PIP, Audio/BGM).
  - High-speed keyframe thumbnail extractor with persistent disk caching.

---

## 📦 5. Building Release AAB (Google Play Store)

To build the signed `.aab` for Google Play upload:

1. Create or place your release keystore in `~/.keystores/video_engine.keystore` (or configure via environment variables).
2. In terminal, navigate to `android/`:
   ```bash
   cd android
   ./gradlew :app:bundlePlayRelease
   ```
3. The generated bundle will be located at:
   `android/app/build/outputs/bundle/playRelease/app-play-release.aab`

---

## 📄 6. License & Support

This software is licensed under the **Commercial & Personal Developer License** (see `COMMERCIAL_LICENSE.md`).
For inquiries or support:
- Email: contactmayankstudio@gmail.com

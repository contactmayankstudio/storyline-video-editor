# Android Native Library Build Guide

**Problem:** `java.lang.UnsatisfiedLinkError: couldn't find "libvideo_engine.so"`

**Solution:** Complete native library build and APK packaging pipeline

---

## File Structure Overview

```
android/
├── app/
│   ├── src/main/
│   │   ├── cpp/
│   │   │   └── CMakeLists.txt          ← Main CMake build config
│   │   ├── jniLibs/                    ← Fallback for prebuilt .so files
│   │   │   ├── armeabi-v7a/
│   │   │   └── arm64-v8a/
│   │   └── kotlin/com/video/engine/
│   │       └── VideoPreviewView.kt     ← JNI loader (System.loadLibrary)
│   └── build.gradle                    ← NDK + CMake config
├── jni/                                ← JNI wrapper implementations
├── build.gradle                        ← Root project config
└── settings.gradle
```

---

## STEP 1: CMakeLists.txt Configuration

**File:** `android/app/src/main/cpp/CMakeLists.txt`

✅ **What it does:**
- Compiles C++ sources from `engine/`, `core/`, `backend/ffmpeg/`, `backend/gpu/`, `preview/`, `android/jni/`
- Creates a shared library with **exact name**: `libvideo_engine.so`
- Links required Android native libraries: `log`, `android`, `EGL`, `GLESv3`, `OpenSLES`
- Outputs: 
  - `build/intermediates/cmake/debug/obj/armeabi-v7a/libvideo_engine.so`
  - `build/intermediates/cmake/debug/obj/arm64-v8a/libvideo_engine.so`

✅ **Key settings:**
```cmake
cmake_minimum_required(VERSION 3.10)
set(CMAKE_CXX_STANDARD 17)
project(video_engine)

# Includes engine/, core/, backend/ffmpeg/, backend/gpu/, preview/, android/jni/
# Finds all *.cpp and *.c files recursively

add_library(video_engine SHARED ${ENGINE_SRCS})

target_link_libraries(video_engine
    PUBLIC
    log
    android
    EGL
    GLESv3
    OpenSLES
)
```

---

## STEP 2: build.gradle NDK Configuration

**File:** `android/app/build.gradle`

✅ **What it does:**
- Configures Android Gradle to invoke CMake during build
- Restricts native builds to `armeabi-v7a` (32-bit ARM) and `arm64-v8a` (64-bit ARM)
- Sets C++17 compiler flags
- Packages compiled `.so` files into APK automatically

✅ **Key sections:**

```gradle
android {
    defaultConfig {
        // ... app config ...
        
        externalNativeBuild {
            cmake {
                cppFlags "-std=c++17"
            }
        }

        ndk {
            abiFilters "armeabi-v7a", "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path "src/main/cpp/CMakeLists.txt"
            version "3.22.1"
        }
    }

    sourceSets {
        main {
            jniLibs.srcDirs = ['src/main/jniLibs']  // Fallback for prebuilt .so
        }
    }
}

// Verification task
task verifyNativeLibs {
    doLast {
        // Checks .so files in CMake outputs
        // Checks APK contents
    }
}
```

---

## STEP 3: JNI Loader (Kotlin)

**File:** `android/app/src/main/kotlin/com/video/engine/VideoPreviewView.kt`

✅ **What it does:**
- Loads `libvideo_engine.so` when the class initializes
- Wraps load in try/catch to handle missing library gracefully
- Provides detailed error logging if load fails

✅ **Key code:**

```kotlin
companion object {
    private const val TAG = "VideoPreviewView"
    private var nativeLibraryLoaded = false

    init {
        try {
            Log.i(TAG, "[NATIVE LOADER] Attempting to load: libvideo_engine.so")
            System.loadLibrary("video_engine")
            nativeLibraryLoaded = true
            Log.i(TAG, "[NATIVE LOADER] ✓ SUCCESS - libvideo_engine.so loaded")
        } catch (e: UnsatisfiedLinkError) {
            nativeLibraryLoaded = false
            Log.e(TAG, """
                [NATIVE LOADER] ✗ FATAL ERROR - Native library failed to load
                Library: libvideo_engine.so
                Message: ${e.message}
                ...
                TROUBLESHOOTING:
                1. Verify CMakeLists.txt exists...
                2. Run: ./gradlew clean :app:assembleDebug
                3. Check build output: ./gradlew :app:verifyNativeLibs
                ...
            """.trimMargin())
            e.printStackTrace()
        }
    }

    fun isNativeLibraryLoaded(): Boolean = nativeLibraryLoaded
}
```

---

## STEP 4: Build Process

### 4.1 Clean and Build

```bash
cd android

# Full clean build
./gradlew clean :app:assembleDebug

# Or with verbose output
./gradlew clean :app:assembleDebug --info
```

**Expected output:**
```
> Task :app:externalNativeBuildDebug
Building with CMake version 3.22.1

[video_engine] CMake Config:
  REPO_ROOT: /path/to/video_engine_core
  ENGINE_DIR: /path/to/video_engine_core/engine
  CORE_DIR: /path/to/video_engine_core/core
  FFMPEG_DIR: /path/to/video_engine_core/backend/ffmpeg
  GPU_DIR: /path/to/video_engine_core/backend/gpu
  ANDROID_JNI_DIR: /path/to/video_engine_core/android/jni
  PREVIEW_DIR: /path/to/video_engine_core/preview

[video_engine] Found 42 source files
[video_engine] Building shared library: libvideo_engine.so
[video_engine] Linked libraries: log android EGL GLESv3 OpenSLES
[video_engine] Build configuration complete. Output: libvideo_engine.so

> Task :app:stripDebugSymbols
...

BUILD SUCCESSFUL in Xs
```

### 4.2 Verify Native Libraries

```bash
./gradlew :app:verifyNativeLibs
```

**Expected output:**
```
======================================================================
CHECKING NATIVE LIBRARIES INSIDE APK
======================================================================

📦 BUILD INTERMEDIATES (CMake obj outputs):
  [✓ FOUND] armeabi-v7a/libvideo_engine.so
  [✓ FOUND] arm64-v8a/libvideo_engine.so

📱 APK CONTENTS:
  Debug APK: /path/to/app/build/outputs/apk/debug/app-debug.apk
    ✓ Found in APK: lib/armeabi-v7a/libvideo_engine.so
    ✓ Found in APK: lib/arm64-v8a/libvideo_engine.so

======================================================================
Verification complete. Library should be packaged in APK.
======================================================================
```

---

## STEP 5: Verify APK Contents

### 5.1 List all native libraries in APK:

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "\.so$"
```

**Expected output:**
```
  1234567  01-05-2026 12:34   lib/armeabi-v7a/libvideo_engine.so
  5678901  01-05-2026 12:34   lib/arm64-v8a/libvideo_engine.so
```

### 5.2 Extract and inspect a .so file:

```bash
unzip -p app/build/outputs/apk/debug/app-debug.apk lib/arm64-v8a/libvideo_engine.so | file -

# Or inspect with readelf (if available):
unzip -p app/build/outputs/apk/debug/app-debug.apk lib/arm64-v8a/libvideo_engine.so | readelf -h
```

---

## STEP 6: Check CMake Build Outputs

```bash
ls -lah app/build/intermediates/cmake/debug/obj/armeabi-v7a/libvideo_engine.so
ls -lah app/build/intermediates/cmake/debug/obj/arm64-v8a/libvideo_engine.so
```

**Expected:** Both files exist and are > 1 MB (depending on engine size)

---

## STEP 7: Fallback - Manual .so Packaging

If CMake fails to compile sources, you can place **prebuilt** `.so` files manually:

```bash
mkdir -p android/app/src/main/jniLibs/armeabi-v7a
mkdir -p android/app/src/main/jniLibs/arm64-v8a

# Copy prebuilt .so files
cp libvideo_engine_armv7a.so android/app/src/main/jniLibs/armeabi-v7a/libvideo_engine.so
cp libvideo_engine_arm64.so  android/app/src/main/jniLibs/arm64-v8a/libvideo_engine.so

# Rebuild APK
./gradlew :app:assembleDebug
```

Gradle will automatically package `.so` files from `jniLibs/` into the APK.

---

## Troubleshooting

### Issue: `UnsatisfiedLinkError: couldn't find "libvideo_engine.so"`

**Root causes:**

1. **CMakeLists.txt missing or invalid**
   - Check: `android/app/src/main/cpp/CMakeLists.txt` exists
   - Run: `./gradlew :app:externalNativeBuildDebug --info`

2. **CMake compilation failed**
   - Check build output for errors
   - Verify all source files in `engine/`, `core/`, `backend/` exist
   - Try explicit source listing in CMakeLists: `add_library(video_engine SHARED file1.cpp file2.cpp ...)`

3. **build.gradle NDK config missing**
   - Verify `android/app/build.gradle` has:
     ```gradle
     externalNativeBuild {
         cmake {
             path "src/main/cpp/CMakeLists.txt"
         }
     }
     ndk {
         abiFilters "armeabi-v7a", "arm64-v8a"
     }
     ```

4. **APK not including .so file**
   - Run: `./gradlew :app:verifyNativeLibs`
   - Check: `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libvideo_engine.so`
   - If missing, try: `./gradlew clean :app:assembleDebug`

5. **Device ABI mismatch**
   - Device must support `armeabi-v7a` or `arm64-v8a`
   - Check device ABI: Logcat → `ro.product.cpu.abi`
   - Or: `adb shell getprop ro.product.cpu.abi`

6. **Library name mismatch**
   - System.loadLibrary("video_engine") → loads libvideo_engine.so
   - Make sure CMakeLists.txt creates: `add_library(video_engine SHARED ...)`
   - ✓ Not: `add_library(videoengine ...)`
   - ✓ Not: `add_library(libvideo_engine ...)`

### Issue: `CMake Error: No source files found`

- Verify directory structure and file extensions (`.cpp`, `.c`)
- Check paths in CMakeLists: `${REPO_ROOT}` should point to repo root
- Try listing files manually:
  ```bash
  find engine core backend/ffmpeg backend/gpu preview android/jni -name "*.cpp" -o -name "*.c"
  ```

---

## Build Verification Checklist

- [ ] CMakeLists.txt exists at `android/app/src/main/cpp/CMakeLists.txt`
- [ ] CMakeLists.txt includes `engine/`, `core/`, `backend/ffmpeg/`, `backend/gpu/`, `preview/`, `android/jni/`
- [ ] build.gradle has `externalNativeBuild.cmake.path` and `ndk.abiFilters`
- [ ] VideoPreviewView.kt has `System.loadLibrary("video_engine")` in companion init
- [ ] CMake build succeeds: `./gradlew :app:assembleDebug` completes without errors
- [ ] CMake outputs exist: `app/build/intermediates/cmake/debug/obj/armeabi-v7a/libvideo_engine.so`
- [ ] APK contains .so: `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libvideo_engine.so`
- [ ] App launches without `UnsatisfiedLinkError` crash
- [ ] Logcat shows: `[NATIVE LOADER] ✓ SUCCESS - libvideo_engine.so loaded`

---

## Quick Reference Commands

```bash
# Full rebuild
cd android && ./gradlew clean :app:assembleDebug

# Verify native libs
./gradlew :app:verifyNativeLibs

# Check CMake logs (verbose)
./gradlew :app:assembleDebug --info 2>&1 | grep -i cmake

# List .so in APK
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libvideo_engine.so

# Check CMake outputs
ls -lh app/build/intermediates/cmake/debug/obj/*/libvideo_engine.so

# Install and run app
./gradlew :app:installDebug && adb shell am start com.video.engine/.MainActivity

# Check native library load in logcat
adb logcat | grep "NATIVE LOADER"
```

---

## Success Indicators

✅ When everything works:

1. **Build completes successfully:**
   ```
   BUILD SUCCESSFUL in 45s
   ```

2. **Logcat shows successful load:**
   ```
   [NATIVE LOADER] ✓ SUCCESS - libvideo_engine.so loaded
   ```

3. **APK contains native libraries:**
   ```
   unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libvideo_engine.so
   lib/armeabi-v7a/libvideo_engine.so
   lib/arm64-v8a/libvideo_engine.so
   ```

4. **VideoPreviewView initializes without crash**

5. **GPU rendering works on supported devices**

---

## Support

If issues persist:

1. **Check all build files** are in place and properly formatted
2. **Run full clean build**: `./gradlew clean :app:assembleDebug`
3. **Verify CMake configuration** outputs diagnostic messages
4. **Use verifyNativeLibs task** to inspect build outputs and APK
5. **Check device ABI compatibility** with supported filters
6. **Enable verbose logging** with `--info` flag

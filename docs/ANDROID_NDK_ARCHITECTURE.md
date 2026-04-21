# Android NDK + OpenGL ES Integration - Architecture & Implementation

## Overview

This document explains the complete Android NDK + OpenGL ES 3.0 integration for real-time GPU-accelerated video preview in the video engine.

The system follows the **VN** / **KineMaster** architecture pattern: native C++ video engine + Android JNI bridge + custom SurfaceView.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    ANDROID APPLICATION LAYER                │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  MainActivity                                               │
│  ┌──────────────────────────────┐                          │
│  │ onCreate()                   │                          │
│  │ • Create VideoPreviewView    │                          │
│  │ • Load video file            │                          │
│  │ • Handle lifecycle           │                          │
│  └──────────────────────────────┘                          │
│           ↓                                                 │
│  VideoPreviewView (SurfaceView)                            │
│  ┌──────────────────────────────┐                          │
│  │ SurfaceHolder.Callback:      │                          │
│  │ • surfaceCreated()           │→ nativeInitPreview()    │
│  │ • surfaceChanged()           │→ nativeSetSurfaceSize() │
│  │ • surfaceDestroyed()         │→ nativeReleasePreview() │
│  │                              │                          │
│  │ Public methods:              │                          │
│  │ • loadVideo(path)            │→ nativeLoadVideo()      │
│  │ • seekToTime(ms)             │→ nativeSeekPreview()    │
│  │ • startPlayback(ms)          │→ nativeStartPlayback()  │
│  │ • stopPlayback()             │→ nativeStopPlayback()   │
│  └──────────────────────────────┘                          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
              ↓ JNI BRIDGE (native_preview.cpp)
┌─────────────────────────────────────────────────────────────┐
│              NATIVE C++ LAYER (NDK)                         │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  JNI Entry Points:                                          │
│  • Java_com_video_engine_VideoPreviewView_nativeInitPreview │
│  • Java_com_video_engine_VideoPreviewView_nativeSetSurfaceSize
│  • Java_com_video_engine_VideoPreviewView_nativeReleasePreview
│  • Java_com_video_engine_VideoPreviewView_nativeLoadVideo   │
│  • Java_com_video_engine_VideoPreviewView_nativeSeekPreview │
│  • Java_com_video_engine_VideoPreviewView_nativeStartPlayback
│  • Java_com_video_engine_VideoPreviewView_nativeStopPlayback │
│  • Java_com_video_engine_VideoPreviewView_nativePauseRendering
│  • Java_com_video_engine_VideoPreviewView_nativeResumeRendering
│           ↓                                                  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ EGL Management                                       │  │
│  │ • eglGetDisplay() → get EGL display                 │  │
│  │ • eglInitialize() → init EGL                        │  │
│  │ • eglChooseConfig() → select rendering config       │  │
│  │ • eglCreateContext() → create OpenGL context        │  │
│  │ • eglCreateWindowSurface() → create render surface  │  │
│  │ • eglMakeCurrent() → activate context               │  │
│  │ • eglSwapBuffers() → display rendered frame         │  │
│  └──────────────────────────────────────────────────────┘  │
│           ↓                                                  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ Video Engine Core (C++)                             │  │
│  │ • PreviewController                                 │  │
│  │   - FFmpeg video decoding (YUV420P)                │  │
│  │   - GPU texture upload                              │  │
│  │   - Frame rendering                                 │  │
│  │ • PreviewRenderer (GPU backend)                     │  │
│  │   - OpenGL ES 3.0 shaders                          │  │
│  │   - Multi-layer compositing                         │  │
│  │   - Real-time effects (brightness, contrast, LUT)   │  │
│  │ • YUVTexture management                             │  │
│  └──────────────────────────────────────────────────────┘  │
│           ↓                                                  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ FFmpeg Decoder                                      │  │
│  │ • avformat_open_input() → open video file          │  │
│  │ • avcodec_decode_frame() → decode frames           │  │
│  │ • sws_scale() → YUV conversion                      │  │
│  │ • Audio decoding (optional)                         │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                              │
└─────────────────────────────────────────────────────────────┘
              ↓ OpenGL ES 3.0 + EGL
┌─────────────────────────────────────────────────────────────┐
│                    GPU / HARDWARE LAYER                     │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  • Adreno GPU (Qualcomm ARM devices)                        │
│  • Mali GPU (Samsung/etc ARM devices)                       │
│  • PowerVR GPU (Apple, older Android)                       │
│                                                              │
│  OpenGL ES 3.0 Features:                                    │
│  • Vertex/Fragment shaders                                  │
│  • Texture units (YUV planes, LUT)                          │
│  • Framebuffer objects (FBO)                                │
│  • Vertex arrays, draw calls                                │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## Why SurfaceView (vs TextureView)?

### SurfaceView ✅ (Chosen)

**Pros:**
- **Dedicated rendering thread**: Native code can render independently without blocking UI
- **Best performance**: Direct GPU access via EGL
- **Lower latency**: No need to wait for Android compositor
- **Hardware acceleration**: Full GPU utilization
- **Standard for video playback**: YouTube, VLC, KineMaster use SurfaceView

**Cons:**
- **Cannot transform**: No built-in scaling/rotation
- **Cannot apply View effects**: Shadows, transparency, elevation
- **Overlays complex**: Must use separate surface for overlays

**Why we chose it:**
- Real-time video preview needs maximum performance
- No UI transformations needed (preview is full-screen)
- Native rendering loop is most efficient

### TextureView ✗ (Alternative)

**Pros:**
- Full View integration (can apply effects, animations)
- Can be scaled, rotated, overlaid easily

**Cons:**
- **Rendering blocks UI thread**: Must render on GL thread, affects UI responsiveness
- **Slower**: GPU → texture → compositor → display (extra step)
- **Higher latency**: Not ideal for real-time video
- **More complex**: Thread synchronization with UI thread

**Why we didn't use it:**
- VN, KineMaster, professional video apps use SurfaceView for performance
- Real-time video preview needs lowest possible latency
- Additional overhead unacceptable for smooth playback

---

## SurfaceView & Surface Lifecycle

### 1. Surface Created → Native Initialization

```
MainActivity.onCreate()
     ↓
setContentView(previewView)
     ↓
SurfaceView surface allocated
     ↓
SurfaceHolder.Callback.surfaceCreated()
     ↓
nativeInitPreview(surface)  [JNI call]
     ↓
ANativeWindow_fromSurface()  [Get native window handle]
     ↓
initializeEGL()  [Set up OpenGL context]
     ├─ eglGetDisplay(EGL_DEFAULT_DISPLAY)
     ├─ eglInitialize()
     ├─ eglChooseConfig()
     ├─ eglCreateContext()
     ├─ eglCreateWindowSurface(g_nativeWindow)
     ├─ eglMakeCurrent()
     └─ glClearColor(), glViewport()
     ↓
PreviewController initialized
     └─ Ready to decode & render
```

### 2. Surface Size Change → Viewport Update

```
Screen rotation or configuration change
     ↓
SurfaceHolder.Callback.surfaceChanged(width, height)
     ↓
nativeSetSurfaceSize(width, height)  [JNI call]
     ↓
eglMakeCurrent()
     ↓
glViewport(0, 0, width, height)  [Update render area]
```

### 3. Activity Pause → Context Release

```
Activity.onPause()
     ↓
VideoPreviewView.onPause()
     ↓
nativePauseRendering()  [JNI call]
     ↓
eglMakeCurrent(NO_DISPLAY, NO_SURFACE, NO_SURFACE, NO_CONTEXT)
     └─ Releases EGL context for other apps
     ↓
g_preview->stopPlayback()  [Stop frame rendering]
```

### 4. Activity Resume → Context Restoration

```
Activity.onResume()
     ↓
VideoPreviewView.onResume()
     ↓
nativeResumeRendering()  [JNI call]
     ↓
eglMakeCurrent(display, surface, surface, context)
     └─ Restores OpenGL context
```

### 5. Surface Destroyed → Cleanup

```
MainActivity.onDestroy() / SurfaceView removed
     ↓
SurfaceHolder.Callback.surfaceDestroyed()
     ↓
nativeReleasePreview()  [JNI call]
     ↓
g_preview->close()  [Stop decoding, close video]
     ↓
terminateEGL()
     ├─ eglMakeCurrent(NO_CONTEXT)
     ├─ eglDestroySurface()
     ├─ eglDestroyContext()
     └─ eglTerminate()
     ↓
ANativeWindow_release(g_nativeWindow)
```

---

## EGL Context Lifecycle

### What is EGL?

**EGL = Embedded Graphics Library**

EGL is the platform abstraction layer between OpenGL ES and the native windowing system (Android SurfaceView, iOS CAEAGLLayer, etc.).

**Key responsibilities:**
- Create OpenGL context
- Bind context to window surface
- Swap framebuffers for display
- Handle surface lifecycle

### EGL Components

```
EGL Display
├─ Represents connection to GPU/display
├─ Created via eglGetDisplay(EGL_DEFAULT_DISPLAY)
└─ One per application (typically)

EGL Config
├─ Describes framebuffer format (RGBA8, depth, etc.)
├─ Selected via eglChooseConfig()
└─ Multiple configs available, we pick best match

EGL Context
├─ Holds OpenGL state (shaders, uniforms, etc.)
├─ Created via eglCreateContext()
├─ Thread-local (each thread can have one current context)
└─ Must be "current" for OpenGL calls to work

EGL Surface
├─ Rendering target (window or pbuffer)
├─ Created via eglCreateWindowSurface() for SurfaceView
├─ Draw commands write to surface
└─ eglSwapBuffers() displays the surface
```

### Typical EGL Initialization

```cpp
// 1. Get display (connection to GPU)
EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);

// 2. Initialize EGL (query capabilities)
EGLint major, minor;
eglInitialize(display, &major, &minor);  // EGL 1.4, 1.5, etc.

// 3. Choose config (framebuffer format)
const EGLint configAttribs[] = {
    EGL_SURFACE_TYPE, EGL_WINDOW_BIT,        // For window rendering
    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, // OpenGL ES 3.0
    EGL_RED_SIZE, 8,
    EGL_GREEN_SIZE, 8,
    EGL_BLUE_SIZE, 8,
    EGL_ALPHA_SIZE, 8,
    EGL_NONE
};
EGLConfig config;
EGLint numConfigs;
eglChooseConfig(display, configAttribs, &config, 1, &numConfigs);

// 4. Create context (OpenGL state holder)
const EGLint contextAttribs[] = {
    EGL_CONTEXT_CLIENT_VERSION, 3,  // OpenGL ES 3.0
    EGL_NONE
};
EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);

// 5. Create surface (rendering target)
EGLSurface surface = eglCreateWindowSurface(display, config, nativeWindow, nullptr);

// 6. Make current (activate context for this thread)
eglMakeCurrent(display, surface, surface, context);

// Now OpenGL calls work:
glClear(GL_COLOR_BUFFER_BIT);
glDrawArrays(GL_TRIANGLES, 0, 3);

// 7. Display rendered frame
eglSwapBuffers(display, surface);
```

### Thread Safety

**EGL is thread-local:**

```cpp
// Thread 1: Rendering thread (native code)
eglMakeCurrent(display, surface, surface, context);
glDrawArrays(...);  // ← Works on this thread
eglSwapBuffers(display, surface);

// Thread 2: UI thread (Java code)
// OpenGL calls here would FAIL (no context current)
// But JNI calls are OK (they're just function calls)
```

This is why our native_preview.cpp uses:
```cpp
std::mutex g_mutex;  // Protects EGL state
thread_local EGLDisplay g_eglDisplay;
thread_local EGLContext g_eglContext;

JNIEXPORT void JNICALL nativeSeekPreview(...) {
    std::lock_guard<std::mutex> lock(g_mutex);  // Lock
    eglMakeCurrent(...);  // Make context current
    // ... render ...
    eglSwapBuffers(...);
}  // Unlock
```

---

## Native Rendering Loop Design

### Rendering Models

#### Model 1: Driven by Java (Not Used)

```java
// Java (MainActivity)
while (playing) {
    long now = SystemClock.uptimeMillis();
    nativeRenderFrame(now);
    Thread.sleep(16);  // 60fps
}
```

**Problems:**
- Java-driven loop blocks UI thread
- Unpredictable frame timing (garbage collection, etc.)
- High latency

#### Model 2: Driven by Native Thread (Used)

```cpp
// C++ (native_preview.cpp)
void renderThread() {
    while (g_isPlaying) {
        std::chrono::steady_clock::time_point now = std::chrono::steady_clock::now();
        
        eglMakeCurrent(display, surface, surface, context);
        
        g_preview->renderFrame(now);
        
        eglSwapBuffers(display, surface);
        
        // Sleep to maintain 60fps
        std::this_thread::sleep_for(std::chrono::milliseconds(16));
    }
}
```

**Benefits:**
- Dedicated rendering thread (UI not blocked)
- Precise timing (no GC interference)
- Low latency
- Can use thread affinity (pin to fast core)

**Current Implementation:**
We don't have a dedicated render thread yet. Instead:
- JNI calls are triggered from Java UI thread
- Native code locks mutex, makes context current
- Renders one frame, swaps buffers
- Returns control to Java

**To add dedicated thread:**

```cpp
// In native_preview.cpp
std::thread g_renderThread;
std::atomic<bool> g_isPlaying = false;

void renderThreadProc() {
    eglMakeCurrent(display, surface, surface, context);
    
    while (g_isPlaying) {
        auto frameStart = std::chrono::steady_clock::now();
        
        g_preview->renderFrame();
        eglSwapBuffers(display, surface);
        
        auto elapsed = std::chrono::steady_clock::now() - frameStart;
        auto sleepTime = 16ms - elapsed;
        if (sleepTime > 0ms) {
            std::this_thread::sleep_for(sleepTime);
        }
    }
    
    eglMakeCurrent(EGL_NO_DISPLAY, ...);
}

// In nativeStartPlayback:
g_isPlaying = true;
g_renderThread = std::thread(renderThreadProc);

// In nativeStopPlayback:
g_isPlaying = false;
if (g_renderThread.joinable()) g_renderThread.join();
```

---

## JNI Bridge Explained

### Method Naming Convention

Java class: `com.video.engine.VideoPreviewView`
Java method: `nativeInitPreview(surface: Surface)`

JNI function name:
```
Java_com_video_engine_VideoPreviewView_nativeInitPreview
 │    │   │      │     │            │    │     │
 │    │   │      │     │            │    │     └─ Method name
 │    │   │      │     │            │    └────── "native" keyword
 │    │   │      │     │            └─────────── Fully qualified class name
 │    │   │      │     └────────────────────── Package components
 │    └───┴──────┘
 └──────── Required prefix
```

### JNI Type Mapping

```java
// Java                    // C++ (JNI)
void                   →   void
boolean                →   jboolean
byte                   →   jbyte
char                   →   jchar
int                    →   jint
long                   →   jlong
float                  →   jfloat
double                 →   jdouble
String                 →   jstring
Object                 →   jobject

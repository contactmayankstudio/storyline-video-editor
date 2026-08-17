# Smooth Engine Pro Plus (Advanced Features)

This directory contains standalone, high-performance C++ components for the Storyline Video Engine. These features are designed to be integrated into the `smooth_engine` or `core` layers to provide professional-grade functionality.

## Included Features

### 1. Cinematic LUT Engine (`CinematicLUTEngine.h/cpp`)
- **Purpose**: Hollywood-grade color grading using 3D Look-Up Tables.
- **Implementation**: GPU-accelerated via OpenGL 3D textures and fragment shaders.
- **Supported Format**: Standard `.cube` files (up to 64x64x64).

### 2. Neural Video Denoising (`NeuralDenoiseProvider.h/cpp`)
- **Purpose**: Remove grain and ISO noise from low-light footage.
- **Implementation**: Advanced Bilateral-Neural filtering that preserves edges while smoothing flat areas.
- **Performance**: Optimized spatial-temporal loops.

### 3. AI Scene Intelligence (`SceneIntelligence.h/cpp`)
- **Purpose**: Smart Highlight detection and object tracking.
- **Implementation**: Frame energy analysis and histogram-based scene change detection.
- **Usage**: Automatically find the "best" moments in a raw clip.

### 4. GPU Particle System (`GPUParticleSystem.h/cpp`)
- **Purpose**: Add atmospheric effects like Rain, Snow, and Fire.
- **Implementation**: Fully GPU-driven using Transform Feedback (ES 3.0), allowing thousands of particles with zero CPU overhead.

### 5. Advanced Frame Interpolation (`FrameInterpolator.h/cpp`)
- **Purpose**: Create "Super Slo-Mo" from standard 30fps/60fps footage.
- **Implementation**: Optical Flow estimation and forward/backward warping to generate artificial intermediate frames.

## Integration Instructions

1. **CMake**: Add these files to your `CMakeLists.txt`.
2. **Bridge**: Create JNI wrappers in `android/jni/` to expose these to the Kotlin UI.
3. **Usage**:
   - For LUT: Load a `.cube` file during clip initialization.
   - For Denoise: Apply in the rendering pipeline before effects.
   - For Particles: Overlay on the final composite quad.

---
**Note**: These files are kept separate to ensure project stability. No existing files have been modified.

cmake_minimum_required(VERSION 3.10)
project(video_engine)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED TRUE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-error=narrowing -Wno-error=int-to-pointer-cast -Wno-error=pointer-to-int-cast -fpermissive") # Disable narrowing and pointer cast errors, and use permissive mode

# Set the path to your prebuilt .so files based on ANDROID_ABI
set(JNI_LIBS_PATH "${CMAKE_SOURCE_DIR}/third_party/ffmpeg/${ANDROID_ABI}/lib")

# Add the prebuilt shared libraries
add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION
    "${JNI_LIBS_PATH}/libavcodec.so")

add_library(avformat SHARED IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION
    "${JNI_LIBS_PATH}/libavformat.so")

add_library(avutil SHARED IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION
    "${JNI_LIBS_PATH}/libavutil.so")

add_library(swscale SHARED IMPORTED)
set_target_properties(swscale PROPERTIES IMPORTED_LOCATION
    "${JNI_LIBS_PATH}/libswscale.so")

# Add your native library
add_library(video_engine SHARED
    "${CMAKE_SOURCE_DIR}/jni/native_preview.cpp"
    "${CMAKE_SOURCE_DIR}/jni/project_jni.cpp"
    "${CMAKE_SOURCE_DIR}/jni/command_jni.cpp"
    "${CMAKE_SOURCE_DIR}/jni/photo_jni.cpp"
    "${CMAKE_SOURCE_DIR}/../engine/photo_ai_effects.cpp"
    "${CMAKE_SOURCE_DIR}/../core/timeline.cpp"
    "${CMAKE_SOURCE_DIR}/../core/clip.cpp"
    "${CMAKE_SOURCE_DIR}/../engine/project.cpp"
    "${CMAKE_SOURCE_DIR}/../engine/engine.cpp"
    "${CMAKE_SOURCE_DIR}/../engine/commands/command_manager.cpp"
    "${CMAKE_SOURCE_DIR}/../backend/export/export_controller.cpp"
    "${CMAKE_SOURCE_DIR}/../backend/ffmpeg/ffmpeg_audio_renderer.cpp"
    "${CMAKE_SOURCE_DIR}/../backend/gpu/preview_renderer.cpp"
    "${CMAKE_SOURCE_DIR}/../backend/gpu/shader_program.cpp"
    "${CMAKE_SOURCE_DIR}/../backend/gpu/shader_loader.cpp"
    "${CMAKE_SOURCE_DIR}/../backend/gpu/quad_mesh.cpp"
    "${CMAKE_SOURCE_DIR}/../backend/gpu/texture.cpp"
    "${CMAKE_SOURCE_DIR}/../backend/gpu/text_renderer.cpp"
    "${CMAKE_SOURCE_DIR}/../backend/gpu/gl_context.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/AdvancedAnimation.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/SmoothRenderLoop.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/FramePrefetcher.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/FrameBudgetController.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/SpeedRamping.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/TransitionEngine.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/TexturePool.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/AdaptiveResolution.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/AISegmentation.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/OpticalFlow.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/ColorSciencePro.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/SmartCache.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/ProxyManager.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/RenderGraphPro.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/AudioEnginePro.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/AAudioEngine.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/DisplaySync.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/TripleBuffer.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/MotionTracker.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/VoiceEnhancer.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/HardwareEncoderPro.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/HapticEngine.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/SuperResolution.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/VulkanRenderer.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/ProfessionalEffects.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/AudioDucking.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/AutoCaptions.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine/ThermalManager.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine_pro_plus/CinematicLUTEngine.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine_pro_plus/NeuralDenoiseProvider.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine_pro_plus/SceneIntelligence.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine_pro_plus/GPUParticleSystem.cpp"
    "${CMAKE_SOURCE_DIR}/../smooth_engine_pro_plus/FrameInterpolator.cpp"
    )

# Include directories
target_include_directories(video_engine PRIVATE
    "${CMAKE_SOURCE_DIR}/jni"
    "${CMAKE_SOURCE_DIR}/.."
    "${CMAKE_SOURCE_DIR}/../engine"
    "${CMAKE_SOURCE_DIR}/../core"
    "${CMAKE_SOURCE_DIR}/../backend"
    "${CMAKE_SOURCE_DIR}/../backend/gpu"
    "${CMAKE_SOURCE_DIR}/third_party/ffmpeg/${ANDROID_ABI}/include"
    "${CMAKE_SOURCE_DIR}/third_party/glm"
    )

# Link the FFmpeg libraries to your native library
target_link_libraries(video_engine
    avcodec
    avformat
    avutil
    swscale
    aaudio
    log              # For __android_log_print
    EGL              # For EGL functions
    GLESv3           # For OpenGL ES 3.0 functions
    android          # For ANativeWindow functions
    nativewindow     # For ANativeWindow_release (required by prebuilt libavutil)
    )

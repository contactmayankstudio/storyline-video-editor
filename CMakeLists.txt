cmake_minimum_required(VERSION 3.10.2)
project(engine)
set(CMAKE_CXX_STANDARD 17)

set(ENGINE_SOURCES
    core/clip.cpp
    core/timeline.cpp
    engine/engine.cpp
    engine/project.cpp
    smooth_engine/AdvancedAnimation.cpp
    smooth_engine/SmoothRenderLoop.cpp
    smooth_engine/FramePrefetcher.cpp
    smooth_engine/FrameBudgetController.cpp
    smooth_engine/SpeedRamping.cpp
    smooth_engine/TransitionEngine.cpp
    smooth_engine/TexturePool.cpp
    smooth_engine/AdaptiveResolution.cpp
    smooth_engine/AISegmentation.cpp
    smooth_engine/OpticalFlow.cpp
    smooth_engine/ColorSciencePro.cpp
    smooth_engine/SmartCache.cpp
    smooth_engine/ProxyManager.cpp
    smooth_engine/RenderGraphPro.cpp
    smooth_engine/AudioEnginePro.cpp
    smooth_engine/AAudioEngine.cpp
    smooth_engine/DisplaySync.cpp
    smooth_engine/TripleBuffer.cpp
    smooth_engine/MotionTracker.cpp
    smooth_engine/VoiceEnhancer.cpp
    smooth_engine/HardwareEncoderPro.cpp
    smooth_engine/HapticEngine.cpp
    smooth_engine/SuperResolution.cpp
    smooth_engine/VulkanRenderer.cpp
    smooth_engine/ProfessionalEffects.cpp
    smooth_engine/AudioDucking.cpp
    smooth_engine/AutoCaptions.cpp
    smooth_engine/ThermalManager.cpp
    smooth_engine_pro_plus/CinematicLUTEngine.cpp
    smooth_engine_pro_plus/NeuralDenoiseProvider.cpp
    smooth_engine_pro_plus/SceneIntelligence.cpp
    smooth_engine_pro_plus/GPUParticleSystem.cpp
    smooth_engine_pro_plus/FrameInterpolator.cpp
    preview/transform_engine.cpp
    preview/preview_controller.cpp
    backend/gpu/texture.cpp
    preview/gpu/gl_texture.cpp
    backend/ffmpeg/video_decoder.cpp
    backend/ffmpeg/frame_converter.cpp
)

# Android-only sources
if(ANDROID)
    list(APPEND ENGINE_SOURCES
        preview/gpu/egl_renderer.cpp
        backend/mediacodec/media_codec_surface_renderer.cpp
    )
endif()

add_library(engine STATIC ${ENGINE_SOURCES})

set(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}")
if(ANDROID)
    set(FFMPEG_HEADERS_ROOT "${REPO_ROOT}/android/third_party/ffmpeg/${ANDROID_ABI}")
    set(FFMPEG_LIB_DIR "${FFMPEG_HEADERS_ROOT}/lib")
else()
    set(FFMPEG_HEADERS_ROOT "")
    set(FFMPEG_LIB_DIR "")
endif()

target_include_directories(engine PUBLIC
    "${REPO_ROOT}"
)

if(EXISTS "${FFMPEG_HEADERS_ROOT}/include/libavutil/rational.h" AND EXISTS "${FFMPEG_LIB_DIR}/libavformat.so")
    target_sources(engine PRIVATE backend/ffmpeg/packet_demuxer.cpp)
    target_include_directories(engine PUBLIC ${FFMPEG_HEADERS_ROOT}/include)
    target_link_directories(engine PUBLIC ${FFMPEG_LIB_DIR})
    target_link_libraries(engine PUBLIC avformat avcodec avutil swscale swresample)
    target_compile_definitions(engine PUBLIC VIDEO_ENGINE_FFMPEG_DEMUX_AVAILABLE=1)
endif()

cmake_minimum_required(VERSION 3.10)
project(smooth_engine_test)
set(CMAKE_CXX_STANDARD 17)

include_directories(..)

add_library(smooth_engine STATIC
    AdvancedAnimation.cpp
    SmoothRenderLoop.cpp
    FramePrefetcher.cpp
    FrameBudgetController.cpp
    SpeedRamping.cpp
    TransitionEngine.cpp
    TexturePool.cpp
    AISegmentation.cpp
    OpticalFlow.cpp
    ColorSciencePro.cpp
    SmartCache.cpp
    ProxyManager.cpp
    RenderGraphPro.cpp
    AudioEnginePro.cpp
    MotionTracker.cpp
    VoiceEnhancer.cpp
    HardwareEncoderPro.cpp
    ThermalManager.cpp
    HapticEngine.cpp
    AudioDucking.cpp
    AutoCaptions.cpp
    AAudioEngine.cpp
    DisplaySync.cpp
    AdaptiveResolution.cpp
    TripleBuffer.cpp
    SuperResolution.cpp
    VulkanRenderer.cpp
    ProfessionalEffects.cpp
)

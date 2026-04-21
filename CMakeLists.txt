cmake_minimum_required(VERSION 3.10.2)
project(engine)
set(CMAKE_CXX_STANDARD 17)

set(ENGINE_SOURCES
    core/clip.cpp
    core/timeline.cpp
    engine/engine.cpp
    engine/project.cpp
    preview/preview_controller.cpp
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

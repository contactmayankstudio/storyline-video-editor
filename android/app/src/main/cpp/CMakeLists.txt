cmake_minimum_required(VERSION 3.10.2)
project(video_engine_android)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_program(CCACHE_PROGRAM ccache)
if (CCACHE_PROGRAM)
    message(STATUS "Using ccache: ${CCACHE_PROGRAM}")
    set(CMAKE_C_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
    set(CMAKE_CXX_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
endif()

set(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../..")
set(ANDROID_JNI_DIR "${REPO_ROOT}/android/jni")

# Reuse the native engine target from the repo root. This keeps Android on the
# same preview/playback code path and avoids compiling desktop-only GPU/export code.
add_subdirectory("${REPO_ROOT}" engine_build)

add_library(
    video_engine
    SHARED
    "${ANDROID_JNI_DIR}/native_preview.cpp"
    "${ANDROID_JNI_DIR}/project_jni.cpp"
    "${ANDROID_JNI_DIR}/command_jni.cpp"
    "${REPO_ROOT}/engine/commands/command_manager.cpp"
)

target_include_directories(
    video_engine
    PRIVATE
    "${REPO_ROOT}"
    "${ANDROID_JNI_DIR}"
)

find_library(log-lib log)
find_library(android-lib android)
find_library(mediandk-lib mediandk)
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)
find_library(opensles-lib OpenSLES)

target_link_libraries(
    video_engine
    PRIVATE
    engine
    ${log-lib}
    ${android-lib}
    ${mediandk-lib}
    ${egl-lib}
    ${gles-lib}
    ${opensles-lib}
)

# CMake configuration for GPU backend
# This file contains GPU-specific targets and configuration

# OpenGL Backend (GPU Preview Renderer)
option(BUILD_GPU_BACKEND "Build GPU-based OpenGL preview renderer" OFF)

if(BUILD_GPU_BACKEND)
    # Find required packages
    find_package(OpenGL REQUIRED)
    find_package(X11 REQUIRED)
    find_package(glm REQUIRED)
    find_package(GLEW REQUIRED)

    message(STATUS "GPU Backend: ENABLED")
    message(STATUS "  OpenGL: ${OPENGL_LIBRARIES}")
    message(STATUS "  X11: ${X11_LIBRARIES}")
    message(STATUS "  GLM: ${GLM_INCLUDE_DIRS}")
    message(STATUS "  GLEW: ${GLEW_LIBRARIES}")

    # GPU backend sources
    target_sources(video_engine PRIVATE
        backend/gpu/gl_context.cpp
        backend/gpu/shader_program.cpp
        backend/gpu/shader_loader.cpp
        backend/gpu/texture.cpp
        backend/gpu/quad_mesh.cpp
        backend/gpu/preview_renderer.cpp
    )

    # Include directories
    target_include_directories(video_engine PRIVATE
        ${OPENGL_INCLUDE_DIR}
        ${X11_INCLUDE_DIR}
    )

    # Link libraries
    target_link_libraries(video_engine PRIVATE
        ${OPENGL_LIBRARIES}
        ${X11_LIBRARIES}
        GLEW::GLEW
        glm::glm
    )

    # Explicit GLX linking
    if(UNIX AND NOT APPLE)
        find_library(GLX_LIBRARY GLX)
        if(GLX_LIBRARY)
            target_link_libraries(video_engine PRIVATE ${GLX_LIBRARY})
        endif()
    endif()

    # Compiler flags
    if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
        target_compile_options(video_engine PRIVATE -Wall -Wextra -pedantic)
    endif()

    # Install shader files
    install(DIRECTORY backend/gpu/shaders/
            DESTINATION share/video_engine/shaders
            FILES_MATCHING PATTERN "*.vert" PATTERN "*.frag")

else()
    message(STATUS "GPU Backend: DISABLED")
endif()

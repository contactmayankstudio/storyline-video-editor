#pragma once

#include <string>
#include <memory>
#include <vector>

namespace VideoEngine::GPU {

/**
 * Utility for loading shader files from disk.
 * Platform-independent file I/O for GLSL shader sources.
 */
class ShaderLoader {
public:
    /**
     * Load a shader file from disk.
     * @param filePath Path to shader file (relative or absolute)
     * @return Shader source code as string
     * @throws std::runtime_error if file cannot be read
     */
    static std::string loadShaderFile(const std::string& filePath);

    /**
     * Find shader directory relative to executable or library.
     * Tries multiple common locations.
     * @return Path to shader directory
     */
    static std::string getShaderDirectory();

    /**
     * Load shader with automatic path resolution.
     * Tries to find shader in default locations.
     * @param shaderName Name of shader file (e.g., "fullscreen.vert")
     * @return Shader source code
     * @throws std::runtime_error if shader not found
     */
    static std::string loadShader(const std::string& shaderName);
};

} // namespace VideoEngine::GPU

#include "shader_loader.h"
#include <fstream>
#include <sstream>
#include <iostream>
#include <algorithm>
#include <filesystem>
#include <stdexcept>

namespace VideoEngine::GPU {

std::string ShaderLoader::loadShaderFile(const std::string& filePath) {
    std::ifstream file(filePath);
    if (!file.is_open()) {
        throw std::runtime_error("Failed to open shader file: " + filePath);
    }

    std::stringstream buffer;
    buffer << file.rdbuf();
    std::string source = buffer.str();

    if (source.empty()) {
        throw std::runtime_error("Shader file is empty: " + filePath);
    }

    std::cout << "[ShaderLoader] Loaded " << filePath 
              << " (" << source.length() << " bytes)\n";
    return source;
}

std::string ShaderLoader::getShaderDirectory() {
    // Try multiple common locations for shader directory
    std::vector<std::string> searchPaths = {
        // Relative to current working directory
        "./backend/gpu/shaders",
        "backend/gpu/shaders",
        
        // Common build directory structures
        "../backend/gpu/shaders",
        "../../backend/gpu/shaders",
        
        // Relative to executable (if available)
        "./shaders",
        "../shaders",
        
        // Absolute path (if set in environment)
        // Can be overridden via CMAKE_INSTALL_PREFIX
    };

    // Check environment variable first
    const char* envPath = std::getenv("VIDEOENGINE_SHADER_PATH");
    if (envPath && std::filesystem::exists(envPath)) {
        std::cout << "[ShaderLoader] Using VIDEOENGINE_SHADER_PATH: " << envPath << "\n";
        return std::string(envPath);
    }

    // Try search paths
    for (const auto& path : searchPaths) {
        if (std::filesystem::exists(path) && std::filesystem::is_directory(path)) {
            std::cout << "[ShaderLoader] Found shader directory: " << path << "\n";
            return path;
        }
    }

    // If nothing found, return default and let caller handle error
    std::cerr << "[ShaderLoader] Warning: Shader directory not found in search paths\n";
    return "./backend/gpu/shaders";
}

std::string ShaderLoader::loadShader(const std::string& shaderName) {
    std::string shaderDir = getShaderDirectory();
    std::string fullPath = shaderDir + "/" + shaderName;

    // Normalize path separators for cross-platform compatibility
    std::replace(fullPath.begin(), fullPath.end(), '\\', '/');

    try {
        return loadShaderFile(fullPath);
    } catch (const std::exception& e) {
        std::cerr << "[ShaderLoader] Error: " << e.what() << "\n";
        
        // Try alternate path formats
        std::string altPath = shaderDir + "\\" + shaderName;
        try {
            return loadShaderFile(altPath);
        } catch (...) {
            throw std::runtime_error("Could not load shader: " + shaderName + 
                                   " (tried: " + fullPath + ")");
        }
    }
}

} // namespace VideoEngine::GPU

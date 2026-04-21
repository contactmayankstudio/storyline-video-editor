#pragma once

#include <string>
#include <memory>
#include <cstdint>

namespace VideoEngine::GPU {

/**
 * Shader program wrapper for OpenGL.
 * Compiles and manages vertex + fragment shaders.
 */
class ShaderProgram {
public:
    /**
     * Create a shader program from source code.
     * @param vertexSource GLSL vertex shader source
     * @param fragmentSource GLSL fragment shader source
     */
    ShaderProgram(const std::string& vertexSource, const std::string& fragmentSource);
    
    /**
     * Create a shader program from shader files.
     * @param vertexShaderPath Path to vertex shader file
     * @param fragmentShaderPath Path to fragment shader file
     * @throws std::runtime_error if files cannot be loaded
     */
    static std::shared_ptr<ShaderProgram> createFromFiles(
        const std::string& vertexShaderPath,
        const std::string& fragmentShaderPath
    );
    
    ~ShaderProgram();

    // Non-copyable
    ShaderProgram(const ShaderProgram&) = delete;
    ShaderProgram& operator=(const ShaderProgram&) = delete;

    /**
     * Use this shader program for rendering.
     */
    void use() const;

    /**
     * Get OpenGL program handle.
     */
    uint32_t getHandle() const { return m_handle; }

    /**
     * Set uniform mat4.
     */
    void setUniformMatrix4fv(const std::string& name, const float* value) const;

    /**
     * Set uniform mat3.
     */
    void setUniformMatrix3fv(const std::string& name, const float* value) const;

    /**
     * Set uniform float.
     */
    void setUniform1f(const std::string& name, float value) const;

    /**
     * Set uniform int.
     */
    void setUniform1i(const std::string& name, int value) const;

    /**
     * Set uniform vec2.
     */
    void setUniform2f(const std::string& name, float x, float y) const;

    /**
     * Set uniform vec3.
     */
    void setUniform3f(const std::string& name, float x, float y, float z) const;

    /**
     * Set uniform vec4.
     */
    void setUniform4f(const std::string& name, float x, float y, float z, float w) const;

    /**
     * Check if program compiled and linked successfully.
     */
    bool isValid() const { return m_isValid; }

private:
    uint32_t m_handle;
    bool m_isValid;

    uint32_t compileShader(uint32_t type, const std::string& source);
    int32_t getUniformLocation(const std::string& name) const;
};

using ShaderProgramPtr = std::shared_ptr<ShaderProgram>;

} // namespace VideoEngine::GPU

#include "shader_program.h"
#include "shader_loader.h"
#ifdef __ANDROID__
#include <GLES3/gl3.h>
#else
#include <GL/glew.h>
#endif
#include <iostream>
#include <unordered_map>

namespace VideoEngine::GPU {

ShaderProgram::ShaderProgram(const std::string& vertexSource, const std::string& fragmentSource)
    : m_handle(0)
    , m_isValid(false)
{
    uint32_t vertexShader = compileShader(GL_VERTEX_SHADER, vertexSource);
    if (!vertexShader) {
        return;
    }

    uint32_t fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
    if (!fragmentShader) {
        glDeleteShader(vertexShader);
        return;
    }

    // Link program
    m_handle = glCreateProgram();
    glAttachShader(m_handle, vertexShader);
    glAttachShader(m_handle, fragmentShader);
    glLinkProgram(m_handle);

    int success;
    glGetProgramiv(m_handle, GL_LINK_STATUS, &success);
    if (!success) {
        char infoLog[512];
        glGetProgramInfoLog(m_handle, sizeof(infoLog), nullptr, infoLog);
        std::cerr << "[ShaderProgram] Link error: " << infoLog << "\n";
        glDeleteProgram(m_handle);
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        return;
    }

    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    m_isValid = true;
    std::cout << "[ShaderProgram] Compiled and linked successfully (handle=" << m_handle << ")\n";
}

std::shared_ptr<ShaderProgram> ShaderProgram::createFromFiles(
    const std::string& vertexShaderPath,
    const std::string& fragmentShaderPath)
{
    try {
        std::string vertexSource = ShaderLoader::loadShader(vertexShaderPath);
        std::string fragmentSource = ShaderLoader::loadShader(fragmentShaderPath);
        
        auto program = std::make_shared<ShaderProgram>(vertexSource, fragmentSource);
        
        if (!program->isValid()) {
            throw std::runtime_error("Failed to compile shaders from files");
        }
        
        return program;
    } catch (const std::exception& e) {
        std::cerr << "[ShaderProgram] Failed to create from files: " << e.what() << "\n";
        throw;
    }
}

ShaderProgram::~ShaderProgram() {
    if (m_handle) {
        glDeleteProgram(m_handle);
    }
}

uint32_t ShaderProgram::compileShader(uint32_t type, const std::string& source) {
    uint32_t shader = glCreateShader(type);
    const char* sourceCStr = source.c_str();
    glShaderSource(shader, 1, &sourceCStr, nullptr);
    glCompileShader(shader);

    int success;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &success);
    if (!success) {
        char infoLog[512];
        glGetShaderInfoLog(shader, sizeof(infoLog), nullptr, infoLog);
        const char* shaderType = (type == GL_VERTEX_SHADER) ? "vertex" : "fragment";
        std::cerr << "[ShaderProgram] " << shaderType << " shader compile error: " << infoLog << "\n";
        glDeleteShader(shader);
        return 0;
    }

    return shader;
}

void ShaderProgram::use() const {
    if (m_isValid) {
        glUseProgram(m_handle);
    }
}

int32_t ShaderProgram::getUniformLocation(const std::string& name) const {
    return glGetUniformLocation(m_handle, name.c_str());
}

void ShaderProgram::setUniformMatrix4fv(const std::string& name, const float* value) const {
    int32_t loc = getUniformLocation(name);
    if (loc >= 0) {
        glUniformMatrix4fv(loc, 1, GL_FALSE, value);
    }
}

void ShaderProgram::setUniformMatrix3fv(const std::string& name, const float* value) const {
    int32_t loc = getUniformLocation(name);
    if (loc >= 0) {
        glUniformMatrix3fv(loc, 1, GL_FALSE, value);
    }
}

void ShaderProgram::setUniform1f(const std::string& name, float value) const {
    int32_t loc = getUniformLocation(name);
    if (loc >= 0) {
        glUniform1f(loc, value);
    }
}

void ShaderProgram::setUniform1i(const std::string& name, int value) const {
    int32_t loc = getUniformLocation(name);
    if (loc >= 0) {
        glUniform1i(loc, value);
    }
}

void ShaderProgram::setUniform2f(const std::string& name, float x, float y) const {
    int32_t loc = getUniformLocation(name);
    if (loc >= 0) {
        glUniform2f(loc, x, y);
    }
}

void ShaderProgram::setUniform3f(const std::string& name, float x, float y, float z) const {
    int32_t loc = getUniformLocation(name);
    if (loc >= 0) {
        glUniform3f(loc, x, y, z);
    }
}

void ShaderProgram::setUniform4f(const std::string& name, float x, float y, float z, float w) const {
    int32_t loc = getUniformLocation(name);
    if (loc >= 0) {
        glUniform4f(loc, x, y, z, w);
    }
}

} // namespace VideoEngine::GPU

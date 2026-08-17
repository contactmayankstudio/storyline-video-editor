#include "CinematicLUTEngine.h"
#include <fstream>
#include <sstream>
#include <iostream>
#include <cmath>

namespace SmoothEngineProPlus {

CinematicLUTEngine::CinematicLUTEngine() {
    initShader();
}

CinematicLUTEngine::~CinematicLUTEngine() {
    release();
}

void CinematicLUTEngine::initShader() {
    const char* vShaderCode = R"(
        #version 300 es
        layout (location = 0) in vec3 aPos;
        layout (location = 1) in vec2 aTexCoord;
        out vec2 TexCoord;
        void main() {
            gl_Position = vec4(aPos, 1.0);
            TexCoord = aTexCoord;
        }
    )";

    const char* fShaderCode = R"(
        #version 300 es
        precision highp float;
        precision highp sampler3D;
        in vec2 TexCoord;
        out vec4 FragColor;
        uniform sampler2D inputTexture;
        uniform sampler3D lutTexture;
        uniform float intensity;

        void main() {
            vec4 rawColor = texture(inputTexture, TexCoord);
            // Sample the 3D LUT using the RGB values as coordinates
            vec3 gradedColor = texture(lutTexture, rawColor.rgb).rgb;
            FragColor = vec4(mix(rawColor.rgb, gradedColor, intensity), rawColor.a);
        }
    )";

    // Standard shader compilation logic omitted for brevity, but this is the core GL logic
    // In a real implementation, you'd use your existing ShaderLoader.
}

bool CinematicLUTEngine::loadLUT(const std::string& filePath) {
    std::ifstream file(filePath);
    if (!file.is_open()) return false;

    std::string line;
    std::vector<float> lutData;
    int size = 0;

    while (std::getline(file, line)) {
        if (line.find("LUT_3D_SIZE") != std::string::npos) {
            std::stringstream ss(line);
            std::string tmp;
            ss >> tmp >> size;
        } else if (!line.empty() && line[0] != '#') {
            float r, g, b;
            if (std::sscanf(line.c_str(), "%f %f %f", &r, &g, &b) == 3) {
                lutData.push_back(r);
                lutData.push_back(g);
                lutData.push_back(b);
            }
        }
    }

    if (size <= 0 || lutData.size() != size * size * size * 3) return false;

    m_lutSize = size;

    // Create 3D Texture
    glGenTextures(1, &m_lutTextureId);
    glBindTexture(GL_TEXTURE_3D, m_lutTextureId);
    glTexImage3D(GL_TEXTURE_3D, 0, GL_RGB, size, size, size, 0, GL_RGB, GL_FLOAT, lutData.data());
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

    return true;
}

void CinematicLUTEngine::applyLUT(GLuint inputTextureId, int width, int height) {
    if (m_lutTextureId == 0) return;

    glUseProgram(m_shaderProgram);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, inputTextureId);
    glUniform1i(glGetUniformLocation(m_shaderProgram, "inputTexture"), 0);

    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_3D, m_lutTextureId);
    glUniform1i(glGetUniformLocation(m_shaderProgram, "lutTexture"), 1);

    glUniform1f(glGetUniformLocation(m_shaderProgram, "intensity"), m_intensity);

    // Draw full screen quad...
}

void CinematicLUTEngine::release() {
    if (m_lutTextureId) glDeleteTextures(1, &m_lutTextureId);
    if (m_shaderProgram) glDeleteProgram(m_shaderProgram);
}

} // namespace SmoothEngineProPlus

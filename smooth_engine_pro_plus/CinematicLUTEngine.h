#ifndef CINEMATIC_LUT_ENGINE_H
#define CINEMATIC_LUT_ENGINE_H

#include <string>
#include <vector>
#include <GLES3/gl3.h>

namespace SmoothEngineProPlus {

/**
 * CinematicLUTEngine handles loading and applying 3D Look-Up Tables (LUTs).
 * Supports standard .cube files (32x32x32) for Hollywood-grade color grading.
 */
class CinematicLUTEngine {
public:
    CinematicLUTEngine();
    ~CinematicLUTEngine();

    // Loads a .cube file and creates a 3D texture
    bool loadLUT(const std::string& filePath);

    // Applies the LUT to the current frame buffer using a fragment shader
    void applyLUT(GLuint inputTextureId, int width, int height);

    // Set intensity (0.0 to 1.0)
    void setIntensity(float intensity) { m_intensity = intensity; }

private:
    GLuint m_lutTextureId = 0;
    GLuint m_shaderProgram = 0;
    float m_intensity = 1.0f;
    int m_lutSize = 0;

    void initShader();
    void release();
};

} // namespace SmoothEngineProPlus

#endif // CINEMATIC_LUT_ENGINE_H

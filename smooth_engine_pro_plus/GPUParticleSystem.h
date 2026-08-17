#ifndef GPU_PARTICLE_SYSTEM_H
#define GPU_PARTICLE_SYSTEM_H

#include <GLES3/gl3.h>
#include <vector>

namespace SmoothEngineProPlus {

/**
 * GPUParticleSystem handles real-time visual effects like Rain, Snow, and Fire
 * using GPU Compute shaders (or Transform Feedback for ES 3.0 compatibility).
 */
class GPUParticleSystem {
public:
    enum class EffectType { RAIN, SNOW, BUBBLES, FIREFLIES };

    GPUParticleSystem(int maxParticles = 5000);
    ~GPUParticleSystem();

    void init();
    void setEffect(EffectType type);
    void update(float deltaTime);
    void render(const float* projectionMatrix);

private:
    int m_maxParticles;
    GLuint m_vbo[2]; // Double buffering for transform feedback
    GLuint m_vao[2];
    GLuint m_transformFeedback[2];
    GLuint m_updateProgram;
    GLuint m_renderProgram;
    
    float m_birthRate = 0.1f;
    EffectType m_currentType = EffectType::SNOW;

    void setupShaders();
    void setupBuffers();
};

} // namespace SmoothEngineProPlus

#endif // GPU_PARTICLE_SYSTEM_H

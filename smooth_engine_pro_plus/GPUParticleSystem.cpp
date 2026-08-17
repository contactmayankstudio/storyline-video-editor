#include "GPUParticleSystem.h"
#include <random>

namespace SmoothEngineProPlus {

GPUParticleSystem::GPUParticleSystem(int maxParticles) : m_maxParticles(maxParticles) {
    init();
}

GPUParticleSystem::~GPUParticleSystem() {
    // Cleanup GL resources
}

void GPUParticleSystem::init() {
    setupShaders();
    setupBuffers();
}

void GPUParticleSystem::setupShaders() {
    // Update Shader (Transform Feedback) - Moves particles
    const char* updateVS = R"(
        #version 300 es
        layout (location = 0) in vec2 aPos;
        layout (location = 1) in vec2 aVel;
        layout (location = 2) in float aLife;

        out vec2 vPos;
        out vec2 vVel;
        out float vLife;

        uniform float deltaTime;
        uniform vec2 gravity;

        void main() {
            vVel = aVel + gravity * deltaTime;
            vPos = aPos + vVel * deltaTime;
            vLife = aLife - deltaTime;
            
            // Reset particle if dead
            if (vLife < 0.0) {
                vLife = 2.0;
                vPos = vec2(0.0, 1.0); // Start from top
            }
        }
    )";

    // Render Shader - Draws particles as soft circles
    const char* renderFS = R"(
        #version 300 es
        precision mediump float;
        out vec4 FragColor;
        void main() {
            float dist = distance(gl_PointCoord, vec2(0.5));
            float alpha = smoothstep(0.5, 0.4, dist);
            FragColor = vec4(1.0, 1.0, 1.0, alpha);
        }
    )";
}

void GPUParticleSystem::setupBuffers() {
    // Initialize particles with random positions
    std::vector<float> initialData(m_maxParticles * 5); // x, y, vx, vy, life
    // Fill with random data...
}

void GPUParticleSystem::update(float deltaTime) {
    // Use glBeginTransformFeedback to update on GPU
}

void GPUParticleSystem::render(const float* projectionMatrix) {
    // Draw particles using GL_POINTS
}

} // namespace SmoothEngineProPlus

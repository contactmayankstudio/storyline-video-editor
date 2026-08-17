#pragma once

#include <cstdint>
#include <map>
#include <string>

namespace VideoEngine::Advanced {

/**
 * @brief Dynamic Transition Engine for GLSL-based effects.
 * 
 * Instead of hardcoding 5-10 transitions, this engine allows
 * loading GLSL transition files (compatible with gl-transitions.com).
 */
class TransitionEngine {
public:
    struct TransitionProfile {
        int typeId = 0;
        std::string name;
        float progressExponent = 1.0f;
        float smoothness = 0.0f;
        std::map<std::string, float> params;
    };

    void registerTransition(const TransitionProfile& transition);
    const TransitionProfile* findTransition(const std::string& name) const;
    void prepareShader(const std::string& name);
    void renderTransition(const std::string& name, uint32_t outTex, uint32_t inTex, float progress);

    static int normalizeTypeId(int typeId);
    static const TransitionProfile& resolveTransition(int typeId);
    static float remapProgress(int typeId, float progress);

private:
    std::map<std::string, TransitionProfile> m_transitions;
};

/**
 * Examples of Professional Transitions:
 * 1. Glitch: RGB Split + Noise.
 * 2. Zoom: Radial scale interpolation.
 * 3. Film Burn: Alpha-blended fire/light leaks.
 * 4. Page Curl: Geometric deformation (3D).
 */

} // namespace VideoEngine::Advanced

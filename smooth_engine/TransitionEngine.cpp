#include "TransitionEngine.h"
#include <algorithm>
#include <cmath>
#include <iostream>

namespace {

float clampUnit(float value) {
    return std::clamp(value, 0.0f, 1.0f);
}

float smoothstep01(float value) {
    const float clamped = clampUnit(value);
    return clamped * clamped * (3.0f - (2.0f * clamped));
}

const std::map<int, VideoEngine::Advanced::TransitionEngine::TransitionProfile>& builtinProfiles() {
    static const std::map<int, VideoEngine::Advanced::TransitionEngine::TransitionProfile> profiles = {
        {0, {0, "cut", 1.0f, 0.0f, {}}},
        {1, {1, "fade", 1.0f, 1.0f, {{"opacity_bias", 0.08f}}}},
        {2, {2, "cross", 1.0f, 0.25f, {{"mix_bias", 0.0f}}}},
        {3, {3, "wipe", 1.0f, 1.0f, {{"direction", 1.0f}}}},
        {4, {4, "slide", 1.0f, 1.0f, {{"direction", 1.0f}}}},
        {5, {5, "dither", 0.92f, 0.15f, {{"grain", 1.0f}}}},
        {6, {6, "circle", 0.85f, 0.55f, {{"center_weight", 1.0f}}}},
        {7, {7, "zigzag", 1.08f, 0.65f, {{"wave", 1.0f}}}},
    };
    return profiles;
}

} // namespace

namespace VideoEngine::Advanced {

void TransitionEngine::registerTransition(const TransitionProfile& transition) {
    m_transitions[transition.name] = transition;
}

const TransitionEngine::TransitionProfile* TransitionEngine::findTransition(const std::string& name) const {
    auto it = m_transitions.find(name);
    if (it == m_transitions.end()) {
        return nullptr;
    }
    return &it->second;
}

void TransitionEngine::prepareShader(const std::string& name) {
    const auto* profile = findTransition(name);
    if (!profile) {
        std::cerr << "[TransitionEngine] Transition not found: " << name << "\n";
        return;
    }
    
    // In a real implementation, this would:
    // 1. Compile vertex and fragment shaders
    // 2. Link them into a GLProgram
    // 3. Cache the program handle
    std::cout << "[TransitionEngine] Compiling GLSL for " << name << "...\n";
    (void)profile;
}

void TransitionEngine::renderTransition(const std::string& name, uint32_t outTex, uint32_t inTex, float progress) {
    const auto* profile = findTransition(name);
    if (!profile) return;

    const float remapped = remapProgress(profile->typeId, progress);
    std::cout << "[TransitionEngine] prepared transition " << profile->name
              << " outTex=" << outTex
              << " inTex=" << inTex
              << " progress=" << remapped << "\n";
}

int TransitionEngine::normalizeTypeId(int typeId) {
    return builtinProfiles().count(typeId) > 0 ? typeId : 2;
}

const TransitionEngine::TransitionProfile& TransitionEngine::resolveTransition(int typeId) {
    const auto normalizedTypeId = normalizeTypeId(typeId);
    const auto& profiles = builtinProfiles();
    auto it = profiles.find(normalizedTypeId);
    if (it != profiles.end()) {
        return it->second;
    }
    return profiles.at(2);
}

float TransitionEngine::remapProgress(int typeId, float progress) {
    const auto& profile = resolveTransition(typeId);
    float remapped = clampUnit(progress);
    if (std::fabs(profile.progressExponent - 1.0f) > 0.001f) {
        remapped = std::pow(remapped, profile.progressExponent);
    }
    if (profile.smoothness > 0.0f) {
        const float smoothed = smoothstep01(remapped);
        remapped = remapped + ((smoothed - remapped) * std::clamp(profile.smoothness, 0.0f, 1.0f));
    }

    switch (profile.typeId) {
        case 1:
        case 3:
        case 4:
            return smoothstep01(remapped);
        case 5:
            return clampUnit((remapped * 0.85f) + (smoothstep01(remapped) * 0.15f));
        case 6:
            return clampUnit(std::sqrt(remapped));
        case 7: {
            const float wobble = std::sin(remapped * 3.14159265f) * 0.06f;
            return clampUnit(smoothstep01(remapped + wobble));
        }
        default:
            return remapped;
    }
}

} // namespace VideoEngine::Advanced

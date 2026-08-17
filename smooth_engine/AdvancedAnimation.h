#pragma once

#include <vector>
#include <variant>
#include <algorithm>

namespace VideoEngine::Advanced {

/**
 * @brief Keyframe types for professional animation.
 * 
 * Linear: Simple constant change.
 * Bezier: Smooth curves for professional easing (VN/KineMaster style).
 * Hold: Jump directly to value.
 */
enum class InterpolationType {
    Linear,
    Bezier,
    Hold
};

struct Keyframe {
    int64_t timeMs;
    float value;
    InterpolationType interpolation = InterpolationType::Bezier;
    float cp1_x = 0.25f, cp1_y = 0.1f; // Control points for Bezier
    float cp2_x = 0.25f, cp2_y = 1.0f;
};

/**
 * @brief AnimatableProperty allows any value (Scale, Position, Opacity)
 * to be changed over time using keyframes.
 */
class AnimatableProperty {
public:
    void setDefaultValue(float value) { m_defaultValue = value; }
    void addKeyframe(const Keyframe& kf);
    float getValueAt(int64_t timeMs) const;

private:
    float m_defaultValue = 1.0f;
    std::vector<Keyframe> m_keyframes;
};

/**
 * @brief Transform component for VN-style "Picture-in-Picture" (PiP).
 */
struct AdvancedTransform {
    AnimatableProperty scaleX;
    AnimatableProperty scaleY;
    AnimatableProperty positionX;
    AnimatableProperty positionY;
    AnimatableProperty rotation;
    AnimatableProperty anchorPointX;
    AnimatableProperty anchorPointY;
};

} // namespace VideoEngine::Advanced

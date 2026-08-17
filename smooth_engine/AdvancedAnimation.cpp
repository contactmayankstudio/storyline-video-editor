#include "AdvancedAnimation.h"
#include <cmath>

namespace VideoEngine::Advanced {

/**
 * @brief Cubic Bezier solver for professional easing.
 * Based on the unit bezier easing used in CSS and VN/CapCut.
 */
class BezierSolver {
public:
    BezierSolver(float p1x, float p1y, float p2x, float p2y)
        : cx(3.0f * p1x), bx(3.0f * (p2x - p1x) - (3.0f * p1x)), ax(1.0f - (3.0f * p1x) - (3.0f * (p2x - p1x) - (3.0f * p1x))),
          cy(3.0f * p1y), by(3.0f * (p2y - p1y) - (3.0f * p1y)), ay(1.0f - (3.0f * p1y) - (3.0f * (p2y - p1y) - (3.0f * p1y))) {}

    float solve(float t) {
        return sampleCurveY(solveCurveX(t));
    }

private:
    float sampleCurveX(float t) { return ((ax * t + bx) * t + cx) * t; }
    float sampleCurveY(float t) { return ((ay * t + by) * t + cy) * t; }
    float sampleCurveDerivativeX(float t) { return (3.0f * ax * t + 2.0f * bx) * t + cx; }

    float solveCurveX(float x) {
        float t2 = x;
        for (int i = 0; i < 8; i++) {
            float x2 = sampleCurveX(t2) - x;
            if (std::abs(x2) < 1e-6) return t2;
            float d2 = sampleCurveDerivativeX(t2);
            if (std::abs(d2) < 1e-6) break;
            t2 = t2 - x2 / d2;
        }
        return t2;
    }

    float ax, bx, cx, ay, by, cy;
};

void AnimatableProperty::addKeyframe(const Keyframe& kf) {
    m_keyframes.push_back(kf);
    std::sort(m_keyframes.begin(), m_keyframes.end(), [](const Keyframe& a, const Keyframe& b) {
        return a.timeMs < b.timeMs;
    });
}

float AnimatableProperty::getValueAt(int64_t timeMs) const {
    if (m_keyframes.empty()) return m_defaultValue;
    if (timeMs <= m_keyframes.front().timeMs) return m_keyframes.front().value;
    if (timeMs >= m_keyframes.back().timeMs) return m_keyframes.back().value;

    // Binary search for the two keyframes surrounding timeMs
    auto it = std::lower_bound(m_keyframes.begin(), m_keyframes.end(), timeMs,
        [](const Keyframe& kf, int64_t t) { return kf.timeMs < t; });

    const Keyframe& kf2 = *it;
    const Keyframe& kf1 = *std::prev(it);

    float t = static_cast<float>(timeMs - kf1.timeMs) / (kf2.timeMs - kf1.timeMs);

    switch (kf2.interpolation) {
        case InterpolationType::Hold:
            return kf1.value;
        case InterpolationType::Linear:
            return kf1.value + (kf2.value - kf1.value) * t;
        case InterpolationType::Bezier: {
            BezierSolver solver(kf2.cp1_x, kf2.cp1_y, kf2.cp2_x, kf2.cp2_y);
            float progress = solver.solve(t);
            return kf1.value + (kf2.value - kf1.value) * progress;
        }
    }
    return kf1.value;
}

} // namespace VideoEngine::Advanced

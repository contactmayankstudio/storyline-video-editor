#include "SpeedRamping.h"
#include <algorithm>
#include <array>

namespace VideoEngine::Advanced {

void TimeMapper::addSpeedPoint(int64_t timelineMs, float speed) {
    m_curve.push_back({timelineMs, speed});
    std::sort(m_curve.begin(), m_curve.end(), [](const SpeedPoint& a, const SpeedPoint& b) {
        return a.timelineMs < b.timelineMs;
    });
    
    // Pre-calculate integral of speed (distance traveled in source time)
    // sourceTime = ∫ speed(t) dt
    m_integratedSourceTime.clear();
    m_integratedSourceTime.push_back(0);
    
    int64_t totalSourceMs = 0;
    for (size_t i = 1; i < m_curve.size(); ++i) {
        int64_t dt = m_curve[i].timelineMs - m_curve[i-1].timelineMs;
        float avgSpeed = (m_curve[i].speed + m_curve[i-1].speed) / 2.0f;
        totalSourceMs += static_cast<int64_t>(dt * avgSpeed);
        m_integratedSourceTime.push_back(totalSourceMs);
    }
}

int64_t TimeMapper::mapToSourceTime(int64_t currentTimelineMs) const {
    if (m_curve.empty()) return currentTimelineMs;
    if (currentTimelineMs <= m_curve.front().timelineMs) {
        return static_cast<int64_t>(currentTimelineMs * m_curve.front().speed);
    }

    auto it = std::lower_bound(m_curve.begin(), m_curve.end(), currentTimelineMs,
        [](const SpeedPoint& sp, int64_t t) { return sp.timelineMs < t; });

    if (it == m_curve.end()) {
        size_t lastIdx = m_curve.size() - 1;
        int64_t dt = currentTimelineMs - m_curve[lastIdx].timelineMs;
        return m_integratedSourceTime[lastIdx] + static_cast<int64_t>(dt * m_curve[lastIdx].speed);
    }

    size_t idx = std::distance(m_curve.begin(), it);
    const SpeedPoint& sp1 = m_curve[idx - 1];
    const SpeedPoint& sp2 = m_curve[idx];
    
    int64_t dt = currentTimelineMs - sp1.timelineMs;
    float t = static_cast<float>(dt) / (sp2.timelineMs - sp1.timelineMs);
    
    // Average speed in the segment (Linear speed ramp)
    float currentSpeed = sp1.speed + (sp2.speed - sp1.speed) * (t / 2.0f);
    
    return m_integratedSourceTime[idx - 1] + static_cast<int64_t>(dt * currentSpeed);
}

namespace {
float clampPositiveSpeed(float value) {
    return std::max(0.1f, value);
}
}

int64_t mapTimelineToSourceWithProfile(
    int64_t localTimelineUnits,
    int64_t clipDurationUnits,
    int64_t sourceInUnits,
    int64_t sourceOutUnits,
    float playbackSpeed,
    bool reversePlayback,
    const std::string& curveProfile,
    float curveStrength) {
    const int64_t clampedDuration = std::max<int64_t>(1, clipDurationUnits);
    const int64_t clampedLocal = std::clamp<int64_t>(localTimelineUnits, 0, clampedDuration - 1);
    const int64_t sourceSpan = std::max<int64_t>(1, sourceOutUnits - sourceInUnits);
    const float baseSpeed = clampPositiveSpeed(playbackSpeed);

    int64_t mapped = sourceInUnits + static_cast<int64_t>(
        std::llround(static_cast<double>(clampedLocal) * static_cast<double>(baseSpeed)));

    const float strength = std::clamp(curveStrength, 0.1f, 4.0f);
    if (curveProfile != "linear" && clampedDuration > 1) {
        TimeMapper mapper;
        const int64_t endUnit = clampedDuration - 1;
        const int64_t midUnit = endUnit / 2;

        float startSpeed = baseSpeed;
        float midSpeed = baseSpeed;
        float endSpeed = baseSpeed;

        if (curveProfile == "ease_in") {
            startSpeed = clampPositiveSpeed(baseSpeed * std::clamp(0.55f / strength, 0.18f, 0.9f));
            endSpeed = clampPositiveSpeed(baseSpeed * (1.0f + (strength - 1.0f) * 0.85f));
        } else if (curveProfile == "ease_out") {
            startSpeed = clampPositiveSpeed(baseSpeed * (1.0f + (strength - 1.0f) * 0.85f));
            endSpeed = clampPositiveSpeed(baseSpeed * std::clamp(0.55f / strength, 0.18f, 0.9f));
        } else if (curveProfile == "ease_in_out") {
            startSpeed = clampPositiveSpeed(baseSpeed * std::clamp(0.55f / strength, 0.18f, 0.9f));
            midSpeed = clampPositiveSpeed(baseSpeed * (1.0f + (strength - 1.0f) * 1.15f));
            endSpeed = startSpeed;
        } else if (curveProfile == "hyperlapse") {
            startSpeed = clampPositiveSpeed(std::max(baseSpeed, 1.0f));
            midSpeed = clampPositiveSpeed(std::max(baseSpeed * 1.45f, strength * 1.2f));
            endSpeed = clampPositiveSpeed(std::max(baseSpeed * 2.0f, strength * 1.8f));
        }

        mapper.addSpeedPoint(0, startSpeed);
        if (curveProfile == "ease_in_out" || curveProfile == "hyperlapse") {
            mapper.addSpeedPoint(midUnit, midSpeed);
        }
        mapper.addSpeedPoint(endUnit, endSpeed);

        const int64_t mappedLocal = mapper.mapToSourceTime(clampedLocal);
        const int64_t maxLocalSource = sourceSpan - 1;
        mapped = sourceInUnits + std::clamp<int64_t>(mappedLocal, 0, maxLocalSource);
    }

    mapped = std::clamp<int64_t>(mapped, sourceInUnits, sourceOutUnits - 1);
    if (reversePlayback) {
        mapped = sourceOutUnits - 1 - (mapped - sourceInUnits);
        mapped = std::clamp<int64_t>(mapped, sourceInUnits, sourceOutUnits - 1);
    }
    return mapped;
}

} // namespace VideoEngine::Advanced

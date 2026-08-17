#pragma once

#include <string>
#include <vector>
#include <cmath>

namespace VideoEngine::Advanced {

/**
 * @brief TimeMapper handles Curve Speed (Speed Ramping).
 * 
 * VN/CapCut use speed curves (e.g., Montage, Hero, Bullet) where speed
 * changes smoothly from 0.1x to 10.0x.
 * 
 * The challenge: Moving from timeline time -> source clip time accurately
 * when speed is changing constantly.
 */
class TimeMapper {
public:
    struct SpeedPoint {
        int64_t timelineMs;
        float speed; // 1.0 = normal
    };

    /**
     * @brief Add a point to the speed curve.
     */
    void addSpeedPoint(int64_t timelineMs, float speed);

    /**
     * @brief Map timeline position to source media timestamp.
     * Uses the integral of the speed curve.
     */
    int64_t mapToSourceTime(int64_t currentTimelineMs) const;

private:
    std::vector<SpeedPoint> m_curve;
    std::vector<int64_t> m_integratedSourceTime;
};

int64_t mapTimelineToSourceWithProfile(
    int64_t localTimelineUnits,
    int64_t clipDurationUnits,
    int64_t sourceInUnits,
    int64_t sourceOutUnits,
    float playbackSpeed,
    bool reversePlayback,
    const std::string& curveProfile,
    float curveStrength);

} // namespace VideoEngine::Advanced

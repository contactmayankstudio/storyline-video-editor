#pragma once

#include <cstdint>

namespace VideoEngine::Android {

/**
 * @brief Android Thermal & Power Manager.
 * 
 * Professional editors like CapCut monitor the phone's heat.
 * If the phone gets too hot, this engine automatically:
 * 1. Lowers preview resolution.
 * 2. Throttles background rendering.
 * 3. Prevents the app from crashing due to thermal throttling.
 */
class ThermalManager {
public:
    enum class ThermalStatus {
        None,
        Light,
        Moderate,
        Severe,
        Critical
    };

    /**
     * @brief Get current thermal state from Android OS.
     */
    ThermalStatus getStatus() const;

    /**
     * @brief Returns a scale factor (0.5 to 1.0) to adjust engine workload.
     */
    float getWorkloadScale() const;

    /**
     * @brief Feed render cost / overload hints so preview can self-throttle.
     */
    void updatePreviewLoad(int64_t renderCostMs, int64_t targetFrameMs, int overloadScore);

    int recommendedPreviewLongEdgePx(int baseLongEdgePx) const;
    int recommendedPreviewFps(int requestedFps, int minFps) const;

private:
    int m_lastThermalLevel = 0;
    ThermalStatus m_status = ThermalStatus::None;
    int64_t m_smoothedRenderCostMs = 0;
    int64_t m_targetFrameMs = 33;
    int m_overloadScore = 0;
};

} // namespace VideoEngine::Android

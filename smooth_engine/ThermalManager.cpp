#include "ThermalManager.h"
#include <algorithm>
#include <cmath>

namespace VideoEngine::Android {

ThermalManager::ThermalStatus ThermalManager::getStatus() const {
    return m_status;
}

float ThermalManager::getWorkloadScale() const {
    auto status = getStatus();
    switch (status) {
        case ThermalStatus::Light: return 0.92f;
        case ThermalStatus::Moderate: return 0.82f;
        case ThermalStatus::Severe: return 0.70f;
        case ThermalStatus::Critical: return 0.55f;
        case ThermalStatus::None:
        default:
            return 1.0f;
    }
}

void ThermalManager::updatePreviewLoad(int64_t renderCostMs, int64_t targetFrameMs, int overloadScore) {
    const int64_t clampedCostMs = std::max<int64_t>(0, renderCostMs);
    m_targetFrameMs = std::max<int64_t>(1, targetFrameMs);
    m_overloadScore = std::clamp(overloadScore, 0, 12);

    if (m_smoothedRenderCostMs <= 0) {
        m_smoothedRenderCostMs = clampedCostMs;
    } else {
        m_smoothedRenderCostMs = ((m_smoothedRenderCostMs * 4) + clampedCostMs) / 5;
    }

    const double framePressure =
        static_cast<double>(m_smoothedRenderCostMs) / static_cast<double>(m_targetFrameMs);
    if (m_overloadScore >= 10 || framePressure >= 2.30) {
        m_status = ThermalStatus::Critical;
    } else if (m_overloadScore >= 8 || framePressure >= 1.75) {
        m_status = ThermalStatus::Severe;
    } else if (m_overloadScore >= 5 || framePressure >= 1.35) {
        m_status = ThermalStatus::Moderate;
    } else if (m_overloadScore >= 2 || framePressure >= 1.08) {
        m_status = ThermalStatus::Light;
    } else {
        m_status = ThermalStatus::None;
    }
    m_lastThermalLevel = static_cast<int>(m_status);
}

int ThermalManager::recommendedPreviewLongEdgePx(int baseLongEdgePx) const {
    if (baseLongEdgePx <= 0) {
        return 0;
    }
    const int scaled = static_cast<int>(std::lround(baseLongEdgePx * getWorkloadScale()));
    return std::clamp(scaled, 240, baseLongEdgePx);
}

int ThermalManager::recommendedPreviewFps(int requestedFps, int minFps) const {
    const int clampedRequested = std::max(1, requestedFps);
    const int clampedMin = std::max(1, std::min(minFps, clampedRequested));
    const int scaled = static_cast<int>(std::lround(clampedRequested * getWorkloadScale()));
    return std::clamp(scaled, clampedMin, clampedRequested);
}

} // namespace VideoEngine::Android

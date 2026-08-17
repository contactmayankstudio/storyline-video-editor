#include "FrameBudgetController.h"

#include <algorithm>
#include <cmath>

namespace VideoEngine::Performance {

int FrameBudgetController::frameIntervalMs(int fps) {
    return static_cast<int>(1000.0 / static_cast<double>(std::max(1, fps)));
}

int FrameBudgetController::evenPreviewLongEdge(int longEdgePx) {
    if (longEdgePx <= 0) {
        return 0;
    }
    return std::max(2, longEdgePx - (longEdgePx % 2));
}

FrameBudgetDecision FrameBudgetController::baseDecision(const FrameBudgetInput& input) {
    const int targetFps = std::max(1, input.targetFps);
    const int minFps = std::max(1, std::min(input.minFps, targetFps));
    const int baseLongEdgePx = std::max(0, input.basePreviewLongEdgePx);
    const int lookAroundMs = std::clamp(input.predictiveLookAroundMs, 200, 8000);
    const int sampleStepMs = std::clamp(input.predictiveSampleStepMs, 16, 1000);
    const int cacheMaxFrames = std::clamp(input.predictiveCacheMaxFrames, 4, 240);

    FrameBudgetDecision decision;
    decision.overloadScore = 0;
    decision.previewFps = std::clamp(targetFps, minFps, targetFps);
    decision.previewLongEdgePx = evenPreviewLongEdge(baseLongEdgePx);
    decision.predictiveLookAroundMs = lookAroundMs;
    decision.predictiveSampleStepMs = sampleStepMs;
    decision.predictiveCacheMaxFrames = cacheMaxFrames;
    decision.bypassOverlayComposition = false;
    const bool lowMemoryPlaybackBudget =
        input.playing &&
        (cacheMaxFrames <= 4 || targetFps <= 18 || baseLongEdgePx <= 320);
    decision.allowPredictivePrefetch =
        input.predictiveCachingEnabled && !lowMemoryPlaybackBudget;
    return decision;
}

FrameBudgetDecision FrameBudgetController::applyPressureTier(
    const FrameBudgetInput& input,
    int overloadScore) {
    FrameBudgetDecision decision = baseDecision(input);
    decision.overloadScore = std::clamp(overloadScore, 0, 12);

    const int targetFps = std::max(1, input.targetFps);
    const int minFps = std::max(1, std::min(input.minFps, targetFps));
    const int baseLongEdgePx = std::max(0, input.basePreviewLongEdgePx);
    const int baseLookAroundMs = std::clamp(input.predictiveLookAroundMs, 200, 8000);
    const int baseSampleStepMs = std::clamp(input.predictiveSampleStepMs, 16, 1000);
    const int baseCacheMaxFrames = std::clamp(input.predictiveCacheMaxFrames, 4, 240);
    const bool lowMemoryPlaybackBudget =
        input.playing &&
        (baseCacheMaxFrames <= 4 || targetFps <= 18 || baseLongEdgePx <= 320);

    double scale = 1.0;
    if (decision.overloadScore >= 8) {
        decision.previewFps = minFps;
        decision.predictiveLookAroundMs = std::clamp(std::min(baseLookAroundMs, 500), 200, baseLookAroundMs);
        decision.predictiveSampleStepMs = std::clamp(std::max(baseSampleStepMs, 220), 16, 1000);
        decision.predictiveCacheMaxFrames = std::clamp(std::min(baseCacheMaxFrames, 10), 4, baseCacheMaxFrames);
        decision.bypassOverlayComposition = input.playing;
        decision.allowPredictivePrefetch = false;
        scale = 0.70;
    } else if (decision.overloadScore >= 5) {
        decision.previewFps = std::clamp((targetFps + minFps) / 2, minFps, targetFps);
        decision.predictiveLookAroundMs = std::clamp(std::min(baseLookAroundMs, 900), 200, baseLookAroundMs);
        decision.predictiveSampleStepMs = std::clamp(std::max(baseSampleStepMs, 180), 16, 1000);
        decision.predictiveCacheMaxFrames = std::clamp(std::min(baseCacheMaxFrames, 16), 4, baseCacheMaxFrames);
        decision.allowPredictivePrefetch =
            input.predictiveCachingEnabled && !input.playing && !lowMemoryPlaybackBudget;
        scale = 0.82;
    } else if (decision.overloadScore >= 3) {
        decision.previewFps = std::clamp(24, minFps, targetFps);
        decision.predictiveLookAroundMs = std::clamp(std::min(baseLookAroundMs, 1200), 200, baseLookAroundMs);
        decision.predictiveSampleStepMs = std::clamp(std::max(baseSampleStepMs, 150), 16, 1000);
        decision.predictiveCacheMaxFrames = std::clamp(std::min(baseCacheMaxFrames, 24), 4, baseCacheMaxFrames);
        decision.allowPredictivePrefetch =
            input.predictiveCachingEnabled && !lowMemoryPlaybackBudget;
        scale = 0.90;
    }

    if (baseLongEdgePx > 0 && scale < 1.0) {
        const int scaled = static_cast<int>(std::lround(static_cast<double>(baseLongEdgePx) * scale));
        const int minLongEdgePx = std::min(240, baseLongEdgePx);
        decision.previewLongEdgePx = evenPreviewLongEdge(
            std::clamp(scaled, minLongEdgePx, baseLongEdgePx));
    }

    return decision;
}

FrameBudgetDecision FrameBudgetController::reset(const FrameBudgetInput& input) {
    m_smoothedRenderCostMs = 0;
    m_decision = baseDecision(input);
    return m_decision;
}

FrameBudgetDecision FrameBudgetController::update(const FrameBudgetInput& input) {
    if (!input.adaptiveEnabled) {
        return reset(input);
    }

    const int targetFps = std::max(1, input.targetFps);
    const int minFps = std::max(1, std::min(input.minFps, targetFps));
    const int64_t targetIntervalMs = frameIntervalMs(targetFps);
    const int64_t minIntervalMs = frameIntervalMs(minFps);
    const int64_t clampedRenderCostMs = std::max<int64_t>(0, input.renderCostMs);

    if (m_smoothedRenderCostMs <= 0) {
        m_smoothedRenderCostMs = clampedRenderCostMs;
    } else {
        m_smoothedRenderCostMs = ((m_smoothedRenderCostMs * 4) + clampedRenderCostMs) / 5;
    }

    int overloadScore = m_decision.overloadScore;
    if (m_smoothedRenderCostMs > (minIntervalMs + 8)) {
        overloadScore += 3;
    } else if (m_smoothedRenderCostMs > (targetIntervalMs + 4)) {
        overloadScore += 1;
    } else if (m_smoothedRenderCostMs < std::max<int64_t>(4, targetIntervalMs - 6)) {
        overloadScore -= 1;
    }

    if (input.playing && input.queuedFrames <= 0 && clampedRenderCostMs > targetIntervalMs) {
        overloadScore += 1;
    }

    overloadScore = std::clamp(overloadScore, 0, 12);
    m_decision = applyPressureTier(input, overloadScore);
    return m_decision;
}

} // namespace VideoEngine::Performance

#pragma once

#include <cstdint>

namespace VideoEngine::Performance {

struct FrameBudgetInput {
    bool adaptiveEnabled = true;
    bool playing = false;
    bool predictiveCachingEnabled = true;
    int64_t renderCostMs = 0;
    int targetFps = 30;
    int minFps = 15;
    int basePreviewLongEdgePx = 640;
    int predictiveLookAroundMs = 2000;
    int predictiveSampleStepMs = 120;
    int predictiveCacheMaxFrames = 40;
    int queuedFrames = 0;
};

struct FrameBudgetDecision {
    int overloadScore = 0;
    int previewFps = 30;
    int previewLongEdgePx = 640;
    int predictiveLookAroundMs = 2000;
    int predictiveSampleStepMs = 120;
    int predictiveCacheMaxFrames = 40;
    bool bypassOverlayComposition = false;
    bool allowPredictivePrefetch = true;
};

/**
 * Central preview budget controller.
 *
 * Converts measured render cost into one decision used by playback pacing,
 * preview decode scale, overlay composition, and predictive cache pressure.
 */
class FrameBudgetController {
public:
    FrameBudgetDecision reset(const FrameBudgetInput& input = FrameBudgetInput{});
    FrameBudgetDecision update(const FrameBudgetInput& input);
    FrameBudgetDecision decision() const { return m_decision; }

private:
    static int frameIntervalMs(int fps);
    static int evenPreviewLongEdge(int longEdgePx);
    static FrameBudgetDecision baseDecision(const FrameBudgetInput& input);
    static FrameBudgetDecision applyPressureTier(
        const FrameBudgetInput& input,
        int overloadScore);

    int64_t m_smoothedRenderCostMs = 0;
    FrameBudgetDecision m_decision;
};

} // namespace VideoEngine::Performance

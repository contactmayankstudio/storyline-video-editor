#include "timeline.h"
#include <algorithm>
#include <string>

namespace VideoEngine {

Timeline::Timeline()
    : m_videoProps(),
      m_audioProps() {}

void Timeline::clear() {
    m_clips.clear();
    m_textOverlays.clear();
    m_nextOverlayId = 1;
}

void Timeline::addClip(const std::shared_ptr<Clip>& clip) {
    m_clips.push_back(clip);
}

void Timeline::removeClip(const std::string& clipId) {
    // timeline.h exposes removal by string id for compatibility with some
    // project formats. Compare against Clip::getId() by stringifying it.
    m_clips.erase(
        std::remove_if(
            m_clips.begin(),
            m_clips.end(),
            [&](const std::shared_ptr<Clip>& c) {
                if (!c) return false;
                return std::to_string(c->getId()) == clipId;
            }),
        m_clips.end());
}

// ========== Text Overlay Management ==========

int64_t Timeline::addTextOverlay(const TextOverlay& overlay) {
    TextOverlay newOverlay = overlay;
    newOverlay.id = m_nextOverlayId;
    m_textOverlays[m_nextOverlayId] = newOverlay;
    return m_nextOverlayId++;
}

void Timeline::removeTextOverlay(int64_t overlayId) {
    m_textOverlays.erase(overlayId);
}

std::vector<TextOverlay> Timeline::getAllTextOverlays() const {
    std::vector<TextOverlay> result;
    for (const auto& pair : m_textOverlays) {
        result.push_back(pair.second);
    }
    return result;
}

std::vector<TextOverlay> Timeline::getActiveTextOverlaysAtTime(TimeMs timeMs) const {
    std::vector<TextOverlay> active;

    // Filter overlays that are active at this time
    for (const auto& pair : m_textOverlays) {
        const TextOverlay& overlay = pair.second;
        
        // Check if overlay is enabled
        if (!overlay.enabled)
            continue;
        
        // Check if we're within the start/end time window
        // Active if: startTime <= timeMs && (endTime == -1 || timeMs < endTime)
        if (timeMs < overlay.startTime)
            continue;
        
        if (overlay.endTime != -1 && timeMs >= overlay.endTime)
            continue;
        
        // This overlay is active at this time
        active.push_back(overlay);
    }

    // Sort by zOrder (ascending, lowest renders first)
    std::sort(active.begin(), active.end(),
        [](const TextOverlay& a, const TextOverlay& b) {
            return a.zOrder < b.zOrder;
        });

    return active;
}
const std::vector<std::shared_ptr<Clip>>& Timeline::clips() const {
    return m_clips;
}

TimeMs Timeline::getDuration() const {
    TimeMs maxEnd = 0;
    for (const auto& c : m_clips) {
        if (!c) continue;
        TimeMs end = c->getStartTime() + c->getDuration();
        if (end > maxEnd) maxEnd = end;
    }
    for (const auto& pair : m_textOverlays) {
        const TextOverlay& ov = pair.second;
        if (!ov.enabled) continue;
        TimeMs end = (ov.endTime > 0) ? ov.endTime : ov.startTime;
        if (end > maxEnd) maxEnd = end;
    }
    return maxEnd;
}

std::vector<std::shared_ptr<Clip>> Timeline::getActiveClipsAtTime(TimeMs timeMs) const {
    std::vector<std::shared_ptr<Clip>> result;
    for (const auto& c : m_clips) {
        if (!c || !c->getProperties().enabled) continue;
        const TimeMs s = c->getStartTime();
        const TimeMs e = s + c->getDuration();
        if (timeMs >= s && timeMs < e)
            result.push_back(c);
    }
    std::sort(result.begin(), result.end(),
        [](const std::shared_ptr<Clip>& a, const std::shared_ptr<Clip>& b) {
            return a->getTrackZOrder() < b->getTrackZOrder();
        });
    return result;
}

bool Timeline::hasOverlap(const Clip& candidate, int excludeClipId) const {
    const TimeMs cStart = candidate.getStartTime();
    const TimeMs cEnd   = cStart + candidate.getDuration();
    for (const auto& c : m_clips) {
        if (!c) continue;
        if (static_cast<int>(c->getId()) == excludeClipId) continue;
        if (c->getTrackRole() != candidate.getTrackRole()) continue;
        if (c->getTrackLane() != candidate.getTrackLane()) continue;
        const TimeMs s = c->getStartTime();
        const TimeMs e = s + c->getDuration();
        if (cStart < e && cEnd > s) return true;
    }
    return false;
}

TimeMs Timeline::frameToMs(uint64_t frameNum) const {
    if (m_videoProps.frameRate == 0) return 0;
    return static_cast<TimeMs>((frameNum * 1000ULL) / m_videoProps.frameRate);
}

uint64_t Timeline::msToFrame(TimeMs ms) const {
    if (m_videoProps.frameRate == 0) return 0;
    return static_cast<uint64_t>((static_cast<uint64_t>(ms) * m_videoProps.frameRate) / 1000ULL);
}

} // namespace VideoEngine

#include "SmartCache.h"

#include <algorithm>
#include <sstream>

namespace VideoEngine::Performance {

SmartCache::~SmartCache() {
    stopBackgroundRender();
}

void SmartCache::markHeavySegment(int64_t startMs, int64_t endMs) {
    if (endMs <= startMs) return;
    std::lock_guard<std::mutex> lock(m_mutex);

    Segment merged{startMs, endMs};
    std::vector<Segment> nextSegments;
    for (const auto& segment : m_heavySegments) {
        if (segment.endMs < merged.startMs || segment.startMs > merged.endMs) {
            nextSegments.push_back(segment);
            continue;
        }
        merged.startMs = std::min(merged.startMs, segment.startMs);
        merged.endMs = std::max(merged.endMs, segment.endMs);
    }
    nextSegments.push_back(merged);
    std::sort(nextSegments.begin(), nextSegments.end(), [](const Segment& a, const Segment& b) {
        return a.startMs < b.startMs;
    });
    m_heavySegments = std::move(nextSegments);
    m_dirty = true;
    m_cv.notify_all();
}

void SmartCache::startBackgroundRender() {
    bool expected = false;
    if (!m_running.compare_exchange_strong(expected, true)) {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_dirty = true;
        m_cv.notify_all();
        return;
    }
    m_worker = std::thread(&SmartCache::workerLoop, this);
}

void SmartCache::stopBackgroundRender() {
    if (!m_running.exchange(false)) return;
    m_cv.notify_all();
    if (m_worker.joinable()) {
        m_worker.join();
    }
}

std::string SmartCache::getCachedSegment(int64_t timeMs) {
    std::lock_guard<std::mutex> lock(m_mutex);
    auto it = m_cacheIndex.upper_bound(timeMs);
    if (it != m_cacheIndex.begin()) {
        --it;
        const auto segIt = std::find_if(m_heavySegments.begin(), m_heavySegments.end(), [&](const Segment& segment) {
            return segment.startMs == it->first && timeMs >= segment.startMs && timeMs <= segment.endMs;
        });
        if (segIt != m_heavySegments.end()) {
            return it->second;
        }
    }
    return "";
}

void SmartCache::reset() {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_heavySegments.clear();
    m_cacheIndex.clear();
    m_dirty = false;
}

void SmartCache::workerLoop() {
    while (m_running.load()) {
        std::vector<Segment> segments;
        {
            std::unique_lock<std::mutex> lock(m_mutex);
            m_cv.wait(lock, [&]() { return !m_running.load() || m_dirty; });
            if (!m_running.load()) break;
            segments = m_heavySegments;
            m_dirty = false;
        }

        std::map<int64_t, std::string> nextIndex;
        for (const auto& segment : segments) {
            std::ostringstream cacheKey;
            cacheKey << "smartcache://" << segment.startMs << "-" << segment.endMs;
            nextIndex[segment.startMs] = cacheKey.str();
        }

        std::lock_guard<std::mutex> lock(m_mutex);
        m_cacheIndex = std::move(nextIndex);
    }
}

}  // namespace VideoEngine::Performance

#include "FramePrefetcher.h"
#include <chrono>
#include <cmath>
#include <unistd.h>

namespace VideoEngine::Performance {

namespace {

bool fileExists(const std::string& path) {
    return !path.empty() && access(path.c_str(), F_OK) == 0;
}

}

FramePrefetcher::FramePrefetcher() {
}

FramePrefetcher::~FramePrefetcher() {
    stop();
}

void FramePrefetcher::start(const std::vector<std::shared_ptr<Clip>>& clips, int64_t startTimeMs, float speed) {
    stop();
    
    {
        std::lock_guard<std::mutex> lock(m_clipsMutex);
        m_activeClips = clips;
    }
    
    m_currentTimeMs.store(startTimeMs);
    m_speed.store(speed);
    m_running.store(true);
    
    m_workerThread = std::thread(&FramePrefetcher::workerLoop, this);
}

void FramePrefetcher::stop() {
    m_running.store(false);
    m_cv.notify_all();
    if (m_workerThread.joinable()) {
        m_workerThread.join();
    }
    
    std::lock_guard<std::mutex> lock(m_cacheMutex);
    m_frameCache.clear();
    m_decoderStates.clear();
}

void FramePrefetcher::updatePlaybackTime(int64_t timeMs) {
    m_currentTimeMs.store(timeMs);
    m_cv.notify_one(); // Wake up worker to check if we need more frames
}

std::shared_ptr<CachedFrame> FramePrefetcher::getFrame(uint32_t clipId, int64_t timeMs) {
    std::lock_guard<std::mutex> lock(m_cacheMutex);
    
    auto it = m_frameCache.find(clipId);
    if (it == m_frameCache.end() || !it->second) return nullptr;
    
    // Find exact or closest preceding frame
    auto frameOpt = it->second->find_last_if([timeMs](const std::shared_ptr<CachedFrame>& frame) {
        return frame && frame->timelineMs <= timeMs;
    });
    
    if (frameOpt.has_value()) {
        return frameOpt.value();
    }
    
    return nullptr;
}

void FramePrefetcher::workerLoop() {
    while (m_running.load()) {
        std::vector<std::shared_ptr<Clip>> clips;
        {
            std::lock_guard<std::mutex> lock(m_clipsMutex);
            clips = m_activeClips;
        }
        
        int64_t currentTime = m_currentTimeMs.load();
        const float speed = std::max(0.1f, std::abs(m_speed.load()));
        const int64_t backwardLookMs = 120;
        const int64_t forwardLookMs = speed >= 1.5f ? 1400 : 900;
        const int64_t sampleStepMs = speed >= 1.5f ? 66 : 48;
        
        for (auto& clip : clips) {
            if (!isEligibleClip(clip)) {
                continue;
            }

            const uint32_t clipId = clip->getId();
            const int64_t clipStartMs = clip->getStartTime();
            const int64_t clipEndMs = clip->getEndTime();
            if (clipEndMs <= clipStartMs) {
                continue;
            }

            auto& decoderState = m_decoderStates[clipId];
            if (!ensureDecoder(clipId, clip, decoderState)) {
                continue;
            }

            const int64_t requestStartMs = std::max<int64_t>(clipStartMs, currentTime - backwardLookMs);
            const int64_t requestEndMs = std::min<int64_t>(clipEndMs - 1, currentTime + forwardLookMs);
            if (requestEndMs < requestStartMs) {
                continue;
            }

            for (int64_t targetTimelineMs = requestStartMs; targetTimelineMs <= requestEndMs; targetTimelineMs += sampleStepMs) {
                {
                    std::lock_guard<std::mutex> lock(m_cacheMutex);
                    auto clipIt = m_frameCache.find(clipId);
                    if (clipIt != m_frameCache.end() && clipIt->second) {
                        auto frameOpt = clipIt->second->find_last_if([targetTimelineMs, &decoderState](const std::shared_ptr<CachedFrame>& frame) {
                            return frame && std::llabs(frame->timelineMs - targetTimelineMs) <= decoderState.approximateFrameMs;
                        });
                        if (frameOpt.has_value()) {
                            continue;
                        }
                    }
                }

                const int64_t sourceSeekMs = mapTimelineToSourceMs(clip, targetTimelineMs, decoderState.mediaDurationMs);
                if (!decoderState.decoder->seekForPreview(sourceSeekMs)) {
                    continue;
                }

                Backend::DecodedFrame decodedFrame;
                if (!decoderState.decoder->decodeNextFrame(decodedFrame)) {
                    continue;
                }

                auto frame = std::make_shared<CachedFrame>();
                frame->clipId = clipId;
                frame->timelineMs = targetTimelineMs;
                frame->sourcePtsMs = clampSourceTimeMs(
                    decodedFrame.ptsMs > 0 ? decodedFrame.ptsMs : sourceSeekMs,
                    decoderState.mediaDurationMs);
                decodedFrame.ptsMs = frame->sourcePtsMs;
                frame->frame = std::move(decodedFrame);

                {
                    std::lock_guard<std::mutex> lock(m_cacheMutex);
                    if (m_frameCache.find(clipId) == m_frameCache.end() || !m_frameCache[clipId]) {
                        m_frameCache[clipId] = std::make_shared<RingBuffer<std::shared_ptr<CachedFrame>>>(40);
                    }
                    m_frameCache[clipId]->push(frame);
                }
            }
        }
        
        // Wait for next update or timeout
        std::unique_lock<std::mutex> lock(m_clipsMutex);
        m_cv.wait_for(lock, std::chrono::milliseconds(100));
    }
}

bool FramePrefetcher::isEligibleClip(const std::shared_ptr<Clip>& clip) {
    if (!clip || clip->getMediaType() != Clip::MediaType::Video) {
        return false;
    }
    const auto& props = clip->getProperties();
    return !props.reversePlayback &&
        !props.freezeFrameEnabled &&
        std::fabs(props.playbackSpeed - 1.0f) <= 0.001f &&
        props.curveSpeedProfile == "linear";
}

std::string FramePrefetcher::resolveDecoderPath(const std::shared_ptr<Clip>& clip) {
    if (!clip) {
        return {};
    }
    const std::string& proxyPath = clip->getPreviewProxyPath();
    if (fileExists(proxyPath)) {
        return proxyPath;
    }
    return clip->getMediaPath();
}

int64_t FramePrefetcher::clampSourceTimeMs(int64_t sourceMs, int64_t durationMs) {
    if (sourceMs < 0) {
        return 0;
    }
    if (durationMs <= 1) {
        return 0;
    }
    if (sourceMs >= durationMs) {
        return durationMs - 1;
    }
    return sourceMs;
}

int64_t FramePrefetcher::mapTimelineToSourceMs(const std::shared_ptr<Clip>& clip, int64_t timelineMs, int64_t mediaDurationMs) {
    if (!clip) {
        return 0;
    }
    int64_t sourceInMs = 0;
    int64_t sourceOutMs = 0;
    clip->getTrimPoints(sourceInMs, sourceOutMs);
    const int64_t localTimelineMs = std::max<int64_t>(0, timelineMs - clip->getStartTime());
    const int64_t sourceMs = sourceInMs + localTimelineMs;
    const int64_t trimmedSourceMs =
        sourceOutMs > sourceInMs ? std::min<int64_t>(sourceMs, sourceOutMs - 1) : sourceMs;
    return clampSourceTimeMs(trimmedSourceMs, mediaDurationMs);
}

bool FramePrefetcher::ensureDecoder(uint32_t clipId, const std::shared_ptr<Clip>& clip, ClipDecoderState& state) {
    const std::string decoderPath = resolveDecoderPath(clip);
    if (decoderPath.empty()) {
        return false;
    }
    if (state.decoder && state.openPath == decoderPath) {
        return true;
    }
    auto decoder = std::make_unique<Backend::VideoDecoder>();
    if (!decoder->open(decoderPath)) {
        state.decoder.reset();
        state.openPath.clear();
        state.mediaDurationMs = 0;
        state.approximateFrameMs = 33;
        return false;
    }
    state.mediaDurationMs = static_cast<int64_t>(decoder->getDuration() * 1000.0);
    const double fps = decoder->getFps();
    state.approximateFrameMs = fps > 0.0
        ? std::max<int64_t>(1, static_cast<int64_t>(std::llround(1000.0 / fps)))
        : 33;
    state.openPath = decoderPath;
    state.decoder = std::move(decoder);
    {
        std::lock_guard<std::mutex> lock(m_cacheMutex);
        m_frameCache.erase(clipId);
    }
    return true;
}

} // namespace VideoEngine::Performance

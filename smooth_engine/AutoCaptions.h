#pragma once

#include <cstdint>
#include <vector>
#include <string>

namespace VideoEngine::AI {

/**
 * @brief AI Auto-Captions (Speech-to-Text).
 * 
 * Works 100% OFFLINE using Whisper-style quantized models.
 * Automatically generates timestamped subtitles from the audio track.
 */
class AutoCaptions {
public:
    struct Caption {
        int64_t startMs;
        int64_t endMs;
        std::string text;
    };

    /**
     * @brief Generates captions for the entire timeline.
     */
    std::vector<Caption> generate(const std::string& audioPath);

private:
    void* m_sttModel = nullptr; // e.g., Whisper.cpp instance
};

} // namespace VideoEngine::AI

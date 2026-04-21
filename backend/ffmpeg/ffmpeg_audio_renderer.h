#pragma once

#include <string>
#include <memory>
#include <vector>
#include <cstdint>
#include <functional>
#include <stdexcept>
#include "engine/engine.h"

namespace VideoEngine::Backend {

/**
 * AudioRenderer handles audio decoding, resampling, effect application,
 * and encoding for the video engine.
 * 
 * Features:
 * - Multi-codec decoding (AAC, MP3, WAV via libavcodec)
 * - Audio resampling to 48kHz stereo FLTP via libswresample
 * - Per-clip volume control and fade effects
 * - AAC audio encoding
 * - Audio packet generation ready for video muxing
 */
class AudioRenderer {
public:
    struct AudioConfig {
        std::string outputCodec = "aac";      // Audio codec
        int sampleRate = 48000;               // Hz (typical video audio)
        int channels = 2;                      // Stereo
        int bitrate = 128;                     // kbps
        int maxDecodeBufferFrames = 4096;     // Decode buffer size
    };

    struct AudioFrame {
        std::vector<std::vector<float>> samples;  // [channel][sample]
        int sampleRate = 0;
        int channels = 0;
        int64_t ptsMs = 0;
    };

    struct DecodedAudioSegment {
        std::vector<std::vector<float>> samples;  // [channel][sample] after resampling
        int sampleRate = 0;
        int channels = 0;
        TimeMs startTimeMs = 0;
        TimeMs endTimeMs = 0;
    };

    using ProgressCallback = std::function<void(int clipIdx, int totalClips)>;

    /**
     * Construct audio renderer with target audio properties.
     * Initializes FFmpeg audio codecs.
     */
    explicit AudioRenderer(const AudioConfig& config);
    
    // Convenience constructor with default config
    AudioRenderer();
    ~AudioRenderer();

    // Non-copyable, movable
    AudioRenderer(const AudioRenderer&) = delete;
    AudioRenderer& operator=(const AudioRenderer&) = delete;
    AudioRenderer(AudioRenderer&&) noexcept = default;
    AudioRenderer& operator=(AudioRenderer&&) noexcept = default;

    /**
     * Decode a single audio clip file.
     * Handles multiple audio codecs (AAC, MP3, WAV).
     * Resamples to target format (48kHz stereo FLTP).
     * 
     * @param clipFilePath Path to audio file
     * @param startOffsetMs Trim start (0 to decode from beginning)
     * @param durationMs Length to decode (0 to decode full)
     * @return Resampled audio with proper metadata
     * @throws std::runtime_error on decode errors
     */
    DecodedAudioSegment decodeAudioClip(const std::string& clipFilePath,
                                       TimeMs startOffsetMs = 0,
                                       TimeMs durationMs = 0);

    /**
     * Apply volume and fade effects to audio segment.
     * Modifies samples in-place.
     * 
     * @param segment Audio segment to modify
     * @param clipStartTimeMs Clip's start time in timeline
     * @param effects Effect chain from clip
     */
    void applyEffects(DecodedAudioSegment& segment,
                     TimeMs clipStartTimeMs,
                     const std::vector<std::shared_ptr<Effect>>& effects);

    /**
     * Mix multiple audio segments into a single output at a given time range.
     * Handles overlapping clips, volume mixing, and fade effects.
     * Output is ready for encoding.
     * 
     * @param segments Audio segments from visible clips
     * @param startTimeMs Time range start
     * @param endTimeMs Time range end (endTimeMs - startTimeMs = output duration)
     * @return Mixed audio frame ready for encoding
     */
    AudioFrame mixAudioSegments(const std::vector<DecodedAudioSegment>& segments,
                               TimeMs startTimeMs,
                               TimeMs endTimeMs);

    /**
     * Encode audio frame to AAC and generate packet.
     * For use in muxing with video.
     * 
     * @param frame Audio frame to encode
     * @param ptsMs Presentation timestamp (milliseconds)
     * @return Encoded packet data + metadata (nullptr if no packet yet)
     */
    struct EncodedAudioPacket {
        std::vector<uint8_t> data;
        int64_t ptsMs = 0;
        int64_t durationMs = 0;
    };

    std::shared_ptr<EncodedAudioPacket> encodeAudioFrame(const AudioFrame& frame,
                                                        int64_t ptsMs);

    /**
     * Flush audio encoder and retrieve final packets.
     * Call after all frames are encoded.
     * 
     * @return Vector of final encoded packets
     */
    std::vector<std::shared_ptr<EncodedAudioPacket>> flushAudioEncoder();

    /**
     * Generate audio track for entire timeline.
     * Integrates decoding, mixing, effects, and encoding.
     * 
     * @param timeline Timeline to extract audio clips from
     * @param outputFile Output AAC file path (for debugging)
     * @param progress Optional progress callback
     * @return Vector of all encoded audio packets (ordered by timestamp)
     */
    std::vector<std::shared_ptr<EncodedAudioPacket>> generateAudioTrack(
        const Timeline& timeline,
        const std::string& outputFile = "",
        ProgressCallback progress = nullptr);

    // Get audio configuration
    const AudioConfig& getConfig() const { return config_; }

private:
    AudioConfig config_;

    // FFmpeg audio context (forward-declared, defined in cpp)
    struct FFmpegAudioContext;
    std::unique_ptr<FFmpegAudioContext> ctx_;

    // Internal: initialize global FFmpeg audio state
    static bool initializeFFmpegAudio();
    static bool ffmpegAudioInitialized_;
};

class AudioRendererException : public std::runtime_error {
public:
    explicit AudioRendererException(const std::string& msg)
        : std::runtime_error(msg) {}
};

} // namespace VideoEngine::Backend

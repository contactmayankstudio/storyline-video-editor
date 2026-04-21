#include "clip.h"
#include <cassert>
#include <algorithm>
#include <atomic>

namespace VideoEngine {

// Static member initialization
std::atomic<uint32_t> Clip::nextId_ = 1;

// ============ Constructor ============
Clip::Clip(const std::string& mediaPath, TimeMs startTimeMs, TimeMs durationMs)
    : mediaPath_(mediaPath),
      id_(nextId_++),
      startTimeMs_(startTimeMs),
      durationMs_(durationMs) {
    
    mediaType_ = detectMediaType(mediaPath);
    if (mediaType_ == MediaType::Audio) {
        trackRole_ = TrackRole::Audio;
    } else {
        trackRole_ = TrackRole::MainVideo;
    }
    
    // If duration not specified, will be calculated from actual media on render
    if (durationMs_ == 0) {
        durationMs_ = 1000;  // Placeholder: actual duration loaded from FFmpeg layer
    }
    // sourceOutPointMs_ defaults to match duration so getTrimPoints() is always valid
    sourceOutPointMs_ = durationMs_;
}

// ============ Helper: Detect Media Type ============
Clip::MediaType Clip::detectMediaType(const std::string& path) {
    // Extract file extension
    size_t dotPos = path.find_last_of('.');
    if (dotPos == std::string::npos) {
        return MediaType::Unknown;
    }

    std::string ext = path.substr(dotPos + 1);
    
    // Convert to lowercase for comparison
    std::transform(ext.begin(), ext.end(), ext.begin(),
                  [](unsigned char c) { return std::tolower(c); });

    // Video formats
    if (ext == "mp4" || ext == "mov" || ext == "avi" || ext == "mkv" ||
        ext == "webm" || ext == "flv" || ext == "wmv" || ext == "m4v" ||
        ext == "3gp" || ext == "3gpp" || ext == "ts" || ext == "mts" ||
        ext == "m2ts" || ext == "mpeg" || ext == "mpg") {
        return MediaType::Video;
    }

    // Audio formats
    if (ext == "mp3" || ext == "aac" || ext == "wav" || ext == "flac" || 
        ext == "ogg" || ext == "m4a" || ext == "wma") {
        return MediaType::Audio;
    }

    // Image formats
    if (ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "gif" || 
        ext == "bmp" || ext == "webp" || ext == "tiff") {
        return MediaType::Image;
    }

    return MediaType::Unknown;
}

} // namespace VideoEngine

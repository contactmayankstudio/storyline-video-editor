#include "project.h"
#include "../core/timeline.h"
#include <fstream>
#include <sstream>
#include <ctime>
#include <iomanip>
#include <iostream>
#include <cctype>
#include <stdexcept>
#include <algorithm>

namespace VideoEngine {

// Simple JSON building without external dependencies
namespace {

std::string escapeJSON(const std::string& str) {
    std::string result;
    for (char c : str) {
        switch (c) {
            case '"': result += "\\\""; break;
            case '\\': result += "\\\\"; break;
            case '\b': result += "\\b"; break;
            case '\f': result += "\\f"; break;
            case '\n': result += "\\n"; break;
            case '\r': result += "\\r"; break;
            case '\t': result += "\\t"; break;
            default:
                if (c < 32) {
                    result += "\\u" + std::to_string((int)c);
                } else {
                    result += c;
                }
        }
    }
    return result;
}

bool hasJsonKey(const std::string& json, const std::string& key) {
    return json.find("\"" + key + "\"") != std::string::npos;
}

std::string getCurrentTimestamp() {
    auto now = std::time(nullptr);
    auto tm = *std::localtime(&now);
    std::ostringstream oss;
    oss << std::put_time(&tm, "%Y-%m-%d %H:%M:%S");
    return oss.str();
}

std::string trackRoleToString(VideoEngine::Clip::TrackRole role) {
    switch (role) {
        case VideoEngine::Clip::TrackRole::MainVideo: return "VIDEO";
        case VideoEngine::Clip::TrackRole::Overlay: return "OVERLAY";
        case VideoEngine::Clip::TrackRole::TextSticker: return "TEXT";
        case VideoEngine::Clip::TrackRole::Audio: return "AUDIO";
        default: return "VIDEO";
    }
}

}  // anonymous namespace

// ============ Project Implementation ============

Project::Project(std::shared_ptr<Timeline> timeline) {
    if (!timeline) {
        setError("Timeline is null");
        return;
    }

    // Get timeline metadata
    metadata_.version = "1.1";
    metadata_.createdDate = getCurrentTimestamp();
    metadata_.lastModifiedDate = getCurrentTimestamp();
    metadata_.name = "Untitled Project";
    metadata_.fps = 30;
    metadata_.width = 1920;
    metadata_.height = 1080;

    // Capture all clips
    auto allClips = timeline->clips();
    for (const auto& clip : allClips) {
        ClipEntry entry;
        entry.id = clip->getId();
        entry.mediaPath = clip->getMediaPath();
        entry.startTimeMs = clip->getStartTime();
        entry.durationMs = clip->getDuration();
        entry.trackType = trackRoleToString(clip->getTrackRole());
        entry.trackLane = clip->getTrackLane();
        entry.zOrder = clip->getTrackZOrder();
        const auto& props = clip->getProperties();
        entry.opacity = props.opacity;
        entry.volumeGain = props.volumeGain;
        entry.enabled = props.enabled;
        entry.playbackSpeed = props.playbackSpeed;
        entry.reversePlayback = props.reversePlayback;
        entry.freezeFrameEnabled = props.freezeFrameEnabled;
        entry.freezeFrameTimeMs = props.freezeFrameTimeMs;
        entry.freezeFrameDurationMs = props.freezeFrameDurationMs;
        entry.curveSpeedProfile = props.curveSpeedProfile;
        entry.curveSpeedStrength = props.curveSpeedStrength;
        entry.effects.brightness = clip->getEffects().brightness;
        entry.effects.contrast = clip->getEffects().contrast;
        entry.effects.saturation = clip->getEffects().saturation;
        entry.effects.enabled = clip->getEffects().enabled;
        const auto& chroma = clip->getChromaKey();
        entry.chromaKey.enabled = chroma.enabled;
        entry.chromaKey.color =
            chroma.color == Clip::ChromaKeyParams::KeyColor::Blue ? 1 : 0;
        entry.chromaKey.similarity = chroma.similarity;
        entry.chromaKey.smoothness = chroma.smoothness;
        entry.chromaKey.spill = chroma.spill;
        clip->getTrimPoints(entry.sourceInMs, entry.sourceOutMs);
        if (entry.sourceOutMs <= entry.sourceInMs)
            entry.sourceOutMs = entry.sourceInMs + entry.durationMs;
        clips_.push_back(entry);
    }

    // Capture all text overlays
    auto allTexts = timeline->getAllTextOverlays();
    for (const auto& textOverlay : allTexts) {
        TextEntry entry;
        entry.id = textOverlay.id;
        entry.text = textOverlay.text;
        entry.x = textOverlay.x;
        entry.y = textOverlay.y;
        entry.scale = textOverlay.scale;
        entry.rotation = textOverlay.rotation;
        entry.color = textOverlay.color;
        entry.opacity = textOverlay.opacity;
        entry.fadeInMs = textOverlay.fadeInMs;
        entry.fadeOutMs = textOverlay.fadeOutMs;
        entry.zOrder = textOverlay.zOrder;
        entry.startTime = textOverlay.startTime;
        entry.endTime = textOverlay.endTime;
        entry.enabled = textOverlay.enabled;
        entry.keyframes = textOverlay.keyframes;
        textOverlays_.push_back(entry);
    }

    // Transitions are stored per-project (not on Timeline), preserved on load/save.

    std::cout << "Project created from timeline: " << clips_.size() << " clips, "
              << textOverlays_.size() << " text overlays" << std::endl;
}

bool Project::saveToFile(const std::string& filePath) const {
    try {
        std::string jsonStr = toJSON();
        std::ofstream file(filePath);
        if (!file.is_open()) {
            setError("Failed to open file for writing: " + filePath);
            return false;
        }
        file << jsonStr;
        file.close();
        std::cout << "Project saved to: " << filePath << std::endl;
        return true;
    } catch (const std::exception& e) {
        setError(std::string("Exception while saving: ") + e.what());
        return false;
    }
}

bool Project::loadFromFile(const std::string& filePath) {
    try {
        std::ifstream file(filePath);
        if (!file.is_open()) {
            setError("Failed to open file for reading: " + filePath);
            return false;
        }
        std::stringstream buffer;
        buffer << file.rdbuf();
        file.close();
        
        return fromJSON(buffer.str());
    } catch (const std::exception& e) {
        setError(std::string("Exception while loading: ") + e.what());
        return false;
    }
}

std::string Project::toJSON() const {
    std::ostringstream json;
    json << "{\n";
    json << "  \"version\": \"" << escapeJSON(metadata_.version) << "\",\n";
    json << "  \"name\": \"" << escapeJSON(metadata_.name) << "\",\n";
    json << "  \"createdDate\": \"" << escapeJSON(metadata_.createdDate) << "\",\n";
    json << "  \"lastModifiedDate\": \"" << escapeJSON(metadata_.lastModifiedDate) << "\",\n";
    json << "  \"width\": " << metadata_.width << ",\n";
    json << "  \"height\": " << metadata_.height << ",\n";
    json << "  \"fps\": " << metadata_.fps << ",\n";

    // Clips array
    json << "  \"clips\": [\n";
    for (size_t i = 0; i < clips_.size(); ++i) {
        const auto& clip = clips_[i];
        json << "    {\n";
        json << "      \"id\": " << clip.id << ",\n";
        json << "      \"mediaPath\": \"" << escapeJSON(clip.mediaPath) << "\",\n";
        json << "      \"startTimeMs\": " << clip.startTimeMs << ",\n";
        json << "      \"durationMs\": " << clip.durationMs << ",\n";
        json << "      \"sourceInMs\": " << clip.sourceInMs << ",\n";
        json << "      \"sourceOutMs\": " << clip.sourceOutMs << ",\n";
        json << "      \"trackType\": \"" << escapeJSON(clip.trackType) << "\",\n";
        json << "      \"trackLane\": " << clip.trackLane << ",\n";
        json << "      \"zOrder\": " << clip.zOrder << ",\n";
        json << "      \"opacity\": " << clip.opacity << ",\n";
        json << "      \"volumeGain\": " << clip.volumeGain << ",\n";
        json << "      \"enabled\": " << (clip.enabled ? "true" : "false") << ",\n";
        json << "      \"playback\": {\n";
        json << "        \"speed\": " << clip.playbackSpeed << ",\n";
        json << "        \"reverse\": " << (clip.reversePlayback ? "true" : "false") << ",\n";
        json << "        \"freezeEnabled\": " << (clip.freezeFrameEnabled ? "true" : "false") << ",\n";
        json << "        \"freezeTimeMs\": " << clip.freezeFrameTimeMs << ",\n";
        json << "        \"freezeDurationMs\": " << clip.freezeFrameDurationMs << ",\n";
        json << "        \"curveProfile\": \"" << escapeJSON(clip.curveSpeedProfile) << "\",\n";
        json << "        \"curveStrength\": " << clip.curveSpeedStrength << "\n";
        json << "      },\n";
        json << "      \"effects\": {\n";
        json << "        \"brightness\": " << clip.effects.brightness << ",\n";
        json << "        \"contrast\": " << clip.effects.contrast << ",\n";
        json << "        \"saturation\": " << clip.effects.saturation << ",\n";
        json << "        \"enabled\": " << (clip.effects.enabled ? "true" : "false") << "\n";
        json << "      },\n";
        json << "      \"chromaKey\": {\n";
        json << "        \"enabled\": " << (clip.chromaKey.enabled ? "true" : "false") << ",\n";
        json << "        \"color\": " << clip.chromaKey.color << ",\n";
        json << "        \"similarity\": " << clip.chromaKey.similarity << ",\n";
        json << "        \"smoothness\": " << clip.chromaKey.smoothness << ",\n";
        json << "        \"spill\": " << clip.chromaKey.spill << "\n";
        json << "      }\n";
        json << "    }";
        if (i < clips_.size() - 1) json << ",";
        json << "\n";
    }
    json << "  ],\n";

    // Text overlays array
    json << "  \"textOverlays\": [\n";
    for (size_t i = 0; i < textOverlays_.size(); ++i) {
        const auto& text = textOverlays_[i];
        json << "    {\n";
        json << "      \"id\": " << text.id << ",\n";
        json << "      \"text\": \"" << escapeJSON(text.text) << "\",\n";
        json << "      \"x\": " << text.x << ",\n";
        json << "      \"y\": " << text.y << ",\n";
        json << "      \"scale\": " << text.scale << ",\n";
        json << "      \"rotation\": " << text.rotation << ",\n";
        json << "      \"color\": \"0x" << std::hex << std::setfill('0') << std::setw(8) << text.color << std::dec << "\",\n";
        json << "      \"opacity\": " << text.opacity << ",\n";
        json << "      \"fadeInMs\": " << text.fadeInMs << ",\n";
        json << "      \"fadeOutMs\": " << text.fadeOutMs << ",\n";
        json << "      \"zOrder\": " << text.zOrder << ",\n";
        json << "      \"startTime\": " << text.startTime << ",\n";
        json << "      \"endTime\": " << text.endTime << ",\n";
        json << "      \"enabled\": " << (text.enabled ? "true" : "false") << ",\n";
        
        // Keyframes array
        json << "      \"keyframes\": [\n";
        for (size_t j = 0; j < text.keyframes.size(); ++j) {
            const auto& kf = text.keyframes[j];
            json << "        {\n";
            json << "          \"timeMs\": " << kf.timeMs << ",\n";
            json << "          \"posX\": " << kf.posX << ",\n";
            json << "          \"posY\": " << kf.posY << ",\n";
            json << "          \"scale\": " << kf.scale << ",\n";
            json << "          \"opacity\": " << kf.opacity << "\n";
            json << "        }";
            if (j < text.keyframes.size() - 1) json << ",";
            json << "\n";
        }
        json << "      ]\n";
        json << "    }";
        if (i < textOverlays_.size() - 1) json << ",";
        json << "\n";
    }
    json << "  ],\n";

    // Transitions array (simplified)
    json << "  \"transitions\": [\n";
    for (size_t i = 0; i < transitions_.size(); ++i) {
        const auto& trans = transitions_[i];
        json << "    {\n";
        json << "      \"id\": " << trans.id << ",\n";
        json << "      \"type\": \"" << escapeJSON(trans.type) << "\",\n";
        json << "      \"fromClipId\": " << trans.fromClipId << ",\n";
        json << "      \"toClipId\": " << trans.toClipId << ",\n";
        json << "      \"startTimeMs\": " << trans.startTimeMs << ",\n";
        json << "      \"durationMs\": " << trans.durationMs << "\n";
        json << "    }";
        if (i < transitions_.size() - 1) json << ",";
        json << "\n";
    }
    json << "  ]\n";

    json << "}\n";
    return json.str();
}

// Simple JSON parsing helpers (no external library)
namespace {

// Extract string value from JSON (e.g., "key": "value")
std::string extractStringValue(const std::string& json, const std::string& key) {
    std::string pattern = "\"" + key + "\": \"";
    size_t pos = json.find(pattern);
    if (pos == std::string::npos) return "";
    
    pos += pattern.length();
    size_t endPos = json.find("\"", pos);
    if (endPos == std::string::npos) return "";
    
    return json.substr(pos, endPos - pos);
}

// Extract number value from JSON (e.g., "key": 123)
int64_t extractInt64Value(const std::string& json, const std::string& key) {
    std::string pattern = "\"" + key + "\": ";
    size_t pos = json.find(pattern);
    if (pos == std::string::npos) return 0;
    
    pos += pattern.length();
    size_t endPos = pos;
    while (endPos < json.length() && (isdigit(json[endPos]) || json[endPos] == '-')) {
        endPos++;
    }
    
    if (endPos == pos) return 0;
    return std::stoll(json.substr(pos, endPos - pos));
}

// Extract double value from JSON (e.g., "key": 1.5)
double extractDoubleValue(const std::string& json, const std::string& key) {
    std::string pattern = "\"" + key + "\": ";
    size_t pos = json.find(pattern);
    if (pos == std::string::npos) return 0.0;
    
    pos += pattern.length();
    size_t endPos = pos;
    while (endPos < json.length() && (isdigit(json[endPos]) || json[endPos] == '-' || json[endPos] == '.')) {
        endPos++;
    }
    
    if (endPos == pos) return 0.0;
    return std::stod(json.substr(pos, endPos - pos));
}

// Extract hex color value (e.g., "color": "0x12345678")
uint32_t extractColorValue(const std::string& json, const std::string& key) {
    std::string hexStr = extractStringValue(json, key);
    if (hexStr.empty() || hexStr.substr(0, 2) != "0x") return 0xFFFFFFFF;
    
    try {
        return static_cast<uint32_t>(std::stoul(hexStr, nullptr, 16));
    } catch (...) {
        return 0xFFFFFFFF;
    }
}

// Extract boolean value from JSON (e.g., "key": true)
bool extractBoolValue(const std::string& json, const std::string& key) {
    std::string pattern = "\"" + key + "\": ";
    size_t pos = json.find(pattern);
    if (pos == std::string::npos) return false;
    
    pos += pattern.length();
    if (json.substr(pos, 4) == "true") return true;
    if (json.substr(pos, 5) == "false") return false;
    return false;
}

// Extract array of sub-objects (returns vector of JSON object strings)
std::vector<std::string> extractArrayObjects(const std::string& json, const std::string& arrayKey) {
    std::vector<std::string> result;
    std::string pattern = "\"" + arrayKey + "\": [";
    size_t pos = json.find(pattern);
    if (pos == std::string::npos) return result;
    
    pos += pattern.length();
    int braceCount = 0;
    size_t objStart = 0;
    bool inObject = false;
    
    for (size_t i = pos; i < json.length(); ++i) {
        if (json[i] == '{') {
            if (!inObject) {
                objStart = i;
                inObject = true;
            }
            braceCount++;
        } else if (json[i] == '}') {
            braceCount--;
            if (braceCount == 0 && inObject) {
                result.push_back(json.substr(objStart, i - objStart + 1));
                inObject = false;
            }
        } else if (json[i] == ']' && braceCount == 0) {
            break;
        }
    }
    
    return result;
}

std::string extractObjectValue(const std::string& json, const std::string& key) {
    std::string pattern = "\"" + key + "\": {";
    size_t pos = json.find(pattern);
    if (pos == std::string::npos) return {};

    pos = json.find('{', pos);
    if (pos == std::string::npos) return {};

    int braceCount = 0;
    for (size_t i = pos; i < json.length(); ++i) {
        if (json[i] == '{') {
            ++braceCount;
        } else if (json[i] == '}') {
            --braceCount;
            if (braceCount == 0) {
                return json.substr(pos, i - pos + 1);
            }
        }
    }
    return {};
}

}  // anonymous namespace

bool Project::fromJSON(const std::string& jsonStr) {
    try {
        // Clear existing data
        clips_.clear();
        textOverlays_.clear();
        transitions_.clear();

        // Parse metadata
        metadata_.version = extractStringValue(jsonStr, "version");
        metadata_.name = extractStringValue(jsonStr, "name");
        metadata_.createdDate = extractStringValue(jsonStr, "createdDate");
        metadata_.lastModifiedDate = extractStringValue(jsonStr, "lastModifiedDate");
        metadata_.width  = static_cast<int>(extractInt64Value(jsonStr, "width"));
        metadata_.height = static_cast<int>(extractInt64Value(jsonStr, "height"));
        metadata_.fps    = static_cast<int>(extractInt64Value(jsonStr, "fps"));
        // Defaults if missing from older project files
        if (metadata_.width  <= 0) metadata_.width  = 1920;
        if (metadata_.height <= 0) metadata_.height = 1080;
        if (metadata_.fps    <= 0) metadata_.fps    = 30;

        // Parse clips array
        auto clipObjects = extractArrayObjects(jsonStr, "clips");
        for (const auto& clipJson : clipObjects) {
            ClipEntry clip;
            clip.id = extractInt64Value(clipJson, "id");
            clip.mediaPath = extractStringValue(clipJson, "mediaPath");
            clip.startTimeMs = extractInt64Value(clipJson, "startTimeMs");
            clip.durationMs = extractInt64Value(clipJson, "durationMs");
            clip.trackType = extractStringValue(clipJson, "trackType");
            if (clip.trackType.empty()) {
                clip.trackType = "VIDEO";
            }
            clip.trackLane = static_cast<int32_t>(extractInt64Value(clipJson, "trackLane"));
            clip.zOrder = static_cast<int32_t>(extractInt64Value(clipJson, "zOrder"));
            clip.opacity = extractDoubleValue(clipJson, "opacity");
            clip.volumeGain = hasJsonKey(clipJson, "volumeGain")
                ? extractDoubleValue(clipJson, "volumeGain")
                : 1.0f;
            clip.enabled = extractBoolValue(clipJson, "enabled");
            clip.sourceInMs = extractInt64Value(clipJson, "sourceInMs");
            clip.sourceOutMs = extractInt64Value(clipJson, "sourceOutMs");
            if (clip.sourceOutMs <= clip.sourceInMs)
                clip.sourceOutMs = clip.sourceInMs + clip.durationMs;
            
            const std::string playbackJson = extractObjectValue(clipJson, "playback");
            if (!playbackJson.empty()) {
                clip.playbackSpeed = extractDoubleValue(playbackJson, "speed");
                clip.reversePlayback = extractBoolValue(playbackJson, "reverse");
                clip.freezeFrameEnabled = extractBoolValue(playbackJson, "freezeEnabled");
                clip.freezeFrameTimeMs = extractInt64Value(playbackJson, "freezeTimeMs");
                clip.freezeFrameDurationMs = extractInt64Value(playbackJson, "freezeDurationMs");
                clip.curveSpeedProfile = extractStringValue(playbackJson, "curveProfile");
                clip.curveSpeedStrength = extractDoubleValue(playbackJson, "curveStrength");
            } else {
                clip.playbackSpeed = 1.0f;
                clip.reversePlayback = extractBoolValue(clipJson, "reversePlayback");
                clip.freezeFrameEnabled = extractBoolValue(clipJson, "freezeFrameEnabled");
                clip.freezeFrameTimeMs = extractInt64Value(clipJson, "freezeFrameTimeMs");
                clip.freezeFrameDurationMs = extractInt64Value(clipJson, "freezeFrameDurationMs");
                clip.curveSpeedProfile = extractStringValue(clipJson, "curveSpeedProfile");
                clip.curveSpeedStrength = extractDoubleValue(clipJson, "curveSpeedStrength");
            }
            if (clip.playbackSpeed <= 0.0f) clip.playbackSpeed = 1.0f;
            if (clip.freezeFrameDurationMs <= 0) clip.freezeFrameDurationMs = 1000;
            if (clip.curveSpeedProfile.empty()) clip.curveSpeedProfile = "linear";
            if (clip.curveSpeedStrength <= 0.0f) clip.curveSpeedStrength = 1.0f;

            const std::string effectsJson = extractObjectValue(clipJson, "effects");
            if (!effectsJson.empty()) {
                clip.effects.brightness = extractDoubleValue(effectsJson, "brightness");
                clip.effects.contrast = extractDoubleValue(effectsJson, "contrast");
                clip.effects.saturation = extractDoubleValue(effectsJson, "saturation");
                clip.effects.enabled = extractBoolValue(effectsJson, "enabled");
            } else {
                clip.effects.brightness = extractDoubleValue(clipJson, "brightness");
                clip.effects.contrast = extractDoubleValue(clipJson, "contrast");
                clip.effects.saturation = extractDoubleValue(clipJson, "saturation");
                clip.effects.enabled = true;
            }
            if (clip.effects.contrast <= 0.0f) clip.effects.contrast = 1.0f;
            if (clip.effects.saturation <= 0.0f) clip.effects.saturation = 1.0f;

            const std::string chromaJson = extractObjectValue(clipJson, "chromaKey");
            if (!chromaJson.empty()) {
                clip.chromaKey.enabled = extractBoolValue(chromaJson, "enabled");
                clip.chromaKey.color = static_cast<int32_t>(extractInt64Value(chromaJson, "color"));
                clip.chromaKey.similarity = extractDoubleValue(chromaJson, "similarity");
                clip.chromaKey.smoothness = extractDoubleValue(chromaJson, "smoothness");
                clip.chromaKey.spill = extractDoubleValue(chromaJson, "spill");
            }
            clip.chromaKey.similarity = std::clamp(clip.chromaKey.similarity, 0.0f, 1.0f);
            clip.chromaKey.smoothness = std::clamp(clip.chromaKey.smoothness, 0.0f, 1.0f);
            clip.chromaKey.spill = std::clamp(clip.chromaKey.spill, 0.0f, 1.0f);
            
            clips_.push_back(clip);
        }

        // Parse text overlays array
        auto textObjects = extractArrayObjects(jsonStr, "textOverlays");
        for (const auto& textJson : textObjects) {
            TextEntry text;
            text.id = extractInt64Value(textJson, "id");
            text.text = extractStringValue(textJson, "text");
            text.x = extractDoubleValue(textJson, "x");
            text.y = extractDoubleValue(textJson, "y");
            text.scale = extractDoubleValue(textJson, "scale");
            text.rotation = extractDoubleValue(textJson, "rotation");
            text.color = extractColorValue(textJson, "color");
            text.opacity = extractDoubleValue(textJson, "opacity");
            text.fadeInMs = static_cast<int32_t>(extractInt64Value(textJson, "fadeInMs"));
            text.fadeOutMs = static_cast<int32_t>(extractInt64Value(textJson, "fadeOutMs"));
            text.zOrder = static_cast<int32_t>(extractInt64Value(textJson, "zOrder"));
            text.startTime = extractInt64Value(textJson, "startTime");
            text.endTime = extractInt64Value(textJson, "endTime");
            text.enabled = extractBoolValue(textJson, "enabled");
            
            // Parse keyframes
            auto keyframeObjects = extractArrayObjects(textJson, "keyframes");
            for (const auto& kfJson : keyframeObjects) {
                TextOverlay::TextKeyframe kf;
                kf.timeMs = extractInt64Value(kfJson, "timeMs");
                kf.posX = extractDoubleValue(kfJson, "posX");
                kf.posY = extractDoubleValue(kfJson, "posY");
                kf.scale = extractDoubleValue(kfJson, "scale");
                kf.opacity = extractDoubleValue(kfJson, "opacity");
                text.keyframes.push_back(kf);
            }
            
            textOverlays_.push_back(text);
        }

        // Parse transitions array
        auto transObjects = extractArrayObjects(jsonStr, "transitions");
        for (const auto& transJson : transObjects) {
            TransitionEntry trans;
            trans.id = extractInt64Value(transJson, "id");
            trans.type = extractStringValue(transJson, "type");
            trans.fromClipId = extractInt64Value(transJson, "fromClipId");
            trans.toClipId = extractInt64Value(transJson, "toClipId");
            trans.startTimeMs = extractInt64Value(transJson, "startTimeMs");
            trans.durationMs = extractInt64Value(transJson, "durationMs");
            transitions_.push_back(trans);
        }

        std::cout << "[Project] Loaded from JSON: " << clips_.size() << " clips, " 
                  << textOverlays_.size() << " overlays, " << transitions_.size() << " transitions\n";
        return true;
        
    } catch (const std::exception& e) {
        setError(std::string("Exception while parsing JSON: ") + e.what());
        return false;
    }
}


}  // namespace VideoEngine

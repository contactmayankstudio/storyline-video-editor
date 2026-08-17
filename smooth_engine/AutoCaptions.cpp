#include "AutoCaptions.h"

#include <algorithm>
#include <cctype>
#include <fstream>
#include <iostream>
#include <sstream>
#include <vector>

namespace VideoEngine::AI {
namespace {

std::string trim(const std::string& value) {
    const auto begin = std::find_if_not(value.begin(), value.end(), [](unsigned char ch) { return std::isspace(ch) != 0; });
    if (begin == value.end()) {
        return {};
    }
    const auto end = std::find_if_not(value.rbegin(), value.rend(), [](unsigned char ch) { return std::isspace(ch) != 0; }).base();
    return std::string(begin, end);
}

std::string replaceExtension(const std::string& path, const std::string& extension) {
    const std::size_t slashPos = path.find_last_of("/\\");
    const std::size_t dotPos = path.find_last_of('.');
    if (dotPos == std::string::npos || (slashPos != std::string::npos && dotPos < slashPos)) {
        return path + extension;
    }
    return path.substr(0, dotPos) + extension;
}

bool fileExists(const std::string& path) {
    std::ifstream in(path);
    return in.good();
}

std::string readTextFile(const std::string& path) {
    std::ifstream in(path, std::ios::in | std::ios::binary);
    if (!in.is_open()) {
        return {};
    }
    std::ostringstream buffer;
    buffer << in.rdbuf();
    return buffer.str();
}

int64_t parseTimestampMs(const std::string& rawValue) {
    std::string clean = trim(rawValue);
    std::replace(clean.begin(), clean.end(), '.', ',');
    std::replace(clean.begin(), clean.end(), ':', ' ');
    std::replace(clean.begin(), clean.end(), ',', ' ');
    std::istringstream stream(clean);
    int64_t hours = 0;
    int64_t minutes = 0;
    int64_t seconds = 0;
    int64_t millis = 0;
    if (!(stream >> hours >> minutes >> seconds >> millis)) {
        return -1;
    }
    return (hours * 3600000LL) + (minutes * 60000LL) + (seconds * 1000LL) + millis;
}

std::vector<AutoCaptions::Caption> parseSrtLike(const std::string& rawText) {
    std::vector<AutoCaptions::Caption> captions;
    std::string normalized = rawText;
    std::replace(normalized.begin(), normalized.end(), '\r', '\n');
    std::istringstream stream(normalized);
    std::string line;
    std::vector<std::string> block;
    auto flushBlock = [&]() {
        if (block.empty()) {
            return;
        }
        std::size_t timeLineIndex = std::string::npos;
        for (std::size_t i = 0; i < block.size(); ++i) {
            if (block[i].find("-->") != std::string::npos) {
                timeLineIndex = i;
                break;
            }
        }
        if (timeLineIndex == std::string::npos) {
            block.clear();
            return;
        }
        const std::string& timeLine = block[timeLineIndex];
        const std::size_t arrowPos = timeLine.find("-->");
        if (arrowPos == std::string::npos) {
            block.clear();
            return;
        }
        const int64_t startMs = parseTimestampMs(timeLine.substr(0, arrowPos));
        const int64_t endMs = parseTimestampMs(timeLine.substr(arrowPos + 3));
        if (startMs < 0 || endMs < 0) {
            block.clear();
            return;
        }
        std::string text;
        for (std::size_t i = timeLineIndex + 1; i < block.size(); ++i) {
            const std::string trimmed = trim(block[i]);
            if (trimmed.empty()) {
                continue;
            }
            if (!text.empty()) {
                text += ' ';
            }
            text += trimmed;
        }
        if (!text.empty()) {
            captions.push_back({startMs, std::max<int64_t>(endMs, startMs + 1), text});
        }
        block.clear();
    };

    while (std::getline(stream, line)) {
        const std::string trimmed = trim(line);
        if (trimmed.empty()) {
            flushBlock();
            continue;
        }
        if (trimmed == "WEBVTT") {
            continue;
        }
        block.push_back(trimmed);
    }
    flushBlock();
    return captions;
}

std::vector<AutoCaptions::Caption> parseTxt(const std::string& rawText) {
    std::vector<AutoCaptions::Caption> captions;
    std::istringstream stream(rawText);
    std::string line;
    int64_t startMs = 0;
    while (std::getline(stream, line)) {
        const std::string text = trim(line);
        if (text.empty()) {
            continue;
        }
        const int64_t endMs = startMs + 1800LL;
        captions.push_back({startMs, endMs, text});
        startMs = endMs + 200LL;
    }
    return captions;
}

}  // namespace

std::vector<AutoCaptions::Caption> AutoCaptions::generate(const std::string& audioPath) {
    std::cout << "[AutoCaptions] Resolving captions for: " << audioPath << "\n";

    const std::vector<std::string> candidates = {
        replaceExtension(audioPath, ".srt"),
        replaceExtension(audioPath, ".vtt"),
        replaceExtension(audioPath, ".txt"),
    };

    for (const auto& candidate : candidates) {
        if (!fileExists(candidate)) {
            continue;
        }
        const std::string raw = readTextFile(candidate);
        if (raw.empty()) {
            continue;
        }
        std::vector<Caption> captions;
        if (candidate.size() >= 4 && candidate.substr(candidate.size() - 4) == ".txt") {
            captions = parseTxt(raw);
        } else {
            captions = parseSrtLike(raw);
        }
        if (!captions.empty()) {
            std::cout << "[AutoCaptions] Loaded " << captions.size() << " captions from " << candidate << "\n";
            return captions;
        }
    }

    std::cout << "[AutoCaptions] No sidecar transcript found.\n";
    return {};
}

}  // namespace VideoEngine::AI

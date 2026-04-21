#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>

#include "preview/preview_controller.h"
#include "command_result.h"

namespace VideoEngine::Commands {

struct CommandContext {
    std::mutex* previewMutex = nullptr;
    std::unique_ptr<VideoEngine::PreviewController>* preview = nullptr;
    std::atomic<long long>* currentTimeMs = nullptr;
    std::atomic<bool>* renderingActive = nullptr;
    std::atomic<int>* timelineZoomMilliPxPerSecond = nullptr;
};

class EditorCommand {
public:
    explicit EditorCommand(std::string action) : m_action(std::move(action)) {}
    virtual ~EditorCommand() = default;

    virtual CommandResult execute(CommandContext& context) = 0;
    virtual CommandResult undo(CommandContext& context) {
        return CommandResult::fail(m_action, "Undo not supported");
    }
    virtual bool canUndo() const { return false; }

    const std::string& action() const { return m_action; }

private:
    std::string m_action;
};

} // namespace VideoEngine::Commands

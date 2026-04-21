#pragma once

#include <condition_variable>
#include <deque>
#include <future>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "editor_command.h"

namespace VideoEngine::Commands {

class CommandManager {
public:
    static CommandManager& instance();

    void initialize(CommandContext context);
    CommandResult execute(const std::string& action, const std::string& payloadJson);
    void executeAsync(const std::string& action, const std::string& payloadJson);
    CommandResult undo();
    CommandResult redo();
    std::string recentTelemetryJson() const;
    void clearTelemetry();

    ~CommandManager();

private:
    CommandManager();

    enum class QueueOperation {
        Execute,
        Undo,
        Redo,
    };

    struct PendingCommand {
        QueueOperation operation = QueueOperation::Execute;
        std::unique_ptr<EditorCommand> command;
        std::shared_ptr<std::promise<CommandResult>> promise;
        std::string action;
        std::string payloadJson;
        bool async = false;
        size_t queueDepthAtEnqueue = 0;
    };

    void ensureWorkerRunning();
    void workerLoop();
    std::unique_ptr<EditorCommand> buildCommand(const std::string& action, const std::string& payloadJson);
    void appendTelemetryEvent(
        const std::string& phase,
        const std::string& action,
        const std::string& payloadJson,
        bool async,
        size_t queueDepth,
        const CommandResult* result = nullptr,
        long long durationMs = -1);

    CommandContext m_context;
    std::mutex m_queueMutex;
    std::condition_variable m_queueCv;
    std::deque<PendingCommand> m_queue;
    std::vector<std::unique_ptr<EditorCommand>> m_undoStack;
    std::vector<std::unique_ptr<EditorCommand>> m_redoStack;
    std::thread m_worker;
    mutable std::mutex m_telemetryMutex;
    std::deque<std::string> m_telemetryEntries;
    bool m_workerStarted = false;
    bool m_shutdown = false;
};

} // namespace VideoEngine::Commands

#pragma once

#include <string>

namespace VideoEngine::Commands {

struct CommandResult {
    bool success = false;
    std::string action;
    std::string message;
    std::string dataJson = "{}";

    std::string toJson() const;
    static CommandResult ok(const std::string& action, const std::string& message, const std::string& dataJson = "{}");
    static CommandResult fail(const std::string& action, const std::string& message, const std::string& dataJson = "{}");
};

} // namespace VideoEngine::Commands

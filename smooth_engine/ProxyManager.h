#pragma once

#include <string>
#include <queue>
#include <thread>
#include <mutex>
#include <atomic>
#include <condition_variable>
#include <set>
#include <unordered_map>

namespace VideoEngine::DeepPro {

/**
 * @brief Automatic Proxy Generation (Premiere Pro style).
 * 
 * High-res 4K H.264/H.265 clips are hard to scrub.
 * This manager runs in the background and creates low-res (720p/360p)
 * i-frame only (ProRes/DNxHR style) proxies automatically.
 */
class ProxyManager {
public:
    enum class JobState {
        Pending,
        Running,
        Ready,
        Failed,
    };

    ProxyManager();
    ~ProxyManager();

    /**
     * @brief Add a clip to the proxy queue.
     */
    void requestProxy(const std::string& originalPath);
    std::string resolveProxyPath(const std::string& originalPath) const;
    std::string resolvePlaybackPath(const std::string& originalPath) const;
    bool isProxyReady(const std::string& originalPath) const;
    JobState getJobState(const std::string& originalPath) const;

    /**
     * @brief Switch between Proxy and Original media globally.
     */
    void setProxyMode(bool enabled) { m_proxyEnabled = enabled; }

    /**
     * @brief Intelligent Cache: If the device is low on storage,
     * it automatically purges old proxy files.
     */
    void manageStorage();

private:
    void workerLoop();
    bool generateProxy(const std::string& originalPath, const std::string& proxyPath) const;
    static std::string proxyDirectory();
    static bool ensureDirectory(const std::string& path);
    static bool copyFile(const std::string& from, const std::string& to);

    std::queue<std::string> m_queue;
    mutable std::mutex m_mutex;
    std::condition_variable m_cv;
    std::set<std::string> m_queuedPaths;
    std::unordered_map<std::string, std::string> m_proxyBySource;
    std::unordered_map<std::string, JobState> m_stateBySource;
    std::atomic<bool> m_proxyEnabled{true};
    std::atomic<bool> m_running{true};
    std::thread m_worker;
};

} // namespace VideoEngine::DeepPro

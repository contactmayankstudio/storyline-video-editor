#include "ProxyManager.h"

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <dirent.h>

namespace VideoEngine::DeepPro {

namespace {

std::string shellQuote(const std::string& input) {
    std::string out = "'";
    for (char ch : input) {
        if (ch == '\'') {
            out += "'\\''";
        } else {
            out += ch;
        }
    }
    out += "'";
    return out;
}

bool fileExists(const std::string& path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

std::string fileStem(const std::string& path) {
    const auto slash = path.find_last_of("/\\");
    const std::string fileName = slash == std::string::npos ? path : path.substr(slash + 1);
    const auto dot = fileName.find_last_of('.');
    return dot == std::string::npos ? fileName : fileName.substr(0, dot);
}

std::string fileExtension(const std::string& path) {
    const auto dot = path.find_last_of('.');
    return dot == std::string::npos ? std::string("mp4") : path.substr(dot + 1);
}

}  // namespace

ProxyManager::ProxyManager() {
    m_worker = std::thread(&ProxyManager::workerLoop, this);
}

ProxyManager::~ProxyManager() {
    m_running.store(false);
    m_cv.notify_all();
    if (m_worker.joinable()) {
        m_worker.join();
    }
}

void ProxyManager::requestProxy(const std::string& originalPath) {
    if (originalPath.empty()) return;
    const std::string proxyPath = resolveProxyPath(originalPath);
    std::lock_guard<std::mutex> lock(m_mutex);
    m_proxyBySource[originalPath] = proxyPath;
    if (fileExists(proxyPath)) {
        m_stateBySource[originalPath] = JobState::Ready;
        return;
    }
    if (!m_queuedPaths.insert(originalPath).second) {
        return;
    }
    m_stateBySource[originalPath] = JobState::Pending;
    m_queue.push(originalPath);
    m_cv.notify_one();
}

std::string ProxyManager::resolveProxyPath(const std::string& originalPath) const {
    const std::string directory = proxyDirectory();
    const std::string stem = fileStem(originalPath);
    return directory + "/" + stem + ".proxy.mp4";
}

std::string ProxyManager::resolvePlaybackPath(const std::string& originalPath) const {
    if (!m_proxyEnabled.load()) return originalPath;
    const std::lock_guard<std::mutex> lock(m_mutex);
    auto pathIt = m_proxyBySource.find(originalPath);
    const std::string proxyPath =
        pathIt != m_proxyBySource.end() ? pathIt->second : resolveProxyPath(originalPath);
    auto stateIt = m_stateBySource.find(originalPath);
    if (stateIt != m_stateBySource.end() && stateIt->second == JobState::Ready && fileExists(proxyPath)) {
        return proxyPath;
    }
    return originalPath;
}

bool ProxyManager::isProxyReady(const std::string& originalPath) const {
    return getJobState(originalPath) == JobState::Ready;
}

ProxyManager::JobState ProxyManager::getJobState(const std::string& originalPath) const {
    std::lock_guard<std::mutex> lock(m_mutex);
    const auto it = m_stateBySource.find(originalPath);
    return it == m_stateBySource.end() ? JobState::Failed : it->second;
}

void ProxyManager::workerLoop() {
    while (m_running.load()) {
        std::string target;
        {
            std::unique_lock<std::mutex> lock(m_mutex);
            m_cv.wait(lock, [&]() { return !m_running.load() || !m_queue.empty(); });
            if (!m_running.load()) break;
            target = m_queue.front();
            m_queue.pop();
            m_queuedPaths.erase(target);
            m_stateBySource[target] = JobState::Running;
        }

        const std::string proxyPath = resolveProxyPath(target);
        const bool ok = generateProxy(target, proxyPath);

        {
            std::lock_guard<std::mutex> lock(m_mutex);
            m_proxyBySource[target] = proxyPath;
            m_stateBySource[target] = ok ? JobState::Ready : JobState::Failed;
        }

        manageStorage();
    }
}

bool ProxyManager::generateProxy(const std::string& originalPath, const std::string& proxyPath) const {
    if (!fileExists(originalPath)) {
        return false;
    }
    if (!ensureDirectory(proxyDirectory())) {
        return false;
    }

    const std::string ffmpegProbe = "command -v ffmpeg >/dev/null 2>&1";
    if (std::system(ffmpegProbe.c_str()) == 0) {
        std::ostringstream cmd;
        cmd << "ffmpeg -y -loglevel error -i " << shellQuote(originalPath)
            << " -vf scale='min(1280,iw)':-2"
            << " -c:v libx264 -preset ultrafast -crf 30 -g 1 -keyint_min 1 -sc_threshold 0"
            << " -an " << shellQuote(proxyPath);
        if (std::system(cmd.str().c_str()) == 0 && fileExists(proxyPath)) {
            return true;
        }
    }

    return copyFile(originalPath, proxyPath);
}

void ProxyManager::manageStorage() {
    const std::string directory = proxyDirectory();
    DIR* dir = opendir(directory.c_str());
    if (!dir) return;

    struct ProxyFile {
        std::string path;
        time_t mtime = 0;
        off_t size = 0;
    };
    std::vector<ProxyFile> files;
    off_t totalBytes = 0;

    while (dirent* entry = readdir(dir)) {
        if (entry->d_name[0] == '.') continue;
        const std::string path = directory + "/" + entry->d_name;
        struct stat st {};
        if (stat(path.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) continue;
        totalBytes += st.st_size;
        ProxyFile file;
        file.path = path;
        file.mtime = st.st_mtime;
        file.size = static_cast<off_t>(st.st_size);
        files.push_back(file);
    }
    closedir(dir);

    constexpr off_t kMaxProxyBytes = static_cast<off_t>(2LL * 1024LL * 1024LL * 1024LL);
    if (totalBytes <= kMaxProxyBytes) return;

    std::sort(files.begin(), files.end(), [](const ProxyFile& a, const ProxyFile& b) {
        return a.mtime < b.mtime;
    });
    for (const auto& file : files) {
        if (totalBytes <= kMaxProxyBytes) break;
        if (std::remove(file.path.c_str()) == 0) {
            totalBytes -= file.size;
        }
    }
}

std::string ProxyManager::proxyDirectory() {
    const char* tmpDir = std::getenv("TMPDIR");
    const std::string base = (tmpDir && tmpDir[0] != '\0') ? tmpDir : "/tmp";
    return base + "/storyline_proxies";
}

bool ProxyManager::ensureDirectory(const std::string& path) {
    struct stat st {};
    if (stat(path.c_str(), &st) == 0) {
        return S_ISDIR(st.st_mode);
    }
    return mkdir(path.c_str(), 0775) == 0 || errno == EEXIST;
}

bool ProxyManager::copyFile(const std::string& from, const std::string& to) {
    std::ifstream input(from, std::ios::binary);
    if (!input.is_open()) return false;
    std::ofstream output(to, std::ios::binary | std::ios::trunc);
    if (!output.is_open()) return false;
    output << input.rdbuf();
    return output.good();
}

}  // namespace VideoEngine::DeepPro

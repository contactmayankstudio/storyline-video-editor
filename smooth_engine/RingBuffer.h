#pragma once

#include <vector>
#include <memory>
#include <mutex>
#include <atomic>
#include <optional>

namespace VideoEngine::Performance {

/**
 * @brief A lock-free(ish) Ring Buffer for caching decoded frames.
 * 
 * Provides O(1) insertion and O(1) lookup.
 * Much faster than std::map for sequential frame prefetching.
 */
template <typename T>
class RingBuffer {
public:
    explicit RingBuffer(size_t capacity)
        : m_capacity(capacity), m_head(0), m_tail(0), m_size(0) {
        m_buffer.resize(capacity);
    }

    ~RingBuffer() = default;

    bool push(const T& item) {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_size == m_capacity) {
            // Overwrite oldest
            m_buffer[m_head] = item;
            m_head = (m_head + 1) % m_capacity;
            m_tail = (m_tail + 1) % m_capacity;
        } else {
            m_buffer[m_tail] = item;
            m_tail = (m_tail + 1) % m_capacity;
            m_size++;
        }
        return true;
    }

    bool push(T&& item) {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_size == m_capacity) {
            // Overwrite oldest
            m_buffer[m_head] = std::move(item);
            m_head = (m_head + 1) % m_capacity;
            m_tail = (m_tail + 1) % m_capacity;
        } else {
            m_buffer[m_tail] = std::move(item);
            m_tail = (m_tail + 1) % m_capacity;
            m_size++;
        }
        return true;
    }

    void clear() {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_head = 0;
        m_tail = 0;
        m_size = 0;
        // Optionally clear the items to release memory
        for (auto& item : m_buffer) {
            item = T{};
        }
    }

    size_t size() const {
        std::lock_guard<std::mutex> lock(m_mutex);
        return m_size;
    }

    size_t capacity() const {
        return m_capacity;
    }

    /**
     * @brief Find an item that satisfies the predicate.
     * Starts from the most recently added items (tail) and goes backwards.
     */
    template <typename Predicate>
    std::optional<T> find_last_if(Predicate pred) const {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_size == 0) return std::nullopt;

        size_t index = (m_tail == 0) ? m_capacity - 1 : m_tail - 1;
        for (size_t i = 0; i < m_size; ++i) {
            if (pred(m_buffer[index])) {
                return m_buffer[index];
            }
            if (index == 0) {
                index = m_capacity - 1;
            } else {
                index--;
            }
        }
        return std::nullopt;
    }

    /**
     * @brief Get all items in chronological order.
     */
    std::vector<T> get_all() const {
        std::lock_guard<std::mutex> lock(m_mutex);
        std::vector<T> result;
        result.reserve(m_size);
        size_t index = m_head;
        for (size_t i = 0; i < m_size; ++i) {
            result.push_back(m_buffer[index]);
            index = (index + 1) % m_capacity;
        }
        return result;
    }

private:
    size_t m_capacity;
    size_t m_head;
    size_t m_tail;
    size_t m_size;
    std::vector<T> m_buffer;
    mutable std::mutex m_mutex;
};

} // namespace VideoEngine::Performance

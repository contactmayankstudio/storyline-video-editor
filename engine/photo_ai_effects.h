#pragma once

#include <vector>
#include <string>
#include <memory>
#include <mutex>
#include <cstdint>

namespace engine {
namespace ai {

/**
 * @struct ImageBuffer
 * @brief Represents an uncompressed image buffer (e.g., RGBA 8-bit).
 */
struct ImageBuffer {
    std::vector<uint8_t> data;
    int width = 0;
    int height = 0;
    int channels = 4; // Default to RGBA
};

/**
 * @class PhotoAIEffects
 * @brief Provides AI-based photo effects, such as background removal.
 * 
 * This class handles loading on-device ML models and running inferences 
 * against image buffers. It is designed to be thread-safe.
 */
class PhotoAIEffects {
public:
    /**
     * @brief Construct a new PhotoAIEffects object.
     * @param modelPath Path to the TFLite model file on device.
     */
    explicit PhotoAIEffects(const std::string& modelPath);
    
    ~PhotoAIEffects();

    // Prevent copying and assignment to manage model resources safely
    PhotoAIEffects(const PhotoAIEffects&) = delete;
    PhotoAIEffects& operator=(const PhotoAIEffects&) = delete;

    /**
     * @brief Initialize the model and interpreter.
     * @return true if initialization was successful, false otherwise.
     */
    bool initialize();

    /**
     * @brief Removes the background from the provided image buffer.
     * 
     * @param input Image buffer containing the original image.
     * @param output Image buffer where the foreground-only image will be stored.
     * @return true if processing was successful, false otherwise.
     */
    bool removeBackground(const ImageBuffer& input, ImageBuffer& output);

private:
    std::string m_modelPath;
    bool m_initialized = false;
    
    // Mutex for synchronizing inference calls to ensure thread safety
    mutable std::mutex m_inferenceMutex;

    // Mocking TFLite internals for now
    // std::unique_ptr<tflite::FlatBufferModel> m_model;
    // std::unique_ptr<tflite::Interpreter> m_interpreter;
    
    /**
     * @brief Helper to preprocess input image to match model input requirements.
     */
    void preprocess(const ImageBuffer& input, std::vector<float>& modelInput);

    /**
     * @brief Helper to postprocess model output to standard image buffer format.
     */
    void postprocess(const std::vector<float>& modelOutput, int width, int height, ImageBuffer& output);
};

} // namespace ai
} // namespace engine

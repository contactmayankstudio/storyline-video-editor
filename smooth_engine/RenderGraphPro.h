#pragma once

#include <vector>
#include <memory>
#include <string>

namespace VideoEngine::DeepPro {

/**
 * @brief Node-Based Rendering (DaVinci/Nuke Style).
 * 
 * Instead of a fixed pipeline (YUV->RGB->Effects), a Node Graph allows 
 * arbitrary connections. You can have a "Blur" node before "Color Grade" 
 * or branch one video into two different effect paths.
 */
class RenderNode {
public:
    virtual ~RenderNode() = default;
    virtual void process() = 0; // GPU execution
    
    void addInput(std::shared_ptr<RenderNode> input) { m_inputs.push_back(input); }
    
protected:
    std::vector<std::shared_ptr<RenderNode>> m_inputs;
    uint32_t m_outputTextureId;
};

class ColorGradeNode : public RenderNode {
    // 32-bit Float Precision processing for high dynamic range
    void process() override { /* 32-bit float shader execution */ }
};

class MaskNode : public RenderNode {
    // Advanced bezier masking
    void process() override { /* Alpha mask calculation */ }
};

class RenderGraphPro {
public:
    /**
     * @brief Optimizes the graph by merging shaders (Kernel Fusion).
     * This reduces memory bandwidth and makes the engine super fast.
     */
    void optimize();
    
    /**
     * @brief Executes the graph for a specific frame.
     */
    void execute(int64_t ptsMs);
};

} // namespace VideoEngine::DeepPro

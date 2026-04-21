#pragma once

#include <cstdint>
#include <memory>
#include <glm/glm.hpp>

namespace VideoEngine::GPU {

/**
 * Vertex Array Object and Vertex Buffer Object for rendering quads.
 * Each quad represents one rendered item (clip).
 */
class QuadMesh {
public:
    /**
     * Create a quad mesh.
     */
    QuadMesh();

    ~QuadMesh();

    // Non-copyable
    QuadMesh(const QuadMesh&) = delete;
    QuadMesh& operator=(const QuadMesh&) = delete;

    /**
     * Bind the VAO for rendering.
     */
    void bind() const;

    /**
     * Render the quad.
     */
    void render() const;

    /**
     * Get OpenGL VAO handle.
     */
    uint32_t getVAO() const { return m_vao; }

private:
    uint32_t m_vao;
    uint32_t m_vbo;
    uint32_t m_ebo;

    void setupBuffers();
};

using QuadMeshPtr = std::shared_ptr<QuadMesh>;

} // namespace VideoEngine::GPU

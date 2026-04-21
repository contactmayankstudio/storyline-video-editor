#version 330 core

/**
 * Fullscreen Quad Vertex Shader
 * 
 * Renders a fullscreen quad using normalized device coordinates (NDC).
 * Transforms vertices using projection, view, and model matrices.
 * Passes texture coordinates to fragment shader.
 * 
 * Compatible with OpenGL 3.3 core profile.
 */

layout(location = 0) in vec3 position;
layout(location = 1) in vec2 texCoord;

// Transformation matrices
uniform mat4 projection;
uniform mat4 view;
uniform mat4 model;

// Pass texture coordinates to fragment shader
out VS_OUT {
    vec2 texCoord;
    vec3 worldPos;
} vs_out;

void main() {
    // Transform position through MVP matrix chain
    gl_Position = projection * view * model * vec4(position, 1.0);
    
    // Pass texture coordinates to fragment shader
    vs_out.texCoord = texCoord;
    
    // Pass world position for potential lighting/effects
    vs_out.worldPos = vec3(model * vec4(position, 1.0));
}

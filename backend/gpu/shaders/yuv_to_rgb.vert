#version 330 core

/**
 * YUV420P Video Frame Vertex Shader
 * 
 * Renders fullscreen quad for video playback.
 * Transforms vertices and passes texture coordinates to fragment shader.
 * 
 * Fragment shader will sample YUV planes and convert to RGB.
 */

layout(location = 0) in vec3 position;
layout(location = 1) in vec2 texCoord;

// Transformation matrices
uniform mat4 projection;
uniform mat4 view;
uniform mat4 model;

// Pass to fragment shader
out VS_OUT {
    vec2 texCoord;
    vec3 worldPos;
} vs_out;

void main() {
    // Transform position through MVP chain
    gl_Position = projection * view * model * vec4(position, 1.0);
    
    // Pass texture coordinates (used to sample Y, U, V planes)
    vs_out.texCoord = texCoord;
    
    // Pass world position
    vs_out.worldPos = vec3(model * vec4(position, 1.0));
}

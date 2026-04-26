#include "egl_renderer.h"
#include "gl_texture.h"

#include <iostream>
#include <cstdarg>
#include <cstring>
#include <cmath>
#include <algorithm>

// Android EGL/GL headers
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include <android/window.h>

namespace VideoEngine::GPU {

// Embedded GLSL shaders (GLSL 300 es for OpenGL ES 3.0)

static const char* VERTEX_SHADER_SRC = R"(
#version 300 es
precision highp float;

layout(location = 0) in vec2 position;
layout(location = 1) in vec2 texCoord;

out vec2 fragTexCoord;

void main() {
    gl_Position = vec4(position, 0.0, 1.0);
    fragTexCoord = vec2(texCoord.x, 1.0 - texCoord.y);
}
)";

static const char* FRAGMENT_SHADER_SRC = R"(
#version 300 es
precision mediump float;

in vec2 fragTexCoord;
out vec4 outColor;

uniform sampler2D textureSampler;
uniform float uOpacity;
uniform bool uTransformEnabled;
uniform vec2 uViewportSize;
uniform vec2 uTextureSize;
uniform float uZoom;
uniform vec2 uPanNorm;
uniform float uRotationDeg;
uniform bool uMirrorX;
uniform bool uChromaEnabled;
uniform vec3 uChromaKeyColor;
uniform float uChromaSimilarity;
uniform float uChromaSmoothness;
uniform float uChromaSpill;
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;

void main() {
    vec2 sampleCoord = fragTexCoord;
    if (uTransformEnabled) {
        vec2 viewportSize = max(uViewportSize, vec2(1.0));
        vec2 textureSize = max(uTextureSize, vec2(1.0));
        float sourceAspect = textureSize.x / max(textureSize.y, 1.0);
        float viewportAspect = viewportSize.x / max(viewportSize.y, 1.0);
        vec2 baseRenderedSize;
        if (sourceAspect > viewportAspect) {
            baseRenderedSize = vec2(viewportSize.y * sourceAspect, viewportSize.y);
        } else {
            baseRenderedSize = vec2(viewportSize.x, viewportSize.x / max(sourceAspect, 0.0001));
        }
        vec2 renderedSize = max(baseRenderedSize * max(uZoom, 1.0), vec2(1.0));
        vec2 maxPanPx = max((renderedSize - viewportSize) * 0.5, vec2(0.0));
        vec2 localPx = (fragTexCoord - vec2(0.5)) * viewportSize;
        localPx -= clamp(uPanNorm, vec2(-1.0), vec2(1.0)) * maxPanPx;

        float angleRad = radians(uRotationDeg);
        float cosA = cos(angleRad);
        float sinA = sin(angleRad);
        localPx = vec2(
            (localPx.x * cosA) + (localPx.y * sinA),
            (-localPx.x * sinA) + (localPx.y * cosA)
        );
        if (uMirrorX) {
            localPx.x = -localPx.x;
        }
        sampleCoord = (localPx / renderedSize) + vec2(0.5);
        if (sampleCoord.x < 0.0 || sampleCoord.x > 1.0 ||
            sampleCoord.y < 0.0 || sampleCoord.y > 1.0) {
            outColor = vec4(0.0);
            return;
        }
    }

    vec4 color = texture(textureSampler, sampleCoord);
    if (uChromaEnabled) {
        float d = distance(color.rgb, uChromaKeyColor);
        float alpha = smoothstep(uChromaSimilarity, uChromaSimilarity + uChromaSmoothness, d);
        if (uChromaKeyColor.g > 0.5) {
            color.g = mix(color.g, (color.r + color.b) * 0.5, uChromaSpill);
        } else if (uChromaKeyColor.b > 0.5) {
            color.b = mix(color.b, (color.r + color.g) * 0.5, uChromaSpill);
        }
        color.a *= alpha;
        color.rgb *= alpha;
    }
    // Brightness
    color.rgb += uBrightness;
    // Contrast
    color.rgb = (color.rgb - 0.5) * uContrast + 0.5;
    // Saturation
    float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    color.rgb = mix(vec3(luma), color.rgb, uSaturation);
    color.rgb = clamp(color.rgb, 0.0, 1.0);
    color.a *= uOpacity;
    color.rgb *= uOpacity;
    outColor = color;
}
)";

static const char* TRANSITION_FRAGMENT_SHADER_SRC = R"(
#version 300 es
precision mediump float;

in vec2 fragTexCoord;
out vec4 outColor;

uniform vec2 uViewportSize;
uniform float uProgress;
uniform int uTransitionType;

uniform sampler2D textureSamplerA;
uniform float uOpacityA;
uniform bool uTransformEnabledA;
uniform vec2 uTextureSizeA;
uniform float uZoomA;
uniform vec2 uPanNormA;
uniform float uRotationDegA;
uniform bool uMirrorXA;
uniform bool uChromaEnabledA;
uniform vec3 uChromaKeyColorA;
uniform float uChromaSimilarityA;
uniform float uChromaSmoothnessA;
uniform float uChromaSpillA;
uniform float uBrightnessA;
uniform float uContrastA;
uniform float uSaturationA;

uniform sampler2D textureSamplerB;
uniform float uOpacityB;
uniform bool uTransformEnabledB;
uniform vec2 uTextureSizeB;
uniform float uZoomB;
uniform vec2 uPanNormB;
uniform float uRotationDegB;
uniform bool uMirrorXB;
uniform bool uChromaEnabledB;
uniform vec3 uChromaKeyColorB;
uniform float uChromaSimilarityB;
uniform float uChromaSmoothnessB;
uniform float uChromaSpillB;
uniform float uBrightnessB;
uniform float uContrastB;
uniform float uSaturationB;

vec2 resolveSampleCoord(
    vec2 baseCoord,
    bool transformEnabled,
    vec2 textureSize,
    float zoom,
    vec2 panNorm,
    float rotationDeg,
    bool mirrorX) {
    if (!transformEnabled) {
        return baseCoord;
    }

    vec2 viewportSize = max(uViewportSize, vec2(1.0));
    vec2 safeTextureSize = max(textureSize, vec2(1.0));
    float sourceAspect = safeTextureSize.x / max(safeTextureSize.y, 1.0);
    float viewportAspect = viewportSize.x / max(viewportSize.y, 1.0);
    vec2 baseRenderedSize;
    if (sourceAspect > viewportAspect) {
        baseRenderedSize = vec2(viewportSize.y * sourceAspect, viewportSize.y);
    } else {
        baseRenderedSize = vec2(viewportSize.x, viewportSize.x / max(sourceAspect, 0.0001));
    }

    vec2 renderedSize = max(baseRenderedSize * max(zoom, 1.0), vec2(1.0));
    vec2 maxPanPx = max((renderedSize - viewportSize) * 0.5, vec2(0.0));
    vec2 localPx = (baseCoord - vec2(0.5)) * viewportSize;
    localPx -= clamp(panNorm, vec2(-1.0), vec2(1.0)) * maxPanPx;

    float angleRad = radians(rotationDeg);
    float cosA = cos(angleRad);
    float sinA = sin(angleRad);
    localPx = vec2(
        (localPx.x * cosA) + (localPx.y * sinA),
        (-localPx.x * sinA) + (localPx.y * cosA)
    );
    if (mirrorX) {
        localPx.x = -localPx.x;
    }
    return (localPx / renderedSize) + vec2(0.5);
}

vec4 sampleLayer(
    sampler2D textureSampler,
    vec2 baseCoord,
    bool transformEnabled,
    vec2 textureSize,
    float zoom,
    vec2 panNorm,
    float rotationDeg,
    bool mirrorX,
    bool chromaEnabled,
    vec3 chromaKeyColor,
    float chromaSimilarity,
    float chromaSmoothness,
    float chromaSpill,
    float brightness,
    float contrast,
    float saturation,
    float opacity) {
    vec2 sampleCoord = resolveSampleCoord(
        baseCoord,
        transformEnabled,
        textureSize,
        zoom,
        panNorm,
        rotationDeg,
        mirrorX);
    if (sampleCoord.x < 0.0 || sampleCoord.x > 1.0 ||
        sampleCoord.y < 0.0 || sampleCoord.y > 1.0) {
        return vec4(0.0);
    }

    vec4 color = texture(textureSampler, sampleCoord);
    if (chromaEnabled) {
        float d = distance(color.rgb, chromaKeyColor);
        float alpha = smoothstep(chromaSimilarity, chromaSimilarity + chromaSmoothness, d);
        if (chromaKeyColor.g > 0.5) {
            color.g = mix(color.g, (color.r + color.b) * 0.5, chromaSpill);
        } else if (chromaKeyColor.b > 0.5) {
            color.b = mix(color.b, (color.r + color.g) * 0.5, chromaSpill);
        }
        color.a *= alpha;
        color.rgb *= alpha;
    }

    color.rgb += brightness;
    color.rgb = (color.rgb - 0.5) * contrast + 0.5;
    float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    color.rgb = mix(vec3(luma), color.rgb, saturation);
    color.rgb = clamp(color.rgb, 0.0, 1.0);
    color.a *= opacity;
    color.rgb *= opacity;
    return color;
}

void main() {
    float progress = clamp(uProgress, 0.0, 1.0);
    vec4 outgoing = sampleLayer(
        textureSamplerA,
        fragTexCoord + vec2(progress, 0.0),
        uTransformEnabledA,
        uTextureSizeA,
        uZoomA,
        uPanNormA,
        uRotationDegA,
        uMirrorXA,
        uChromaEnabledA,
        uChromaKeyColorA,
        uChromaSimilarityA,
        uChromaSmoothnessA,
        uChromaSpillA,
        uBrightnessA,
        uContrastA,
        uSaturationA,
        uOpacityA);
    vec4 incoming = sampleLayer(
        textureSamplerB,
        fragTexCoord - vec2(1.0 - progress, 0.0),
        uTransformEnabledB,
        uTextureSizeB,
        uZoomB,
        uPanNormB,
        uRotationDegB,
        uMirrorXB,
        uChromaEnabledB,
        uChromaKeyColorB,
        uChromaSimilarityB,
        uChromaSmoothnessB,
        uChromaSpillB,
        uBrightnessB,
        uContrastB,
        uSaturationB,
        uOpacityB);

    vec4 outgoingStatic = sampleLayer(
        textureSamplerA,
        fragTexCoord,
        uTransformEnabledA,
        uTextureSizeA,
        uZoomA,
        uPanNormA,
        uRotationDegA,
        uMirrorXA,
        uChromaEnabledA,
        uChromaKeyColorA,
        uChromaSimilarityA,
        uChromaSmoothnessA,
        uChromaSpillA,
        uBrightnessA,
        uContrastA,
        uSaturationA,
        uOpacityA);
    vec4 incomingStatic = sampleLayer(
        textureSamplerB,
        fragTexCoord,
        uTransformEnabledB,
        uTextureSizeB,
        uZoomB,
        uPanNormB,
        uRotationDegB,
        uMirrorXB,
        uChromaEnabledB,
        uChromaKeyColorB,
        uChromaSimilarityB,
        uChromaSmoothnessB,
        uChromaSpillB,
        uBrightnessB,
        uContrastB,
        uSaturationB,
        uOpacityB);

    vec4 result;
    if (uTransitionType == 1) {
        if (progress < 0.5) {
            result = outgoingStatic * (1.0 - (progress * 2.0));
        } else {
            result = incomingStatic * ((progress - 0.5) * 2.0);
        }
    } else if (uTransitionType == 3) {
        float feather = max(0.0025, 1.5 / max(uViewportSize.x, 1.0));
        float wipeMix = smoothstep(progress - feather, progress + feather, fragTexCoord.x);
        result = mix(outgoingStatic, incomingStatic, wipeMix);
    } else if (uTransitionType == 4) {
        result = outgoing + incoming;
    } else {
        result = mix(outgoingStatic, incomingStatic, progress);
    }

    outColor = vec4(clamp(result.rgb, 0.0, 1.0), clamp(result.a, 0.0, 1.0));
}
)";

// Quad geometry: full-screen textured quad in NDC coordinates
// Position: (-1, -1) to (1, 1), TexCoord: (0, 0) to (1, 1)
static const float QUAD_VERTICES[] = {
    // position  |  texCoord
    -1.0f, -1.0f,   0.0f, 0.0f,   // Bottom-left
     1.0f, -1.0f,   1.0f, 0.0f,   // Bottom-right
     1.0f,  1.0f,   1.0f, 1.0f,   // Top-right
    -1.0f,  1.0f,   0.0f, 1.0f    // Top-left
};

static const uint16_t QUAD_INDICES[] = {
    0, 1, 2,   // First triangle
    0, 2, 3    // Second triangle
};

EGLRenderer::EGLRenderer()
    : m_eglDisplay(nullptr)
    , m_eglContext(nullptr)
    , m_eglSurface(nullptr)
    , m_nativeWindow(nullptr)
    , m_programId(0)
    , m_transitionProgramId(0)
    , m_vao(0)
    , m_vbo(0)
    , m_ebo(0)
    , m_viewportWidth(0)
    , m_viewportHeight(0)
    , m_initialized(false)
{
}

EGLRenderer::~EGLRenderer() {
    shutdown();
}

bool EGLRenderer::initialize(ANativeWindow* nativeWindow) {
    if (m_initialized) {
        setError("Renderer already initialized");
        return false;
    }

    if (!nativeWindow) {
        setError("ANativeWindow is null");
        return false;
    }

    m_nativeWindow = nativeWindow;
    ANativeWindow_setBuffersGeometry(nativeWindow, 0, 0, WINDOW_FORMAT_RGBA_8888);
    m_viewportWidth = ANativeWindow_getWidth(nativeWindow);
    m_viewportHeight = ANativeWindow_getHeight(nativeWindow);

    // ========== EGL Setup ==========

    // Get EGL display
    m_eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (m_eglDisplay == EGL_NO_DISPLAY) {
        setError("eglGetDisplay failed");
        return false;
    }

    // Initialize EGL
    EGLint majorVersion, minorVersion;
    if (!eglInitialize(m_eglDisplay, &majorVersion, &minorVersion)) {
        setError("eglInitialize failed: 0x%x", eglGetError());
        m_eglDisplay = nullptr;
        return false;
    }

    std::cout << "[EGLRenderer] EGL initialized: " << majorVersion << "." 
              << minorVersion << "\n";

    // Choose EGL config
    const EGLint configAttribs[] = {
        EGL_SURFACE_TYPE,        EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE,     EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE,            8,
        EGL_GREEN_SIZE,          8,
        EGL_BLUE_SIZE,           8,
        EGL_ALPHA_SIZE,          8,
        EGL_DEPTH_SIZE,          0,
        EGL_NONE
    };

    EGLConfig config;
    EGLint numConfigs;
    if (!eglChooseConfig(m_eglDisplay, configAttribs, &config, 1, &numConfigs) || 
        numConfigs == 0) {
        setError("eglChooseConfig failed: 0x%x", eglGetError());
        eglTerminate(m_eglDisplay);
        m_eglDisplay = nullptr;
        return false;
    }

    // Create EGL context
    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    m_eglContext = eglCreateContext(m_eglDisplay, config, EGL_NO_CONTEXT, contextAttribs);
    if (m_eglContext == EGL_NO_CONTEXT) {
        setError("eglCreateContext failed: 0x%x", eglGetError());
        eglTerminate(m_eglDisplay);
        m_eglDisplay = nullptr;
        return false;
    }

    // Create EGL surface from ANativeWindow
    m_eglSurface = eglCreateWindowSurface(m_eglDisplay, config, m_nativeWindow, nullptr);
    if (m_eglSurface == EGL_NO_SURFACE) {
        setError("eglCreateWindowSurface failed: 0x%x", eglGetError());
        eglDestroyContext(m_eglDisplay, m_eglContext);
        eglTerminate(m_eglDisplay);
        m_eglDisplay = nullptr;
        m_eglContext = nullptr;
        return false;
    }

    // Make context current
    if (!makeCurrent()) {
        setError("eglMakeCurrent failed");
        eglDestroySurface(m_eglDisplay, m_eglSurface);
        eglDestroyContext(m_eglDisplay, m_eglContext);
        eglTerminate(m_eglDisplay);
        m_eglDisplay = nullptr;
        m_eglContext = nullptr;
        m_eglSurface = nullptr;
        return false;
    }

    // ========== OpenGL Setup ==========

    // Create shader programs
    m_programId = createShaderProgram(FRAGMENT_SHADER_SRC);
    if (m_programId == 0) {
        setError("Failed to create shader program");
        releaseResources();
        return false;
    }
    m_transitionProgramId = createShaderProgram(TRANSITION_FRAGMENT_SHADER_SRC);
    if (m_transitionProgramId == 0) {
        setError("Failed to create transition shader program");
        releaseResources();
        return false;
    }

    m_uOpacityLoc = glGetUniformLocation(m_programId, "uOpacity");
    m_uChromaEnabledLoc = glGetUniformLocation(m_programId, "uChromaEnabled");
    m_uChromaKeyColorLoc = glGetUniformLocation(m_programId, "uChromaKeyColor");
    m_uChromaSimilarityLoc = glGetUniformLocation(m_programId, "uChromaSimilarity");
    m_uChromaSmoothnessLoc = glGetUniformLocation(m_programId, "uChromaSmoothness");
    m_uChromaSpillLoc = glGetUniformLocation(m_programId, "uChromaSpill");
    m_uBrightnessLoc = glGetUniformLocation(m_programId, "uBrightness");
    m_uContrastLoc = glGetUniformLocation(m_programId, "uContrast");
    m_uSaturationLoc = glGetUniformLocation(m_programId, "uSaturation");

    // Create quad mesh
    if (!createQuadMesh()) {
        setError("Failed to create quad mesh");
        releaseResources();
        return false;
    }

    // Configure OpenGL state
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_BLEND);

    if (!checkGLError("GL initialization")) {
        releaseResources();
        return false;
    }

    m_initialized = true;
    std::cout << "[EGLRenderer] Initialization successful\n";
    return true;
}

void EGLRenderer::resizeViewport(int width, int height) {
    if (width <= 0 || height <= 0) {
        return;
    }

    m_viewportWidth = width;
    m_viewportHeight = height;
}

bool EGLRenderer::renderFrame(const GLTexture& texture) {
    return renderFrameRegion(texture, 0, 0, m_viewportWidth, m_viewportHeight);
}

bool EGLRenderer::renderFrameRegion(
    const GLTexture& texture,
    int dirtyX,
    int dirtyY,
    int dirtyWidth,
    int dirtyHeight) {
    if (!m_initialized) {
        setError("Renderer not initialized");
        return false;
    }

    if (!texture.isValid()) {
        setError("Texture is not valid");
        return false;
    }

    if (!makeCurrent()) {
        setError("eglMakeCurrent failed");
        return false;
    }

    if (m_viewportWidth > 0 && m_viewportHeight > 0) {
        glViewport(0, 0, m_viewportWidth, m_viewportHeight);
    }

    const bool useDirtyRegion =
        dirtyWidth > 0 &&
        dirtyHeight > 0 &&
        dirtyWidth < m_viewportWidth &&
        dirtyHeight < m_viewportHeight;
    if (useDirtyRegion) {
        const int clampedX = std::max(0, std::min(dirtyX, m_viewportWidth - 1));
        const int clampedYTop = std::max(0, std::min(dirtyY, m_viewportHeight - 1));
        const int clampedW = std::max(1, std::min(dirtyWidth, m_viewportWidth - clampedX));
        const int clampedH = std::max(1, std::min(dirtyHeight, m_viewportHeight - clampedYTop));
        // Android/UI uses top-left origin, OpenGL scissor uses bottom-left.
        const int scissorY = std::max(0, m_viewportHeight - (clampedYTop + clampedH));
        glEnable(GL_SCISSOR_TEST);
        glScissor(clampedX, scissorY, clampedW, clampedH);
    } else {
        glDisable(GL_SCISSOR_TEST);
    }

    // Clear framebuffer (full or scissored dirty region)
    glClear(GL_COLOR_BUFFER_BIT);

    // Use shader program
    glUseProgram(m_programId);
    if (m_uOpacityLoc >= 0) {
        glUniform1f(m_uOpacityLoc, 1.0f);
    }
    if (m_uChromaEnabledLoc >= 0) {
        glUniform1i(m_uChromaEnabledLoc, m_chromaEnabled ? 1 : 0);
    }
    if (m_uChromaKeyColorLoc >= 0) {
        glUniform3f(
            m_uChromaKeyColorLoc,
            m_chromaColor[0],
            m_chromaColor[1],
            m_chromaColor[2]
        );
    }
    if (m_uChromaSimilarityLoc >= 0) {
        glUniform1f(m_uChromaSimilarityLoc, m_chromaSimilarity);
    }
    if (m_uChromaSmoothnessLoc >= 0) {
        glUniform1f(m_uChromaSmoothnessLoc, m_chromaSmoothness);
    }
    if (m_uChromaSpillLoc >= 0) {
        glUniform1f(m_uChromaSpillLoc, m_chromaSpill);
    }
    if (m_uBrightnessLoc >= 0)
        glUniform1f(m_uBrightnessLoc, 0.0f);
    if (m_uContrastLoc >= 0)
        glUniform1f(m_uContrastLoc, 1.0f);
    if (m_uSaturationLoc >= 0)
        glUniform1f(m_uSaturationLoc, 1.0f);

    // Bind texture to unit 0
    texture.bind(0);

    // Set texture uniform
    GLint texLoc = glGetUniformLocation(m_programId, "textureSampler");
    glUniform1i(texLoc, 0);

    // Bind and draw quad
    glBindVertexArray(m_vao);
    glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, nullptr);

    // Unbind
    glBindVertexArray(0);
    glUseProgram(0);

    if (!checkGLError("renderFrame")) {
        glDisable(GL_SCISSOR_TEST);
        return false;
    }

    // Swap buffers to display
    if (!eglSwapBuffers(m_eglDisplay, m_eglSurface)) {
        setError("eglSwapBuffers failed: 0x%x", eglGetError());
        glDisable(GL_SCISSOR_TEST);
        return false;
    }

    if (useDirtyRegion) {
        glDisable(GL_SCISSOR_TEST);
    }

    return true;
}

bool EGLRenderer::renderLayers(const std::vector<Layer>& layers) {
    if (!m_initialized) {
        setError("Renderer not initialized");
        return false;
    }
    if (!makeCurrent()) {
        setError("eglMakeCurrent failed");
        return false;
    }

    glViewport(0, 0, m_viewportWidth, m_viewportHeight);
    glDisable(GL_SCISSOR_TEST);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(m_programId);

    GLint texLoc = glGetUniformLocation(m_programId, "textureSampler");
    glUniform1i(texLoc, 0);
    const GLint transformEnabledLoc = glGetUniformLocation(m_programId, "uTransformEnabled");
    const GLint viewportSizeLoc = glGetUniformLocation(m_programId, "uViewportSize");
    const GLint textureSizeLoc = glGetUniformLocation(m_programId, "uTextureSize");
    const GLint zoomLoc = glGetUniformLocation(m_programId, "uZoom");
    const GLint panNormLoc = glGetUniformLocation(m_programId, "uPanNorm");
    const GLint rotationLoc = glGetUniformLocation(m_programId, "uRotationDeg");
    const GLint mirrorXLoc = glGetUniformLocation(m_programId, "uMirrorX");

    for (const auto& layer : layers) {
        if (!layer.texture || !layer.texture->isValid()) continue;

        if (m_uOpacityLoc >= 0)
            glUniform1f(m_uOpacityLoc, layer.opacity);
        const bool transformEnabled =
            std::fabs(layer.zoom - 1.0f) > 0.001f ||
            std::fabs(layer.panXNorm) > 0.001f ||
            std::fabs(layer.panYNorm) > 0.001f ||
            std::fabs(layer.rotationDeg) > 0.001f ||
            layer.mirrorX;
        if (transformEnabledLoc >= 0)
            glUniform1i(transformEnabledLoc, transformEnabled ? 1 : 0);
        if (viewportSizeLoc >= 0)
            glUniform2f(
                viewportSizeLoc,
                static_cast<float>(std::max(1, m_viewportWidth)),
                static_cast<float>(std::max(1, m_viewportHeight)));
        if (textureSizeLoc >= 0)
            glUniform2f(
                textureSizeLoc,
                static_cast<float>(std::max(1, layer.texture->getWidth())),
                static_cast<float>(std::max(1, layer.texture->getHeight())));
        if (zoomLoc >= 0)
            glUniform1f(zoomLoc, std::max(1.0f, layer.zoom));
        if (panNormLoc >= 0)
            glUniform2f(panNormLoc, layer.panXNorm, layer.panYNorm);
        if (rotationLoc >= 0)
            glUniform1f(rotationLoc, layer.rotationDeg);
        if (mirrorXLoc >= 0)
            glUniform1i(mirrorXLoc, layer.mirrorX ? 1 : 0);
        if (m_uChromaEnabledLoc >= 0)
            glUniform1i(m_uChromaEnabledLoc, layer.chromaEnabled ? 1 : 0);
        if (layer.chromaEnabled) {
            if (m_uChromaKeyColorLoc >= 0)
                glUniform3f(m_uChromaKeyColorLoc,
                    layer.blueKey ? 0.0f : 0.0f,
                    layer.blueKey ? 0.0f : 1.0f,
                    layer.blueKey ? 1.0f : 0.0f);
            if (m_uChromaSimilarityLoc >= 0)
                glUniform1f(m_uChromaSimilarityLoc, layer.chromaSimilarity);
            if (m_uChromaSmoothnessLoc >= 0)
                glUniform1f(m_uChromaSmoothnessLoc, layer.chromaSmoothness);
            if (m_uChromaSpillLoc >= 0)
                glUniform1f(m_uChromaSpillLoc, layer.chromaSpill);
        }
        if (m_uBrightnessLoc >= 0)
            glUniform1f(m_uBrightnessLoc, layer.brightness);
        if (m_uContrastLoc >= 0)
            glUniform1f(m_uContrastLoc, layer.contrast);
        if (m_uSaturationLoc >= 0)
            glUniform1f(m_uSaturationLoc, layer.saturation);

        layer.texture->bind(0);
        glBindVertexArray(m_vao);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, nullptr);
    }

    glBindVertexArray(0);
    glUseProgram(0);
    glDisable(GL_BLEND);

    if (!checkGLError("renderLayers")) return false;
    if (!eglSwapBuffers(m_eglDisplay, m_eglSurface)) {
        setError("eglSwapBuffers failed: 0x%x", eglGetError());
        return false;
    }
    return true;
}

bool EGLRenderer::renderTransition(
    const Layer& outgoing,
    const Layer& incoming,
    int transitionType,
    float progress) {
    if (!m_initialized) {
        setError("Renderer not initialized");
        return false;
    }
    if (!outgoing.texture || !outgoing.texture->isValid() ||
        !incoming.texture || !incoming.texture->isValid()) {
        setError("Transition textures are not valid");
        return false;
    }
    if (!makeCurrent()) {
        setError("eglMakeCurrent failed");
        return false;
    }

    glViewport(0, 0, m_viewportWidth, m_viewportHeight);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(m_transitionProgramId);

    auto applyLayerUniforms = [&](const char* suffix, const Layer& layer) {
        const std::string suffixStr(suffix);
        const bool transformEnabled =
            std::fabs(layer.zoom - 1.0f) > 0.001f ||
            std::fabs(layer.panXNorm) > 0.001f ||
            std::fabs(layer.panYNorm) > 0.001f ||
            std::fabs(layer.rotationDeg) > 0.001f ||
            layer.mirrorX;

        auto setUniform1f = [&](const char* name, float value) {
            const GLint loc = glGetUniformLocation(m_transitionProgramId, (std::string(name) + suffixStr).c_str());
            if (loc >= 0) glUniform1f(loc, value);
        };
        auto setUniform1i = [&](const char* name, int value) {
            const GLint loc = glGetUniformLocation(m_transitionProgramId, (std::string(name) + suffixStr).c_str());
            if (loc >= 0) glUniform1i(loc, value);
        };
        auto setUniform2f = [&](const char* name, float x, float y) {
            const GLint loc = glGetUniformLocation(m_transitionProgramId, (std::string(name) + suffixStr).c_str());
            if (loc >= 0) glUniform2f(loc, x, y);
        };
        auto setUniform3f = [&](const char* name, float x, float y, float z) {
            const GLint loc = glGetUniformLocation(m_transitionProgramId, (std::string(name) + suffixStr).c_str());
            if (loc >= 0) glUniform3f(loc, x, y, z);
        };

        setUniform1f("uOpacity", layer.opacity);
        setUniform1i("uTransformEnabled", transformEnabled ? 1 : 0);
        setUniform2f(
            "uTextureSize",
            static_cast<float>(std::max(1, layer.texture->getWidth())),
            static_cast<float>(std::max(1, layer.texture->getHeight())));
        setUniform1f("uZoom", std::max(1.0f, layer.zoom));
        setUniform2f("uPanNorm", layer.panXNorm, layer.panYNorm);
        setUniform1f("uRotationDeg", layer.rotationDeg);
        setUniform1i("uMirrorX", layer.mirrorX ? 1 : 0);
        setUniform1i("uChromaEnabled", layer.chromaEnabled ? 1 : 0);
        setUniform3f(
            "uChromaKeyColor",
            layer.blueKey ? 0.0f : 0.0f,
            layer.blueKey ? 0.0f : 1.0f,
            layer.blueKey ? 1.0f : 0.0f);
        setUniform1f("uChromaSimilarity", layer.chromaSimilarity);
        setUniform1f("uChromaSmoothness", layer.chromaSmoothness);
        setUniform1f("uChromaSpill", layer.chromaSpill);
        setUniform1f("uBrightness", layer.brightness);
        setUniform1f("uContrast", layer.contrast);
        setUniform1f("uSaturation", layer.saturation);
    };

    const GLint viewportLoc = glGetUniformLocation(m_transitionProgramId, "uViewportSize");
    if (viewportLoc >= 0) {
        glUniform2f(
            viewportLoc,
            static_cast<float>(std::max(1, m_viewportWidth)),
            static_cast<float>(std::max(1, m_viewportHeight)));
    }
    const GLint progressLoc = glGetUniformLocation(m_transitionProgramId, "uProgress");
    if (progressLoc >= 0) {
        glUniform1f(progressLoc, std::clamp(progress, 0.0f, 1.0f));
    }
    const GLint typeLoc = glGetUniformLocation(m_transitionProgramId, "uTransitionType");
    if (typeLoc >= 0) {
        glUniform1i(typeLoc, transitionType);
    }

    outgoing.texture->bind(0);
    incoming.texture->bind(1);
    const GLint texALoc = glGetUniformLocation(m_transitionProgramId, "textureSamplerA");
    if (texALoc >= 0) glUniform1i(texALoc, 0);
    const GLint texBLoc = glGetUniformLocation(m_transitionProgramId, "textureSamplerB");
    if (texBLoc >= 0) glUniform1i(texBLoc, 1);

    applyLayerUniforms("A", outgoing);
    applyLayerUniforms("B", incoming);

    glBindVertexArray(m_vao);
    glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, nullptr);
    glBindVertexArray(0);
    glUseProgram(0);

    if (!checkGLError("renderTransition")) {
        return false;
    }
    if (!eglSwapBuffers(m_eglDisplay, m_eglSurface)) {
        setError("eglSwapBuffers failed: 0x%x", eglGetError());
        return false;
    }
    return true;
}

bool EGLRenderer::acquireContext() {
    if (!m_initialized) {
        setError("Renderer not initialized");
        return false;
    }
    if (!makeCurrent()) {
        setError("eglMakeCurrent failed");
        return false;
    }
    return true;
}

void EGLRenderer::releaseContext() {
    if (m_eglDisplay == EGL_NO_DISPLAY || m_eglDisplay == nullptr) {
        return;
    }
    eglMakeCurrent(
        m_eglDisplay,
        EGL_NO_SURFACE,
        EGL_NO_SURFACE,
        EGL_NO_CONTEXT);
}

void EGLRenderer::setChromaKey(bool enabled, bool blueKey, float similarity, float smoothness, float spill) {
    m_chromaEnabled = enabled;
    m_chromaSimilarity = similarity;
    m_chromaSmoothness = smoothness;
    m_chromaSpill = spill;
    if (blueKey) {
        m_chromaColor[0] = 0.0f;
        m_chromaColor[1] = 0.0f;
        m_chromaColor[2] = 1.0f;
    } else {
        m_chromaColor[0] = 0.0f;
        m_chromaColor[1] = 1.0f;
        m_chromaColor[2] = 0.0f;
    }
}

void EGLRenderer::shutdown() {
    if (!m_initialized) {
        return;
    }

    if (makeCurrent()) {
        releaseResources();
    }

    // Destroy EGL resources
    if (m_eglSurface != EGL_NO_SURFACE) {
        eglDestroySurface(m_eglDisplay, m_eglSurface);
        m_eglSurface = nullptr;
    }

    if (m_eglContext != EGL_NO_CONTEXT) {
        eglDestroyContext(m_eglDisplay, m_eglContext);
        m_eglContext = nullptr;
    }

    if (m_eglDisplay != EGL_NO_DISPLAY) {
        eglTerminate(m_eglDisplay);
        m_eglDisplay = nullptr;
    }
    eglReleaseThread(); // Release EGL state for this thread

    m_nativeWindow = nullptr;
    m_initialized = false;
    std::cout << "[EGLRenderer] Shutdown complete\n";
}

uint32_t EGLRenderer::compileShader(const char* source, uint32_t type) {
    uint32_t shader = glCreateShader(type);
    if (shader == 0) {
        setError("glCreateShader failed");
        return 0;
    }

    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    // Check compilation status
    GLint compiled;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint logLength = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &logLength);
        if (logLength > 0) {
            char* log = new char[logLength];
            glGetShaderInfoLog(shader, logLength, nullptr, log);
            setError("Shader compilation failed: %s", log);
            delete[] log;
        } else {
            setError("Shader compilation failed (no log)");
        }
        glDeleteShader(shader);
        return 0;
    }

    return shader;
}

uint32_t EGLRenderer::createShaderProgram(const char* fragmentSource) {
    uint32_t vertexShader = compileShader(VERTEX_SHADER_SRC, GL_VERTEX_SHADER);
    if (vertexShader == 0) {
        return 0;
    }

    uint32_t fragmentShader = compileShader(fragmentSource, GL_FRAGMENT_SHADER);
    if (fragmentShader == 0) {
        glDeleteShader(vertexShader);
        return 0;
    }

    // Create program and link
    uint32_t program = glCreateProgram();
    glAttachShader(program, vertexShader);
    glAttachShader(program, fragmentShader);
    glLinkProgram(program);

    // Check link status
    GLint linked;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint logLength = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &logLength);
        if (logLength > 0) {
            char* log = new char[logLength];
            glGetProgramInfoLog(program, logLength, nullptr, log);
            setError("Program link failed: %s", log);
            delete[] log;
        } else {
            setError("Program link failed (no log)");
        }
        glDeleteProgram(program);
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        return 0;
    }

    // Clean up shaders (no longer needed after linking)
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    return program;
}

bool EGLRenderer::createQuadMesh() {
    // Create VAO
    glGenVertexArrays(1, &m_vao);
    if (m_vao == 0) {
        setError("glGenVertexArrays failed");
        return false;
    }

    glBindVertexArray(m_vao);

    // Create VBO
    glGenBuffers(1, &m_vbo);
    glBindBuffer(GL_ARRAY_BUFFER, m_vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(QUAD_VERTICES), QUAD_VERTICES, GL_STATIC_DRAW);

    // Create EBO
    glGenBuffers(1, &m_ebo);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, m_ebo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, sizeof(QUAD_INDICES), QUAD_INDICES, GL_STATIC_DRAW);

    // Configure vertex attributes
    // Position (location 0)
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);

    // TexCoord (location 1)
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)(2 * sizeof(float)));

    glBindVertexArray(0);

    if (!checkGLError("createQuadMesh")) {
        glDeleteBuffers(1, &m_vbo);
        glDeleteBuffers(1, &m_ebo);
        glDeleteVertexArrays(1, &m_vao);
        m_vbo = 0;
        m_ebo = 0;
        m_vao = 0;
        return false;
    }

    return true;
}

bool EGLRenderer::makeCurrent() {
    if (m_eglDisplay == nullptr || m_eglContext == nullptr || m_eglSurface == nullptr) {
        return false;
    }

    return eglMakeCurrent(m_eglDisplay, m_eglSurface, m_eglSurface, m_eglContext) == EGL_TRUE;
}

void EGLRenderer::releaseResources() {
    if (m_programId != 0) {
        glDeleteProgram(m_programId);
        m_programId = 0;
    }
    if (m_transitionProgramId != 0) {
        glDeleteProgram(m_transitionProgramId);
        m_transitionProgramId = 0;
    }

    if (m_vao != 0) {
        glDeleteVertexArrays(1, &m_vao);
        m_vao = 0;
    }

    if (m_vbo != 0) {
        glDeleteBuffers(1, &m_vbo);
        m_vbo = 0;
    }

    if (m_ebo != 0) {
        glDeleteBuffers(1, &m_ebo);
        m_ebo = 0;
    }
}

bool EGLRenderer::checkGLError(const char* operation) {
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        setError("GL error in %s: 0x%x", operation, error);
        return false;
    }
    return true;
}

void EGLRenderer::setError(const char* fmt, ...) {
    char buffer[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    m_lastError = buffer;
}

}  // namespace VideoEngine::GPU

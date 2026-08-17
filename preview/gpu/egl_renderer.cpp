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

namespace {
int transitionUniformLocation(uint32_t programId, const char* baseName, const char* suffix) {
    return glGetUniformLocation(programId, (std::string(baseName) + suffix).c_str());
}
}

// Embedded GLSL shaders (GLSL 300 es for OpenGL ES 3.0)

static const char* VERTEX_SHADER_SRC = R"(#version 300 es
precision highp float;

layout(location = 0) in vec2 position;
layout(location = 1) in vec2 texCoord;

out vec2 fragTexCoord;

void main() {
    gl_Position = vec4(position, 0.0, 1.0);
    fragTexCoord = vec2(texCoord.x, 1.0 - texCoord.y);
}
)";

static const char* FRAGMENT_SHADER_SRC = R"(#version 300 es
precision mediump float;

in vec2 fragTexCoord;
out vec4 outColor;

uniform sampler2D textureSampler;
uniform float uOpacity;
uniform bool uTransformEnabled;
uniform vec2 uViewportSize;
uniform vec2 uTextureSize;
uniform float uZoom;
uniform vec2 uScale;
uniform vec2 uPanPx;
uniform float uRotationDeg;
uniform bool uMirrorX;
uniform bool uObjectTransform;
uniform bool uChromaEnabled;
uniform vec3 uChromaKeyColor;
uniform float uChromaSimilarity;
uniform float uChromaSmoothness;
uniform float uChromaSpill;
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;

float chromaKeyMatte(vec3 rgb, vec3 keyColor, float similarityValue, float smoothnessValue) {
    bool blueKey = keyColor.b > keyColor.g;
    vec3 target = vec3(0.12, blueKey ? 0.24 : 0.94, blueKey ? 0.92 : 0.14);
    float similarity = clamp(similarityValue, 0.02, 1.0);
    float smoothness = clamp(smoothnessValue, 0.01, 1.0);

    float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
    float cb = (rgb.b - luma) * 0.564;
    float cr = (rgb.r - luma) * 0.713;
    float keyLuma = dot(target, vec3(0.299, 0.587, 0.114));
    float keyCb = (target.b - keyLuma) * 0.564;
    float keyCr = (target.r - keyLuma) * 0.713;
    float chromaDist = distance(vec2(cb, cr), vec2(keyCb, keyCr));

    float keyDominance = blueKey
        ? (rgb.b - max(rgb.r, rgb.g))
        : (rgb.g - max(rgb.r, rgb.b));
    float dominanceCenter = 0.03 + (similarity * 0.42);
    float dominanceSoftness = 0.015 + (smoothness * 0.20);
    float matteByDominance = smoothstep(
        dominanceCenter - dominanceSoftness,
        dominanceCenter + dominanceSoftness,
        keyDominance);

    float distanceCenter = 0.015 + (similarity * 0.26);
    float distanceSoftness = 0.025 + (smoothness * 0.28);
    float matteByDistance = 1.0 - smoothstep(
        distanceCenter,
        distanceCenter + distanceSoftness,
        chromaDist);

    return clamp(max(matteByDistance, matteByDominance * 0.96), 0.0, 1.0);
}

vec3 despillChroma(vec3 rgb, vec3 keyColor, float matte, float spillValue) {
    float spill = clamp(spillValue, 0.0, 1.0);
    if (spill <= 0.0 || matte <= 0.0) {
        return rgb;
    }
    float spillMix = matte * spill;
    if (keyColor.b > keyColor.g) {
        rgb.b = mix(rgb.b, (rgb.r + rgb.g) * 0.5, spillMix);
    } else {
        rgb.g = mix(rgb.g, (rgb.r + rgb.b) * 0.5, spillMix);
    }
    return rgb;
}

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
        float minZoom = uObjectTransform ? 0.15 : 0.35;
        vec2 renderedSize = max(
            baseRenderedSize * max(uZoom, minZoom) * max(uScale, vec2(0.15)),
            vec2(1.0));
        vec2 maxPanPx;
        if (uObjectTransform) {
            maxPanPx = (renderedSize * 0.5) + (viewportSize * 0.92);
        } else {
            maxPanPx = max((renderedSize - viewportSize) * 0.5, vec2(0.0));
        }
        vec2 localPx = (fragTexCoord - vec2(0.5)) * viewportSize;
        localPx -= clamp(uPanPx, -maxPanPx, maxPanPx);

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
        float matte = chromaKeyMatte(color.rgb, uChromaKeyColor, uChromaSimilarity, uChromaSmoothness);
        float alpha = 1.0 - matte;
        color.rgb = despillChroma(color.rgb, uChromaKeyColor, matte, uChromaSpill);
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

static const char* TRANSITION_FRAGMENT_SHADER_SRC = R"(#version 300 es
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
uniform vec2 uScaleA;
uniform vec2 uPanPxA;
uniform float uRotationDegA;
uniform bool uMirrorXA;
uniform bool uObjectTransformA;
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
uniform vec2 uScaleB;
uniform vec2 uPanPxB;
uniform float uRotationDegB;
uniform bool uMirrorXB;
uniform bool uObjectTransformB;
uniform bool uChromaEnabledB;
uniform vec3 uChromaKeyColorB;
uniform float uChromaSimilarityB;
uniform float uChromaSmoothnessB;
uniform float uChromaSpillB;
uniform float uBrightnessB;
uniform float uContrastB;
uniform float uSaturationB;

float chromaKeyMatte(vec3 rgb, vec3 keyColor, float similarityValue, float smoothnessValue) {
    bool blueKey = keyColor.b > keyColor.g;
    vec3 target = vec3(0.12, blueKey ? 0.24 : 0.94, blueKey ? 0.92 : 0.14);
    float similarity = clamp(similarityValue, 0.02, 1.0);
    float smoothness = clamp(smoothnessValue, 0.01, 1.0);

    float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
    float cb = (rgb.b - luma) * 0.564;
    float cr = (rgb.r - luma) * 0.713;
    float keyLuma = dot(target, vec3(0.299, 0.587, 0.114));
    float keyCb = (target.b - keyLuma) * 0.564;
    float keyCr = (target.r - keyLuma) * 0.713;
    float chromaDist = distance(vec2(cb, cr), vec2(keyCb, keyCr));

    float keyDominance = blueKey
        ? (rgb.b - max(rgb.r, rgb.g))
        : (rgb.g - max(rgb.r, rgb.b));
    float dominanceCenter = 0.03 + (similarity * 0.42);
    float dominanceSoftness = 0.015 + (smoothness * 0.20);
    float matteByDominance = smoothstep(
        dominanceCenter - dominanceSoftness,
        dominanceCenter + dominanceSoftness,
        keyDominance);

    float distanceCenter = 0.015 + (similarity * 0.26);
    float distanceSoftness = 0.025 + (smoothness * 0.28);
    float matteByDistance = 1.0 - smoothstep(
        distanceCenter,
        distanceCenter + distanceSoftness,
        chromaDist);

    return clamp(max(matteByDistance, matteByDominance * 0.96), 0.0, 1.0);
}

vec3 despillChroma(vec3 rgb, vec3 keyColor, float matte, float spillValue) {
    float spill = clamp(spillValue, 0.0, 1.0);
    if (spill <= 0.0 || matte <= 0.0) {
        return rgb;
    }
    float spillMix = matte * spill;
    if (keyColor.b > keyColor.g) {
        rgb.b = mix(rgb.b, (rgb.r + rgb.g) * 0.5, spillMix);
    } else {
        rgb.g = mix(rgb.g, (rgb.r + rgb.b) * 0.5, spillMix);
    }
    return rgb;
}

vec2 resolveSampleCoord(
    vec2 baseCoord,
    bool transformEnabled,
    vec2 textureSize,
    float zoom,
    vec2 scale,
    vec2 panPx,
    float rotationDeg,
    bool mirrorX,
    bool objectTransform) {
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

    float minZoom = objectTransform ? 0.15 : 0.35;
    vec2 renderedSize = max(
        baseRenderedSize * max(zoom, minZoom) * max(scale, vec2(0.15)),
        vec2(1.0));
    vec2 maxPanPx;
    if (objectTransform) {
        maxPanPx = (renderedSize * 0.5) + (viewportSize * 0.92);
    } else {
        maxPanPx = max((renderedSize - viewportSize) * 0.5, vec2(0.0));
    }
    vec2 localPx = (baseCoord - vec2(0.5)) * viewportSize;
    localPx -= clamp(panPx, -maxPanPx, maxPanPx);

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
    vec2 scale,
    vec2 panPx,
    float rotationDeg,
    bool mirrorX,
    bool objectTransform,
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
        scale,
        panPx,
        rotationDeg,
        mirrorX,
        objectTransform);
    if (sampleCoord.x < 0.0 || sampleCoord.x > 1.0 ||
        sampleCoord.y < 0.0 || sampleCoord.y > 1.0) {
        return vec4(0.0);
    }

    vec4 color = texture(textureSampler, sampleCoord);
    if (chromaEnabled) {
        float matte = chromaKeyMatte(color.rgb, chromaKeyColor, chromaSimilarity, chromaSmoothness);
        float alpha = 1.0 - matte;
        color.rgb = despillChroma(color.rgb, chromaKeyColor, matte, chromaSpill);
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
        uScaleA,
        uPanPxA,
        uRotationDegA,
        uMirrorXA,
        uObjectTransformA,
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
        uScaleB,
        uPanPxB,
        uRotationDegB,
        uMirrorXB,
        uObjectTransformB,
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
        uScaleA,
        uPanPxA,
        uRotationDegA,
        uMirrorXA,
        uObjectTransformA,
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
        uScaleB,
        uPanPxB,
        uRotationDegB,
        uMirrorXB,
        uObjectTransformB,
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
        float wipeMix = 1.0 - smoothstep(progress - feather, progress + feather, fragTexCoord.x);
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
        if (m_lastError.empty()) {
            setError("Failed to create shader program");
        }
        releaseResources();
        return false;
    }
    m_transitionProgramId = createShaderProgram(TRANSITION_FRAGMENT_SHADER_SRC);
    if (m_transitionProgramId == 0) {
        if (m_lastError.empty()) {
            setError("Failed to create transition shader program");
        }
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
    m_uTextureSamplerLoc = glGetUniformLocation(m_programId, "textureSampler");
    m_uTransformEnabledLoc = glGetUniformLocation(m_programId, "uTransformEnabled");
    m_uViewportSizeLoc = glGetUniformLocation(m_programId, "uViewportSize");
    m_uTextureSizeLoc = glGetUniformLocation(m_programId, "uTextureSize");
    m_uZoomLoc = glGetUniformLocation(m_programId, "uZoom");
    m_uScaleLoc = glGetUniformLocation(m_programId, "uScale");
    m_uPanPxLoc = glGetUniformLocation(m_programId, "uPanPx");
    m_uRotationDegLoc = glGetUniformLocation(m_programId, "uRotationDeg");
    m_uMirrorXLoc = glGetUniformLocation(m_programId, "uMirrorX");
    m_uObjectTransformLoc = glGetUniformLocation(m_programId, "uObjectTransform");
    m_uTransitionViewportLoc = glGetUniformLocation(m_transitionProgramId, "uViewportSize");
    m_uTransitionProgressLoc = glGetUniformLocation(m_transitionProgramId, "uProgress");
    m_uTransitionTypeLoc = glGetUniformLocation(m_transitionProgramId, "uTransitionType");
    m_uTransitionTextureSamplerALoc = glGetUniformLocation(m_transitionProgramId, "textureSamplerA");
    m_uTransitionTextureSamplerBLoc = glGetUniformLocation(m_transitionProgramId, "textureSamplerB");

    auto cacheTransitionUniforms = [&](const char* suffix, TransitionLayerUniformSet& uniforms) {
        uniforms.opacity = transitionUniformLocation(m_transitionProgramId, "uOpacity", suffix);
        uniforms.transformEnabled = transitionUniformLocation(m_transitionProgramId, "uTransformEnabled", suffix);
        uniforms.textureSize = transitionUniformLocation(m_transitionProgramId, "uTextureSize", suffix);
        uniforms.zoom = transitionUniformLocation(m_transitionProgramId, "uZoom", suffix);
        uniforms.scale = transitionUniformLocation(m_transitionProgramId, "uScale", suffix);
        uniforms.panPx = transitionUniformLocation(m_transitionProgramId, "uPanPx", suffix);
        uniforms.rotationDeg = transitionUniformLocation(m_transitionProgramId, "uRotationDeg", suffix);
        uniforms.mirrorX = transitionUniformLocation(m_transitionProgramId, "uMirrorX", suffix);
        uniforms.objectTransform = transitionUniformLocation(m_transitionProgramId, "uObjectTransform", suffix);
        uniforms.chromaEnabled = transitionUniformLocation(m_transitionProgramId, "uChromaEnabled", suffix);
        uniforms.chromaKeyColor = transitionUniformLocation(m_transitionProgramId, "uChromaKeyColor", suffix);
        uniforms.chromaSimilarity = transitionUniformLocation(m_transitionProgramId, "uChromaSimilarity", suffix);
        uniforms.chromaSmoothness = transitionUniformLocation(m_transitionProgramId, "uChromaSmoothness", suffix);
        uniforms.chromaSpill = transitionUniformLocation(m_transitionProgramId, "uChromaSpill", suffix);
        uniforms.brightness = transitionUniformLocation(m_transitionProgramId, "uBrightness", suffix);
        uniforms.contrast = transitionUniformLocation(m_transitionProgramId, "uContrast", suffix);
        uniforms.saturation = transitionUniformLocation(m_transitionProgramId, "uSaturation", suffix);
    };
    cacheTransitionUniforms("A", m_transitionUniformsA);
    cacheTransitionUniforms("B", m_transitionUniformsB);

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
    if (m_nativeWindow) {
        ANativeWindow_setBuffersGeometry(
            m_nativeWindow,
            width,
            height,
            WINDOW_FORMAT_RGBA_8888);
    }
    if (m_initialized && acquireContext()) {
        glViewport(0, 0, width, height);
        releaseContext();
    }
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
    glUniform1i(m_uTextureSamplerLoc, 0);

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

    glUniform1i(m_uTextureSamplerLoc, 0);

    for (const auto& layer : layers) {
        if (!layer.texture || !layer.texture->isValid()) continue;

        if (m_uOpacityLoc >= 0)
            glUniform1f(m_uOpacityLoc, layer.opacity);
        const bool transformEnabled =
            std::fabs(layer.zoom - 1.0f) > 0.001f ||
            std::fabs(layer.scaleX - 1.0f) > 0.001f ||
            std::fabs(layer.scaleY - 1.0f) > 0.001f ||
            std::fabs(layer.panXPx) > 0.5f ||
            std::fabs(layer.panYPx) > 0.5f ||
            std::fabs(layer.rotationDeg) > 0.001f ||
            layer.mirrorX;
        if (m_uTransformEnabledLoc >= 0)
            glUniform1i(m_uTransformEnabledLoc, transformEnabled ? 1 : 0);
        if (m_uViewportSizeLoc >= 0)
            glUniform2f(
                m_uViewportSizeLoc,
                static_cast<float>(std::max(1, m_viewportWidth)),
                static_cast<float>(std::max(1, m_viewportHeight)));
        if (m_uTextureSizeLoc >= 0)
            glUniform2f(
                m_uTextureSizeLoc,
                static_cast<float>(std::max(1, layer.texture->getWidth())),
                static_cast<float>(std::max(1, layer.texture->getHeight())));
        if (m_uZoomLoc >= 0)
            glUniform1f(m_uZoomLoc, std::max(layer.objectTransform ? 0.15f : 0.35f, layer.zoom));
        if (m_uScaleLoc >= 0)
            glUniform2f(m_uScaleLoc, std::max(0.15f, layer.scaleX), std::max(0.15f, layer.scaleY));
        if (m_uPanPxLoc >= 0)
            glUniform2f(m_uPanPxLoc, layer.panXPx, layer.panYPx);
        if (m_uRotationDegLoc >= 0)
            glUniform1f(m_uRotationDegLoc, layer.rotationDeg);
        if (m_uMirrorXLoc >= 0)
            glUniform1i(m_uMirrorXLoc, layer.mirrorX ? 1 : 0);
        if (m_uObjectTransformLoc >= 0)
            glUniform1i(m_uObjectTransformLoc, layer.objectTransform ? 1 : 0);
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

    auto applyLayerUniforms = [&](const TransitionLayerUniformSet& uniforms, const Layer& layer) {
        const bool transformEnabled =
            std::fabs(layer.zoom - 1.0f) > 0.001f ||
            std::fabs(layer.scaleX - 1.0f) > 0.001f ||
            std::fabs(layer.scaleY - 1.0f) > 0.001f ||
            std::fabs(layer.panXPx) > 0.5f ||
            std::fabs(layer.panYPx) > 0.5f ||
            std::fabs(layer.rotationDeg) > 0.001f ||
            layer.mirrorX;

        if (uniforms.opacity >= 0) glUniform1f(uniforms.opacity, layer.opacity);
        if (uniforms.transformEnabled >= 0) glUniform1i(uniforms.transformEnabled, transformEnabled ? 1 : 0);
        if (uniforms.textureSize >= 0) glUniform2f(
            uniforms.textureSize,
            static_cast<float>(std::max(1, layer.texture->getWidth())),
            static_cast<float>(std::max(1, layer.texture->getHeight())));
        if (uniforms.zoom >= 0) glUniform1f(uniforms.zoom, std::max(layer.objectTransform ? 0.15f : 0.35f, layer.zoom));
        if (uniforms.scale >= 0) glUniform2f(uniforms.scale, std::max(0.15f, layer.scaleX), std::max(0.15f, layer.scaleY));
        if (uniforms.panPx >= 0) glUniform2f(uniforms.panPx, layer.panXPx, layer.panYPx);
        if (uniforms.rotationDeg >= 0) glUniform1f(uniforms.rotationDeg, layer.rotationDeg);
        if (uniforms.mirrorX >= 0) glUniform1i(uniforms.mirrorX, layer.mirrorX ? 1 : 0);
        if (uniforms.objectTransform >= 0) glUniform1i(uniforms.objectTransform, layer.objectTransform ? 1 : 0);
        if (uniforms.chromaEnabled >= 0) glUniform1i(uniforms.chromaEnabled, layer.chromaEnabled ? 1 : 0);
        if (uniforms.chromaKeyColor >= 0) glUniform3f(
            uniforms.chromaKeyColor,
            layer.blueKey ? 0.0f : 0.0f,
            layer.blueKey ? 0.0f : 1.0f,
            layer.blueKey ? 1.0f : 0.0f);
        if (uniforms.chromaSimilarity >= 0) glUniform1f(uniforms.chromaSimilarity, layer.chromaSimilarity);
        if (uniforms.chromaSmoothness >= 0) glUniform1f(uniforms.chromaSmoothness, layer.chromaSmoothness);
        if (uniforms.chromaSpill >= 0) glUniform1f(uniforms.chromaSpill, layer.chromaSpill);
        if (uniforms.brightness >= 0) glUniform1f(uniforms.brightness, layer.brightness);
        if (uniforms.contrast >= 0) glUniform1f(uniforms.contrast, layer.contrast);
        if (uniforms.saturation >= 0) glUniform1f(uniforms.saturation, layer.saturation);
    };

    if (m_uTransitionViewportLoc >= 0) {
        glUniform2f(
            m_uTransitionViewportLoc,
            static_cast<float>(std::max(1, m_viewportWidth)),
            static_cast<float>(std::max(1, m_viewportHeight)));
    }
    if (m_uTransitionProgressLoc >= 0) {
        glUniform1f(m_uTransitionProgressLoc, std::clamp(progress, 0.0f, 1.0f));
    }
    if (m_uTransitionTypeLoc >= 0) {
        glUniform1i(m_uTransitionTypeLoc, transitionType);
    }

    outgoing.texture->bind(0);
    incoming.texture->bind(1);
    if (m_uTransitionTextureSamplerALoc >= 0) glUniform1i(m_uTransitionTextureSamplerALoc, 0);
    if (m_uTransitionTextureSamplerBLoc >= 0) glUniform1i(m_uTransitionTextureSamplerBLoc, 1);

    applyLayerUniforms(m_transitionUniformsA, outgoing);
    applyLayerUniforms(m_transitionUniformsB, incoming);

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

    m_uOpacityLoc = -1;
    m_uChromaEnabledLoc = -1;
    m_uChromaKeyColorLoc = -1;
    m_uChromaSimilarityLoc = -1;
    m_uChromaSmoothnessLoc = -1;
    m_uChromaSpillLoc = -1;
    m_uBrightnessLoc = -1;
    m_uContrastLoc = -1;
    m_uSaturationLoc = -1;
    m_uTextureSamplerLoc = -1;
    m_uTransformEnabledLoc = -1;
    m_uViewportSizeLoc = -1;
    m_uTextureSizeLoc = -1;
    m_uZoomLoc = -1;
    m_uScaleLoc = -1;
    m_uPanPxLoc = -1;
    m_uRotationDegLoc = -1;
    m_uMirrorXLoc = -1;
    m_uObjectTransformLoc = -1;
    m_uTransitionViewportLoc = -1;
    m_uTransitionProgressLoc = -1;
    m_uTransitionTypeLoc = -1;
    m_uTransitionTextureSamplerALoc = -1;
    m_uTransitionTextureSamplerBLoc = -1;
    m_transitionUniformsA = {};
    m_transitionUniformsB = {};
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

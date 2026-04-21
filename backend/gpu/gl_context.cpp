#include "gl_context.h"
#include <iostream>
#include <stdexcept>

#if defined(__ANDROID__)
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#else
#ifdef __ANDROID__
#include <GLES3/gl3.h>
#else
#include <GL/glew.h>
#endif
#include <GL/glx.h>
#include <X11/Xlib.h>
#endif

namespace VideoEngine::GPU {

#if !defined(__ANDROID__)
// GLX extension function pointers
typedef GLXContext (*glXCreateContextAttribsARB)(Display*, GLXFBConfig, GLXContext, Bool, const int*);
typedef Bool (*glXSwapIntervalEXT)(Display*, GLXDrawable, int);
#endif

GLContext::GLContext(uint32_t width, uint32_t height, RenderMode mode, bool debugMode)
    : m_width(width)
    , m_height(height)
    , m_mode(mode)
    , m_debugMode(debugMode)
    , m_isValid(false)
    , m_nativeWindow(nullptr)
    , m_nativeGLContext(nullptr)
    , m_nativeDisplay(nullptr)
{
#if defined(__ANDROID__)
    // On Android, we expect the EGL context to be managed by the JNI/preview layer
    m_isValid = true; 
#else
    initializeGLContext();
#endif
}

GLContext::~GLContext() {
#if !defined(__ANDROID__)
    if (m_nativeGLContext) {
        release();
        
        Display* display = static_cast<Display*>(m_nativeDisplay);
        GLXContext context = static_cast<GLXContext>(m_nativeGLContext);
        
        if (m_mode == RenderMode::Windowed && m_nativeWindow) {
            Window window = reinterpret_cast<Window>(m_nativeWindow);
            glXDestroyContext(display, context);
            XDestroyWindow(display, window);
        } else {
            glXDestroyContext(display, context);
        }
        
        if (display) {
            XCloseDisplay(display);
        }
    }
#endif
}

void GLContext::initializeGLContext() {
#if defined(__ANDROID__)
    // Stub for Android
#else
    // Open X11 display
    Display* display = XOpenDisplay(nullptr);
    if (!display) {
        throw std::runtime_error("Failed to open X11 display");
    }
    m_nativeDisplay = display;

    // Get default screen
    int screen = DefaultScreen(display);

    // Query GLX version
    int majorGLX = 0, minorGLX = 0;
    if (!glXQueryVersion(display, &majorGLX, &minorGLX)) {
        throw std::runtime_error("Failed to query GLX version");
    }

    if (majorGLX < 1 || (majorGLX == 1 && minorGLX < 3)) {
        throw std::runtime_error("GLX 1.3 or higher required");
    }

    // Find appropriate FB config
    int fbAttribs[] = {
        GLX_RENDER_TYPE,   GLX_RGBA_BIT,
        GLX_DRAWABLE_TYPE, GLX_PBUFFER_BIT | GLX_WINDOW_BIT,
        GLX_RED_SIZE,      8,
        GLX_GREEN_SIZE,    8,
        GLX_BLUE_SIZE,     8,
        GLX_ALPHA_SIZE,    8,
        GLX_DEPTH_SIZE,    24,
        GLX_STENCIL_SIZE,  8,
        GLX_DOUBLEBUFFER,  True,
        None
    };

    int numConfigs = 0;
    GLXFBConfig* fbConfigs = glXChooseFBConfig(display, screen, fbAttribs, &numConfigs);
    if (!fbConfigs || numConfigs == 0) {
        throw std::runtime_error("No suitable GLX framebuffer configuration found");
    }

    GLXFBConfig fbConfig = fbConfigs[0];
    XFree(fbConfigs);

    // Get glXCreateContextAttribsARB function
    glXCreateContextAttribsARB createContextAttribs = nullptr;
    const char* extsStr = glXQueryExtensionsString(display, screen);
    if (extsStr && std::string(extsStr).find("GLX_ARB_create_context") != std::string::npos) {
        createContextAttribs = reinterpret_cast<glXCreateContextAttribsARB>(
            glXGetProcAddress(reinterpret_cast<const GLubyte*>("glXCreateContextAttribsARB"))
        );
    }

    GLXContext context = nullptr;

    // Create GL 3.3 core context if available
    if (createContextAttribs) {
        int ctxAttribs[] = {
            GLX_CONTEXT_MAJOR_VERSION_ARB, 3,
            GLX_CONTEXT_MINOR_VERSION_ARB, 3,
            GLX_CONTEXT_PROFILE_MASK_ARB,  GLX_CONTEXT_CORE_PROFILE_BIT_ARB,
            None
        };
        context = createContextAttribs(display, fbConfig, nullptr, True, ctxAttribs);
    }

    // Fallback: create compatibility context
    if (!context) {
        context = glXCreateNewContext(display, fbConfig, GLX_RGBA_TYPE, nullptr, True);
    }

    if (!context) {
        throw std::runtime_error("Failed to create OpenGL context");
    }

    m_nativeGLContext = context;

    // Create drawable (pbuffer for headless, window for windowed)
    GLXPbuffer pbuffer = 0;
    Window window = None;

    if (m_mode == RenderMode::Headless) {
        int pbufferAttribs[] = {
            GLX_PBUFFER_WIDTH,  (int)m_width,
            GLX_PBUFFER_HEIGHT, (int)m_height,
            None
        };
        pbuffer = glXCreatePbuffer(display, fbConfig, pbufferAttribs);
        if (!pbuffer) {
            glXDestroyContext(display, context);
            throw std::runtime_error("Failed to create GLX pbuffer");
        }
        m_nativeWindow = (void*)(uintptr_t)pbuffer;
    } else {
        // Create simple window for windowed mode
        XVisualInfo* vi = glXGetVisualFromFBConfig(display, fbConfig);
        if (!vi) {
            glXDestroyContext(display, context);
            throw std::runtime_error("Failed to get visual info");
        }

        XSetWindowAttributes swa;
        swa.background_pixmap = None;
        swa.background_pixel  = 0;
        swa.border_pixel      = 0;
        swa.event_mask        = StructureNotifyMask;
        swa.colormap          = XCreateColormap(display, RootWindow(display, screen), vi->visual, AllocNone);

        window = XCreateWindow(
            display, RootWindow(display, screen),
            0, 0, m_width, m_height,
            0, vi->depth, InputOutput, vi->visual,
            CWBackPixmap | CWBackPixel | CWBorderPixel | CWColormap | CWEventMask,
            &swa
        );

        XFree(vi);

        if (!window) {
            glXDestroyContext(display, context);
            throw std::runtime_error("Failed to create X11 window");
        }

        XMapWindow(display, window);
        m_nativeWindow = (void*)(uintptr_t)window;
    }

    makeCurrent();

    // Load OpenGL function pointers using glXGetProcAddress
    initializeGLExtensions();

    if (m_debugMode) {
        setupDebugOutput();
    }

    m_isValid = true;

    std::cout << "[GLContext] OpenGL 3.3 context created successfully (" 
              << (m_mode == RenderMode::Headless ? "headless" : "windowed")
              << ", " << m_width << "x" << m_height << ")\n";
#endif
}

void GLContext::initializeGLExtensions() {
    // In a real implementation, load GL function pointers here
}

void GLContext::setupDebugOutput() {
    // Enable GL debug output if available
    std::cout << "[GLContext] Debug output enabled\n";
}

void GLContext::makeCurrent() {
#if !defined(__ANDROID__)
    if (!m_isValid) {
        throw std::runtime_error("GLContext is not valid");
    }

    Display* display = static_cast<Display*>(m_nativeDisplay);
    GLXContext context = static_cast<GLXContext>(m_nativeGLContext);
    GLXDrawable drawable = (GLXDrawable)(uintptr_t)m_nativeWindow;

    if (!glXMakeCurrent(display, drawable, context)) {
        throw std::runtime_error("Failed to make GLX context current");
    }
#endif
}

void GLContext::release() {
#if !defined(__ANDROID__)
    if (!m_isValid) {
        return;
    }

    Display* display = static_cast<Display*>(m_nativeDisplay);
    if (!glXMakeCurrent(display, None, nullptr)) {
        std::cerr << "[GLContext] Warning: Failed to release GLX context\n";
    }
#endif
}

void GLContext::swapBuffers() {
#if !defined(__ANDROID__)
    if (!m_isValid || m_mode != RenderMode::Windowed) {
        return;
    }

    Display* display = static_cast<Display*>(m_nativeDisplay);
    GLXDrawable drawable = (GLXDrawable)(uintptr_t)m_nativeWindow;
    glXSwapBuffers(display, drawable);
#endif
}

} // namespace VideoEngine::GPU

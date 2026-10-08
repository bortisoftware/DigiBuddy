#include "libretro.h"
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <cerrno>
#include <cstdarg>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <fstream>
#include <jni.h>
#include <map>
#include <mutex>
#include <new>
#include <string>
#include <unistd.h>
#include <vector>

static std::mutex guard;
static void *library = nullptr;
static void *glLibrary = nullptr;
static constexpr size_t MAX_STATE_BYTES = 64 * 1024 * 1024;
static constexpr size_t MAX_CARD_BYTES = 1024 * 1024;
static constexpr size_t MAX_AUDIO_SAMPLES = 65536;
static ANativeWindow *window = nullptr;
static bool loaded = false;
static unsigned buttons = 0;
static retro_pixel_format pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
static std::string systemDir, saveDir, gamePath;
static std::map<std::string, std::string> options;
static std::map<std::string, std::string> overrides;
static bool optionsChanged = false;
static std::vector<int16_t> samples;
static retro_system_av_info av = {};
static retro_hw_render_callback hardware = {};
static bool hardwareRequested = false, hardwareReady = false;
static EGLDisplay eglDisplay = EGL_NO_DISPLAY;
static EGLContext eglContext = EGL_NO_CONTEXT;
static EGLSurface eglOffscreen = EGL_NO_SURFACE, eglWindow = EGL_NO_SURFACE;
static EGLConfig eglConfig;
static unsigned windowGeneration = 0, eglWindowGeneration = 0;
static GLuint framebuffer = 0, colorTexture = 0, depthBuffer = 0;
static unsigned framebufferWidth = 0, framebufferHeight = 0;

static retro_proc_address_t glProc(const char *name) {
  auto address = eglGetProcAddress(name);
  if (!address) {
    if (!glLibrary)
      glLibrary = dlopen("libGLESv3.so", RTLD_NOW | RTLD_LOCAL);
    if (glLibrary)
      address = reinterpret_cast<decltype(address)>(dlsym(glLibrary, name));
  }
  return reinterpret_cast<retro_proc_address_t>(address);
}
static void destroyGL();
static bool createGL(const retro_hw_render_callback &requested) {
  if (eglContext != EGL_NO_CONTEXT)
    return true;
  eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (eglDisplay == EGL_NO_DISPLAY ||
      !eglInitialize(eglDisplay, nullptr, nullptr)) {
    destroyGL();
    return false;
  }
  eglBindAPI(EGL_OPENGL_ES_API);
  const EGLint configAttributes[] = {EGL_SURFACE_TYPE,
                                     EGL_PBUFFER_BIT | EGL_WINDOW_BIT,
                                     EGL_RENDERABLE_TYPE,
                                     EGL_OPENGL_ES3_BIT_KHR,
                                     EGL_RED_SIZE,
                                     8,
                                     EGL_GREEN_SIZE,
                                     8,
                                     EGL_BLUE_SIZE,
                                     8,
                                     EGL_ALPHA_SIZE,
                                     8,
                                     EGL_NONE};
  EGLint count = 0;
  if (!eglChooseConfig(eglDisplay, configAttributes, &eglConfig, 1, &count) ||
      !count) {
    destroyGL();
    return false;
  }
  EGLint major = requested.context_type == RETRO_HW_CONTEXT_OPENGLES_VERSION
                     ? requested.version_major
                     : 3;
  EGLint minor = requested.context_type == RETRO_HW_CONTEXT_OPENGLES_VERSION
                     ? requested.version_minor
                     : 0;
  const EGLint contextAttributes[] = {EGL_CONTEXT_MAJOR_VERSION_KHR, major,
                                      EGL_CONTEXT_MINOR_VERSION_KHR, minor,
                                      EGL_NONE};
  eglContext = eglCreateContext(eglDisplay, eglConfig, EGL_NO_CONTEXT,
                                contextAttributes);
  if (eglContext == EGL_NO_CONTEXT) {
    destroyGL();
    return false;
  }
  const EGLint pbufferAttributes[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
  eglOffscreen =
      eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttributes);
  if (eglOffscreen == EGL_NO_SURFACE) {
    destroyGL();
    return false;
  }
  if (!eglMakeCurrent(eglDisplay, eglOffscreen, eglOffscreen, eglContext)) {
    destroyGL();
    return false;
  }
  return true;
}
static void prepareGLWindow() {
  if (eglContext == EGL_NO_CONTEXT)
    return;
  if (eglWindowGeneration != windowGeneration) {
    eglMakeCurrent(eglDisplay, eglOffscreen, eglOffscreen, eglContext);
    if (eglWindow != EGL_NO_SURFACE)
      eglDestroySurface(eglDisplay, eglWindow);
    eglWindow = EGL_NO_SURFACE;
    if (window) {
      EGLint format;
      eglGetConfigAttrib(eglDisplay, eglConfig, EGL_NATIVE_VISUAL_ID, &format);
      ANativeWindow_setBuffersGeometry(window, 0, 0, format);
      eglWindow =
          eglCreateWindowSurface(eglDisplay, eglConfig, window, nullptr);
    }
    eglWindowGeneration = windowGeneration;
  }
  EGLSurface target = eglWindow != EGL_NO_SURFACE ? eglWindow : eglOffscreen;
  eglMakeCurrent(eglDisplay, target, target, eglContext);
  eglSwapInterval(eglDisplay, 0);
}
static uintptr_t currentFramebuffer() {
  unsigned width = av.geometry.max_width ? av.geometry.max_width : 1024;
  unsigned height = av.geometry.max_height ? av.geometry.max_height : 512;
  if (width > 8192 || height > 8192 ||
      static_cast<size_t>(width) * height > 32 * 1024 * 1024)
    return 0;
  if (framebuffer && width == framebufferWidth && height == framebufferHeight)
    return framebuffer;
  GLint previousFbo, previousReadFbo, previousTexture, previousRenderbuffer;
  glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &previousFbo);
  glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &previousReadFbo);
  glGetIntegerv(GL_TEXTURE_BINDING_2D, &previousTexture);
  glGetIntegerv(GL_RENDERBUFFER_BINDING, &previousRenderbuffer);
  if (!framebuffer)
    glGenFramebuffers(1, &framebuffer);
  if (!colorTexture)
    glGenTextures(1, &colorTexture);
  glBindTexture(GL_TEXTURE_2D, colorTexture);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA,
               GL_UNSIGNED_BYTE, nullptr);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                         colorTexture, 0);
  if (hardware.depth || hardware.stencil) {
    if (!depthBuffer)
      glGenRenderbuffers(1, &depthBuffer);
    glBindRenderbuffer(GL_RENDERBUFFER, depthBuffer);
    glRenderbufferStorage(GL_RENDERBUFFER,
                          hardware.stencil ? GL_DEPTH24_STENCIL8
                                           : GL_DEPTH_COMPONENT24,
                          width, height);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER,
                              hardware.stencil ? GL_DEPTH_STENCIL_ATTACHMENT
                                               : GL_DEPTH_ATTACHMENT,
                              GL_RENDERBUFFER, depthBuffer);
  }
  GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
  glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousFbo);
  glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFbo);
  glBindTexture(GL_TEXTURE_2D, previousTexture);
  glBindRenderbuffer(GL_RENDERBUFFER, previousRenderbuffer);
  if (status != GL_FRAMEBUFFER_COMPLETE) {
    __android_log_print(ANDROID_LOG_ERROR, "DigiMapCore",
                        "Framebuffer error %x", status);
    return 0;
  }
  framebufferWidth = width;
  framebufferHeight = height;
  return framebuffer;
}
static void destroyGL() {
  if (eglContext != EGL_NO_CONTEXT) {
    eglMakeCurrent(eglDisplay, eglOffscreen, eglOffscreen, eglContext);
    if (framebuffer)
      glDeleteFramebuffers(1, &framebuffer);
    if (colorTexture)
      glDeleteTextures(1, &colorTexture);
    if (depthBuffer)
      glDeleteRenderbuffers(1, &depthBuffer);
    eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(eglDisplay, eglContext);
  }
  if (eglDisplay != EGL_NO_DISPLAY) {
    if (eglWindow != EGL_NO_SURFACE)
      eglDestroySurface(eglDisplay, eglWindow);
    if (eglOffscreen != EGL_NO_SURFACE)
      eglDestroySurface(eglDisplay, eglOffscreen);
    eglTerminate(eglDisplay);
  }
  if (glLibrary) {
    dlclose(glLibrary);
    glLibrary = nullptr;
  }
  eglDisplay = EGL_NO_DISPLAY;
  eglContext = EGL_NO_CONTEXT;
  eglWindow = eglOffscreen = EGL_NO_SURFACE;
  framebuffer = colorTexture = depthBuffer = 0;
  framebufferWidth = framebufferHeight = 0;
  hardwareReady = hardwareRequested = false;
  hardware = {};
  eglWindowGeneration = 0;
}

static void (*core_init)();
static void (*core_deinit)();
static bool (*core_load)(const retro_game_info *);
static void (*core_unload)();
static void (*core_run)();
static void (*core_av)(retro_system_av_info *);
static void *(*core_memory)(unsigned);
static size_t (*core_memory_size)(unsigned);
static size_t (*core_state_size)();
static bool (*core_serialize)(void *, size_t);
static bool (*core_unserialize)(const void *, size_t);
static void (*core_controller)(unsigned, unsigned);

static void logMessage(enum retro_log_level, const char *fmt, ...)
    __attribute__((format(printf, 2, 3)));
static void logMessage(enum retro_log_level, const char *fmt, ...) {
  va_list args;
  va_start(args, fmt);
  __android_log_vprint(ANDROID_LOG_INFO, "DigiMapCore", fmt, args);
  va_end(args);
}

static bool environment(unsigned cmd, void *data) {
  switch (cmd) {
  case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    *static_cast<const char **>(data) = systemDir.c_str();
    return true;
  case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
    *static_cast<const char **>(data) = saveDir.c_str();
    return true;
  case RETRO_ENVIRONMENT_GET_CAN_DUPE:
    *static_cast<bool *>(data) = true;
    return true;
  case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
    pixelFormat = *static_cast<retro_pixel_format *>(data);
    return pixelFormat == RETRO_PIXEL_FORMAT_RGB565 ||
           pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888 ||
           pixelFormat == RETRO_PIXEL_FORMAT_0RGB1555;
  case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
    static_cast<retro_log_callback *>(data)->log = logMessage;
    return true;
  case RETRO_ENVIRONMENT_SET_HW_RENDER: {
    auto *requested = static_cast<retro_hw_render_callback *>(data);
    if (requested->context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
        requested->context_type != RETRO_HW_CONTEXT_OPENGLES_VERSION)
      return false;
    if (!createGL(*requested))
      return false;
    requested->get_current_framebuffer = currentFramebuffer;
    requested->get_proc_address = glProc;
    hardware = *requested;
    hardwareRequested = true;
    return true;
  }
  case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
    *static_cast<unsigned *>(data) = 0;
    return true;
  case RETRO_ENVIRONMENT_SET_VARIABLES: {
    const auto *vars = static_cast<const retro_variable *>(data);
    for (; vars && vars->key; ++vars) {
      std::string value = vars->value ? vars->value : "";
      size_t start = value.find(';');
      if (start == std::string::npos)
        continue;
      start = value.find_first_not_of(' ', start + 1);
      if (start == std::string::npos)
        continue;
      size_t end = value.find('|', start);
      options[vars->key] =
          value.substr(start, end == std::string::npos ? end : end - start);
    }
    options["pcsx_rearmed_memcard2"] = "disabled";
    options["pcsx_rearmed_bios"] = "auto";
    for (const auto &value : overrides)
      options[value.first] = value.second;
    return true;
  }
  case RETRO_ENVIRONMENT_GET_VARIABLE: {
    auto *var = static_cast<retro_variable *>(data);
    auto found = options.find(var->key);
    var->value = found == options.end() ? nullptr : found->second.c_str();
    return found != options.end();
  }
  case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
    *static_cast<bool *>(data) = optionsChanged;
    optionsChanged = false;
    return true;
  case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
    av = *static_cast<retro_system_av_info *>(data);
    return true;
  case RETRO_ENVIRONMENT_SET_GEOMETRY:
    av.geometry = *static_cast<retro_game_geometry *>(data);
    return true;
  case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
  case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
  case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
  case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
  case RETRO_ENVIRONMENT_SET_MESSAGE:
    return true;
  default:
    return false;
  }
}

static void video(const void *data, unsigned width, unsigned height,
                  size_t pitch) {
  if (data == RETRO_HW_FRAME_BUFFER_VALID) {
    static unsigned lastWidth = 0, lastHeight = 0;
    if (width != lastWidth || height != lastHeight) {
      __android_log_print(
          ANDROID_LOG_INFO, "DigiMapCore",
          "Video %ux%u; framebuffer %ux%u; geometry %ux%u max %ux%u", width,
          height, framebufferWidth, framebufferHeight, av.geometry.base_width,
          av.geometry.base_height, av.geometry.max_width,
          av.geometry.max_height);
      lastWidth = width;
      lastHeight = height;
    }
    if (!hardwareReady || !framebuffer || !window ||
        eglWindow == EGL_NO_SURFACE)
      return;
    if (!width || !height || width > framebufferWidth ||
        height > framebufferHeight)
      return;
    EGLint destWidth = 0, destHeight = 0;
    if (!eglQuerySurface(eglDisplay, eglWindow, EGL_WIDTH, &destWidth) ||
        !eglQuerySurface(eglDisplay, eglWindow, EGL_HEIGHT, &destHeight) ||
        destWidth <= 0 || destHeight <= 0)
      return;
    GLint readFbo, drawFbo;
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &readFbo);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &drawFbo);
    bool scissor = glIsEnabled(GL_SCISSOR_TEST);
    glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    GLenum filter =
        overrides["digimap_filter"] == "linear" ? GL_LINEAR : GL_NEAREST;
    glBlitFramebuffer(0, 0, width, height, 0, 0, destWidth, destHeight,
                      GL_COLOR_BUFFER_BIT, filter);
    eglSwapBuffers(eglDisplay, eglWindow);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo);
    if (scissor)
      glEnable(GL_SCISSOR_TEST);
    return;
  }
  if (hardwareRequested && eglContext != EGL_NO_CONTEXT)
    return;
  if (!data || !window || width == 0 || height == 0 || width > 2048 ||
      height > 2048)
    return;
  const size_t pixelBytes = pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888 ? 4 : 2;
  if (pitch < width * pixelBytes || pitch > 65536)
    return;
  if (ANativeWindow_setBuffersGeometry(window, width, height,
                                       WINDOW_FORMAT_RGBA_8888) != 0)
    return;
  ANativeWindow_Buffer buffer;
  if (ANativeWindow_lock(window, &buffer, nullptr) != 0)
    return;
  if (!buffer.bits || buffer.width < static_cast<int>(width) ||
      buffer.height < static_cast<int>(height) ||
      buffer.stride < static_cast<int>(width)) {
    ANativeWindow_unlockAndPost(window);
    return;
  }
  for (unsigned y = 0; y < height; ++y) {
    auto *out = static_cast<uint32_t *>(buffer.bits) + y * buffer.stride;
    const auto *row = static_cast<const uint8_t *>(data) + y * pitch;
    for (unsigned x = 0; x < width; ++x) {
      unsigned r, g, b;
      if (pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
        uint32_t p;
        memcpy(&p, row + x * 4, 4);
        r = (p >> 16) & 255;
        g = (p >> 8) & 255;
        b = p & 255;
      } else {
        uint16_t p;
        memcpy(&p, row + x * 2, 2);
        if (pixelFormat == RETRO_PIXEL_FORMAT_RGB565) {
          r = ((p >> 11) & 31) * 255 / 31;
          g = ((p >> 5) & 63) * 255 / 63;
        } else {
          r = ((p >> 10) & 31) * 255 / 31;
          g = ((p >> 5) & 31) * 255 / 31;
        }
        b = (p & 31) * 255 / 31;
      }
      out[x] = 0xff000000u | (b << 16) | (g << 8) | r;
    }
  }
  ANativeWindow_unlockAndPost(window);
}

static size_t audioBatch(const int16_t *data, size_t frames) {
  if (data && frames <= (MAX_AUDIO_SAMPLES - samples.size()) / 2)
    samples.insert(samples.end(), data, data + frames * 2);
  return frames;
}
static void audioSample(int16_t l, int16_t r) {
  int16_t pair[] = {l, r};
  audioBatch(pair, 1);
}
static void inputPoll() {}
static int16_t inputState(unsigned port, unsigned device, unsigned,
                          unsigned id) {
  return port == 0 && device == RETRO_DEVICE_JOYPAD && id < 16
             ? (buttons >> id) & 1
             : 0;
}
static std::string fromJava(JNIEnv *env, jstring s) {
  if (!s)
    return {};
  const char *chars = env->GetStringUTFChars(s, nullptr);
  if (!chars)
    return {};
  std::string result(chars);
  env->ReleaseStringUTFChars(s, chars);
  return result;
}
static void clean() {
  if (loaded)
    core_unload();
  if (hardwareReady && hardware.context_destroy)
    hardware.context_destroy();
  if (library) {
    core_deinit();
    dlclose(library);
  }
  destroyGL();
  library = nullptr;
  loaded = false;
  buttons = 0;
  av = {};
  options.clear();
  samples.clear();
  optionsChanged = false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_es_digimap_thor_NativeCore_start(JNIEnv *env, jclass, jstring lib,
                                      jstring system, jstring saves,
                                      jstring game) {
  std::lock_guard<std::mutex> lock(guard);
  if (library)
    clean();
  av = {};
  samples.clear();
  buttons = 0;
  if (!lib || !system || !saves || !game)
    return env->NewStringUTF("Faltan rutas del emulador");
  systemDir = fromJava(env, system);
  saveDir = fromJava(env, saves);
  gamePath = fromJava(env, game);
  pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
  library = dlopen(fromJava(env, lib).c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!library) {
    __android_log_print(ANDROID_LOG_ERROR, "DigiMapCore", "core_library_load_failed");
    return env->NewStringUTF("No se pudo abrir el núcleo PSX.");
  }
#define BIND(name, symbol)                                                     \
  name = reinterpret_cast<decltype(name)>(dlsym(library, symbol));             \
  if (!name) {                                                                 \
    dlclose(library);                                                          \
    library = nullptr;                                                         \
    return env->NewStringUTF("Núcleo PSX incompleto: " symbol);                \
  }
  BIND(core_init, "retro_init");
  BIND(core_deinit, "retro_deinit");
  BIND(core_load, "retro_load_game");
  BIND(core_unload, "retro_unload_game");
  BIND(core_run, "retro_run");
  BIND(core_av, "retro_get_system_av_info");
  BIND(core_memory, "retro_get_memory_data");
  BIND(core_memory_size, "retro_get_memory_size");
  BIND(core_state_size, "retro_serialize_size");
  BIND(core_serialize, "retro_serialize");
  BIND(core_unserialize, "retro_unserialize");
  BIND(core_controller, "retro_set_controller_port_device");
#define CALLBACK(symbol, type, fn)                                             \
  {                                                                            \
    auto set = reinterpret_cast<void (*)(type)>(dlsym(library, symbol));       \
    if (!set) {                                                                \
      dlclose(library);                                                        \
      library = nullptr;                                                       \
      return env->NewStringUTF("API libretro incompleta");                     \
    }                                                                          \
    set(fn);                                                                   \
  }
  CALLBACK("retro_set_environment", retro_environment_t, environment);
  CALLBACK("retro_set_video_refresh", retro_video_refresh_t, video);
  CALLBACK("retro_set_audio_sample", retro_audio_sample_t, audioSample);
  CALLBACK("retro_set_audio_sample_batch", retro_audio_sample_batch_t,
           audioBatch);
  CALLBACK("retro_set_input_poll", retro_input_poll_t, inputPoll);
  CALLBACK("retro_set_input_state", retro_input_state_t, inputState);
  core_init();
  retro_game_info info = {gamePath.c_str(), nullptr, 0, nullptr};
  loaded = core_load(&info);
  if (!loaded) {
    clean();
    return env->NewStringUTF(
        "No se pudo cargar el disco. Consulta el registro DigiMapCore.");
  }
  core_controller(0, RETRO_DEVICE_JOYPAD);
  core_av(&av);
  if (hardwareRequested && hardware.context_reset) {
    prepareGLWindow();
    hardware.context_reset();
    hardwareReady = true;
    core_av(&av);
  }
  // One libretro memory card per imported game. Load only an exact-size card.
  auto *ram = static_cast<char *>(core_memory(RETRO_MEMORY_SAVE_RAM));
  size_t size = core_memory_size(RETRO_MEMORY_SAVE_RAM);
  std::ifstream file(saveDir + "/memory-card.mcr",
                     std::ios::binary | std::ios::ate);
  if (ram && size > 0 && size <= MAX_CARD_BYTES && file &&
      file.tellg() == static_cast<std::streamoff>(size)) {
    file.seekg(0);
    file.read(ram, size);
  }
  return nullptr;
}
extern "C" JNIEXPORT void JNICALL
Java_es_digimap_thor_NativeCore_surface(JNIEnv *env, jclass, jobject surface) {
  std::lock_guard<std::mutex> lock(guard);
  // Destroy the EGL surface while its native window is still owned.
  if (eglWindow != EGL_NO_SURFACE) {
    eglMakeCurrent(eglDisplay, eglOffscreen, eglOffscreen, eglContext);
    eglDestroySurface(eglDisplay, eglWindow);
    eglWindow = EGL_NO_SURFACE;
  }
  if (window)
    ANativeWindow_release(window);
  window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
  windowGeneration++;
}
extern "C" JNIEXPORT void JNICALL
Java_es_digimap_thor_NativeCore_runFrame(JNIEnv *, jclass, jint mask) {
  std::lock_guard<std::mutex> lock(guard);
  buttons = mask;
  if (loaded) {
    if (hardwareReady)
      prepareGLWindow();
    core_run();
    if (hardwareRequested && !hardwareReady && hardware.context_reset) {
      prepareGLWindow();
      hardware.context_reset();
      hardwareReady = true;
      core_av(&av);
    }
  }
}
extern "C" JNIEXPORT jshortArray JNICALL
Java_es_digimap_thor_NativeCore_audio(JNIEnv *env, jclass) {
  std::lock_guard<std::mutex> lock(guard);
  jshortArray result = env->NewShortArray(samples.size());
  if (result && !samples.empty())
    env->SetShortArrayRegion(result, 0, samples.size(), samples.data());
  samples.clear();
  return result;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_es_digimap_thor_NativeCore_writeRam(JNIEnv *, jclass, jint address,
                                         jint value, jint bytes) {
  std::lock_guard<std::mutex> lock(guard);
  if (!loaded || address < 0 || (bytes != 1 && bytes != 2 && bytes != 4))
    return false;
  size_t size = core_memory_size(RETRO_MEMORY_SYSTEM_RAM);
  auto *data = static_cast<uint8_t *>(core_memory(RETRO_MEMORY_SYSTEM_RAM));
  if (!data || size != 2097152 || static_cast<size_t>(address) + bytes > size)
    return false;
  for (int i = 0; i < bytes; i++)
    data[address + i] = static_cast<uint32_t>(value) >> (8 * i);
  return true;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_es_digimap_thor_NativeCore_memory(JNIEnv *env, jclass) {
  std::lock_guard<std::mutex> lock(guard);
  if (!loaded)
    return nullptr;
  size_t size = core_memory_size(RETRO_MEMORY_SYSTEM_RAM);
  auto *data = static_cast<jbyte *>(core_memory(RETRO_MEMORY_SYSTEM_RAM));
  if (!data || size != 2 * 1024 * 1024)
    return nullptr;
  jbyteArray result = env->NewByteArray(size);
  if (result)
    env->SetByteArrayRegion(result, 0, size, data);
  return result;
}
extern "C" JNIEXPORT jdouble JNICALL
Java_es_digimap_thor_NativeCore_fps(JNIEnv *, jclass) {
  std::lock_guard<std::mutex> lock(guard);
  return av.timing.fps;
}
extern "C" JNIEXPORT jdouble JNICALL
Java_es_digimap_thor_NativeCore_sampleRate(JNIEnv *, jclass) {
  std::lock_guard<std::mutex> lock(guard);
  return av.timing.sample_rate;
}
extern "C" JNIEXPORT void JNICALL
Java_es_digimap_thor_NativeCore_option(JNIEnv *env, jclass, jstring key,
                                       jstring value) {
  std::lock_guard<std::mutex> lock(guard);
  std::string k = fromJava(env, key), v = fromJava(env, value);
  overrides[k] = v;
  options[k] = v;
  optionsChanged = true;
}
// Private save files are replaced only after a complete, synced temporary
// write.
static bool atomicWrite(const std::string &destination, const void *data,
                        size_t size) {
  if (destination.empty() || !data || !size)
    return false;
  const std::string temporary = destination + ".tmp";
  int fd = open(temporary.c_str(),
                O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
  if (fd < 0)
    return false;
  const auto *bytes = static_cast<const char *>(data);
  size_t done = 0;
  bool ok = true;
  while (done < size) {
    ssize_t count = write(fd, bytes + done, size - done);
    if (count < 0 && errno == EINTR)
      continue;
    if (count <= 0) {
      ok = false;
      break;
    }
    done += static_cast<size_t>(count);
  }
  if (ok && fsync(fd) != 0)
    ok = false;
  if (close(fd) != 0)
    ok = false;
  if (ok && rename(temporary.c_str(), destination.c_str()) != 0)
    ok = false;
  if (!ok)
    unlink(temporary.c_str());
  return ok;
}
extern "C" JNIEXPORT jlong JNICALL
Java_es_digimap_thor_NativeCore_stateBytes(JNIEnv *, jclass) {
  std::lock_guard<std::mutex> lock(guard);
  if (!loaded) return 0;
  size_t size = core_state_size();
  return size <= MAX_STATE_BYTES ? static_cast<jlong>(size) : 0;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_es_digimap_thor_NativeCore_saveState(JNIEnv *env, jclass, jstring path) {
  std::lock_guard<std::mutex> lock(guard);
  if (!loaded || !path)
    return false;
  size_t size = core_state_size();
  if (!size || size > MAX_STATE_BYTES)
    return false;
  try {
    std::vector<char> data(size);
    return core_serialize(data.data(), size) &&
           atomicWrite(fromJava(env, path), data.data(), size);
  } catch (const std::bad_alloc &) {
    return false;
  }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_es_digimap_thor_NativeCore_loadState(JNIEnv *env, jclass, jstring path) {
  std::lock_guard<std::mutex> lock(guard);
  if (!loaded || !path)
    return false;
  size_t size = core_state_size();
  if (!size || size > MAX_STATE_BYTES)
    return false;
  std::ifstream in(fromJava(env, path), std::ios::binary | std::ios::ate);
  if (!in || in.tellg() != static_cast<std::streamoff>(size))
    return false;
  try {
    std::vector<char> data(size);
    in.seekg(0);
    in.read(data.data(), size);
    samples.clear();
    return in.good() && core_unserialize(data.data(), size);
  } catch (const std::bad_alloc &) {
    return false;
  }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_es_digimap_thor_NativeCore_saveCard(JNIEnv *env, jclass, jstring path) {
  std::lock_guard<std::mutex> lock(guard);
  if (!loaded || !path)
    return false;
  auto *data = core_memory(RETRO_MEMORY_SAVE_RAM);
  size_t size = core_memory_size(RETRO_MEMORY_SAVE_RAM);
  return size <= MAX_CARD_BYTES && atomicWrite(fromJava(env, path), data, size);
}
extern "C" JNIEXPORT void JNICALL Java_es_digimap_thor_NativeCore_stop(JNIEnv *,
                                                                       jclass) {
  std::lock_guard<std::mutex> lock(guard);
  if (library)
    clean();
  if (window) {
    ANativeWindow_release(window);
    window = nullptr;
    windowGeneration++;
  }
}

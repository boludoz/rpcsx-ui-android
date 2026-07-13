#include <algorithm>
#include <android/dlext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <optional>
#include <string>
#include <string_view>
#include <sys/resource.h>
#include <unistd.h>
#include <utility>

#if defined(__aarch64__)
#include <adrenotools/driver.h>
#include <adrenotools/priv.h>
#endif

struct RPCSXApi {
  bool (*overlayPadData)(int digital1, int digital2, int leftStickX,
                         int leftStickY, int rightStickX, int rightStickY);
  bool (*multiPadData)(int playerIndex, int digital1, int digital2,
                       int leftStickX, int leftStickY, int rightStickX,
                       int rightStickY);
  int (*getMaxVirtualPads)();
  bool (*initialize)(std::string_view rootDir, std::string_view user);
  bool (*processCompilationQueue)(JNIEnv *env);
  bool (*startMainThreadProcessor)(JNIEnv *env);
  bool (*collectGameInfo)(JNIEnv *env, std::string_view rootDir,
                          long progressId);
  bool (*collectGameInfoFromUri)(JNIEnv *env, std::string_view treeUri,
                                 long progressId);
  bool (*collectIsoInfoFromUri)(JNIEnv *env, std::string_view treeUri,
                                long progressId);
  jstring (*resolveTreeUriToPath)(JNIEnv *env, std::string_view treeUri);
  void (*shutdown)();
  int (*boot)(std::string_view path_, std::string_view configPath);
  int (*bootIsoFd)(int fd, std::string_view configPath);
  int (*getState)();
  void (*kill)();
  void (*resume)();
  void (*openHomeMenu)();
  std::string (*getTitleId)();
  bool (*surfaceEvent)(JNIEnv *env, jobject surface, jint event);
  bool (*usbDeviceEvent)(int fd, int vendorId, int productId, int event);
  bool (*installFw)(JNIEnv *env, int fd, long progressId);
  bool (*isInstallableFile)(jint fd);
  jstring (*getDirInstallPath)(JNIEnv *env, jint fd);
  bool (*install)(JNIEnv *env, int fd, long progressId, std::string_view gamePath);
  bool (*installKey)(JNIEnv *env, int fd, long progressId,
                     std::string_view gamePath);
  std::string (*systemInfo)();
  void (*loginUser)(std::string_view userId);
  std::string (*getUser)();
  std::string (*settingsGet)(std::string_view titleId, std::string_view path);
  bool (*settingsSet)(std::string_view titleId, std::string_view path,
                      std::string_view valueString);
  bool (*settingsRemove)(std::string_view titleId, std::string_view path);
  bool (*settingsLiveApply)(std::string_view path, std::string_view valueString);
  std::string (*getVersion)();
  void *(*setCustomDriver)(void *driverHandle);
};

struct RPCSXLibrary : RPCSXApi {
  void *handle = nullptr;

  RPCSXLibrary() = default;
  RPCSXLibrary(const RPCSXLibrary &) = delete;
  RPCSXLibrary(RPCSXLibrary &&other) { swap(other); }
  RPCSXLibrary &operator=(RPCSXLibrary &&other) {
    swap(other);
    return *this;
  }
  ~RPCSXLibrary() {
    if (handle) {
      ::dlclose(handle);
    }
  }

  void swap(RPCSXLibrary &other) noexcept {
    std::swap(handle, other.handle);
    std::swap(static_cast<RPCSXApi &>(*this), static_cast<RPCSXApi &>(other));
  }

  static std::optional<RPCSXLibrary> Open(const char *path) {
    void *handle = ::dlopen(path, RTLD_LOCAL | RTLD_NOW);
    if (handle == nullptr) {
      __android_log_print(ANDROID_LOG_ERROR, "RPCSX-UI",
                          "Failed to open RPCSX library at %s, error %s", path,
                          ::dlerror());
      return {};
    }

    RPCSXLibrary result;
    result.handle = handle;

    // clang-format off
    result.overlayPadData = reinterpret_cast<decltype(overlayPadData)>(dlsym(handle, "_rpcsx_overlayPadData"));
    result.multiPadData = reinterpret_cast<decltype(multiPadData)>(dlsym(handle, "_rpcsx_multiPadData"));
    result.getMaxVirtualPads = reinterpret_cast<decltype(getMaxVirtualPads)>(dlsym(handle, "_rpcsx_getMaxVirtualPads"));
    result.initialize = reinterpret_cast<decltype(initialize)>(dlsym(handle, "_rpcsx_initialize"));
    result.processCompilationQueue = reinterpret_cast<decltype(processCompilationQueue)>(dlsym(handle, "_rpcsx_processCompilationQueue"));
    result.startMainThreadProcessor = reinterpret_cast<decltype(startMainThreadProcessor)>(dlsym(handle, "_rpcsx_startMainThreadProcessor"));
    result.collectGameInfo = reinterpret_cast<decltype(collectGameInfo)>(dlsym(handle, "_rpcsx_collectGameInfo"));
    result.collectGameInfoFromUri = reinterpret_cast<decltype(collectGameInfoFromUri)>(dlsym(handle, "_rpcsx_collectGameInfoFromUri"));
    result.collectIsoInfoFromUri = reinterpret_cast<decltype(collectIsoInfoFromUri)>(dlsym(handle, "_rpcsx_collectIsoInfoFromUri"));
    result.resolveTreeUriToPath = reinterpret_cast<decltype(resolveTreeUriToPath)>(dlsym(handle, "_rpcsx_resolveTreeUriToPath"));
    result.shutdown = reinterpret_cast<decltype(shutdown)>(dlsym(handle, "_rpcsx_shutdown"));
    result.boot = reinterpret_cast<decltype(boot)>(dlsym(handle, "_rpcsx_boot"));
    result.bootIsoFd = reinterpret_cast<decltype(bootIsoFd)>(dlsym(handle, "_rpcsx_bootIsoFd"));
    result.getState = reinterpret_cast<decltype(getState)>(dlsym(handle, "_rpcsx_getState"));
    result.kill = reinterpret_cast<decltype(kill)>(dlsym(handle, "_rpcsx_kill"));
    result.resume = reinterpret_cast<decltype(resume)>(dlsym(handle, "_rpcsx_resume"));
    result.openHomeMenu = reinterpret_cast<decltype(openHomeMenu)>(dlsym(handle, "_rpcsx_openHomeMenu"));
    result.getTitleId = reinterpret_cast<decltype(getTitleId)>(dlsym(handle, "_rpcsx_getTitleId"));
    result.surfaceEvent = reinterpret_cast<decltype(surfaceEvent)>(dlsym(handle, "_rpcsx_surfaceEvent"));
    result.usbDeviceEvent = reinterpret_cast<decltype(usbDeviceEvent)>(dlsym(handle, "_rpcsx_usbDeviceEvent"));
    result.installFw = reinterpret_cast<decltype(installFw)>(dlsym(handle, "_rpcsx_installFw"));
    result.isInstallableFile = reinterpret_cast<decltype(isInstallableFile)>(dlsym(handle, "_rpcsx_isInstallableFile"));
    result.getDirInstallPath = reinterpret_cast<decltype(getDirInstallPath)>(dlsym(handle, "_rpcsx_getDirInstallPath"));
    result.install = reinterpret_cast<decltype(install)>(dlsym(handle, "_rpcsx_install"));
    result.installKey = reinterpret_cast<decltype(installKey)>(dlsym(handle, "_rpcsx_installKey"));
    result.systemInfo = reinterpret_cast<decltype(systemInfo)>(dlsym(handle, "_rpcsx_systemInfo"));
    result.loginUser = reinterpret_cast<decltype(loginUser)>(dlsym(handle, "_rpcsx_loginUser"));
    result.getUser = reinterpret_cast<decltype(getUser)>(dlsym(handle, "_rpcsx_getUser"));
    result.settingsGet = reinterpret_cast<decltype(settingsGet)>(dlsym(handle, "_rpcsx_configGet"));
    result.settingsSet = reinterpret_cast<decltype(settingsSet)>(dlsym(handle, "_rpcsx_configSet"));
    result.settingsRemove = reinterpret_cast<decltype(settingsRemove)>(dlsym(handle, "_rpcsx_configRemove"));
    result.settingsLiveApply = reinterpret_cast<decltype(settingsLiveApply)>(dlsym(handle, "_rpcsx_configLiveApply"));
    result.getVersion = reinterpret_cast<decltype(getVersion)>(dlsym(handle, "_rpcsx_getVersion"));
    result.setCustomDriver = reinterpret_cast<decltype(setCustomDriver)>(dlsym(handle, "_rpcsx_setCustomDriver"));
    // clang-format on

    return result;
  }
};

static RPCSXLibrary rpcsxLib;

static std::string unwrap(JNIEnv *env, jstring string) {
  auto resultBuffer = env->GetStringUTFChars(string, nullptr);
  std::string result(resultBuffer);
  env->ReleaseStringUTFChars(string, resultBuffer);
  return result;
}
static jstring wrap(JNIEnv *env, const std::string &string) {
  return env->NewStringUTF(string.c_str());
}
static jstring wrap(JNIEnv *env, const char *string) {
  return env->NewStringUTF(string);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_openLibrary(JNIEnv *env, jobject, jstring path) {
  if (auto library = RPCSXLibrary::Open(unwrap(env, path).c_str())) {
    rpcsxLib = std::move(*library);
    return true;
  }

  return false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_getLibraryVersion(JNIEnv *env, jobject, jstring path) {
  if (auto library = RPCSXLibrary::Open(unwrap(env, path).c_str())) {
    if (auto getVersion = library->getVersion) {
      return wrap(env, getVersion());
    }
  }

  return {};
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_overlayPadData(
    JNIEnv *, jobject, jint digital1, jint digital2, jint leftStickX,
    jint leftStickY, jint rightStickX, jint rightStickY) {
  return rpcsxLib.overlayPadData(digital1, digital2, leftStickX, leftStickY,
                                 rightStickX, rightStickY);
}

// Older downloaded engine builds may not export _rpcsx_multiPadData yet, so
// these two calls must tolerate a null function pointer instead of crashing.
extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_multiPadData(
    JNIEnv *, jobject, jint playerIndex, jint digital1, jint digital2,
    jint leftStickX, jint leftStickY, jint rightStickX, jint rightStickY) {
  if (rpcsxLib.multiPadData == nullptr) {
    return false;
  }

  return rpcsxLib.multiPadData(playerIndex, digital1, digital2, leftStickX,
                               leftStickY, rightStickX, rightStickY);
}

extern "C" JNIEXPORT jint JNICALL
Java_net_rpcsx_RPCSX_getMaxVirtualPads(JNIEnv *, jobject) {
  if (rpcsxLib.getMaxVirtualPads == nullptr) {
    return 1;
  }

  return rpcsxLib.getMaxVirtualPads();
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_initialize(
    JNIEnv *env, jobject, jstring rootDir, jstring user) {
  return rpcsxLib.initialize(unwrap(env, rootDir), unwrap(env, user));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_processCompilationQueue(JNIEnv *env, jobject) {
  return rpcsxLib.processCompilationQueue(env);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_startMainThreadProcessor(JNIEnv *env, jobject) {
  return rpcsxLib.startMainThreadProcessor(env);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_collectGameInfo(
    JNIEnv *env, jobject, jstring jrootDir, jlong progressId) {
  return rpcsxLib.collectGameInfo(env, unwrap(env, jrootDir), progressId);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_collectGameInfoFromUri(JNIEnv *env, jobject,
                                            jstring jtreeUri,
                                            jlong progressId) {
  if (rpcsxLib.collectGameInfoFromUri == nullptr) {
    return false;
  }
  return rpcsxLib.collectGameInfoFromUri(env, unwrap(env, jtreeUri),
                                         progressId);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_collectIsoInfoFromUri(JNIEnv *env, jobject,
                                           jstring jtreeUri,
                                           jlong progressId) {
  if (rpcsxLib.collectIsoInfoFromUri == nullptr) {
    return false;
  }
  return rpcsxLib.collectIsoInfoFromUri(env, unwrap(env, jtreeUri),
                                        progressId);
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_resolveTreeUriToPath(JNIEnv *env, jobject,
                                          jstring jtreeUri) {
  if (rpcsxLib.resolveTreeUriToPath == nullptr) {
    return nullptr;
  }
  return rpcsxLib.resolveTreeUriToPath(env, unwrap(env, jtreeUri));
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_shutdown(JNIEnv *env,
                                                                jobject) {
  return rpcsxLib.shutdown();
}

extern "C" JNIEXPORT jint JNICALL Java_net_rpcsx_RPCSX_boot(JNIEnv *env,
                                                            jobject,
                                                            jstring jpath,
                                                            jstring jconfigPath) {
  return rpcsxLib.boot(unwrap(env, jpath), unwrap(env, jconfigPath));
}

extern "C" JNIEXPORT jint JNICALL Java_net_rpcsx_RPCSX_bootIsoFd(JNIEnv *env,
                                                                 jobject,
                                                                 jint fd,
                                                                 jstring jconfigPath) {
  if (rpcsxLib.bootIsoFd == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, "RPCSX-UI",
                        "bootIsoFd: core library too old - update the core .so");
    if (fd >= 0) {
      close(fd);
    }
    return 1; // GenericError
  }
  return rpcsxLib.bootIsoFd(fd, unwrap(env, jconfigPath));
}

extern "C" JNIEXPORT jint JNICALL Java_net_rpcsx_RPCSX_getState(JNIEnv *env,
                                                                jobject) {
  return rpcsxLib.getState();
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_kill(JNIEnv *env,
                                                            jobject) {
  return rpcsxLib.kill();
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_resume(JNIEnv *env,
                                                              jobject) {
  return rpcsxLib.resume();
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_openHomeMenu(JNIEnv *env,
                                                                    jobject) {
  return rpcsxLib.openHomeMenu();
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_getTitleId(JNIEnv *env, jobject) {
  return wrap(env, rpcsxLib.getTitleId());
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_surfaceEvent(
    JNIEnv *env, jobject, jobject surface, jint event) {
  return rpcsxLib.surfaceEvent(env, surface, event);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_usbDeviceEvent(
    JNIEnv *env, jobject, jint fd, jint vendorId, jint productId, jint event) {
  return rpcsxLib.usbDeviceEvent(fd, vendorId, productId, event);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_installFw(
    JNIEnv *env, jobject, jint fd, jlong progressId) {
  return rpcsxLib.installFw(env, fd, progressId);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_isInstallableFile(JNIEnv *env, jobject, jint fd) {
  return rpcsxLib.isInstallableFile(fd);
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_getDirInstallPath(JNIEnv *env, jobject, jint fd) {
  return rpcsxLib.getDirInstallPath(env, fd);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_install(JNIEnv *env, jobject, jint fd, jlong progressId, jstring jgamePath) {
  return rpcsxLib.install(env, fd, progressId, unwrap(env, jgamePath));
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_installKey(
    JNIEnv *env, jobject, jint fd, jlong progressId, jstring gamePath) {
  return rpcsxLib.installKey(env, fd, progressId, unwrap(env, gamePath));
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_systemInfo(JNIEnv *env, jobject) {
  return wrap(env, rpcsxLib.systemInfo());
}

extern "C" JNIEXPORT void JNICALL
Java_net_rpcsx_RPCSX_loginUser(JNIEnv *env, jobject, jstring user_id) {
  return rpcsxLib.loginUser(unwrap(env, user_id));
}

extern "C" JNIEXPORT jstring JNICALL Java_net_rpcsx_RPCSX_getUser(JNIEnv *env,
                                                                  jobject) {
  return wrap(env, rpcsxLib.getUser());
}

// Settings bridge: one API for global (titleId "") and per-title scope. The
// core owns the global config.json; this shim only forwards. Null-checked so
// a core .so built before this API degrades loudly instead of crashing.
extern "C" JNIEXPORT jstring JNICALL Java_net_rpcsx_RPCSX_settingsGet(
    JNIEnv *env, jobject, jstring jtitleId, jstring jpath) {
  if (rpcsxLib.settingsGet == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, "RPCSX-UI",
                        "settingsGet: core library too old - update the core .so");
    return wrap(env, "{}");
  }
  return wrap(env, rpcsxLib.settingsGet(unwrap(env, jtitleId), unwrap(env, jpath)));
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_settingsSet(
    JNIEnv *env, jobject, jstring jtitleId, jstring jpath, jstring jvalue) {
  if (rpcsxLib.settingsSet == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, "RPCSX-UI",
                        "settingsSet: core library too old - update the core .so");
    return false;
  }
  return rpcsxLib.settingsSet(unwrap(env, jtitleId), unwrap(env, jpath),
                              unwrap(env, jvalue));
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_settingsRemove(
    JNIEnv *env, jobject, jstring jtitleId, jstring jpath) {
  if (rpcsxLib.settingsRemove == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, "RPCSX-UI",
                        "settingsRemove: core library too old - update the core .so");
    return false;
  }
  return rpcsxLib.settingsRemove(unwrap(env, jtitleId), unwrap(env, jpath));
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_settingsLiveApply(
    JNIEnv *env, jobject, jstring jpath, jstring jvalue) {
  if (rpcsxLib.settingsLiveApply == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, "RPCSX-UI",
                        "settingsLiveApply: core library too old - update the core .so");
    return false;
  }
  return rpcsxLib.settingsLiveApply(unwrap(env, jpath), unwrap(env, jvalue));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_supportsCustomDriverLoading(JNIEnv *env,
                                                 jobject instance) {
  return access("/dev/kgsl-3d0", F_OK) == 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_getVersion(JNIEnv *env, jobject) {
  return wrap(env, rpcsxLib.getVersion());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_setCustomDriver(JNIEnv *env, jobject, jstring jpath,
                                     jstring jlibraryName, jstring jhookDir) {
#ifdef __aarch64__
  if (rpcsxLib.setCustomDriver == nullptr) {
    return false;
  }

  auto path = unwrap(env, jpath);
  void *loader = nullptr;

  if (!path.empty()) {
      auto hookDir = unwrap(env, jhookDir);
      auto libraryName = unwrap(env, jlibraryName);
      __android_log_print(ANDROID_LOG_INFO, "RPCSX-UI", "Loading custom driver %s",
                          path.c_str());

      ::dlerror();
      loader = adrenotools_open_libvulkan(
              RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr, (hookDir + "/").c_str(),
              (path + "/").c_str(), libraryName.c_str(), nullptr, nullptr);

      if (loader == nullptr) {
          __android_log_print(ANDROID_LOG_INFO, "RPCSX-UI",
                              "Failed to load custom driver at '%s': %s",
                              path.c_str(), ::dlerror());
          return false;
      }
  }

  auto prevLoader = rpcsxLib.setCustomDriver(loader);
  if (prevLoader != nullptr) {
    ::dlclose(prevLoader);
  }

  return true;
#else
  return false;
#endif // __aarch64__
}

#include "android_bridge.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <thread>

#include "src/config.h"
#include "src/crypto.h"
#include "src/globals.h"
#include "src/httpcommon.h"
#include "src/input.h"
#include "src/logging.h"
#include "src/nvhttp.h"
#include "src/display_device.h"
#include "src/process.h"
#include "src/rtsp.h"
#include "sandbox_audio_native_bridge.h"
extern "C" {
#include "src/rswrapper.h"
}

using namespace std::literals;

namespace sunshine_android {
  namespace {
    constexpr int LOG_INFO = 2;

    std::int64_t monotonic_ms() {
      return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
      ).count();
    }
  }

  JavaVM *g_vm = nullptr;
  jobject g_callbacks = nullptr;
  runtime_config_t g_config;
  std::mutex g_mutex;

  jmethodID g_on_video_input_surface = nullptr;
  jmethodID g_on_video_stopped = nullptr;
  jmethodID g_on_pin_requested = nullptr;
  jmethodID g_on_client_connected = nullptr;
  jmethodID g_on_client_disconnected = nullptr;
  jmethodID g_on_encoder_error = nullptr;
  jmethodID g_on_audio_source_start = nullptr;
  jmethodID g_on_audio_source_read = nullptr;
  jmethodID g_on_audio_source_stop = nullptr;
  jmethodID g_on_native_stats = nullptr;
  jmethodID g_on_log = nullptr;
  jmethodID g_on_touch = nullptr;
  jmethodID g_on_absolute_mouse = nullptr;
  jmethodID g_on_relative_mouse = nullptr;
  jmethodID g_on_mouse_button = nullptr;
  jmethodID g_on_mouse_scroll = nullptr;
  jmethodID g_on_keyboard = nullptr;
  jmethodID g_on_unicode = nullptr;
  jmethodID g_on_gamepad = nullptr;

  std::atomic<std::uint64_t> g_video_frames {0};
  std::atomic<std::uint64_t> g_video_key_frames {0};
  std::atomic<std::uint64_t> g_video_bytes {0};
  std::atomic<std::uint64_t> g_audio_frames {0};
  std::atomic<std::uint64_t> g_audio_samples {0};
  std::atomic<std::int64_t> g_last_stats_emit_ms {0};

  template<typename Fn>
  void with_callbacks(Fn &&fn) {
    bool attached = false;
    JNIEnv *env = attach_env(&attached);
    if (!env) {
      return;
    }
    jobject callbacks = nullptr;
    {
      std::lock_guard<std::mutex> lock(g_mutex);
      callbacks = g_callbacks;
    }
    if (callbacks) {
      fn(env, callbacks);
      if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
      }
    }
    detach_env(attached);
  }

  void ensure_credentials(const runtime_config_t &config) {
    if (std::filesystem::exists(config.cert_path) && std::filesystem::exists(config.key_path)) {
      return;
    }
    std::filesystem::create_directories(std::filesystem::path(config.cert_path).parent_path());
    std::filesystem::create_directories(std::filesystem::path(config.key_path).parent_path());
    auto creds = crypto::gen_creds("Sunshine Android"sv, 2048);
    {
      std::ofstream cert(config.cert_path, std::ios::binary | std::ios::trunc);
      cert << creds.x509;
    }
    {
      std::ofstream key(config.key_path, std::ios::binary | std::ios::trunc);
      key << creds.pkey;
    }
  }

  void configure_sunshine(const runtime_config_t &config) {
    ensure_credentials(config);

    config::nvhttp.pkey = config.key_path;
    config::nvhttp.cert = config.cert_path;
    config::nvhttp.file_state = config.state_path;
    config::nvhttp.sunshine_name = config.host_name;
    config::sunshine.credentials_file = config.state_path;
    config::sunshine.config_file = config.files_dir + "/sunshine.conf";
    config::sunshine.log_file = config.files_dir + "/sunshine.log";
    config::sunshine.port = static_cast<std::uint16_t>(config.port);
    config::sunshine.address_family = "ipv4";
    config::sunshine.bind_address.clear();
    config::sunshine.system_tray = false;
    config::sunshine.min_log_level = 2;
    config::stream.file_apps = config.files_dir + "/apps.json";

    config::audio.stream = config.enable_audio;
    config::input.keyboard = true;
    config::input.mouse = true;
    config::input.controller = true;
    config::input.native_pen_touch = true;
    config::input.high_resolution_scrolling = true;

    config::stream.fec_percentage = std::clamp(config::stream.fec_percentage, 1, 80);
    config::video.hevc_mode = config.enable_hevc ? 2 : 1;
    config::video.av1_mode = 1;
  }

  void release_callbacks(JNIEnv *env) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_callbacks) {
      env->DeleteGlobalRef(g_callbacks);
      g_callbacks = nullptr;
    }
  }

  JavaVM *java_vm() {
    return g_vm;
  }

  runtime_config_t runtime_config() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_config;
  }

  JNIEnv *attach_env(bool *attached) {
    if (attached) {
      *attached = false;
    }
    if (!g_vm) {
      return nullptr;
    }
    JNIEnv *env = nullptr;
    jint status = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_OK) {
      return env;
    }
    if (status == JNI_EDETACHED && g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
      if (attached) {
        *attached = true;
      }
      return env;
    }
    return nullptr;
  }

  std::string jstring_to_string(JNIEnv *env, jstring value) {
    if (!value) {
      return {};
    }
    const char *raw = env->GetStringUTFChars(value, nullptr);
    std::string out = raw ? raw : "";
    if (raw) {
      env->ReleaseStringUTFChars(value, raw);
    }
    return out;
  }

  void detach_env(bool attached) {
    if (attached && g_vm) {
      g_vm->DetachCurrentThread();
    }
  }

  void request_runtime_shutdown() {
    if (!mail::man) {
      return;
    }
    mail::man->event<bool>(mail::broadcast_shutdown)->raise(true);
    mail::man->event<bool>(mail::shutdown)->raise(true);
  }

  void call_video_input_surface(jobject surface, int width, int height, int frame_rate) {
    with_callbacks([&](JNIEnv *env, jobject callbacks) {
      env->CallVoidMethod(callbacks, g_on_video_input_surface, surface, width, height, frame_rate);
    });
  }

  void call_video_stopped() {
    with_callbacks([](JNIEnv *env, jobject callbacks) {
      env->CallVoidMethod(callbacks, g_on_video_stopped);
    });
  }

  void call_pin_requested() {
    with_callbacks([](JNIEnv *env, jobject callbacks) {
      env->CallVoidMethod(callbacks, g_on_pin_requested);
    });
  }

  void call_client_connected(const std::string &name, const std::string &address) {
    with_callbacks([&](JNIEnv *env, jobject callbacks) {
      jstring name_text = env->NewStringUTF(name.c_str());
      jstring address_text = env->NewStringUTF(address.c_str());
      env->CallVoidMethod(callbacks, g_on_client_connected, name_text, address_text);
      env->DeleteLocalRef(name_text);
      env->DeleteLocalRef(address_text);
    });
  }

  void call_client_disconnected(const std::string &name) {
    with_callbacks([&](JNIEnv *env, jobject callbacks) {
      jstring text = env->NewStringUTF(name.c_str());
      env->CallVoidMethod(callbacks, g_on_client_disconnected, text);
      env->DeleteLocalRef(text);
    });
  }

  void call_encoder_error(const std::string &message) {
    with_callbacks([&](JNIEnv *env, jobject callbacks) {
      jstring text = env->NewStringUTF(message.c_str());
      env->CallVoidMethod(callbacks, g_on_encoder_error, text);
      env->DeleteLocalRef(text);
    });
  }

  void call_log(int level, const std::string &message) {
    with_callbacks([&](JNIEnv *env, jobject callbacks) {
      jstring text = env->NewStringUTF(message.c_str());
      env->CallVoidMethod(callbacks, g_on_log, level, text);
      env->DeleteLocalRef(text);
    });
  }

  bool call_audio_source_start(int sample_rate, int channel_count, std::uint32_t frame_size) {
    bool started = false;
    with_callbacks([&](JNIEnv *env, jobject callbacks) {
      started = env->CallBooleanMethod(
        callbacks,
        g_on_audio_source_start,
        sample_rate,
        channel_count,
        static_cast<jint>(frame_size)
      ) == JNI_TRUE;
    });
    return started;
  }

  int call_audio_source_read(jfloatArray buffer, int frame_count) {
    int result = -1;
    with_callbacks([&](JNIEnv *env, jobject callbacks) {
      result = env->CallIntMethod(callbacks, g_on_audio_source_read, buffer, frame_count);
    });
    return result;
  }

  void call_audio_source_stop() {
    with_callbacks([](JNIEnv *env, jobject callbacks) {
      env->CallVoidMethod(callbacks, g_on_audio_source_stop);
    });
  }

  stats_snapshot_t stats_snapshot() {
    return {
      g_video_frames.load(std::memory_order_relaxed),
      g_video_key_frames.load(std::memory_order_relaxed),
      g_video_bytes.load(std::memory_order_relaxed),
      g_audio_frames.load(std::memory_order_relaxed),
      g_audio_samples.load(std::memory_order_relaxed),
    };
  }

  void reset_stats() {
    g_video_frames.store(0, std::memory_order_relaxed);
    g_video_key_frames.store(0, std::memory_order_relaxed);
    g_video_bytes.store(0, std::memory_order_relaxed);
    g_audio_frames.store(0, std::memory_order_relaxed);
    g_audio_samples.store(0, std::memory_order_relaxed);
    g_last_stats_emit_ms.store(0, std::memory_order_relaxed);
  }

  void emit_stats(stats_snapshot_t snapshot) {
    with_callbacks([&](JNIEnv *env, jobject callbacks) {
      env->CallVoidMethod(
        callbacks,
        g_on_native_stats,
        static_cast<jlong>(snapshot.video_frames),
        static_cast<jlong>(snapshot.video_key_frames),
        static_cast<jlong>(snapshot.video_bytes),
        static_cast<jlong>(snapshot.audio_frames),
        static_cast<jlong>(snapshot.audio_samples)
      );
    });
  }

  void emit_stats_if_due() {
    const auto now = monotonic_ms();
    auto last = g_last_stats_emit_ms.load(std::memory_order_relaxed);
    if (now - last < 1000) {
      return;
    }
    if (g_last_stats_emit_ms.compare_exchange_strong(last, now, std::memory_order_relaxed)) {
      emit_stats(stats_snapshot());
    }
  }

  void record_video_frame(std::size_t bytes, bool key_frame) {
    g_video_frames.fetch_add(1, std::memory_order_relaxed);
    g_video_bytes.fetch_add(bytes, std::memory_order_relaxed);
    if (key_frame) {
      g_video_key_frames.fetch_add(1, std::memory_order_relaxed);
    }
    emit_stats_if_due();
  }

  void record_audio_frame(std::uint64_t samples) {
    g_audio_frames.fetch_add(1, std::memory_order_relaxed);
    g_audio_samples.fetch_add(samples, std::memory_order_relaxed);
    emit_stats_if_due();
  }

  void call_touch(int event_type, int rotation, int pointer_id, float x, float y, float pressure, float major, float minor) {
    with_callbacks([&](JNIEnv *env, jobject callbacks) {
      env->CallVoidMethod(callbacks, g_on_touch, event_type, rotation, pointer_id, x, y, pressure, major, minor);
    });
  }

  void call_absolute_mouse(float x, float y, float width, float height) {
    with_callbacks([&](JNIEnv *env, jobject callbacks) {
      env->CallVoidMethod(callbacks, g_on_absolute_mouse, x, y, width, height);
    });
  }

  void call_relative_mouse(int dx, int dy) {
    with_callbacks([&](JNIEnv *env, jobject callbacks) {
      env->CallVoidMethod(callbacks, g_on_relative_mouse, dx, dy);
    });
  }

  void call_mouse_button(int button, bool release) {
    with_callbacks([&](JNIEnv *env, jobject callbacks) {
      env->CallVoidMethod(callbacks, g_on_mouse_button, button, static_cast<jboolean>(release));
    });
  }

  void call_mouse_scroll(int vertical, int horizontal) {
    with_callbacks([&](JNIEnv *env, jobject callbacks) {
      env->CallVoidMethod(callbacks, g_on_mouse_scroll, vertical, horizontal);
    });
  }

  void call_keyboard(int windows_key_code, bool release, int flags) {
    with_callbacks([&](JNIEnv *env, jobject callbacks) {
      env->CallVoidMethod(callbacks, g_on_keyboard, windows_key_code, static_cast<jboolean>(release), flags);
    });
  }

  void call_unicode(const char *utf8, int size) {
    std::string text(utf8, utf8 + size);
    with_callbacks([&](JNIEnv *env, jobject callbacks) {
      jstring j_text = env->NewStringUTF(text.c_str());
      env->CallVoidMethod(callbacks, g_on_unicode, j_text);
      env->DeleteLocalRef(j_text);
    });
  }

  void call_gamepad(int controller, std::uint32_t buttons, std::uint8_t lt, std::uint8_t rt, std::int16_t ls_x, std::int16_t ls_y, std::int16_t rs_x, std::int16_t rs_y) {
    with_callbacks([&](JNIEnv *env, jobject callbacks) {
      env->CallVoidMethod(callbacks, g_on_gamepad, controller, static_cast<jint>(buttons), lt, rt, ls_x, ls_y, rs_x, rs_y);
    });
  }

}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
  sunshine_android::g_vm = vm;
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_lizardbyte_sunshine_android_SunshineHost_nativeInit(
  JNIEnv *env,
  jclass,
  jobject callbacks,
  jstring files_dir,
  jstring state_path,
  jstring cert_path,
  jstring key_path,
  jstring host_name,
  jint port,
  jboolean enable_hevc,
  jboolean enable_audio
) {
  env->GetJavaVM(&sunshine_android::g_vm);
  sunshine_android::release_callbacks(env);

  auto read_string = [&](jstring value) {
    const char *raw = env->GetStringUTFChars(value, nullptr);
    std::string out = raw ? raw : "";
    env->ReleaseStringUTFChars(value, raw);
    return out;
  };

  sunshine_android::runtime_config_t config;
  config.files_dir = read_string(files_dir);
  config.state_path = read_string(state_path);
  config.cert_path = read_string(cert_path);
  config.key_path = read_string(key_path);
  config.host_name = read_string(host_name);
  config.port = port;
  config.enable_hevc = enable_hevc;
  config.enable_audio = enable_audio;

  jclass callbacks_class = env->GetObjectClass(callbacks);
  sunshine_android::g_on_video_input_surface = env->GetMethodID(callbacks_class, "onVideoInputSurface", "(Landroid/view/Surface;III)V");
  sunshine_android::g_on_video_stopped = env->GetMethodID(callbacks_class, "onVideoStopped", "()V");
  sunshine_android::g_on_pin_requested = env->GetMethodID(callbacks_class, "onPinRequested", "()V");
  sunshine_android::g_on_client_connected = env->GetMethodID(callbacks_class, "onClientConnected", "(Ljava/lang/String;Ljava/lang/String;)V");
  sunshine_android::g_on_client_disconnected = env->GetMethodID(callbacks_class, "onClientDisconnected", "(Ljava/lang/String;)V");
  sunshine_android::g_on_encoder_error = env->GetMethodID(callbacks_class, "onEncoderError", "(Ljava/lang/String;)V");
  sunshine_android::g_on_audio_source_start = env->GetMethodID(callbacks_class, "onAudioSourceStart", "(III)Z");
  sunshine_android::g_on_audio_source_read = env->GetMethodID(callbacks_class, "onAudioSourceRead", "([FI)I");
  sunshine_android::g_on_audio_source_stop = env->GetMethodID(callbacks_class, "onAudioSourceStop", "()V");
  sunshine_android::g_on_native_stats = env->GetMethodID(callbacks_class, "onNativeStats", "(JJJJJ)V");
  sunshine_android::g_on_log = env->GetMethodID(callbacks_class, "onLog", "(ILjava/lang/String;)V");
  sunshine_android::g_on_touch = env->GetMethodID(callbacks_class, "onTouch", "(IIIFFFFF)V");
  sunshine_android::g_on_absolute_mouse = env->GetMethodID(callbacks_class, "onAbsoluteMouse", "(FFFF)V");
  sunshine_android::g_on_relative_mouse = env->GetMethodID(callbacks_class, "onRelativeMouse", "(II)V");
  sunshine_android::g_on_mouse_button = env->GetMethodID(callbacks_class, "onMouseButton", "(IZ)V");
  sunshine_android::g_on_mouse_scroll = env->GetMethodID(callbacks_class, "onMouseScroll", "(II)V");
  sunshine_android::g_on_keyboard = env->GetMethodID(callbacks_class, "onKeyboard", "(IZI)V");
  sunshine_android::g_on_unicode = env->GetMethodID(callbacks_class, "onUnicode", "(Ljava/lang/String;)V");
  sunshine_android::g_on_gamepad = env->GetMethodID(callbacks_class, "onGamepad", "(IIIIIIII)V");
  env->DeleteLocalRef(callbacks_class);

  {
    std::lock_guard<std::mutex> lock(sunshine_android::g_mutex);
    sunshine_android::g_callbacks = env->NewGlobalRef(callbacks);
    sunshine_android::g_config = config;
  }
  sunshine_android::configure_sunshine(config);
  sunshine_android::call_log(sunshine_android::LOG_INFO, "Sunshine Android host initialized");
}

extern "C" JNIEXPORT void JNICALL
Java_dev_lizardbyte_sunshine_android_SunshineHost_nativeStart(JNIEnv *, jclass) {
  sunshine_android::request_runtime_shutdown();
  std::this_thread::sleep_for(150ms);
  sunshine_android::reset_stats();
  static std::unique_ptr<logging::deinit_t> logging_guard;
  if (!logging_guard) {
    logging_guard = logging::init(config::sunshine.min_log_level, config::sunshine.log_file);
  }
  mail::man = std::make_shared<safe::mail_raw_t>();
  task_pool.start(2);

  http::init();
  sunshine_android::configure_sunshine(sunshine_android::runtime_config());
  reed_solomon_init();
  [[maybe_unused]] auto platform_guard = platf::init();
  [[maybe_unused]] auto input_guard = input::init();

  std::thread http_thread {nvhttp::start};
  std::thread rtsp_thread {rtsp_stream::start};
  rtsp_thread.join();
  http_thread.join();
  task_pool.stop();
  task_pool.join();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_lizardbyte_sunshine_android_SunshineHost_nativePrepareFreshStart(JNIEnv *, jclass) {
  sunshine_android::request_runtime_shutdown();
  std::this_thread::sleep_for(150ms);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_lizardbyte_sunshine_android_SunshineHost_nativeStop(JNIEnv *, jclass) {
  sunshine_android::request_runtime_shutdown();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_lizardbyte_sunshine_android_SunshineHost_nativeStopCurrentSession(JNIEnv *, jclass) {
  if (!mail::man) {
    return;
  }
  rtsp_stream::terminate_sessions();
  if (proc::proc.running() > 0) {
    proc::proc.terminate();
  }
  display_device::revert_configuration();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_lizardbyte_sunshine_android_SunshineHost_nativeSubmitPin(JNIEnv *env, jclass, jstring pin) {
  return nvhttp::pin(sunshine_android::jstring_to_string(env, pin), "Moonlight") ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_lizardbyte_sunshine_android_SunshineHost_nativeRegisterPendingAutoPair(
  JNIEnv *env,
  jclass,
  jstring request_id,
  jstring sink_unique_id,
  jstring pin,
  jlong expires_at_unix_seconds
) {
  return nvhttp::register_pending_auto_pair(
           sunshine_android::jstring_to_string(env, request_id),
           sunshine_android::jstring_to_string(env, sink_unique_id),
           sunshine_android::jstring_to_string(env, pin),
           static_cast<std::int64_t>(expires_at_unix_seconds)
         ) ?
           JNI_TRUE :
           JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_lizardbyte_sunshine_android_SunshineHost_nativeRelease(JNIEnv *env, jclass) {
  sunshine_android::request_runtime_shutdown();
  arctrl::sandbox_audio::clear_sunshine_audio_record(env);
  arctrl::sandbox_audio::set_sunshine_sandbox_source_enabled(env, false);
  sunshine_android::release_callbacks(env);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_lizardbyte_sunshine_android_SunshineHost_nativeSetSandboxAudioSourceEnabled(
  JNIEnv *env,
  jclass,
  jboolean enabled
) {
  arctrl::sandbox_audio::set_sunshine_sandbox_source_enabled(env, enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_lizardbyte_sunshine_android_SunshineHost_nativeSetPlaybackAudioRecord(
  JNIEnv *env,
  jclass,
  jobject audio_record,
  jint sample_rate,
  jint channel_count
) {
  arctrl::sandbox_audio::set_sunshine_audio_record(
    env,
    audio_record,
    static_cast<int>(sample_rate),
    static_cast<int>(channel_count)
  );
}

extern "C" JNIEXPORT void JNICALL
Java_dev_lizardbyte_sunshine_android_SunshineHost_nativeClearNativeAudioSource(JNIEnv *env, jclass) {
  arctrl::sandbox_audio::clear_sunshine_audio_record(env);
  arctrl::sandbox_audio::set_sunshine_sandbox_source_enabled(env, false);
}

// LibreDeX 扩展（m7）：编码参数配置通道（实现在 android_video.cpp）
extern void set_libredex_encoder_settings(
    int bitrate_percent, int bitrate_mode, int complexity,
    int i_frame_interval, int max_fps,
    bool low_latency, bool disable_b_frames, bool realtime_priority);

extern "C" JNIEXPORT void JNICALL
Java_dev_lizardbyte_sunshine_android_SunshineHost_nativeSetLibreDeXEncoderSettings(
  JNIEnv *,
  jclass,
  jint bitrate_percent,
  jint bitrate_mode,
  jint complexity,
  jint i_frame_interval,
  jint max_fps,
  jboolean low_latency,
  jboolean disable_b_frames,
  jboolean realtime_priority
) {
  set_libredex_encoder_settings(
    static_cast<int>(bitrate_percent),
    static_cast<int>(bitrate_mode),
    static_cast<int>(complexity),
    static_cast<int>(i_frame_interval),
    static_cast<int>(max_fps),
    low_latency == JNI_TRUE,
    disable_b_frames == JNI_TRUE,
    realtime_priority == JNI_TRUE
  );
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_lizardbyte_sunshine_android_SunshineHost_nativeGetStats(JNIEnv *env, jclass) {
  auto snapshot = sunshine_android::stats_snapshot();
  std::array<jlong, 5> values {
    static_cast<jlong>(snapshot.video_frames),
    static_cast<jlong>(snapshot.video_key_frames),
    static_cast<jlong>(snapshot.video_bytes),
    static_cast<jlong>(snapshot.audio_frames),
    static_cast<jlong>(snapshot.audio_samples),
  };
  jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
  if (result) {
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
  }
  return result;
}

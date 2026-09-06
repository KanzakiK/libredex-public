#include "android_bridge.h"
#include "sandbox_audio_native_bridge.h"

#include <netdb.h>
#include <pthread.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <string>
#include <thread>
#include <vector>

#include "src/logging.h"
#include "src/platform/common.h"

using namespace std::literals;

namespace {
  class noop_deinit_t: public platf::deinit_t {};

  class sleep_timer_t: public platf::high_precision_timer {
  public:
    void sleep_for(const std::chrono::nanoseconds &duration) override {
      std::this_thread::sleep_for(duration);
    }

    operator bool() override {
      return true;
    }
  };

  class java_audio_mic_t: public platf::mic_t {
  public:
    java_audio_mic_t(std::uint32_t sample_rate, std::uint32_t frame_size, int channel_count):
        sample_rate_ {std::max<std::uint32_t>(sample_rate, 1)},
        frame_size_ {std::max<std::uint32_t>(frame_size, 1)},
        channel_count_ {std::max(1, channel_count)},
        sample_count_ {static_cast<std::size_t>(frame_size_) * static_cast<std::size_t>(channel_count_)} {
      bool attached = false;
      JNIEnv *env = sunshine_android::attach_env(&attached);
      if (env) {
        jfloatArray local_buffer = env->NewFloatArray(static_cast<jsize>(sample_count_));
        if (local_buffer) {
          buffer_ = static_cast<jfloatArray>(env->NewGlobalRef(local_buffer));
          env->DeleteLocalRef(local_buffer);
        }
      }
      sunshine_android::detach_env(attached);

      if (!buffer_) {
        sunshine_android::call_log(4, "Unable to allocate SunshineAudioSource buffer");
        return;
      }

      open_ = sunshine_android::call_audio_source_start(
        static_cast<int>(sample_rate_),
        channel_count_,
        frame_size_
      );
    }

    ~java_audio_mic_t() override {
      close();
    }

    bool is_open() const {
      return open_;
    }

    platf::capture_e sample(std::vector<float> &frame_buffer) override {
      if (!open_ || !buffer_) {
        return platf::capture_e::reinit;
      }

      while (pending_frames() < frame_size_) {
        const auto remaining_frames = frame_size_ - pending_frames();
        const int frames = sunshine_android::call_audio_source_read(buffer_, static_cast<int>(remaining_frames));
        if (frames == 0) {
          return platf::capture_e::timeout;
        }
        if (frames < 0) {
          pending_.clear();
          close();
          return platf::capture_e::reinit;
        }

        const auto captured_frames = std::min<std::size_t>(static_cast<std::size_t>(frames), remaining_frames);
        const auto captured_samples = captured_frames * static_cast<std::size_t>(channel_count_);
        scratch_.assign(captured_samples, 0.0f);

        bool attached = false;
        JNIEnv *env = sunshine_android::attach_env(&attached);
        if (!env) {
          pending_.clear();
          close();
          return platf::capture_e::reinit;
        }
        env->GetFloatArrayRegion(buffer_, 0, static_cast<jsize>(captured_samples), scratch_.data());
        if (env->ExceptionCheck()) {
          env->ExceptionDescribe();
          env->ExceptionClear();
          sunshine_android::detach_env(attached);
          pending_.clear();
          close();
          return platf::capture_e::reinit;
        }
        sunshine_android::detach_env(attached);

        pending_.insert(pending_.end(), scratch_.begin(), scratch_.end());
        sunshine_android::record_audio_frame(captured_samples);
      }

      frame_buffer.assign(pending_.begin(), pending_.begin() + static_cast<std::ptrdiff_t>(sample_count_));
      pending_.erase(pending_.begin(), pending_.begin() + static_cast<std::ptrdiff_t>(sample_count_));
      return platf::capture_e::ok;
    }

  private:
    std::size_t pending_frames() const {
      return pending_.size() / static_cast<std::size_t>(channel_count_);
    }

    void close() {
      if (open_) {
        open_ = false;
        sunshine_android::call_audio_source_stop();
      }
      if (buffer_) {
        bool attached = false;
        JNIEnv *env = sunshine_android::attach_env(&attached);
        if (env) {
          env->DeleteGlobalRef(buffer_);
        }
        sunshine_android::detach_env(attached);
        buffer_ = nullptr;
      }
    }

    std::uint32_t sample_rate_;
    std::uint32_t frame_size_;
    int channel_count_;
    std::size_t sample_count_;
    jfloatArray buffer_ = nullptr;
    std::vector<float> pending_;
    std::vector<float> scratch_;
    bool open_ = false;
  };

  class native_audio_mic_t: public platf::mic_t {
  public:
    native_audio_mic_t(std::uint32_t sample_rate, std::uint32_t frame_size, int channel_count):
        sample_rate_ {std::max<std::uint32_t>(sample_rate, 1)},
        frame_size_ {std::max<std::uint32_t>(frame_size, 1)},
        channel_count_ {std::max(1, channel_count)},
        sample_count_ {static_cast<std::size_t>(frame_size_) * static_cast<std::size_t>(channel_count_)} {
      // Playback capture can be active before a Moonlight client requests its
      // audio stream. Never encode that pre-session backlog.
      arctrl::sandbox_audio::drop_sunshine_audio_backlog();
    }

    bool is_open() const {
      return arctrl::sandbox_audio::has_sunshine_native_source();
    }

    platf::capture_e sample(std::vector<float> &frame_buffer) override {
      if (!arctrl::sandbox_audio::has_sunshine_native_source()) {
        pending_.clear();
        return platf::capture_e::reinit;
      }

      while (pending_frames() < frame_size_) {
        const auto remaining_frames = frame_size_ - pending_frames();
        scratch_.assign(
          remaining_frames * static_cast<std::size_t>(channel_count_),
          0.0f
        );
        int frames = arctrl::sandbox_audio::read_sunshine_float(
          static_cast<int>(sample_rate_),
          channel_count_,
          scratch_.data(),
          static_cast<int>(remaining_frames)
        );
        if (frames == 0) {
          // Preserve a partial Opus frame and retry when the producer delivers
          // more PCM. The queue/AudioRecord is the audio clock; adding another
          // timer here halves throughput for bursty sandbox AudioTrack writes.
          return platf::capture_e::timeout;
        }
        if (frames < 0) {
          pending_.clear();
          return platf::capture_e::reinit;
        }
        const auto captured_frames = std::min<std::size_t>(static_cast<std::size_t>(frames), remaining_frames);
        const auto captured_samples = captured_frames * static_cast<std::size_t>(channel_count_);
        pending_.insert(pending_.end(), scratch_.begin(), scratch_.begin() + static_cast<std::ptrdiff_t>(captured_samples));
        sunshine_android::record_audio_frame(captured_samples);
      }

      frame_buffer.assign(pending_.begin(), pending_.begin() + static_cast<std::ptrdiff_t>(sample_count_));
      pending_.erase(pending_.begin(), pending_.begin() + static_cast<std::ptrdiff_t>(sample_count_));
      return platf::capture_e::ok;
    }

  private:
    std::size_t pending_frames() const {
      return pending_.size() / static_cast<std::size_t>(channel_count_);
    }

    std::uint32_t sample_rate_;
    std::uint32_t frame_size_;
    int channel_count_;
    std::size_t sample_count_;
    std::vector<float> pending_;
    std::vector<float> scratch_;
  };

  class audio_control_t: public platf::audio_control_t {
  public:
    int set_sink(const std::string &) override {
      return 0;
    }

    std::unique_ptr<platf::mic_t> microphone(
      const std::uint8_t *,
      int channel_count,
      std::uint32_t sample_rate,
      std::uint32_t frame_size,
      bool,
      bool
    ) override {
      if (arctrl::sandbox_audio::has_sunshine_native_source()) {
        auto mic = std::make_unique<native_audio_mic_t>(sample_rate, frame_size, std::max(1, channel_count));
        if (mic->is_open()) {
          BOOST_LOG(info) << "Using Arctrl native audio source for Sunshine";
          return mic;
        }
      }

      auto mic = std::make_unique<java_audio_mic_t>(sample_rate, frame_size, std::max(1, channel_count));
      if (mic->is_open()) {
        return mic;
      }

      BOOST_LOG(warning) << "Android audio source is not configured";
      return nullptr;
    }

    bool is_sink_available(const std::string &) override {
      return true;
    }

    std::optional<platf::sink_t> sink_info() override {
      return platf::sink_t {"android-audio-source", std::nullopt};
    }
  };

  struct input_state_t {
    float mouse_x = 0.5f;
    float mouse_y = 0.5f;
  };

  class client_input_t: public platf::client_input_t {};

  sockaddr_storage to_sockaddr(const boost::asio::ip::address &address, std::uint16_t port, socklen_t &len) {
    sockaddr_storage storage {};
    if (address.is_v4()) {
      auto *addr = reinterpret_cast<sockaddr_in *>(&storage);
      addr->sin_family = AF_INET;
      addr->sin_port = htons(port);
      auto bytes = address.to_v4().to_bytes();
      std::memcpy(&addr->sin_addr, bytes.data(), bytes.size());
      len = sizeof(sockaddr_in);
    } else {
      auto *addr = reinterpret_cast<sockaddr_in6 *>(&storage);
      addr->sin6_family = AF_INET6;
      addr->sin6_port = htons(port);
      auto bytes = address.to_v6().to_bytes();
      std::memcpy(&addr->sin6_addr, bytes.data(), bytes.size());
      len = sizeof(sockaddr_in6);
    }
    return storage;
  }

  bool send_one(
    std::uintptr_t native_socket,
    boost::asio::ip::address &target_address,
    std::uint16_t target_port,
    const iovec *iov,
    int iov_count
  ) {
    socklen_t addr_len = 0;
    auto storage = to_sockaddr(target_address, target_port, addr_len);

    msghdr message {};
    message.msg_name = &storage;
    message.msg_namelen = addr_len;
    message.msg_iov = const_cast<iovec *>(iov);
    message.msg_iovlen = static_cast<size_t>(iov_count);

    return ::sendmsg(static_cast<int>(native_socket), &message, MSG_NOSIGNAL) >= 0;
  }

  std::string default_files_dir() {
    auto config = sunshine_android::runtime_config();
    if (!config.files_dir.empty()) {
      return config.files_dir;
    }
    return "/data/local/tmp/sunshine";
  }
}

namespace platf {
  void freeInput(void *input) {
    delete static_cast<input_state_t *>(input);
  }

  std::filesystem::path appdata() {
    return default_files_dir();
  }

  std::string get_mac_address(const std::string_view &) {
    return "00:00:00:00:00:00";
  }

  std::string from_sockaddr(const sockaddr *const ip_addr) {
    return from_sockaddr_ex(ip_addr).second;
  }

  std::pair<std::uint16_t, std::string> from_sockaddr_ex(const sockaddr *const ip_addr) {
    char host[NI_MAXHOST] {};
    std::uint16_t port = 0;
    socklen_t len = 0;
    if (ip_addr->sa_family == AF_INET) {
      auto *addr = reinterpret_cast<const sockaddr_in *>(ip_addr);
      port = ntohs(addr->sin_port);
      len = sizeof(sockaddr_in);
    } else if (ip_addr->sa_family == AF_INET6) {
      auto *addr = reinterpret_cast<const sockaddr_in6 *>(ip_addr);
      port = ntohs(addr->sin6_port);
      len = sizeof(sockaddr_in6);
    }
    if (len && getnameinfo(ip_addr, len, host, sizeof(host), nullptr, 0, NI_NUMERICHOST) == 0) {
      return {port, host};
    }
    return {port, {}};
  }

  std::unique_ptr<audio_control_t> audio_control() {
    return std::make_unique<::audio_control_t>();
  }

  std::shared_ptr<display_t> display(mem_type_e, const std::string &, const video::config_t &) {
    return nullptr;
  }

  std::vector<std::string> display_names(mem_type_e) {
    return {"android-window"};
  }

  bool needs_encoder_reenumeration() {
    return false;
  }

  boost::process::v1::child run_command(
    bool,
    bool,
    const std::string &,
    boost::filesystem::path &,
    const boost::process::v1::environment &,
    FILE *,
    std::error_code &ec,
    boost::process::v1::group *
  ) {
    ec = std::make_error_code(std::errc::function_not_supported);
    return {};
  }

  void adjust_thread_priority(thread_priority_e) {
  }

  void set_thread_name(const std::string &name) {
    std::string truncated = name.substr(0, 15);
    pthread_setname_np(pthread_self(), truncated.c_str());
  }

  void enable_mouse_keys() {
  }

  void streaming_will_start(const std::string &client_address) {
    sunshine_android::call_client_connected("Moonlight", client_address);
  }

  void streaming_will_stop() {
    sunshine_android::call_client_disconnected("Moonlight");
  }

  void restart() {
  }

  int set_env(const std::string &name, const std::string &value) {
    return setenv(name.c_str(), value.c_str(), 1);
  }

  int unset_env(const std::string &name) {
    return unsetenv(name.c_str());
  }

  bool send_batch(batched_send_info_t &send_info) {
    for (size_t block = 0; block < send_info.block_count; ++block) {
      const auto block_index = send_info.block_offset + block;
      const auto payload_offset = static_cast<ptrdiff_t>(block_index * send_info.payload_size);
      auto payload = send_info.buffer_for_payload_offset(payload_offset);
      if (!payload.buffer || payload.size < send_info.payload_size) {
        return false;
      }

      iovec iov[2] {};
      int iov_count = 0;
      if (send_info.header_size > 0) {
        iov[iov_count++] = {const_cast<char *>(send_info.headers + block_index * send_info.header_size), send_info.header_size};
      }
      iov[iov_count++] = {const_cast<char *>(payload.buffer), send_info.payload_size};

      if (!send_one(send_info.native_socket, send_info.target_address, send_info.target_port, iov, iov_count)) {
        return false;
      }
    }
    return true;
  }

  bool send(send_info_t &send_info) {
    iovec iov[2] {};
    int iov_count = 0;
    if (send_info.header_size > 0) {
      iov[iov_count++] = {const_cast<char *>(send_info.header), send_info.header_size};
    }
    iov[iov_count++] = {const_cast<char *>(send_info.payload), send_info.payload_size};
    return send_one(send_info.native_socket, send_info.target_address, send_info.target_port, iov, iov_count);
  }

  std::unique_ptr<deinit_t> enable_socket_qos(uintptr_t, boost::asio::ip::address &, uint16_t, qos_data_type_e, bool) {
    return std::make_unique<noop_deinit_t>();
  }

  void open_url(const std::string &url) {
    BOOST_LOG(info) << "open_url ignored: " << url;
  }

  bool request_process_group_exit(std::uintptr_t) {
    return false;
  }

  bool process_group_running(std::uintptr_t) {
    return false;
  }

  input_t input() {
    return input_t {new input_state_t};
  }

  util::point_t get_mouse_loc(input_t &input) {
    auto *state = static_cast<input_state_t *>(input.get());
    return {static_cast<double>(state ? state->mouse_x : 0), static_cast<double>(state ? state->mouse_y : 0)};
  }

  void move_mouse(input_t &input, int deltaX, int deltaY) {
    auto *state = static_cast<input_state_t *>(input.get());
    if (state) {
      state->mouse_x += static_cast<float>(deltaX);
      state->mouse_y += static_cast<float>(deltaY);
    }
    sunshine_android::call_relative_mouse(deltaX, deltaY);
  }

  void abs_mouse(input_t &input, const touch_port_t &touch_port, float x, float y) {
    auto *state = static_cast<input_state_t *>(input.get());
    if (state) {
      state->mouse_x = x;
      state->mouse_y = y;
    }
    sunshine_android::call_absolute_mouse(x, y, static_cast<float>(std::max(1, touch_port.width)), static_cast<float>(std::max(1, touch_port.height)));
  }

  void button_mouse(input_t &, int button, bool release) {
    sunshine_android::call_mouse_button(button, release);
  }

  void scroll(input_t &, int distance) {
    sunshine_android::call_mouse_scroll(distance, 0);
  }

  void hscroll(input_t &, int distance) {
    sunshine_android::call_mouse_scroll(0, distance);
  }

  void keyboard_update(input_t &, uint16_t modcode, bool release, uint8_t flags) {
    sunshine_android::call_keyboard(modcode, release, flags);
  }

  void gamepad_update(input_t &, int nr, const gamepad_state_t &gamepad_state) {
    sunshine_android::call_gamepad(
      nr,
      gamepad_state.buttonFlags,
      gamepad_state.lt,
      gamepad_state.rt,
      gamepad_state.lsX,
      gamepad_state.lsY,
      gamepad_state.rsX,
      gamepad_state.rsY
    );
  }

  void unicode(input_t &, char *utf8, int size) {
    sunshine_android::call_unicode(utf8, size);
  }

  std::unique_ptr<client_input_t> allocate_client_input_context(input_t &) {
    return std::make_unique<::client_input_t>();
  }

  void touch_update(client_input_t *, const touch_port_t &, const touch_input_t &touch) {
    sunshine_android::call_touch(
      touch.eventType,
      touch.rotation,
      static_cast<int>(touch.pointerId),
      touch.x,
      touch.y,
      touch.pressureOrDistance,
      touch.contactAreaMajor,
      touch.contactAreaMinor
    );
  }

  void pen_update(client_input_t *input, const touch_port_t &touch_port, const pen_input_t &pen) {
    touch_input_t touch {
      pen.eventType,
      pen.rotation,
      0,
      pen.x,
      pen.y,
      pen.pressureOrDistance,
      pen.contactAreaMajor,
      pen.contactAreaMinor,
    };
    touch_update(input, touch_port, touch);
  }

  void gamepad_touch(input_t &, const gamepad_touch_t &touch) {
    sunshine_android::call_touch(touch.eventType, 0, static_cast<int>(touch.pointerId), touch.x, touch.y, touch.pressure, 1.0f, 1.0f);
  }

  void gamepad_motion(input_t &, const gamepad_motion_t &) {
  }

  void gamepad_battery(input_t &, const gamepad_battery_t &) {
  }

  int alloc_gamepad(input_t &, const gamepad_id_t &, const gamepad_arrival_t &, feedback_queue_t) {
    return 0;
  }

  void free_gamepad(input_t &, int nr) {
    sunshine_android::call_gamepad(nr, 0, 0, 0, 0, 0, 0, 0);
  }

  platform_caps::caps_t get_capabilities() {
    return platform_caps::pen_touch | platform_caps::controller_touch;
  }

  namespace publish {
    std::unique_ptr<deinit_t> start() {
      return std::make_unique<noop_deinit_t>();
    }
  }

  std::unique_ptr<deinit_t> init() {
    return std::make_unique<noop_deinit_t>();
  }

  std::string get_host_name() {
    auto config = sunshine_android::runtime_config();
    return config.host_name.empty() ? "Sunshine Android" : config.host_name;
  }

  std::string resolve_render_device() {
    return {};
  }

  std::vector<supported_gamepad_t> &supported_gamepads(input_t *) {
    static std::vector<supported_gamepad_t> gamepads {
      {"auto", true, {}},
      {"android-window", true, {}},
    };
    return gamepads;
  }

  std::unique_ptr<high_precision_timer> create_high_precision_timer() {
    return std::make_unique<sleep_timer_t>();
  }

  bool has_elevated_privileges(bool) {
    return false;
  }

  void drop_elevated_privileges(bool) {
  }
}

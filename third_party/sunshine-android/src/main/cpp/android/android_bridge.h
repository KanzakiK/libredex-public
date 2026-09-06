#pragma once

#include <jni.h>

#include <cstdint>
#include <cstddef>
#include <string>

namespace sunshine_android {
  struct runtime_config_t {
    std::string files_dir;
    std::string state_path;
    std::string cert_path;
    std::string key_path;
    std::string host_name;
    int port = 47989;
    bool enable_hevc = true;
    bool enable_audio = true;
  };

  JavaVM *java_vm();
  runtime_config_t runtime_config();

  JNIEnv *attach_env(bool *attached);
  void detach_env(bool attached);

  void call_video_input_surface(jobject surface, int width, int height, int frame_rate);
  void call_video_stopped();
  void call_pin_requested();
  void call_client_connected(const std::string &name, const std::string &address);
  void call_client_disconnected(const std::string &name);
  void call_encoder_error(const std::string &message);
  void call_log(int level, const std::string &message);

  bool call_audio_source_start(int sample_rate, int channel_count, std::uint32_t frame_size);
  int call_audio_source_read(jfloatArray buffer, int frame_count);
  void call_audio_source_stop();

  struct stats_snapshot_t {
    std::uint64_t video_frames = 0;
    std::uint64_t video_key_frames = 0;
    std::uint64_t video_bytes = 0;
    std::uint64_t audio_frames = 0;
    std::uint64_t audio_samples = 0;
  };

  void reset_stats();
  void record_video_frame(std::size_t bytes, bool key_frame);
  void record_audio_frame(std::uint64_t samples);
  stats_snapshot_t stats_snapshot();

  void call_touch(int event_type, int rotation, int pointer_id, float x, float y, float pressure, float major, float minor);
  void call_absolute_mouse(float x, float y, float width, float height);
  void call_relative_mouse(int dx, int dy);
  void call_mouse_button(int button, bool release);
  void call_mouse_scroll(int vertical, int horizontal);
  void call_keyboard(int windows_key_code, bool release, int flags);
  void call_unicode(const char *utf8, int size);
  void call_gamepad(int controller, std::uint32_t buttons, std::uint8_t lt, std::uint8_t rt, std::int16_t ls_x, std::int16_t ls_y, std::int16_t rs_x, std::int16_t rs_y);

}

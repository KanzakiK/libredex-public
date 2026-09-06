#include "android_bridge.h"

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <string>
#include <thread>
#include <vector>

#include "src/config.h"
#include "src/globals.h"
#include "src/logging.h"
#include "src/video.h"
#include "android_video_nal.h"

using namespace std::literals;

// LibreDeX 扩展（m7）：官方编码参数配置通道。
// 官方 SunshineHostConfig 无编码参数入口（Android 编码参数由客户端协商 + 此处默认），
// 此全局设置让 Java 侧 setLibreDeXEncoderSettings 驱动 AMediaCodec 编码参数。
struct libredex_encoder_settings_t {
  int bitrate_percent = 100;
  int bitrate_mode = 2;  // MediaCodec BITRATE_MODE_CBR
  int complexity = 10;
  int i_frame_interval = 1;
  int max_fps = 0;  // 0 = 跟随客户端
  bool low_latency = true;
  bool disable_b_frames = true;
  bool realtime_priority = true;
};
libredex_encoder_settings_t g_libredex_encoder;

void set_libredex_encoder_settings(
    int bitrate_percent, int bitrate_mode, int complexity,
    int i_frame_interval, int max_fps,
    bool low_latency, bool disable_b_frames, bool realtime_priority) {
  g_libredex_encoder.bitrate_percent = bitrate_percent;
  g_libredex_encoder.bitrate_mode = bitrate_mode;
  g_libredex_encoder.complexity = complexity;
  g_libredex_encoder.i_frame_interval = std::max(1, i_frame_interval);
  g_libredex_encoder.max_fps = max_fps;
  g_libredex_encoder.low_latency = low_latency;
  g_libredex_encoder.disable_b_frames = disable_b_frames;
  g_libredex_encoder.realtime_priority = realtime_priority;
}

namespace {
  constexpr int COLOR_FormatSurface = 0x7F000789;
  constexpr int AVCProfileHigh = 0x08;
  constexpr int AVCLevel42 = 0x200;
  constexpr int HEVCProfileMain = 1;
  constexpr int HEVCProfileMain10 = 2;
  constexpr int HEVCMainTierLevel51 = 0x10000;
  constexpr int COLOR_STANDARD_BT709 = 1;
  constexpr int COLOR_STANDARD_BT601_NTSC = 4;
  constexpr int COLOR_STANDARD_BT2020 = 6;
  constexpr int ANDROID_COLOR_RANGE_FULL = 1;
  constexpr int ANDROID_COLOR_RANGE_LIMITED = 2;
  constexpr int COLOR_TRANSFER_SDR_VIDEO = 3;
  constexpr int COLOR_TRANSFER_ST2084 = 6;

  const char *mime_for_config(const video::config_t &config) {
    if (config.videoFormat == 1) {
      return "video/hevc";
    }
    if (config.videoFormat == 0) {
      return "video/avc";
    }
    return nullptr;
  }

  int color_standard_for_config(const video::config_t &config) {
    switch (config.encoderCscMode >> 1) {
      case 0:
        return COLOR_STANDARD_BT601_NTSC;
      case 2:
        return COLOR_STANDARD_BT2020;
      default:
        return COLOR_STANDARD_BT709;
    }
  }

  void set_common_encoder_format(AMediaFormat *format, const video::config_t &config, int width, int height, int frame_rate, int bitrate) {
    const int encode_frame_rate = frame_rate;
    // LibreDeX 扩展（m7）：应用编码设置（码率百分比 / 码率模式 / 帧率上限 / I 帧间隔 / 低延迟 / B 帧 / 复杂度）
    int effective_bitrate = bitrate;
    if (g_libredex_encoder.bitrate_percent != 100) {
      effective_bitrate = static_cast<int>((static_cast<int64_t>(bitrate) * g_libredex_encoder.bitrate_percent) / 100);
    }
    int eff_frame_rate = encode_frame_rate;
    if (g_libredex_encoder.max_fps > 0 && g_libredex_encoder.max_fps < eff_frame_rate) {
      eff_frame_rate = g_libredex_encoder.max_fps;
    }
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, height);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, effective_bitrate);
    AMediaFormat_setInt32(format, "bitrate-mode", g_libredex_encoder.bitrate_mode);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, eff_frame_rate);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, g_libredex_encoder.i_frame_interval);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, COLOR_FormatSurface);

    AMediaFormat_setInt32(format, "operating-rate", eff_frame_rate);
    AMediaFormat_setInt32(format, "capture-rate", eff_frame_rate);
    AMediaFormat_setFloat(format, "max-fps-to-encoder", static_cast<float>(eff_frame_rate));
    AMediaFormat_setInt32(format, "latency", g_libredex_encoder.low_latency ? 0 : 1);
    AMediaFormat_setInt32(format, "complexity", g_libredex_encoder.complexity);
    AMediaFormat_setInt32(format, "max-bframes", g_libredex_encoder.disable_b_frames ? 0 : 1);

    AMediaFormat_setInt32(format, "color-standard", color_standard_for_config(config));
    AMediaFormat_setInt32(format, "color-range", (config.encoderCscMode & 0x1) ? ANDROID_COLOR_RANGE_FULL : ANDROID_COLOR_RANGE_LIMITED);
    AMediaFormat_setInt32(format, "color-transfer", config.dynamicRange > 0 ? COLOR_TRANSFER_ST2084 : COLOR_TRANSFER_SDR_VIDEO);

    if (config.videoFormat == 1) {
      AMediaFormat_setInt32(format, "profile", config.dynamicRange > 0 ? HEVCProfileMain10 : HEVCProfileMain);
      AMediaFormat_setInt32(format, "level", HEVCMainTierLevel51);
    } else {
      AMediaFormat_setInt32(format, "profile", AVCProfileHigh);
      AMediaFormat_setInt32(format, "level", AVCLevel42);
      AMediaFormat_setInt32(format, "vendor.qti-ext-enc-low-latency.enable", 1);
    }
  }

  class media_format_t {
  public:
    media_format_t():
        format {AMediaFormat_new()} {
    }

    ~media_format_t() {
      if (format) {
        AMediaFormat_delete(format);
      }
    }

    AMediaFormat *get() const {
      return format;
    }

  private:
    AMediaFormat *format {};
  };

  class media_codec_t {
  public:
    explicit media_codec_t(const char *mime):
        codec {AMediaCodec_createEncoderByType(mime)} {
    }

    ~media_codec_t() {
      if (codec) {
        AMediaCodec_delete(codec);
      }
    }

    AMediaCodec *get() const {
      return codec;
    }

  private:
    AMediaCodec *codec {};
  };

  void append_codec_config(std::vector<std::uint8_t> &codec_config, const std::uint8_t *data, size_t size) {
    codec_config.assign(data, data + size);
  }

  void append_format_buffer(AMediaFormat *format, const char *key, std::vector<std::uint8_t> &codec_config) {
    void *data = nullptr;
    size_t size = 0;
    if (AMediaFormat_getBuffer(format, key, &data, &size) && data && size > 0) {
      auto *bytes = static_cast<std::uint8_t *>(data);
      codec_config.insert(codec_config.end(), bytes, bytes + size);
    }
  }

  void read_output_codec_config(AMediaCodec *codec, std::vector<std::uint8_t> &codec_config) {
    AMediaFormat *output_format = AMediaCodec_getOutputFormat(codec);
    if (!output_format) {
      return;
    }

    std::vector<std::uint8_t> next_config;
    append_format_buffer(output_format, "csd-0", next_config);
    append_format_buffer(output_format, "csd-1", next_config);
    append_format_buffer(output_format, "csd-2", next_config);
    if (!next_config.empty()) {
      codec_config = std::move(next_config);
    }

    AMediaFormat_delete(output_format);
  }

  void prepend_codec_config(std::vector<std::uint8_t> &frame, const std::vector<std::uint8_t> &codec_config) {
    if (codec_config.empty()) {
      return;
    }
    frame.insert(frame.begin(), codec_config.begin(), codec_config.end());
  }
}

namespace video {
  int active_hevc_mode = 2;
  int active_av1_mode = 1;
  bool last_encoder_probe_supported_ref_frames_invalidation = false;
  std::array<bool, 3> last_encoder_probe_supported_yuv444_for_codec {false, false, false};

  bool validate_encoder(encoder_t &, bool) {
    return true;
  }

  int probe_encoders() {
    active_hevc_mode = config::video.hevc_mode;
    active_av1_mode = 1;
    last_encoder_probe_supported_ref_frames_invalidation = false;
    last_encoder_probe_supported_yuv444_for_codec = {false, false, false};
    return 0;
  }

  void capture(safe::mail_t mail, config_t config, void *channel_data) {
    platf::set_thread_name("android::video");

    auto shutdown_event = mail->event<bool>(mail::shutdown);
    auto idr_events = mail->event<bool>(mail::idr);
    auto invalidate_ref_frames_events = mail->event<std::pair<int64_t, int64_t>>(mail::invalidate_ref_frames);
    auto packets = mail::man->queue<packet_t>(mail::video_packets);
    auto touch_port_event = mail->event<input::touch_port_t>(mail::touch_port);

    const int width = std::max(16, config.width);
    const int height = std::max(16, config.height);
    const int frame_rate = std::max(1, config.framerate);
    const int bitrate = std::max(1000000, config.bitrate * 1000);
    const char *mime = mime_for_config(config);
    if (!mime) {
      sunshine_android::call_encoder_error("Unsupported video codec requested; only H.264 and HEVC are supported");
      shutdown_event->view();
      return;
    }

    media_format_t format;
    AMediaFormat_setString(format.get(), AMEDIAFORMAT_KEY_MIME, mime);
    set_common_encoder_format(format.get(), config, width, height, frame_rate, bitrate);

    media_codec_t codec {mime};
    if (!codec.get()) {
      sunshine_android::call_encoder_error(std::string {"Unable to create MediaCodec encoder for "} + mime);
      shutdown_event->view();
      return;
    }

    media_status_t status = AMediaCodec_configure(codec.get(), format.get(), nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    if (status != AMEDIA_OK) {
      sunshine_android::call_encoder_error("Unable to configure MediaCodec encoder");
      shutdown_event->view();
      return;
    }

    ANativeWindow *input_surface = nullptr;
    status = AMediaCodec_createInputSurface(codec.get(), &input_surface);
    if (status != AMEDIA_OK || !input_surface) {
      sunshine_android::call_encoder_error("Unable to create MediaCodec input surface");
      shutdown_event->view();
      return;
    }

    status = AMediaCodec_start(codec.get());
    if (status != AMEDIA_OK) {
      sunshine_android::call_encoder_error("Unable to start MediaCodec encoder");
      ANativeWindow_release(input_surface);
      shutdown_event->view();
      return;
    }

    bool attached = false;
    JNIEnv *env = sunshine_android::attach_env(&attached);
    jobject surface = env ? ANativeWindow_toSurface(env, input_surface) : nullptr;
    if (surface) {
      sunshine_android::call_video_input_surface(surface, width, height, frame_rate);
      env->DeleteLocalRef(surface);
    }
    sunshine_android::detach_env(attached);

    input::touch_port_t touch_port {};
    touch_port.width = width;
    touch_port.height = height;
    touch_port.logical_width = width;
    touch_port.logical_height = height;
    touch_port.env_width = width;
    touch_port.env_height = height;
    touch_port.env_logical_width = width;
    touch_port.env_logical_height = height;
    touch_port.scalar_inv = 1.0f;
    touch_port.scalar_tpcoords = 1.0f;
    touch_port_event->raise(touch_port);

    std::vector<std::uint8_t> codec_config;
    int64_t frame_index = 0;
    bool request_idr = true;
    auto last_forced_idr_request = std::chrono::steady_clock::now() - 1s;

    while (!shutdown_event->peek()) {
      while (idr_events->peek()) {
        idr_events->pop(0ms);
        request_idr = true;
      }
      while (invalidate_ref_frames_events->peek()) {
        invalidate_ref_frames_events->pop(0ms);
        request_idr = true;
      }

      const auto now = std::chrono::steady_clock::now();
      if (now - last_forced_idr_request >= 1s) {
        request_idr = true;
      }

      if (request_idr) {
        media_format_t params;
        AMediaFormat_setInt32(params.get(), "request-sync", 0);
        const media_status_t request_status = AMediaCodec_setParameters(codec.get(), params.get());
        last_forced_idr_request = now;
        if (request_status != AMEDIA_OK) {
          BOOST_LOG(warning) << "Android encoder request-sync failed, status="sv << request_status;
        }
        request_idr = false;
      }

      AMediaCodecBufferInfo info {};
      ssize_t index = AMediaCodec_dequeueOutputBuffer(codec.get(), &info, 10000);
      if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
        continue;
      }
      if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
        read_output_codec_config(codec.get(), codec_config);
        continue;
      }
      if (index < 0) {
        continue;
      }

      size_t buffer_size = 0;
      std::uint8_t *buffer = AMediaCodec_getOutputBuffer(codec.get(), static_cast<size_t>(index), &buffer_size);
      if (!buffer || info.size <= 0 || info.offset < 0 || static_cast<size_t>(info.offset + info.size) > buffer_size) {
        AMediaCodec_releaseOutputBuffer(codec.get(), static_cast<size_t>(index), false);
        continue;
      }

      auto *data = buffer + info.offset;
      const auto size = static_cast<size_t>(info.size);
      if (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) {
        append_codec_config(codec_config, data, size);
        AMediaCodec_releaseOutputBuffer(codec.get(), static_cast<size_t>(index), false);
        continue;
      }

      std::vector<std::uint8_t> frame(data, data + size);
      const bool codec_key_frame = (info.flags & AMEDIACODEC_BUFFER_FLAG_KEY_FRAME) != 0;
      const bool parsed_key_frame = sunshine_android::video_nal::contains_key_picture(data, size, config.videoFormat);
      const bool key_frame = codec_key_frame || parsed_key_frame;
      if (key_frame) {
        prepend_codec_config(frame, codec_config);
      }

      const auto frame_size = frame.size();
      auto packet = std::make_unique<packet_raw_generic>(std::move(frame), frame_index++, key_frame);
      packet->channel_data = channel_data;
      packet->frame_timestamp = std::chrono::steady_clock::now();
      packets->raise(std::move(packet));
      sunshine_android::record_video_frame(frame_size, key_frame);

      AMediaCodec_releaseOutputBuffer(codec.get(), static_cast<size_t>(index), false);
    }

    AMediaCodec_signalEndOfInputStream(codec.get());
    AMediaCodec_stop(codec.get());
    sunshine_android::call_video_stopped();
    ANativeWindow_release(input_surface);
  }
}

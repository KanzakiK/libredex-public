#pragma once

#include <cstddef>
#include <cstdint>

namespace sunshine_android::video_nal {
  constexpr int VIDEO_FORMAT_AVC = 0;
  constexpr int VIDEO_FORMAT_HEVC = 1;

  bool contains_key_picture(const std::uint8_t *data, std::size_t size, int video_format);
}

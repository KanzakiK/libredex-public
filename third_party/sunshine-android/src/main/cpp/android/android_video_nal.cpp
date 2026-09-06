#include "android_video_nal.h"

namespace sunshine_android::video_nal {
  namespace {
    bool start_code_at(const std::uint8_t *data, std::size_t size, std::size_t offset, std::size_t &start_code_size) {
      if (offset + 3 <= size && data[offset] == 0 && data[offset + 1] == 0 && data[offset + 2] == 1) {
        start_code_size = 3;
        return true;
      }
      if (offset + 4 <= size && data[offset] == 0 && data[offset + 1] == 0 && data[offset + 2] == 0 && data[offset + 3] == 1) {
        start_code_size = 4;
        return true;
      }
      return false;
    }

    bool find_start_code(const std::uint8_t *data, std::size_t size, std::size_t offset, std::size_t &start_code_offset, std::size_t &start_code_size) {
      for (std::size_t i = offset; i + 3 <= size; ++i) {
        if (start_code_at(data, size, i, start_code_size)) {
          start_code_offset = i;
          return true;
        }
      }
      return false;
    }

    bool nal_type_is_key_picture(int video_format, int nal_type) {
      if (video_format == VIDEO_FORMAT_AVC) {
        return nal_type == 5;
      }
      if (video_format == VIDEO_FORMAT_HEVC) {
        return nal_type == 19 || nal_type == 20 || nal_type == 21;
      }
      return false;
    }

    int nal_type_for_header(int video_format, const std::uint8_t *data, std::size_t size) {
      if (!data || size == 0) {
        return -1;
      }
      if (video_format == VIDEO_FORMAT_AVC) {
        return data[0] & 0x1F;
      }
      if (video_format == VIDEO_FORMAT_HEVC && size >= 2) {
        return (data[0] >> 1) & 0x3F;
      }
      return -1;
    }

    bool contains_key_picture_annex_b(const std::uint8_t *data, std::size_t size, int video_format) {
      std::size_t start_code_offset = 0;
      std::size_t start_code_size = 0;
      if (!find_start_code(data, size, 0, start_code_offset, start_code_size)) {
        return false;
      }

      while (true) {
        const std::size_t nal_offset = start_code_offset + start_code_size;
        std::size_t next_start_code_offset = 0;
        std::size_t next_start_code_size = 0;
        const bool has_next_start_code = find_start_code(data, size, nal_offset, next_start_code_offset, next_start_code_size);
        const std::size_t nal_end = has_next_start_code ? next_start_code_offset : size;
        if (nal_offset < nal_end) {
          const int nal_type = nal_type_for_header(video_format, data + nal_offset, nal_end - nal_offset);
          if (nal_type_is_key_picture(video_format, nal_type)) {
            return true;
          }
        }
        if (!has_next_start_code) {
          return false;
        }
        start_code_offset = next_start_code_offset;
        start_code_size = next_start_code_size;
      }
    }

    bool contains_key_picture_length_prefixed(const std::uint8_t *data, std::size_t size, int video_format, std::size_t length_size) {
      std::size_t offset = 0;
      while (offset + length_size <= size) {
        std::size_t nal_size = 0;
        for (std::size_t i = 0; i < length_size; ++i) {
          nal_size = (nal_size << 8) | data[offset + i];
        }
        offset += length_size;
        if (nal_size == 0 || nal_size > size - offset) {
          return false;
        }
        const int nal_type = nal_type_for_header(video_format, data + offset, nal_size);
        if (nal_type_is_key_picture(video_format, nal_type)) {
          return true;
        }
        offset += nal_size;
      }
      return false;
    }
  }

  bool contains_key_picture(const std::uint8_t *data, std::size_t size, int video_format) {
    if (!data || size == 0) {
      return false;
    }
    if (video_format != VIDEO_FORMAT_AVC && video_format != VIDEO_FORMAT_HEVC) {
      return false;
    }
    if (contains_key_picture_annex_b(data, size, video_format)) {
      return true;
    }
    return contains_key_picture_length_prefixed(data, size, video_format, 4) ||
           contains_key_picture_length_prefixed(data, size, video_format, 2);
  }
}

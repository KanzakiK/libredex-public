#include "sandbox_audio_native_bridge.h"

#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <inttypes.h>
#include <limits>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <errno.h>
#include <stddef.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

namespace arctrl::sandbox_audio {
namespace {

constexpr const char *kLogTag = "ArctrlSandboxAudioHost";
constexpr std::uint32_t kPacketMagic = 0x41535841;  // AXSA
constexpr std::uint16_t kPacketVersion = 2;
constexpr std::uint16_t kMinPacketVersion = 1;
constexpr std::size_t kMaxPacketBytes = 64 * 1024;
// This queue carries live playback audio, not a recording. Keep enough room for
// one common 80 ms AudioTrack.write() plus scheduling jitter, without retaining
// the seconds-long backlog that would make a new Moonlight session lag behind.
// This is a capacity limit, not a startup buffer: readers consume immediately.
constexpr int kMaxQueuedAudioMs = 120;
constexpr int kQueueStatsIntervalMs = 5000;
constexpr int kSocketBufferBytes = 1024 * 1024;
// AudioTrack commonly delivers PCM in 20 ms bursts. This is only a condition
// wait (a producer wakes it immediately), not added steady-state latency.
constexpr int kQueueReadTimeoutMs = 25;
constexpr std::uint32_t kAudioFormatDefault = 0;
constexpr std::uint32_t kAudioFormatPcm16Bit = 1;
constexpr std::uint32_t kAudioFormatPcm8Bit = 2;
constexpr std::uint32_t kAudioFormatPcm32Bit = 3;
constexpr std::uint32_t kAudioFormatPcm8_24Bit = 4;
constexpr std::uint32_t kAudioFormatPcmFloat = 5;
constexpr std::uint32_t kAudioFormatPcm24BitPacked = 6;

#define AUDIO_LOGI(...) __android_log_print(ANDROID_LOG_INFO, kLogTag, __VA_ARGS__)
#define AUDIO_LOGW(...) __android_log_print(ANDROID_LOG_WARN, kLogTag, __VA_ARGS__)

#pragma pack(push, 1)
struct PacketHeader {
    std::uint32_t magic;
    std::uint16_t version;
    std::uint16_t header_size;
    std::uint32_t sample_rate;
    std::uint16_t channel_count;
    std::uint16_t bytes_per_sample;
    std::uint32_t frame_count;
    std::uint64_t timestamp_ns;
    std::uint32_t sample_format;
};
#pragma pack(pop)

struct StereoFrame {
    float left;
    float right;
};

constexpr std::size_t kPacketHeaderV1Size = offsetof(PacketHeader, sample_format);

std::string socket_name(const char *consumer_name) {
    std::string name = "arctrl_sandbox_audio_" + std::to_string(getuid());
    if (consumer_name != nullptr && consumer_name[0] != '\0') {
        name += "_";
        name += consumer_name;
    }
    return name;
}

socklen_t fill_abstract_address(sockaddr_un *addr, const std::string &name) {
    std::memset(addr, 0, sizeof(*addr));
    addr->sun_family = AF_UNIX;
    addr->sun_path[0] = '\0';
    std::memcpy(addr->sun_path + 1, name.data(), name.size());
    return static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name.size());
}

std::uint32_t normalize_sample_format(std::uint32_t format) {
    return format == kAudioFormatDefault ? kAudioFormatPcm16Bit : format;
}

int bytes_per_sample_for_format(std::uint32_t format) {
    switch (normalize_sample_format(format)) {
        case kAudioFormatPcm8Bit:
            return 1;
        case kAudioFormatPcm16Bit:
            return 2;
        case kAudioFormatPcm24BitPacked:
            return 3;
        case kAudioFormatPcm32Bit:
        case kAudioFormatPcm8_24Bit:
        case kAudioFormatPcmFloat:
            return 4;
        default:
            return 0;
    }
}

const char *sample_format_name(std::uint32_t format) {
    switch (normalize_sample_format(format)) {
        case kAudioFormatPcm8Bit:
            return "pcm8";
        case kAudioFormatPcm16Bit:
            return "pcm16";
        case kAudioFormatPcm24BitPacked:
            return "pcm24";
        case kAudioFormatPcm32Bit:
            return "pcm32";
        case kAudioFormatPcm8_24Bit:
            return "pcm8_24";
        case kAudioFormatPcmFloat:
            return "float";
        default:
            return "unsupported";
    }
}

float sample_as_float(const std::uint8_t *bytes, std::size_t sample_index, std::uint32_t sample_format, int bytes_per_sample) {
    const std::uint8_t *sample = bytes + sample_index * static_cast<std::size_t>(bytes_per_sample);
    switch (normalize_sample_format(sample_format)) {
        case kAudioFormatPcm8Bit:
            return std::clamp((static_cast<int>(*sample) - 128) / 128.0f, -1.0f, 1.0f);
        case kAudioFormatPcm16Bit: {
            int16_t value = 0;
            std::memcpy(&value, sample, sizeof(value));
            return std::clamp(static_cast<float>(value) / 32768.0f, -1.0f, 1.0f);
        }
        case kAudioFormatPcm24BitPacked: {
            int32_t value =
                static_cast<int32_t>(sample[0]) |
                (static_cast<int32_t>(sample[1]) << 8) |
                (static_cast<int32_t>(sample[2]) << 16);
            if ((value & 0x00800000) != 0) {
                value |= static_cast<int32_t>(0xFF000000);
            }
            return std::clamp(static_cast<float>(value) / 8388608.0f, -1.0f, 1.0f);
        }
        case kAudioFormatPcm32Bit: {
            int32_t value = 0;
            std::memcpy(&value, sample, sizeof(value));
            return std::clamp(static_cast<float>(static_cast<double>(value) / 2147483648.0), -1.0f, 1.0f);
        }
        case kAudioFormatPcm8_24Bit: {
            int32_t value = 0;
            std::memcpy(&value, sample, sizeof(value));
            return std::clamp(static_cast<float>(static_cast<double>(value) / 2147483648.0), -1.0f, 1.0f);
        }
        case kAudioFormatPcmFloat: {
            float value = 0.0f;
            std::memcpy(&value, sample, sizeof(value));
            if (!std::isfinite(value)) {
                return 0.0f;
            }
            return std::clamp(value, -1.0f, 1.0f);
        }
        default:
            return 0.0f;
    }
}

float lerp_sample(float a, float b, double t) {
    double value = static_cast<double>(a) + (static_cast<double>(b) - static_cast<double>(a)) * t;
    return std::clamp(static_cast<float>(value), -1.0f, 1.0f);
}

int16_t float_to_int16(float value) {
    if (!std::isfinite(value)) {
        return 0;
    }
    if (value <= -1.0f) {
        return std::numeric_limits<int16_t>::min();
    }
    if (value >= 1.0f) {
        return std::numeric_limits<int16_t>::max();
    }
    return static_cast<int16_t>(std::lrint(value * 32767.0f));
}

class PcmQueue {
public:
    explicit PcmQueue(const char *label): label_(label) {}

    void clear() {
        std::lock_guard<std::mutex> lock(mu_);
        ring_head_ = 0;
        queued_frames_ = 0;
        source_rate_ = kDefaultSampleRate;
        source_channels_ = kDefaultChannelCount;
        source_format_ = kAudioFormatPcm16Bit;
        position_ = 0.0;
        total_frames_pushed_ = 0;
        total_frames_read_ = 0;
        total_frames_dropped_ = 0;
        total_wait_timeouts_ = 0;
        next_drop_log_frame_ = 1;
        max_queued_frames_ = 0;
        last_stats_time_ = {};
        last_stats_frames_pushed_ = 0;
        last_stats_frames_read_ = 0;
        last_stats_frames_dropped_ = 0;
        last_stats_wait_timeouts_ = 0;
    }

    void push(const PacketHeader &header, const std::uint8_t *payload, std::size_t payload_size) {
        std::uint32_t sample_format = header.header_size >= sizeof(PacketHeader)
            ? normalize_sample_format(header.sample_format)
            : kAudioFormatPcm16Bit;
        int bytes_per_sample = bytes_per_sample_for_format(sample_format);
        if (payload == nullptr ||
            header.sample_rate == 0 ||
            header.channel_count == 0 ||
            bytes_per_sample <= 0 ||
            header.bytes_per_sample != static_cast<std::uint16_t>(bytes_per_sample)) {
            AUDIO_LOGW(
                "Ignoring sandbox PCM packet sampleRate=%u channels=%u bytesPerSample=%u format=%s(%u)",
                header.sample_rate,
                header.channel_count,
                header.bytes_per_sample,
                sample_format_name(sample_format),
                sample_format);
            return;
        }
        const std::size_t frame_bytes =
            static_cast<std::size_t>(header.channel_count) * static_cast<std::size_t>(bytes_per_sample);
        const std::size_t available_frames = payload_size / std::max<std::size_t>(1, frame_bytes);
        const std::size_t frames_to_copy = std::min<std::size_t>(available_frames, header.frame_count);
        if (frames_to_copy == 0) {
            return;
        }

        std::lock_guard<std::mutex> lock(mu_);
        if (source_rate_ != static_cast<int>(header.sample_rate) ||
            source_channels_ != static_cast<int>(header.channel_count) ||
            source_format_ != sample_format) {
            ring_head_ = 0;
            queued_frames_ = 0;
            position_ = 0.0;
            source_rate_ = static_cast<int>(header.sample_rate);
            source_channels_ = static_cast<int>(header.channel_count);
            source_format_ = sample_format;
            AUDIO_LOGI(
                "Sandbox PCM source rate=%d channels=%d format=%s(%u)",
                source_rate_,
                source_channels_,
                sample_format_name(source_format_),
                source_format_);
        }

        const std::size_t queue_capacity = std::max<std::size_t>(
            2,
            static_cast<std::size_t>(source_rate_) * kMaxQueuedAudioMs / 1000);
        if (ring_.size() != queue_capacity) {
            ring_.resize(queue_capacity);
            ring_head_ = 0;
            queued_frames_ = 0;
            position_ = 0.0;
        }

        std::uint64_t dropped_now = 0;
        for (std::size_t frame = 0; frame < frames_to_copy; ++frame) {
            std::size_t base = frame * header.channel_count;
            float left = sample_as_float(payload, base, sample_format, bytes_per_sample);
            float right = header.channel_count >= 2
                ? sample_as_float(payload, base + 1, sample_format, bytes_per_sample)
                : left;
            if (queued_frames_ < ring_.size()) {
                const std::size_t tail = (ring_head_ + queued_frames_) % ring_.size();
                ring_[tail] = StereoFrame{left, right};
                ++queued_frames_;
            } else {
                ring_[ring_head_] = StereoFrame{left, right};
                ring_head_ = (ring_head_ + 1) % ring_.size();
                ++dropped_now;
                if (position_ >= 1.0) {
                    position_ -= 1.0;
                }
            }
        }
        total_frames_dropped_ += dropped_now;
        max_queued_frames_ = std::max(max_queued_frames_, queued_frames_);
        if (dropped_now > 0 && total_frames_dropped_ >= next_drop_log_frame_) {
            AUDIO_LOGI(
                "Dropped stale live PCM frames=%" PRIu64 " total=%" PRIu64 " queued=%zu latencyMs=%zu",
                dropped_now,
                total_frames_dropped_,
                queued_frames_,
                queued_frames_ * 1000 / static_cast<std::size_t>(std::max(1, source_rate_)));
            next_drop_log_frame_ = total_frames_dropped_ +
                static_cast<std::uint64_t>(std::max(1, source_rate_)) * 5;
        }
        total_frames_pushed_ += frames_to_copy;
        log_stats_if_due_locked();
        cv_.notify_all();
    }

    int read_int16(int target_rate, int target_channels, int16_t *out, int frame_count, int timeout_ms) {
        if (out == nullptr || target_rate <= 0 || target_channels <= 0 || frame_count <= 0) {
            return -1;
        }
        std::vector<float> scratch(
            static_cast<std::size_t>(frame_count) * static_cast<std::size_t>(target_channels));
        int frames = read_float(target_rate, target_channels, scratch.data(), frame_count, timeout_ms);
        if (frames <= 0) {
            return frames;
        }
        std::size_t samples = static_cast<std::size_t>(frames) * static_cast<std::size_t>(target_channels);
        for (std::size_t i = 0; i < samples; ++i) {
            out[i] = float_to_int16(scratch[i]);
        }
        return frames;
    }

    int read_float(int target_rate, int target_channels, float *out, int frame_count, int timeout_ms) {
        if (out == nullptr || target_rate <= 0 || target_channels <= 0 || frame_count <= 0) {
            return -1;
        }
        std::unique_lock<std::mutex> lock(mu_);
        if (queued_frames_ < 2) {
            cv_.wait_for(lock, std::chrono::milliseconds(std::max(0, timeout_ms)), [&] {
                return queued_frames_ >= 2;
            });
        }
        if (queued_frames_ < 2) {
            ++total_wait_timeouts_;
            return 0;
        }

        int produced = 0;
        const double step = static_cast<double>(std::max(1, source_rate_)) /
            static_cast<double>(target_rate);
        while (produced < frame_count && queued_frames_ >= 2) {
            std::size_t index = static_cast<std::size_t>(position_);
            if (index + 1 >= queued_frames_) {
                break;
            }
            double frac = position_ - static_cast<double>(index);
            StereoFrame a = ring_[(ring_head_ + index) % ring_.size()];
            StereoFrame b = ring_[(ring_head_ + index + 1) % ring_.size()];
            float left = lerp_sample(a.left, b.left, frac);
            float right = lerp_sample(a.right, b.right, frac);

            if (target_channels == 1) {
                out[produced] = std::clamp((left + right) * 0.5f, -1.0f, 1.0f);
            } else {
                std::size_t base = static_cast<std::size_t>(produced) * static_cast<std::size_t>(target_channels);
                out[base] = left;
                out[base + 1] = right;
                for (int channel = 2; channel < target_channels; ++channel) {
                    out[base + static_cast<std::size_t>(channel)] = 0;
                }
            }

            position_ += step;
            std::size_t drop = static_cast<std::size_t>(position_);
            if (drop > 0) {
                drop = std::min(drop, queued_frames_);
                ring_head_ = (ring_head_ + drop) % ring_.size();
                queued_frames_ -= drop;
                position_ -= static_cast<double>(drop);
            }
            ++produced;
        }
        total_frames_read_ += static_cast<std::uint64_t>(produced);
        return produced;
    }

private:
    void log_stats_if_due_locked() {
        const auto now = std::chrono::steady_clock::now();
        if (last_stats_time_ == std::chrono::steady_clock::time_point{}) {
            last_stats_time_ = now;
            last_stats_frames_pushed_ = total_frames_pushed_;
            last_stats_frames_read_ = total_frames_read_;
            last_stats_frames_dropped_ = total_frames_dropped_;
            last_stats_wait_timeouts_ = total_wait_timeouts_;
            return;
        }
        const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            now - last_stats_time_).count();
        if (elapsed_ms < kQueueStatsIntervalMs) {
            return;
        }
        const auto pushed = total_frames_pushed_ - last_stats_frames_pushed_;
        const auto read = total_frames_read_ - last_stats_frames_read_;
        const auto dropped = total_frames_dropped_ - last_stats_frames_dropped_;
        const auto wait_timeouts = total_wait_timeouts_ - last_stats_wait_timeouts_;
        const double seconds = std::max(0.001, static_cast<double>(elapsed_ms) / 1000.0);
        const double drop_percent = pushed > 0
            ? static_cast<double>(dropped) * 100.0 / static_cast<double>(pushed)
            : 0.0;
        AUDIO_LOGI(
            "%s PCM stats inputFps=%.0f outputFps=%.0f dropPct=%.2f waitTimeouts=%" PRIu64
            " queueMs=%zu maxQueueMs=%zu",
            label_,
            static_cast<double>(pushed) / seconds,
            static_cast<double>(read) / seconds,
            drop_percent,
            wait_timeouts,
            queued_frames_ * 1000 / static_cast<std::size_t>(std::max(1, source_rate_)),
            max_queued_frames_ * 1000 / static_cast<std::size_t>(std::max(1, source_rate_)));
        last_stats_time_ = now;
        last_stats_frames_pushed_ = total_frames_pushed_;
        last_stats_frames_read_ = total_frames_read_;
        last_stats_frames_dropped_ = total_frames_dropped_;
        last_stats_wait_timeouts_ = total_wait_timeouts_;
        max_queued_frames_ = queued_frames_;
    }

    const char *label_;
    std::mutex mu_;
    std::condition_variable cv_;
    // Audio is a continuous producer/consumer stream. A deque allocates and
    // frees small blocks on different threads as its ends move, which causes
    // Android's native allocator caches to grow for the lifetime of a stream.
    // Keep one fixed ring so steady-state PCM forwarding performs no allocation.
    std::vector<StereoFrame> ring_;
    std::size_t ring_head_ = 0;
    std::size_t queued_frames_ = 0;
    int source_rate_ = kDefaultSampleRate;
    int source_channels_ = kDefaultChannelCount;
    std::uint32_t source_format_ = kAudioFormatPcm16Bit;
    double position_ = 0.0;
    std::uint64_t total_frames_pushed_ = 0;
    std::uint64_t total_frames_read_ = 0;
    std::uint64_t total_frames_dropped_ = 0;
    std::uint64_t total_wait_timeouts_ = 0;
    std::uint64_t next_drop_log_frame_ = 1;
    std::size_t max_queued_frames_ = 0;
    std::chrono::steady_clock::time_point last_stats_time_ {};
    std::uint64_t last_stats_frames_pushed_ = 0;
    std::uint64_t last_stats_frames_read_ = 0;
    std::uint64_t last_stats_frames_dropped_ = 0;
    std::uint64_t last_stats_wait_timeouts_ = 0;
};

PcmQueue g_sandbox_queue("Sandbox");
PcmQueue g_record_queue("AudioRecord");

std::mutex g_server_mu;
std::thread g_server_thread;
std::atomic_bool g_server_running{false};
int g_server_fd = -1;
std::string g_server_name;

enum class SunshineSourceMode {
    None,
    SandboxSocket,
    AudioRecord,
};

std::mutex g_sunshine_mu;
// Source mode and socket-server lifecycle must change as one operation. Sunshine
// callbacks and projection source switches run on different Java threads.
// Without this lock, an enable can observe the old running server between a
// disable's mode update and stop_server(), leaving SandboxSocket selected with
// no bound socket.
std::mutex g_sunshine_source_transition_mu;
SunshineSourceMode g_sunshine_mode = SunshineSourceMode::None;
JavaVM *g_record_vm = nullptr;
jobject g_record = nullptr;
jmethodID g_record_start = nullptr;
jmethodID g_record_read = nullptr;
jmethodID g_record_stop = nullptr;
bool g_record_started = false;
int g_record_sample_rate = kDefaultSampleRate;
int g_record_channel_count = kDefaultChannelCount;
std::vector<int16_t> g_record_pcm;
jobject g_record_direct_buffer = nullptr;

void close_fd(int *fd) {
    if (*fd >= 0) {
        close(*fd);
        *fd = -1;
    }
}

void configure_socket_buffer(int fd, int option, const char *label) {
    int size = kSocketBufferBytes;
    if (setsockopt(fd, SOL_SOCKET, option, &size, sizeof(size)) != 0) {
        AUDIO_LOGW("Sandbox PCM socket %s buffer configure failed errno=%d", label, errno);
        return;
    }
    socklen_t actual_size_len = sizeof(size);
    if (getsockopt(fd, SOL_SOCKET, option, &size, &actual_size_len) == 0) {
        AUDIO_LOGI("Sandbox PCM socket %s buffer=%d", label, size);
    }
}

JNIEnv *attach_record_env(JavaVM *vm, bool *attached);
void detach_record_env(JavaVM *vm, bool attached);

bool push_packet_bytes(const std::uint8_t *packet, std::size_t bytes) {
    if (packet == nullptr || bytes < kPacketHeaderV1Size) {
        return false;
    }
    PacketHeader header{};
    std::memcpy(&header, packet, std::min<std::size_t>(bytes, sizeof(PacketHeader)));
    if (header.magic != kPacketMagic ||
        header.version < kMinPacketVersion ||
        header.version > kPacketVersion ||
        header.header_size < kPacketHeaderV1Size ||
        header.header_size > bytes) {
        return false;
    }
    if (header.header_size < sizeof(PacketHeader)) {
        header.sample_format = kAudioFormatPcm16Bit;
    }
    g_sandbox_queue.push(header, packet + header.header_size, bytes - header.header_size);
    return true;
}

void receive_loop() {
    std::vector<std::uint8_t> packet(kMaxPacketBytes);
    std::uint64_t packets = 0;
    while (g_server_running.load(std::memory_order_acquire)) {
        int fd = g_server_fd;
        if (fd < 0) {
            break;
        }
        ssize_t bytes = recv(fd, packet.data(), packet.size(), 0);
        if (bytes < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
                continue;
            }
            if (g_server_running.load(std::memory_order_relaxed)) {
                AUDIO_LOGW("Sandbox PCM recv failed errno=%d", errno);
            }
            continue;
        }
        if (!push_packet_bytes(packet.data(), static_cast<std::size_t>(bytes))) continue;
        ++packets;
        if (packets == 1 || packets % 250 == 0) {
            AUDIO_LOGI("Sandbox PCM received packets=%" PRIu64 " bytes=%zd", packets, bytes);
        }
    }
}

JavaVM *record_vm_snapshot() {
    std::lock_guard<std::mutex> lock(g_sunshine_mu);
    return g_record_vm;
}

JNIEnv *attach_record_env(JavaVM *vm, bool *attached) {
    if (attached) {
        *attached = false;
    }
    if (vm == nullptr) {
        return nullptr;
    }
    JNIEnv *env = nullptr;
    jint status = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_OK) {
        return env;
    }
    if (status == JNI_EDETACHED && vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        if (attached) {
            *attached = true;
        }
        return env;
    }
    return nullptr;
}

void detach_record_env(JavaVM *vm, bool attached) {
    if (attached && vm != nullptr) {
        vm->DetachCurrentThread();
    }
}

bool ensure_record_started_locked(JNIEnv *env, int frame_count) {
    if (g_record == nullptr || g_record_read == nullptr) {
        return false;
    }
    if (!g_record_started && g_record_start != nullptr) {
        env->CallVoidMethod(g_record, g_record_start);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return false;
        }
        g_record_started = true;
        AUDIO_LOGI(
            "Sunshine native AudioRecord started sampleRate=%d channels=%d",
            g_record_sample_rate,
            g_record_channel_count);
    }
    std::size_t required_samples =
        static_cast<std::size_t>(std::max(1, frame_count)) *
        static_cast<std::size_t>(std::max(1, g_record_channel_count));
    std::size_t required_bytes = required_samples * sizeof(int16_t);
    if (g_record_pcm.size() * sizeof(int16_t) < required_bytes) {
        if (g_record_direct_buffer != nullptr) {
            env->DeleteGlobalRef(g_record_direct_buffer);
            g_record_direct_buffer = nullptr;
        }
        g_record_pcm.assign(required_samples, 0);
        jobject local = env->NewDirectByteBuffer(g_record_pcm.data(), static_cast<jlong>(required_bytes));
        if (local == nullptr) {
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
            return false;
        }
        g_record_direct_buffer = env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
    }
    return g_record_direct_buffer != nullptr;
}

int read_audio_record_float(int target_rate, int target_channels, float *out, int frame_count) {
    if (out == nullptr || frame_count <= 0 || target_rate <= 0 || target_channels <= 0) {
        return -1;
    }
    JavaVM *vm = record_vm_snapshot();
    bool attached = false;
    JNIEnv *env = attach_record_env(vm, &attached);
    if (env == nullptr) {
        return -1;
    }

    int record_rate = kDefaultSampleRate;
    int record_channels = kDefaultChannelCount;
    int bytes_read = 0;
    bool failed = false;
    {
        std::lock_guard<std::mutex> lock(g_sunshine_mu);
        if (!ensure_record_started_locked(env, frame_count)) {
            failed = true;
        } else {
            record_rate = g_record_sample_rate;
            record_channels = g_record_channel_count;
            int requested_bytes = frame_count * record_channels * static_cast<int>(sizeof(int16_t));
            bytes_read = env->CallIntMethod(g_record, g_record_read, g_record_direct_buffer, requested_bytes, 1);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                failed = true;
            }
        }
        if (!failed && bytes_read > 0) {
            PacketHeader header{};
            header.magic = kPacketMagic;
            header.version = kPacketVersion;
            header.header_size = sizeof(PacketHeader);
            header.sample_rate = static_cast<std::uint32_t>(record_rate);
            header.channel_count = static_cast<std::uint16_t>(record_channels);
            header.bytes_per_sample = kBytesPerSample;
            header.frame_count = static_cast<std::uint32_t>(
                bytes_read / std::max(1, record_channels * static_cast<int>(sizeof(int16_t))));
            header.timestamp_ns = 0;
            header.sample_format = kAudioFormatPcm16Bit;
            g_record_queue.push(
                header,
                reinterpret_cast<const std::uint8_t *>(g_record_pcm.data()),
                static_cast<std::size_t>(bytes_read));
        }
    }
    detach_record_env(vm, attached);
    if (failed) {
        return -1;
    }
    if (bytes_read <= 0) {
        return 0;
    }
    return g_record_queue.read_float(target_rate, target_channels, out, frame_count, kQueueReadTimeoutMs);
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_taowen_arctrl_SandboxAudioPcmSource_nativeStart(
    JNIEnv *,
    jobject
) {
    clear_queue();
    start_server(nullptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_taowen_arctrl_SandboxAudioPcmSource_nativeStop(
    JNIEnv *,
    jobject
) {
    stop_server();
    clear_queue();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_taowen_arctrl_SandboxAudioPcmSource_nativeReadPcm16(
    JNIEnv *env,
    jobject,
    jobject buffer,
    jint sample_rate,
    jint channel_count,
    jint frame_count,
    jint timeout_ms
) {
    if (env == nullptr || buffer == nullptr || sample_rate <= 0 || channel_count <= 0 || frame_count <= 0) {
        return -1;
    }
    auto *pcm = static_cast<int16_t *>(env->GetDirectBufferAddress(buffer));
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    const jlong required = static_cast<jlong>(frame_count) *
        static_cast<jlong>(channel_count) *
        static_cast<jlong>(sizeof(int16_t));
    if (pcm == nullptr || capacity < required) {
        return -1;
    }
    return static_cast<jint>(
        read_int16(
            static_cast<int>(sample_rate),
            static_cast<int>(channel_count),
            pcm,
            static_cast<int>(frame_count),
            static_cast<int>(timeout_ms)));
}

extern "C" JNIEXPORT void JNICALL
Java_com_taowen_arctrl_wireless_privileged_PrivilegedAudioPipe_nativePushPacket(
    JNIEnv *env,
    jobject,
    jobject packet,
    jint size
) {
    if (env == nullptr || packet == nullptr || size <= 0) return;
    auto *bytes = static_cast<std::uint8_t *>(env->GetDirectBufferAddress(packet));
    jlong capacity = env->GetDirectBufferCapacity(packet);
    if (bytes == nullptr || capacity < size ||
        !push_packet_bytes(bytes, static_cast<std::size_t>(size))) {
        AUDIO_LOGW("Ignoring invalid privileged PCM packet bytes=%d", size);
    }
}

void start_server(const char *consumer_name) {
    std::lock_guard<std::mutex> lock(g_server_mu);
    std::string name = socket_name(consumer_name);
    if (g_server_running.load(std::memory_order_acquire)) {
        if (g_server_name != name) {
            AUDIO_LOGW("Sandbox PCM server already running name=%s requested=%s",
                       g_server_name.c_str(),
                       name.c_str());
        }
        return;
    }
    int fd = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        AUDIO_LOGW("Sandbox PCM socket create failed errno=%d", errno);
        return;
    }
    configure_socket_buffer(fd, SO_RCVBUF, "recv");
    timeval timeout{};
    timeout.tv_sec = 0;
    timeout.tv_usec = 200 * 1000;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));

    sockaddr_un addr{};
    socklen_t len = fill_abstract_address(&addr, name);
    if (bind(fd, reinterpret_cast<sockaddr *>(&addr), len) != 0) {
        AUDIO_LOGW("Sandbox PCM socket bind failed errno=%d name=%s", errno, name.c_str());
        close(fd);
        return;
    }
    g_server_fd = fd;
    g_server_name = name;
    g_server_running.store(true, std::memory_order_release);
    g_server_thread = std::thread(receive_loop);
    AUDIO_LOGI("Sandbox PCM server started name=%s", name.c_str());
}

void stop_server() {
    std::thread thread;
    {
        std::lock_guard<std::mutex> lock(g_server_mu);
        if (!g_server_running.exchange(false, std::memory_order_acq_rel)) {
            return;
        }
        close_fd(&g_server_fd);
        g_server_name.clear();
        thread = std::move(g_server_thread);
    }
    if (thread.joinable()) {
        thread.join();
    }
    AUDIO_LOGI("Sandbox PCM server stopped");
}

void clear_queue() {
    g_sandbox_queue.clear();
    g_record_queue.clear();
}

int read_int16(int sample_rate, int channel_count, int16_t *out, int frame_count, int timeout_ms) {
    return g_sandbox_queue.read_int16(sample_rate, channel_count, out, frame_count, timeout_ms);
}

int read_float(int sample_rate, int channel_count, float *out, int frame_count, int timeout_ms) {
    return g_sandbox_queue.read_float(sample_rate, channel_count, out, frame_count, timeout_ms);
}

void set_sunshine_sandbox_source_enabled(JNIEnv *env, bool enabled) {
    std::lock_guard<std::mutex> transition_lock(g_sunshine_source_transition_mu);
    bool was_sandbox_socket = false;
    if (enabled) {
        clear_sunshine_audio_record(env);
    }
    {
        std::lock_guard<std::mutex> lock(g_sunshine_mu);
        was_sandbox_socket = g_sunshine_mode == SunshineSourceMode::SandboxSocket;
        if (enabled) {
            g_sunshine_mode = SunshineSourceMode::SandboxSocket;
        } else if (was_sandbox_socket) {
            g_sunshine_mode = SunshineSourceMode::None;
        }
    }
    if (enabled) {
        clear_queue();
        start_server(nullptr);
    } else if (was_sandbox_socket) {
        g_sandbox_queue.clear();
        stop_server();
    }
}

bool has_sunshine_native_source() {
    std::lock_guard<std::mutex> lock(g_sunshine_mu);
    return g_sunshine_mode != SunshineSourceMode::None;
}

int read_sunshine_float(int sample_rate, int channel_count, float *out, int frame_count) {
    SunshineSourceMode mode;
    {
        std::lock_guard<std::mutex> lock(g_sunshine_mu);
        mode = g_sunshine_mode;
    }
    if (mode == SunshineSourceMode::SandboxSocket) {
        return read_float(sample_rate, channel_count, out, frame_count, kQueueReadTimeoutMs);
    }
    if (mode == SunshineSourceMode::AudioRecord) {
        return read_audio_record_float(sample_rate, channel_count, out, frame_count);
    }
    return -1;
}

void drop_sunshine_audio_backlog() {
    SunshineSourceMode mode;
    {
        std::lock_guard<std::mutex> lock(g_sunshine_mu);
        mode = g_sunshine_mode;
    }
    if (mode == SunshineSourceMode::SandboxSocket) {
        g_sandbox_queue.clear();
    } else if (mode == SunshineSourceMode::AudioRecord) {
        g_record_queue.clear();
    }
    AUDIO_LOGI("Dropped Sunshine audio backlog before starting encoder mode=%d", static_cast<int>(mode));
}

void set_sunshine_audio_record(JNIEnv *env, jobject audio_record, int sample_rate, int channel_count) {
    if (env == nullptr || audio_record == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> transition_lock(g_sunshine_source_transition_mu);
    bool was_sandbox_socket = false;
    {
        std::lock_guard<std::mutex> lock(g_sunshine_mu);
        was_sandbox_socket = g_sunshine_mode == SunshineSourceMode::SandboxSocket;
        if (was_sandbox_socket) {
            g_sunshine_mode = SunshineSourceMode::None;
        }
    }
    if (was_sandbox_socket) {
        g_sandbox_queue.clear();
        stop_server();
    }
    clear_sunshine_audio_record(env);
    std::lock_guard<std::mutex> lock(g_sunshine_mu);
    env->GetJavaVM(&g_record_vm);
    g_record = env->NewGlobalRef(audio_record);
    jclass cls = env->GetObjectClass(audio_record);
    g_record_start = cls != nullptr ? env->GetMethodID(cls, "startRecording", "()V") : nullptr;
    g_record_read = cls != nullptr ? env->GetMethodID(cls, "read", "(Ljava/nio/ByteBuffer;II)I") : nullptr;
    g_record_stop = cls != nullptr ? env->GetMethodID(cls, "stop", "()V") : nullptr;
    if (cls != nullptr) {
        env->DeleteLocalRef(cls);
    }
    g_record_sample_rate = std::max(1, sample_rate);
    g_record_channel_count = std::max(1, channel_count);
    g_record_started = false;
    g_record_queue.clear();
    g_sunshine_mode = SunshineSourceMode::AudioRecord;
    AUDIO_LOGI(
        "Sunshine native AudioRecord source set sampleRate=%d channels=%d",
        g_record_sample_rate,
        g_record_channel_count);
}

void clear_sunshine_audio_record(JNIEnv *env) {
    std::lock_guard<std::mutex> lock(g_sunshine_mu);
    if (env != nullptr && g_record != nullptr && g_record_started && g_record_stop != nullptr) {
        env->CallVoidMethod(g_record, g_record_stop);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
    g_record_started = false;
    if (env != nullptr && g_record_direct_buffer != nullptr) {
        env->DeleteGlobalRef(g_record_direct_buffer);
    }
    g_record_direct_buffer = nullptr;
    if (env != nullptr && g_record != nullptr) {
        env->DeleteGlobalRef(g_record);
    }
    g_record = nullptr;
    g_record_vm = nullptr;
    g_record_start = nullptr;
    g_record_read = nullptr;
    g_record_stop = nullptr;
    g_record_pcm.clear();
    g_record_queue.clear();
    if (g_sunshine_mode == SunshineSourceMode::AudioRecord) {
        g_sunshine_mode = SunshineSourceMode::None;
    }
}

}  // namespace arctrl::sandbox_audio

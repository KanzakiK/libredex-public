#pragma once

#include <cstdint>

#include <jni.h>

namespace arctrl::sandbox_audio {

constexpr int kDefaultSampleRate = 48000;
constexpr int kDefaultChannelCount = 2;
constexpr int kBytesPerSample = 2;

void start_server(const char *consumer_name);
void stop_server();
void clear_queue();

int read_int16(int sample_rate, int channel_count, int16_t *out, int frame_count, int timeout_ms);
int read_float(int sample_rate, int channel_count, float *out, int frame_count, int timeout_ms);

void set_sunshine_sandbox_source_enabled(JNIEnv *env, bool enabled);
bool has_sunshine_native_source();
int read_sunshine_float(int sample_rate, int channel_count, float *out, int frame_count);
void drop_sunshine_audio_backlog();

void set_sunshine_audio_record(JNIEnv *env, jobject audio_record, int sample_rate, int channel_count);
void clear_sunshine_audio_record(JNIEnv *env);

}  // namespace arctrl::sandbox_audio

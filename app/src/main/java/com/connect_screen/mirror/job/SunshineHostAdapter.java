package com.connect_screen.mirror.job;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Build;

import com.connect_screen.mirror.Pref;
import com.connect_screen.mirror.State;

import dev.lizardbyte.sunshine.android.SunshineHost;
import dev.lizardbyte.sunshine.android.SunshineHostConfig;
import dev.lizardbyte.sunshine.android.SunshineHostListener;
import dev.lizardbyte.sunshine.android.SunshineLogLevel;
import dev.lizardbyte.sunshine.android.SunshineSessionState;
import dev.lizardbyte.sunshine.android.SunshineStats;

/**
 * 官方 sunshine-android SDK 对接适配层（m3：生命周期/配置/PIN/回调转发）。
 *
 * 目标：用官方 SunshineHost（libsunshine_android.so）逐步取代私有 libsunshine.so 路径。
 * 本类只覆盖生命周期与配置；视频源（m4）/输入（m5）/音频（m6）后续接入。
 * 现有私有 SunshineServer 路径保持不动，两侧共存，验证通过后再切换。
 */
public final class SunshineHostAdapter {

    private final Object lock = new Object();
    private SunshineHost host;
    private DexMirrorVideoSource videoSource;
    private AudioRecord playbackAudioRecord;

    public void start(Context context) {
        if (context == null) {
            return;
        }
        synchronized (lock) {
            if (host != null) {
                State.log("[SunshineHostAdapter] already started");
                return;
            }
            try {
                // Use a dedicated subdirectory so official Sunshine state/cert/key files
                // are completely isolated from the private libsunshine.so path.
                // Both engines use identically-named files (sunshine_state.json,
                // sunshine_cert.pem, sunshine_key.pem) — sharing a directory causes
                // parse failures and write races when switching between them.
                java.io.File officialDir = new java.io.File(context.getFilesDir(), "official_sunshine");
                if (!officialDir.exists()) {
                    officialDir.mkdirs();
                }
                SunshineHostConfig config = new SunshineHostConfig.Builder(officialDir)
                        .setHostName("LibreDeX")
                        .setPort(47989)
                        .setEnableHevc(Pref.getEncoderCodec() == Pref.ENCODER_CODEC_H265)
                        .setEnableAudio(true)
                        .build();
                SunshineHost newHost = new SunshineHost(config);
                DexMirrorVideoSource newVideoSource = new DexMirrorVideoSource();
                newHost.setVideoSource(newVideoSource);
                newHost.setInputSink(new SunshineInputSinkAdapter());
                newHost.setListener(new SunshineHostListener() {
                    @Override
                    public void onPinRequested() {
                        // 复用现有 PIN 弹窗逻辑（内部已切主线程）
                        SunshineServer.onPinRequested();
                    }

                    @Override
                    public void onSessionStateChanged(SunshineSessionState state) {
                        State.log("[SunshineHostAdapter] session state: " + state);
                    }

                    @Override
                    public void onClientConnected(String name, String address) {
                        State.log("[SunshineHostAdapter] client connected: " + name + " @" + address);
                    }

                    @Override
                    public void onClientDisconnected(String name) {
                        State.log("[SunshineHostAdapter] client disconnected: " + name);
                    }

                    @Override
                    public void onStats(SunshineStats stats) {
                        // 简化：转发基本统计到串流调试信息
                        State.streamingDebugInfo.setValue(
                                "video=" + stats.videoFrames + "f key=" + stats.videoKeyFrames
                                        + " bytes=" + stats.videoBytes
                                        + " audio=" + stats.audioFrames + "f/" + stats.audioSamples + "s");
                    }

                    @Override
                    public void onLog(SunshineLogLevel level, String message) {
                        State.log("[SunshineHostAdapter] " + level + " " + message);
                    }

                    @Override
                    public void onEncoderError(String message) {
                        // 复用现有编码错误提示
                        SunshineServer.showEncoderError(message);
                    }
                });
                newHost.start();
                host = newHost;
                videoSource = newVideoSource;
                applyEncoderSettings(newHost);
                attachPlaybackAudioRecord(context);
                State.log("[SunshineHostAdapter] started");
            } catch (Throwable t) {
                State.log("[SunshineHostAdapter] start failed: " + t.getMessage());
                host = null;
            }
        }
    }

    public void stop() {
        synchronized (lock) {
            SunshineHost current = host;
            host = null;
            DexMirrorVideoSource currentVideoSource = videoSource;
            videoSource = null;
            AudioRecord currentAudioRecord = playbackAudioRecord;
            playbackAudioRecord = null;
            if (current != null) {
                try {
                    current.stop();
                    current.clearNativeAudioSource();
                    current.close();
                } catch (Throwable t) {
                    State.log("[SunshineHostAdapter] stop failed: " + t.getMessage());
                }
                State.log("[SunshineHostAdapter] stopped");
            }
            if (currentAudioRecord != null) {
                try {
                    currentAudioRecord.release();
                } catch (Throwable ignored) {
                }
            }
            if (currentVideoSource != null) {
                currentVideoSource.close();
            }
        }
    }

    /**
     * LibreDeX 编码设置界面 → 官方编码器（m7）：从 Pref 读参数注入。
     */
    private void applyEncoderSettings(SunshineHost sunshineHost) {
        try {
            sunshineHost.setLibreDeXEncoderSettings(
                    Pref.getEncoderBitratePercent(),
                    Pref.getEncoderBitrateMode(),
                    Pref.getEncoderComplexity(),
                    Pref.getEncoderIFrameInterval(),
                    Pref.getEncoderMaxFps(),
                    Pref.getEncoderLowLatency(),
                    Pref.getEncoderDisableBFrames(),
                    Pref.getEncoderRealtimePriority());
        } catch (Throwable t) {
            State.log("[SunshineHostAdapter] applyEncoderSettings failed: " + t.getMessage());
        }
    }

    /**
     * 官方 native 直接读取 AudioRecord（m6）：替代私有路径的跨 Binder readAudio。
     * 使用 MediaProjection AudioPlaybackCapture（普通权限可建，免 UserService 特权）。
     */
    private void attachPlaybackAudioRecord(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            State.log("[SunshineHostAdapter] RECORD_AUDIO not granted, skip audio");
            return;
        }
        MediaProjection mediaProjection = State.getMediaProjection();
        if (mediaProjection == null) {
            State.log("[SunshineHostAdapter] no MediaProjection, skip audio");
            return;
        }
        try {
            int sampleRate = 48000;
            int channelCount = 2;
            int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding) * 2;
            AudioFormat audioFormat = new AudioFormat.Builder()
                    .setEncoding(audioEncoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build();
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration
                    .Builder(mediaProjection)
                    .excludeUsage(AudioAttributes.USAGE_ALARM)
                    .build();
            AudioRecord record = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .build();
            record.startRecording();
            host.setPlaybackAudioRecord(record, sampleRate, channelCount);
            playbackAudioRecord = record;
            State.log("[SunshineHostAdapter] official playback audio attached " + sampleRate + "Hz/" + channelCount + "ch");
        } catch (Throwable t) {
            State.log("[SunshineHostAdapter] audio attach failed: " + t.getMessage());
        }
    }

    public void stopCurrentSession() {
        synchronized (lock) {
            if (host != null) {
                host.stopCurrentSession();
            }
        }
    }

    public boolean isRunning() {
        synchronized (lock) {
            return host != null && host.isRunning();
        }
    }

    public boolean submitPin(String pin) {
        synchronized (lock) {
            if (host == null) {
                return false;
            }
            return host.submitPin(pin);
        }
    }

    public SunshineHost host() {
        synchronized (lock) {
            return host;
        }
    }
}

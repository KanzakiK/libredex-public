package com.connect_screen.mirror.job;

import android.content.Context;

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
                SunshineHostConfig config = new SunshineHostConfig.Builder(context.getFilesDir())
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
            if (current != null) {
                try {
                    current.stop();
                    current.close();
                } catch (Throwable t) {
                    State.log("[SunshineHostAdapter] stop failed: " + t.getMessage());
                }
                State.log("[SunshineHostAdapter] stopped");
            }
            if (currentVideoSource != null) {
                currentVideoSource.close();
            }
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

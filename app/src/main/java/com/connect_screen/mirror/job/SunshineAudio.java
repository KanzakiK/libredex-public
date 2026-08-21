package com.connect_screen.mirror.job;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;

import com.connect_screen.mirror.State;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

public class SunshineAudio {
    private static final android.os.Handler mainHandler = new android.os.Handler(Looper.getMainLooper());
    private static boolean userServiceWaitScheduled;
    private static int userServiceWaitAttempts;
    private static boolean isMuted = false;
    private static AudioManager.OnAudioFocusChangeListener volumeChangeListener;
    private static final java.util.concurrent.atomic.AtomicBoolean remoteSubmixActive =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile Thread remoteSubmixThread;
    private static volatile boolean remoteSubmixStopRequested;
    private static int savedMusicVolume = -1;
    private static boolean wasMusicMuted;
    public static void startClientAudioCapture(Context context, int packetDuration, boolean shouldMutePhone) {
        if (userServiceWaitScheduled) {
            return;
        }
        android.util.Log.i("SunshineAudio", "startClientAudioCapture pkt=" + packetDuration
                + " mute=" + shouldMutePhone + " userSvc=" + State.isUserServiceAlive());
        boolean started = false;
        if (State.isUserServiceAlive()) {
            started = startRemoteSubmixAudioCapture(packetDuration);
            if (started) {
                userServiceWaitAttempts = 0;
            }
        }
        if (!started && ShizukuUtils.hasPermission()) {
            userServiceWaitAttempts++;
            if (userServiceWaitAttempts <= 20) {
                userServiceWaitScheduled = true;
                State.log("Waiting for UserService bind, will retry audio capture (" + userServiceWaitAttempts + "/20)");
                State.ensureUserServiceBound();
                mainHandler.postDelayed(() -> {
                    userServiceWaitScheduled = false;
                    if (SunshineServer.isMoonlightSessionActive()) {
                        startClientAudioCapture(context, packetDuration, shouldMutePhone);
                    }
                }, 150);
                return;
            }
            userServiceWaitAttempts = 0;
        }
        if (!started && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            started = startAudioUseNormalPermission(context, packetDuration);
        }
        if (!started && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            started = startRemoteSubmixAudioCapture(packetDuration);
        }

        if (!started) {
            State.log("Moonlight audio capture not started, continuing video stream");
            android.util.Log.i("SunshineAudio", "audio capture not started");
            return;
        }
        if (remoteSubmixActive.get()) {
            ensureRemoteSubmixVolume(context);
            State.log("8.1 audio routed to remote_submix, phone speaker silent");
        } else if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            ensureRemoteSubmixVolume(context);
            State.log("8.1 audio routed to remote_submix, phone speaker silent");
        } else if (shouldMutePhone) {
            mutePhoneSpeaker(context);
        } else {
            State.log("Client asked to keep phone-side playback, phone speaker not muted");
        }
    }

    private static void mutePhoneSpeaker(Context context) {
        if (context.checkSelfPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            State.log("No audio control permission, cannot mute");
        }
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
        if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
            isMuted = true;
            State.log("Muting phone per client request");
            // 注册音量变化监听器
            registerVolumeChangeListener(context, audioManager);
        } else {
            State.log("Mute setting not applied");
        }
    }

    // 添加注册音量变化监听器的方法
    private static void registerVolumeChangeListener(Context context, AudioManager audioManager) {

        // 创建音频焦点变化监听器
        volumeChangeListener = focusChange -> {
            // 如果还在投屏且应该保持静音状态，检查并重新设置静音
            if (isMuted) {
                checkAndRestoreMute();
            }
        };

        // 请求音频焦点以便接收音频变化事件
        audioManager.requestAudioFocus(volumeChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);

        // 创建内容观察者监听音量变化
        context.getContentResolver().registerContentObserver(
                android.provider.Settings.System.CONTENT_URI,
                true,
                new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        super.onChange(selfChange);
                        // 如果还在投屏且应该保持静音状态，检查并重新设置静音
                        if (isMuted) {
                            checkAndRestoreMute();
                        }
                    }
                }
        );
    }

    // 检查并恢复静音状态
    private static void checkAndRestoreMute() {
        Context context = State.getContext();
        if (context == null) {
            return;
        }
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (!audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
            State.log("Volume change detected, re-applying mute");
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
        }
    }

    private static boolean startAudioUseNormalPermission(Context context, int packetDuration) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            State.log("Android version too low to record audio");
            return false;
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            android.util.Log.i("SunshineAudio", "normal permission path, mediaProjection="
                    + (State.getMediaProjection() != null));
            // 配置音频捕获参数
            int sampleRate = 48000; // 与您的Opus配置匹配
            int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            int audioEncoding = AudioFormat.ENCODING_PCM_FLOAT;
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding) * 2;

            // 计算每个数据包的帧数 (每个通道的样本数)
            // packetDuration 是毫秒，所以需要除以1000转换为秒
            int framesPerPacket = (int) (sampleRate * packetDuration / 1000.0f);
            AudioFormat audioFormat = new AudioFormat.Builder()
                    .setEncoding(audioEncoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build();
            MediaProjection mediaProjection = State.getMediaProjection();
            if (mediaProjection == null) {
                State.log("No MediaProjection available, skipping system audio capture");
                android.util.Log.i("SunshineAudio", "no MediaProjection, skip capture");
                return false;
            }
            AudioRecord audioRecord;
            try {
                AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .excludeUsage(AudioAttributes.USAGE_ALARM)
                        .build();
                audioRecord = new AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(config)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(bufferSize)
                        .build();
                audioRecord.startRecording();
            } catch (Throwable e) {
                State.log("System audio capture failed, continuing video projection: " + e.getMessage());
                return false;
            }

            // 将 AudioRecord 传递给 SunshineServer 进行处理
            SunshineServer.startAudioRecording(audioRecord, framesPerPacket);
            State.log("Android native system audio capture started");
            return true;

        } else {
            State.log("Recording permission not granted, skipping audio capture and continuing video stream");
            android.util.Log.i("SunshineAudio", "RECORD_AUDIO not granted");
            return false;
        }
    }

    private static boolean startRemoteSubmixAudioCapture(int packetDuration) {
        android.util.Log.i("SunshineAudio", "remote submix path, userSvc=" + State.isUserServiceAlive());
        if (!State.isUserServiceAlive()) {
            State.log("8.1 REMOTE_SUBMIX audio requires UserService");
            return false;
        }
        try {
            if (!State.userService.forceTntAudioRoute(true)) {
                State.log("8.1 forcing remote_submix route failed, continuing with recording");
                android.util.Log.i("SunshineAudio", "forceTntAudioRoute failed");
            }
            if (!State.userService.startRecordingAudio()) {
                State.log("8.1 REMOTE_SUBMIX AudioRecord failed to start");
                android.util.Log.i("SunshineAudio", "startRecordingAudio failed");
                return false;
            }
        } catch (Throwable e) {
            State.log("8.1 REMOTE_SUBMIX audio start exception: " + e.getClass().getSimpleName() + " " + e.getMessage());
            android.util.Log.i("SunshineAudio", "remote submix exception: " + e);
            stopRemoteSubmixAudio();
            return false;
        }

        remoteSubmixStopRequested = false;
        if (!remoteSubmixActive.compareAndSet(false, true)) {
            return true;
        }
        int framesPerPacket = Math.max(1, (int) (48000 * packetDuration / 1000.0f));
        float[] buffer = new float[framesPerPacket * 2];
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            while (remoteSubmixActive.get() && !remoteSubmixStopRequested) {
                try {
                    int n = State.userService.readAudio(buffer);
                    if (n > 0) {
                        SunshineServer.pushAudioSamples(buffer, n);
                    } else if (n < 0) {
                        State.log("8.1 REMOTE_SUBMIX readAudio returned " + n);
                        break;
                    }
                } catch (Throwable e) {
                    State.log("8.1 REMOTE_SUBMIX readAudio exception: " + e.getClass().getSimpleName() + " " + e.getMessage());
                    break;
                }
            }
            remoteSubmixActive.set(false);
            State.log("8.1 REMOTE_SUBMIX reader thread ended");
        }, "moonlight-audio-submix");
        thread.setDaemon(true);
        remoteSubmixThread = thread;
        thread.start();
        State.log("8.1 REMOTE_SUBMIX audio capture started");
        return true;
    }

    public static void stopRemoteSubmixAudio() {
        boolean wasActive = remoteSubmixActive.get() || remoteSubmixThread != null;
        remoteSubmixStopRequested = true;
        try {
            if (State.userService != null) {
                State.userService.forceTntAudioRoute(false);
                State.userService.stopRecordingAudio();
            }
        } catch (Throwable e) {
            State.log("8.1 REMOTE_SUBMIX stop exception: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        Thread thread = remoteSubmixThread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        remoteSubmixThread = null;
        remoteSubmixActive.set(false);
        if (wasActive) {
            State.log("8.1 REMOTE_SUBMIX audio stopped");
        }
    }

    private static void ensureRemoteSubmixVolume(Context context) {
        if (context == null) {
            return;
        }
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        wasMusicMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC);
        if (savedMusicVolume <= 0 || wasMusicMuted) {
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int target = Math.max(1, (int) (maxVolume * 0.6f));
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            State.log("8.1 remote_submix volume from " + savedMusicVolume + " adjusted to " + target);
        } else {
            State.log("8.1 remote_submix volume stays " + savedMusicVolume);
        }
    }

    public static void restoreVolume(Context context) {
        stopRemoteSubmixAudio();
        if (savedMusicVolume >= 0 && context != null) {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0);
                if (wasMusicMuted) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
                }
                State.log("Restoring media volume to " + savedMusicVolume + " muted=" + wasMusicMuted);
            }
            savedMusicVolume = -1;
            wasMusicMuted = false;
        }
        if (isMuted && context != null) {
            State.log("Restoring volume");
            isMuted = false;
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);

            // 取消注册音量变化监听器
            if (volumeChangeListener != null) {
                audioManager.abandonAudioFocus(volumeChangeListener);
                volumeChangeListener = null;
            }

        }
    }

    public static void mutePhoneForCast(Context context) {
        if (context == null) {
            return;
        }
        mutePhoneSpeaker(context);
    }

    public static void unmutePhoneForCast(Context context) {
        if (!isMuted || context == null) {
            return;
        }
        isMuted = false;
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            if (volumeChangeListener != null) {
                audioManager.abandonAudioFocus(volumeChangeListener);
                volumeChangeListener = null;
            }
            State.log("Restoring phone volume after projection stops");
        }
    }
}

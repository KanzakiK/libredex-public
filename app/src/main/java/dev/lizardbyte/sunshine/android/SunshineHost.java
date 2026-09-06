package dev.lizardbyte.sunshine.android;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.media.AudioRecord;
import android.view.Surface;
import android.view.View;
import android.view.Window;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SunshineHost implements AutoCloseable {
    static {
        System.loadLibrary("sunshine_android");
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SunshineHostConfig config;
    private final NativeCallbacks nativeCallbacks = new NativeCallbacks();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final File stateFile;
    private final File certificateFile;
    private final File privateKeyFile;
    private final File logFile;

    private volatile SunshineHostListener listener = new SunshineHostListener() {};
    private volatile SunshineVideoSource videoSource;
    private volatile SunshineAudioSource audioSource;
    private volatile SunshineAudioSource activeAudioSource;
    private volatile SunshineInputSink inputSink;
    private volatile SunshineSessionState state = SunshineSessionState.STOPPED;
    private volatile String clientName;
    private volatile String clientAddress;
    private volatile long startUptimeMs;
    private volatile long captureEpoch;
    private volatile long stopRequestedEpoch = -1L;
    private SunshineVideoSource activeVideoSource;
    private Surface activeVideoSurface;
    private int activeVideoWidth;
    private int activeVideoHeight;
    private int activeVideoFrameRate;
    private Thread nativeThread;

    public SunshineHost(SunshineHostConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;
        stateFile = new File(config.filesDir, "sunshine_state.json");
        certificateFile = new File(config.filesDir, "sunshine_cert.pem");
        privateKeyFile = new File(config.filesDir, "sunshine_key.pem");
        logFile = new File(config.filesDir, "sunshine.log");
    }

    public void setListener(SunshineHostListener listener) {
        this.listener = listener != null ? listener : new SunshineHostListener() {};
    }

    public void attach(Window window, View targetView) {
        if (window == null) {
            throw new IllegalArgumentException("window is required");
        }
        setVideoSource(new SunshineWindowCapture(window));
        attachInput(targetView);
    }

    public void setVideoSource(SunshineVideoSource videoSource) {
        if (videoSource == null) {
            throw new IllegalArgumentException("videoSource is required");
        }
        this.videoSource = videoSource;
    }

    public void switchVideoSource(SunshineVideoSource videoSource) {
        setVideoSource(videoSource);
        if (Looper.myLooper() == mainHandler.getLooper()) {
            switchVideoSourceOnMain(videoSource);
        } else {
            mainHandler.post(() -> {
                try {
                    switchVideoSourceOnMain(videoSource);
                } catch (RuntimeException e) {
                    emitEncoderError("Unable to switch Android video source: " + e.getMessage());
                }
            });
        }
    }

    public void attachInput(View targetView) {
        if (targetView == null) {
            throw new IllegalArgumentException("targetView is required");
        }
        setInputSink(new SunshineViewInputSink(targetView));
    }

    public void setInputSink(SunshineInputSink inputSink) {
        this.inputSink = inputSink;
    }

    public void setAudioSource(SunshineAudioSource audioSource) {
        this.audioSource = audioSource;
    }

    public void setSandboxAudioSourceEnabled(boolean enabled) {
        if (enabled) {
            setAudioSource(null);
        }
        nativeSetSandboxAudioSourceEnabled(enabled);
    }

    public void setPlaybackAudioRecord(AudioRecord audioRecord, int sampleRate, int channelCount) {
        if (audioRecord == null) {
            throw new IllegalArgumentException("audioRecord is required");
        }
        setAudioSource(null);
        nativeSetPlaybackAudioRecord(audioRecord, sampleRate, channelCount);
    }

    public void clearNativeAudioSource() {
        nativeClearNativeAudioSource();
    }

    public SunshineSessionState getSessionState() {
        return state;
    }

    public boolean isRunning() {
        return running.get() && state != SunshineSessionState.STOPPED && state != SunshineSessionState.STOPPING;
    }

    public SunshineStats getStats() {
        return buildStats(nativeGetStats());
    }

    public File getStateFile() {
        return stateFile;
    }

    public File getCertificateFile() {
        return certificateFile;
    }

    public File getPrivateKeyFile() {
        return privateKeyFile;
    }

    public File getLogFile() {
        return logFile;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        clientName = null;
        clientAddress = null;
        startUptimeMs = SystemClock.uptimeMillis();
        setState(SunshineSessionState.STARTING);

        try {
            nativePrepareFreshStart();
            nativeInit(
                    nativeCallbacks,
                    config.filesDir.getAbsolutePath(),
                    stateFile.getAbsolutePath(),
                    certificateFile.getAbsolutePath(),
                    privateKeyFile.getAbsolutePath(),
                    config.hostName,
                    config.port,
                    config.enableHevc,
                    config.enableAudio
            );
        } catch (RuntimeException e) {
            running.set(false);
            startUptimeMs = 0;
            setState(SunshineSessionState.STOPPED);
            throw e;
        }

        nativeThread = new Thread(() -> {
            try {
                setState(SunshineSessionState.RUNNING);
                nativeStart();
            } finally {
                stopCapture();
                running.set(false);
                clientName = null;
                clientAddress = null;
                startUptimeMs = 0;
                setState(SunshineSessionState.STOPPED);
                dispatchStats(getStats());
            }
        }, "sunshine-android-native");
        nativeThread.start();
    }

    public boolean submitPin(String pin) {
        return nativeSubmitPin(pin);
    }

    public boolean registerPendingAutoPair(String requestId, String sinkUniqueId, String pin, long expiresAtUnixSeconds) {
        if (!running.get()) {
            return false;
        }
        return nativeRegisterPendingAutoPair(requestId, sinkUniqueId, pin, expiresAtUnixSeconds);
    }

    public void stopCurrentSession() {
        if (!running.get()) {
            return;
        }
        nativeStopCurrentSession();
        requestStopCapture();
    }

    public void stop() {
        if (running.get()) {
            setState(SunshineSessionState.STOPPING);
        }
        try {
            nativeStop();
        } catch (RuntimeException ignored) {
        }
        requestStopCapture();
        joinNativeThread();
    }

    private void joinNativeThread() {
        Thread thread = nativeThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        nativeThread = null;
    }

    @Override
    public void close() {
        stop();
        nativeRelease();
    }

    private void startCapture(Surface surface, int width, int height, int frameRate) {
        mainHandler.post(() -> {
            SunshineVideoSource source = videoSource;
            if (source == null) {
                releaseSurface(surface);
                emitEncoderError("No Android video source is attached");
                return;
            }
            startVideoSourceOnMain(source, surface, width, height, frameRate);
        });
    }

    private void stopCapture() {
        if (Looper.myLooper() == mainHandler.getLooper()) {
            stopCaptureOnMain();
        } else {
            mainHandler.post(this::stopCaptureOnMain);
        }
    }

    private void requestStopCapture() {
        stopRequestedEpoch = captureEpoch;
        if (Looper.myLooper() == mainHandler.getLooper()) {
            stopCaptureOnMain();
        } else {
            mainHandler.post(this::stopCaptureOnMain);
        }
    }

    private void startVideoSourceOnMain(SunshineVideoSource source, Surface surface, int width, int height, int frameRate) {
        captureEpoch++;
        Surface previousSurface = activeVideoSurface;
        stopActiveVideoSourceOnMain();
        if (previousSurface != null && previousSurface != surface) {
            releaseSurface(previousSurface);
        }
        activeVideoSurface = surface;
        activeVideoWidth = width;
        activeVideoHeight = height;
        activeVideoFrameRate = frameRate;
        try {
            source.start(surface, width, height, frameRate);
            activeVideoSource = source;
        } catch (RuntimeException e) {
            releaseSurface(activeVideoSurface);
            clearActiveVideoStateOnMain();
            emitEncoderError("Unable to start Android video source: " + e.getMessage());
        }
    }

    private void switchVideoSourceOnMain(SunshineVideoSource nextSource) {
        Surface surface = activeVideoSurface;
        if (surface == null || !surface.isValid()) {
            return;
        }
        SunshineVideoSource previousSource = activeVideoSource;
        if (previousSource == nextSource) {
            return;
        }
        if (previousSource != null) {
            try {
                previousSource.stop();
            } catch (RuntimeException ignored) {
            }
        }
        try {
            nextSource.start(surface, activeVideoWidth, activeVideoHeight, activeVideoFrameRate);
            activeVideoSource = nextSource;
        } catch (RuntimeException e) {
            if (previousSource != null) {
                try {
                    previousSource.start(surface, activeVideoWidth, activeVideoHeight, activeVideoFrameRate);
                    activeVideoSource = previousSource;
                    videoSource = previousSource;
                } catch (RuntimeException ignored) {
                    activeVideoSource = null;
                }
            }
            throw e;
        }
    }

    private void stopCaptureOnMain() {
        stopActiveVideoSourceOnMain();
        releaseSurface(activeVideoSurface);
        clearActiveVideoStateOnMain();
    }

    private void handleNativeVideoStopped() {
        mainHandler.post(this::handleNativeVideoStoppedOnMain);
    }

    private void handleNativeVideoStoppedOnMain() {
        long pendingStopEpoch = stopRequestedEpoch;
        if (pendingStopEpoch >= 0L) {
            stopRequestedEpoch = -1L;
            if (pendingStopEpoch != captureEpoch) {
                return;
            }
        }
        stopCaptureOnMain();
    }

    private void stopActiveVideoSourceOnMain() {
        SunshineVideoSource source = activeVideoSource;
        activeVideoSource = null;
        if (source != null) {
            try {
                source.stop();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void clearActiveVideoStateOnMain() {
        activeVideoSource = null;
        activeVideoSurface = null;
        activeVideoWidth = 0;
        activeVideoHeight = 0;
        activeVideoFrameRate = 0;
    }

    private void releaseSurface(Surface surface) {
        if (surface == null) {
            return;
        }
        try {
            surface.release();
        } catch (RuntimeException ignored) {
        }
    }

    private void setState(SunshineSessionState nextState) {
        if (nextState == null || state == nextState) {
            return;
        }
        state = nextState;
        mainHandler.post(() -> listener.onSessionStateChanged(nextState));
    }

    private SunshineStats buildStats(long[] nativeStats) {
        long[] values = nativeStats != null && nativeStats.length >= 5 ? nativeStats : new long[5];
        long uptimeMs = startUptimeMs == 0 ? 0 : Math.max(0, SystemClock.uptimeMillis() - startUptimeMs);
        return new SunshineStats(
                state,
                clientName,
                uptimeMs,
                values[0],
                values[1],
                values[2],
                values[3],
                values[4]
        );
    }

    private SunshineStats buildStats(long videoFrames, long videoKeyFrames, long videoBytes, long audioFrames, long audioSamples) {
        long uptimeMs = startUptimeMs == 0 ? 0 : Math.max(0, SystemClock.uptimeMillis() - startUptimeMs);
        return new SunshineStats(
                state,
                clientName,
                uptimeMs,
                videoFrames,
                videoKeyFrames,
                videoBytes,
                audioFrames,
                audioSamples
        );
    }

    private void dispatchStats(SunshineStats stats) {
        mainHandler.post(() -> listener.onStats(stats));
    }

    private void emitLog(SunshineLogLevel level, String message) {
        SunshineLogLevel safeLevel = level != null ? level : SunshineLogLevel.INFO;
        String safeMessage = message != null ? message : "";
        mainHandler.post(() -> listener.onLog(safeLevel, safeMessage));
    }

    private void emitEncoderError(String message) {
        emitLog(SunshineLogLevel.ERROR, message);
        listener.onEncoderError(message);
    }

    private static SunshineLogLevel fromNativeLogLevel(int level) {
        SunshineLogLevel[] values = SunshineLogLevel.values();
        if (level < 0 || level >= values.length) {
            return SunshineLogLevel.INFO;
        }
        return values[level];
    }

    private final class NativeCallbacks {
        @SuppressWarnings("unused")
        public void onVideoInputSurface(Surface surface, int width, int height, int frameRate) {
            startCapture(surface, width, height, frameRate);
        }

        @SuppressWarnings("unused")
        public void onVideoStopped() {
            handleNativeVideoStopped();
        }

        @SuppressWarnings("unused")
        public void onPinRequested() {
            mainHandler.post(() -> listener.onPinRequested());
        }

        @SuppressWarnings("unused")
        public void onClientConnected(String name, String address) {
            clientName = name;
            clientAddress = address;
            setState(SunshineSessionState.STREAMING);
            mainHandler.post(() -> listener.onClientConnected(name, address));
        }

        @SuppressWarnings("unused")
        public void onClientDisconnected(String name) {
            clientName = null;
            clientAddress = null;
            if (running.get()) {
                setState(SunshineSessionState.RUNNING);
            }
            mainHandler.post(() -> listener.onClientDisconnected(name));
        }

        @SuppressWarnings("unused")
        public void onEncoderError(String message) {
            mainHandler.post(() -> emitEncoderError(message));
        }

        @SuppressWarnings("unused")
        public boolean onAudioSourceStart(int sampleRate, int channelCount, int frameSize) {
            SunshineAudioSource source = audioSource;
            if (source == null) {
                activeAudioSource = null;
                emitLog(SunshineLogLevel.ERROR, "Audio is enabled, but no SunshineAudioSource is configured");
                return false;
            }

            SunshineAudioFormat format = new SunshineAudioFormat(sampleRate, channelCount, frameSize);
            try {
                source.start(format);
                activeAudioSource = source;
                return true;
            } catch (RuntimeException e) {
                activeAudioSource = null;
                emitLog(SunshineLogLevel.ERROR, "Unable to start SunshineAudioSource: " + e.getMessage());
                return false;
            }
        }

        @SuppressWarnings("unused")
        public int onAudioSourceRead(float[] buffer, int frameCount) {
            SunshineAudioSource source = activeAudioSource;
            if (source == null) {
                return SunshineAudioSource.READ_ERROR;
            }
            try {
                int frames = source.read(buffer, frameCount);
                if (frames > frameCount) {
                    emitLog(SunshineLogLevel.WARNING, "SunshineAudioSource returned more frames than requested");
                    return frameCount;
                }
                return frames;
            } catch (RuntimeException e) {
                emitLog(SunshineLogLevel.ERROR, "SunshineAudioSource read failed: " + e.getMessage());
                return SunshineAudioSource.READ_ERROR;
            }
        }

        @SuppressWarnings("unused")
        public void onAudioSourceStop() {
            SunshineAudioSource source = activeAudioSource;
            activeAudioSource = null;
            if (source != null) {
                try {
                    source.stop();
                } catch (RuntimeException e) {
                    emitLog(SunshineLogLevel.WARNING, "SunshineAudioSource stop failed: " + e.getMessage());
                }
            }
        }

        @SuppressWarnings("unused")
        public void onNativeStats(long videoFrames, long videoKeyFrames, long videoBytes, long audioFrames, long audioSamples) {
            dispatchStats(buildStats(videoFrames, videoKeyFrames, videoBytes, audioFrames, audioSamples));
        }

        @SuppressWarnings("unused")
        public void onLog(int level, String message) {
            emitLog(fromNativeLogLevel(level), message);
        }

        @SuppressWarnings("unused")
        public void onTouch(int eventType, int rotation, int pointerId, float x, float y, float pressure, float major, float minor) {
            mainHandler.post(() -> {
                if (inputSink != null) {
                    inputSink.onTouch(eventType, rotation, pointerId, x, y, pressure, major, minor);
                }
            });
        }

        @SuppressWarnings("unused")
        public void onAbsoluteMouse(float x, float y, float width, float height) {
            mainHandler.post(() -> {
                if (inputSink != null) {
                    inputSink.onAbsoluteMouse(x, y, width, height);
                }
            });
        }

        @SuppressWarnings("unused")
        public void onRelativeMouse(int dx, int dy) {
            mainHandler.post(() -> {
                if (inputSink != null) {
                    inputSink.onRelativeMouse(dx, dy);
                }
            });
        }

        @SuppressWarnings("unused")
        public void onMouseButton(int button, boolean release) {
            mainHandler.post(() -> {
                if (inputSink != null) {
                    inputSink.onMouseButton(button, release);
                }
            });
        }

        @SuppressWarnings("unused")
        public void onMouseScroll(int vertical, int horizontal) {
            mainHandler.post(() -> {
                if (inputSink != null) {
                    inputSink.onMouseScroll(vertical, horizontal);
                }
            });
        }

        @SuppressWarnings("unused")
        public void onKeyboard(int windowsKeyCode, boolean release, int flags) {
            mainHandler.post(() -> {
                if (inputSink != null) {
                    inputSink.onKeyboard(windowsKeyCode, release, flags);
                }
            });
        }

        @SuppressWarnings("unused")
        public void onUnicode(String text) {
            mainHandler.post(() -> {
                if (inputSink != null) {
                    inputSink.onUnicode(text);
                }
            });
        }

        @SuppressWarnings("unused")
        public void onGamepad(int controller, int buttons, int lt, int rt, int lsX, int lsY, int rsX, int rsY) {
            mainHandler.post(() -> {
                if (inputSink != null) {
                    inputSink.onGamepad(controller, buttons, lt, rt, lsX, lsY, rsX, rsY);
                }
            });
        }
    }

    private static native void nativeInit(
            Object callbacks,
            String filesDir,
            String statePath,
            String certPath,
            String keyPath,
            String hostName,
            int port,
            boolean enableHevc,
            boolean enableAudio);

    private static native void nativeStart();
    private static native void nativePrepareFreshStart();
    private static native void nativeStop();
    private static native void nativeStopCurrentSession();
    private static native boolean nativeSubmitPin(String pin);
    private static native boolean nativeRegisterPendingAutoPair(String requestId, String sinkUniqueId, String pin, long expiresAtUnixSeconds);
    private static native void nativeSetSandboxAudioSourceEnabled(boolean enabled);
    private static native void nativeSetPlaybackAudioRecord(AudioRecord audioRecord, int sampleRate, int channelCount);
    private static native void nativeClearNativeAudioSource();
    private static native void nativeRelease();
    private static native long[] nativeGetStats();
}

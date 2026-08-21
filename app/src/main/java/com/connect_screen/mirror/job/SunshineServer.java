package com.connect_screen.mirror.job;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.input.IInputManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.WindowManager;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.MotionEventHidden;
import android.view.Surface;
import android.widget.EditText;
import android.widget.Toast;
import android.media.AudioRecord;

import android.media.AudioManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.connect_screen.mirror.Pref;
import com.connect_screen.mirror.R;
import com.connect_screen.mirror.State;
import com.connect_screen.mirror.SunshineService;
import com.connect_screen.mirror.shizuku.ServiceUtils;
import com.connect_screen.mirror.shizuku.ShizukuUtils;
import com.connect_screen.mirror.shizuku.SurfaceControl;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.rikka.tools.refine.Refine;

// 代码拷贝自 v2025.122.141614
public class SunshineServer {
    public static String suppressPin;
    public static String pinCandidate;
    private static final AtomicBoolean stoppingVirtualDisplay = new AtomicBoolean(false);
    private static final long MOONLIGHT_PROJECTION_START_TIMEOUT_MS = 15_000L;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile long activeMoonlightSessionId;
    private static volatile Thread videoSourceThread;
    private static volatile Surface activeEncoderSurface;
    private static volatile int activeVideoWidth;
    private static volatile int activeVideoHeight;
    public static volatile int activeDexDisplayId = -1;
    private static volatile boolean videoSourceCancelled;

    static {
        System.loadLibrary("sunshine");
    }

    public static native void start();

    public static native void setSunshineName(String sunshineName);
    public static native void setPkeyPath(String path);
    public static native void setCertPath(String path);
    public static native void setFileStatePath(String path);
    public static native void setVideoCodec(int codec);
    public static native void setEncoderSettings(
            int bitratePercent,
            int bitrateMode,
            int complexity,
            int iFrameInterval,
            int maxFps,
            boolean lowLatency,
            boolean disableBFrames,
            boolean realtimePriority,
            int fecPercent);
    public static native void setEncoderAvcBaseline(boolean enabled);

    public static void setEncoderSettingsFromPreferences() {
        setEncoderSettings(
                Pref.getEncoderBitratePercent(),
                Pref.getEncoderBitrateMode(),
                Pref.getEncoderComplexity(),
                Pref.getEncoderIFrameInterval(),
                Pref.getEncoderMaxFps(),
                Pref.getEncoderLowLatency(),
                Pref.getEncoderDisableBFrames(),
                Pref.getEncoderRealtimePriority(),
                Pref.getStreamFecPercent());
        setEncoderAvcBaseline(Pref.getEncoderAvcBaseline());
    }
    
    // 添加新的回调方法，当需要 PIN 码时被 C++ 代码调用
    public static void onPinRequested() {
        // 使用 Handler 将回调切换到主线程
        new Handler(Looper.getMainLooper()).post(() -> {
            Context context = State.getContext();
            if (context == null) {
                return;
            }

            // 创建一个输入框
            final EditText input = new EditText(context);
            input.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            input.setText(pinCandidate != null ? pinCandidate : "");
            input.setBackgroundResource(R.drawable.bg_libredex_button_neutral);
            input.setTextColor(ContextCompat.getColor(context, R.color.ui_text_primary));
            input.setHintTextColor(ContextCompat.getColor(context, R.color.ui_text_secondary));
            input.setTextSize(20);
            input.setGravity(android.view.Gravity.CENTER);
            input.setPadding(48, 0, 48, 0);
            // 限制输入长度为4位
            InputFilter[] filters = new InputFilter[1];
            filters[0] = new InputFilter.LengthFilter(4);
            input.setFilters(filters);
            input.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    submitPinFromDialog(input, context);
                    return true;
                }
                return false;
            });

            // 创建对话框
            if (suppressPin != null) {
                submitPin(suppressPin);
            } else {
                AlertDialog dialog = new MaterialAlertDialogBuilder(
                        context, R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                        .setTitle(context.getString(R.string.pin_dialog_title))
                        .setMessage(context.getString(R.string.pin_dialog_msg))
                        .setView(input)
                        .setPositiveButton(context.getString(R.string.action_ok), (d, which) -> submitPinFromDialog(input, context))
                        .setNegativeButton(context.getString(R.string.action_cancel), (d, which) -> d.cancel())
                        .create();
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                }
                dialog.setOnShowListener(d -> styleDialogButtons(dialog, context));
                dialog.show();
            }
        });
    }

    private static void submitPinFromDialog(EditText input, Context context) {
        String pin = input.getText().toString();
        if (pin.length() == 4) {
            submitPin(pin);
        } else {
            Toast.makeText(context, context.getString(R.string.pin_invalid_toast), Toast.LENGTH_SHORT).show();
        }
    }

    private static void styleDialogButtons(AlertDialog dialog, Context context) {
        android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        android.widget.Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (positive != null) {
            ViewCompat.setBackgroundTintList(positive, ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.ui_accent)));
            positive.setTextColor(ContextCompat.getColor(context, R.color.ui_on_accent));
        }
        if (negative != null) {
            ViewCompat.setBackgroundTintList(negative, ColorStateList.valueOf(Color.TRANSPARENT));
            negative.setTextColor(ContextCompat.getColor(context, R.color.ui_text_primary));
        }
    }
    
    // 添加提交PIN码的native方法
    public static native void submitPin(String pin);

    public static native void notifyVideoSourceFailure();
    
    
    // surface created by MediaCodec
    // width always > height, as it is a landscape mode
    public static boolean createVirtualDisplay(int width, int height, int frameRate, int packetDuration, Surface surface, boolean shouldMutePhone, long sessionId) {
        suppressPin = null;
        activeMoonlightSessionId = sessionId;
        activeVideoWidth = width;
        activeVideoHeight = height;
        Context context = State.getContext();
        if (context == null) {
            State.log("[ProjectViaMoonlight] context is null before scheduling projection startup");
            return true;
        }
        SessionLifecycle.start(context, sessionId);

        try {
            SunshineMouse.initialize(width, height);
        } catch(Throwable e) {
            State.log("[SunshineServer] SunshineMouse.initialize failed (non-fatal): " + e.getMessage());
        }
        try {
            SunshineKeyboard.initialize();
        } catch(Throwable e) {
            State.log("[SunshineServer] SunshineKeyboard.initialize failed (non-fatal): " + e.getMessage());
        }

        // The native side calls this entry with the MediaCodec encoder input
        // surface; the LibreDeX video source renders the fake DeX display.
        State.log("[SunshineVD] LibreDeX video source for API " + Build.VERSION.SDK_INT);
        activeEncoderSurface = surface;
        videoSourceCancelled = false;
        Thread previous = videoSourceThread;
        if (previous != null && previous.isAlive()) {
            State.log("[SunshineVD] replacing previous video source worker");
        }
        Thread worker = new Thread(() -> startMoonlightVideoSource(
                surface, width, height, frameRate, packetDuration, shouldMutePhone, sessionId),
                "moonlight-video-source");
        worker.setDaemon(true);
        videoSourceThread = worker;
        worker.start();
        State.bringMainActivityToFrontLater(1500);
        State.bringMainActivityToFrontLater(4000);
        return true;
    }

    public static void onVideoInputSurface(Surface surface, int width, int height, int frameRate, long sessionId) {
        if (surface == null) {
            State.log("[SunshineVD] onVideoInputSurface called with null surface");
            return;
        }
        suppressPin = null;
        activeMoonlightSessionId = sessionId;
        activeVideoWidth = width;
        activeVideoHeight = height;
        Context context = State.getContext();
        if (context != null) {
            SessionLifecycle.start(context, sessionId);
        }
        try {
            SunshineMouse.initialize(width, height);
        } catch (Throwable e) {
            State.log("[SunshineServer] SunshineMouse.initialize failed (non-fatal): " + e.getMessage());
        }
        try {
            SunshineKeyboard.initialize();
        } catch (Throwable e) {
            State.log("[SunshineServer] SunshineKeyboard.initialize failed (non-fatal): " + e.getMessage());
        }

        activeEncoderSurface = surface;
        videoSourceCancelled = false;
        Thread previous = videoSourceThread;
        if (previous != null && previous.isAlive()) {
            State.log("[SunshineVD] replacing previous video source worker");
        }
        Thread worker = new Thread(() -> startMoonlightVideoSource(
                surface, width, height, frameRate, 0, false, sessionId),
                "moonlight-video-source");
        worker.setDaemon(true);
        videoSourceThread = worker;
        worker.start();
        State.bringMainActivityToFrontLater(1500);
        State.bringMainActivityToFrontLater(4000);
    }

    public static void restartDexSession() {
        int displayId = activeDexDisplayId >= 0 ? activeDexDisplayId : State.externalDisplayId;
        if (displayId < 0 || !State.isUserServiceAlive()) {
            State.log("[LibreDeX] restart skipped: no active DeX display or user service");
            return;
        }
        State.log("[LibreDeX] restarting DeX desktop on display " + displayId);
        try {
            State.userService.restartSecondaryLauncher(
                    displayId, activeVideoWidth, activeVideoHeight);
        } catch (Throwable t) {
            State.log("[LibreDeX] restartSecondaryLauncher failed: " + t.getMessage());
        }
    }

    private static void startMoonlightVideoSource(
            Surface encoderSurface, int width, int height, int frameRate,
            int packetDuration, boolean shouldMutePhone, long sessionId) {
        if (OutputSource.isMirrorActive()) {
            State.log("[LibreDeX] mirror source selected, starting display 0 mirror");
            try {
                new ProjectViaMoonlight(
                        width, height, frameRate, packetDuration,
                        encoderSurface, shouldMutePhone, sessionId).start();
            } catch (YieldException e) {
                State.log("[LibreDeX] mirror projection yielded: " + e.getMessage());
            } catch (Throwable t) {
                State.log("[LibreDeX] mirror projection failed: " + t);
                failMoonlightVideoSource("Mirror projection failed: " + t.getMessage());
            }
        } else {
            startDexAnywhereVideoSource(encoderSurface, width, height, frameRate);
        }
    }

    private static void startDexAnywhereVideoSource(Surface encoderSurface, int width, int height, int frameRate) {
        try {
            if (!State.isUserServiceAlive()) {
                State.ensureUserServiceBound();
                long deadline = System.currentTimeMillis() + 5000;
                while (!State.isUserServiceAlive()
                        && System.currentTimeMillis() < deadline && !videoSourceCancelled) {
                    Thread.sleep(100);
                }
            }
            if (videoSourceCancelled) {
                return;
            }
            if (!State.isUserServiceAlive()) {
                failMoonlightVideoSource("LibreDeX UserService unavailable");
                return;
            }
            Surface videoSurface = encoderSurface;
            ExternalDisplayFramePacer framePacer = null;
            if (!Pref.getEncoderDynamicFrameRate()) {
                try {
                    framePacer = new ExternalDisplayFramePacer(width, height, frameRate, encoderSurface);
                    videoSurface = framePacer.start();
                    SunshineMouse.setExternalDisplayFramePacer(framePacer, activeMoonlightSessionId);
                    State.log("[LibreDeX] frame pacer enabled @" + frameRate + "fps");
                } catch (Throwable t) {
                    State.log("[LibreDeX] frame pacer unavailable, direct surface: " + t.getMessage());
                    if (framePacer != null) {
                        try {
                            framePacer.stop();
                        } catch (Throwable ignored) {
                        }
                    }
                    framePacer = null;
                    videoSurface = encoderSurface;
                }
            }
            int vdId = State.userService.createDexMirror(
                    "dex-anywhere-dex-flag-enc", width, height, frameRate, videoSurface);
            State.log("[LibreDeX] createDexMirror id=" + vdId);
            if (vdId < 0) {
                activeDexDisplayId = -1;
                if (framePacer != null) {
                    try {
                        framePacer.stop();
                    } catch (Throwable ignored) {
                    }
                }
                failMoonlightVideoSource("LibreDeX fake DeX display creation failed");
                return;
            }
            activeDexDisplayId = vdId;
            SunshineMouse.setDexTargetDisplayId(vdId);
            SunshineMouse.initialize(width, height);
            try {
                InputRouting.bindAllExternalInputToDisplay(vdId);
                State.log("[LibreDeX] bound external input to display " + vdId);
            } catch (Throwable t) {
                State.log("[LibreDeX] bind external input failed: " + t.getMessage());
            }
            showMoonlightControlHint();
            while (!videoSourceCancelled) {
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            State.log("[LibreDeX] video source worker interrupted");
        } catch (Throwable t) {
            State.log("[LibreDeX] video source failed: " + t.getClass().getSimpleName()
                    + " " + t.getMessage());
            failMoonlightVideoSource("LibreDeX video source failed: " + t.getMessage());
        }
    }

    private static void failMoonlightVideoSource(String message) {
        State.log("[SunshineVD] " + message);
        showEncoderError(message);
        notifyVideoSourceFailure();
        stopVirtualDisplay();
    }

    public static boolean isMoonlightSessionActive() {
        return activeMoonlightSessionId != 0;
    }

    public static void updateStreamingDebugInfo(String info) {
        String nativeDebugInfo = info;
        new Handler(Looper.getMainLooper()).post(() -> {
            String debugInfo = nativeDebugInfo;
            String framePacerInfo = SunshineMouse.collectFramePacerDebugLine();
            if (!framePacerInfo.isEmpty()) {
                debugInfo = debugInfo + "\n" + framePacerInfo;
            }
            State.streamingDebugInfo.setValue(debugInfo);
        });
    }

    public static void updateLastMoonlightHandshakeInfo(String info) {
        State.lastMoonlightHandshakeInfo = info;
    }

    public static void updateLastMoonlightControlInputInfo(String info) {
        String touchInputInfo = SunshineMouse.collectTouchInputDebugLine();
        if (!touchInputInfo.isEmpty()) {
            State.lastMoonlightControlInputInfo = info + "\n" + touchInputInfo;
            return;
        }
        State.lastMoonlightControlInputInfo = info;
    }

    public static void showMoonlightControlHint() {
        new Handler(Looper.getMainLooper()).post(() -> {
            Context context = State.getContext();
            if (context == null) {
                return;
            }
            Toast.makeText(context, context.getString(R.string.moonlight_cursor_hint), Toast.LENGTH_LONG).show();
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                    Toast.makeText(context, context.getString(R.string.moonlight_cursor_hint), Toast.LENGTH_LONG).show(), 3500);
        });
    }

    public static void stopVirtualDisplay() {
        stopVirtualDisplay(0);
    }

    public static void stopVirtualDisplay(long sessionId) {
        if (!stoppingVirtualDisplay.compareAndSet(false, true)) {
            State.log("Moonlight projection stopping, skipping duplicate stop request");
            return;
        }
        Runnable cleanup = () -> {
            try {
                cleanupMoonlightProjection(sessionId);
            } finally {
                stoppingVirtualDisplay.set(false);
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup.run();
            return;
        }

        CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                cleanup.run();
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(5, TimeUnit.SECONDS)) {
                State.log("Moonlight projection stop wait timed out, releasing encoder");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            State.log("Moonlight projection stop wait interrupted");
        }
    }

    private static void cleanupMoonlightProjection(long sessionId) {
        boolean force = sessionId == 0;
        long activeSessionId = activeMoonlightSessionId;
        if (!force && activeSessionId != 0 && activeSessionId != sessionId) {
            State.log("Skipping stale Moonlight projection cleanup, session=" + sessionId
                    + " active=" + activeSessionId);
            return;
        }
        State.log("Stopping Moonlight projection");
        SessionLifecycle.stop(State.getContext(), activeSessionId);
        activeMoonlightSessionId = 0;
        videoSourceCancelled = true;
        Thread videoThread = videoSourceThread;
        if (videoThread != null && videoThread != Thread.currentThread()) {
            videoThread.interrupt();
            try {
                videoThread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        videoSourceThread = null;
        activeDexDisplayId = -1;
        State.streamingDebugInfo.setValue("串流未启动");
        SunshineAudio.restoreVolume(State.getContext());
        SunshineMouse.resetInjectedInputState();
        SunshineMouse.cleanupCursorOverlay();
        CreateVirtualDisplay.powerOnScreen();
        SunshineMouse.stopExternalDisplayFramePacer(sessionId, force);
        if (SunshineMouse.autoRotateAndScaleForMoonlight != null) {
            SunshineMouse.autoRotateAndScaleForMoonlight.stop();
            SunshineMouse.autoRotateAndScaleForMoonlight = null;
        }
        if (State.mirrorVirtualDisplay != null) {
            State.mirrorVirtualDisplay.release();
            State.mirrorVirtualDisplay = null;
        }
        if (State.isUserServiceAlive()) {
            try {
                State.userService.destroyExternalMirror();
            } catch (RemoteException e) {
                State.log("destroyExternalMirror failed: " + e.getMessage());
                State.userService = null;
            }
        } else if (State.userService != null) {
            State.userService = null;
        }
        Surface encoderSurface = activeEncoderSurface;
        activeEncoderSurface = null;
        if (encoderSurface != null) {
            try {
                encoderSurface.release();
            } catch (Throwable e) {
                State.log("release encoder surface failed: " + e.getMessage());
            }
        }
    }

    // 添加新方法用于启动音频录制
    public static native void startAudioRecording(Object audioRecord, int framesPerPacket);
    public static native void pushAudioSamples(float[] data, int count);

    public static native void enableH265();

    public static void startMoonlightAudioCapture(int packetDuration, boolean shouldMutePhone) {
        android.util.Log.i("SunshineAudio", "native called startMoonlightAudioCapture pkt="
                + packetDuration + " mute=" + shouldMutePhone);
        new Handler(Looper.getMainLooper()).post(() -> {
            Context context = State.getContext();
            if (context == null) {
                State.log("Moonlight audio capture skipped: context is null");
                android.util.Log.i("SunshineAudio", "context null, skip audio");
                return;
            }
            SunshineAudio.startClientAudioCapture(context, packetDuration, shouldMutePhone);
        });
    }

    // 添加显示编码器错误的方法
    public static void showEncoderError(String errorMessage) {
        State.log("[SunshineServer] encoder error: " + errorMessage);
        Log.e("SunshineServer", "encoder error: " + errorMessage,
                new Throwable("encoder error origin"));
        boolean autoFellBack = fallbackEncoderCodecOnFailure();
        if (autoFellBack) {
            errorMessage = errorMessage
                    + State.getContext().getString(R.string.sunshine_auto_fallback);
            State.log("[SunshineServer] HEVC encoder setup failed, auto fallback to H.264");
        }
        final String message = errorMessage;
        new Handler(Looper.getMainLooper()).post(() -> {
            Context context = State.getContext();
            if (context == null) {
                return;
            }
            
            AlertDialog dialog = new MaterialAlertDialogBuilder(
                    context, R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                    .setTitle(context.getString(R.string.encoder_config_failed))
                    .setMessage(message)
                    .setPositiveButton(context.getString(R.string.action_ok), (d, which) -> stopVirtualDisplay())
                    .setCancelable(false)
                    .create();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            }
            dialog.setOnShowListener(d -> {
                android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (positive != null) {
                    positive.setTextColor(ContextCompat.getColor(context, R.color.ui_accent));
                }
            });
            dialog.show();
        });
    }

    private static boolean fallbackEncoderCodecOnFailure() {
        if (Pref.getEncoderCodec() != Pref.ENCODER_CODEC_H265) {
            return false;
        }
        // Do not persist H.264 here: the fallback is per Sunshine process only.
        // The next service restart retries HEVC so a HEVC-capable client is not
        // permanently locked out. True per-client negotiation would need the
        // native Sunshine handshake, which this repo does not vendor.
        setVideoCodec(Pref.ENCODER_CODEC_H264);
        setEncoderSettingsFromPreferences();
        return true;
    }

    public static void onConnectScreenClientDiscovered(String connectScreenClient) {
        if (State.discoveredConnectScreenClients.contains(connectScreenClient)) {
            return;
        }
        State.discoveredConnectScreenClients.add(connectScreenClient);
    }

    public static void setConnectScreenServerUuid(String uuid) {
        State.serverUuid = uuid;
        if (!Pref.doNotAutoStartMoonlight) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (Pref.getAutoConnectClient() && !Pref.getSelectedClient().isEmpty()) {
                    ConnectToClient.connect((int)(Math.random() * 9000) + 1000);
                }
            }, 1000);
        }
    }

    public static native boolean exitServer();

}

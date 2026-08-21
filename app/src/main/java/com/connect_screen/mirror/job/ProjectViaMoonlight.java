package com.connect_screen.mirror.job;

import android.content.Context;
import android.os.Build;
import android.os.RemoteException;
import android.view.Display;
import android.view.Surface;

import com.connect_screen.mirror.Pref;
import com.connect_screen.mirror.State;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

public class ProjectViaMoonlight implements Job {
    public interface StartupCallback {
        void onStartupComplete(boolean success);
    }

    private final int width;
    private final int height;
    private final int frameRate;
    private final int packetDuration;
    private final Surface surface;
    private final boolean shouldMutePhone;
    private final long sessionId;
    private final StartupCallback startupCallback;
    private boolean userServiceRequested;
    private boolean startupReported;

    public ProjectViaMoonlight(int width, int height, int frameRate, int packetDuration, Surface surface, boolean shouldMutePhone, long sessionId) {
        this(width, height, frameRate, packetDuration, surface, shouldMutePhone, sessionId, null);
    }

    public ProjectViaMoonlight(int width, int height, int frameRate, int packetDuration, Surface surface, boolean shouldMutePhone, long sessionId, StartupCallback startupCallback) {
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
        this.packetDuration = packetDuration;
        this.surface = surface;
        this.shouldMutePhone = shouldMutePhone;
        this.sessionId = sessionId;
        this.startupCallback = startupCallback;
    }

    @Override
    public void start() throws YieldException {
        State.log("[ProjectViaMoonlight] start, w=" + width + " h=" + height
                + " fps=" + frameRate + " mutePhone=" + shouldMutePhone);

        Context context = State.getContext();
        if (context == null) {
            State.log("[ProjectViaMoonlight] context is null, abort");
            reportStartup(false);
            return;
        }

        startDefaultProjection(context);
    }

    private void startDefaultProjection(Context context) throws YieldException {
        if (!ShizukuUtils.hasPermission()) {
            State.showErrorStatus("Mirror mode needs Shizuku permission to capture display 0");
            return;
        }
        releaseStaleAppMirrorState();
        SunshineMouse.initialize(width, height);
        SunshineKeyboard.initialize();
        State.log("[MirrorPrimary] start display 0 mirror w=" + width + " h=" + height);
        if (!mirrorPrimaryDisplay(width, height, surface)) {
            throw new RuntimeException("Mirror mode failed to start");
        }

        State.log(shouldMutePhone
                ? "Moonlight audio route requests phone speaker mute; audio capture is driven by native audio thread"
                : "Moonlight audio route keeps phone speaker enabled; audio capture is driven by native audio thread");
    }

    private boolean mirrorPrimaryDisplay(int width, int height, Surface surface) throws YieldException {
        CurrentScreen currentScreen = CurrentScreen.detect(State.getContext());
        int mirrorDisplayId = currentScreen.displayId;
        State.log("[MirrorPrimaryDisplay] current screen displayId=" + mirrorDisplayId
                + " cover=" + currentScreen.isCover
                + " size=" + currentScreen.width + "x" + currentScreen.height);
        if (MirrorTransformPolicy.shouldUseSurfacePipeline()
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            State.log("[MirrorPrimaryDisplay] mirror transform enabled, use shared GL pipeline");
            AutoRotateAndScaleForMoonlight autoRotatePipeline =
                    MirrorTransformPolicy.startSurfacePipeline(
                            surface,
                            currentScreen,
                            width,
                            height,
                            frameRate,
                            "Moonlight-main-mirror",
                            "Mirror mode failed to mirror the current screen. Confirm Shizuku is running, then retry.",
                            true);
            if (autoRotatePipeline != null) {
                SunshineMouse.autoRotateAndScaleForMoonlight = autoRotatePipeline;
                return true;
            }
            State.showErrorStatus(
                    "Mirror mode failed to start the shared GL pipeline. Confirm Shizuku is running, then retry.");
            throw new RuntimeException("shared GL mirror pipeline failed to start");
        }
        return mirrorDisplay(
                "[MirrorPrimaryDisplay]",
                "Moonlight-main-mirror",
                mirrorDisplayId,
                mirrorDisplayId,
                width,
                height,
                surface,
                "Mirror mode failed to mirror the current screen. Confirm Shizuku is running, then retry.");
    }

    private void releaseStaleAppMirrorState() {
        if (State.mirrorVirtualDisplay != null) {
            try {
                State.log("[MirrorPrimary] release stale app VirtualDisplay before Shizuku mirror");
                State.mirrorVirtualDisplay.release();
            } catch (Exception e) {
                State.log("[MirrorPrimary] release stale app VirtualDisplay failed: "
                        + e.getClass().getSimpleName() + " " + e.getMessage());
            }
            State.mirrorVirtualDisplay = null;
        }
        State.log("[MirrorPrimary] keep MediaProjection for Android native audio capture");
    }

    private boolean mirrorExternalDisplay(int width, int height, Surface surface) throws YieldException {
        return mirrorDisplay(
                "[MirrorExternalDisplay]",
                "Moonlight-mirror",
                State.externalDisplayId,
                State.externalControlDisplayId > 0 ? State.externalControlDisplayId : State.externalDisplayId,
                width,
                height,
                surface,
                "Mirror mode failed to mirror external display. Restart Sunshine service and retry.");
    }

    private boolean mirrorDisplay(
            String logPrefix,
            String mirrorName,
            int displayIdToMirror,
            int controlDisplayId,
            int width,
            int height,
            Surface surface,
            String failureMessage) throws YieldException {
        State.log(logPrefix + " enter, surface=" + surface
                + " w=" + width + " h=" + height
                + " displayId=" + displayIdToMirror
                + " userService=" + State.userService);
        try {
            if (!State.isUserServiceAlive()) {
                waitForUserService(logPrefix + " userService unavailable, rebind before mirroring display");
            }
            Surface mirrorSurface = surface;
            ExternalDisplayFramePacer framePacer = startFramePacerIfNeeded(
                    logPrefix,
                    width,
                    height,
                    frameRate,
                    surface);
            SunshineMouse.setExternalDisplayFramePacer(framePacer, sessionId);
            if (framePacer != null) {
                mirrorSurface = framePacer.getInputSurface();
            }
            State.log(logPrefix + " call userService.createExternalMirror");
            int result = State.userService.createExternalMirror(mirrorName, width, height, displayIdToMirror, frameRate, mirrorSurface);
            State.log(logPrefix + " createExternalMirror result=" + result);
            if (result < 0) {
                SunshineMouse.stopExternalDisplayFramePacer(sessionId, false);
                State.showErrorStatus(failureMessage);
                return false;
            }
            State.mirrorExternalToken = null;
            State.lastSingleAppDisplay = controlDisplayId;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                State.log(logPrefix + " [API31+] success, vdId=" + result);
            } else {
                State.log(logPrefix + " [API30] SurfaceControl success");
            }
            SunshineServer.showMoonlightControlHint();
            return true;
        } catch (YieldException e) {
            throw e;
        } catch (RemoteException e) {
            State.log(logPrefix + " RemoteException: "
                    + e.getClass().getSimpleName() + " msg=" + e.getMessage());
            State.userService = null;
            waitForUserService(logPrefix + " userService binder died, rebind and retry");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            State.log(logPrefix + " exception: "
                    + e.getClass().getSimpleName() + " msg=" + e.getMessage());
        }
        return false;
    }

    private ExternalDisplayFramePacer startFramePacerIfNeeded(
            String logPrefix,
            int width,
            int height,
            int frameRate,
            Surface outputSurface) {
        if (Pref.getEncoderDynamicFrameRate()) {
            State.log(logPrefix + " dynamic frame rate enabled, use direct encoder surface");
            return null;
        }
        ExternalDisplayFramePacer framePacer = null;
        try {
            framePacer = new ExternalDisplayFramePacer(width, height, frameRate, outputSurface);
            framePacer.start();
            State.log(logPrefix + " fixed frame pacer enabled");
            return framePacer;
        } catch (RuntimeException e) {
            State.log(logPrefix + " fixed frame pacer unavailable, fallback to direct surface: "
                    + e.getMessage());
            if (framePacer != null) {
                framePacer.stop();
            }
            return null;
        }
    }

    private void waitForUserService(String reason) throws YieldException {
        State.log(reason);
        if (!ShizukuUtils.hasPermission()) {
            State.showErrorStatus("Moonlight mirror needs Shizuku permission");
            throw new RuntimeException("Shizuku permission missing");
        }
        if (userServiceRequested) {
            State.showErrorStatus("Moonlight mirror failed to wait for Shizuku user service. Confirm Shizuku is running, then retry.");
            throw new RuntimeException("Shizuku user service unavailable");
        }
        userServiceRequested = true;
        State.ensureUserServiceBound();
        State.resumeJobLater(3000);
        throw new YieldException("Waiting for Shizuku user service");
    }

    private void reportStartup(boolean success) {
        if (startupReported) {
            return;
        }
        startupReported = true;
        if (startupCallback != null) {
            startupCallback.onStartupComplete(success);
        }
    }

}

package com.connect_screen.mirror.job;

import android.content.Context;
import android.graphics.Rect;
import android.os.RemoteException;
import android.view.Display;

import com.connect_screen.mirror.State;
import com.connect_screen.mirror.shizuku.ServiceUtils;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

/**
 * Wired DP output: tells the LSPosed hook which physical display to treat as a
 * DeX display, launches SecondaryLauncher on it, binds external input, and
 * stops automatically when the display is unplugged.
 */
public class ProjectViaDp implements Job {
    private static final String TAG = "ProjectViaDp";
    private static final String DP_DISPLAY_PROP = "persist.dex.lspmirror.dp_display_id";

    private static volatile boolean active;
    private static volatile int activeDisplayId = -1;
    private static volatile boolean activeMirror;
    private static volatile DpMirrorPresentation activeMirrorPresentation;

    private final boolean dexSource;

    public ProjectViaDp(boolean dexSource) {
        this.dexSource = dexSource;
    }

    public static boolean isActive() {
        return active;
    }

    public static void stop() {
        active = false;
        if (activeMirror) {
            DpMirrorPresentation mirrorPresentation = activeMirrorPresentation;
            activeMirrorPresentation = null;
            if (mirrorPresentation != null) {
                mirrorPresentation.stop();
            }
            stopDpMirror();
        }
        activeMirror = false;
        activeDisplayId = -1;
        if (State.externalDisplayId > 0) {
            resetDpMirror(State.externalDisplayId);
        }
        SessionLifecycle.stop(State.getContext(), ProjectViaDp.class);
        clearConfiguredDisplayId();
        ExternalDisplayMonitor.stop();
    }

    @Override
    public void start() throws YieldException {
        Context context = State.getContext();
        if (context == null) {
            State.log(TAG + ": context unavailable");
            return;
        }
        if (!ShizukuUtils.hasPermission()) {
            State.showErrorStatus("DP output needs Shizuku permission");
            return;
        }
        ExternalDisplayMonitor.refreshState(context);
        final int displayId = State.externalDisplayId;
        if (displayId <= 0) {
            State.showErrorStatus("未检测到外接屏，请先连接 DP/HDMI 线");
            return;
        }
        int width = State.externalDisplayWidth;
        int height = State.externalDisplayHeight;
        if (width <= 0 || height <= 0) {
            Display display = ExternalDisplayMonitor.getPrimaryExternalDisplay(context);
            if (display != null) {
                width = display.getWidth();
                height = display.getHeight();
            }
        }
        if (width <= 0 || height <= 0) {
            State.showErrorStatus("无法读取外接屏尺寸");
            return;
        }
        int refresh = 60;
        Display externalDisplay = ExternalDisplayMonitor.getPrimaryExternalDisplay(context);
        if (externalDisplay != null && externalDisplay.getMode() != null) {
            refresh = Math.round(externalDisplay.getMode().getRefreshRate());
        }

        active = true;
        activeDisplayId = displayId;
        activeMirror = false;
        final int targetDisplayId = displayId;
        final int targetWidth = width;
        final int targetHeight = height;
        final int targetRefresh = refresh;
        new Thread(() -> startInternal(
                targetDisplayId, targetWidth, targetHeight, targetRefresh), TAG).start();
    }

    private void startInternal(int displayId, int width, int height, int refresh) {
        try {
            State.ensureUserServiceBound();
            long deadline = System.currentTimeMillis() + 5000;
            while (!State.isUserServiceAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            if (!State.isUserServiceAlive()) {
                State.showErrorStatus("LibreDeX UserService unavailable");
                stop();
                return;
            }
            int resetRc = State.userService.resetDpMirror(displayId);
            State.log(TAG + ": resetDpMirror rc=" + resetRc);
            if (dexSource) {
                setConfiguredDisplayId(displayId);
                runShell("getprop " + DP_DISPLAY_PROP);
                forceDisplayInfoQuery(displayId);
                State.userService.startSecondaryLauncher(displayId, width, height);
                InputRouting.bindAllExternalInputToDisplay(displayId);
            } else {
                clearConfiguredDisplayId();
                forceDisplayInfoQuery(displayId);
                CurrentScreen source = CurrentScreen.detect(State.getContext());
                int sourceDisplayId = source.displayId;
                boolean aspectChanged = MirrorTransformPolicy.prepareAspectRatio(
                        State.getContext(), source, width, height);
                if (aspectChanged) {
                    // Let the newly forced display size settle before the
                    // mirror pipeline starts reading it.
                    Thread.sleep(600);
                }
                boolean presentationStarted = false;
                if (MirrorTransformPolicy.shouldUseSurfacePipeline()) {
                    Display externalDisplay =
                            ExternalDisplayMonitor.getPrimaryExternalDisplay(State.getContext());
                    if (externalDisplay != null) {
                        DpMirrorPresentation mirrorPresentation = DpMirrorPresentation.start(
                                State.getContext(), externalDisplay, source,
                                width, height, refresh);
                        if (mirrorPresentation != null) {
                            activeMirrorPresentation = mirrorPresentation;
                            presentationStarted = true;
                            State.log(TAG + ": DP mirror GL presentation started");
                        } else {
                            State.log(TAG + ": DP mirror GL presentation unavailable, "
                                    + "falling back to display projection");
                        }
                    }
                }
                if (!presentationStarted) {
                    MirrorTransformPolicy.ProjectionGeometry geometry =
                            MirrorTransformPolicy.projectionGeometry(source, width, height);
                    android.view.DisplayInfo sourceInfo =
                            ServiceUtils.getDisplayManager().getDisplayInfo(sourceDisplayId);
                    Rect sourceRect = MirrorTransformPolicy.sourceRect(
                            sourceInfo, source.width, source.height);
                    Rect layerStackRect = MirrorTransformPolicy.layerStackRect(
                            sourceRect, width, height, geometry.orientation, geometry.fill);
                    Rect displayRect = MirrorTransformPolicy.displayRect(
                            sourceRect, width, height, geometry.orientation, geometry.fill);
                    State.log(TAG + ": startDpMirrorWithGeometry source=" + sourceDisplayId
                            + " orientation=" + geometry.orientation
                            + " fill=" + geometry.fill
                            + " layerStack=" + layerStackRect
                            + " display=" + displayRect);
                    int rc = State.userService.startDpMirrorWithGeometry(
                            displayId, sourceDisplayId, width, height,
                            geometry.orientation, layerStackRect, displayRect);
                    State.log(TAG + ": startDpMirrorWithGeometry rc=" + rc);
                    if (rc != 0) {
                        State.showErrorStatus(TAG + ": DP mirror start failed");
                        stop();
                        State.refreshMainActivity();
                        return;
                    }
                }
                activeMirror = true;
            }
            SessionLifecycle.start(State.getContext(), ProjectViaDp.class);
            State.log(TAG + ": DP output started source=" + (dexSource ? "dex" : "mirror")
                    + " display=" + displayId + " " + width + "x" + height);
        } catch (Throwable t) {
            State.log(TAG + ": start failed: " + t);
            stop();
            State.refreshMainActivity();
            return;
        }
        ExternalDisplayMonitor.start(State.getContext(), () -> {
            if (active && activeDisplayId >= 0 && State.externalDisplayId != activeDisplayId) {
                State.log(TAG + ": external display removed, stopping");
                stop();
                State.refreshMainActivity();
            }
        });
        State.refreshMainActivity();
    }

    private static void setConfiguredDisplayId(int displayId) {
        runShell("setprop " + DP_DISPLAY_PROP + " " + displayId);
    }

    private static void clearConfiguredDisplayId() {
        runShell("setprop " + DP_DISPLAY_PROP + " \"\"");
    }

    private static void stopDpMirror() {
        try {
            if (State.userService != null && State.externalDisplayId > 0) {
                int rc = State.userService.stopDpMirror(State.externalDisplayId);
                State.log(TAG + ": stopDpMirror rc=" + rc);
            }
        } catch (Throwable t) {
            State.log(TAG + ": stopDpMirror failed: " + t.getMessage());
        }
    }

    private static void resetDpMirror(int displayId) {
        try {
            if (State.userService != null) {
                int rc = State.userService.resetDpMirror(displayId);
                State.log(TAG + ": resetDpMirror rc=" + rc);
            }
        } catch (Throwable t) {
            State.log(TAG + ": resetDpMirror failed: " + t.getMessage());
        }
    }

    private static void runShell(String command) {
        if (State.userService == null) {
            return;
        }
        try {
            String actual = command;
            if (State.userService.isRooted()) {
                actual = "su -c '" + command.replace("'", "'\\''") + "'";
            }
            String out = State.userService.executeShellCommand(actual);
            State.log(TAG + ": " + command + " -> " + (out == null ? "" : out.trim()));
        } catch (RemoteException e) {
            State.log(TAG + ": shell command failed: " + e.getMessage());
        }
    }

    private static void forceDisplayInfoQuery(int displayId) {
        try {
            android.view.DisplayInfo info = ServiceUtils.getDisplayManager().getDisplayInfo(displayId);
            State.log(TAG + ": forced display info query displayId=" + displayId
                    + " ok=" + (info != null));
        } catch (Throwable t) {
            State.log(TAG + ": display info query failed: " + t.getMessage());
        }
    }
}

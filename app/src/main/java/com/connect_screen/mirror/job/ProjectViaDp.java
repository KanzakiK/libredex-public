package com.connect_screen.mirror.job;

import android.content.Context;
import android.graphics.Rect;
import android.os.RemoteException;
import android.view.Display;

import com.connect_screen.mirror.Pref;
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
    private static final String DP_SESSION_ACTIVE_PROP = "dex.lspmirror.dp_session_active";
    private static final String DP_SETTINGS_KEY = "libredex_dp_display_id";
    private static final String DP_MANAGED_SETTINGS_KEY = "libredex_dp_managed_display";

    private static volatile boolean active;
    private static volatile int activeDisplayId = -1;
    private static volatile boolean activeMirror;
    private static volatile DpMirrorPresentation activeMirrorPresentation;
    private static volatile DpMirrorPresentation plainMirrorPresentation;
    private static volatile long plainMirrorGeneration;

    private final boolean dexSource;

    public ProjectViaDp(boolean dexSource) {
        this.dexSource = dexSource;
    }

    public static boolean isActive() {
        return active;
    }

    public static void stop() {
        stop(true);
    }

    public static void stop(boolean startPlainMirror) {
        active = false;
        if (activeMirror) {
            DpMirrorPresentation mirrorPresentation = activeMirrorPresentation;
            activeMirrorPresentation = null;
            if (mirrorPresentation != null) {
                // Keep the live GL pipeline rendering: dismissing it makes the
                // HDMI sink freeze and only a physical replug recovers it.
                plainMirrorPresentation = mirrorPresentation;
                State.log(TAG + ": keep mirror presentation alive for phone mirror");
            }
        }
        activeMirror = false;
        activeDisplayId = -1;
        clearConfiguredDisplayId();
        if (State.externalDisplayId > 0) {
            // The live GL mirror stays on the external screen. Do not re-project
            // the phone display underneath it or force-stop the launcher here:
            // both paths rebuild the display stack (activity relaunch plus
            // launcher restart), which blanks the Presentation window and
            // resurrects the DeX UI. DeX teardown belongs to the LSPosed hook.
            forceDisplayInfoQuery(State.externalDisplayId);
        }
        if (startPlainMirror && State.externalDisplayId > 0) {
            final int displayId = State.externalDisplayId;
            int[] preferred = resolvePreferredDpSize();
            final int width = preferred[0] > 0 ? preferred[0]
                    : (State.externalDisplayWidth > 0 ? State.externalDisplayWidth : 1920);
            final int height = preferred[1] > 0 ? preferred[1]
                    : (State.externalDisplayHeight > 0 ? State.externalDisplayHeight : 1080);
            final long generation = ++plainMirrorGeneration;
            new Thread(() -> startPlainPhoneMirror(
                    displayId, width, height, generation),
                    TAG + "-plain").start();
        }
        SessionLifecycle.stop(State.getContext(), ProjectViaDp.class);
        ExternalDisplayMonitor.stop();
        Pref.setDpSessionStarted(false);
    }

    private static void startPlainPhoneMirror(
            int displayId, int width, int height, long generation) {
        try {
            if (plainMirrorPresentation != null) {
                State.log(TAG + ": plain phone mirror already alive display="
                        + displayId);
                return;
            }
            Context context = State.getContext();
            if (context == null) {
                return;
            }
            Display externalDisplay =
                    ExternalDisplayMonitor.getPrimaryExternalDisplay(context);
            CurrentScreen source = CurrentScreen.detect(context);
            if (externalDisplay == null || source == null) {
                State.log(TAG + ": plain phone mirror unavailable display=" + displayId);
                return;
            }
            int refresh = 60;
            if (externalDisplay.getMode() != null) {
                refresh = Math.round(externalDisplay.getMode().getRefreshRate());
            }
            DpMirrorPresentation plain = DpMirrorPresentation.startPlain(
                    context, externalDisplay, source, width, height, refresh);
            if (generation != plainMirrorGeneration) {
                if (plain != null) {
                    plain.stop();
                }
                State.log(TAG + ": plain phone mirror superseded display=" + displayId);
                return;
            }
            plainMirrorPresentation = plain;
            State.log(TAG + ": plain phone mirror started display=" + displayId
                    + " ok=" + (plain != null));
        } catch (Throwable t) {
            State.log(TAG + ": plain phone mirror failed: " + t.getMessage());
        }
    }

    private static void stopPlainPhoneMirror() {
        plainMirrorGeneration++;
        DpMirrorPresentation plain = plainMirrorPresentation;
        plainMirrorPresentation = null;
        if (plain != null) {
            plain.stopAndWait();
            State.log(TAG + ": plain phone mirror stopped");
        }
    }

    /**
     * Preferred wired (DP) output mode. When the user has explicitly picked a
     * mode via the resolution dialog, that is used so DeX rendering follows
     * the DP output signal. Otherwise default to 1080p (1920x1080) as a
     * conservative fallback so a 4K panel does not drive a 4K render pipeline.
     * Returns {width, height, refresh}; a 0 refresh means "fall back to the
     * external display's current refresh rate".
     */
    private static int[] resolvePreferredDpSize() {
        int[] mode = Pref.getDpOutputMode();
        if (mode != null && mode.length >= 3 && mode[0] > 0 && mode[1] > 0) {
            return new int[]{mode[0], mode[1], mode[2]};
        }
        // No explicit user choice: conservative 1080p default.
        State.log(TAG + ": no user-chosen DP mode, defaulting render to 1920x1080");
        return new int[]{1920, 1080, 0};
    }

    @Override
    public void start() throws YieldException {
        stopPlainPhoneMirror();
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
        int[] preferred = resolvePreferredDpSize();
        int width = preferred[0];
        int height = preferred[1];
        int refresh = preferred[2];
        boolean usedPreferred = width > 0 && height > 0;
        if (!usedPreferred) {
            // No size at all: fall back to the external panel's reported metrics.
            width = State.externalDisplayWidth;
            height = State.externalDisplayHeight;
            Display display = ExternalDisplayMonitor.getPrimaryExternalDisplay(context);
            if ((width <= 0 || height <= 0) && display != null) {
                width = display.getWidth();
                height = display.getHeight();
            }
        }
        if (width <= 0 || height <= 0) {
            State.showErrorStatus("无法读取外接屏尺寸");
            return;
        }
        if (refresh <= 0) {
            // Preferred/default path left refresh unspecified: use the external
            // display's current refresh rate instead of hard-coding 60Hz.
            Display display = ExternalDisplayMonitor.getPrimaryExternalDisplay(context);
            if (display != null && display.getMode() != null) {
                refresh = Math.round(display.getMode().getRefreshRate());
            }
            if (refresh <= 0) {
                refresh = 60;
            }
        }
        State.log(TAG + ": DP render mode " + width + "x" + height + "@" + refresh
                + "Hz (preferred-mode=" + usedPreferred + ")");

        active = true;
        activeDisplayId = displayId;
        activeMirror = false;
        Pref.setDpSessionStarted(true);
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
            if (dexSource) {
                try {
                    if (!State.userService.isRooted()) {
                        State.log(TAG + ": DeX path needs root UserService, "
                                + "restarting Shizuku as root");
                        if (!AcquireShizuku.fixRootShizuku()) {
                            State.showErrorStatus(
                                    TAG + ": DP DeX 需要以 root 启动 Shizuku");
                            stop();
                            State.refreshMainActivity();
                            return;
                        }
                    }
                } catch (Throwable t) {
                    State.log(TAG + ": root check failed: " + t.getMessage());
                    stop();
                    State.refreshMainActivity();
                    return;
                }
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
                    // Same settle delay used by other output paths before the
                    // mirror starts reading the newly forced display size.
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
        runShell("setprop " + DP_SESSION_ACTIVE_PROP + " 1");
        runShell("setprop " + DP_DISPLAY_PROP + " " + displayId);
        runShell("settings put global " + DP_SETTINGS_KEY + " " + displayId);
        runShell("settings put global " + DP_MANAGED_SETTINGS_KEY + " " + displayId);
    }

    private static void clearConfiguredDisplayId() {
        runShell("setprop " + DP_SESSION_ACTIVE_PROP + " \"\"");
        runShell("setprop " + DP_DISPLAY_PROP + " \"\"");
        runShell("settings delete global " + DP_SETTINGS_KEY);
        runShell("settings delete global " + DP_MANAGED_SETTINGS_KEY);
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
            // executeRootShellCommand falls back to plain shell when su is
            // unavailable; persist.* setprops only succeed through root.
            String out = State.userService.executeRootShellCommand(command);
            if (command.startsWith("setprop")
                    && (out == null
                    || out.contains("Failed to set property")
                    || out.contains("Permission")
                    || !out.contains("__EXIT_CODE=0"))) {
                String rootOut = AcquireShizuku.runRootCommand(command);
                if (rootOut != null) {
                    State.log(TAG + ": app root fallback " + command
                            + " -> " + rootOut.trim());
                }
            }
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

package com.connect_screen.mirror;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.connect_screen.mirror.job.ExternalDisplayMonitor;
import com.connect_screen.mirror.job.ProjectViaDp;
import com.connect_screen.mirror.job.SunshineServer;
import com.connect_screen.mirror.transport.TransportRegistry;

/**
 * Wired-DP auto-switch: when enabled (see Pref#getAutoDexOnHotplug), plugging in
 * a USB-C->HDMI display auto-starts DeX and unplugging auto-exits it.
 *
 * This is intentionally DeX-only: without LibreDeX an unrooted Samsung phone
 * already auto-mirrors on plug, so the only value-add is upgrading that plug
 * event into a real DeX desktop. Calling this with the user-chosen source
 * (mirror) would just re-do what the OS already does, so we always launch DeX.
 *
 * Attach/detach mirror app foreground (MirrorMainActivity onResume/onPause) so a
 * plug in the background does not surprise-start DeX on the external screen.
 */
public final class DpAutoSwitch {
    private static final String TAG = "DpAutoSwitch";
    private static final long DEBOUNCE_MS = 1200L;

    private static Context appContext;
    private static Handler mainHandler;
    private static DisplayManager.DisplayListener displayListener;
    private static boolean attached;
    private static boolean lastHadExternal;
    private static long lastActionAt;

    private DpAutoSwitch() {
    }

    public static void attach(Context context) {
        if (context == null || attached) {
            return;
        }
        appContext = context.getApplicationContext();
        mainHandler = new Handler(Looper.getMainLooper());
        DisplayManager dm = (DisplayManager) appContext
                .getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            return;
        }
        lastHadExternal = hasExternal();
        displayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                onDisplayEvent();
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                onDisplayEvent();
            }

            @Override
            public void onDisplayChanged(int displayId) {
                onDisplayEvent();
            }
        };
        dm.registerDisplayListener(displayListener, mainHandler);
        attached = true;
    }

    public static void detach() {
        if (!attached) {
            return;
        }
        try {
            DisplayManager dm = appContext != null
                    ? (DisplayManager) appContext
                            .getSystemService(Context.DISPLAY_SERVICE)
                    : null;
            if (dm != null && displayListener != null) {
                dm.unregisterDisplayListener(displayListener);
            }
        } catch (Throwable t) {
            State.log(TAG + ": detach failed: " + t.getMessage());
        }
        displayListener = null;
        mainHandler = null;
        attached = false;
    }

    private static void onDisplayEvent() {
        if (!Pref.getAutoDexOnHotplug()) {
            // Keep edge state accurate even while disabled, so a later toggle-on
            // doesn't mistake an already-present display for a fresh plug.
            lastHadExternal = hasExternal();
            return;
        }
        mainHandler.post(() -> {
            long now = SystemClock.uptimeMillis();
            if (now - lastActionAt < DEBOUNCE_MS) {
                return;
            }
            boolean nowExternal = hasExternal();
            if (nowExternal == lastHadExternal) {
                return;
            }
            lastHadExternal = nowExternal;
            lastActionAt = now;
            if (nowExternal) {
                onPlug();
            } else {
                onUnplug();
            }
        });
    }

    private static boolean hasExternal() {
        ExternalDisplayMonitor.refreshState(appContext);
        return State.externalDisplayId > 0;
    }

    private static void onPlug() {
        if (ProjectViaDp.isActive()
                || SunshineServer.isMoonlightSessionActive()
                || TransportRegistry.isOptionalActive()) {
            State.log(TAG + ": plug detected but another output is active, "
                    + "skip auto DeX");
            return;
        }
        State.log(TAG + ": external display plugged, auto-starting DeX");
        State.startNewJob(new ProjectViaDp(true));
    }

    private static void onUnplug() {
        if (ProjectViaDp.isActive()) {
            State.log(TAG + ": external display unplugged, exiting DeX");
            ProjectViaDp.stop();
        }
    }
}
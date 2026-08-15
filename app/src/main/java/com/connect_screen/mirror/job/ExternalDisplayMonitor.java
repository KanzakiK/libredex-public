package com.connect_screen.mirror.job;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import com.connect_screen.mirror.State;

import java.util.ArrayList;
import java.util.List;

/**
 * Watches physical external displays (USB-C DP/HDMI) so the wired DP flow can
 * react to plug/unplug and keep {@link State#externalDisplayId} in sync.
 */
public final class ExternalDisplayMonitor {
    private static final String TAG = "ExternalDisplayMonitor";
    private static final String OWN_VD_NAME_PREFIX = "dex-anywhere-";

    public interface Listener {
        void onExternalDisplayChanged();
    }

    private static final Object LOCK = new Object();
    private static Context appContext;
    private static DisplayManager displayManager;
    private static DisplayManager.DisplayListener displayListener;
    private static Handler mainHandler;
    private static Listener currentListener;
    private static boolean started;

    private ExternalDisplayMonitor() {
    }

    public static List<Display> getExternalDisplays(Context context) {
        List<Display> result = new ArrayList<>();
        DisplayManager dm = getDisplayManager(context);
        if (dm == null) {
            return result;
        }
        int ownVirtualDisplayId = State.getMirrorVirtualDisplayId();
        for (Display display : dm.getDisplays()) {
            if (display == null) {
                continue;
            }
            int displayId = display.getDisplayId();
            if (displayId == Display.DEFAULT_DISPLAY || displayId == ownVirtualDisplayId) {
                continue;
            }
            String name = display.getName();
            if (name != null && name.startsWith(OWN_VD_NAME_PREFIX)) {
                continue;
            }
            result.add(display);
        }
        return result;
    }

    public static Display getPrimaryExternalDisplay(Context context) {
        List<Display> displays = getExternalDisplays(context);
        return displays.isEmpty() ? null : displays.get(0);
    }

    public static void refreshState(Context context) {
        Display display = getPrimaryExternalDisplay(context);
        if (display == null) {
            State.externalDisplayId = -1;
            State.externalDisplayWidth = 0;
            State.externalDisplayHeight = 0;
            return;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        State.externalDisplayId = display.getDisplayId();
        State.externalDisplayWidth = metrics.widthPixels;
        State.externalDisplayHeight = metrics.heightPixels;
        Log.d(TAG, "external display #" + display.getDisplayId() + " " + display.getName()
                + " " + State.externalDisplayWidth + "x" + State.externalDisplayHeight);
    }

    public static void start(Context context, Listener listener) {
        synchronized (LOCK) {
            appContext = context != null ? context.getApplicationContext() : null;
            currentListener = listener;
            if (started) {
                refreshState(appContext);
                return;
            }
            displayManager = getDisplayManager(context);
            if (displayManager == null) {
                return;
            }
            mainHandler = new Handler(Looper.getMainLooper());
            displayListener = new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    onExternalDisplayChanged();
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    onExternalDisplayChanged();
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    onExternalDisplayChanged();
                }
            };
            try {
                displayManager.registerDisplayListener(displayListener, mainHandler);
                started = true;
                refreshState(appContext);
            } catch (RuntimeException e) {
                Log.e(TAG, "registerDisplayListener failed", e);
            }
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            currentListener = null;
            if (displayManager != null && displayListener != null && started) {
                try {
                    displayManager.unregisterDisplayListener(displayListener);
                } catch (RuntimeException e) {
                    Log.e(TAG, "unregisterDisplayListener failed", e);
                }
            }
            displayListener = null;
            mainHandler = null;
            displayManager = null;
            started = false;
        }
    }

    private static void onExternalDisplayChanged() {
        Listener listener;
        synchronized (LOCK) {
            listener = currentListener;
        }
        refreshState(appContext);
        if (listener != null) {
            listener.onExternalDisplayChanged();
        }
    }

    private static DisplayManager getDisplayManager(Context context) {
        if (context == null) {
            return null;
        }
        return (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
    }
}

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
 *
 * Fix for OneUI 8.5 Flip5: DisplayManager.getDisplays() returns [inner(0), cover(1, 748x720), dp(6, 1920x1200)].
 * Old code returned displays.get(0) which was the cover (748x720) so DP was mis-identified
 * as flip cover resolution. Now filters cover displays using the same rule as CurrentScreen
 * (size <= default) and sorts by area to pick the real DP/HDMI display.
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
        Display defaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY);
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
            if (name != null && isOwnVirtualDisplay(name)) {
                continue;
            }
            // OneUI 8.5 Flip5 fix: exclude cover (748x720) which now appears in getDisplays()
            // alongside the real DP display (e.g. 1920x1200 / 1920x1080 / 3840x2160).
            // isCoverDisplay mirrors CurrentScreen logic: cover is strictly smaller than default.
            if (isCoverDisplay(display, defaultDisplay)) {
                continue;
            }
            result.add(display);
        }
        // If multiple externals (e.g. DP + HDMI dongle), prefer largest area so
        // the true high-res panel wins instead of a small side display.
        if (result.size() > 1) {
            result.sort((a, b) -> Long.compare(displayArea(b), displayArea(a)));
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
        // Prefer mode physical size (orientation-independent) then fallback to RealMetrics.
        // getRealMetrics can still be correct but mode is the source of truth for DP.
        int w = modeWidth(display);
        int h = modeHeight(display);
        if (w <= 0 || h <= 0) {
            DisplayMetrics metrics = new DisplayMetrics();
            try {
                display.getRealMetrics(metrics);
                w = metrics.widthPixels;
                h = metrics.heightPixels;
            } catch (Throwable ignored) {
            }
        }
        State.externalDisplayId = display.getDisplayId();
        State.externalDisplayWidth = w;
        State.externalDisplayHeight = h;
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

    // --- helpers mirrored from CurrentScreen (kept local to avoid cross-class coupling) ---

    private static boolean isOwnVirtualDisplay(String name) {
        return name != null && (name.startsWith(OWN_VD_NAME_PREFIX)
                || name.startsWith("LibreDeX")
                || name.startsWith("Moonlight-"));
    }

    private static boolean isCoverDisplay(Display display, Display defaultDisplay) {
        if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
            return false;
        }
        if (isOwnVirtualDisplay(display.getName())) {
            return false;
        }
        // STATE_ON check avoided here because DP can be reported before fully ON;
        // size is the decisive signal on Flip5.
        if (defaultDisplay == null) {
            return false;
        }
        int defaultWidth = modeWidth(defaultDisplay);
        int defaultHeight = modeHeight(defaultDisplay);
        int width = modeWidth(display);
        int height = modeHeight(display);
        if (defaultWidth <= 0 || defaultHeight <= 0 || width <= 0 || height <= 0) {
            return false;
        }
        // Cover is strictly smaller than inner on both axes (or at least one axis with tie on other).
        // DP panels (1920x1200, 1920x1080, 3840x2160) are NOT covered because 1920 > 1080 (default width).
        return width <= defaultWidth && height <= defaultHeight
                && (width < defaultWidth || height < defaultHeight);
    }

    private static long displayArea(Display display) {
        int w = modeWidth(display);
        int h = modeHeight(display);
        if (w <= 0 || h <= 0) {
            DisplayMetrics m = realMetrics(display);
            if (m != null) {
                w = m.widthPixels;
                h = m.heightPixels;
            }
        }
        return (long) Math.max(0, w) * Math.max(0, h);
    }

    private static DisplayMetrics realMetrics(Display display) {
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            return metrics;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int modeWidth(Display display) {
        try {
            Display.Mode mode = display.getMode();
            if (mode != null && mode.getPhysicalWidth() > 0) {
                return mode.getPhysicalWidth();
            }
        } catch (Throwable ignored) {
        }
        DisplayMetrics m = realMetrics(display);
        return m == null ? 0 : m.widthPixels;
    }

    private static int modeHeight(Display display) {
        try {
            Display.Mode mode = display.getMode();
            if (mode != null && mode.getPhysicalHeight() > 0) {
                return mode.getPhysicalHeight();
            }
        } catch (Throwable ignored) {
        }
        DisplayMetrics m = realMetrics(display);
        return m == null ? 0 : m.heightPixels;
    }
}

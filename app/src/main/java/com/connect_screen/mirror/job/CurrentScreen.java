package com.connect_screen.mirror.job;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.DisplayHidden;
import android.view.Surface;

import com.connect_screen.mirror.State;

import dev.rikka.tools.refine.Refine;

/**
 * Unified source-screen detection for the 12-link matrix
 * (connection x dex/mirror x inner/cover). Every link asks which phone screen
 * is in use at start instead of each path hard-coding display 0.
 */
public final class CurrentScreen {
    public final int displayId;
    public final boolean isCover;
    public final int width;
    public final int height;
    public final int rotation;

    private CurrentScreen(int displayId, boolean isCover, int width, int height, int rotation) {
        this.displayId = displayId;
        this.isCover = isCover;
        this.width = width;
        this.height = height;
        this.rotation = rotation;
    }

    public boolean isLandscape() {
        return width > height;
    }

    public static CurrentScreen detect(Context context) {
        if (context == null) {
            context = State.getContext();
        }
        if (context == null) {
            CurrentScreen fallback = fallback();
            logDetection(fallback, -1, -1, -1, -1);
            return fallback;
        }
        DisplayManager displayManager =
                (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) {
            CurrentScreen fallback = fallback();
            logDetection(fallback, -1, -1, -1, -1);
            return fallback;
        }

        Display defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        Display activityDisplay = activityDisplay(context);
        if (isCoverDisplay(activityDisplay, defaultDisplay)) {
            CurrentScreen result = fromDisplay(activityDisplay, true);
            logDetection(result, stateOf(defaultDisplay), stateOf(activityDisplay),
                    activityDisplay == null ? -1 : activityDisplay.getDisplayId(),
                    State.currentScreenDisplayId);
            return result;
        }

        Display cover = findCoverDisplay(displayManager, defaultDisplay);
        int defaultState = stateOf(defaultDisplay);
        if (cover != null && defaultState != Display.STATE_ON) {
            CurrentScreen result = fromDisplay(cover, true);
            logDetection(result, defaultState, stateOf(cover),
                    activityDisplay == null ? -1 : activityDisplay.getDisplayId(),
                    State.currentScreenDisplayId);
            return result;
        }

        if (State.currentScreenDisplayId >= 0) {
            Display cached = displayManager.getDisplay(State.currentScreenDisplayId);
            if (isCoverDisplay(cached, defaultDisplay)) {
                CurrentScreen result = fromDisplay(cached, true);
                logDetection(result, defaultState, stateOf(cached),
                        activityDisplay == null ? -1 : activityDisplay.getDisplayId(),
                        State.currentScreenDisplayId);
                return result;
            }
        }

        // Foldables can report both built-in displays ON while folded. Prefer
        // the activity window's display, then the cover (the visible side).
        if (activityDisplay != null) {
            CurrentScreen result = fromDisplay(activityDisplay,
                    isCoverDisplay(activityDisplay, defaultDisplay));
            logDetection(result, defaultState,
                    activityDisplay == null ? -1 : stateOf(activityDisplay),
                    activityDisplay.getDisplayId(), State.currentScreenDisplayId);
            return result;
        }
        if (cover != null) {
            CurrentScreen result = fromDisplay(cover, true);
            logDetection(result, defaultState, stateOf(cover), -1, State.currentScreenDisplayId);
            return result;
        }
        if (defaultDisplay != null) {
            CurrentScreen result = fromDisplay(defaultDisplay, false);
            logDetection(result, defaultState, -1, -1, State.currentScreenDisplayId);
            return result;
        }
        CurrentScreen fallback = fallback();
        logDetection(fallback, defaultState, -1, -1, State.currentScreenDisplayId);
        return fallback;
    }

    private static void logDetection(CurrentScreen screen, int defaultState, int coverState,
                                     int activityDisplayId, int cachedDisplayId) {
        State.log("[CurrentScreen] detect -> display=" + screen.displayId
                + " cover=" + screen.isCover
                + " size=" + screen.width + "x" + screen.height
                + " defaultState=" + defaultState
                + " coverState=" + coverState
                + " activity=" + activityDisplayId
                + " cached=" + cachedDisplayId);
    }

    public static int detectDisplayId(Context context) {
        return detect(context).displayId;
    }

    public static boolean isCoverActive(Context context) {
        return detect(context).isCover;
    }

    private static Display activityDisplay(Context context) {
        Activity activity = unwrapActivity(context);
        if (activity == null) {
            return null;
        }
        try {
            return activity.getDisplay();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Activity unwrapActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static Display findCoverDisplay(DisplayManager displayManager, Display defaultDisplay) {
        Display best = null;
        int bestArea = Integer.MAX_VALUE;
        for (Display display : displayManager.getDisplays()) {
            if (!isCoverDisplay(display, defaultDisplay)) {
                continue;
            }
            int area = displayWidth(display) * displayHeight(display);
            if (area > 0 && area < bestArea) {
                bestArea = area;
                best = display;
            }
        }
        return best;
    }

    private static boolean isCoverDisplay(Display display, Display defaultDisplay) {
        if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
            return false;
        }
        if (stateOf(display) != Display.STATE_ON) {
            return false;
        }
        String name = display.getName();
        if (name != null && isOwnVirtualDisplay(name)) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int type;
            try {
                DisplayHidden displayHidden = Refine.unsafeCast(display);
                type = displayHidden.getType();
            } catch (Throwable t) {
                type = -1;
            }
            // TYPE_INTERNAL=1, TYPE_EXTERNAL=2, TYPE_UNKNOWN=-1. Samsung reports
            // the Flip5 cover as external to apps; DP/HDMI is filtered by size
            // below, so accepting 1/2 is safe.
            if (type != 1 && type != 2 && type != -1) {
                return false;
            }
        }
        if (defaultDisplay == null) {
            return true;
        }
        int defaultWidth = modeWidth(defaultDisplay);
        int defaultHeight = modeHeight(defaultDisplay);
        int width = modeWidth(display);
        int height = modeHeight(display);
        if (defaultWidth <= 0 || defaultHeight <= 0) {
            return true;
        }
        return width <= defaultWidth && height <= defaultHeight
                && (width < defaultWidth || height < defaultHeight);
    }

    private static boolean isOwnVirtualDisplay(String name) {
        return name.startsWith("dex-anywhere-")
                || name.startsWith("LibreDeX")
                || name.startsWith("Moonlight-");
    }

    private static CurrentScreen fromDisplay(Display display, boolean isCover) {
        return new CurrentScreen(
                display.getDisplayId(),
                isCover,
                displayWidth(display),
                displayHeight(display),
                display.getRotation());
    }

    private static int displayWidth(Display display) {
        DisplayMetrics metrics = realMetrics(display);
        return metrics == null ? 0 : metrics.widthPixels;
    }

    private static int displayHeight(Display display) {
        DisplayMetrics metrics = realMetrics(display);
        return metrics == null ? 0 : metrics.heightPixels;
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
        return displayWidth(display);
    }

    private static int modeHeight(Display display) {
        try {
            Display.Mode mode = display.getMode();
            if (mode != null && mode.getPhysicalHeight() > 0) {
                return mode.getPhysicalHeight();
            }
        } catch (Throwable ignored) {
        }
        return displayHeight(display);
    }

    private static int stateOf(Display display) {
        if (display == null) {
            return Display.STATE_UNKNOWN;
        }
        try {
            return display.getState();
        } catch (Throwable t) {
            return Display.STATE_UNKNOWN;
        }
    }

    private static CurrentScreen fallback() {
        return new CurrentScreen(Display.DEFAULT_DISPLAY, false, 0, 0, Surface.ROTATION_0);
    }
}

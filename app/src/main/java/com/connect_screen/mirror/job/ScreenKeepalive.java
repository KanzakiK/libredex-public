package com.connect_screen.mirror.job;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.connect_screen.mirror.Pref;
import com.connect_screen.mirror.State;
import com.connect_screen.mirror.shizuku.PermissionManager;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mirror-session screen management shared by connections that do not run the
 * Moonlight Sunshine service: prevent auto lock and delayed auto screen off.
 * Screen off itself goes through {@link CreateVirtualDisplay}, which targets
 * the current inner/cover display.
 */
public final class ScreenKeepalive {
    private static final long AUTO_SCREEN_OFF_DELAY_MS = 30_000L;
    private static final long PREVENT_LOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Map<Object, Runnable> AUTO_SCREEN_OFF =
            new ConcurrentHashMap<>();
    private static int savedScreenOffTimeout = -1;

    private ScreenKeepalive() {
    }

    public static void applyPreventAutoLock(Context context) {
        if (context == null || !ShizukuUtils.hasPermission() || Pref.getFakeScreen()) {
            return;
        }
        try {
            if (!PermissionManager.grant("android.permission.WRITE_SECURE_SETTINGS")) {
                return;
            }
            int current = Settings.System.getInt(
                    context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 0);
            if (savedScreenOffTimeout < 0) {
                savedScreenOffTimeout = current;
                State.log("[ScreenKeepalive] saved screen-off timeout " + current + "ms");
            }
            if (current >= PREVENT_LOCK_TIMEOUT_MS) {
                return;
            }
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, (int) PREVENT_LOCK_TIMEOUT_MS);
            State.log("[ScreenKeepalive] screen-off timeout set to 4h");
        } catch (Throwable t) {
            State.log("[ScreenKeepalive] prevent auto lock failed: " + t.getMessage());
        }
    }

    public static void restorePreventAutoLock(Context context) {
        if (context == null || savedScreenOffTimeout < 0) {
            return;
        }
        try {
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, savedScreenOffTimeout);
            State.log("[ScreenKeepalive] screen-off timeout restored to "
                    + savedScreenOffTimeout + "ms");
        } catch (Throwable t) {
            State.log("[ScreenKeepalive] restore timeout failed: " + t.getMessage());
        }
        savedScreenOffTimeout = -1;
    }

    public static void scheduleAutoScreenOff(Context context, Object token) {
        cancelAutoScreenOff(token);
        if (!Pref.getAutoScreenOff()) {
            return;
        }
        Runnable runnable = () -> {
            AUTO_SCREEN_OFF.remove(token);
            if (context == null || !Pref.getAutoScreenOff()) {
                return;
            }
            State.log("[ScreenKeepalive] auto screen off after 30s");
            CreateVirtualDisplay.doPowerOffScreen(context);
        };
        AUTO_SCREEN_OFF.put(token, runnable);
        MAIN_HANDLER.postDelayed(runnable, AUTO_SCREEN_OFF_DELAY_MS);
    }

    public static void cancelAutoScreenOff(Object token) {
        Runnable runnable = AUTO_SCREEN_OFF.remove(token);
        if (runnable != null) {
            MAIN_HANDLER.removeCallbacks(runnable);
        }
    }
}

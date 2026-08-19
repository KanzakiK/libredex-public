package com.dex.lspmirror;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.FileObserver;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public final class DexWallpaperHook implements IXposedHookLoadPackage {
    private static final String TAG = "DexWallpaper";
    private static final String SECONDARY_LAUNCHER =
            "com.honeyspace.dexservice.SecondaryLauncher";
    private static final String WALLPAPER_PATH =
            "/data/user/0/com.sec.android.app.launcher/files/dex_wallpaper.png";
    private static final String WALLPAPER_DIR =
            "/data/user/0/com.sec.android.app.launcher/files";
    private static Activity lastActivity;
    private static FileObserver wallpaperObserver;
    private static long lastApplyElapsed;
    private static long lastAppliedMtime;
    // Cap the missing-wallpaper retry burst so a missing/deferred wallpaper
    // PNG cannot grow into an unbounded self-multiplying relayout/log storm that
    // pegs the launcher main thread (saw 97% CPU + ANR on One UI 8.5).
    private static int missingRetries;
    private static final int MAX_MISSING_RETRIES = 6;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!"com.sec.android.app.launcher".equals(lpparam.packageName)) {
            return;
        }
        try {
            Class<?> secondary = XposedHelpers.findClass(
                    SECONDARY_LAUNCHER, lpparam.classLoader);
            XposedBridge.hookMethod(
                    secondary.getMethod("onCreate", Bundle.class),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyWallpaper((Activity) param.thisObject, "create");
                        }
                    });
            Class<?> activityClass = XposedHelpers.findClass(
                    "android.app.Activity", lpparam.classLoader);
            java.lang.reflect.Method onResume =
                    activityClass.getDeclaredMethod("onResume");
            onResume.setAccessible(true);
            XposedBridge.hookMethod(onResume,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                                if (param.thisObject != null
                                        && SECONDARY_LAUNCHER.equals(
                                        param.thisObject.getClass().getName())) {
                                applyWallpaper((Activity) param.thisObject, "resume");
                            }
                        }
                    });
            if (wallpaperObserver == null) {
                wallpaperObserver = new FileObserver(WALLPAPER_DIR,
                        FileObserver.CLOSE_WRITE | FileObserver.CREATE
                                | FileObserver.MODIFY | FileObserver.MOVED_TO) {
                    @Override
                    public void onEvent(int event, String path) {
                        if ("dex_wallpaper.png".equals(path) && lastActivity != null) {
                            Activity activity = lastActivity;
                            activity.runOnUiThread(() -> applyWallpaper(activity, "watcher"));
                        }
                    }
                };
                wallpaperObserver.startWatching();
            }
            XposedBridge.log(TAG + ": launcher wallpaper hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook setup failed: " + t);
        }
    }

    private static void applyWallpaper(Activity activity, String source) {
        applyWallpaperInternal(activity, source, false);
    }

    private static void applyWallpaperInternal(
            Activity activity, String source, boolean bypass) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!bypass && now - lastApplyElapsed < 300) {
            return;
        }
        lastApplyElapsed = now;
        lastActivity = activity;
        try {
            File file = new File(WALLPAPER_PATH);
            long mtime = file.lastModified();
            if (!bypass && "watcher".equals(source) && mtime == lastAppliedMtime) {
                return;
            }
            Bitmap bitmap = file.exists()
                    ? BitmapFactory.decodeFile(WALLPAPER_PATH)
                    : null;
            View decor = activity.getWindow().getDecorView();
            if (bitmap != null) {
                missingRetries = 0;
                decor.setBackground(new BitmapDrawable(
                        activity.getResources(), bitmap));
                lastAppliedMtime = mtime;
                if (!"refresh".equals(source)) {
                    XposedBridge.log(TAG + ": wallpaper applied display="
                            + displayIdOf(activity) + " source=" + source);
                }
            } else {
                // Missing/deferred wallpaper: teal once, then retry with a hard
                // cap. Do not force-redraw or dump the view tree on retries —
                // that work is exactly what saturated the launcher main thread
                // (97% CPU + ANR on One UI 8.5) when the retry grew unbounded.
                if (!"retry".equals(source)) {
                    decor.setBackgroundColor(0xFF1B7F79);
                    XposedBridge.log(TAG + ": teal fallback display="
                            + displayIdOf(activity) + " source=" + source);
                }
                if (missingRetries < MAX_MISSING_RETRIES) {
                    missingRetries++;
                    View d = decor;
                    long[] timings = {250L, 600L, 1200L};
                    for (long t : timings) {
                        d.postDelayed(
                                () -> applyWallpaperInternal(activity, "retry", true), t);
                    }
                }
                return; // skip forceRedraw / logBottomViews during missing fallback
            }
            clearHotseatBackground(decor);
            if (!"refresh".equals(source)) {
                logBottomViews(activity);
            }
            forceRedraw(decor);
            if (bitmap != null && !"refresh".equals(source)) {
                View d = decor;
                d.postDelayed(
                        () -> applyWallpaperInternal(activity, "refresh", true), 150L);
                d.postDelayed(
                        () -> applyWallpaperInternal(activity, "refresh", true), 350L);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": apply failed: " + t);
        }
    }

    private static void forceRedraw(View decor) {
        try {
            decor.invalidate();
            decor.requestLayout();
            if (decor instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) decor;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child != null) {
                        child.invalidate();
                        child.requestLayout();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void clearHotseatBackground(View decor) {
        try {
            clearHotseatBackgroundRecursive(decor, 0);
        } catch (Throwable ignored) {
        }
    }

    private static void clearHotseatBackgroundRecursive(View view, int depth) {
        if (view == null || depth > 24) {
            return;
        }
        String cls = view.getClass().getName().toLowerCase();
        if (cls.contains("hotseat") || cls.contains("hotseatcell")) {
            if (view.getBackground() != null) {
                view.setBackground(null);
                XposedBridge.log(TAG + ": hotseat background cleared "
                        + view.getClass().getName());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                clearHotseatBackgroundRecursive(group.getChildAt(i), depth + 1);
            }
        }
    }

    private static void logBottomViews(Activity activity) {
        try {
            final View decor = activity.getWindow().getDecorView();
            decor.post(() -> {
                StringBuilder sb = new StringBuilder();
                collectBottomViews(decor, sb, 0, 0);
                if (sb.length() > 0) {
                    XposedBridge.log(TAG + ": bottom views " + sb);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void collectBottomViews(
            View view, StringBuilder sb, int depth, int count) {
        if (view == null || depth > 24 || count >= 25) {
            return;
        }
        int h = view.getHeight();
        int bottom = view.getBottom();
        String cls = view.getClass().getName().toLowerCase();
        boolean hot = cls.contains("hotseat")
                || cls.contains("dock")
                || cls.contains("taskbar")
                || cls.contains("bottom");
        boolean inBottom = h > 0 && bottom >= h * 3 / 4;
        if (hot || inBottom) {
            sb.append('[').append(view.getClass().getName())
                    .append(" b=").append(view.getLeft()).append(',')
                    .append(view.getTop()).append('-')
                    .append(view.getRight()).append(',')
                    .append(view.getBottom())
                    .append(" bg=").append(view.getBackground() != null)
                    .append("] ");
            count++;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectBottomViews(group.getChildAt(i), sb, depth + 1, count);
            }
        }
    }

    private static int displayIdOf(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor != null && decor.getDisplay() != null) {
                return decor.getDisplay().getDisplayId();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}

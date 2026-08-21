package com.connect_screen.mirror;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.widget.Toast;

import com.connect_screen.mirror.job.SunshineServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

public final class DexWallpaper {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private DexWallpaper() {
    }

    public static void applyPickedWallpaper(Context context, Uri uri) {
        applyPickedWallpaper(context, uri, SunshineServer.activeDexDisplayId);
    }

    public static void applyPickedWallpaper(Context context, Uri uri, int displayId) {
        new Thread(() -> {
            try {
                int[] size = dexDisplaySize(context, displayId);
                Bitmap bitmap = decodeCenterCrop(context, uri, size[0], size[1]);
                if (bitmap == null) {
                    State.log("wallpaper decode failed uri=" + uri);
                    return;
                }
                State.log("wallpaper decoded " + bitmap.getWidth() + "x" + bitmap.getHeight());
                trySetDexWallpaper(context, bitmap, displayId);
            } catch (Throwable t) {
                State.log("wallpaper picker apply failed uri=" + uri + " err=" + t);
            }
        }).start();
    }

    private static int[] dexDisplaySize(Context context, int displayId) {
        try {
            DisplayManager displayManager =
                    (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display display = displayManager.getDisplay(displayId);
            if (display != null) {
                Point size = new Point();
                display.getRealSize(size);
                if (size.x > 0 && size.y > 0) {
                    return new int[]{size.x, size.y};
                }
            }
        } catch (Throwable ignored) {
        }
        return new int[]{1920, 1080};
    }

    private static Bitmap decodeCenterCrop(Context context, Uri uri, int targetW, int targetH) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, bounds);
        } catch (Throwable t) {
            State.log("wallpaper bounds read failed: " + t);
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetW
                && bounds.outHeight / (sample * 2) >= targetH) {
            sample *= 2;
        }
        State.log("wallpaper bounds " + bounds.outWidth + "x" + bounds.outHeight
                + " target " + targetW + "x" + targetH + " sample=" + sample);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap src;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            src = BitmapFactory.decodeStream(is, null, opts);
        } catch (Throwable t) {
            State.log("wallpaper decode stream failed: " + t);
            return null;
        }
        if (src == null) {
            State.log("wallpaper sampled decode returned null");
            return null;
        }
        float scale = Math.max(
                (float) targetW / src.getWidth(),
                (float) targetH / src.getHeight());
        int sw = Math.round(src.getWidth() * scale);
        int sh = Math.round(src.getHeight() * scale);
        Bitmap scaled = Bitmap.createScaledBitmap(src, sw, sh, true);
        if (scaled != src) {
            src.recycle();
        }
        int x = Math.max(0, (sw - targetW) / 2);
        int y = Math.max(0, (sh - targetH) / 2);
        int cw = Math.min(targetW, scaled.getWidth());
        int ch = Math.min(targetH, scaled.getHeight());
        Bitmap cropped = Bitmap.createBitmap(scaled, x, y, cw, ch);
        if (cropped != scaled) {
            scaled.recycle();
        }
        return cropped;
    }

    private static void trySetDexWallpaper(Context context, Bitmap bitmap, int displayId) {
        if (displayId < 0) {
            toast(context, context.getString(R.string.dex_wallpaper_connect_dex));
            return;
        }
        DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display display = displayManager.getDisplay(displayId);
        if (display == null) {
            toast(context, context.getString(R.string.dex_wallpaper_display_missing));
            return;
        }
        Context displayContext = context.createDisplayContext(display);
        new Thread(() -> {
            try {
                android.app.WallpaperManager wallpaperManager =
                        android.app.WallpaperManager.getInstance(displayContext);
                Method setBitmap = android.app.WallpaperManager.class.getMethod(
                        "setBitmap", Bitmap.class, Rect.class, boolean.class, int.class);
                int semFlagDex = android.app.WallpaperManager.class.getField("SEM_FLAG_DEX").getInt(null);
                int which = android.app.WallpaperManager.FLAG_SYSTEM | semFlagDex;
                setBitmap.invoke(wallpaperManager, bitmap, null, false, which);
                State.log("DeX wallpaper setBitmap OK displayId=" + displayId + " which=" + which);
                writeWallpaperFile(context, bitmap);
                toast(context, context.getString(R.string.dex_wallpaper_changed));
            } catch (Throwable e) {
                State.log("DeX wallpaper setBitmap failed: " + e);
                tryDexManagerWallpaper(context, bitmap);
            }
        }).start();
    }

    private static void writeWallpaperFile(Context context, Bitmap bitmap) {
        try {
            File preview = new File(context.getFilesDir(), "dex_wallpaper_preview.png");
            FileOutputStream fos = new FileOutputStream(preview);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            State.log("wallpaper preview file written");
        } catch (Throwable t) {
            State.log("wallpaper preview file write failed: " + t);
        }
        try {
            if (State.userService != null && State.userService.writeDexWallpaper(bitmap)) {
                State.log("launcher wallpaper file written");
            } else {
                State.log("launcher wallpaper file write skipped/failed");
            }
        } catch (Throwable t) {
            State.log("launcher wallpaper file write error: " + t);
        }
    }

    private static void tryDexManagerWallpaper(Context context, Bitmap bitmap) {
        try {
            Class<?> dexManagerClass = Class.forName("com.samsung.android.knox.dex.DexManager");
            Object instance = dexManagerClass.getMethod("getInstance").invoke(null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            InputStream inputStream = new ByteArrayInputStream(baos.toByteArray());
            Method setWallpaper = dexManagerClass.getMethod(
                    "setWallpaper", Context.class, InputStream.class, Rect.class, boolean.class, int.class);
            int rc = (Integer) setWallpaper.invoke(instance, context, inputStream, null, false, 1);
            State.log("DexManager.setWallpaper rc=" + rc);
            toast(context, "DexManager rc=" + rc);
        } catch (Throwable e) {
            State.log("DexManager.setWallpaper failed: " + e);
            toast(context, "DexManager failed: " + e.getMessage());
        }
    }

    private static void toast(Context context, String message) {
        MAIN_HANDLER.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}

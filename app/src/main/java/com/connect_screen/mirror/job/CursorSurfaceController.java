package com.connect_screen.mirror.job;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;

import com.connect_screen.mirror.R;
import com.connect_screen.mirror.State;

/** App-owned pointer window on the active DeX display. */
public final class CursorSurfaceController {
    private static final String TAG = "LibreDeX cursor";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final float HOTSPOT_X_RATIO = 4.5f / 48f;
    private static final float HOTSPOT_Y_RATIO = 2f / 48f;

    private static WindowManager windowManager;
    private static ImageView view;
    private static WindowManager.LayoutParams params;
    private static int attachedDisplayId = -1;
    private static int hotspotX;
    private static int hotspotY;
    private static float lastX;
    private static float lastY;

    private CursorSurfaceController() {
    }

    public static void attach(int displayId) {
        if (displayId < 0) {
            return;
        }
        MAIN.post(() -> attachOnMain(displayId));
    }

    public static void move(float x, float y) {
        lastX = x;
        lastY = y;
        MAIN.post(CursorSurfaceController::moveOnMain);
    }

    public static void followSystemPointer(int displayId) {
        attach(displayId);
    }

    public static void detach() {
        MAIN.post(CursorSurfaceController::detachOnMain);
    }

    private static void attachOnMain(int displayId) {
        if (view != null && attachedDisplayId == displayId) {
            return;
        }
        detachOnMain();
        Context context = State.getContext();
        if (context == null) {
            State.log(TAG + ": attach skipped, no context");
            return;
        }
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display display = manager == null ? null : manager.getDisplay(displayId);
        if (display == null) {
            State.log(TAG + ": attach skipped, display " + displayId + " unavailable");
            return;
        }
        Context displayContext = context.createDisplayContext(display);
        WindowManager wm = (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return;
        }
        int size = Math.max(24, Math.round(32f * displayContext.getResources().getDisplayMetrics().density));
        hotspotX = Math.round(size * HOTSPOT_X_RATIO);
        hotspotY = Math.round(size * HOTSPOT_Y_RATIO);

        ImageView cursor = new ImageView(displayContext);
        cursor.setImageBitmap(BitmapFactory.decodeResource(
                displayContext.getResources(), R.drawable.mouse_cursor));
        cursor.setScaleType(ImageView.ScaleType.FIT_START);

        int[] types = {2018, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY};
        for (int type : types) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    size, size, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = Math.round(lastX) - hotspotX;
            lp.y = Math.round(lastY) - hotspotY;
            try {
                wm.addView(cursor, lp);
                windowManager = wm;
                view = cursor;
                params = lp;
                attachedDisplayId = displayId;
                State.log(TAG + ": attached display=" + displayId
                        + " size=" + size + " type=" + type);
                return;
            } catch (Throwable t) {
                State.log(TAG + ": type=" + type + " failed: " + t.getMessage());
            }
        }
    }

    private static void moveOnMain() {
        if (view == null || windowManager == null || params == null) {
            return;
        }
        params.x = Math.round(lastX) - hotspotX;
        params.y = Math.round(lastY) - hotspotY;
        try {
            windowManager.updateViewLayout(view, params);
        } catch (Throwable t) {
            State.log(TAG + ": move failed: " + t.getMessage());
        }
    }

    private static void detachOnMain() {
        if (view != null && windowManager != null) {
            try {
                windowManager.removeView(view);
            } catch (Throwable ignored) {
            }
        }
        windowManager = null;
        view = null;
        params = null;
        attachedDisplayId = -1;
    }
}

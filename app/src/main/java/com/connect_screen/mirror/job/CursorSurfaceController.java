package com.connect_screen.mirror.job;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.hardware.display.DisplayManager;
import android.hardware.input.IInputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;

import com.connect_screen.mirror.R;
import com.connect_screen.mirror.State;
import com.connect_screen.mirror.shizuku.ServiceUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Draws one pointer SurfaceControl directly on the active DeX display layer
 * stack. It is deliberately not a WindowManager window: WMS ordering can put
 * DeX taskbar/home layers above the pointer in both capture and DP projection.
 */
@SuppressLint("PrivateApi")
public final class CursorSurfaceController {
    private static final String TAG = "LibreDeX cursor";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int CURSOR_LAYER = Integer.MAX_VALUE - 1;
    private static final float HOTSPOT_X_RATIO = 4.5f / 48f;
    private static final float HOTSPOT_Y_RATIO = 2f / 48f;

    private static Class<?> surfaceControlClass;
    private static Class<?> builderClass;
    private static Class<?> transactionClass;
    private static Method setName;
    private static Method setBufferSize;
    private static Method setFormat;
    private static Method setHidden;
    private static Method setParent;
    private static Method build;
    private static Method setLayer;
    private static Method setLayerStack;
    private static Method setPosition;
    private static Method setVisibility;
    private static Method apply;
    private static Constructor<Surface> surfaceConstructor;

    private static Object cursorControl;
    private static Surface cursorSurface;
    private static int attachedDisplayId = -1;
    private static int attachedLayerStack = -1;
    private static int hiddenPointerDisplayId = -1;
    private static int hotspotX;
    private static int hotspotY;
    private static float lastX;
    private static float lastY;
    private static boolean followSystem;
    private static boolean pollScheduled;
    private static int moveLogCount;

    private static final Runnable SYSTEM_CURSOR_POLL = CursorSurfaceController::pollSystemCursor;

    private static void pollSystemCursor() {
        if (!followSystem || attachedDisplayId < 0 || cursorControl == null) {
            pollScheduled = false;
            return;
        }
        try {
            IInputManager inputManager = ServiceUtils.getInputManager();
            PointF point = inputManager == null ? null : inputManager.getCursorPosition();
            if (point != null) {
                moveOnMain(point.x, point.y);
            }
        } catch (Throwable t) {
            State.log(TAG + ": getCursorPosition failed: " + t.getMessage());
        }
        MAIN.postDelayed(SYSTEM_CURSOR_POLL, 16);
    }

    private CursorSurfaceController() {
    }

    public static void attach(int displayId) {
        MAIN.post(() -> attachOnMain(displayId));
    }

    /** Moonlight/virtual touchpad position: stop following the system pointer. */
    public static void move(float x, float y) {
        MAIN.post(() -> {
            followSystem = false;
            MAIN.removeCallbacks(SYSTEM_CURSOR_POLL);
            pollScheduled = false;
            moveOnMain(x, y);
            if (moveLogCount < 5) {
                moveLogCount++;
                State.log(TAG + ": move x=" + x + " y=" + y
                        + " display=" + attachedDisplayId + " control=" + (cursorControl != null));
            }
        });
    }

    /** Physical mouse on DP: follow the system pointer position. */
    public static void followSystemPointer(int displayId) {
        MAIN.post(() -> {
            attachOnMain(displayId);
            if (cursorControl == null) {
                return;
            }
            followSystem = true;
            if (!pollScheduled) {
                pollScheduled = true;
                MAIN.postDelayed(SYSTEM_CURSOR_POLL, 16);
            }
        });
    }

    public static void detach() {
        MAIN.post(CursorSurfaceController::detachOnMain);
    }

    private static void attachOnMain(int displayId) {
        if (displayId < 0) {
            return;
        }
        int layerStack = resolveLayerStack(displayId);
        if (layerStack < 0) {
            State.log(TAG + ": cannot resolve layerStack for display " + displayId);
            return;
        }
        if (cursorControl != null && attachedDisplayId == displayId
                && attachedLayerStack == layerStack) {
            setVisibleOnMain(true);
            return;
        }
        releaseOnMain(false);
        if (!initReflection() || !createOnMain(displayId, layerStack)) {
            return;
        }
        attachedDisplayId = displayId;
        attachedLayerStack = layerStack;
        moveLogCount = 0;
        // Some Samsung builds do not expose IInputManager.setPointerIconVisible().
        // That must not prevent our own topmost cursor from being drawn: the
        // system Sprite may be excluded from Moonlight capture even when visible.
        boolean nativePointerHidden = hideNativePointer(displayId);
        State.log(TAG + ": top surface shown display=" + displayId
                + " layerStack=" + layerStack + " layer=" + CURSOR_LAYER
                + " nativePointerHidden=" + nativePointerHidden);
    }

    private static Object resolveParentControl(int displayId) {
        android.view.IWindowManager windowManager = ServiceUtils.getWindowManager();
        if (windowManager == null) {
            return null;
        }
        String[] names = {"getA11yOverlayLayer", "getHandwritingSurfaceForDisplay"};
        for (String name : names) {
            try {
                Method method = windowManager.getClass().getMethod(name, int.class);
                Object parent = method.invoke(windowManager, displayId);
                if (parent != null) {
                    State.log(TAG + ": parent=" + name + " display=" + displayId);
                    return parent;
                }
            } catch (Throwable t) {
                State.log(TAG + ": " + name + " unavailable: " + t.getMessage());
            }
        }
        return null;
    }

    private static boolean initReflection() {
        if (surfaceControlClass != null) {
            return true;
        }
        try {
            surfaceControlClass = Class.forName("android.view.SurfaceControl");
            builderClass = Class.forName("android.view.SurfaceControl$Builder");
            transactionClass = Class.forName("android.view.SurfaceControl$Transaction");

            setName = builderClass.getMethod("setName", String.class);
            setBufferSize = builderClass.getMethod("setBufferSize", int.class, int.class);
            setFormat = builderClass.getMethod("setFormat", int.class);
            setHidden = builderClass.getMethod("setHidden", boolean.class);
            setParent = builderClass.getMethod("setParent", surfaceControlClass);
            build = builderClass.getMethod("build");

            setLayer = transactionClass.getMethod("setLayer", surfaceControlClass, int.class);
            setLayerStack = transactionClass.getMethod("setLayerStack", surfaceControlClass, int.class);
            setPosition = transactionClass.getMethod(
                    "setPosition", surfaceControlClass, float.class, float.class);
            setVisibility = transactionClass.getMethod(
                    "setVisibility", surfaceControlClass, boolean.class);
            apply = transactionClass.getMethod("apply");

            surfaceConstructor = Surface.class.getDeclaredConstructor(surfaceControlClass);
            surfaceConstructor.setAccessible(true);
            return true;
        } catch (Throwable t) {
            State.log(TAG + ": reflection init failed: " + t);
            return false;
        }
    }

    private static boolean createOnMain(int displayId, int layerStack) {
        Context context = State.getContext();
        DisplayManager displayManager = context == null ? null
                : (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display display = displayManager == null ? null : displayManager.getDisplay(displayId);
        if (context == null || display == null) {
            return false;
        }
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            int size = Math.max(24, Math.round(32f * metrics.density));
            hotspotX = Math.round(size * HOTSPOT_X_RATIO);
            hotspotY = Math.round(size * HOTSPOT_Y_RATIO);

            Object parent = resolveParentControl(displayId);
            Object builder = builderClass.getDeclaredConstructor().newInstance();
            setName.invoke(builder, "libredex-cursor");
            setBufferSize.invoke(builder, size, size);
            setFormat.invoke(builder, PixelFormat.TRANSLUCENT);
            setHidden.invoke(builder, true);
            if (parent != null) {
                setParent.invoke(builder, parent);
            } else {
                setParent.invoke(builder, new Object[]{null});
            }
            cursorControl = build.invoke(builder);
            cursorSurface = surfaceConstructor.newInstance(cursorControl);
            drawCursor(context, size);

            Object transaction = transactionClass.getDeclaredConstructor().newInstance();
            setLayer.invoke(transaction, cursorControl, CURSOR_LAYER);
            setLayerStack.invoke(transaction, cursorControl, layerStack);
            setPosition.invoke(transaction, cursorControl,
                    lastX - hotspotX, lastY - hotspotY);
            setVisibility.invoke(transaction, cursorControl, true);
            apply.invoke(transaction);
            return true;
        } catch (Throwable t) {
            State.log(TAG + ": create failed: " + t);
            releaseOnMain(false);
            return false;
        }
    }

    private static void drawCursor(Context context, int size) {
        Bitmap source = BitmapFactory.decodeResource(context.getResources(), R.drawable.mouse_cursor);
        if (source == null) {
            throw new IllegalStateException("mouse_cursor bitmap missing");
        }
        Bitmap scaled = Bitmap.createScaledBitmap(source, size, size, true);
        Canvas canvas = cursorSurface.lockCanvas(null);
        try {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setFilterBitmap(true);
            canvas.drawBitmap(scaled, 0f, 0f, paint);
        } finally {
            cursorSurface.unlockCanvasAndPost(canvas);
            scaled.recycle();
            source.recycle();
        }
    }

    private static void moveOnMain(float x, float y) {
        lastX = x;
        lastY = y;
        if (cursorControl == null) {
            return;
        }
        try {
            Object transaction = transactionClass.getDeclaredConstructor().newInstance();
            setPosition.invoke(transaction, cursorControl, x - hotspotX, y - hotspotY);
            apply.invoke(transaction);
        } catch (Throwable t) {
            State.log(TAG + ": move failed: " + t.getMessage());
        }
    }

    private static void setVisibleOnMain(boolean visible) {
        if (cursorControl == null) {
            return;
        }
        try {
            Object transaction = transactionClass.getDeclaredConstructor().newInstance();
            setLayer.invoke(transaction, cursorControl, CURSOR_LAYER);
            setLayerStack.invoke(transaction, cursorControl, attachedLayerStack);
            setPosition.invoke(transaction, cursorControl, lastX - hotspotX, lastY - hotspotY);
            setVisibility.invoke(transaction, cursorControl, visible);
            apply.invoke(transaction);
        } catch (Throwable t) {
            State.log(TAG + ": visibility failed: " + t.getMessage());
        }
    }

    private static boolean hideNativePointer(int displayId) {
        IInputManager inputManager = ServiceUtils.getInputManager();
        if (inputManager == null) {
            return false;
        }
        try {
            inputManager.setPointerIconVisible(false, displayId);
            hiddenPointerDisplayId = displayId;
            return true;
        } catch (Throwable t) {
            State.log(TAG + ": setPointerIconVisible(false) failed: " + t.getMessage());
            return false;
        }
    }

    private static void restoreNativePointer() {
        if (hiddenPointerDisplayId < 0) {
            return;
        }
        IInputManager inputManager = ServiceUtils.getInputManager();
        if (inputManager != null) {
            try {
                inputManager.setPointerIconVisible(true, hiddenPointerDisplayId);
            } catch (Throwable t) {
                State.log(TAG + ": setPointerIconVisible(true) failed: " + t.getMessage());
            }
        }
        hiddenPointerDisplayId = -1;
    }

    private static void detachOnMain() {
        followSystem = false;
        MAIN.removeCallbacks(SYSTEM_CURSOR_POLL);
        pollScheduled = false;
        releaseOnMain(true);
        attachedDisplayId = -1;
        attachedLayerStack = -1;
        moveLogCount = 0;
        State.log(TAG + ": detached");
    }

    private static void releaseOnMain(boolean restorePointer) {
        if (cursorControl != null && surfaceControlClass != null && transactionClass != null) {
            try {
                Object transaction = transactionClass.getDeclaredConstructor().newInstance();
                setVisibility.invoke(transaction, cursorControl, false);
                apply.invoke(transaction);
            } catch (Throwable ignored) {
            }
        }
        if (cursorSurface != null) {
            try {
                cursorSurface.release();
            } catch (Throwable ignored) {
            }
            cursorSurface = null;
        }
        if (cursorControl != null && surfaceControlClass != null) {
            try {
                Method release = surfaceControlClass.getMethod("release");
                release.invoke(cursorControl);
            } catch (Throwable ignored) {
            }
            cursorControl = null;
        }
        if (restorePointer) {
            restoreNativePointer();
        }
    }

    private static int resolveLayerStack(int displayId) {
        try {
            Class<?> managerClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object manager = managerClass.getMethod("getInstance").invoke(null);
            Object info = managerClass.getMethod("getDisplayInfo", int.class)
                    .invoke(manager, displayId);
            if (info == null) {
                return -1;
            }
            Field field;
            try {
                field = info.getClass().getField("layerStack");
            } catch (NoSuchFieldException ignored) {
                field = info.getClass().getDeclaredField("layerStack");
                field.setAccessible(true);
            }
            return field.getInt(info);
        } catch (Throwable t) {
            State.log(TAG + ": resolveLayerStack failed: " + t.getMessage());
            return -1;
        }
    }
}

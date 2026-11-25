package com.connect_screen.mirror.job;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.Parcel;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;

import com.connect_screen.mirror.R;
import com.connect_screen.mirror.State;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/** Draws the DeX pointer on its own SurfaceControl, parented under WMS overlay layer. */
@SuppressLint("PrivateApi")
public final class CursorSurfaceController {
    private static final String TAG = "LibreDeX cursor";
    private static final String PARENT_SERVICE = "libredex.cursor";
    private static final int PARENT_TRANSACTION = 1;
    private static final int CURSOR_LAYER = Integer.MAX_VALUE - 1;
    private static final float HOTSPOT_X_RATIO = 4.5f / 48f;
    private static final float HOTSPOT_Y_RATIO = 2f / 48f;

    private static Class<?> scClass;
    private static Class<?> builderClass;
    private static Class<?> txClass;
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
    private static Constructor<Surface> surfaceCtor;

    private static Object control;
    private static Surface surface;
    private static int attachedDisplayId = -1;
    private static int layerStack = -1;
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
        if (control != null && attachedDisplayId == displayId) {
            setVisible(true);
            return;
        }
        release(false);
        if (!initReflection()) {
            return;
        }
        Object parent = resolveParent(displayId);
        if (parent == null) {
            State.log(TAG + ": parent unavailable for display=" + displayId);
            return;
        }
        if (!create(displayId, parent)) {
            return;
        }
        attachedDisplayId = displayId;
        State.log(TAG + ": attached display=" + displayId + " layerStack=" + layerStack);
    }

    public static void move(float x, float y) {
        lastX = x;
        lastY = y;
        setPosition(x, y);
    }

    public static void followSystemPointer(int displayId) {
        attach(displayId);
    }

    public static void detach() {
        release(true);
        attachedDisplayId = -1;
        State.log(TAG + ": detached");
    }

    private static boolean initReflection() {
        if (scClass != null) {
            return true;
        }
        try {
            scClass = Class.forName("android.view.SurfaceControl");
            builderClass = Class.forName("android.view.SurfaceControl$Builder");
            txClass = Class.forName("android.view.SurfaceControl$Transaction");
            setName = builderClass.getMethod("setName", String.class);
            setBufferSize = builderClass.getMethod("setBufferSize", int.class, int.class);
            setFormat = builderClass.getMethod("setFormat", int.class);
            setHidden = builderClass.getMethod("setHidden", boolean.class);
            setParent = builderClass.getMethod("setParent", scClass);
            build = builderClass.getMethod("build");
            setLayer = txClass.getMethod("setLayer", scClass, int.class);
            setLayerStack = txClass.getMethod("setLayerStack", scClass, int.class);
            setPosition = txClass.getMethod("setPosition", scClass, float.class, float.class);
            setVisibility = txClass.getMethod("setVisibility", scClass, boolean.class);
            apply = txClass.getMethod("apply");
            surfaceCtor = Surface.class.getDeclaredConstructor(scClass);
            surfaceCtor.setAccessible(true);
            return true;
        } catch (Throwable t) {
            State.log(TAG + ": reflection init failed: " + t.getMessage());
            return false;
        }
    }

    private static Object resolveParent(int displayId) {
        try {
            IBinder raw = SystemServiceHelper.getSystemService(PARENT_SERVICE);
            if (raw == null) {
                State.log(TAG + ": parent service not registered");
                return null;
            }
            IBinder binder = new ShizukuBinderWrapper(raw);
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInt(displayId);
                binder.transact(PARENT_TRANSACTION, data, reply, 0);
                Object parent = reply.readParcelable(CursorSurfaceController.class.getClassLoader());
                State.log(TAG + ": parent=" + (parent != null) + " display=" + displayId);
                return parent;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) {
            State.log(TAG + ": parent lookup failed: " + t.getMessage());
            return null;
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
            State.log(TAG + ": layerStack failed: " + t.getMessage());
            return -1;
        }
    }

    private static boolean create(int displayId, Object parent) {
        Context context = State.getContext();
        DisplayManager manager = context == null ? null
                : (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display display = manager == null ? null : manager.getDisplay(displayId);
        if (context == null || display == null) {
            return false;
        }
        layerStack = resolveLayerStack(displayId);
        if (layerStack < 0) {
            return false;
        }
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            int size = Math.max(24, Math.round(32f * metrics.density));
            hotspotX = Math.round(size * HOTSPOT_X_RATIO);
            hotspotY = Math.round(size * HOTSPOT_Y_RATIO);

            Object builder = builderClass.getDeclaredConstructor().newInstance();
            setName.invoke(builder, "libredex-cursor");
            setBufferSize.invoke(builder, size, size);
            setFormat.invoke(builder, PixelFormat.TRANSLUCENT);
            setHidden.invoke(builder, true);
            setParent.invoke(builder, parent);
            control = build.invoke(builder);
            surface = surfaceCtor.newInstance(control);
            draw(context, size);

            Object tx = txClass.getDeclaredConstructor().newInstance();
            setLayer.invoke(tx, control, CURSOR_LAYER);
            setLayerStack.invoke(tx, control, layerStack);
            setPosition.invoke(tx, control, lastX - hotspotX, lastY - hotspotY);
            setVisibility.invoke(tx, control, true);
            apply.invoke(tx);
            return true;
        } catch (Throwable t) {
            State.log(TAG + ": create failed: " + t.getMessage());
            release(false);
            return false;
        }
    }

    private static void draw(Context context, int size) {
        Bitmap source = BitmapFactory.decodeResource(context.getResources(), R.drawable.mouse_cursor);
        if (source == null) {
            throw new IllegalStateException("cursor bitmap missing");
        }
        Bitmap scaled = Bitmap.createScaledBitmap(source, size, size, true);
        Canvas canvas = surface.lockCanvas(null);
        try {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setFilterBitmap(true);
            canvas.drawBitmap(scaled, 0f, 0f, paint);
        } finally {
            surface.unlockCanvasAndPost(canvas);
            scaled.recycle();
            source.recycle();
        }
    }

    private static void setPosition(float x, float y) {
        if (control == null) {
            return;
        }
        try {
            Object tx = txClass.getDeclaredConstructor().newInstance();
            setPosition.invoke(tx, control, x - hotspotX, y - hotspotY);
            apply.invoke(tx);
        } catch (Throwable t) {
            State.log(TAG + ": move failed: " + t.getMessage());
        }
    }

    private static void setVisible(boolean visible) {
        if (control == null) {
            return;
        }
        try {
            Object tx = txClass.getDeclaredConstructor().newInstance();
            setLayer.invoke(tx, control, CURSOR_LAYER);
            setLayerStack.invoke(tx, control, layerStack);
            setPosition.invoke(tx, control, lastX - hotspotX, lastY - hotspotY);
            setVisibility.invoke(tx, control, visible);
            apply.invoke(tx);
        } catch (Throwable t) {
            State.log(TAG + ": visibility failed: " + t.getMessage());
        }
    }

    private static void release(boolean restorePointer) {
        if (control == null) {
            return;
        }
        try {
            Object tx = txClass.getDeclaredConstructor().newInstance();
            setVisibility.invoke(tx, control, false);
            apply.invoke(tx);
        } catch (Throwable ignored) {
        }
        if (surface != null) {
            try {
                surface.release();
            } catch (Throwable ignored) {
            }
            surface = null;
        }
        try {
            Method releaseMethod = scClass.getMethod("release");
            releaseMethod.invoke(control);
        } catch (Throwable ignored) {
        }
        control = null;
        layerStack = -1;
    }
}

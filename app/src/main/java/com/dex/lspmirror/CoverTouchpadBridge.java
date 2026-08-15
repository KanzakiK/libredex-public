package com.dex.lspmirror;

import android.annotation.SuppressLint;
import android.hardware.display.DisplayManager;
import android.hardware.input.IInputManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;

/**
 * Bridges cover-screen touches on the native DeX touchpad window into mouse
 * events on the fake DeX display. Samsung's native InputDispatcher only runs
 * its virtual touchpad conversion for the primary display; a touchpad window
 * on the Flip5 cover display receives touches but never converts them.
 */
public final class CoverTouchpadBridge {
    private static final String TAG = "DexLspMirror";
    private static final float MOVE_SLOP = 8f;

    private static IInputManager inputManager;
    private static Method motionSetDisplayId;
    private static Method motionSetActionButton;
    private static float cursorX;
    private static float cursorY;
    private static float lastTouchX;
    private static float lastTouchY;
    private static long gestureDownTime;
    private static boolean dragging;
    private static long lastFailureLog;

    private CoverTouchpadBridge() {
    }

    public static void install(View view) {
        if (view == null) {
            return;
        }
        view.setOnTouchListener(CoverTouchpadBridge::onTouch);
        XposedBridge.log(TAG + ": cover touchpad bridge attached");
    }

    private static boolean onTouch(View v, MotionEvent event) {
        try {
            Display display = v.getDisplay();
            if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
                return false;
            }
            int dexId = resolveDexDisplayId(v);
            if (dexId < 0) {
                return false;
            }
            Display dexDisplay = v.getContext()
                    .getSystemService(DisplayManager.class).getDisplay(dexId);
            if (dexDisplay == null) {
                return false;
            }
            float dexW = 1920f;
            float dexH = 1080f;
            try {
                android.view.DisplayInfo info = new android.view.DisplayInfo();
                Method getInfo = dexDisplay.getClass().getMethod(
                        "getDisplayInfo", android.view.DisplayInfo.class);
                getInfo.invoke(dexDisplay, info);
                dexW = info.getClass().getField("logicalWidth").getInt(info);
                dexH = info.getClass().getField("logicalHeight").getInt(info);
            } catch (Throwable ignored) {
                // Keep the default DeX resolution when hidden APIs are blocked.
            }
            float viewW = Math.max(1f, v.getWidth());
            float viewH = Math.max(1f, v.getHeight());
            if (cursorX == 0f && cursorY == 0f) {
                cursorX = dexW / 2f;
                cursorY = dexH / 2f;
            }

            int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    gestureDownTime = event.getEventTime();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - lastTouchX;
                    float dy = event.getY() - lastTouchY;
                    if (!dragging && Math.abs(dx) + Math.abs(dy) > MOVE_SLOP) {
                        dragging = true;
                    }
                    if (dragging) {
                        cursorX = clamp(cursorX + dx * dexW / viewW, 0f, dexW);
                        cursorY = clamp(cursorY + dy * dexH / viewH, 0f, dexH);
                        inject(MotionEvent.ACTION_HOVER_MOVE,
                                cursorX, cursorY, dexId, 0, 0f);
                    }
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) {
                        inject(MotionEvent.ACTION_DOWN, cursorX, cursorY, dexId,
                                MotionEvent.BUTTON_PRIMARY, 1f);
                        inject(MotionEvent.ACTION_BUTTON_PRESS, cursorX, cursorY, dexId,
                                MotionEvent.BUTTON_PRIMARY, 1f);
                        inject(MotionEvent.ACTION_BUTTON_RELEASE, cursorX, cursorY, dexId,
                                MotionEvent.BUTTON_PRIMARY, 1f);
                        inject(MotionEvent.ACTION_UP, cursorX, cursorY, dexId,
                                0, 0f);
                    }
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        } catch (Throwable t) {
            logFailure(t);
            return false;
        }
    }

    private static int resolveDexDisplayId(View v) {
        try {
            DisplayManager dm = v.getContext()
                    .getSystemService(DisplayManager.class);
            for (Display display : dm.getDisplays()) {
                String name = display.getName();
                if (name != null && name.startsWith("dex-anywhere-dex-flag")) {
                    return display.getDisplayId();
                }
            }
        } catch (Throwable t) {
            logFailure(t);
        }
        return -1;
    }

    @SuppressLint("BlockedPrivateApi")
    private static void inject(int action, float x, float y, int dexDisplayId,
                               int buttonState, float pressure) {
        try {
            if (inputManager == null) {
                inputManager = resolveInputManager();
            }
            if (inputManager == null) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            MotionEvent.PointerProperties[] properties =
                    new MotionEvent.PointerProperties[1];
            MotionEvent.PointerCoords[] coords =
                    new MotionEvent.PointerCoords[1];
            properties[0] = new MotionEvent.PointerProperties();
            properties[0].id = 0;
            properties[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
            coords[0] = new MotionEvent.PointerCoords();
            coords[0].x = x;
            coords[0].y = y;
            coords[0].pressure = pressure;
            MotionEvent event = MotionEvent.obtain(
                    gestureDownTime > 0 ? gestureDownTime : now,
                    now, action, 1, properties, coords, 0, buttonState,
                    1.0f, 1.0f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
            if (action == MotionEvent.ACTION_BUTTON_PRESS
                    || action == MotionEvent.ACTION_BUTTON_RELEASE) {
                try {
                    if (motionSetActionButton == null) {
                        motionSetActionButton = MotionEvent.class.getMethod(
                                "setActionButton", int.class);
                    }
                    motionSetActionButton.invoke(event, buttonState);
                } catch (Throwable ignored) {
                    // Action button is optional for a basic click.
                }
            }
            if (motionSetDisplayId == null) {
                motionSetDisplayId = MotionEvent.class.getDeclaredMethod(
                        "setDisplayId", int.class);
                motionSetDisplayId.setAccessible(true);
            }
            motionSetDisplayId.invoke(event, dexDisplayId);
            inputManager.injectInputEvent(event, 0);
            event.recycle();
        } catch (Throwable t) {
            logFailure(t);
        }
    }

    private static IInputManager resolveInputManager() {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "input");
            if (binder == null) {
                return null;
            }
            return IInputManager.Stub.asInterface(binder);
        } catch (Throwable t) {
            logFailure(t);
            return null;
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void logFailure(Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLog > 5000) {
            lastFailureLog = now;
            XposedBridge.log(TAG + ": cover touchpad bridge error: " + t);
        }
    }
}

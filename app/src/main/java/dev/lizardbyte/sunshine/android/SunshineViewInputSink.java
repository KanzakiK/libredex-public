package dev.lizardbyte.sunshine.android;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SunshineViewInputSink implements SunshineInputSink {
    private static final Method SET_ACTION_BUTTON = findSetActionButton();

    private final View target;
    private final LinkedHashMap<Integer, TouchPoint> touchPoints = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, GamepadState> gamepads = new LinkedHashMap<>();
    private float mouseX;
    private float mouseY;
    private boolean mousePositionInitialized;
    private int mouseButtons;
    private int metaState;
    private long touchDownTime;
    private long mouseDownTime;

    public SunshineViewInputSink(View target) {
        if (target == null) {
            throw new IllegalArgumentException("target is required");
        }
        this.target = target;
        target.setFocusable(true);
        target.setFocusableInTouchMode(true);
    }

    @Override
    public void onTouch(int eventType, int rotation, int pointerId, float x, float y, float pressure, float major, float minor) {
        float viewX = clamp(x, 0f, 1f) * target.getWidth();
        float viewY = clamp(y, 0f, 1f) * target.getHeight();
        pointerId = Math.floorMod(pointerId, 16);

        if (eventType == TOUCH_EVENT_CANCEL_ALL) {
            dispatchTouch(MotionEvent.ACTION_CANCEL, 0, true);
            touchPoints.clear();
            return;
        }

        if (eventType == TOUCH_EVENT_DOWN) {
            if (touchPoints.isEmpty()) {
                touchDownTime = SystemClock.uptimeMillis();
            }
            touchPoints.put(pointerId, new TouchPoint(pointerId, viewX, viewY, pressure, major, minor));
            dispatchTouch(touchPoints.size() == 1 ? MotionEvent.ACTION_DOWN : pointerAction(MotionEvent.ACTION_POINTER_DOWN, pointerId), pointerId, false);
        } else if (eventType == TOUCH_EVENT_MOVE) {
            TouchPoint point = touchPoints.get(pointerId);
            if (point == null) {
                return;
            }
            point.update(viewX, viewY, pressure, major, minor);
            dispatchTouch(MotionEvent.ACTION_MOVE, pointerId, false);
        } else if (eventType == TOUCH_EVENT_UP || eventType == TOUCH_EVENT_CANCEL) {
            TouchPoint point = touchPoints.get(pointerId);
            if (point == null) {
                return;
            }
            point.update(viewX, viewY, pressure, major, minor);
            int action = eventType == TOUCH_EVENT_CANCEL ? MotionEvent.ACTION_CANCEL :
                    (touchPoints.size() == 1 ? MotionEvent.ACTION_UP : pointerAction(MotionEvent.ACTION_POINTER_UP, pointerId));
            dispatchTouch(action, pointerId, eventType == TOUCH_EVENT_CANCEL);
            touchPoints.remove(pointerId);
        }
    }

    @Override
    public void onAbsoluteMouse(float x, float y, float width, float height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        mouseX = clamp(x / width, 0f, 1f) * target.getWidth();
        mouseY = clamp(y / height, 0f, 1f) * target.getHeight();
        dispatchMouse(MotionEvent.ACTION_HOVER_MOVE, 0, 0f, 0f);
    }

    @Override
    public void onRelativeMouse(int dx, int dy) {
        ensureMousePosition();
        mouseX = clamp(mouseX + dx, 0f, target.getWidth());
        mouseY = clamp(mouseY + dy, 0f, target.getHeight());
        dispatchMouse(MotionEvent.ACTION_HOVER_MOVE, 0, 0f, 0f);
    }

    @Override
    public void onMouseButton(int button, boolean release) {
        int androidButton = toAndroidMouseButton(button);
        if (androidButton == 0) {
            return;
        }
        ensureMousePosition();
        if (!release) {
            if (mouseButtons == 0) {
                mouseDownTime = SystemClock.uptimeMillis();
                mouseButtons |= androidButton;
                dispatchMouse(MotionEvent.ACTION_DOWN, androidButton, 0f, 0f);
            } else {
                mouseButtons |= androidButton;
            }
            dispatchMouse(MotionEvent.ACTION_BUTTON_PRESS, androidButton, 0f, 0f);
        } else {
            dispatchMouse(MotionEvent.ACTION_BUTTON_RELEASE, androidButton, 0f, 0f);
            mouseButtons &= ~androidButton;
            if (mouseButtons == 0) {
                dispatchMouse(MotionEvent.ACTION_UP, androidButton, 0f, 0f);
                mouseDownTime = 0;
            }
        }
    }

    @Override
    public void onMouseScroll(int vertical, int horizontal) {
        dispatchMouse(MotionEvent.ACTION_SCROLL, 0, horizontal / 120f, vertical / 120f);
    }

    @Override
    public void onKeyboard(int windowsKeyCode, boolean release, int flags) {
        int keyCode = WindowsKeyMap.toAndroidKeyCode(windowsKeyCode);
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return;
        }
        updateMetaState(keyCode, !release);
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(
                now,
                now,
                release ? KeyEvent.ACTION_UP : KeyEvent.ACTION_DOWN,
                keyCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                flags,
                InputDevice.SOURCE_KEYBOARD
        );
        target.requestFocus();
        target.dispatchKeyEvent(event);
    }

    @Override
    public void onUnicode(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        KeyEvent[] events = map.getEvents(text.toCharArray());
        if (events == null) {
            return;
        }
        target.requestFocus();
        for (KeyEvent event : events) {
            target.dispatchKeyEvent(event);
        }
    }

    @Override
    public void onGamepad(int controller, int buttons, int lt, int rt, int lsX, int lsY, int rsX, int rsY) {
        GamepadState state = gamepads.get(controller);
        if (state == null) {
            state = new GamepadState();
            gamepads.put(controller, state);
        }

        dispatchGamepadButtonChanges(controller, state.buttons, buttons, state.downTime);
        state.buttons = buttons;
        if (buttons == 0) {
            state.downTime = 0;
        } else if (state.downTime == 0) {
            state.downTime = SystemClock.uptimeMillis();
        }

        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = controller;
        properties[0].toolType = MotionEvent.TOOL_TYPE_UNKNOWN;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].setAxisValue(MotionEvent.AXIS_LTRIGGER, lt / 255f);
        coords[0].setAxisValue(MotionEvent.AXIS_RTRIGGER, rt / 255f);
        coords[0].setAxisValue(MotionEvent.AXIS_X, normalizeStick(lsX));
        coords[0].setAxisValue(MotionEvent.AXIS_Y, normalizeStick(lsY));
        coords[0].setAxisValue(MotionEvent.AXIS_Z, normalizeStick(rsX));
        coords[0].setAxisValue(MotionEvent.AXIS_RZ, normalizeStick(rsY));

        MotionEvent event = MotionEvent.obtain(
                now,
                now,
                MotionEvent.ACTION_MOVE,
                1,
                properties,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK,
                0
        );
        target.dispatchGenericMotionEvent(event);
        event.recycle();
    }

    private void ensureMousePosition() {
        if (!mousePositionInitialized && target.getWidth() > 0 && target.getHeight() > 0) {
            mouseX = target.getWidth() / 2f;
            mouseY = target.getHeight() / 2f;
            mousePositionInitialized = true;
        }
    }

    private void dispatchTouch(int action, int actionPointerId, boolean cancelled) {
        if (touchPoints.isEmpty()) {
            return;
        }
        ArrayList<TouchPoint> points = new ArrayList<>(touchPoints.values());
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[points.size()];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[points.size()];
        for (int i = 0; i < points.size(); i++) {
            TouchPoint point = points.get(i);
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = point.id;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = point.x;
            coords[i].y = point.y;
            coords[i].pressure = point.pressure > 0f ? point.pressure : 1f;
            coords[i].size = point.major > 0f ? point.major : 1f;
        }
        MotionEvent event = MotionEvent.obtain(
                touchDownTime,
                SystemClock.uptimeMillis(),
                action,
                points.size(),
                properties,
                coords,
                metaState,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                cancelled ? MotionEvent.FLAG_CANCELED : 0
        );
        target.dispatchTouchEvent(event);
        event.recycle();
    }

    private void dispatchMouse(int action, int actionButton, float hScroll, float vScroll) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = mouseX;
        coords[0].y = mouseY;
        coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
        coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);

        MotionEvent event = MotionEvent.obtain(
                mouseDownTime == 0 ? SystemClock.uptimeMillis() : mouseDownTime,
                SystemClock.uptimeMillis(),
                action,
                1,
                properties,
                coords,
                metaState,
                mouseButtons,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_MOUSE,
                0
        );
        setActionButton(event, actionButton);
        target.dispatchGenericMotionEvent(event);
        event.recycle();
    }

    private static Method findSetActionButton() {
        try {
            Method method = MotionEvent.class.getDeclaredMethod("setActionButton", int.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void setActionButton(MotionEvent event, int actionButton) {
        if (SET_ACTION_BUTTON == null || actionButton == 0) {
            return;
        }
        try {
            SET_ACTION_BUTTON.invoke(event, actionButton);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private void dispatchGamepadButtonChanges(int controller, int oldButtons, int newButtons, long downTime) {
        int changed = oldButtons ^ newButtons;
        if (changed == 0) {
            return;
        }
        target.requestFocus();
        dispatchGamepadButton(controller, changed, newButtons, 0x0001, KeyEvent.KEYCODE_DPAD_UP, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x0002, KeyEvent.KEYCODE_DPAD_DOWN, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x0004, KeyEvent.KEYCODE_DPAD_LEFT, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x0008, KeyEvent.KEYCODE_DPAD_RIGHT, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x0010, KeyEvent.KEYCODE_BUTTON_START, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x0020, KeyEvent.KEYCODE_BUTTON_SELECT, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x0040, KeyEvent.KEYCODE_BUTTON_THUMBL, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x0080, KeyEvent.KEYCODE_BUTTON_THUMBR, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x0100, KeyEvent.KEYCODE_BUTTON_L1, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x0200, KeyEvent.KEYCODE_BUTTON_R1, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x0400, KeyEvent.KEYCODE_BUTTON_MODE, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x1000, KeyEvent.KEYCODE_BUTTON_A, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x2000, KeyEvent.KEYCODE_BUTTON_B, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x4000, KeyEvent.KEYCODE_BUTTON_X, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x8000, KeyEvent.KEYCODE_BUTTON_Y, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x010000, KeyEvent.KEYCODE_BUTTON_1, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x020000, KeyEvent.KEYCODE_BUTTON_2, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x040000, KeyEvent.KEYCODE_BUTTON_3, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x080000, KeyEvent.KEYCODE_BUTTON_4, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x100000, KeyEvent.KEYCODE_BUTTON_THUMBL, downTime);
        dispatchGamepadButton(controller, changed, newButtons, 0x200000, KeyEvent.KEYCODE_BUTTON_MODE, downTime);
    }

    private void dispatchGamepadButton(int controller, int changed, int buttons, int mask, int keyCode, long downTime) {
        if ((changed & mask) == 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean pressed = (buttons & mask) != 0;
        long eventDownTime = pressed ? now : (downTime == 0 ? now : downTime);
        KeyEvent event = new KeyEvent(
                eventDownTime,
                now,
                pressed ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                keyCode,
                0,
                0,
                controller,
                0,
                0,
                InputDevice.SOURCE_GAMEPAD
        );
        target.dispatchKeyEvent(event);
    }

    private int pointerAction(int action, int pointerId) {
        int index = 0;
        for (Map.Entry<Integer, TouchPoint> entry : touchPoints.entrySet()) {
            if (entry.getKey() == pointerId) {
                break;
            }
            index++;
        }
        return action | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    }

    private void updateMetaState(int keyCode, boolean pressed) {
        int mask = 0;
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT) {
            mask = KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        } else if (keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            mask = KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_RIGHT_ON;
        } else if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT) {
            mask = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        } else if (keyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
            mask = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_RIGHT_ON;
        } else if (keyCode == KeyEvent.KEYCODE_ALT_LEFT) {
            mask = KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        } else if (keyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
            mask = KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON;
        } else if (keyCode == KeyEvent.KEYCODE_META_LEFT) {
            mask = KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON;
        } else if (keyCode == KeyEvent.KEYCODE_META_RIGHT) {
            mask = KeyEvent.META_META_ON | KeyEvent.META_META_RIGHT_ON;
        }
        if (pressed) {
            metaState |= mask;
        } else {
            metaState &= ~mask;
        }
    }

    private static int toAndroidMouseButton(int button) {
        if (button == MOUSE_BUTTON_LEFT) {
            return MotionEvent.BUTTON_PRIMARY;
        }
        if (button == MOUSE_BUTTON_MIDDLE) {
            return MotionEvent.BUTTON_TERTIARY;
        }
        if (button == MOUSE_BUTTON_RIGHT) {
            return MotionEvent.BUTTON_SECONDARY;
        }
        return 0;
    }

    private static float normalizeStick(int value) {
        return Math.max(-1f, Math.min(1f, value / 32767f));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class TouchPoint {
        final int id;
        float x;
        float y;
        float pressure;
        float major;
        float minor;

        TouchPoint(int id, float x, float y, float pressure, float major, float minor) {
            this.id = id;
            update(x, y, pressure, major, minor);
        }

        void update(float x, float y, float pressure, float major, float minor) {
            this.x = x;
            this.y = y;
            this.pressure = pressure;
            this.major = major;
            this.minor = minor;
        }
    }

    private static final class GamepadState {
        int buttons;
        long downTime;
    }
}

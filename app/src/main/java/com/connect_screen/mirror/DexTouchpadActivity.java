package com.connect_screen.mirror;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.input.IInputManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.MotionEventHidden;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.connect_screen.mirror.job.InputRouting;
import com.connect_screen.mirror.shizuku.ServiceUtils;

import dev.rikka.tools.refine.Refine;

import java.lang.reflect.Method;

/**
 * LibreDeX virtual touchpad.
 *
 * Rendered as a normal (non-SystemUI) activity on the phone/cover screen. Touch
 * gestures are translated into synthetic SOURCE_MOUSE events injected, via the
 * root (Shizuku) IInputManager, into the active DeX output display. A visible
 * cursor overlay is drawn on that display so movement is visible even when the
 * system keeps its pointer hidden. No dependency on Samsung's SystemUI touchpad.
 */
public final class DexTouchpadActivity extends Activity {

    private static final float TAP_MOVEMENT_TOLERANCE_DP = 24f;
    private static final long TAP_TIMEOUT_MS = 400L;
    private static final long TWO_FINGER_TAP_TIMEOUT_MS = 500L;

    private IInputManager inputManager;
    private int targetDisplayId = Display.DEFAULT_DISPLAY;
    private float cursorFieldW = 1920f;
    private float cursorFieldH = 1080f;
    private float cursorX, cursorY;

    private boolean touchTracking;
    private float lastTouchX, lastTouchY;
    private long fingerDownTime;
    private float fingerDownX, fingerDownY;
    private boolean fingerMoved;

    private boolean secondFingerDown;
    private long secondFingerDownTime;
    private float secondDownX, secondDownY;
    private boolean secondFingerMoved;

    private long lastFocusAttemptMs;

    private final MotionEvent.PointerProperties[] mouseProps = new MotionEvent.PointerProperties[1];
    private final MotionEvent.PointerCoords[] mouseCoords = new MotionEvent.PointerCoords[1];

    private long mouseDownTime;
    private boolean mousePressed;

    // cursor overlay on the target display
    private WindowManager cursorWindowManager;
    private ImageView cursorView;
    private WindowManager.LayoutParams cursorParams;
    private int cursorHotspotX;
    private int cursorHotspotY;
    private int cursorDisplayId = Integer.MIN_VALUE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.setStatusBarColor(Color.argb(200, 20, 20, 22));

        try {
            inputManager = ServiceUtils.getInputManager();
        } catch (Throwable t) {
            State.log("touchpad: inputManager failed: " + t.getMessage());
            finish();
            return;
        }

        targetDisplayId = resolveTargetDisplayId();
        resolveCursorSpace(targetDisplayId);
        cursorX = cursorFieldW / 2f;
        cursorY = cursorFieldH / 2f;
        State.log("touchpad: activity up target=" + targetDisplayId + " field="
                + cursorFieldW + "x" + cursorFieldH);

        setContentView(buildPadView());
        ensureCursorOverlay();
    }

    @Override
    protected void onDestroy() {
        removeCursorOverlay();
        super.onDestroy();
    }

    private View buildPadView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.argb(230, 28, 28, 32));

        TextView hint = new TextView(this);
        hint.setText("LibreDeX Touchpad");
        hint.setTextColor(Color.argb(160, 255, 255, 255));
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        root.setOnTouchListener((v, event) -> handleTouch(event));
        return root;
    }

    // ------------------------------------------------------------------
    // Cursor overlay on the target display
    // ------------------------------------------------------------------

    private void ensureCursorOverlay() {
        try {
            removeCursorOverlay();
            DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (displayManager == null) {
                return;
            }
            Display targetDisplay = displayManager.getDisplay(targetDisplayId);
            if (targetDisplay == null) {
                return;
            }
            android.content.Context displayContext = createDisplayContext(targetDisplay);
            cursorWindowManager = (WindowManager) displayContext.getSystemService(WINDOW_SERVICE);
            if (cursorWindowManager == null) {
                return;
            }
            int cursorSize = (int) (32 * displayContext.getResources().getDisplayMetrics().density);
            cursorHotspotX = 0;
            cursorHotspotY = 0;
            cursorView = new ImageView(displayContext);
            cursorView.setImageResource(R.drawable.mouse_cursor);
            cursorView.setScaleType(ImageView.ScaleType.FIT_START);
            cursorParams = new WindowManager.LayoutParams(
                    cursorSize, cursorSize,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            cursorParams.gravity = Gravity.TOP | Gravity.START;
            cursorParams.x = Math.round(cursorX - cursorHotspotX);
            cursorParams.y = Math.round(cursorY - cursorHotspotY);
            cursorWindowManager.addView(cursorView, cursorParams);
            cursorDisplayId = targetDisplayId;
            State.log("touchpad: cursor overlay shown on displayId=" + targetDisplayId);
        } catch (Throwable t) {
            State.log("touchpad: cursor overlay failed: " + t.getMessage());
            cursorWindowManager = null;
            cursorParams = null;
            cursorView = null;
        }
    }

    private void updateCursorPosition(float x, float y) {
        if (cursorView == null || cursorParams == null || cursorWindowManager == null
                || cursorDisplayId != targetDisplayId) {
            ensureCursorOverlay();
        }
        if (cursorView == null || cursorParams == null || cursorWindowManager == null) {
            return;
        }
        cursorParams.x = Math.round(x) - cursorHotspotX;
        cursorParams.y = Math.round(y) - cursorHotspotY;
        try {
            cursorWindowManager.updateViewLayout(cursorView, cursorParams);
        } catch (Throwable ignored) {
        }
    }

    private void removeCursorOverlay() {
        if (cursorWindowManager != null && cursorView != null) {
            try {
                cursorWindowManager.removeView(cursorView);
            } catch (Throwable ignored) {
            }
        }
        cursorWindowManager = null;
        cursorParams = null;
        cursorView = null;
        cursorDisplayId = Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------
    // Target display
    // ------------------------------------------------------------------

    private int resolveTargetDisplayId() {
        if (State.externalDisplayId > 0) {
            return State.externalDisplayId;
        }
        if (State.externalControlDisplayId > 0) {
            return State.externalControlDisplayId;
        }
        String prop = readDpDisplayProp();
        if (prop != null && !prop.trim().isEmpty()) {
            try {
                int id = Integer.parseInt(prop.trim());
                if (id > 0) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return Display.DEFAULT_DISPLAY;
    }

    private String readDpDisplayProp() {
        try {
            if (State.isUserServiceAlive()) {
                String out = State.userService.executeCommand("getprop persist.dex.lspmirror.dp_display_id");
                if (out != null && !out.trim().isEmpty()) {
                    return out.trim();
                }
            }
        } catch (Throwable t) {
            State.log("touchpad: read dp_display prop failed: " + t.getMessage());
        }
        return null;
    }

    private void resolveCursorSpace(int displayId) {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (dm == null) {
                return;
            }
            Display display = dm.getDisplay(displayId);
            if (display == null) {
                return;
            }
            Point size = new Point();
            display.getRealSize(size);
            if (size.x > 0 && size.y > 0) {
                cursorFieldW = size.x;
                cursorFieldH = size.y;
            }
        } catch (Throwable t) {
            State.log("touchpad: resolve display size failed: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Touch -> mouse
    // ------------------------------------------------------------------

    private boolean handleTouch(MotionEvent event) {
        try {
            int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    touchTracking = true;
                    fingerMoved = false;
                    fingerDownTime = event.getEventTime();
                    fingerDownX = event.getX();
                    fingerDownY = event.getY();
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    secondFingerDown = false;
                    secondFingerMoved = false;
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN: {
                    secondFingerDown = true;
                    secondFingerDownTime = event.getEventTime();
                    secondDownX = event.getX(1);
                    secondDownY = event.getY(1);
                    secondFingerMoved = false;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (secondFingerDown && event.getPointerCount() >= 2) {
                        float x2 = event.getX(1);
                        float y2 = event.getY(1);
                        float dx = x2 - secondDownX;
                        float dy = y2 - secondDownY;
                        if (Math.hypot(dx, dy) > dp(12)) {
                            secondFingerMoved = true;
                        }
                        return true;
                    }
                    handleMove(event.getX(), event.getY(), event.getEventTime());
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP: {
                    if (secondFingerDown && event.getPointerId(event.getActionIndex()) != 0) {
                        secondFingerDown = false;
                        long dt = event.getEventTime() - secondFingerDownTime;
                        if (!secondFingerMoved && dt <= TWO_FINGER_TAP_TIMEOUT_MS) {
                            rightClick();
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    if (secondFingerDown) {
                        secondFingerDown = false;
                        touchTracking = false;
                        return true;
                    }
                    boolean tap = !fingerMoved
                            && (event.getEventTime() - fingerDownTime <= TAP_TIMEOUT_MS)
                            && Math.hypot(event.getX() - fingerDownX, event.getY() - fingerDownY)
                            <= dp(TAP_MOVEMENT_TOLERANCE_DP);
                    touchTracking = false;
                    if (tap) {
                        leftClick();
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: {
                    touchTracking = false;
                    secondFingerDown = false;
                    return true;
                }
                default:
                    return true;
            }
        } catch (Throwable t) {
            State.log("touchpad: handleTouch error: " + t.getMessage());
            return true;
        }
    }

    private void handleMove(float touchX, float touchY, long now) {
        if (!touchTracking) {
            return;
        }
        if (cursorFieldW <= 0 || cursorFieldH <= 0) {
            return;
        }
        View pad = findViewById(android.R.id.content);
        float padW = pad != null && pad.getWidth() > 0 ? pad.getWidth() : 1f;
        float padH = pad != null && pad.getHeight() > 0 ? pad.getHeight() : 1f;
        float dx = (touchX - lastTouchX) * (cursorFieldW / padW);
        float dy = (touchY - lastTouchY) * (cursorFieldH / padH);
        if (!fingerMoved && Math.hypot(touchX - fingerDownX, touchY - fingerDownY) > dp(4)) {
            fingerMoved = true;
        }
        lastTouchX = touchX;
        lastTouchY = touchY;
        if (fingerMoved) {
            cursorX = clamp(cursorX + dx, 0f, cursorFieldW);
            cursorY = clamp(cursorY + dy, 0f, cursorFieldH);
            updateCursorPosition(cursorX, cursorY);
            injectMouseHover(cursorX, cursorY);
        }
    }

    private void leftClick() {
        if (mousePressed) {
            return;
        }
        mousePressed = true;
        mouseDownTime = SystemClock.uptimeMillis();
        injectMouseEvent(MotionEvent.ACTION_DOWN, cursorX, cursorY,
                MotionEvent.BUTTON_PRIMARY, 0);
        injectMouseEvent(MotionEvent.ACTION_BUTTON_PRESS, cursorX, cursorY,
                MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY);
        mousePressed = false;
        injectMouseEvent(MotionEvent.ACTION_BUTTON_RELEASE, cursorX, cursorY,
                0, MotionEvent.BUTTON_PRIMARY);
        injectMouseEvent(MotionEvent.ACTION_UP, cursorX, cursorY, 0, 0);
        mouseDownTime = 0;
    }

    private void rightClick() {
        injectMouseEvent(MotionEvent.ACTION_DOWN, cursorX, cursorY,
                MotionEvent.BUTTON_SECONDARY, 0);
        injectMouseEvent(MotionEvent.ACTION_BUTTON_PRESS, cursorX, cursorY,
                MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_SECONDARY);
        injectMouseEvent(MotionEvent.ACTION_BUTTON_RELEASE, cursorX, cursorY,
                0, MotionEvent.BUTTON_SECONDARY);
        injectMouseEvent(MotionEvent.ACTION_UP, cursorX, cursorY, 0, 0);
    }

    private void injectMouseHover(float x, float y) {
        injectMouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, 0);
    }

    private void injectMouseEvent(int action, float x, float y, int buttonState, int actionButton) {
        if (inputManager == null) {
            return;
        }
        ensureMouseProps();
        long now = SystemClock.uptimeMillis();
        long downTime = mouseDownTime != 0 ? mouseDownTime : now;
        mouseCoords[0].x = x;
        mouseCoords[0].y = y;
        mouseCoords[0].pressure = buttonState == 0 ? 0f : 1f;

        MotionEvent event = MotionEvent.obtain(
                downTime, now, action, 1, mouseProps, mouseCoords,
                0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        if (actionButton != 0) {
            setActionButton(event, actionButton);
        }

        // The hidden cross-display API (injectInputEventOtherScreens) does not
        // exist on this API level; the working delivery is to pin the event's
        // display id and inject. Setting the display id is required or the
        // pointer/click land on the wrong (default) display.
        setEventDisplayId(event, targetDisplayId);
        tryInject(event);
    }

    private void tryInject(MotionEvent event) {
        try {
            // Re-assert display focus periodically (not just once). The DeX
            // session being stopped and restarted rebuilds the root task on the
            // target display even when its id stays the same (e.g. 6), which
            // drops our previously-set focus. A time-based re-assert on each
            // gesture prevents clicks from landing on an unfocused display.
            if (targetDisplayId != 0
                    && SystemClock.uptimeMillis() - lastFocusAttemptMs > 1000) {
                InputRouting.setFocus(inputManager, targetDisplayId);
                lastFocusAttemptMs = SystemClock.uptimeMillis();
            }
            inputManager.injectInputEvent(event, 0);
        } catch (Throwable t) {
            State.log("touchpad: inject failed: " + t.getMessage());
        }
    }

    private void setEventDisplayId(MotionEvent event, int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            return;
        }
        try {
            MotionEventHidden hidden = Refine.unsafeCast(event);
            hidden.setDisplayId(displayId);
        } catch (Throwable t) {
            State.log("touchpad: setDisplayId failed: " + t.getMessage());
        }
    }

    private static void setActionButton(MotionEvent event, int actionButton) {
        try {
            Method m = MotionEvent.class.getDeclaredMethod("setActionButton", int.class);
            m.setAccessible(true);
            m.invoke(event, actionButton);
        } catch (Throwable ignored) {
        }
    }

    private void ensureMouseProps() {
        if (mouseProps[0] == null) {
            mouseProps[0] = new MotionEvent.PointerProperties();
            mouseProps[0].id = 0;
            mouseProps[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
            mouseCoords[0] = new MotionEvent.PointerCoords();
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
package com.connect_screen.mirror;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.input.IInputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.KeyEventHidden;
import android.view.MotionEvent;
import android.view.MotionEventHidden;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.connect_screen.mirror.job.InputRouting;
import com.connect_screen.mirror.shizuku.ServiceUtils;

import dev.rikka.tools.refine.Refine;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // Two-finger scroll: AXIS_VSCROLL units per dp of finger movement, and the
    // direction sign. SCROLL_SIGN is -1 per the plan ("手指上滑 -> 内容向下滚");
    // flip it on device if the scroll direction feels inverted.
    private static final float SCROLL_STEP_DP = 3f;
    private static final int SCROLL_SIGN = -1;

    private static final int BUTTON_SIZE_DP = 32;
    private static final int BUTTON_SPACING_DP = 8;
    // Keep the bar clear of the crosshair frame line + its rounded corner.
    private static final int BUTTON_MARGIN_DP = 24;

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
    private float lastScrollY;

    private CrosshairView crosshairView;

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

        // Decorative crosshair: a frame matching the touchpad's own screen
        // ratio (内屏/盖屏), purely visual, never clickable, does not intercept
        // touches.
        crosshairView = new CrosshairView(this);
        root.addView(crosshairView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Vertical button bar pinned to the pad's top-right corner. Buttons are
        // clickable so they consume their own touches and never fall through to
        // handleTouch(); the pad underneath keeps its full touch-to-cursor map.
        root.addView(buildButtonBar());

        root.setOnTouchListener((v, event) -> handleTouch(event));
        return root;
    }

    // ------------------------------------------------------------------
    // Top-right button bar
    // ------------------------------------------------------------------

    private View buildButtonBar() {
        int accent = getColor(R.color.ui_accent);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(0x66, 0x22, 0x2A, 0x4A)); // #66222A4A
        bg.setStroke(Math.round(dp(1.2f)), accent);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        int size = Math.round(dp(BUTTON_SIZE_DP));
        bar.addView(makeButton(R.drawable.ic_touchpad_close, size, bg, v -> finish()));
        bar.addView(makeButton(R.drawable.ic_touchpad_back, size, bg, v -> injectBackKey()));
        bar.addView(makeButton(R.drawable.ic_touchpad_kill, size, bg, v -> killForegroundApp()));
        // Parent is the root FrameLayout, so gravity goes on a FrameLayout
        // LayoutParams (LinearLayout.LayoutParams' 3-arg ctor takes weight, not
        // gravity — using it left the bar pinned to the top-left corner).
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.TOP);
        barLp.setMargins(0, Math.round(dp(BUTTON_MARGIN_DP)),
                Math.round(dp(BUTTON_MARGIN_DP)), 0);
        bar.setLayoutParams(barLp);
        return bar;
    }

    private ImageButton makeButton(int iconRes, int size, GradientDrawable bg,
                                   View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconRes);
        button.setBackground(bg);
        button.setClickable(true);
        button.setFocusable(false);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.bottomMargin = Math.round(dp(BUTTON_SPACING_DP));
        button.setLayoutParams(lp);
        return button;
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
            // Imported 48x48 PNG at 60% size (36dp * 0.6 ~= 22dp @1x density).
            int cursorSize = (int) (22 * displayContext.getResources().getDisplayMetrics().density);
            // mouse_cursor.png is a 48x48 classic pointer whose tip sits about
            // (4.5,2) inside the bitmap. FIT_START maps it 1:1 onto the square
            // view, so scale the tip offset by cursorSize/48 for exact clicks.
            cursorHotspotX = Math.round(cursorSize * 4.5f / 48f);
            cursorHotspotY = Math.round(cursorSize * 2f / 48f);
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
                    lastScrollY = event.getY(1);
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
                        // Two-finger vertical swipe -> mouse wheel scroll. Use
                        // incremental delta per MOVE so scrolling stays smooth.
                        // Sign needs real-device tuning (see SCROLL_SIGN).
                        float yIncrement = y2 - lastScrollY;
                        lastScrollY = y2;
                        if (Math.abs(yIncrement) >= 1f) {
                            scroll(SCROLL_SIGN * yIncrement * dp(SCROLL_STEP_DP));
                        }
                        // Keep the first finger's position in sync while
                        // scrolling, so leaving scroll mode has no jump.
                        lastTouchX = event.getX(0);
                        lastTouchY = event.getY(0);
                        if (secondFingerMoved) {
                            fingerMoved = true;
                        }
                        return true;
                    }
                    if (secondFingerDown) {
                        // Pointer count dropped below 2 without a clean
                        // POINTER_UP (touch jitter). Leave scroll mode and
                        // resync the baseline so the next single-finger move
                        // does not jump.
                        secondFingerDown = false;
                        lastTouchX = event.getX(0);
                        lastTouchY = event.getY(0);
                        return true;
                    }
                    handleMove(event.getX(), event.getY(), event.getEventTime());
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP: {
                    if (!secondFingerDown) {
                        return true;
                    }
                    int actionIndex = event.getActionIndex();
                    boolean secondLifted = event.getPointerId(actionIndex) != 0;
                    // Whichever finger lifts, the remaining one becomes pointer
                    // 0 on later events. Resync the delta baseline to it so
                    // continuing to drag never jumps from the other finger's
                    // stale position (the old code only handled the second
                    // finger lifting, which left a jump when the first finger
                    // went up first).
                    float remainX = actionIndex == 0 ? event.getX(1) : event.getX(0);
                    float remainY = actionIndex == 0 ? event.getY(1) : event.getY(0);
                    lastTouchX = remainX;
                    lastTouchY = remainY;
                    long dt = event.getEventTime() - secondFingerDownTime;
                    boolean tap = !secondFingerMoved && dt <= TWO_FINGER_TAP_TIMEOUT_MS;
                    secondFingerDown = false;
                    if (secondLifted && tap) {
                        rightClick();
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

    // Two-finger swipe -> mouse wheel. Injects an ACTION_SCROLL SOURCE_MOUSE
    // event pinned to the target display with the vertical scroll delta set on
    // AXIS_VSCROLL (same setEventDisplayId + tryInject pipeline as clicks).
    private void scroll(float delta) {
        if (inputManager == null) {
            return;
        }
        try {
            ensureMouseProps();
            long now = SystemClock.uptimeMillis();
            long downTime = mouseDownTime != 0 ? mouseDownTime : now;
            mouseCoords[0].x = cursorX;
            mouseCoords[0].y = cursorY;
            mouseCoords[0].pressure = 0f;
            mouseCoords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, delta);
            MotionEvent event = MotionEvent.obtain(
                    downTime, now, MotionEvent.ACTION_SCROLL, 1, mouseProps, mouseCoords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
            setEventDisplayId(event, targetDisplayId);
            tryInject(event);
        } catch (Throwable t) {
            State.log("touchpad: scroll inject failed: " + t.getMessage());
        }
    }

    // Back button -> inject KEYCODE_BACK onto the target (external) display.
    // `input keyevent` from the shell would land on the default display, so the
    // KeyEvent is pinned to targetDisplayId via KeyEventHidden (same Refine
    // approach the keyboard uses) and injected through the root input manager.
    private void injectBackKey() {
        if (inputManager == null) {
            return;
        }
        try {
            if (targetDisplayId != Display.DEFAULT_DISPLAY) {
                InputRouting.setFocus(inputManager, targetDisplayId);
            }
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_BACK, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD,
                    0, 0, InputDevice.SOURCE_KEYBOARD);
            KeyEvent up = new KeyEvent(now, now + 5L, KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_BACK, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD,
                    0, 0, InputDevice.SOURCE_KEYBOARD);
            if (targetDisplayId != Display.DEFAULT_DISPLAY) {
                KeyEventHidden downHidden = Refine.unsafeCast(down);
                downHidden.setDisplayId(targetDisplayId);
                KeyEventHidden upHidden = Refine.unsafeCast(up);
                upHidden.setDisplayId(targetDisplayId);
            }
            inputManager.injectInputEvent(down, 0);
            inputManager.injectInputEvent(up, 0);
            State.log("touchpad: injected BACK to display=" + targetDisplayId);
        } catch (Throwable t) {
            State.log("touchpad: BACK inject failed: " + t.getMessage());
        }
    }

    // Kill button -> force-stop the foreground app on the target (external)
    // display. The top package is resolved from `dumpsys activity activities`
    // restricted to the target display section, so the inner-screen foreground
    // app is never touched. Runs off the main thread (remote shell calls).
    private void killForegroundApp() {
        new Thread(() -> {
            try {
                String pkg = resolveTopPackageOnDisplay(targetDisplayId);
                if (pkg == null || pkg.isEmpty()) {
                    State.log("touchpad: no foreground app on display=" + targetDisplayId);
                    return;
                }
                if (pkg.equals(getPackageName()) || "com.android.systemui".equals(pkg)) {
                    return;
                }
                if (State.isUserServiceAlive()) {
                    State.userService.executeCommand("am force-stop " + pkg);
                    State.log("touchpad: force-stopped " + pkg
                            + " on display=" + targetDisplayId);
                }
            } catch (Throwable t) {
                State.log("touchpad: kill foreground failed: " + t.getMessage());
            }
        }, "touchpad-kill").start();
    }

    private static final Pattern ACTIVITY_RECORD = Pattern.compile(
            "ActivityRecord\\{[^}]* (\\S+?)/");

    private String resolveTopPackageOnDisplay(int displayId) {
        if (!State.isUserServiceAlive()) {
            return null;
        }
        try {
            String out = State.userService.executeCommand("dumpsys activity activities");
            int exitIdx = out.indexOf("__EXIT_CODE");
            if (exitIdx >= 0) {
                out = out.substring(0, exitIdx);
            }
            boolean inTarget = false;
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (t.startsWith("Display #") && t.contains("activities from top to bottom")) {
                    inTarget = t.startsWith("Display #" + displayId + " ");
                    continue;
                }
                if (!inTarget) {
                    continue;
                }
                if (t.startsWith("Display #")) {
                    break; // passed the target display section
                }
                Matcher m = ACTIVITY_RECORD.matcher(t);
                if (m.find()) {
                    return m.group(1);
                }
            }
        } catch (Throwable t) {
            State.log("touchpad: resolve top package failed: " + t.getMessage());
        }
        return null;
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

    // ------------------------------------------------------------------
    // Crosshair: own-screen ratio indicator
    // ------------------------------------------------------------------

    /**
     * Draws a frame around the pad (its own screen 内屏/盖屏) plus an accent
     * center crosshair. The frame is recomputed on every size change, so it
     * stays correct across screen rotation. Decoration only: NOT clickable,
     * never intercepts the pad's touch handling.
     */
    private final class CrosshairView extends View {
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        CrosshairView(Context context) {
            super(context);
            setClickable(false);
            setFocusable(false);
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(dp(1.6f));
            framePaint.setColor(Color.argb(180, 255, 255, 255));
            accentPaint.setStyle(Paint.Style.STROKE);
            accentPaint.setStrokeWidth(dp(2f));
            accentPaint.setStrokeCap(Paint.Cap.ROUND);
            accentPaint.setColor(getColor(R.color.ui_accent));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            // The frame mirrors the pad itself (own screen 内屏/盖屏). Deriving
            // it from the view size keeps it correct through screen rotation,
            // where the activity re-layouts but a one-time resolved display
            // ratio would stay stale.
            box.set(0f, 0f, w, h);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (box.isEmpty()) {
                return;
            }
            // The white rounded frame already marks the boundary, so no extra
            // corner brackets. Center accent crosshair only.
            canvas.drawRoundRect(box, dp(8f), dp(8f), framePaint);
            float cx = box.centerX();
            float cy = box.centerY();
            float cross = dp(14f);
            canvas.drawLine(cx - cross, cy, cx + cross, cy, accentPaint);
            canvas.drawLine(cx, cy - cross, cx, cy + cross, accentPaint);
        }
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
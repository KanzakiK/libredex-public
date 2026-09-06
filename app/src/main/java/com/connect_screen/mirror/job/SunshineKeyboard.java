package com.connect_screen.mirror.job;

import android.content.Context;
import android.hardware.input.IInputManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.Display;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.KeyEventHidden;
import android.view.MotionEventHidden;

import java.lang.reflect.Method;

import com.connect_screen.mirror.Pref;
import com.connect_screen.mirror.State;
import com.connect_screen.mirror.shizuku.IUserService;
import com.connect_screen.mirror.shizuku.ServiceUtils;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

import dev.rikka.tools.refine.Refine;

public class SunshineKeyboard {
    private static String TAG = "SunshineKeyboard";
    private static final boolean DEBUG_INPUT_EVENTS = false;

    /**
     * GFE's prefix for every key code
     */
    private static final short KEY_PREFIX = (short) 0x80;

    public static final int VK_0 = 48;
    public static final int VK_9 = 57;
    public static final int VK_A = 65;
    public static final int VK_Z = 90;
    public static final int VK_NUMPAD0 = 96;
    public static final int VK_BACK_SLASH = 92;
    public static final int VK_CAPS_LOCK = 20;
    public static final int VK_CLEAR = 12;
    public static final int VK_COMMA = 44;
    public static final int VK_BACK_SPACE = 8;
    public static final int VK_EQUALS = 61;
    public static final int VK_ESCAPE = 27;
    public static final int VK_F1 = 112;
    public static final int VK_F12 = 123;

    public static final int VK_END = 35;
    public static final int VK_HOME = 36;
    public static final int VK_NUM_LOCK = 144;
    public static final int VK_PAGE_UP = 33;
    public static final int VK_PAGE_DOWN = 34;
    public static final int VK_PLUS = 521;
    public static final int VK_CLOSE_BRACKET = 93;
    public static final int VK_SCROLL_LOCK = 145;
    public static final int VK_SEMICOLON = 59;
    public static final int VK_SLASH = 47;
    public static final int VK_SPACE = 32;
    public static final int VK_PRINTSCREEN = 154;
    public static final int VK_TAB = 9;
    public static final int VK_LEFT = 37;
    public static final int VK_RIGHT = 39;
    public static final int VK_UP = 38;
    public static final int VK_DOWN = 40;
    public static final int VK_BACK_QUOTE = 192;
    public static final int VK_QUOTE = 222;
    public static final int VK_PAUSE = 19;

    public static final int VK_B = 66;

    public static final int VK_C = 67;
    public static final int VK_D = 68;
    public static final int VK_G = 71;
    public static final int VK_V = 86;
    public static final int VK_Q = 81;

    public static final int VK_S = 83;

    public static final int VK_U = 85;

    public static final int VK_X = 88;
    public static final int VK_R = 82;

    public static final int VK_I = 73;

    public static final int VK_F11 = 122;
    public static final int VK_LWIN = 91;
    public static final int VK_LSHIFT = 160;
    public static final int VK_LCONTROL = 162;

    //Left ALT key
    public static final int VK_LMENU = 164;
    //ENTER key
    public static final int VK_RETURN = 13;

    public static final int VK_F4 = 115;

    public static final int VK_P = 80;

    public static final byte MODIFIER_SHIFT = 0x01;
    public static final byte MODIFIER_CTRL = 0x02;
    public static final byte MODIFIER_ALT = 0x04;
    public static final byte MODIFIER_META = 0x08;
    private static IInputManager inputManager;
    private static Method injectInputEventOtherScreensMethod;
    private static boolean singleAppMode;
    private static boolean externalMirrorMode;
    private static int externalMirrorDisplayId = Display.DEFAULT_DISPLAY;
    private static int lastFocusedDisplayId = Integer.MIN_VALUE;

    // 添加修饰键状态跟踪
    private static int currentMetaState = 0;
    
    private static void updateMetaState(int keycode, boolean pressed) {
        int mask = 0;
        switch (keycode) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
                mask = KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
                break;
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                mask = KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_RIGHT_ON;
                break;
            case KeyEvent.KEYCODE_CTRL_LEFT:
                mask = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
                break;
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                mask = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_RIGHT_ON;
                break;
            case KeyEvent.KEYCODE_ALT_LEFT:
                mask = KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
                break;
            case KeyEvent.KEYCODE_ALT_RIGHT:
                mask = KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON;
                break;
            case KeyEvent.KEYCODE_META_LEFT:
                mask = KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON;
                break;
            case KeyEvent.KEYCODE_META_RIGHT:
                mask = KeyEvent.META_META_ON | KeyEvent.META_META_RIGHT_ON;
                break;
        }
        
        if (pressed) {
            currentMetaState |= mask;
        } else {
            currentMetaState &= ~mask;
        }
    }

    public static void initialize() {
        Context context = State.getContext();
        if (context == null) {
            return;
        }
        if (ShizukuUtils.hasPermission()) {
            inputManager = ServiceUtils.getInputManager();
        }
        singleAppMode = Pref.getSingleAppMode();
        externalMirrorMode = !singleAppMode && false && State.externalDisplayId > 0;
        externalMirrorDisplayId = externalMirrorMode ? getExternalControlDisplayId() : Display.DEFAULT_DISPLAY;
        lastFocusedDisplayId = Integer.MIN_VALUE;
        // Moonlight 会话：尝试拉起 uinput 虚拟键盘，让系统把键盘当真实外接键盘，
        // Samsung IME 才会给拼音组合。失败自动回退 injectInputEvent。
        startUinputKeyboard();
    }

    // ---- uinput 虚拟键盘（Moonlight 中文输入） ----

    private static volatile boolean uinputActive = false;

    private static void startUinputKeyboard() {
        try {
            IUserService us = State.userService;
            if (us == null) {
                Log.w(TAG, "startUinputKeyboard: userService not bound");
                return;
            }
            uinputActive = us.startUinputKeyboard();
            Log.i(TAG, "startUinputKeyboard active=" + uinputActive);
        } catch (Throwable t) {
            Log.w(TAG, "startUinputKeyboard failed: " + t);
            uinputActive = false;
        }
    }

    public static void stopUinputKeyboard() {
        uinputActive = false;
        try {
            IUserService us = State.userService;
            if (us != null) {
                us.stopUinputKeyboard();
            }
        } catch (Throwable t) {
            Log.w(TAG, "stopUinputKeyboard failed: " + t);
        }
    }

    /**
     * Android keycode -> Linux evdev KEY_* code. The two numbering schemes are
     * different (KEYCODE_A=29 vs KEY_A=30), so this must be an explicit table,
     * not an offset. Covers letters, digits, symbols, F-keys, modifiers and
     * navigation keys used by physical keyboards.
     */
    private static int translateAndroidKeyToEvdev(int keycode) {
        switch (keycode) {
            // letters A-Z (evdev KEY_A=30, KEY_Z=44)
            case KeyEvent.KEYCODE_A: return 30; case KeyEvent.KEYCODE_B: return 48;
            case KeyEvent.KEYCODE_C: return 46; case KeyEvent.KEYCODE_D: return 32;
            case KeyEvent.KEYCODE_E: return 18; case KeyEvent.KEYCODE_F: return 33;
            case KeyEvent.KEYCODE_G: return 34; case KeyEvent.KEYCODE_H: return 35;
            case KeyEvent.KEYCODE_I: return 23; case KeyEvent.KEYCODE_J: return 36;
            case KeyEvent.KEYCODE_K: return 37; case KeyEvent.KEYCODE_L: return 38;
            case KeyEvent.KEYCODE_M: return 50; case KeyEvent.KEYCODE_N: return 49;
            case KeyEvent.KEYCODE_O: return 24; case KeyEvent.KEYCODE_P: return 25;
            case KeyEvent.KEYCODE_Q: return 16; case KeyEvent.KEYCODE_R: return 19;
            case KeyEvent.KEYCODE_S: return 31; case KeyEvent.KEYCODE_T: return 20;
            case KeyEvent.KEYCODE_U: return 22; case KeyEvent.KEYCODE_V: return 47;
            case KeyEvent.KEYCODE_W: return 17; case KeyEvent.KEYCODE_X: return 45;
            case KeyEvent.KEYCODE_Y: return 21; case KeyEvent.KEYCODE_Z: return 44;
            // digits row (KEY_1=2 ... KEY_0=11)
            case KeyEvent.KEYCODE_1: return 2; case KeyEvent.KEYCODE_2: return 3;
            case KeyEvent.KEYCODE_3: return 4; case KeyEvent.KEYCODE_4: return 5;
            case KeyEvent.KEYCODE_5: return 6; case KeyEvent.KEYCODE_6: return 7;
            case KeyEvent.KEYCODE_7: return 8; case KeyEvent.KEYCODE_8: return 9;
            case KeyEvent.KEYCODE_9: return 10; case KeyEvent.KEYCODE_0: return 11;
            // punctuation / symbols
            case KeyEvent.KEYCODE_MINUS: return 12;     // KEY_MINUS
            case KeyEvent.KEYCODE_EQUALS: return 13;    // KEY_EQUAL
            case KeyEvent.KEYCODE_LEFT_BRACKET: return 26;  // KEY_LEFTBRACE
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return 27; // KEY_RIGHTBRACE
            case KeyEvent.KEYCODE_SEMICOLON: return 39; // KEY_SEMICOLON
            case KeyEvent.KEYCODE_APOSTROPHE: return 40; // KEY_APOSTROPHE
            case KeyEvent.KEYCODE_GRAVE: return 41;     // KEY_GRAVE
            case KeyEvent.KEYCODE_BACKSLASH: return 43; // KEY_BACKSLASH
            case KeyEvent.KEYCODE_COMMA: return 51;     // KEY_COMMA
            case KeyEvent.KEYCODE_PERIOD: return 52;    // KEY_DOT
            case KeyEvent.KEYCODE_SLASH: return 53;     // KEY_SLASH
            // action / editing
            case KeyEvent.KEYCODE_SPACE: return 57;     // KEY_SPACE
            case KeyEvent.KEYCODE_ENTER: return 28;     // KEY_ENTER
            case KeyEvent.KEYCODE_TAB: return 15;       // KEY_TAB
            case KeyEvent.KEYCODE_DEL: return 14;       // KEY_BACKSPACE
            case KeyEvent.KEYCODE_FORWARD_DEL: return 111; // KEY_DELETE
            case KeyEvent.KEYCODE_ESCAPE: return 1;     // KEY_ESC
            case KeyEvent.KEYCODE_CAPS_LOCK: return 58; // KEY_CAPSLOCK
            case KeyEvent.KEYCODE_INSERT: return 110;   // KEY_INSERT
            case KeyEvent.KEYCODE_NUM_LOCK: return 69;  // KEY_NUMLOCK
            case KeyEvent.KEYCODE_SCROLL_LOCK: return 70; // KEY_SCROLLLOCK
            case KeyEvent.KEYCODE_SYSRQ: return 99;     // KEY_SYSRQ
            case KeyEvent.KEYCODE_BREAK: return 119;    // KEY_PAUSE
            // modifiers (left first)
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 42;   // KEY_LEFTSHIFT
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 54;  // KEY_RIGHTSHIFT
            case KeyEvent.KEYCODE_CTRL_LEFT: return 29;    // KEY_LEFTCTRL
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 97;   // KEY_RIGHTCTRL
            case KeyEvent.KEYCODE_ALT_LEFT: return 56;     // KEY_LEFTALT
            case KeyEvent.KEYCODE_ALT_RIGHT: return 100;   // KEY_RIGHTALT
            case KeyEvent.KEYCODE_META_LEFT: return 125;   // KEY_LEFTMETA
            case KeyEvent.KEYCODE_META_RIGHT: return 126;  // KEY_RIGHTMETA
            case KeyEvent.KEYCODE_MENU: return 127;        // KEY_COMPOSE (menu fallback)
            // navigation
            case KeyEvent.KEYCODE_MOVE_HOME: return 102;   // KEY_HOME
            case KeyEvent.KEYCODE_MOVE_END: return 107;    // KEY_END
            case KeyEvent.KEYCODE_PAGE_UP: return 104;     // KEY_PAGEUP
            case KeyEvent.KEYCODE_PAGE_DOWN: return 109;   // KEY_PAGEDOWN
            case KeyEvent.KEYCODE_DPAD_UP: return 103;     // KEY_UP
            case KeyEvent.KEYCODE_DPAD_DOWN: return 108;   // KEY_DOWN
            case KeyEvent.KEYCODE_DPAD_LEFT: return 105;   // KEY_LEFT
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 106;  // KEY_RIGHT
            // function keys F1-F12 (KEY_F1=59 ... KEY_F12=68)
            case KeyEvent.KEYCODE_F1: return 59; case KeyEvent.KEYCODE_F2: return 60;
            case KeyEvent.KEYCODE_F3: return 61; case KeyEvent.KEYCODE_F4: return 62;
            case KeyEvent.KEYCODE_F5: return 63; case KeyEvent.KEYCODE_F6: return 64;
            case KeyEvent.KEYCODE_F7: return 65; case KeyEvent.KEYCODE_F8: return 66;
            case KeyEvent.KEYCODE_F9: return 67; case KeyEvent.KEYCODE_F10: return 68;
            case KeyEvent.KEYCODE_F11: return 87; case KeyEvent.KEYCODE_F12: return 88;
            // numpad
            case KeyEvent.KEYCODE_NUMPAD_0: return 82; case KeyEvent.KEYCODE_NUMPAD_1: return 79;
            case KeyEvent.KEYCODE_NUMPAD_2: return 80; case KeyEvent.KEYCODE_NUMPAD_3: return 81;
            case KeyEvent.KEYCODE_NUMPAD_4: return 75; case KeyEvent.KEYCODE_NUMPAD_5: return 76;
            case KeyEvent.KEYCODE_NUMPAD_6: return 77; case KeyEvent.KEYCODE_NUMPAD_7: return 71;
            case KeyEvent.KEYCODE_NUMPAD_8: return 72; case KeyEvent.KEYCODE_NUMPAD_9: return 73;
            case KeyEvent.KEYCODE_NUMPAD_ADD: return 78;    // KEY_KPPLUS
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: return 74; // KEY_KPMINUS
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return 55; // KEY_KPASTERISK
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE: return 98;  // KEY_KPSLASH
            case KeyEvent.KEYCODE_NUMPAD_DOT: return 83;     // KEY_KPDOT
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return 96;   // KEY_KPENTER
            default: return -1;
        }
    }

    /**
     * Try to deliver a translated key via the uinput virtual keyboard. Returns
     * true if the event was handed off (so the caller can skip injectInputEvent),
     * false when the uinput path is unavailable so the caller falls back.
     */
    private static boolean trySendViaUinput(int androidKeyCode, boolean release) {
        if (!uinputActive) {
            return false;
        }
        IUserService us = State.userService;
        if (us == null) {
            uinputActive = false;
            return false;
        }
        int evdev = translateAndroidKeyToEvdev(androidKeyCode);
        if (evdev < 0) {
            // 没有对应 evdev 码的键仍走注入。
            return false;
        }
        try {
            boolean ok = us.sendUinputKey(evdev, release);
            if (!ok) {
                uinputActive = false;
            }
            if (DEBUG_INPUT_EVENTS) {
                Log.d(TAG, "sendUinputKey android=" + androidKeyCode
                        + " evdev=" + evdev + " release=" + release + " ok=" + ok);
            }
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "sendUinputKey failed: " + t);
            uinputActive = false;
            return false;
        }
    }

    private static boolean forwardEventToDisplay(KeyEvent event, int displayId) {
        if (inputManager == null || android.os.Build.VERSION.SDK_INT >= 28) {
            return false;
        }
        try {
            if (injectInputEventOtherScreensMethod == null) {
                injectInputEventOtherScreensMethod = IInputManager.class.getMethod(
                        "injectInputEventOtherScreens", android.view.InputEvent.class, int.class);
            }
            Boolean accepted = (Boolean) injectInputEventOtherScreensMethod.invoke(inputManager, event, 2);
            if (!Boolean.TRUE.equals(accepted)) {
                Log.w(TAG, "injectInputEventOtherScreens rejected event for displayId=" + displayId);
            }
            return Boolean.TRUE.equals(accepted);
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            Log.w(TAG, "injectInputEventOtherScreens failed for displayId=" + displayId + ": "
                    + cause.getClass().getSimpleName() + " " + cause.getMessage());
            return false;
        }
    }

    public static void handleKeyboardEvent(int modcode, boolean release, int _notUsed) {
        if(inputManager == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        int androidKeyCode = translateWindowsVKToAndroidKey(modcode);
        
        // 更新修饰键状态
        updateMetaState(androidKeyCode, !release);

        // 优先走 uinput 虚拟键盘（Moonlight 中文输入）：系统把它当真实外接键盘，
        // Samsung IME 才会组合拼音。映射到 evdev 码独立于测，确保按住/弹起成对。
        if (trySendViaUinput(androidKeyCode, release)) {
            // uinput 事件派发给当前焦点窗口；确保焦点在 DeX 屏上。
            int targetDisplayId = getTargetDisplayId();
            if (targetDisplayId != Display.DEFAULT_DISPLAY
                    && inputManager != null && lastFocusedDisplayId != targetDisplayId) {
                InputRouting.setFocus(inputManager, targetDisplayId);
                lastFocusedDisplayId = targetDisplayId;
            }
            return;
        }

        KeyEvent keyEvent = new KeyEvent(now, now, 
                release ? KeyEvent.ACTION_UP : KeyEvent.ACTION_DOWN,
                androidKeyCode, 0, currentMetaState, // 使用当前的修饰键状态
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        int targetDisplayId = getTargetDisplayId();
        if (targetDisplayId < 0) {
            return;
        }
        if (targetDisplayId != Display.DEFAULT_DISPLAY
                && android.os.Build.VERSION.SDK_INT < 28) {
            forwardEventToDisplay(keyEvent, targetDisplayId);
            if (lastFocusedDisplayId != targetDisplayId) {
                InputRouting.setFocus(inputManager, targetDisplayId);
                lastFocusedDisplayId = targetDisplayId;
            }
            if (DEBUG_INPUT_EVENTS) {
                Log.d(TAG, "handleKeyboardEvent (forwarder): " + modcode + " translated to " + keyEvent);
            }
            return;
        }
        if (targetDisplayId != Display.DEFAULT_DISPLAY) {
            KeyEventHidden keyEventHidden = Refine.unsafeCast(keyEvent);
            keyEventHidden.setDisplayId(targetDisplayId);
            if (lastFocusedDisplayId != targetDisplayId) {
                InputRouting.setFocus(inputManager, targetDisplayId);
                lastFocusedDisplayId = targetDisplayId;
            }
        }
        if (DEBUG_INPUT_EVENTS) {
            Log.d(TAG, "handleKeyboardEvent: " + modcode + " translated to " + keyEvent);
        }
        inputManager.injectInputEvent(keyEvent, 0);
    }

    private static int getTargetDisplayId() {
        if (singleAppMode) {
            if (State.mirrorVirtualDisplay == null) {
                return -1;
            }
            return State.mirrorVirtualDisplay.getDisplay().getDisplayId();
        }
        if (externalMirrorMode) {
            return externalMirrorDisplayId;
        }
        return Display.DEFAULT_DISPLAY;
    }

    private static int getExternalControlDisplayId() {
        return State.externalControlDisplayId > 0 ? State.externalControlDisplayId : State.externalDisplayId;
    }


    /**
     * Translates the given keycode and returns the GFE keycode
     * @param keycode the code to be translated
     * @return a GFE keycode for the given keycode
     */
    private static short translateAndroidKeyToWindowsVK(int keycode) {
        int translated;

        // This is a poor man's mapping between Android key codes
        // and Windows VK_* codes. For all defined VK_ codes, see:
        // https://msdn.microsoft.com/en-us/library/windows/desktop/dd375731(v=vs.85).aspx
        if (keycode >= KeyEvent.KEYCODE_0 &&
                keycode <= KeyEvent.KEYCODE_9) {
            translated = (keycode - KeyEvent.KEYCODE_0) + VK_0;
        }
        else if (keycode >= KeyEvent.KEYCODE_A &&
                keycode <= KeyEvent.KEYCODE_Z) {
            translated = (keycode - KeyEvent.KEYCODE_A) + VK_A;
        }
        else if (keycode >= KeyEvent.KEYCODE_NUMPAD_0 &&
                keycode <= KeyEvent.KEYCODE_NUMPAD_9) {
            translated = (keycode - KeyEvent.KEYCODE_NUMPAD_0) + VK_NUMPAD0;
        }
        else if (keycode >= KeyEvent.KEYCODE_F1 &&
                keycode <= KeyEvent.KEYCODE_F12) {
            translated = (keycode - KeyEvent.KEYCODE_F1) + VK_F1;
        }
        else {
            switch (keycode) {
                case KeyEvent.KEYCODE_ALT_LEFT:
                    translated = 0xA4;
                    break;

                case KeyEvent.KEYCODE_ALT_RIGHT:
                    translated = 0xA5;
                    break;

                case KeyEvent.KEYCODE_BACKSLASH:
                    translated = 0xdc;
                    break;

                case KeyEvent.KEYCODE_CAPS_LOCK:
                    translated = VK_CAPS_LOCK;
                    break;

                case KeyEvent.KEYCODE_CLEAR:
                    translated = VK_CLEAR;
                    break;

                case KeyEvent.KEYCODE_COMMA:
                    translated = 0xbc;
                    break;

                case KeyEvent.KEYCODE_CTRL_LEFT:
                    translated = 0xA2;
                    break;

                case KeyEvent.KEYCODE_CTRL_RIGHT:
                    translated = 0xA3;
                    break;

                case KeyEvent.KEYCODE_DEL:
                    translated = VK_BACK_SPACE;
                    break;

                case KeyEvent.KEYCODE_ENTER:
                    translated = 0x0d;
                    break;

                case KeyEvent.KEYCODE_PLUS:
                case KeyEvent.KEYCODE_EQUALS:
                    translated = 0xbb;
                    break;

                case KeyEvent.KEYCODE_ESCAPE:
                    translated = VK_ESCAPE;
                    break;

                case KeyEvent.KEYCODE_FORWARD_DEL:
                    translated = 0x2e;
                    break;

                case KeyEvent.KEYCODE_INSERT:
                    translated = 0x2d;
                    break;

                case KeyEvent.KEYCODE_LEFT_BRACKET:
                    translated = 0xdb;
                    break;

                case KeyEvent.KEYCODE_META_LEFT:
                    translated = 0x5b;
                    break;

                case KeyEvent.KEYCODE_META_RIGHT:
                    translated = 0x5c;
                    break;

                case KeyEvent.KEYCODE_MENU:
                    translated = 0x5d;
                    break;

                case KeyEvent.KEYCODE_MINUS:
                    translated = 0xbd;
                    break;

                case KeyEvent.KEYCODE_MOVE_END:
                    translated = VK_END;
                    break;

                case KeyEvent.KEYCODE_MOVE_HOME:
                    translated = VK_HOME;
                    break;

                case KeyEvent.KEYCODE_NUM_LOCK:
                    translated = VK_NUM_LOCK;
                    break;

                case KeyEvent.KEYCODE_PAGE_DOWN:
                    translated = VK_PAGE_DOWN;
                    break;

                case KeyEvent.KEYCODE_PAGE_UP:
                    translated = VK_PAGE_UP;
                    break;

                case KeyEvent.KEYCODE_PERIOD:
                    translated = 0xbe;
                    break;

                case KeyEvent.KEYCODE_RIGHT_BRACKET:
                    translated = 0xdd;
                    break;

                case KeyEvent.KEYCODE_SCROLL_LOCK:
                    translated = VK_SCROLL_LOCK;
                    break;

                case KeyEvent.KEYCODE_SEMICOLON:
                    translated = 0xba;
                    break;

                case KeyEvent.KEYCODE_SHIFT_LEFT:
                    translated = 0xA0;
                    break;

                case KeyEvent.KEYCODE_SHIFT_RIGHT:
                    translated = 0xA1;
                    break;

                case KeyEvent.KEYCODE_SLASH:
                    translated = 0xbf;
                    break;

                case KeyEvent.KEYCODE_SPACE:
                    translated = VK_SPACE;
                    break;

                case KeyEvent.KEYCODE_SYSRQ:
                    // Android defines this as SysRq/PrntScrn
                    translated = VK_PRINTSCREEN;
                    break;

                case KeyEvent.KEYCODE_TAB:
                    translated = VK_TAB;
                    break;

                case KeyEvent.KEYCODE_DPAD_LEFT:
                    translated = VK_LEFT;
                    break;

                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    translated = VK_RIGHT;
                    break;

                case KeyEvent.KEYCODE_DPAD_UP:
                    translated = VK_UP;
                    break;

                case KeyEvent.KEYCODE_DPAD_DOWN:
                    translated = VK_DOWN;
                    break;

                case KeyEvent.KEYCODE_GRAVE:
                    translated = VK_BACK_QUOTE;
                    break;

                case KeyEvent.KEYCODE_APOSTROPHE:
                    translated = 0xde;
                    break;

                case KeyEvent.KEYCODE_BREAK:
                    translated = VK_PAUSE;
                    break;

                case KeyEvent.KEYCODE_NUMPAD_DIVIDE:
                    translated = 0x6F;
                    break;

                case KeyEvent.KEYCODE_NUMPAD_MULTIPLY:
                    translated = 0x6A;
                    break;

                case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
                    translated = 0x6D;
                    break;

                case KeyEvent.KEYCODE_NUMPAD_ADD:
                    translated = 0x6B;
                    break;

                case KeyEvent.KEYCODE_NUMPAD_DOT:
                    translated = 0x6E;
                    break;

                default:
                    return 0;
            }
        }

        return (short) ((KEY_PREFIX << 8) | translated);
    }


    private static int translateWindowsVKToAndroidKey(int keycode) {
        // 移除 KEY_PREFIX
        int windowsKey = keycode & 0xFF;

        // 数字键 0-9
        if (windowsKey >= VK_0 && windowsKey <= VK_9) {
            return KeyEvent.KEYCODE_0 + (windowsKey - VK_0);
        }
        // 字母键 A-Z
        else if (windowsKey >= VK_A && windowsKey <= VK_Z) {
            return KeyEvent.KEYCODE_A + (windowsKey - VK_A);
        }
        // 小键盘数字 0-9
        else if (windowsKey >= VK_NUMPAD0 && windowsKey <= (VK_NUMPAD0 + 9)) {
            return KeyEvent.KEYCODE_NUMPAD_0 + (windowsKey - VK_NUMPAD0);
        }
        // 功能键 F1-F12
        else if (windowsKey >= VK_F1 && windowsKey <= VK_F12) {
            return KeyEvent.KEYCODE_F1 + (windowsKey - VK_F1);
        }

        // 其他特殊按键
        switch (windowsKey) {
            case 0xA4: return KeyEvent.KEYCODE_ALT_LEFT;
            case 0xA5: return KeyEvent.KEYCODE_ALT_RIGHT;
            case 0xDC: return KeyEvent.KEYCODE_BACKSLASH;
            case VK_CAPS_LOCK: return KeyEvent.KEYCODE_CAPS_LOCK;
            case VK_CLEAR: return KeyEvent.KEYCODE_CLEAR;
            case 0xBC: return KeyEvent.KEYCODE_COMMA;
            case 0xA2: return KeyEvent.KEYCODE_CTRL_LEFT;
            case 0xA3: return KeyEvent.KEYCODE_CTRL_RIGHT;
            case VK_BACK_SPACE: return KeyEvent.KEYCODE_DEL;
            case 0x0D: return KeyEvent.KEYCODE_ENTER;
            case 0xBB: return KeyEvent.KEYCODE_EQUALS;
            case VK_ESCAPE: return KeyEvent.KEYCODE_ESCAPE;
            case 0x2E: return KeyEvent.KEYCODE_FORWARD_DEL;
            case 0x2D: return KeyEvent.KEYCODE_INSERT;
            case 0xDB: return KeyEvent.KEYCODE_LEFT_BRACKET;
            case 0x5B: return KeyEvent.KEYCODE_META_LEFT;
            case 0x5C: return KeyEvent.KEYCODE_META_RIGHT;
            case 0x5D: return KeyEvent.KEYCODE_MENU;
            case 0xBD: return KeyEvent.KEYCODE_MINUS;
            case VK_END: return KeyEvent.KEYCODE_MOVE_END;
            case VK_HOME: return KeyEvent.KEYCODE_MOVE_HOME;
            case VK_NUM_LOCK: return KeyEvent.KEYCODE_NUM_LOCK;
            case VK_PAGE_DOWN: return KeyEvent.KEYCODE_PAGE_DOWN;
            case VK_PAGE_UP: return KeyEvent.KEYCODE_PAGE_UP;
            case 0xBE: return KeyEvent.KEYCODE_PERIOD;
            case 0xDD: return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case VK_SCROLL_LOCK: return KeyEvent.KEYCODE_SCROLL_LOCK;
            case 0xBA: return KeyEvent.KEYCODE_SEMICOLON;
            case 0xA0: return KeyEvent.KEYCODE_SHIFT_LEFT;
            case 0xA1: return KeyEvent.KEYCODE_SHIFT_RIGHT;
            case 0xBF: return KeyEvent.KEYCODE_SLASH;
            case VK_SPACE: return KeyEvent.KEYCODE_SPACE;
            case VK_PRINTSCREEN: return KeyEvent.KEYCODE_SYSRQ;
            case VK_TAB: return KeyEvent.KEYCODE_TAB;
            case VK_LEFT: return KeyEvent.KEYCODE_DPAD_LEFT;
            case VK_RIGHT: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case VK_UP: return KeyEvent.KEYCODE_DPAD_UP;
            case VK_DOWN: return KeyEvent.KEYCODE_DPAD_DOWN;
            case VK_BACK_QUOTE: return KeyEvent.KEYCODE_GRAVE;
            case 0xDE: return KeyEvent.KEYCODE_APOSTROPHE;
            case VK_PAUSE: return KeyEvent.KEYCODE_BREAK;
            case 0x6F: return KeyEvent.KEYCODE_NUMPAD_DIVIDE;
            case 0x6A: return KeyEvent.KEYCODE_NUMPAD_MULTIPLY;
            case 0x6D: return KeyEvent.KEYCODE_NUMPAD_SUBTRACT;
            case 0x6B: return KeyEvent.KEYCODE_NUMPAD_ADD;
            case 0x6E: return KeyEvent.KEYCODE_NUMPAD_DOT;
            default: return 0;
        }
    }
}

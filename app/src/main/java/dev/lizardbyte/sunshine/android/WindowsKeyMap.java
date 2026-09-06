package dev.lizardbyte.sunshine.android;

import android.view.KeyEvent;

public final class WindowsKeyMap {
    private WindowsKeyMap() {
    }

    public static int toAndroidKeyCode(int vk) {
        if (vk >= 0x30 && vk <= 0x39) {
            return KeyEvent.KEYCODE_0 + (vk - 0x30);
        }
        if (vk >= 0x41 && vk <= 0x5A) {
            return KeyEvent.KEYCODE_A + (vk - 0x41);
        }
        if (vk >= 0x70 && vk <= 0x7B) {
            return KeyEvent.KEYCODE_F1 + (vk - 0x70);
        }
        if (vk >= 0x60 && vk <= 0x69) {
            return KeyEvent.KEYCODE_NUMPAD_0 + (vk - 0x60);
        }
        switch (vk) {
            case 0x08:
                return KeyEvent.KEYCODE_DEL;
            case 0x09:
                return KeyEvent.KEYCODE_TAB;
            case 0x0D:
                return KeyEvent.KEYCODE_ENTER;
            case 0x10:
            case 0xA0:
                return KeyEvent.KEYCODE_SHIFT_LEFT;
            case 0xA1:
                return KeyEvent.KEYCODE_SHIFT_RIGHT;
            case 0x11:
            case 0xA2:
                return KeyEvent.KEYCODE_CTRL_LEFT;
            case 0xA3:
                return KeyEvent.KEYCODE_CTRL_RIGHT;
            case 0x12:
            case 0xA4:
                return KeyEvent.KEYCODE_ALT_LEFT;
            case 0xA5:
                return KeyEvent.KEYCODE_ALT_RIGHT;
            case 0x1B:
                return KeyEvent.KEYCODE_ESCAPE;
            case 0x20:
                return KeyEvent.KEYCODE_SPACE;
            case 0x21:
                return KeyEvent.KEYCODE_PAGE_UP;
            case 0x22:
                return KeyEvent.KEYCODE_PAGE_DOWN;
            case 0x23:
                return KeyEvent.KEYCODE_MOVE_END;
            case 0x24:
                return KeyEvent.KEYCODE_MOVE_HOME;
            case 0x25:
                return KeyEvent.KEYCODE_DPAD_LEFT;
            case 0x26:
                return KeyEvent.KEYCODE_DPAD_UP;
            case 0x27:
                return KeyEvent.KEYCODE_DPAD_RIGHT;
            case 0x28:
                return KeyEvent.KEYCODE_DPAD_DOWN;
            case 0x2D:
                return KeyEvent.KEYCODE_INSERT;
            case 0x2E:
                return KeyEvent.KEYCODE_FORWARD_DEL;
            case 0x5B:
            case 0x5C:
                return KeyEvent.KEYCODE_META_LEFT;
            case 0xBA:
                return KeyEvent.KEYCODE_SEMICOLON;
            case 0xBB:
                return KeyEvent.KEYCODE_EQUALS;
            case 0xBC:
                return KeyEvent.KEYCODE_COMMA;
            case 0xBD:
                return KeyEvent.KEYCODE_MINUS;
            case 0xBE:
                return KeyEvent.KEYCODE_PERIOD;
            case 0xBF:
                return KeyEvent.KEYCODE_SLASH;
            case 0xC0:
                return KeyEvent.KEYCODE_GRAVE;
            case 0xDB:
                return KeyEvent.KEYCODE_LEFT_BRACKET;
            case 0xDC:
                return KeyEvent.KEYCODE_BACKSLASH;
            case 0xDD:
                return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case 0xDE:
                return KeyEvent.KEYCODE_APOSTROPHE;
            default:
                return KeyEvent.KEYCODE_UNKNOWN;
        }
    }
}

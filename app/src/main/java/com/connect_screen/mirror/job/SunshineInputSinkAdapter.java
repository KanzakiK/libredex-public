package com.connect_screen.mirror.job;

import com.connect_screen.mirror.State;

import dev.lizardbyte.sunshine.android.SunshineInputSink;

/**
 * 官方 sunshine-android 输入回调适配（m5）。
 *
 * 官方 SunshineInputSink 的签名与现有 SunshineMouse/SunshineKeyboard 处理入口
 * 一一对应，直接转发复用全部注入逻辑；注入层保持不动，后续重编后再考虑 C++ 直连。
 */
public final class SunshineInputSinkAdapter implements SunshineInputSink {

    @Override
    public void onTouch(int eventType, int rotation, int pointerId,
                        float x, float y, float pressure, float major, float minor) {
        SunshineMouse.handleTouchPacket(eventType, rotation, pointerId, x, y, pressure, major, minor);
    }

    @Override
    public void onAbsoluteMouse(float x, float y, float width, float height) {
        SunshineMouse.handleAbsMouseMovePacket(x, y, width, height);
    }

    @Override
    public void onRelativeMouse(int dx, int dy) {
        SunshineMouse.handleRelMouseMovePacket(dx, dy);
    }

    @Override
    public void onMouseButton(int button, boolean release) {
        if (button == SunshineInputSink.MOUSE_BUTTON_LEFT) {
            SunshineMouse.handleLeftMouseButton(release);
        } else {
            State.log("[InputSinkAdapter] unsupported mouse button: " + button);
        }
    }

    @Override
    public void onMouseScroll(int vertical, int horizontal) {
        SunshineMouse.handleMouseScroll(vertical, horizontal);
    }

    @Override
    public void onKeyboard(int windowsKeyCode, boolean release, int flags) {
        SunshineKeyboard.handleKeyboardEvent(windowsKeyCode, release, flags);
    }

    @Override
    public void onUnicode(String text) {
        State.log("[InputSinkAdapter] unicode input: " + text);
    }

    @Override
    public void onGamepad(int controller, int buttons, int lt, int rt,
                          int lsX, int lsY, int rsX, int rsY) {
        State.log("[InputSinkAdapter] gamepad: c=" + controller + " buttons=" + buttons);
    }
}

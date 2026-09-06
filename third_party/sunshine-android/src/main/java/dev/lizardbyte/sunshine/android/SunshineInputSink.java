package dev.lizardbyte.sunshine.android;

public interface SunshineInputSink {
    int TOUCH_EVENT_DOWN = 0x01;
    int TOUCH_EVENT_UP = 0x02;
    int TOUCH_EVENT_MOVE = 0x03;
    int TOUCH_EVENT_CANCEL = 0x04;
    int TOUCH_EVENT_CANCEL_ALL = 0x07;

    int MOUSE_BUTTON_LEFT = 1;
    int MOUSE_BUTTON_MIDDLE = 2;
    int MOUSE_BUTTON_RIGHT = 3;

    void onTouch(int eventType, int rotation, int pointerId, float x, float y, float pressure, float major, float minor);

    void onAbsoluteMouse(float x, float y, float width, float height);

    void onRelativeMouse(int dx, int dy);

    void onMouseButton(int button, boolean release);

    void onMouseScroll(int vertical, int horizontal);

    void onKeyboard(int windowsKeyCode, boolean release, int flags);

    void onUnicode(String text);

    void onGamepad(int controller, int buttons, int lt, int rt, int lsX, int lsY, int rsX, int rsY);
}

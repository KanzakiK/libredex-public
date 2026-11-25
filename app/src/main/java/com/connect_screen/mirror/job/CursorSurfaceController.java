package com.connect_screen.mirror.job;

import com.connect_screen.mirror.State;
import com.connect_screen.mirror.shizuku.IUserService;

/**
 * Controls the root-owned TYPE_POINTER cursor window. A plain app-owned
 * SurfaceControl cannot be reparented into a virtual display's WMS overlay
 * tree on Samsung builds, so the cursor is created inside UserService (uid 0).
 */
public final class CursorSurfaceController {
    private static final String TAG = "LibreDeX cursor";
    private static int displayId = -1;
    private static float lastX;
    private static float lastY;

    private CursorSurfaceController() {
    }

    public static void attach(int targetDisplayId) {
        if (targetDisplayId < 0) {
            return;
        }
        displayId = targetDisplayId;
        IUserService service = State.userService;
        if (service == null) {
            State.log(TAG + ": attach skipped, service unavailable");
            return;
        }
        try {
            service.showLibreDeXCursor(targetDisplayId, Math.round(lastX), Math.round(lastY));
            State.log(TAG + ": attach display=" + targetDisplayId);
        } catch (Throwable t) {
            State.log(TAG + ": attach failed: " + t.getMessage());
        }
    }

    public static void move(float x, float y) {
        lastX = x;
        lastY = y;
        IUserService service = State.userService;
        if (service == null || displayId < 0) {
            return;
        }
        try {
            service.moveLibreDeXCursor(Math.round(x), Math.round(y));
        } catch (Throwable t) {
            State.log(TAG + ": move failed: " + t.getMessage());
        }
    }

    public static void followSystemPointer(int targetDisplayId) {
        attach(targetDisplayId);
    }

    public static void detach() {
        IUserService service = State.userService;
        if (service != null) {
            try {
                service.hideLibreDeXCursor();
            } catch (Throwable t) {
                State.log(TAG + ": detach failed: " + t.getMessage());
            }
        }
        displayId = -1;
        State.log(TAG + ": detached");
    }
}

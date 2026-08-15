package com.connect_screen.mirror.job;

import android.os.RemoteException;

import com.connect_screen.mirror.State;

/**
 * Marks whether a LibreDeX projection session is active. The system_server
 * fake-screen hook only intercepts the power key while a session is active, so
 * enabling the in-app switch does not change power-key behavior outside a
 * projection.
 */
public final class ScreenSession {
    private static final String SESSION_ACTIVE_PROP = "persist.dex.lspmirror.session_active";

    private ScreenSession() {
    }

    public static void setActive(boolean active) {
        if (State.userService == null) {
            return;
        }
        try {
            State.userService.executeShellCommand(
                    "setprop " + SESSION_ACTIVE_PROP + " " + (active ? 1 : 0));
            State.log("[ScreenSession] session_active=" + active);
        } catch (RemoteException e) {
            State.log("[ScreenSession] setprop failed: " + e.getMessage());
        }
    }
}

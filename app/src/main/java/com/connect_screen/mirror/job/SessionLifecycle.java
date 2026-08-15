package com.connect_screen.mirror.job;

import android.content.Context;

/**
 * Shared projection-session side effects. Every transport starts/stops through
 * this class so screen keepalive, session marker and aspect-ratio restore stay
 * in one place.
 */
public final class SessionLifecycle {

    private SessionLifecycle() {
    }

    public static void start(Context context, Object token) {
        ScreenKeepalive.applyPreventAutoLock(context);
        ScreenKeepalive.scheduleAutoScreenOff(context, token);
        ScreenSession.setActive(true);
    }

    public static void stop(Context context, Object token) {
        ScreenKeepalive.cancelAutoScreenOff(token);
        ScreenKeepalive.restorePreventAutoLock(context);
        ScreenSession.setActive(false);
        CreateVirtualDisplay.restoreAspectRatio();
    }
}

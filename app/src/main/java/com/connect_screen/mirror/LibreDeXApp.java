package com.connect_screen.mirror;

import android.app.Application;

import com.connect_screen.mirror.transport.TransportRegistry;

import rikka.shizuku.Shizuku;

public class LibreDeXApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        TransportRegistry.discover();
        Pref.init(this);
        int lastVersion = Pref.getLastRunVersionCode();
        int currentVersion = BuildConfig.VERSION_CODE;
        if (lastVersion != currentVersion) {
            Pref.setLastRunVersionCode(currentVersion);
            recycleStaleUserServiceAfterUpdate();
        } else {
            recycleStaleUserService();
        }
        if (Pref.getDpSessionStarted()) {
            State.ensureUserServiceBound();
        }
    }

    private void recycleStaleUserService() {
        // A Shizuku user service is a daemon: force-stopping or swiping away
        // the app does not destroy it, so a stale process can keep an old
        // virtual display alive. Always remove the previous service when the
        // app process starts fresh; a new one is started on demand.
        try {
            Shizuku.unbindUserService(State.userServiceArgs, State.userServiceConnection, true);
        } catch (Throwable ignored) {
        }
    }

    private void recycleStaleUserServiceAfterUpdate() {
        new Thread(() -> {
            try {
                State.bindUserService();
                long deadline = System.currentTimeMillis() + 3000;
                while (State.userService == null
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                if (State.userService != null) {
                    try {
                        State.userService.exit();
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            State.unbindUserService();
            State.userService = null;
            State.ensureUserServiceBound();
        }).start();
    }
}

package com.connect_screen.mirror;

import android.app.Application;

import rikka.shizuku.Shizuku;

public class LibreDeXApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Pref.init(this);
        recycleStaleUserService();
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
}

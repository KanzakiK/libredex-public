package com.connect_screen.mirror;

import android.app.Application;

import com.connect_screen.mirror.transport.TransportRegistry;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import rikka.shizuku.Shizuku;

public class LibreDeXApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        TransportRegistry.discover();
        Pref.init(this);
        applyPersistedLanguage();
        int lastVersion = Pref.getLastRunVersionCode();
        int currentVersion = BuildConfig.VERSION_CODE;
        String lastCommit = Pref.getLastRunCommit();
        String currentCommit = BuildConfig.COMMIT;
        // Restart the Shizuku UserService when the code changed: either the
        // version number bumped or the build commit differs (dev builds often
        // reinstall the same version with new code). Same-version same-commit
        // launches keep the daemon alive (avoids frequent restarts that could
        // trip the Samsung process manager).
        boolean codeChanged = lastVersion != currentVersion
                || !currentCommit.equals(lastCommit);
        Pref.setLastRunVersionCode(currentVersion);
        Pref.setLastRunCommit(currentCommit);
        if (codeChanged) {
            recycleStaleUserServiceAfterUpdate();
        } else {
            recycleStaleUserService();
        }
        if (Pref.getDpSessionStarted()) {
            State.ensureUserServiceBound();
        }
    }

    /**
     * Restore the user's language override ("system" or a BCP-47 tag) before
     * any activity is created. Mechanism is AppCompat's application-level
     * locales, so it works on every AppCompatActivity and survives process
     * death via Pref.
     */
    private void applyPersistedLanguage() {
        String lang = Pref.getLanguageMode();
        try {
            if (lang == null || lang.isEmpty() || "system".equals(lang)) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang));
            }
        } catch (Throwable ignored) {
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

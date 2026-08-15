package com.connect_screen.mirror.job;

import com.connect_screen.mirror.State;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

import rikka.shizuku.Shizuku;

public class AcquireShizuku implements Job {
    public static final int SHIZUKU_PERMISSION_REQUEST_CODE = 1001;
    private boolean hasRequestedPermission;
    public boolean acquired = false;

    @Override
    public void start() throws YieldException {
        if (!ShizukuUtils.hasShizukuStarted()) {
            return;
        }
        if (ShizukuUtils.hasPermission()) {
            State.log("Already have Shizuku permission");
            acquired = true;
            if (hasRequestedPermission) {
                State.bindUserService();
            }
        } else {
            if (hasRequestedPermission) {
                State.log("Failed to acquire Shizuku permission");
                return;
            }
            hasRequestedPermission = true;
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
            throw new YieldException("Waiting for Shizuku permission");
        }
    }

    public static void fixRootShizuku() {
        State.log("Keeping current Shizuku process; root mode will not be restarted");
    }
}

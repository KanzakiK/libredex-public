package com.connect_screen.mirror.shizuku;

import android.content.pm.PackageManager;

import rikka.shizuku.Shizuku;

public class ShizukuUtils {
    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch(Exception e) {
            return false;
        }
    }
    public static boolean hasShizukuStarted() {
        try {
            Shizuku.checkSelfPermission();
            return true;
        } catch(Exception e) {
            return false;
        }
    }

    /** server binder 真正可 ping（进程存在 ≠ 可用，授权持久化 ≠ 在线）。 */
    public static boolean isShizukuServerHealthy() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }
}

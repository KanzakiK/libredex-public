package com.connect_screen.mirror.job;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import com.connect_screen.mirror.State;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

import rikka.shizuku.Shizuku;

public class AcquireShizuku implements Job {
    public static final int SHIZUKU_PERMISSION_REQUEST_CODE = 1001;
    private static volatile boolean rootProbePassed;
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

    public static boolean fixRootShizuku() {
        try {
            if (State.userService != null && State.userService.isRooted()) {
                State.log("UserService already running as root");
                return true;
            }
        } catch (Throwable ignored) {
        }
        State.log("UserService is not root; restarting Shizuku as root");
        String su = findSu();
        if (su == null) {
            State.log("su binary not found; DP DeX needs Shizuku started as root");
            return false;
        }
        if (!probeRoot(su)) {
            State.log("su exists but root probe failed; grant LibreDeX root in KSU first");
            return false;
        }
        try {
            State.unbindUserService();
        } catch (Throwable ignored) {
        }
        String[] starter = findShizukuStarter();
        if (starter == null) {
            return false;
        }
        String out = runRootCommand(shellQuote(starter[0])
                + " --apk=" + shellQuote(starter[1]));
        State.log("root Shizuku starter out="
                + (out == null ? "null" : out.trim()));
        if (out == null || !out.contains("info: shizuku_starter exit with 0")) {
            State.log("root Shizuku starter failed, check Shizuku/root setup");
            return false;
        }
        // Let the old shell server die before waiting for the root server.
        sleep(1000);

        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Shizuku.pingBinder()) {
                    break;
                }
            } catch (Throwable ignored) {
            }
            sleep(300);
        }
        State.bindUserService();
        while (System.currentTimeMillis() < deadline) {
            if (State.isUserServiceAlive()) {
                break;
            }
            sleep(300);
        }
        try {
            if (State.userService != null && State.userService.isRooted()) {
                State.log("UserService restarted as root");
                return true;
            } else {
                State.log("UserService still not root after restart");
            }
        } catch (Throwable t) {
            State.log("UserService root check failed: " + t.getMessage());
        }
        return false;
    }

    private static String[] findShizukuStarter() {
        String pmOut = runRootCommand("pm path moe.shizuku.privileged.api");
        String apkPath = null;
        if (pmOut != null) {
            for (String line : pmOut.split("\n")) {
                String t = line.trim();
                if (t.startsWith("package:")) {
                    apkPath = t.substring("package:".length()).trim();
                    break;
                }
            }
        }
        if (apkPath == null || apkPath.isEmpty()) {
            State.log("Shizuku manager APK not found");
            return null;
        }
        File apkFile = new File(apkPath);
        String starter = apkFile.getParentFile() + "/lib/arm64/libshizuku.so";
        String check = runRootCommand("ls -l " + shellQuote(starter) + " 2>&1");
        if (check != null
                && !check.contains("No such file")
                && !check.contains("cannot access")) {
            return new String[]{starter, apkPath};
        }
        String lsOut = runRootCommand(
                "find /data/app -name libshizuku.so 2>/dev/null | head -1");
        if (lsOut != null) {
            for (String line : lsOut.split("\n")) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    return new String[]{t, apkPath};
                }
            }
        }
        State.log("libshizuku.so not found");
        return null;
    }

    private static boolean probeRoot(String su) {
        if (rootProbePassed) {
            return true;
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(su, "-c", "id");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            State.log("root probe exit=" + process.exitValue()
                    + " out=" + output.toString().trim());
            boolean ok = process.exitValue() == 0 && output.indexOf("uid=0") >= 0;
            rootProbePassed = ok;
            return ok;
        } catch (Throwable t) {
            State.log("root probe failed: " + t.getMessage());
            rootProbePassed = false;
            return false;
        }
    }

    public static String runRootCommand(String command) {
        String su = findSu();
        if (su == null || !probeRoot(su)) {
            return null;
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(su, "-c", command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            return output.toString();
        } catch (Throwable t) {
            State.log("runRootCommand failed: " + t.getMessage());
            return null;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String findSu() {
        String[] candidates = {
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/vendor/bin/su",
                "/product/bin/su",
                "/product/xbin/su",
                "/data/adb/ksu/bin/su",
                "/data/adb/ap/bin/su"
        };
        for (String candidate : candidates) {
            File f = new File(candidate);
            if (f.isFile() || f.canExecute()) {
                return candidate;
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(":")) {
                if (dir == null || dir.isEmpty()) {
                    continue;
                }
                String candidate = dir.endsWith("/") ? dir + "su" : dir + "/su";
                File f = new File(candidate);
                if (f.isFile() || f.canExecute()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}

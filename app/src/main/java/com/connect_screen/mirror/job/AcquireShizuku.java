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
        // 绑定 UserService 需要 Shizuku 已授权本应用；未授权时 bind 会抛
        // SecurityException（addUserService requires permission）直接崩线程，这里先做权限防护。
        if (ShizukuUtils.hasPermission()) {
            State.bindUserService();
        } else {
            State.log("Shizuku 未授权，无法绑定 root UserService");
            return false;
        }
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

    /**
     * 应用启动时在后台自动探活 root（纯 su，不依赖 Shizuku 的 shizuku_starter 机制）：
     * su 可用 → 视为已具备 root 能力（isRooted()/executeRootShellCommand 都基于 su），
     *           只尝试确保 UserService 已绑定（若 Shizuku 已授权则绑定，绝不主动重启 Shizuku）；
     * su 不可用 → 仅记录日志，Shizuku 授权兜底由 onResume 主线程的 AcquireShizuku 任务负责。
     * 全程后台线程，不阻塞主线程；失败只记录日志，不打扰用户。
     */
    public static void autoAcquireRoot() {
        new Thread(() -> {
            try {
                if (State.userService != null) {
                    try {
                        if (State.userService.isRooted()) {
                            State.log("启动自动 root 获取：已具备 root 能力，跳过");
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                // 直接 su 探活，不经过 Shizuku/root 重启。
                String su = findSu();
                if (su != null && probeRoot(su)) {
                    State.log("启动自动 root 获取：root 可用 (su=" + su + ")，确保 UserService 绑定");
                    // 仅当 Shizuku 已授权且未绑定时才绑定；绝不主动改 Shizuku 状态。
                    State.ensureUserServiceBound();
                } else {
                    State.log("启动自动 root 获取：无可用 root/su，退回主线程 Shizuku 授权流程");
                }
            } catch (Throwable t) {
                State.log("启动自动 root 获取失败: " + t.getMessage());
            }
        }, "auto-root-acquire").start();
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

package com.connect_screen.mirror.shizuku;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.display.IDisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import android.os.RemoteException;
import android.view.Display;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import com.connect_screen.mirror.job.AndroidVersions;
import com.connect_screen.mirror.job.CreateVirtualDisplay;
import com.connect_screen.mirror.BuildConfig;
import com.connect_screen.mirror.State;

import rikka.shizuku.SystemServiceHelper;

public class UserService extends IUserService.Stub  {
    private Context context;
    private boolean listenVolumeKey = false;
    private Process listenVolumeKeyProcess;
    private Thread volumeKeyThread;
    private AudioRecord audioRecord;
    private Object activeAudioPolicy;
    private float[] buffer;
    private VirtualDisplay mirrorVirtualDisplay;
    private IBinder mirrorExternalToken;
    private int dpMirrorSavedLayerStack = -1;
    private Rect dpMirrorRestoreRect;
    private int lastScreenOffDisplayId = Display.DEFAULT_DISPLAY;
    private static volatile String cachedSuPath;
    private static final String[] SU_BINARY_CANDIDATES = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/product/bin/su",
            "/product/xbin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su"
    };
    private static final String LOG_DIRECTORY_NAME = "LibreDeX/logs";
    private static final String LOG_FILE_PREFIX = "libredex-";
    private static final String LOG_FILE_SUFFIX = ".log";
    private static final String LOG_MARKER_FILE = ".last_logcat_time";
    private static final long MAX_DAILY_LOG_BYTES = 24L * 1024 * 1024;
    private static final long KEEP_DAILY_LOG_BYTES = 16L * 1024 * 1024;
    private static final long MAX_TOTAL_LOG_BYTES = 64L * 1024 * 1024;
    private static final int KEEP_LOG_DAYS = 7;

    public UserService() {
        Ln.i("Start UserService without context: uid=" + android.os.Process.myUid()
                + " sdk=" + Build.VERSION.SDK_INT + " su=" + findSuBinary());
    }

    @Keep
    public UserService(Context context) {
        this.context = context;
        Ln.i("Start UserService with context: uid=" + android.os.Process.myUid()
                + " sdk=" + Build.VERSION.SDK_INT + " su=" + findSuBinary());
    }

    /**
     * Reserved destroy method
     */
    @Override
    public void destroy() {
        Log.i("UserService", "destroy");
        stopListenVolumeKey();
        setScreenPower(SurfaceControl.POWER_MODE_NORMAL);
        if (audioRecord != null) {
            audioRecord.stop();
        }
        System.exit(0);
    }

    @Override
    public void exit() {
        destroy();
    }

    @Override
    public String fetchLogs() throws RemoteException {
        try {
            File logDir = getLogDirectory();
            if (!logDir.exists() && !logDir.mkdirs()) {
                throw new RemoteException("Failed to create log directory: " + logDir);
            }
            File markerFile = new File(logDir, LOG_MARKER_FILE);
            String since = readLogMarker(markerFile);
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("logcat");
            cmd.add("-d");
            cmd.add("-v");
            cmd.add("time");
            if (since != null && !since.isEmpty()) {
                cmd.add("-T");
                cmd.add(since);
            }
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            java.util.Map<String, java.io.BufferedWriter> writers =
                    new java.util.HashMap<>();
            String lastTime = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() < 18 || line.charAt(2) != '-' || line.charAt(5) != ' ') {
                    continue;
                }
                String monthDay = line.substring(0, 5);
                String day = getCurrentYear() + "-" + monthDay;
                lastTime = line.substring(0, 18);
                java.io.BufferedWriter writer = writers.get(day);
                if (writer == null) {
                    File daily = new File(logDir,
                            LOG_FILE_PREFIX + day + LOG_FILE_SUFFIX);
                    writer = new java.io.BufferedWriter(new java.io.FileWriter(daily, true));
                    writers.put(day, writer);
                }
                writer.write(line);
                writer.newLine();
            }
            reader.close();
            int exit = process.waitFor();
            for (java.io.BufferedWriter writer : writers.values()) {
                writer.close();
            }
            if (lastTime == null) {
                lastTime = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                        .format(new Date());
            }
            writeLogMarker(markerFile, lastTime);
            trimLogFiles(logDir);
            Ln.i("fetchLogs done dir=" + logDir + " exit=" + exit
                    + " since=" + since + " lines=" + writers.size());
            return logDir.getAbsolutePath();
        } catch (Exception e) {
            Log.e("UserService", "logcat export failed", e);
            throw new RemoteException("Failed to export logcat: " + e.getMessage());
        }
    }

    private static File getLogDirectory() {
        return new File("/sdcard/Download/" + LOG_DIRECTORY_NAME);
    }

    private static String readLogMarker(File marker) {
        if (!marker.exists()) {
            return null;
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(marker))) {
            String line = reader.readLine();
            return line == null ? null : line.trim();
        } catch (IOException e) {
            Ln.w("readLogMarker failed: " + e);
            return null;
        }
    }

    private static void writeLogMarker(File marker, String value) {
        try (java.io.BufferedWriter writer = new java.io.BufferedWriter(
                new java.io.FileWriter(marker, false))) {
            writer.write(value);
            writer.newLine();
        } catch (IOException e) {
            Ln.w("writeLogMarker failed: " + e);
        }
    }

    private static String getCurrentYear() {
        return String.valueOf(Calendar.getInstance().get(Calendar.YEAR));
    }

    private static void trimLogFiles(File dir) {
        File[] files = dir.listFiles((d, name) ->
                name.startsWith(LOG_FILE_PREFIX) && name.endsWith(LOG_FILE_SUFFIX));
        if (files == null || files.length == 0) {
            return;
        }
        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
        long cutoff = System.currentTimeMillis() - KEEP_LOG_DAYS * 86400000L;
        for (File file : files) {
            long fileTime = parseLogFileDate(file);
            if (fileTime > 0 && fileTime < cutoff) {
                file.delete();
            }
        }
        for (File file : files) {
            if (file.exists()) {
                try {
                    trimDailyFile(file);
                } catch (IOException e) {
                    Ln.w("trimDailyFile failed " + file + ": " + e);
                }
            }
        }
        long total = 0;
        for (File file : files) {
            if (file.exists()) {
                total += file.length();
            }
        }
        if (total > MAX_TOTAL_LOG_BYTES) {
            for (File file : files) {
                if (!file.exists()) {
                    continue;
                }
                total -= file.length();
                file.delete();
                if (total <= MAX_TOTAL_LOG_BYTES) {
                    break;
                }
            }
        }
    }

    private static void trimDailyFile(File file) throws IOException {
        long length = file.length();
        if (length <= MAX_DAILY_LOG_BYTES) {
            return;
        }
        long cut = length - KEEP_DAILY_LOG_BYTES;
        if (cut <= 0) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(cut);
            long pos = cut;
            int ch;
            while ((ch = raf.read()) != -1) {
                pos++;
                if (ch == '\n') {
                    break;
                }
            }
            if (pos >= length) {
                pos = cut;
            }
            byte[] tail = new byte[(int) (length - pos)];
            raf.seek(pos);
            raf.readFully(tail);
            raf.setLength(0);
            raf.seek(0);
            raf.write(tail);
        }
    }

    private static long parseLogFileDate(File file) {
        String name = file.getName();
        int dateStart = LOG_FILE_PREFIX.length();
        if (name.length() != dateStart + 10 + LOG_FILE_SUFFIX.length()) {
            return -1;
        }
        String date = name.substring(dateStart, dateStart + 10);
        try {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date).getTime();
        } catch (java.text.ParseException e) {
            return -1;
        }
    }

    @Override
    public String executeCommand(String command) throws RemoteException {
        try {
            return readProcessOutput(Runtime.getRuntime().exec(command), false);
        } catch (Exception e) {
            Log.e("UserService", "execute command failed: " + command, e);
            throw new RemoteException("Failed to execute command: " + command + " " + e.getMessage());
        }
    }

    @Override
    public String executeShellCommand(String command) throws RemoteException {
        try {
            ProcessBuilder builder = new ProcessBuilder("sh", "-c", command);
            builder.redirectErrorStream(true);
            String suDir = suBinaryDirectory();
            if (suDir != null) {
                Map<String, String> env = builder.environment();
                String path = env.get("PATH");
                env.put("PATH", suDir + (path == null || path.isEmpty() ? "" : ":" + path));
            }
            Process process = builder.start();
            return readProcessOutput(process, true);
        } catch (Exception e) {
            Log.e("UserService", "execute command failed: " + command, e);
            throw new RemoteException("Failed to execute command: " + command + " " + e.getMessage());
        }
    }

    @Override
    public String executeRootShellCommand(String command) throws RemoteException {
        String su = findSuBinary();
        if (su == null) {
            Ln.w("executeRootShellCommand: su binary not found, falling back to shell");
            return executeShellCommand(command);
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(su, "-c", command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String out = readProcessOutput(process, true);
            Ln.i("executeRootShellCommand exit=" + exitCodeFromOutput(out)
                    + " cmd=" + command + " out=" + out.trim());
            return out;
        } catch (Exception e) {
            Ln.e("executeRootShellCommand failed: " + command, e);
            throw new RemoteException("Failed to execute root command: " + command + " " + e.getMessage());
        }
    }

    @Override
    public String getEnvironmentInfo() throws RemoteException {
        StringBuilder sb = new StringBuilder();
        sb.append("uid=").append(android.os.Process.myUid()).append('\n');
        sb.append("su=").append(findSuBinary() == null ? "none" : findSuBinary()).append('\n');
        sb.append("sdk=").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("release=").append(Build.VERSION.RELEASE).append('\n');
        sb.append("incremental=").append(Build.VERSION.INCREMENTAL).append('\n');
        sb.append("fingerprint=").append(Build.FINGERPRINT).append('\n');
        sb.append("display=").append(Build.DISPLAY).append('\n');
        sb.append("oneui=").append(readSystemProp("ro.build.version.oneui")).append('\n');
        sb.append("secure=").append(readSystemProp("ro.secure")).append('\n');
        sb.append("debuggable=").append(readSystemProp("ro.debuggable")).append('\n');
        sb.append("verifiedboot=").append(readSystemProp("ro.boot.verifiedbootstate")).append('\n');
        return sb.toString();
    }

    /**
     * Collect a focused excerpt of LSPosed/XposedBridge log lines plus the
     * LSPosed daemon log files (e.g. /data/adb/lspd/log/*) when readable.
     * The LibreDeX module logs its hooks via these tags (see DexLspMirror
     * markers like "ensureDexRootOrder"), so bundling them lets the reporting
     * side correlate device/version/env with the actual hook behaviour.
     */
    @Override
    public String fetchLspLogs() throws RemoteException {
        StringBuilder sb = new StringBuilder();
        sb.append("===== logcat -s LSPosed XposedBridge (time) =====\n");
        appendProcessOutput(sb, buildLogcat());
        try {
            String su = findSuBinary();
            String prefix = su == null ? "" : (su + " -c ");
            String[] candidates = {
                    "/data/adb/lspd/log/modules.log",
                    "/data/adb/lspd/log/verbose.log",
                    "/data/adb/lspd/log/manager.log",
                    "/data/adb/lspd/log/main.log",
                    "/data/adb/lspd/log/lspd.log",
                    "/data/adb/lspd/log/bridge.log"
            };
            for (String path : candidates) {
                String out = runFileCat(prefix, path);
                if (out == null || out.trim().isEmpty()) {
                    continue;
                }
                sb.append("===== ").append(path).append(" =====\n").append(out);
                if (sb.charAt(sb.length() - 1) != '\n') {
                    sb.append('\n');
                }
            }
        } catch (Throwable t) {
            Ln.w("fetchLspLogs daemon files failed: " + t);
        }
        return sb.toString();
    }

    private void appendProcessOutput(StringBuilder sb, String out) {
        if (out == null) {
            sb.append("(empty)\n");
            return;
        }
        // Strip the __EXIT_CODE= marker appended by readProcessOutput.
        int idx = out.indexOf("__EXIT_CODE=");
        sb.append(idx >= 0 ? out.substring(0, idx) : out);
        if (sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
    }

    private String buildLogcat() {
        try {
            ProcessBuilder builder = new ProcessBuilder("logcat", "-d", "-v", "time",
                    "-s", "LSPosed", "XposedBridge", "libredex", "DexLspMirror", "UserService");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String out = readProcessOutput(process, false);
            Ln.i("fetchLspLogs logcat exit=" + process.waitFor() + " bytes=" + out.length());
            return out;
        } catch (Exception e) {
            Ln.e("fetchLspLogs logcat failed", e);
            return "";
        }
    }

    private String runFileCat(String suPrefix, String path) {
        try {
            String command = suPrefix + "cat " + path;
            ProcessBuilder builder = new ProcessBuilder("sh", "-c", command);
            builder.redirectErrorStream(true);
            String suDir = suBinaryDirectory();
            if (suDir != null) {
                Map<String, String> env = builder.environment();
                String pathEnv = env.get("PATH");
                env.put("PATH", suDir + (pathEnv == null || pathEnv.isEmpty() ? "" : ":" + pathEnv));
            }
            Process process = builder.start();
            String out = readProcessOutput(process, false);
            process.waitFor();
            int off = out.indexOf("__EXIT_CODE=");
            if (off >= 0) {
                out = out.substring(0, off);
            }
            return out;
        } catch (Exception e) {
            Ln.w("runFileCat failed for " + path + ": " + e);
            return null;
        }
    }

    private String readSystemProp(String name) {
        try {
            String out = executeShellCommand("getprop " + name);
            int idx = out == null ? -1 : out.indexOf("__EXIT_CODE=");
            return (idx >= 0 ? out.substring(0, idx) : out == null ? "" : out).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private String readProcessOutput(Process process, boolean includeExitCode) throws Exception {
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));

        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }

        reader.close();
        int exitCode = process.waitFor();
        if (includeExitCode) {
            output.append("__EXIT_CODE=").append(exitCode).append("\n");
        }

        return output.toString();
    }

    private static String findSuBinary() {
        String cached = cachedSuPath;
        if (cached != null && !cached.isEmpty()) {
            return cached.isEmpty() ? null : cached;
        }
        String found = null;
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(":")) {
                if (dir == null || dir.isEmpty()) {
                    continue;
                }
                String candidate = dir.endsWith("/") ? dir + "su" : dir + "/su";
                if (new File(candidate).canExecute()) {
                    found = candidate;
                    break;
                }
            }
        }
        if (found == null) {
            for (String candidate : SU_BINARY_CANDIDATES) {
                File f = new File(candidate);
                if (f.canExecute() || f.isFile()) {
                    found = candidate;
                    break;
                }
            }
        }
        if (found != null) {
            cachedSuPath = found;
        }
        return found;
    }

    private static String suBinaryDirectory() {
        String su = findSuBinary();
        if (su == null) {
            return null;
        }
        int slash = su.lastIndexOf('/');
        return slash > 0 ? su.substring(0, slash) : null;
    }

    private String runAsShell(String command) throws RemoteException {
        if (android.os.Process.myUid() == 0) {
            String su = findSuBinary();
            if (su == null) {
                throw new IllegalStateException("su binary not found");
            }
            try {
                ProcessBuilder builder = new ProcessBuilder(su, "2000", "-c", command);
                builder.redirectErrorStream(true);
                Process process = builder.start();
                process.waitFor();
                String out = readProcessOutput(process, true);
                Ln.i("runAsShell root shizuku exit=" + process.exitValue()
                        + " out=" + out.trim());
                return out;
            } catch (Exception e) {
                Ln.e("runAsShell root shizuku failed", e);
                throw new IllegalStateException("runAsShell failed", e);
            }
        }
        String su = findSuBinary();
        if (su != null) {
            try {
                // Same recipe as the root-spawned shell: root must fork the
                // command, otherwise WMS rejects SECONDARY_HOME on another
                // display and falls back to display 0.
                ProcessBuilder builder = new ProcessBuilder(
                        su, "-c", "su 2000 -c " + command);
                builder.redirectErrorStream(true);
                Process process = builder.start();
                String out = readProcessOutput(process, true);
                Ln.i("runAsShell nested su exit=" + exitCodeFromOutput(out)
                        + " out=" + out.trim());
                return out;
            } catch (Exception e) {
                Ln.w("runAsShell nested su failed, falling back to shell", e);
            }
        }
        return executeShellCommand(command);
    }

    private int exitCodeFromOutput(String out) {
        if (out == null) {
            return -1;
        }
        int idx = out.indexOf("__EXIT_CODE=");
        if (idx < 0) {
            return -1;
        }
        String tail = out.substring(idx + 12).trim();
        int nl = tail.indexOf('\n');
        if (nl >= 0) {
            tail = tail.substring(0, nl);
        }
        try {
            return Integer.parseInt(tail.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public boolean setScreenPower(int powerMode) {
        Log.i("UserService", "try to setScreenPower: " + powerMode);
        if (Build.VERSION.SDK_INT >= 35 && powerMode == SurfaceControl.POWER_MODE_NORMAL) {
            setScreenPowerViaNewApi(SurfaceControl.POWER_MODE_OFF);
            setScreenPowerViaNewApi(SurfaceControl.POWER_MODE_NORMAL);
        }
        try {
            // Resolve the main display by uniqueId instead of
            // physicalDisplayIds[0]; on the Flip5 the first physical id is the
            // cover, so the legacy no-arg path would power off the wrong panel.
            IBinder displayToken = getDisplayToken(Display.DEFAULT_DISPLAY);
            if (displayToken == null) {
                return false;
            }
            Ln.d("setDisplayPowerMode: " + displayToken + " " + powerMode);
            boolean result = SurfaceControl.setDisplayPowerMode(displayToken, powerMode);
            Ln.d("after setDisplayPowerMode: " + result);
        } catch(Throwable e) {
            Ln.e("setScreenPower failed", e);
        }
        return true;
    }

    @Override
    public boolean setScreenPowerForDisplay(int displayId, int powerMode) {
        Log.i("UserService", "try to setScreenPowerForDisplay displayId=" + displayId
                + " powerMode=" + powerMode);
        try {
            // Logical display 0 must keep the original token path
            // (physicalDisplayIds[0]); using getDisplayToken(0) can resolve to
            // a different token and silently fail the power-off call.
            IBinder displayToken = displayId == Display.DEFAULT_DISPLAY
                    ? getDisplayToken()
                    : getDisplayToken(displayId);
            if (displayToken == null) {
                Ln.e("setScreenPowerForDisplay: no token for displayId=" + displayId);
                return false;
            }
            Ln.d("setDisplayPowerMode displayId=" + displayId + " token=" + displayToken
                    + " mode=" + powerMode);
            boolean result = SurfaceControl.setDisplayPowerMode(displayToken, powerMode);
            Ln.d("after setDisplayPowerMode displayId=" + displayId + ": " + result);
            if (powerMode == SurfaceControl.POWER_MODE_OFF) {
                lastScreenOffDisplayId = displayId;
            }
            return true;
        } catch (Throwable e) {
            Ln.e("setScreenPowerForDisplay failed displayId=" + displayId, e);
            return false;
        }
    }

    @Override
    public boolean pressPowerKey() throws RemoteException {
        Ln.i("pressPowerKey");
        try {
            String out = executeShellCommand("input keyevent 26");
            boolean ok = out != null && out.contains("__EXIT_CODE=0");
            Ln.i("pressPowerKey result=" + ok + " out=" + (out == null ? "" : out.trim()));
            return ok;
        } catch (Throwable t) {
            Ln.e("pressPowerKey failed", t);
            return false;
        }
    }

    private boolean setScreenPowerViaNewApi(int powerMode) {
        IDisplayManager displayManager = IDisplayManager.Stub.asInterface(SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
        if (powerMode == SurfaceControl.POWER_MODE_OFF) {
            try {
                displayManager.requestDisplayPower(Display.DEFAULT_DISPLAY, false);
                Log.i("UserService", "requestDisplayPower by bool false");
            } catch(Throwable e) {
                Log.e("UserService", "failed to power off screen", e);
                try {
                    displayManager.requestDisplayPower(Display.DEFAULT_DISPLAY, SurfaceControl.POWER_MODE_OFF);
                    Log.i("UserService", "requestDisplayPower by int: " + powerMode);
                } catch(Throwable e2) {
                    Log.e("UserService", "failed to power off screen", e2);
                    return false;
                }
            }
        } else {
            try {
                displayManager.requestDisplayPower(Display.DEFAULT_DISPLAY, true);
                Log.i("UserService", "requestDisplayPower by bool true");
            } catch (Throwable e) {
                Log.e("UserService", "failed to power up screen", e);
                try {
                    displayManager.requestDisplayPower(Display.DEFAULT_DISPLAY, SurfaceControl.POWER_MODE_NORMAL);
                    Log.i("UserService", "requestDisplayPower by int: " + powerMode);
                } catch(Throwable e2) {
                    Log.e("UserService", "failed to power up screen", e2);
                    return false;
                }
            }
        }
        return true;
    }

    private @Nullable IBinder getDisplayToken() {
        try {
            long[] physicalDisplayIds = DisplayControl.getPhysicalDisplayIds();
            Ln.d("getDisplayToken: physicalDisplayIds count=" + physicalDisplayIds.length + " ids=" + java.util.Arrays.toString(physicalDisplayIds));
            if (physicalDisplayIds.length > 0) {
                Ln.d("getDisplayToken: 浣跨敤 physicalDisplayIds[0]=" + physicalDisplayIds[0]);
                return DisplayControl.getPhysicalDisplayToken(physicalDisplayIds[0]);
            }
            Ln.d("getDisplayToken: physicalDisplayIds 涓虹┖, 浣跨敤 getBuiltInDisplay");
            return SurfaceControl.getBuiltInDisplay();
        } catch (Throwable e) {
            Ln.e("failed to getDisplayToken", e);
            try {
                return SurfaceControl.getBuiltInDisplay();
            } catch (Throwable e2) {
                Ln.e("failed to getDisplayToken", e2);
            }
        }
        return null;
    }

    private @Nullable IBinder getDisplayToken(long displayId) {
        long physicalId = resolvePhysicalDisplayId(displayId);
        if (physicalId >= 0) {
            try {
                IBinder token = DisplayControl.getPhysicalDisplayToken(physicalId);
                if (token != null) {
                    Ln.d("getDisplayToken: logical display " + displayId
                            + " -> physical " + physicalId);
                    return token;
                }
            } catch (Throwable e) {
                Ln.e("getPhysicalDisplayToken failed for physical " + physicalId, e);
            }
        }
        // Fallback: physical id order is not guaranteed to match logical order
        // on foldables, so only use this when uniqueId mapping is unavailable.
        try {
            long[] physicalDisplayIds = DisplayControl.getPhysicalDisplayIds();
            Ln.d("getDisplayToken: physicalDisplayIds="
                    + java.util.Arrays.toString(physicalDisplayIds)
                    + " fallback for logical displayId=" + displayId);
            if (physicalDisplayIds != null && physicalDisplayIds.length > 0) {
                int index = displayId == Display.DEFAULT_DISPLAY ? 0 : 1;
                if (index < physicalDisplayIds.length) {
                    IBinder token = DisplayControl.getPhysicalDisplayToken(
                            physicalDisplayIds[index]);
                    if (token != null) {
                        Ln.d("getDisplayToken: physical id=" + physicalDisplayIds[index]
                                + " index=" + index + " for logical displayId=" + displayId);
                        return token;
                    }
                }
            }
        } catch (Throwable e) {
            Ln.e("getDisplayToken physical lookup failed for displayId=" + displayId, e);
        }
        try {
            IBinder token = SurfaceControl.getDisplayToken(displayId);
            Ln.d("getDisplayToken: SurfaceControl token for displayId=" + displayId
                    + " token=" + token);
            return token;
        } catch (Throwable e) {
            Ln.e("getDisplayToken failed for displayId=" + displayId, e);
        }
        return null;
    }

    private long resolvePhysicalDisplayId(long displayId) {
        try {
            long[] physicalDisplayIds = DisplayControl.getPhysicalDisplayIds();
            if (physicalDisplayIds == null || physicalDisplayIds.length == 0) {
                return -1;
            }
            IDisplayManager displayManager = IDisplayManager.Stub.asInterface(
                    SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
            android.view.DisplayInfo info = displayManager.getDisplayInfo((int) displayId);
            if (info != null && info.uniqueId != null && !info.uniqueId.isEmpty()) {
                int colon = info.uniqueId.lastIndexOf(':');
                String number = colon >= 0 ? info.uniqueId.substring(colon + 1) : info.uniqueId;
                long unique = Long.parseLong(number.trim());
                for (long id : physicalDisplayIds) {
                    if (id == unique) {
                        Ln.d("resolvePhysicalDisplayId: logical " + displayId
                                + " uniqueId=" + info.uniqueId + " -> physical " + id);
                        return id;
                    }
                }
            }
        } catch (Throwable e) {
            Ln.e("resolvePhysicalDisplayId failed for displayId=" + displayId, e);
        }
        return -1;
    }

    public void startListenVolumeKey() throws RemoteException {
        if (listenVolumeKey) {
            return;
        }
        listenVolumeKey = true;
        Thread thread = new Thread(() -> {
            while(listenVolumeKey) {
                try {
                    Ln.i("Run getevent to detect volume key pressed");
                    listenVolumeKeyProcess = Runtime.getRuntime().exec("getevent");
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(listenVolumeKeyProcess.getInputStream()));
                    while (listenVolumeKey) {
                        String line = reader.readLine();
                        if (line == null || !listenVolumeKey) {
                            Ln.i("break out getevent");
                            break;
                        }
                        if (!line.endsWith("0000 0000 00000000") &&
                                (line.endsWith("0001 0072 00000001") || line.endsWith("0001 0073 00000001"))) {
                            Ln.i("detected volume key, try to power on screen");
                            setScreenPowerForDisplay(
                                    lastScreenOffDisplayId, SurfaceControl.POWER_MODE_NORMAL);
                            if (context != null) {
                                Intent intent = new Intent("com.connect_screen.mirror.EXIT_PURE_BLACK");
                                intent.setPackage(BuildConfig.APPLICATION_ID);
                                context.sendBroadcast(intent);
                            } else {
                                Ln.i("context is null, can not send EXIT_PURE_BLACK");
                            }
                        }
                    }
                    reader.close();
                    if (listenVolumeKeyProcess != null) {
                        listenVolumeKeyProcess.waitFor();
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            listenVolumeKeyProcess.destroyForcibly();
                        } else {
                            listenVolumeKeyProcess.destroy();
                        }
                    }
                } catch (Exception e) {
                    Ln.e("Listen volume key failed", e);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
            Ln.i("getevent thread end");
        });
        volumeKeyThread = thread;
        thread.start();
    }

    public void stopListenVolumeKey() {
        listenVolumeKey = false;
        if (listenVolumeKeyProcess != null) {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                listenVolumeKeyProcess.destroyForcibly();
            } else {
                listenVolumeKeyProcess.destroy();
            }
            listenVolumeKeyProcess = null;
        }
        if (volumeKeyThread != null) {
            volumeKeyThread.interrupt();
            volumeKeyThread = null;
        }
    }

    @Override
    public int createVirtualDisplay(Surface surface) throws RemoteException {
        Ln.i("try to createVirtualDisplay");
        try {
            return DisplayManager.create().createNewVirtualDisplay("test", 1920, 1080, 160, surface, CreateVirtualDisplay.getFlags(true, true)).getDisplay().getDisplayId();
        } catch (Throwable e) {
            Ln.e("failed to create virtual display", e);
        }
        return 0;
    }


    @Override
    public boolean isRooted() throws RemoteException {
        return android.os.Process.myUid() == 0 || findSuBinary() != null;
    }

    @Override
    public int readAudio(float[] result) throws RemoteException {
        try {
            if (audioRecord == null) {
                return 0;
            }
            int n = audioRecord.read(result, 0, result.length, AudioRecord.READ_BLOCKING);
            return n;
        } catch(Throwable e) {
            Ln.e("failed to read audio", e);
            return 0;
        }
    }

    @Override
    public boolean startRecordingAudio() throws RemoteException {
        try {
            if (audioRecord == null) {
                Ln.d("before start recording");
                audioRecord = createAudioRecord();
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Ln.e("REMOTE_SUBMIX AudioRecord not initialized state=" + audioRecord.getState());
                    audioRecord.release();
                    audioRecord = null;
                    releaseAudioPolicy();
                    return false;
                }
                audioRecord.startRecording();
                if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    Ln.e("REMOTE_SUBMIX AudioRecord failed to start state=" + audioRecord.getRecordingState());
                    audioRecord.stop();
                    audioRecord.release();
                    audioRecord = null;
                    releaseAudioPolicy();
                    return false;
                }
                Ln.d("started recording");
                return true;
            } else {
                return true;
            }
        } catch(Throwable e) {
            Ln.e("failed to start recording audio", e);
            if (audioRecord != null) {
                try {
                    audioRecord.release();
                } catch (Throwable ignored) {
                }
                audioRecord = null;
            }
            releaseAudioPolicy();
            return false;
        }
    }

    @Override
    public boolean stopRecordingAudio() throws RemoteException {
        try {
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }
            releaseAudioPolicy();
            return true;
        } catch(Throwable e) {
            Ln.e("failed to stop recording audio", e);
            return false;
        }
    }

    private void releaseAudioPolicy() {
        Object policy = activeAudioPolicy;
        activeAudioPolicy = null;
        if (policy == null) {
            return;
        }
        try {
            Context ctx = context != null ? context : FakeContext.get();
            AudioManager audioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                Method unregister = AudioManager.class.getMethod(
                        "unregisterAudioPolicy", policy.getClass());
                unregister.invoke(audioManager, policy);
                Ln.i("unregistered AudioPolicy");
                return;
            }
        } catch (Throwable t) {
            Ln.e("failed to unregister AudioPolicy", t);
        }
        try {
            policy.getClass().getMethod("close").invoke(policy);
            Ln.i("closed AudioPolicy");
        } catch (Throwable t2) {
            Ln.e("failed to close AudioPolicy", t2);
        }
    }

    @Override
    public boolean forceTntAudioRoute(boolean enabled) throws RemoteException {
        Ln.i("forceTntAudioRoute: enabled=" + enabled);
        try {
            Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");
            Class<?> attrsClass = Class.forName("android.media.AudioDeviceAttributes");
            java.lang.reflect.Method setDeviceConnectionState = audioSystemClass.getMethod(
                    "setDeviceConnectionState", attrsClass, int.class, int.class, boolean.class);
            java.lang.reflect.Method setForceUse = audioSystemClass.getMethod(
                    "setForceUse", int.class, int.class);
            final int deviceStateAvailable = 1;
            final int deviceStateUnavailable = 0;
            final int cause = 0;
            final int forceMedia = 1;
            final int forceTnt = 0x22;
            final int forceNone = 0;
            // AudioDeviceAttributes only accepts public AudioDeviceInfo types;
            // REMOTE_SUBMIX is 25 here. The hidden setDeviceConnectionState may
            // still reject it on some builds, but it must not crash.
            Object attrs = attrsClass.getConstructor(int.class, int.class, String.class)
                    .newInstance(2, 25, "remote_submix");
            if (enabled) {
                int rcDevice = (Integer) setDeviceConnectionState.invoke(
                        null, attrs, deviceStateAvailable, cause, Boolean.FALSE);
                int rcForce = (Integer) setForceUse.invoke(null, forceMedia, forceTnt);
                Ln.i("forceTntAudioRoute: device=" + rcDevice + " force=" + rcForce);
                return rcDevice == 0 && rcForce == 0;
            }
            int rcForce = (Integer) setForceUse.invoke(null, forceMedia, forceNone);
            int rcDevice = (Integer) setDeviceConnectionState.invoke(
                    null, attrs, deviceStateUnavailable, cause, Boolean.FALSE);
            Ln.i("forceTntAudioRoute restore: force=" + rcForce + " device=" + rcDevice);
            return rcForce == 0 && rcDevice == 0;
        } catch (Throwable e) {
            Ln.e("forceTntAudioRoute failed", e);
            return false;
        }
    }

    @Override
    public IBinder createDisplay(String name, boolean secure) throws RemoteException {
        try {
            return SurfaceControl.createDisplay(name, secure);
        } catch (Exception e) {
            Ln.e("createDisplay failed", e);
            return null;
        }
    }

    @Override
    public int createExternalMirror(String name, int width, int height, int displayIdToMirror, int frameRate, Surface surface) throws RemoteException {
        Ln.i("createExternalMirror: name=" + name + " mirroring displayId=" + displayIdToMirror
                + " fps=" + frameRate + " sdk=" + Build.VERSION.SDK_INT);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return createExternalMirrorApi31(name, width, height, displayIdToMirror, frameRate, surface);
            } else {
                return createExternalMirrorApi30(name, width, height, displayIdToMirror, surface);
            }
        } catch (Exception e) {
            Ln.e("createExternalMirror failed", e);
            return -1;
        }
    }

    @Override
    public int createDexMirror(String name, int width, int height, int frameRate, Surface surface) throws RemoteException {
        Ln.i("createDexMirror: name=" + name + " w=" + width + " h=" + height + " fps=" + frameRate);
        try {
            if (mirrorVirtualDisplay != null) {
                mirrorVirtualDisplay.release();
                mirrorVirtualDisplay = null;
            }
            Class<?> builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            java.lang.reflect.Constructor<?> ctor = builderClass.getConstructor(
                    String.class, int.class, int.class, int.class);
            Object builder = ctor.newInstance(name, width, height, 160);
            // Mirror the upstream display flags: trusted, focus-owning, always
            // unlocked, touch-capable. DEVICE_DISPLAY_GROUP stays off so the
            // fake DeX display does not rejoin the default display group and
            // kick LibreDeX back to the main launcher.
            int flags = 0x4000 | 0x400 | 0x2000 | 0x1000
                    | 0x100 | 0x40 | 0x8 | 0x2 | 0x1
                    | 0x4000000 | 0x8000000;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Keep the fake DeX virtual display in its own display group so
                // launching SECONDARY_HOME on it cannot activate the main
                // display's HOME task and kick LibreDeX to the launcher.
                // DEVICE_DISPLAY_GROUP must stay off: it would put the VD back
                // into the default display group and defeat this isolation.
                flags |= 0x800; // VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
            }
            Ln.i("createDexMirror flags=0x" + Integer.toHexString(flags));
            builderClass.getMethod("setFlags", int.class).invoke(builder, flags);
            builderClass.getMethod("setSurface", Surface.class).invoke(builder, surface);
            builderClass.getMethod("setDisplayIdToMirror", int.class).invoke(builder, -1);
            // Mark the fake DeX display as home-supported so the system treats
            // it as a real DeX desktop. On One UI 8.5 / Android 16 the builder
            // API was renamed setIsHomeSupported -> setHomeSupported (see
            // firmware firmware.jar: VirtualDisplayConfig$Builder), so try the
            // new name first and fall back to the legacy one on older builds.
            boolean homeFlagSet = false;
            try {
                builderClass.getMethod("setHomeSupported", boolean.class).invoke(builder, true);
                Ln.i("createDexMirror setHomeSupported=true OK");
                homeFlagSet = true;
            } catch (Throwable t8) {
                Ln.w("createDexMirror setHomeSupported unavailable: " + t8);
            }
            if (!homeFlagSet) {
                try {
                    builderClass.getMethod("setIsHomeSupported", boolean.class).invoke(builder, true);
                    Ln.i("createDexMirror setIsHomeSupported=true OK (legacy)");
                    homeFlagSet = true;
                } catch (Throwable tLegacy) {
                    Ln.w("createDexMirror setIsHomeSupported unavailable: " + tLegacy);
                }
            }
            try {
                float refreshRate = frameRate > 0 ? Math.min(frameRate, 120f) : 60f;
                builderClass.getMethod("setRequestedRefreshRate", float.class)
                        .invoke(builder, refreshRate);
            } catch (Throwable t) {
                Ln.w("setRequestedRefreshRate unavailable: " + t.getMessage());
            }
            Object config = builderClass.getMethod("build").invoke(builder);
            Ln.i("createDexMirror config=" + config);

            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object service = sm.getMethod("getService", String.class)
                    .invoke(null, "display");
            Class<?> stub = Class.forName("android.hardware.display.IDisplayManager$Stub");
            Object dm = stub.getMethod("asInterface", android.os.IBinder.class)
                    .invoke(null, (android.os.IBinder) service);
            Class<?> vdConfigClass = Class.forName("android.hardware.display.VirtualDisplayConfig");
            Class<?> iCbClass = Class.forName("android.hardware.display.IVirtualDisplayCallback");
            Class<?> iProjClass = Class.forName("android.media.projection.IMediaProjection");
            Class<?> cbClass = Class.forName("android.hardware.display.DisplayManagerGlobal$VirtualDisplayCallback");
            java.lang.reflect.Constructor<?> cbCtor = cbClass.getDeclaredConstructor(
                    android.hardware.display.VirtualDisplay.Callback.class,
                    java.util.concurrent.Executor.class);
            cbCtor.setAccessible(true);
            Object cb = cbCtor.newInstance(null, null);
            int rc = (Integer) dm.getClass().getMethod("createVirtualDisplay",
                    vdConfigClass, iCbClass, iProjClass, String.class)
                    .invoke(dm, config, cb, null, FakeContext.PACKAGE_NAME);
            Ln.i("createDexMirror raw rc=" + rc);
            if (rc < 0) {
                return rc;
            }
            Class<?> dmGlobalClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object global = dmGlobalClass.getMethod("getInstance").invoke(null);
            mirrorVirtualDisplay = (android.hardware.display.VirtualDisplay) dmGlobalClass.getMethod(
                    "createVirtualDisplayWrapper", vdConfigClass, iCbClass, int.class)
                    .invoke(global, config, cb, rc);
            if (mirrorVirtualDisplay == null || mirrorVirtualDisplay.getDisplay() == null) {
                Ln.e("createDexMirror wrapper returned null display");
                return -1;
            }
            int vdId = mirrorVirtualDisplay.getDisplay().getDisplayId();
            Ln.i("createDexMirror success id=" + vdId);
            logWallpaperDisplayProbe(dm, vdId);
            try {
                // Force the LSPosed module to register this display as a DeX
                // display before launching the home activity. Without this the
                // first HOME launch can fall back to display 0.
                dm.getClass().getMethod("getDisplayInfo", int.class).invoke(dm, vdId);
                Ln.i("createDexMirror queried display info id=" + vdId);
            } catch (Throwable t) {
                Ln.w("createDexMirror display info query failed: " + t.getMessage());
            }
            ensureSecondaryLauncherOnDisplay(vdId, width, height);
            return vdId;
        } catch (Throwable t) {
            Ln.e("createDexMirror failed", t);
            return -1;
        }
    }

    @Override
    public void restartSecondaryLauncher(int displayId, int width, int height) throws RemoteException {
        Ln.i("restartSecondaryLauncher: displayId=" + displayId);
        try {
            String out = runAsShell("am force-stop com.sec.android.app.launcher");
            Ln.i("restartSecondaryLauncher force-stop launcher exit="
                    + exitCodeFromOutput(out) + " out=" + out.trim());
        } catch (Throwable t) {
            Ln.e("restartSecondaryLauncher force-stop failed", t);
        }
        try {
            Thread.sleep(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ensureSecondaryLauncherOnDisplay(displayId, width, height);
    }

    @Override
    public void startSecondaryLauncher(int displayId, int width, int height) throws RemoteException {
        Ln.i("startSecondaryLauncher: displayId=" + displayId + " " + width + "x" + height);
        ensureSecondaryLauncherOnDisplay(displayId, width, height);
    }

    @Override
    public int stopSecondaryLauncher(int displayId) throws RemoteException {
        Ln.i("stopSecondaryLauncher: displayId=" + displayId);
        try {
            if (displayId > 0) {
                moveAppTasksToDefaultDisplay(displayId);
            }
            String stackId = findSecondaryLauncherRootTaskId();
            if (stackId != null && !stackId.isEmpty()) {
                String out = executeShellCommand("am stack remove " + stackId);
                Ln.i("stopSecondaryLauncher am stack remove " + stackId
                        + " -> " + out.trim());
                Thread.sleep(700);
            }
            String taskId = findSecondaryLauncherTaskId();
            if (taskId != null && !taskId.isEmpty()) {
                removeTaskById(Integer.parseInt(taskId));
                Thread.sleep(700);
            }
            if (isSecondaryLauncherOnDisplay(displayId)) {
                String out = runAsShell("am force-stop com.sec.android.app.launcher");
                Ln.i("stopSecondaryLauncher force-stop fallback -> " + out.trim());
                Thread.sleep(1200);
            }
            boolean gone = !isSecondaryLauncherOnDisplay(displayId);
            Ln.i("stopSecondaryLauncher done displayId=" + displayId + " gone=" + gone);
            return gone ? 0 : -1;
        } catch (Throwable t) {
            Ln.e("stopSecondaryLauncher failed", t);
            return -1;
        }
    }

    private void removeTaskById(int taskId) {
        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            Object service = atmClass.getMethod("getService").invoke(null);
            service.getClass().getMethod("removeTask", int.class).invoke(service, taskId);
            Ln.i("removeTaskById success taskId=" + taskId);
        } catch (Throwable t) {
            Ln.e("removeTaskById failed taskId=" + taskId, t);
        }
    }

    private void moveAppTasksToDefaultDisplay(int displayId) {
        try {
            String out = executeShellCommand("dumpsys activity activities");
            int exitIdx = out.indexOf("__EXIT_CODE");
            if (exitIdx >= 0) {
                out = out.substring(0, exitIdx);
            }
            java.util.Set<String> stackIds = new java.util.LinkedHashSet<>();
            boolean inTarget = false;
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (t.startsWith("Display #") && t.contains("(activities from top to bottom)")) {
                    inTarget = t.startsWith("Display #" + displayId + " ");
                    continue;
                }
                if (!inTarget) {
                    continue;
                }
                Matcher m = Pattern.compile(
                        "Task\\{[^}]*#\\d+ type=standard[^}]*rootTaskId=(\\d+)")
                        .matcher(t);
                if (m.find()) {
                    stackIds.add(m.group(1));
                }
            }
            for (String stackId : stackIds) {
                String rc = executeShellCommand("am display move-stack " + stackId + " 0");
                Ln.i("moveAppTasksToDefaultDisplay stack " + stackId
                        + " -> 0: " + rc.trim());
                Thread.sleep(300);
            }
        } catch (Throwable t) {
            Ln.e("moveAppTasksToDefaultDisplay failed", t);
        }
    }

    @Override
    public String getHdmiCfgIdx() throws RemoteException {
        String out = executeShellCommand("getprop vendor.display.hdmi_cfg_idx");
        if (out == null) {
            return "";
        }
        int marker = out.indexOf("__EXIT_CODE=");
        return (marker >= 0 ? out.substring(0, marker) : out).trim();
    }

    @Override
    public String setHdmiCfgIdx(String value) throws RemoteException {
        String quoted = (value == null || value.isEmpty())
                ? "\"\""
                : "'" + value.replace("'", "'\\''") + "'";
        return executeShellCommand("setprop vendor.display.hdmi_cfg_idx " + quoted);
    }

    @Override
    public int applyExternalDisplayMode(int displayId, int width, int height, int refresh) throws RemoteException {
        Ln.i("applyExternalDisplayMode: displayId=" + displayId + " " + width + "x" + height + "@" + refresh);
        String probePath = ensureQtiProbeBinary();
        if (probePath == null) {
            Ln.w("applyExternalDisplayMode: qti-display-probe binary unavailable");
            return -1;
        }
        try {
            String json = runQtiProbe(probePath);
            Ln.i("applyExternalDisplayMode: probe json=" + safeJsonLog(json));
            DrmMode match = findDrmMode(json, width, height, refresh);
            if (match == null) {
                Ln.w("applyExternalDisplayMode: no matching DRM mode for "
                        + width + "x" + height + "@" + refresh);
                return -1;
            }
            String value = width + ":" + height + ":" + refresh + ":" + match.selector;
            setHdmiCfgIdx(value);
            String readback = getHdmiCfgIdx();
            Ln.i("applyExternalDisplayMode: hdmi_cfg_idx readback=" + readback + " expected=" + value);
            return value.equals(readback) ? 1 : -1;
        } catch (Throwable t) {
            Ln.e("applyExternalDisplayMode failed", t);
        }
        return -1;
    }

    private static final String QTI_PROBE_ASSET = "native/arm64-v8a/qti-display-probe";
    private static final String QTI_PROBE_FILE_NAME = "qti-display-probe";
    private static final String QTI_PROBE_LIB_PATH = "/vendor/lib64:/system_ext/lib64";
    private static final double REFRESH_MATCH_TOLERANCE_HZ = 1.0d;

    private String ensureQtiProbeBinary() {
        try {
            // The connect-screen process runs in the shell SELinux domain,
            // which has no write access to the app data dir
            // (/data/user/0/<pkg>/files -> EACCES). /data/local/tmp is the
            // standard writable location for the shell domain and survives
            // reboots, so stage the probe there.
            File dir = new File("/data/local/tmp");
            File file = new File(dir, QTI_PROBE_FILE_NAME);
            if (file.exists() && file.length() > 0) {
                return file.getAbsolutePath();
            }
            try (java.io.InputStream in = context.getAssets().open(QTI_PROBE_ASSET);
                 FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
            }
            if (!file.setExecutable(true, false)) {
                Ln.w("ensureQtiProbeBinary: chmod failed " + file);
            }
            Ln.i("ensureQtiProbeBinary: extracted " + file + " size=" + file.length());
            return file.getAbsolutePath();
        } catch (Throwable t) {
            Ln.e("ensureQtiProbeBinary failed", t);
            return null;
        }
    }

    private String runQtiProbe(String path) throws RemoteException {
        // Run the probe through su: the connect-screen process currently runs
        // in the shell SELinux domain, which is denied access to /dev/dri/card0
        // ("Permission denied (13)"), so a plain sh -c probe gets no DRM modes.
        String command = "su -c \"env LD_LIBRARY_PATH=" + QTI_PROBE_LIB_PATH + " "
                + shellQuote(path) + " diag external\"";
        String out = executeShellCommand(command);
        if (out == null) {
            return "{\"ok\":false,\"error\":\"probe command returned null\"}";
        }
        int marker = out.indexOf("__EXIT_CODE=");
        return marker >= 0 ? out.substring(0, marker) : out;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private DrmMode findDrmMode(String json, int width, int height, int refresh)
            throws org.json.JSONException {
        org.json.JSONObject root = new org.json.JSONObject(json);
        org.json.JSONObject drm = root.optJSONObject("drm");
        if (drm == null || !drm.optBoolean("ok", false)) {
            String reason = drm == null ? "missing drm object" : drm.optString("error", "probe failed");
            Ln.w("findDrmMode: drm probe unavailable: " + reason);
            return null;
        }
        org.json.JSONArray connectors = drm.optJSONArray("connectors");
        if (connectors == null) {
            return null;
        }
        DrmMode best = null;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < connectors.length(); i++) {
                org.json.JSONObject connector = connectors.optJSONObject(i);
                if (connector == null || !connector.optBoolean("external", false)) {
                    continue;
                }
                boolean connected = "connected".equals(connector.optString("connection"));
                if (pass == 0 && !connected) {
                    continue;
                }
                if (pass == 1 && connected) {
                    continue;
                }
                org.json.JSONArray modes = connector.optJSONArray("modes");
                if (modes == null) {
                    continue;
                }
                for (int j = 0; j < modes.length(); j++) {
                    DrmMode candidate = drmModeFromJson(modes.optJSONObject(j));
                    if (candidate == null) {
                        continue;
                    }
                    if (candidate.width != width || candidate.height != height
                            || Math.abs(candidate.refresh - refresh) > REFRESH_MATCH_TOLERANCE_HZ) {
                        continue;
                    }
                    if (best == null || Math.abs(candidate.refresh - refresh)
                            < Math.abs(best.refresh - refresh)) {
                        best = candidate;
                        best.connector = connector.optString("name", "?");
                    }
                }
            }
            if (best != null) {
                break;
            }
        }
        if (best != null) {
            Ln.i("findDrmMode: matched " + best.connector + " " + best.width + "x" + best.height
                    + "@" + best.refresh + " selector=" + best.selector);
        }
        return best;
    }

    private DrmMode drmModeFromJson(org.json.JSONObject mode) {
        if (mode == null) {
            return null;
        }
        int width = mode.optInt("width");
        int height = mode.optInt("height");
        int refresh = mode.optInt("refresh");
        if (width <= 0 || height <= 0 || refresh <= 0) {
            return null;
        }
        int selector = mode.optInt("selector", mode.optInt("flags", 0) & 0xf);
        if (selector <= 0) {
            return null;
        }
        DrmMode result = new DrmMode();
        result.width = width;
        result.height = height;
        result.refresh = refresh;
        result.selector = selector;
        return result;
    }

    private String safeJsonLog(String json) {
        if (json == null) {
            return "null";
        }
        return json.length() > 2000 ? json.substring(0, 2000) + "...(" + json.length() + ")" : json;
    }

    private static final class DrmMode {
        int width;
        int height;
        int refresh;
        int selector;
        String connector = "";
    }

    @Override
    public boolean writeDexWallpaper(android.graphics.Bitmap bitmap) {
        File dir = new File("/data/user/0/com.sec.android.app.launcher/files");
        File target = new File(dir, "dex_wallpaper.png");
        File tmp = new File(dir, "dex_wallpaper.png.tmp");
        try {
            dir.mkdirs();
            FileOutputStream fos = new FileOutputStream(tmp);
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            Runtime.getRuntime().exec(new String[]{
                    "chmod", "644", tmp.getAbsolutePath()});
            Runtime.getRuntime().exec(new String[]{
                    "chown", "10154:10154", tmp.getAbsolutePath()});
            if (!tmp.renameTo(target)) {
                throw new RuntimeException("rename wallpaper tmp failed");
            }
            Ln.i("writeDexWallpaper OK " + target);
            return true;
        } catch (Throwable t) {
            Ln.e("writeDexWallpaper failed: " + t);
            return false;
        }
    }

    private void logWallpaperDisplayProbe(Object dm, int vdId) {
        try {
            Display display = mirrorVirtualDisplay.getDisplay();
            Method isExternalDesktopDisplay = Class.forName("android.hardware.display.DisplayManager")
                    .getMethod("isExternalDesktopDisplay", Display.class);
            boolean isDeX = (Boolean) isExternalDesktopDisplay.invoke(null, display);
            String msg = "[LibreDeX] wallpaper probe displayId=" + vdId
                    + " isExternalDesktopDisplay=" + isDeX;
            Ln.i(msg);
            State.log(msg);
            Object displayInfo = dm.getClass().getMethod("getDisplayInfo", int.class).invoke(dm, vdId);
            if (displayInfo != null) {
                Field flagsField = displayInfo.getClass().getField("flags");
                int flags = (Integer) flagsField.get(displayInfo);
                String flagsMsg = "[LibreDeX] wallpaper probe DisplayInfo.flags=0x"
                        + Integer.toHexString(flags);
                Ln.i(flagsMsg);
                State.log(flagsMsg);
            }
        } catch (Throwable t) {
            Ln.w("wallpaper display probe failed: " + t);
        }
    }

    private void ensureSecondaryLauncherOnDisplay(int vdId, int width, int height) {
        try {
            // WMS needs a moment to register the new virtual display. Launching
            // HOME too early falls back to display 0 and SecondaryLauncher exits.
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String cmd = "am start --display " + vdId
                        + " --activityType 2 --windowingMode 1"
                        + " -a android.intent.action.MAIN -c android.intent.category.SECONDARY_HOME"
                        + " -n com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher";
                String out = runAsShell(cmd);
                Ln.i("started SecondaryLauncher on display " + vdId
                        + " exit=" + exitCodeFromOutput(out) + " out=" + out.trim());
            } catch (Throwable t) {
                Ln.e("startSecondaryLauncher failed", t);
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            resizeSecondaryLauncherTask(width, height);
            if (isSecondaryLauncherOnDisplay(vdId)) {
                return;
            }
            Ln.w("SecondaryLauncher not on display " + vdId + ", retry " + (attempt + 1));
        }
    }

    private boolean isSecondaryLauncherOnDisplay(int vdId) {
        try {
            String out = executeShellCommand(
                    "dumpsys window windows 2>/dev/null | grep -A 1 'com.honeyspace.dexservice.SecondaryLauncher'"
                            + " | grep -oE 'mDisplayId=[0-9]+' | head -1");
            int exitIdx = out.indexOf("__EXIT_CODE");
            if (exitIdx >= 0) {
                out = out.substring(0, exitIdx);
            }
            String id = out.trim().replace("mDisplayId=", "");
            boolean ok = String.valueOf(vdId).equals(id);
            Ln.i("SecondaryLauncher window displayId=" + id + " expected=" + vdId + " ok=" + ok);
            return ok;
        } catch (Throwable t) {
            Ln.e("isSecondaryLauncherOnDisplay failed", t);
            return false;
        }
    }

    private void resizeSecondaryLauncherTask(int width, int height) {
        try {
            for (int attempt = 0; attempt < 20; attempt++) {
                String taskId = findSecondaryLauncherTaskId();
                if (taskId != null && !taskId.isEmpty()) {
                    String resizeOut = executeShellCommand(
                            "am task resize " + taskId + " 0 0 " + width + " " + height);
                    Ln.i("resized SecondaryLauncher task " + taskId
                            + " to " + width + "x" + height
                            + " -> " + resizeOut.trim());
                    return;
                }
                Thread.sleep(250);
            }
            Ln.w("SecondaryLauncher task not found for resize after retries");
        } catch (Throwable t) {
            Ln.e("resizeSecondaryLauncherTask failed", t);
        }
    }

    private String findSecondaryLauncherTaskId() {
        try {
            String out = executeShellCommand(
                    "dumpsys activity activities 2>/dev/null | grep 'Task{.*SecondaryLauncher' | head -1"
                            + " | grep -o '#[0-9]*' | head -1 | tr -d '#'");
            String id = out.trim();
            int exitIdx = id.indexOf("__EXIT_CODE");
            if (exitIdx >= 0) {
                id = id.substring(0, exitIdx).trim();
            }
            return id.isEmpty() ? null : id;
        } catch (Throwable t) {
            Ln.e("findSecondaryLauncherTaskId failed", t);
            return null;
        }
    }

    private String findSecondaryLauncherRootTaskId() {
        try {
            String out = executeShellCommand(
                    "dumpsys activity activities 2>/dev/null | grep 'Task{.*SecondaryLauncher' | head -1"
                            + " | grep -oE 'rootTaskId=[0-9]+' | head -1 | cut -d= -f2");
            String id = out == null ? "" : out.trim();
            int exitIdx = id.indexOf("__EXIT_CODE");
            if (exitIdx >= 0) {
                id = id.substring(0, exitIdx).trim();
            }
            return id.isEmpty() ? null : id;
        } catch (Throwable t) {
            Ln.e("findSecondaryLauncherRootTaskId failed", t);
            return null;
        }
    }

    private int createExternalMirrorApi31(String name, int width, int height, int displayIdToMirror, int frameRate, Surface surface) throws Exception {
        android.hardware.display.DisplayManager dm;
        if (context != null) {
            dm = (android.hardware.display.DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        } else {
            java.lang.reflect.Constructor<android.hardware.display.DisplayManager> ctor =
                    android.hardware.display.DisplayManager.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            dm = ctor.newInstance(FakeContext.get());
        }
        // The legacy createVirtualDisplay(name,w,h,displayIdToMirror,surface)
        // path is the only one that produces mirrored content on this firmware
        // (VirtualDisplayConfig-based builds come up black). The 120 Hz mode is
        // applied by the LSPosed hook on VirtualDisplayDevice
        // getDisplayDeviceInfoLocked; frameRate is accepted for future use.
        Method method = android.hardware.display.DisplayManager.class
                .getMethod("createVirtualDisplay", String.class, int.class, int.class, int.class, Surface.class);
        if (mirrorVirtualDisplay != null) {
            mirrorVirtualDisplay.release();
            mirrorVirtualDisplay = null;
        }
        mirrorVirtualDisplay = (VirtualDisplay) method.invoke(dm, name, width, height, displayIdToMirror, surface);
        int vdId = mirrorVirtualDisplay.getDisplay().getDisplayId();
        Ln.i("createExternalMirror [API31+] success, virtualDisplayId=" + vdId);
        return vdId;
    }

    private int createExternalMirrorApi30(String name, int width, int height, int displayIdToMirror, Surface surface) throws Exception {
        Ln.i("createExternalMirror [API30] begin name=" + name + " w=" + width + " h=" + height + " displayId=" + displayIdToMirror + " surface=" + surface);
        IDisplayManager dm = IDisplayManager.Stub.asInterface(
                SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
        Ln.d("createExternalMirror [API30] IDisplayManager=" + dm);
        android.view.DisplayInfo extInfo = dm.getDisplayInfo(displayIdToMirror);
        if (extInfo == null) {
            Ln.e("createExternalMirror [API30]: getDisplayInfo(" + displayIdToMirror + ") 杩斿洖 null");
            return -1;
        }
        Ln.d("createExternalMirror [API30] getDisplayInfo success, reading layerStack");
        int layerStack;
        try {
            layerStack = extInfo.layerStack;
            Ln.d("createExternalMirror [API30] layerStack from DisplayInfo=" + layerStack);
        } catch (NoSuchFieldError e) {
            Ln.w("createExternalMirror [API30] layerStack field missing, trying dumpsys");
            layerStack = getLayerStackFromDumpsys(displayIdToMirror);
            Ln.d("createExternalMirror [API30] layerStack from dumpsys=" + layerStack);
        }
        if (layerStack < 0) {
            Ln.e("createExternalMirror [API30]: layerStack=" + layerStack);
            return -1;
        }
        Ln.d("createExternalMirror [API30] 鍑嗗璋冪敤 SurfaceControl.createDisplay...");
        IBinder token = SurfaceControl.createDisplay(name, true);
        Ln.d("createExternalMirror [API30] createDisplay token=" + token);
        if (token == null) {
            Ln.e("createExternalMirror [API30]: SurfaceControl.createDisplay 杩斿洖 null");
            return -1;
        }
        Rect sourceRect = getSourceDisplayRect(extInfo, width, height);
        Rect displayRect = getAspectFitRect(sourceRect, width, height);
        Ln.d("createExternalMirror [API30] projection sourceRect=" + sourceRect
                + " displayRect=" + displayRect + " encoder=" + width + "x" + height);
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(token, surface);
            Ln.d("createExternalMirror [API30] setDisplaySurface 瀹屾垚");
            SurfaceControl.setDisplayProjection(token, 0, sourceRect, displayRect);
            Ln.d("createExternalMirror [API30] setDisplayProjection 瀹屾垚");
            SurfaceControl.setDisplayLayerStack(token, layerStack);
            Ln.d("createExternalMirror [API30] setDisplayLayerStack 瀹屾垚");
        } finally {
            SurfaceControl.closeTransaction();
        }
        Ln.d("createExternalMirror [API30] transaction committed");
        mirrorExternalToken = token;
        SurfaceControl.setDisplayPowerMode(token, SurfaceControl.POWER_MODE_NORMAL);
        Ln.d("createExternalMirror [API30] setDisplayPowerMode NORMAL 瀹屾垚");
        Ln.i("createExternalMirror [API30] 鍏ㄩ儴瀹屾垚, layerStack=" + layerStack);
        return 0;
    }

    private Rect getSourceDisplayRect(android.view.DisplayInfo displayInfo, int fallbackWidth, int fallbackHeight) {
        int[] logicalSize = getDisplayInfoSizeFields(displayInfo, "logicalWidth", "logicalHeight");
        if (isValidSize(logicalSize)) {
            Ln.d("createExternalMirror [API30] source size from logical fields="
                    + logicalSize[0] + "x" + logicalSize[1]);
            return new Rect(0, 0, logicalSize[0], logicalSize[1]);
        }

        int[] realSize = parseRealSize(displayInfo.toString());
        if (isValidSize(realSize)) {
            Ln.d("createExternalMirror [API30] source size from DisplayInfo.toString="
                    + realSize[0] + "x" + realSize[1]);
            return new Rect(0, 0, realSize[0], realSize[1]);
        }

        int[] modeSize = getDefaultModeSize(displayInfo);
        if (isValidSize(modeSize)) {
            Ln.d("createExternalMirror [API30] source size from default mode="
                    + modeSize[0] + "x" + modeSize[1]);
            return new Rect(0, 0, modeSize[0], modeSize[1]);
        }

        Ln.w("createExternalMirror [API30] cannot read source display size, fallback to encoder size="
                + fallbackWidth + "x" + fallbackHeight);
        return new Rect(0, 0, fallbackWidth, fallbackHeight);
    }

    private int[] getDisplayInfoSizeFields(android.view.DisplayInfo displayInfo, String widthField, String heightField) {
        try {
            Field w = displayInfo.getClass().getDeclaredField(widthField);
            Field h = displayInfo.getClass().getDeclaredField(heightField);
            w.setAccessible(true);
            h.setAccessible(true);
            return new int[] {w.getInt(displayInfo), h.getInt(displayInfo)};
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private int[] parseRealSize(String displayInfoText) {
        Matcher matcher = Pattern.compile("real ([0-9]+) x ([0-9]+)").matcher(displayInfoText);
        if (!matcher.find()) {
            return null;
        }
        return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    private int[] getDefaultModeSize(android.view.DisplayInfo displayInfo) {
        Display.Mode[] modes = displayInfo.supportedModes;
        if (modes == null || modes.length == 0) {
            return null;
        }
        for (Display.Mode mode : modes) {
            if (mode != null && mode.getModeId() == displayInfo.defaultModeId) {
                return new int[] {mode.getPhysicalWidth(), mode.getPhysicalHeight()};
            }
        }
        Display.Mode first = modes[0];
        return first == null ? null : new int[] {first.getPhysicalWidth(), first.getPhysicalHeight()};
    }

    private boolean isValidSize(int[] size) {
        return size != null && size.length >= 2 && size[0] > 0 && size[1] > 0;
    }

    private Rect getAspectFitRect(Rect sourceRect, int width, int height) {
        if (sourceRect.width() <= 0 || sourceRect.height() <= 0 || width <= 0 || height <= 0) {
            return new Rect(0, 0, width, height);
        }
        long scaledWidthByHeight = (long) height * sourceRect.width();
        long maxScaledWidth = (long) width * sourceRect.height();
        if (scaledWidthByHeight <= maxScaledWidth) {
            int displayWidth = (int) Math.max(1, scaledWidthByHeight / sourceRect.height());
            int left = (width - displayWidth) / 2;
            return new Rect(left, 0, left + displayWidth, height);
        }
        long scaledHeightByWidth = (long) width * sourceRect.height();
        int displayHeight = (int) Math.max(1, scaledHeightByWidth / sourceRect.width());
        int top = (height - displayHeight) / 2;
        return new Rect(0, top, width, top + displayHeight);
    }

    private int getLayerStackFromDumpsys(int displayId) {
        try {
            Process process = Runtime.getRuntime().exec("dumpsys display");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();
            DisplayInfo parsed = DisplayManager.parseDisplayInfo(output.toString(), displayId);
            if (parsed != null && parsed.getLayerStack() > 0) {
                return parsed.getLayerStack();
            }
        } catch (Exception e) {
            Ln.e("dumpsys display parse layerStack failed", e);
        }
        return -1;
    }

    @Override
    public void destroyExternalMirror() throws RemoteException {
        Ln.i("destroyExternalMirror");
        if (mirrorVirtualDisplay != null) {
            mirrorVirtualDisplay.release();
            mirrorVirtualDisplay = null;
        }
        if (mirrorExternalToken != null) {
            SurfaceControl.destroyDisplay(mirrorExternalToken);
            mirrorExternalToken = null;
        }
    }

    @Override
    public int startDpMirror(int externalDisplayId, int sourceDisplayId,
                             int outWidth, int outHeight) throws RemoteException {
        Ln.i("startDpMirror: external=" + externalDisplayId + " source=" + sourceDisplayId
                + " out=" + outWidth + "x" + outHeight);
        try {
            IBinder extToken = getDisplayToken(externalDisplayId);
            if (extToken == null) {
                Ln.e("startDpMirror: external display token unavailable displayId=" + externalDisplayId);
                return -1;
            }
            IDisplayManager dm = IDisplayManager.Stub.asInterface(
                    SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
            android.view.DisplayInfo extInfo = dm.getDisplayInfo(externalDisplayId);
            android.view.DisplayInfo srcInfo = dm.getDisplayInfo(sourceDisplayId);
            if (extInfo == null || srcInfo == null) {
                Ln.e("startDpMirror: display info missing ext=" + (extInfo != null)
                        + " src=" + (srcInfo != null));
                return -1;
            }
            int extLayerStack;
            int srcLayerStack;
            try {
                extLayerStack = extInfo.layerStack;
                srcLayerStack = srcInfo.layerStack;
            } catch (NoSuchFieldError e) {
                Ln.w("startDpMirror: layerStack fields unavailable, parsing dumpsys");
                extLayerStack = getLayerStackFromDumpsys(externalDisplayId);
                srcLayerStack = getLayerStackFromDumpsys(sourceDisplayId);
            }
            if (extLayerStack < 0 || srcLayerStack < 0) {
                Ln.e("startDpMirror: bad layer stacks ext=" + extLayerStack + " src=" + srcLayerStack);
                return -1;
            }
            Rect restoreRect = getSourceDisplayRect(extInfo, outWidth, outHeight);
            Rect sourceRect = getSourceDisplayRect(srcInfo, outWidth, outHeight);
            Rect displayRect = getAspectFitRect(sourceRect, outWidth, outHeight);
            Ln.i("startDpMirror: restoreRect=" + restoreRect + " sourceRect=" + sourceRect
                    + " displayRect=" + displayRect + " srcLayerStack=" + srcLayerStack);
            applyDisplayProjectionAndLayerStack(extToken, 0, sourceRect, displayRect, srcLayerStack);
            dpMirrorSavedLayerStack = extLayerStack;
            dpMirrorRestoreRect = restoreRect;
            Ln.i("startDpMirror: success external=" + externalDisplayId);
            return 0;
        } catch (Throwable t) {
            Ln.e("startDpMirror failed", t);
            return -1;
        }
    }

    @Override
    public int startDpMirrorWithGeometry(int externalDisplayId, int sourceDisplayId,
                                         int outWidth, int outHeight, int orientation,
                                         Rect layerStackRect, Rect displayRect) throws RemoteException {
        Ln.i("startDpMirrorWithGeometry: external=" + externalDisplayId + " source=" + sourceDisplayId
                + " out=" + outWidth + "x" + outHeight + " orientation=" + orientation
                + " layerStack=" + layerStackRect + " display=" + displayRect);
        try {
            IBinder extToken = getDisplayToken(externalDisplayId);
            if (extToken == null) {
                Ln.e("startDpMirrorWithGeometry: external display token unavailable displayId=" + externalDisplayId);
                return -1;
            }
            IDisplayManager dm = IDisplayManager.Stub.asInterface(
                    SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
            android.view.DisplayInfo extInfo = dm.getDisplayInfo(externalDisplayId);
            android.view.DisplayInfo srcInfo = dm.getDisplayInfo(sourceDisplayId);
            if (extInfo == null || srcInfo == null) {
                Ln.e("startDpMirrorWithGeometry: display info missing ext=" + (extInfo != null)
                        + " src=" + (srcInfo != null));
                return -1;
            }
            int extLayerStack;
            int srcLayerStack;
            try {
                extLayerStack = extInfo.layerStack;
                srcLayerStack = srcInfo.layerStack;
            } catch (NoSuchFieldError e) {
                Ln.w("startDpMirrorWithGeometry: layerStack fields unavailable, parsing dumpsys");
                extLayerStack = getLayerStackFromDumpsys(externalDisplayId);
                srcLayerStack = getLayerStackFromDumpsys(sourceDisplayId);
            }
            if (extLayerStack < 0 || srcLayerStack < 0) {
                Ln.e("startDpMirrorWithGeometry: bad layer stacks ext=" + extLayerStack
                        + " src=" + srcLayerStack);
                return -1;
            }
            Rect restoreRect = getSourceDisplayRect(extInfo, outWidth, outHeight);
            Rect activeLayerStackRect = layerStackRect;
            Rect activeDisplayRect = displayRect;
            if (activeLayerStackRect == null || activeLayerStackRect.isEmpty()) {
                Rect sourceRect = getSourceDisplayRect(srcInfo, outWidth, outHeight);
                activeLayerStackRect = sourceRect;
            }
            if (activeDisplayRect == null || activeDisplayRect.isEmpty()) {
                activeDisplayRect = getAspectFitRect(activeLayerStackRect, outWidth, outHeight);
            }
            applyDisplayProjectionAndLayerStack(
                    extToken, orientation, activeLayerStackRect, activeDisplayRect, srcLayerStack);
            dpMirrorSavedLayerStack = extLayerStack;
            dpMirrorRestoreRect = restoreRect;
            Ln.i("startDpMirrorWithGeometry: success external=" + externalDisplayId
                    + " layerStack=" + activeLayerStackRect + " display=" + activeDisplayRect);
            return 0;
        } catch (Throwable t) {
            Ln.e("startDpMirrorWithGeometry failed", t);
            return -1;
        }
    }

    @Override
    public int stopDpMirror(int externalDisplayId) throws RemoteException {
        Ln.i("stopDpMirror: external=" + externalDisplayId
                + " saved=" + dpMirrorSavedLayerStack);
        try {
            IBinder extToken = getDisplayToken(externalDisplayId);
            if (extToken == null) {
                dpMirrorSavedLayerStack = -1;
                dpMirrorRestoreRect = null;
                return -1;
            }
            if (dpMirrorSavedLayerStack >= 0) {
                Rect restoreRect = dpMirrorRestoreRect;
                if (restoreRect == null || restoreRect.isEmpty()) {
                    IDisplayManager dm = IDisplayManager.Stub.asInterface(
                            SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
                    android.view.DisplayInfo extInfo = dm.getDisplayInfo(externalDisplayId);
                    restoreRect = extInfo != null
                            ? getSourceDisplayRect(extInfo, 1920, 1080)
                            : new Rect(0, 0, 1920, 1080);
                }
                applyDisplayProjectionAndLayerStack(
                        extToken, 0, restoreRect, restoreRect, dpMirrorSavedLayerStack);
            }
            dpMirrorSavedLayerStack = -1;
            dpMirrorRestoreRect = null;
            return 0;
        } catch (Throwable t) {
            Ln.e("stopDpMirror failed", t);
            return -1;
        }
    }

    @Override
    public int mirrorPhoneToExternal(int externalDisplayId, int sourceDisplayId,
                                     int outWidth, int outHeight, int orientation,
                                     Rect layerStackRect, Rect displayRect) throws RemoteException {
        Ln.i("mirrorPhoneToExternal: external=" + externalDisplayId + " source=" + sourceDisplayId
                + " out=" + outWidth + "x" + outHeight + " orientation=" + orientation
                + " layerStack=" + layerStackRect + " display=" + displayRect);
        try {
            IBinder extToken = getDisplayToken(externalDisplayId);
            if (extToken == null) {
                Ln.e("mirrorPhoneToExternal: external display token unavailable displayId="
                        + externalDisplayId);
                return -1;
            }
            IDisplayManager dm = IDisplayManager.Stub.asInterface(
                    SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
            android.view.DisplayInfo srcInfo = dm.getDisplayInfo(sourceDisplayId);
            if (srcInfo == null) {
                Ln.e("mirrorPhoneToExternal: source display info missing displayId="
                        + sourceDisplayId);
                return -1;
            }
            int srcLayerStack;
            try {
                srcLayerStack = srcInfo.layerStack;
            } catch (NoSuchFieldError e) {
                Ln.w("mirrorPhoneToExternal: layerStack field unavailable, parsing dumpsys");
                srcLayerStack = getLayerStackFromDumpsys(sourceDisplayId);
            }
            if (srcLayerStack < 0) {
                Ln.e("mirrorPhoneToExternal: bad source layer stack=" + srcLayerStack);
                return -1;
            }
            Rect activeLayerStackRect = layerStackRect;
            Rect activeDisplayRect = displayRect;
            if (activeLayerStackRect == null || activeLayerStackRect.isEmpty()) {
                activeLayerStackRect = getSourceDisplayRect(srcInfo, outWidth, outHeight);
            }
            if (activeDisplayRect == null || activeDisplayRect.isEmpty()) {
                activeDisplayRect = getAspectFitRect(activeLayerStackRect, outWidth, outHeight);
            }
            applyDisplayProjectionAndLayerStack(
                    extToken, orientation, activeLayerStackRect, activeDisplayRect, srcLayerStack);
            dpMirrorSavedLayerStack = -1;
            dpMirrorRestoreRect = null;
            Ln.i("mirrorPhoneToExternal: success external=" + externalDisplayId
                    + " layerStack=" + activeLayerStackRect + " display=" + activeDisplayRect);
            return 0;
        } catch (Throwable t) {
            Ln.e("mirrorPhoneToExternal failed", t);
            return -1;
        }
    }

    @Override
    public int resetDpMirror(int externalDisplayId) throws RemoteException {
        Ln.i("resetDpMirror: external=" + externalDisplayId);
        try {
            IBinder extToken = getDisplayToken(externalDisplayId);
            if (extToken == null) {
                Ln.e("resetDpMirror: external display token unavailable displayId=" + externalDisplayId);
                return -1;
            }
            IDisplayManager dm = IDisplayManager.Stub.asInterface(
                    SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
            android.view.DisplayInfo extInfo = dm.getDisplayInfo(externalDisplayId);
            if (extInfo == null) {
                Ln.e("resetDpMirror: display info missing external=" + externalDisplayId);
                return -1;
            }
            int extLayerStack;
            try {
                extLayerStack = extInfo.layerStack;
            } catch (NoSuchFieldError e) {
                Ln.w("resetDpMirror: layerStack field unavailable, parsing dumpsys");
                extLayerStack = getLayerStackFromDumpsys(externalDisplayId);
            }
            if (extLayerStack < 0) {
                Ln.e("resetDpMirror: bad layer stack ext=" + extLayerStack);
                return -1;
            }
            Rect restoreRect = getSourceDisplayRect(extInfo, 1920, 1080);
            applyDisplayProjectionAndLayerStack(
                    extToken, 0, restoreRect, restoreRect, extLayerStack);
            dpMirrorSavedLayerStack = -1;
            dpMirrorRestoreRect = null;
            Ln.i("resetDpMirror: success external=" + externalDisplayId
                    + " layerStack=" + extLayerStack + " rect=" + restoreRect);
            return 0;
        } catch (Throwable t) {
            Ln.e("resetDpMirror failed", t);
            return -1;
        }
    }

    private static void applyDisplayProjectionAndLayerStack(
            IBinder token, int orientation, Rect layerStackRect, Rect displayRect,
            int layerStack) throws Exception {
        Class<?> txnClass = Class.forName("android.view.SurfaceControl$Transaction");
        Object txn = txnClass.getConstructor().newInstance();
        txnClass.getMethod("setDisplayProjection", IBinder.class, int.class,
                        Rect.class, Rect.class)
                .invoke(txn, token, orientation, layerStackRect, displayRect);
        txnClass.getMethod("setDisplayLayerStack", IBinder.class, int.class)
                .invoke(txn, token, layerStack);
        txnClass.getMethod("apply").invoke(txn);
    }

    @Override
    public int redirectDisplayToSurface(int displayId, Surface surface) throws RemoteException {
        Ln.i("redirectDisplayToSurface: displayId=" + displayId + " surface=" + surface);
        try {
            IBinder token = SurfaceControl.getDisplayToken(displayId);
            if (token == null) {
                Ln.e("redirectDisplayToSurface: getDisplayToken(" + displayId + ") returned null");
                return -1;
            }
            SurfaceControl.openTransaction();
            SurfaceControl.setDisplaySurface(token, surface);
            SurfaceControl.closeTransaction();
            Ln.i("redirectDisplayToSurface: success, displayId=" + displayId);
            return 0;
        } catch (Exception e) {
            Ln.e("redirectDisplayToSurface failed", e);
            return -1;
        }
    }

    @SuppressLint({"WrongConstant", "MissingPermission"})
    private AudioRecord createAudioRecord() {
        AudioRecord loopback = createPlaybackMixAudioRecord();
        if (loopback != null) {
            Ln.i("using AudioPolicy loopback AudioRecord");
            return loopback;
        }
        Ln.i("AudioPolicy loopback unavailable, falling back to REMOTE_SUBMIX");
        AudioRecord.Builder builder = new AudioRecord.Builder();
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
            // On older APIs, Workarounds.fillAppInfo() must be called beforehand
            builder.setContext(privilegedAudioContext());
        }
        builder.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX);
        int sampleRate = 48000; // 涓庢偍鐨凮pus閰嶇疆鍖归厤
        int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
        int audioEncoding = AudioFormat.ENCODING_PCM_FLOAT;
        AudioFormat audioFormat = new AudioFormat.Builder()
                .setEncoding(audioEncoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();
        builder.setAudioFormat(audioFormat);
        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding);
        if (minBufferSize > 0) {
            // This buffer size does not impact latency
            builder.setBufferSizeInBytes(2 * minBufferSize);
        }
        return builder.build();
    }

    private Context privilegedAudioContext() {
        Context base = context != null ? context : FakeContext.get();
        Context packageContext;
        try {
            packageContext = base.createPackageContext(
                    "com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        } catch (Throwable t) {
            packageContext = base;
        }
        final int uid = android.os.Process.myUid();
        final String packageName = uid == 2000 ? "com.android.shell" : "android";
        return new ContextWrapper(packageContext) {
            @Override
            public String getPackageName() {
                return packageName;
            }

            @Override
            public String getOpPackageName() {
                return packageName;
            }

            @Override
            public android.content.AttributionSource getAttributionSource() {
                return new android.content.AttributionSource.Builder(uid)
                        .setPackageName(packageName)
                        .build();
            }

            @Override
            public Context getApplicationContext() {
                return this;
            }

            @Override
            public Context createPackageContext(String name, int flags) {
                return this;
            }
        };
    }

    /** Mirrors the Android 13+ privileged AudioPolicy loopback capture used by streaming. */
    private AudioRecord createPlaybackMixAudioRecord() {
        try {
            Binder.clearCallingIdentity();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
            Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            Object ruleBuilder = ruleBuilderClass.getConstructor().newInstance();
            int playersRole = ruleClass.getField("MIX_ROLE_PLAYERS").getInt(null);
            ruleBuilderClass.getMethod("setTargetMixRole", int.class)
                    .invoke(ruleBuilder, playersRole);
            int matchUsage = ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null);
            Method addRule = ruleBuilderClass.getMethod(
                    "addMixRule", int.class, Object.class);
            int[] usages = {
                    AudioAttributes.USAGE_MEDIA,
                    AudioAttributes.USAGE_GAME,
                    AudioAttributes.USAGE_UNKNOWN,
                    AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            };
            for (int usage : usages) {
                addRule.invoke(ruleBuilder, matchUsage,
                        new AudioAttributes.Builder().setUsage(usage).build());
            }
            Object rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder);
            Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
            Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
            Object mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule);
            mixBuilderClass.getMethod("setFormat", AudioFormat.class)
                    .invoke(mixBuilder, format);
            int loopback = mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null);
            mixBuilderClass.getMethod("setRouteFlags", int.class)
                    .invoke(mixBuilder, loopback);
            Object mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);
            Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
            Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            Object policyBuilder = policyBuilderClass.getConstructor(Context.class)
                    .newInstance(privilegedAudioContext());
            policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix);
            Object policy = policyBuilderClass.getMethod("build").invoke(policyBuilder);
            Method register = AudioManager.class.getDeclaredMethod(
                    "registerAudioPolicyStatic", policyClass);
            register.setAccessible(true);
            int rc = (Integer) register.invoke(null, policy);
            if (rc != 0) {
                Ln.e("registerAudioPolicyStatic rc=" + rc);
                return null;
            }
            activeAudioPolicy = policy;
            AudioRecord record = (AudioRecord) policyClass.getMethod(
                    "createAudioRecordSink", mixClass).invoke(policy, mix);
            if (record != null && record.getState() == AudioRecord.STATE_INITIALIZED) {
                return record;
            }
            if (record != null) {
                record.release();
            }
            // createAudioRecordSink may attribute the capture to the app package;
            // build the same registered-mix record with the privileged context.
            String registration = (String) mixClass.getMethod("getRegistration").invoke(mix);
            AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder();
            AudioAttributes.Builder.class.getMethod(
                    "setInternalCapturePreset", int.class)
                    .invoke(attributesBuilder, MediaRecorder.AudioSource.REMOTE_SUBMIX);
            AudioAttributes.Builder.class.getMethod("addTag", String.class)
                    .invoke(attributesBuilder, "addr=" + registration);
            AudioAttributes attributes = attributesBuilder.build();
            int bufferBytes = AudioRecord.getMinBufferSize(
                    48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
            java.lang.reflect.Constructor<AudioRecord> constructor =
                    AudioRecord.class.getDeclaredConstructor(
                            AudioAttributes.class, AudioFormat.class, int.class, int.class,
                            Context.class, int.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    attributes,
                    format,
                    Math.max(bufferBytes, 48000 * 2 * 4 / 10),
                    AudioManager.AUDIO_SESSION_ID_GENERATE,
                    privilegedAudioContext(),
                    0,
                    0);
        } catch (Throwable t) {
            Ln.e("createPlaybackMixAudioRecord failed", t);
            releaseAudioPolicy();
            return null;
        }
    }
}

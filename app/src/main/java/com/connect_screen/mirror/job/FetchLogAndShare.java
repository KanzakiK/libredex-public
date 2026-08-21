package com.connect_screen.mirror.job;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.RemoteException;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.connect_screen.mirror.BuildConfig;
import com.connect_screen.mirror.State;

import rikka.shizuku.Shizuku;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import com.connect_screen.mirror.R;

public class FetchLogAndShare implements Job {
    private final AcquireShizuku acquireShizuku = new AcquireShizuku();
    private boolean userServiceRequested = false;

    private final Context context;

    private static final String LOG_FILE_PREFIX = "libredex-";
    private static final String LOG_FILE_SUFFIX = ".log";
    private static final String ZIP_PREFIX = "libredex-logs-";
    private static final int KEEP_ZIP_COUNT = 5;

    public FetchLogAndShare(Context context) {
        this.context = context;
    }

    @Override
    public void start() throws YieldException {
        acquireShizuku.start();
        if (!acquireShizuku.acquired) {
            return;
        }
        if (State.userService == null) {
            if (!userServiceRequested) {
                userServiceRequested = true;
                Shizuku.peekUserService(State.userServiceArgs, State.userServiceConnection);
                Shizuku.bindUserService(State.userServiceArgs, State.userServiceConnection);
                State.resumeJobLater(1000);
                throw new YieldException("waiting for user service");
            }
            Toast.makeText(State.getContext(), State.getContext().getString(R.string.log_fetch_service_failed), Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                State.getContext().startActivity(intent);
                Toast.makeText(State.getContext(), State.getContext().getString(R.string.log_fetch_need_file_perm), Toast.LENGTH_LONG).show();
                return;
            }
        }

        try {
            File legacyLog = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "libredex_logcat.log");
            if (legacyLog.exists()) {
                legacyLog.delete();
            }
            String logDirPath = State.userService.fetchLogs();
            if (logDirPath == null || logDirPath.trim().isEmpty()) {
                Toast.makeText(State.getContext(), State.getContext().getString(R.string.log_fetch_dir_failed), Toast.LENGTH_SHORT).show();
                return;
            }
            File logDir = new File(logDirPath);
            File[] logs = logDir.listFiles((dir, name) ->
                    name.startsWith(LOG_FILE_PREFIX) && name.endsWith(LOG_FILE_SUFFIX));
            if (logs == null || logs.length == 0) {
                Toast.makeText(State.getContext(), State.getContext().getString(R.string.log_fetch_none), Toast.LENGTH_SHORT).show();
                return;
            }

            File exportDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), State.getContext().getString(R.string.notify_sunshine_title));
            if (!exportDir.exists() && !exportDir.mkdirs()) {
                Toast.makeText(State.getContext(), State.getContext().getString(R.string.log_fetch_mkdir_failed), Toast.LENGTH_SHORT).show();
                return;
            }

            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(new Date());
            File zipFile = new File(exportDir, ZIP_PREFIX + stamp + ".zip");
            String environmentInfo = null;
            try {
                environmentInfo = State.userService.getEnvironmentInfo();
            } catch (Throwable ignored) {
            }
            String lspLogs = null;
            try {
                lspLogs = State.userService.fetchLspLogs();
            } catch (Throwable ignored) {
                // LSPosed log collection is best-effort; never block export.
            }
            String deviceInfo = buildDeviceInfo(logs, environmentInfo);
            createLogZip(logs, zipFile, deviceInfo, lspLogs);

            File cacheDir = State.getContext().getCacheDir();
            File cacheCopyFile = new File(cacheDir, zipFile.getName());
            Files.copy(zipFile.toPath(), cacheCopyFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            cleanupOldZips(exportDir);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/zip");
            Uri fileUri = FileProvider.getUriForFile(State.getContext(),
                    State.getContext().getPackageName() + ".provider",
                    cacheCopyFile);
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            State.getContext().startActivity(Intent.createChooser(
                    shareIntent, State.getContext().getString(R.string.log_share_archive)));
        } catch (RemoteException | IOException e) {
            Toast.makeText(State.getContext(), State.getContext().getString(R.string.log_fetch_failed), Toast.LENGTH_LONG).show();
            throw new RuntimeException(e);
        }
    }

    private static void createLogZip(File[] logs, File zipFile, String deviceInfo, String lspLogs)
            throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            // Sort so the exported set is deterministic and the newest is last.
            Arrays.sort(logs, Comparator.comparing(File::getName));
            for (File log : logs) {
                zos.putNextEntry(new ZipEntry(log.getName()));
                // Embed the correlation header inside every log file so a single
                // log can be matched back to the app version / device / env that
                // produced it, instead of only living in device_info.txt.
                writeWithHeader(new FileInputStream(log), deviceInfo, zos);
                zos.closeEntry();
            }
            if (lspLogs != null && !lspLogs.trim().isEmpty()) {
                String stamp = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                zos.putNextEntry(new ZipEntry("lspd-" + stamp + ".log"));
                zos.write(lspLogs.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            zos.putNextEntry(new ZipEntry("device_info.txt"));
            zos.write(deviceInfo.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    private static void writeWithHeader(InputStream in, String deviceInfo, OutputStream out)
            throws IOException {
        out.write("########################################################################\n"
                .getBytes(StandardCharsets.UTF_8));
        out.write("# This log was captured with the LibreDeX build / device below.\n"
                .getBytes(StandardCharsets.UTF_8));
        out.write("# See device_info.txt in the same archive for the full field set.\n"
                .getBytes(StandardCharsets.UTF_8));
        out.write("########################################################################\n"
                .getBytes(StandardCharsets.UTF_8));
        // Header lines look like "LibreDeX 0.1.9 (versionCode 9)", "Device ...",
        // etc. Prefix with "# " so they read as comments inside the logcat text.
        for (String line : deviceInfo.split("\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            out.write(("# " + line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        out.write("\n".getBytes(StandardCharsets.UTF_8));
        copyStream(in, out);
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static String buildDeviceInfo(File[] logs, String environmentInfo) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append("LibreDeX ").append(BuildConfig.VERSION_NAME)
                .append(" (versionCode ").append(BuildConfig.VERSION_CODE)
                .append(", commit ").append(BuildConfig.COMMIT).append(")\n");
        sb.append("Exported ").append(dateFormat.format(new Date())).append('\n');
        sb.append("Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Build ").append(Build.DISPLAY).append('\n');
        sb.append("Build ID ").append(Build.ID).append('\n');
        sb.append("Incremental ").append(Build.VERSION.INCREMENTAL).append('\n');
        sb.append("Security patch ").append(Build.VERSION.SECURITY_PATCH).append('\n');
        sb.append("Fingerprint ").append(Build.FINGERPRINT).append('\n');
        sb.append("Device ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.PRODUCT).append('/').append(Build.DEVICE).append(")\n");
        sb.append("Hardware ").append(Build.HARDWARE).append('\n');
        sb.append("Tags ").append(Build.TAGS).append(" Type ").append(Build.TYPE).append('\n');
        sb.append("Build time ").append(dateFormat.format(new Date(Build.TIME))).append('\n');
        if (environmentInfo != null && !environmentInfo.trim().isEmpty()) {
            sb.append("--- UserService ---\n").append(environmentInfo).append('\n');
        }
        sb.append("--- Hook status ---\n").append(scanHookStatus(logs));
        return sb.toString();
    }

    private static String scanHookStatus(File[] logs) {
        int lspLines = 0;
        int hooking = 0;
        int rootOrderHook = 0;
        int tdaUnavailable = 0;
        int tdaFallback = 0;
        int dpInject = 0;
        for (File log : logs) {
            try (BufferedReader reader = new BufferedReader(new FileReader(log))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.contains("DexLspMirror")) {
                        continue;
                    }
                    lspLines++;
                    if (line.contains("hooking system_server")) {
                        hooking++;
                    }
                    if (line.contains("task display area root order hook installed")) {
                        rootOrderHook++;
                    }
                    if (line.contains("ensureDexRootOrder tda displayId unavailable")) {
                        tdaUnavailable++;
                    }
                    if (line.contains("TaskDisplayArea.getDisplayId resolved via fallback")) {
                        tdaFallback++;
                    }
                    if (line.contains("dp dex info inject")) {
                        dpInject++;
                    }
                }
            } catch (IOException ignored) {
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("lsp_lines=").append(lspLines).append('\n');
        sb.append("hooking_system_server=").append(hooking).append('\n');
        sb.append("task_display_area_root_order_hook=").append(rootOrderHook).append('\n');
        sb.append("tda_display_id_unavailable=").append(tdaUnavailable).append('\n');
        sb.append("tda_display_id_fallback=").append(tdaFallback).append('\n');
        sb.append("dp_dex_info_inject=").append(dpInject).append('\n');
        return sb.toString();
    }

    private static void cleanupOldZips(File exportDir) {
        File[] zips = exportDir.listFiles((dir, name) ->
                name.startsWith(ZIP_PREFIX) && name.endsWith(".zip"));
        if (zips == null || zips.length <= KEEP_ZIP_COUNT) {
            return;
        }
        Arrays.sort(zips, Comparator.comparing(File::getName).reversed());
        for (int i = KEEP_ZIP_COUNT; i < zips.length; i++) {
            zips[i].delete();
        }
    }
}

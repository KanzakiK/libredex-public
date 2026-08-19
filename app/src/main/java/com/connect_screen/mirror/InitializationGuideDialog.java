package com.connect_screen.mirror;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.connect_screen.mirror.job.AcquireShizuku;
import com.connect_screen.mirror.shizuku.ShizukuUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class InitializationGuideDialog {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static boolean showing;

    private final Activity activity;
    private AlertDialog dialog;
    private TextView doneButton;

    private InitializationGuideDialog(Activity activity) {
        this.activity = activity;
    }

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing() || showing) {
            return;
        }
        new InitializationGuideDialog(activity).showInternal();
    }

    public static boolean needsSetup(Activity activity) {
        if (activity == null) {
            return false;
        }
        if (Pref.isInitialSetupComplete()) {
            return false;
        }
        boolean shizukuReady = ShizukuUtils.hasPermission();
        boolean recordAudioReady = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            recordAudioReady = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return !shizukuReady || !recordAudioReady;
    }

    private void showInternal() {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPadding(padding, dp(10), padding, 0);

        TextView warning = new TextView(activity);
        warning.setText("开始使用前，请完成必要权限授权。LibreDeX 通过 Shizuku 建立系统级显示与控制链路。");
        warning.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_secondary));
        warning.setTextSize(14);
        warning.setLineSpacing(dp(2), 1.0f);
        content.addView(warning, matchWrapParams());

        // 屏幕采集：连接时会系统弹窗授权，这里只作提示（无法预授权）。
        content.addView(createInfoRow(
                "屏幕采集（投屏）",
                "开始连接时系统会自动弹出投屏授权，确认即可；无需在此提前授权。"));

        content.addView(createShizukuRow());
        content.addView(createOverlayRow());
        content.addView(createAudioRow());
        content.addView(createFileAccessRow());

        dialog = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setTitle("连接向导")
                .setView(content)
                .setPositiveButton("完成", null)
                .create();
        showing = true;
        dialog.setOnDismissListener(d -> {
            showing = false;
            Pref.setInitialSetupComplete(true);
        });
        dialog.setOnShowListener(d -> {
            doneButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (doneButton != null) {
                doneButton.setOnClickListener(v -> finishSetup());
            }
            refreshStatus();
        });
        dialog.show();
    }

    /** 一次性授权后统一刷新状态，保证如悬浮窗 root 静默授权立即反映到 UI。 */
    private void refreshStatus() {
        // 必需权限（决定“完成”按钮是否可点）：Shizuku + 录音。
        boolean shizukuReady = ShizukuUtils.hasPermission();
        boolean recordAudioReady = isRecordAudioGranted();
        if (doneButton != null) {
            doneButton.setEnabled(shizukuReady && recordAudioReady);
        }
    }

    private boolean canDrawOverlays() {
        return Settings.canDrawOverlays(activity);
    }

    private View createInfoRow(String title, String note) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(16), 0, 0);
        TextView t = new TextView(activity);
        t.setText(title);
        t.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_primary));
        t.setTextSize(16);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        col.addView(t, wrapParams());
        TextView n = new TextView(activity);
        n.setText(note);
        n.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_secondary));
        n.setTextSize(13);
        n.setPadding(0, dp(4), 0, 0);
        col.addView(n, matchWrapParams());
        return col;
    }

    private View createShizukuRow() {
        final Button grantBtn = new Button(activity);
        grantBtn.setText("连接");
        LinearLayout status = rowHeader("Shizuku 权限",
                "系统级显示/控制链路（虚拟显示、屏幕采集、控制事件）。",
                grantBtn);
        grantBtn.setOnClickListener(v -> {
            State.startNewJob(new AcquireShizuku());
            MAIN_HANDLER.postDelayed(this::refreshStatus, 800);
            MAIN_HANDLER.postDelayed(this::refreshStatus, 2500);
        });
        Button rootBtn = new Button(activity);
        rootBtn.setText("以 root 重启");
        rootBtn.setMinHeight(0);
        rootBtn.setMinimumHeight(0);
        rootBtn.setPadding(dp(12), dp(4), dp(12), dp(4));
        rootBtn.setOnClickListener(v -> {
            boolean ok = AcquireShizuku.fixRootShizuku();
            Toast.makeText(activity, ok ? "Shizuku 已以 root 重启" : "以 root 重启失败",
                    Toast.LENGTH_SHORT).show();
            MAIN_HANDLER.postDelayed(this::refreshStatus, 800);
        });
        // 合并成一个水平行：两个按钮
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, 0);
        row.addView(grantBtn, wrapParams());
        LinearLayout.LayoutParams rp = wrapParams();
        rp.setMarginStart(dp(8));
        row.addView(rootBtn, rp);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(16), 0, 0);
        col.addView(status, matchWrapParams());
        col.addView(row, matchWrapParams());
        return col;
    }

    private View createOverlayRow() {
        Button grantBtn = new Button(activity);
        grantBtn.setText("授权");
        LinearLayout status = rowHeader("悬浮窗权限",
                "用于黑色画面模拟息屏。通常 root 下可静默授权，无需跳系统页。",
                grantBtn);
        grantBtn.setOnClickListener(v -> grantOverlay(grantBtn));
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(16), 0, 0);
        col.addView(status, matchWrapParams());
        return col;
    }

    private void grantOverlay(Button btn) {
        if (canDrawOverlays()) {
            Toast.makeText(activity, "悬浮窗权限已授权", Toast.LENGTH_SHORT).show();
            return;
        }
        // 优先 root 静默授权（UserService 以 root 运行时直接 appops allow），
        // 再从系统设置页兜底；授权后轮询刷新，根治“跳页/重进才生效”的问题。
        if (isUserServiceRoot()) {
            try {
                State.userService.executeCommand("appops set "
                        + BuildConfig.APPLICATION_ID + " SYSTEM_ALERT_WINDOW allow");
            } catch (Throwable ignored) {
            }
        }
        if (!canDrawOverlays()) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
            } catch (Throwable e) {
                Toast.makeText(activity, "请手动授予悬浮窗权限", Toast.LENGTH_SHORT).show();
            }
        }
        MAIN_HANDLER.postDelayed(this::refreshStatus, 600);
        MAIN_HANDLER.postDelayed(this::refreshStatus, 1800);
        if (btn != null) {
            btn.setEnabled(!canDrawOverlays());
        }
    }

    private boolean isUserServiceRoot() {
        try {
            return State.userService != null && State.userService.isRooted();
        } catch (Throwable t) {
            return false;
        }
    }

    private View createAudioRow() {
        final Button grantBtn = new Button(activity);
        grantBtn.setText("授权");
        LinearLayout status = rowHeader("录音权限",
                "用于采集系统播放音频；未授权时仍可串流画面，但不会传输声音。",
                grantBtn);
        grantBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return;
            }
            activity.requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    MirrorMainActivity.REQUEST_RECORD_AUDIO_PERMISSION);
            MAIN_HANDLER.postDelayed(this::refreshStatus, 600);
            MAIN_HANDLER.postDelayed(this::refreshStatus, 1800);
        });
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(16), 0, 0);
        col.addView(status, matchWrapParams());
        return col;
    }

    private View createFileAccessRow() {
        Button grantBtn = new Button(activity);
        grantBtn.setText("授权");
        LinearLayout status = rowHeader("文件访问权限",
                "用于把日志/压缩包写入下载目录。",
                grantBtn);
        grantBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                activity.startActivity(intent);
            } else {
                Toast.makeText(activity, "当前系统无需额外文件权限", Toast.LENGTH_SHORT).show();
            }
            MAIN_HANDLER.postDelayed(this::refreshStatus, 1500);
            MAIN_HANDLER.postDelayed(this::refreshStatus, 3000);
        });
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(16), 0, 0);
        col.addView(status, matchWrapParams());
        return col;
    }

    private LinearLayout rowHeader(String title, String note, Button actionButton) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_primary));
        titleView.setTextSize(16);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        col.addView(titleView, wrapParams());
        TextView noteView = new TextView(activity);
        noteView.setText(note);
        noteView.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_secondary));
        noteView.setTextSize(13);
        noteView.setPadding(0, dp(2), 0, 0);
        col.addView(noteView, matchWrapParams());
        header.addView(col, matchWrapParams());

        actionButton.setMinHeight(0);
        actionButton.setMinimumHeight(0);
        actionButton.setPadding(dp(12), dp(4), dp(12), dp(4));
        LinearLayout.LayoutParams buttonParams = wrapParams();
        buttonParams.setMarginStart(dp(8));
        header.addView(actionButton, buttonParams);
        return header;
    }

    private void finishSetup() {
        activity.getSharedPreferences(Pref.PREF_NAME, Activity.MODE_PRIVATE)
                .edit()
                .putBoolean(Pref.KEY_INITIAL_SETUP_COMPLETE, true)
                .apply();
        if (dialog != null) {
            dialog.dismiss();
        }
    }

    private boolean isRecordAudioGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private LinearLayout.LayoutParams matchWrapParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

package com.connect_screen.mirror;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

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
    private TextView shizukuStatus;
    private TextView recordAudioStatus;
    private Button shizukuButton;
    private Button recordAudioButton;
    private Button doneButton;

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
        warning.setText("开始使用前，需要先完成必要权限。LibreDeX 会通过 Shizuku 建立系统级显示与控制链路，并使用录音权限采集串流音频；缺少权限时会影响连接、控制或声音输出。");
        warning.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_secondary));
        warning.setTextSize(14);
        warning.setLineSpacing(dp(2), 1.0f);
        content.addView(warning, matchWrapParams());

        content.addView(createStatusRow(
                "Shizuku 权限",
                "用于创建/管理虚拟显示、获取屏幕画面、注入控制事件和执行系统级电源控制。",
                "授权",
                true));
        content.addView(createStatusRow(
                "录音权限",
                "用于采集系统播放音频；未授权时仍可串流画面，但不会传输声音。",
                "授权",
                false));

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

    private View createStatusRow(String title, String note, String actionText, boolean shizukuRow) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(16), 0, 0);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_primary));
        titleView.setTextSize(16);
        header.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView statusView = new TextView(activity);
        statusView.setText("待检查");
        statusView.setTextSize(14);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(statusView, wrapParams());

        Button actionButton = new Button(activity);
        actionButton.setText(actionText);
        actionButton.setMinHeight(0);
        actionButton.setMinimumHeight(0);
        actionButton.setPadding(dp(12), dp(4), dp(12), dp(4));
        LinearLayout.LayoutParams buttonParams = wrapParams();
        buttonParams.setMarginStart(dp(8));
        header.addView(actionButton, buttonParams);

        TextView noteView = new TextView(activity);
        noteView.setText(note);
        noteView.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_secondary));
        noteView.setTextSize(13);
        noteView.setPadding(0, dp(4), 0, 0);

        row.addView(header, matchWrapParams());
        row.addView(noteView, matchWrapParams());

        if (shizukuRow) {
            shizukuStatus = statusView;
            shizukuButton = actionButton;
            shizukuButton.setOnClickListener(v -> {
                State.startNewJob(new AcquireShizuku());
                MAIN_HANDLER.postDelayed(this::refreshStatus, 800);
                MAIN_HANDLER.postDelayed(this::refreshStatus, 2500);
            });
        } else {
            recordAudioStatus = statusView;
            recordAudioButton = actionButton;
            recordAudioButton.setOnClickListener(v -> requestRecordAudioPermission());
        }
        return row;
    }

    private void refreshStatus() {
        boolean shizukuGranted = ShizukuUtils.hasPermission();
        boolean recordAudioGranted = isRecordAudioGranted();

        updateStatus(shizukuStatus, shizukuGranted ? "已授权" : "未授权", shizukuGranted);
        if (shizukuButton != null) {
            shizukuButton.setEnabled(!shizukuGranted);
        }

        updateStatus(recordAudioStatus, recordAudioGranted ? "已授权" : "未授权", recordAudioGranted);
        if (recordAudioButton != null) {
            recordAudioButton.setEnabled(!recordAudioGranted);
        }

        boolean allReady = shizukuGranted && recordAudioGranted;
        if (doneButton != null) {
            doneButton.setEnabled(allReady);
        }
    }

    private void requestRecordAudioPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        if (isRecordAudioGranted()) {
            return;
        }
        activity.requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO},
                MirrorMainActivity.REQUEST_RECORD_AUDIO_PERMISSION);
        MAIN_HANDLER.postDelayed(this::refreshStatus, 600);
        MAIN_HANDLER.postDelayed(this::refreshStatus, 1800);
    }

    private void finishSetup() {
        activity.getSharedPreferences(MirrorSettingsActivity.PREF_NAME, Activity.MODE_PRIVATE)
                .edit()
                .putBoolean(Pref.KEY_INITIAL_SETUP_COMPLETE, true)
                .apply();
        if (dialog != null) {
            dialog.dismiss();
        }
    }

    private void updateStatus(TextView view, String text, boolean ok) {
        if (view == null) {
            return;
        }
        view.setText(text);
        view.setTextColor(ok
                ? ContextCompat.getColor(activity, R.color.ui_success)
                : ContextCompat.getColor(activity, R.color.ui_text_secondary));
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

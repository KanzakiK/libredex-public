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
    private Button doneButton;   // positive "完成"
    private LinearLayout content;
    private TextView pageTitle;
    private View[] pages;
    private int page = 0;
    private Button prevButton;
    private Button nextButton;
    private Button finishButton;

    // 各页状态（刷新用）
    private TextView rootStatusText;
    private TextView shizukuStatusText;
    private TextView overlayStatusText;
    private TextView audioStatusText;
    private TextView lspStatusText;
    private TextView fileStatusText;

    // 一次会话自动请求 root 的 guard
    private boolean rootRequestedOnce;

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
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPadding(padding, dp(10), padding, 0);

        pageTitle = new TextView(activity);
        pageTitle.setTextSize(18);
        pageTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pageTitle.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_primary));
        content.addView(pageTitle, matchWrapParams());

        pages = new View[]{
                createLspPage(),
                createCorePage(),
                createAudioPage(),
                createFilePage(),
        };

        doneButton = null;
        dialog = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setTitle("连接向导")
                .setView(content)
                .setNegativeButton("上一步", null)
                .setNeutralButton("下一步", null)
                .setPositiveButton("完成", null)
                .create();
        showing = true;
        dialog.setOnDismissListener(d -> {
            showing = false;
            Pref.setInitialSetupComplete(true);
        });
        dialog.setOnShowListener(d -> {
            prevButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            nextButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            doneButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (doneButton != null) {
                doneButton.setOnClickListener(v -> finishSetup());
            }
            if (prevButton != null) {
                prevButton.setOnClickListener(v -> goToPage(page - 1));
            }
            if (nextButton != null) {
                nextButton.setOnClickListener(v -> goToPage(page + 1));
            }
            showPage(0);
        });
        dialog.show();
    }

    private void goToPage(int target) {
        if (target < 0 || target >= pages.length) {
            return;
        }
        page = target;
        showPage(page);
    }

    private void showPage(int index) {
        page = index;
        if (content == null || pages == null) {
            return;
        }
        // 移除旧页，加新页
        for (int i = 0; i < content.getChildCount(); i++) {
            View c = content.getChildAt(i);
            if (c != pageTitle) {
                content.removeView(c);
            }
        }
        // 标题在顶部
        String[] titles = {"LSPosed Hook", "Shizuku / Root / 悬浮窗", "录音", "文件访问 · 完成"};
        pageTitle.setText(titles[index]);
        content.addView(pages[index], matchWrapParams());

        boolean first = index == 0;
        boolean last = index == pages.length - 1;
        if (prevButton != null) {
            prevButton.setVisibility(first ? View.GONE : View.VISIBLE);
        }
        if (nextButton != null) {
            nextButton.setVisibility(last ? View.GONE : View.VISIBLE);
        }
        if (doneButton != null) {
            doneButton.setEnabled(last); // 完成只在最后一页可用
        }
        refreshPageStatus();
    }

    private void refreshPageStatus() {
        refreshStatusTexts();
    }

    private void refreshStatusTexts() {
        updateText(rootStatusText, rootStatusLine());
        updateText(shizukuStatusText, ShizukuUtils.hasPermission() ? "已授权" : "未授权");
        updateText(overlayStatusText, canDrawOverlays() ? "已授权" : "未授权");
        updateText(audioStatusText, isRecordAudioGranted() ? "已授权" : "未授权");
        updateText(fileStatusText, isFileAccessReady() ? "已授予" : "未授予");
        updateText(lspStatusText, lspStatusLine());
    }

    private String rootStatusLine() {
        try {
            if (State.userService != null && State.userService.isRooted()) {
                return "已以 root 运行";
            }
        } catch (Throwable ignored) {
        }
        if (ShizukuUtils.hasPermission()) {
            return "未以 root 运行";
        }
        return "待授权 Shizuku";
    }

    private String lspStatusLine() {
        // 通过 UserService 收集的 LSPosed 日志判断框架活跃度（原版/Vector 通用，不依赖包名）。
        try {
            String lsp = State.userService != null ? State.userService.fetchLspLogs() : null;
            if (lsp != null) {
                String clean = lsp.replace("=====", "");
                if (!clean.trim().isEmpty()
                        && clean.toLowerCase().contains("lsposedframework")) {
                    return "已检测到框架活跃";
                }
            }
        } catch (Throwable ignored) {
        }
        return "未检测到框架（需启用模块并重启）";
    }

    private boolean canDrawOverlays() {
        return Settings.canDrawOverlays(activity);
    }

    private boolean isFileAccessReady() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return android.os.Environment.isExternalStorageManager();
        }
        return true;
    }

    private boolean isRecordAudioGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ---------- 第 0 页：LSPosed ----------
    private View createLspPage() {
        lspStatusText = new TextView(activity);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(6), 0, 0);
        col.addView(label("检测结果"), wrapParams());
        col.addView(lspStatusText, matchWrapParams());

        TextView hint = note("LibreDeX 的核心显示/输入钩子依赖 LSPosed（原版或 Vector 均可）。\n"
                + "请在其中启用本模块，作用域勾选 android / 三星设置 / 桌面，然后重启手机使 Hook 生效。");
        col.addView(hint, matchWrapParams());

        Button openLsp = new Button(activity);
        openLsp.setText("打开 LSPosed / Vector");
        styleSmallButton(openLsp);
        openLsp.setOnClickListener(v -> openLsposedManager());
        col.addView(openLsp, wrapParams());

        return col;
    }

    private void openLsposedManager() {
        // 不靠单一包名：依次尝试已知入口，能起一个就行；起不了则提示。
        String[] pkgs = {
                "org.lsposed.manager",           // 原版
                "io.github.vvb2060.lsposed",      // Vector 常见包名
                "com.android.shell",             // 某些变种的宿主
        };
        for (String pkg : pkgs) {
            try {
                Intent launcher = activity.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launcher != null) {
                    activity.startActivity(launcher);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        Toast.makeText(activity, "未找到 LSPosed 入口，请在 LSPosed/Vector 里启用本模块后重启",
                Toast.LENGTH_LONG).show();
    }

    // ---------- 第 1 页：Root + Shizuku + 悬浮窗 ----------
    private View createCorePage() {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(6), 0, 0);

        col.addView(label("Root / UserService"), wrapParams());
        rootStatusText = new TextView(activity);
        rootStatusText.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_secondary));
        rootStatusText.setTextSize(13);
        col.addView(rootStatusText, matchWrapParams());
        Button rootBtn = new Button(activity);
        rootBtn.setText("以 root 重启");
        styleSmallButton(rootBtn);
        rootBtn.setOnClickListener(v -> {
            boolean ok = AcquireShizuku.fixRootShizuku();
            Toast.makeText(activity, ok ? "Shizuku 已以 root 重启" : "以 root 重启失败",
                    Toast.LENGTH_SHORT).show();
            refreshStatusTexts();
        });
        col.addView(rootBtn, wrapParamsR());
        col.addView(note("Root 是悬浮窗/投屏“一键静默授权”的基础；应用打开时会自动尝试拉起授权。"),
                matchWrapParams());

        col.addView(label("Shizuku"), wrapParamsR());
        shizukuStatusText = new TextView(activity);
        shizukuStatusText.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_secondary));
        shizukuStatusText.setTextSize(13);
        col.addView(shizukuStatusText, matchWrapParams());
        Button shizukuBtn = new Button(activity);
        shizukuBtn.setText("连接");
        styleSmallButton(shizukuBtn);
        shizukuBtn.setOnClickListener(v -> {
            State.startNewJob(new AcquireShizuku());
            MAIN_HANDLER.postDelayed(this::refreshStatusTexts, 800);
            MAIN_HANDLER.postDelayed(this::refreshStatusTexts, 2500);
        });
        col.addView(shizukuBtn, wrapParamsR());

        col.addView(label("悬浮窗权限"), wrapParamsR());
        overlayStatusText = new TextView(activity);
        overlayStatusText.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_secondary));
        overlayStatusText.setTextSize(13);
        col.addView(overlayStatusText, matchWrapParams());
        Button overlayBtn = new Button(activity);
        overlayBtn.setText("授权");
        styleSmallButton(overlayBtn);
        overlayBtn.setOnClickListener(v -> grantOverlay());
        col.addView(overlayBtn, wrapParamsR());

        return col;
    }

    private void grantOverlay() {
        if (canDrawOverlays()) {
            Toast.makeText(activity, "悬浮窗权限已授权", Toast.LENGTH_SHORT).show();
            refreshStatusTexts();
            return;
        }
        // 优先 root 静默授权
        try {
            boolean root = State.userService != null && State.userService.isRooted();
            if (root) {
                State.userService.executeCommand("appops set "
                        + BuildConfig.APPLICATION_ID + " SYSTEM_ALERT_WINDOW allow");
            }
        } catch (Throwable ignored) {
        }
        if (!canDrawOverlays()) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (Throwable e) {
                Toast.makeText(activity, "请手动授予悬浮窗权限", Toast.LENGTH_SHORT).show();
            }
        }
        MAIN_HANDLER.postDelayed(this::refreshStatusTexts, 600);
        MAIN_HANDLER.postDelayed(this::refreshStatusTexts, 1800);
    }

    // ---------- 第 2 页：录音 ----------
    private View createAudioPage() {
        audioStatusText = new TextView(activity);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(6), 0, 0);
        col.addView(label("录音权限"), wrapParams());
        col.addView(audioStatusText, matchWrapParams());
        col.addView(note("用于采集系统播放音频；未授权仍可串流画面，只是没有声音。可跳过。"),
                matchWrapParams());
        Button grantBtn = new Button(activity);
        grantBtn.setText("授权");
        styleSmallButton(grantBtn);
        grantBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isRecordAudioGranted()) {
                activity.requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        MirrorMainActivity.REQUEST_RECORD_AUDIO_PERMISSION);
            }
            MAIN_HANDLER.postDelayed(this::refreshStatusTexts, 600);
            MAIN_HANDLER.postDelayed(this::refreshStatusTexts, 1800);
        });
        col.addView(grantBtn, wrapParamsR());
        return col;
    }

    // ---------- 第 3 页：文件访问 + 屏幕采集说明 ----------
    private View createFilePage() {
        fileStatusText = new TextView(activity);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(6), 0, 0);
        col.addView(label("文件访问权限"), wrapParams());
        col.addView(fileStatusText, matchWrapParams());
        col.addView(note("用于把日志/压缩包写入下载目录。"), matchWrapParams());
        Button fileBtn = new Button(activity);
        fileBtn.setText("授权");
        styleSmallButton(fileBtn);
        fileBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.startActivity(
                        new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } else {
                Toast.makeText(activity, "当前系统无需额外文件权限", Toast.LENGTH_SHORT).show();
            }
            MAIN_HANDLER.postDelayed(this::refreshStatusTexts, 1500);
            MAIN_HANDLER.postDelayed(this::refreshStatusTexts, 3000);
        });
        col.addView(fileBtn, wrapParamsR());

        col.addView(label("屏幕采集（投屏）"), wrapParamsR());
        col.addView(note("开始连接时系统会自动弹出投屏授权，确认即可；无需在此提前授权。"),
                matchWrapParams());
        return col;
    }

    // ---------- UI 辅助 ----------
    private TextView label(String s) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_primary));
        t.setTextSize(15);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView note(String s) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_secondary));
        t.setTextSize(13);
        t.setPadding(0, dp(2), 0, 0);
        return t;
    }

    private void styleSmallButton(Button b) {
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), dp(4), dp(12), dp(4));
    }

    private void updateText(TextView view, String text) {
        if (view != null) {
            view.setText(text);
        }
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

    private LinearLayout.LayoutParams wrapParamsR() {
        LinearLayout.LayoutParams p = wrapParams();
        p.topMargin = dp(4);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

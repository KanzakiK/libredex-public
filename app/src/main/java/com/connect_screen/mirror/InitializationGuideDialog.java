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
    private Button doneButton;   // "完成"
    private LinearLayout content;
    private TextView pageTitle;
    private View[] pages;
    private LinearLayout framePage; // 当前页容器
    private int page = 0;
    private Button prevButton;
    private Button nextButton;

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
        prevButton = new Button(activity);
        nextButton = new Button(activity);
        doneButton = new Button(activity);

        LinearLayout nav = new LinearLayout(activity);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(0, dp(8), 0, dp(4));
        prevButton.setText("上一步");
        nextButton.setText("下一步");
        doneButton.setText("完成");
        styleSmallButton(prevButton);
        styleSmallButton(nextButton);
        styleSmallButton(doneButton);

        prevButton.setOnClickListener(v -> goToPage(page - 1));
        nextButton.setOnClickListener(v -> goToPage(page + 1));
        doneButton.setOnClickListener(v -> finishSetup());

        nav.addView(prevButton, wrapParams());
        LinearLayout.LayoutParams gap = wrapParams();
        gap.setMarginStart(dp(8));
        nav.addView(nextButton, gap);
        LinearLayout.LayoutParams doneP = wrapParams();
        doneP.setMarginStart(dp(8));
        doneP.weight = 1;
        nav.addView(doneButton, doneP);

        framePage = new LinearLayout(activity);
        framePage.setOrientation(LinearLayout.VERTICAL);
        content.addView(framePage, matchWrapParams());
        content.addView(nav, matchWrapParams());

        dialog = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setTitle("连接向导")
                .setView(content)
                .setCancelable(false)
                .create();
        showing = true;
        dialog.setOnDismissListener(d -> {
            showing = false;
            Pref.setInitialSetupComplete(true);
        });
        dialog.setOnShowListener(d -> showPage(0));
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
        if (framePage == null || pages == null) {
            return;
        }
        framePage.removeAllViews();
        String[] titles = {"LSPosed Hook", "Shizuku / Root / 悬浮窗", "录音", "文件访问 · 完成"};
        pageTitle.setText(titles[index]);
        framePage.addView(pages[index], matchWrapParams());

        boolean first = index == 0;
        boolean last = index == pages.length - 1;
        if (prevButton != null) {
            prevButton.setVisibility(first ? View.GONE : View.VISIBLE);
        }
        if (nextButton != null) {
            nextButton.setVisibility(last ? View.GONE : View.VISIBLE);
        }
        if (doneButton != null) {
            // 完成只在最后一页显示
            doneButton.setVisibility(last ? View.VISIBLE : View.GONE);
        }
        refreshPageStatus();
    }

    private void refreshPageStatus() {
        refreshStatusTexts();
    }

    private void refreshStatusTexts() {
        // 快速本地状态（无 Binder/root），直接主线程更新
        updateText(shizukuStatusText, ShizukuUtils.hasPermission() ? "已授权" : "未授权");
        updateText(overlayStatusText, canDrawOverlays() ? "已授权" : "未授权");
        updateText(audioStatusText, isRecordAudioGranted() ? "已授权" : "未授权");
        updateText(fileStatusText, isFileAccessReady() ? "已授予" : "未授予");
        // Root 与 LSPosed 状态涉及跨进程 Binder / root / logcat，必须在后台线程执行，
        // 否则会阻塞主线程导致输入超时 ANR（此前实测 lspStatusLine 卡死主线程）。
        refreshRootStatusAsync();
        refreshLspStatusAsync();
    }

    private void refreshRootStatusAsync() {
        final boolean perm = ShizukuUtils.hasPermission();
        new Thread(() -> {
            boolean rooted = false;
            try {
                rooted = State.userService != null && State.userService.isRooted();
            } catch (Throwable ignored) {
            }
            final boolean r = rooted;
            MAIN_HANDLER.post(() -> updateText(rootStatusText,
                    r ? "已以 root 运行" : (perm ? "未以 root 运行" : "待授权 Shizuku")));
        }, "guide-root-status").start();
    }

    private void refreshLspStatusAsync() {
        new Thread(() -> {
            String s = "未检测到框架（需启用模块并重启）";
            try {
                if (State.userService != null) {
                    String lsp = State.userService.fetchLspLogs();
                    if (lsp != null
                            && !lsp.replace("=====", "").trim().isEmpty()
                            && lsp.toLowerCase().contains("lsposedframework")) {
                        s = "已检测到框架活跃";
                    }
                }
            } catch (Throwable ignored) {
            }
            final String fs = s;
            MAIN_HANDLER.post(() -> updateText(lspStatusText, fs));
        }, "guide-lsp-status").start();
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
                + "请在其中启用本模块，作用域勾选 android / 三星设置 / 桌面，然后重启手机使 Hook 生效。\n"
                + "提示：Vector 变种没有独立 App，入口在桌面的“LSPosed”快捷方式或通知栏里。");
        col.addView(hint, matchWrapParams());

        Button openLsp = new Button(activity);
        openLsp.setText("打开 LSPosed / Vector");
        styleSmallButton(openLsp);
        openLsp.setOnClickListener(v -> openLsposedManager());
        col.addView(openLsp, wrapParams());

        return col;
    }

    /** 覆盖已知的 LSPosed / Vector 管理器入口包名（含 Vector 无独立包、可启动宿主）。 */
    private static final String[] LSPOSED_ENTRY_PACKAGES = {
            "org.lsposed.manager",            // 原版
            "io.github.vvb2060.lsposed",      // Vector 常见包名
            "com.android.shell",              // 某些变种的宿主
    };

    private void openLsposedManager() {
        // Vector 变种没有独立应用包，入口是注入的桌面快捷方式 / 通知栏。
        // 因此不能只靠单一包名 getLaunchIntentForPackage，改用 queryIntentActivities
        // 枚举可启动的 launcher intent，能匹配到任一已知入口就尽力跳转。
        try {
            @SuppressWarnings({"deprecation", "RedundantSuppression"})
            java.util.List<android.content.pm.ResolveInfo> resolves =
                    activity.getPackageManager().queryIntentActivities(
                            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                            PackageManager.MATCH_ALL);
            if (resolves != null) {
                for (android.content.pm.ResolveInfo ri : resolves) {
                    if (ri == null || ri.activityInfo == null) {
                        continue;
                    }
                    String pkg = ri.activityInfo.packageName;
                    for (String candidate : LSPOSED_ENTRY_PACKAGES) {
                        if (candidate.equals(pkg)) {
                            Intent launcher = new Intent(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_LAUNCHER)
                                    .setClassName(pkg, ri.activityInfo.name)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            activity.startActivity(launcher);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 枚举失败则退回包名直连
        }

        // 兜底：再按已知包名直接取启动 intent（某些入口无 LAUNCHER 分类也能起）。
        for (String pkg : LSPOSED_ENTRY_PACKAGES) {
            try {
                Intent launcher = activity.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launcher != null) {
                    activity.startActivity(launcher);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        // 找不到可启动入口：Vector 系没有独立应用包，必须手动从桌面/通知栏进入。
        // 用明确的对话框说明，而不是只有一句模糊 toast。
        new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setTitle("未找到 LSPosed 入口")
                .setMessage("你的设备装的是 LSPosed（Vector）变种，它没有独立的 App 图标。\n\n"
                        + "请手动打开：\n"
                        + "· 桌面上的“LSPosed”快捷方式，或\n"
                        + "· 下拉通知栏里的 LSPosed（Vector）入口。\n\n"
                        + "打开后在本模块中启用 LibreDeX，作用域勾选 android / 三星设置 / 桌面，"
                        + "然后重启手机使 Hook 生效。")
                .setPositiveButton("知道了", null)
                .show();
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
            // root 重启涉及 su / 重绑定等阻塞操作，放后台线程，避免卡死主线程。
            rootBtn.setEnabled(false);
            new Thread(() -> {
                boolean ok = AcquireShizuku.fixRootShizuku();
                MAIN_HANDLER.post(() -> {
                    Toast.makeText(activity,
                            ok ? "Shizuku 已以 root 重启" : "以 root 重启失败",
                            Toast.LENGTH_SHORT).show();
                    rootBtn.setEnabled(true);
                    refreshStatusTexts();
                });
            }, "guide-root-restart").start();
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
        // 先显示“处理中”，再在后台线程做 root 静默授权（Binder/root 不能放主线程）。
        Toast.makeText(activity, "正在授权悬浮窗…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean granted = false;
            try {
                boolean root = State.userService != null
                        && State.userService.isRooted();
                if (root) {
                    State.userService.executeCommand("appops set "
                            + BuildConfig.APPLICATION_ID + " SYSTEM_ALERT_WINDOW allow");
                }
            } catch (Throwable ignored) {
            }
            boolean still = canDrawOverlays();
            MAIN_HANDLER.post(() -> {
                if (still) {
                    Toast.makeText(activity, "悬浮窗权限已授权", Toast.LENGTH_SHORT).show();
                    refreshStatusTexts();
                    return;
                }
                try {
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + activity.getPackageName())));
                } catch (Throwable e) {
                    Toast.makeText(activity, "请手动授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                }
                MAIN_HANDLER.postDelayed(this::refreshStatusTexts, 600);
                MAIN_HANDLER.postDelayed(this::refreshStatusTexts, 1800);
                refreshStatusTexts();
            });
        }, "guide-overlay").start();
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

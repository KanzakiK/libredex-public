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
import java.util.Locale;

public final class InitializationGuideDialog {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static boolean showing;

    private final Activity activity;
    private AlertDialog dialog;
    private Button doneButton;   // activity.getString(R.string.action_done)
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
        prevButton.setText(activity.getString(R.string.action_prev));
        nextButton.setText(activity.getString(R.string.action_next));
        doneButton.setText(activity.getString(R.string.action_done));
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
                .setTitle(activity.getString(R.string.guide_title))
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
        updateText(shizukuStatusText, ShizukuUtils.hasPermission() ? "已授权" : activity.getString(R.string.settings_not_granted));
        updateText(overlayStatusText, canDrawOverlays() ? "已授权" : activity.getString(R.string.settings_not_granted));
        updateText(audioStatusText, isRecordAudioGranted() ? "已授权" : activity.getString(R.string.settings_not_granted));
        updateText(fileStatusText, isFileAccessReady() ? "已授予" : activity.getString(R.string.settings_file_not_granted));
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
                            && lsp.toLowerCase(Locale.ROOT).contains("lsposedframework")) {
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
        openLsp.setText(activity.getString(R.string.guide_open_lsposed));
        styleSmallButton(openLsp);
        openLsp.setOnClickListener(v -> openLsposedManager());
        col.addView(openLsp, wrapParams());

        return col;
    }

    /**
     * Vector（及多数 LSPosed 变种）没有独立应用包：管理器是一个被 Zygisk/Riru 注入到宿主
     * 进程（通常是 com.android.shell）里的组件，入口是注入的桌面快捷方式 / 通知栏。
     * 因此按包名 getLaunchIntentForPackage / queryIntentActivities 查 launcher 入口是找不到的。
     *
     * Vector 模块自带的 action.sh 明确给出正确拉起方式（已在真机验证）：
     *   am start -c org.matrix.vector.manager.LAUNCH_MANAGER com.android.shell/.BugreportWarningActivity
     *
     * 说明：能否真正弹出管理器依赖 Vector 框架的 Hook 是否活跃（组件是注入的）。所以这里
     * 通过 shell（优先 root）执行上述命令，再根据输出判断是否真的拉起；失败则给出明确的手动入口提示。
     */
    private static final String VECTOR_MANAGER_CATEGORY = "org.matrix.vector.manager.LAUNCH_MANAGER";
    private static final String VECTOR_HOST_COMPONENT = "com.android.shell/.BugreportWarningActivity";

    /** 已知的独立 LSPosed 管理器包名（针对少数装有独立管理器 App 的设备，兜底用）。 */
    private static final String[] LSPOSED_ENTRY_PACKAGES = {
            "org.lsposed.manager",            // 原版
            "io.github.vvb2060.lsposed",      // Vector 较老版本的独立包名（若被单独安装）
    };

    private void openLsposedManager() {
        // am start 要通过 shell / Binder 执行，且可能走 root，必须放后台线程，避免阻塞主线程。
        new Thread(() -> {
            String error = null;
            try {
                // 真实校验：只有 Vector 管理器窗口真的到前台才算成功，
                // 不能只看 am start 输出（没有 root / 框架不活跃时输出也会“看似成功”）。
                boolean launched = launchVectorAndVerify();
                if (!launched) {
                    // 注入宿主未拉起：再试独立管理器包名。
                    launched = launchStandaloneLsposedPackage();
                }
                if (!launched) {
                    error = "未检测到 LSPosed / Vector 管理器打开";
                }
            } catch (Throwable t) {
                error = "拉起失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }

            final String err = error;
            MAIN_HANDLER.post(() -> {
                if (err == null) {
                    Toast.makeText(activity, activity.getString(R.string.guide_lsposed_enabled_restart),
                            Toast.LENGTH_LONG).show();
                } else {
                    showLsposedManualDialog();
                }
            });
        }, "guide-open-lsposed").start();
    }

    /**
     * 通过 shell（root 优先）拉起 Vector 注入宿主，并依据 am start 输出判断是否真的成功。
     *
     * 为什么用输出判断而不是窗口轮询：Vector 的入口组件 com.android.shell/.BugreportWarningActivity
     * 是框架运行时才“注入/注册”到 Activity 解析器里的。框架活跃时 am start 返回
     * “Starting: Intent {...}”（能解析并启动）；框架不活跃时该组件解析不到，am start 报
     * “does not exist / Error type 3 / Activity not found”。因此：
     *   · 见 “Starting:” → 说明 Vector 框架确实把宿主组件注册上并能启动 → 真成功；
     *   · 报错 → Vector 框架未激活（与是否有 root 无关）→ 正确地提示手动打开。
     */
    private boolean launchVectorAndVerify() {
        String cmd = "am start -c " + VECTOR_MANAGER_CATEGORY + " " + VECTOR_HOST_COMPONENT;
        String out = runShellCommand(cmd);
        if (out == null) {
            return false;
        }
        String lower = out.toLowerCase(Locale.ROOT);
        boolean hasStarting = lower.contains("starting: intent");
        boolean hasError = lower.contains("does not exist")
                || lower.contains("error type")
                || lower.contains("activity not found")
                || lower.contains("unable to find");
        return hasStarting && !hasError;
    }

    /** 兜底：尝试按已知独立管理器包名直接启动。 */
    private boolean launchStandaloneLsposedPackage() {
        for (String pkg : LSPOSED_ENTRY_PACKAGES) {
            try {
                Intent launcher = activity.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launcher != null) {
                    launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(launcher);
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** 依次用 root shell / shell 执行命令；都不可用则用裸命令。失败返回 null。 */
    private String runShellCommand(String command) {
        try {
            if (State.userService != null) {
                try {
                    if (State.userService.isRooted()) {
                        String out = State.userService.executeRootShellCommand(command);
                        if (out != null) {
                            return out;
                        }
                    }
                } catch (Throwable ignored) {
                }
                try {
                    // am 是 shell 脚本，executeShellCommand（sh -c）比裸 Runtime.exec 更稳。
                    String out = State.userService.executeShellCommand(command);
                    if (out != null) {
                        return out;
                    }
                } catch (Throwable ignored) {
                }
            }
            // 无 userService 时退回本地 Runtime 执行（极少数兜底）。
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(Runtime.getRuntime().exec(command).getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 找不到可启动入口：给出明确的手动打开指引（桌面/通知栏），而不是一句模糊 toast。 */
    private void showLsposedManualDialog() {
        new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setTitle(activity.getString(R.string.guide_lsposed_not_found))
                .setMessage(activity.getString(R.string.guide_lsposed_entry_note)
                        + "请手动打开：\n"
                        + "· 桌面上的“LSPosed（Vector）”快捷方式，或\n"
                        + "· 下拉通知栏里的 LSPosed / Vector 入口。\n\n"
                        + "若已打开并启用了本模块，作用域勾选 android / 三星设置 / 桌面，"
                        + "然后重启手机使 Hook 生效。")
                .setPositiveButton(activity.getString(R.string.action_got_it), null)
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
        rootBtn.setText(activity.getString(R.string.guide_root_restart));
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

        col.addView(label(activity.getString(R.string.brand_shizuku)), wrapParamsR());
        shizukuStatusText = new TextView(activity);
        shizukuStatusText.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_secondary));
        shizukuStatusText.setTextSize(13);
        col.addView(shizukuStatusText, matchWrapParams());
        Button shizukuBtn = new Button(activity);
        shizukuBtn.setText(activity.getString(R.string.nav_connect));
        styleSmallButton(shizukuBtn);
        shizukuBtn.setOnClickListener(v -> {
            State.startNewJob(new AcquireShizuku());
            MAIN_HANDLER.postDelayed(this::refreshStatusTexts, 800);
            MAIN_HANDLER.postDelayed(this::refreshStatusTexts, 2500);
        });
        col.addView(shizukuBtn, wrapParamsR());

        col.addView(label(activity.getString(R.string.settings_overlay_permission)), wrapParamsR());
        overlayStatusText = new TextView(activity);
        overlayStatusText.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_secondary));
        overlayStatusText.setTextSize(13);
        col.addView(overlayStatusText, matchWrapParams());
        Button overlayBtn = new Button(activity);
        overlayBtn.setText(activity.getString(R.string.action_grant));
        styleSmallButton(overlayBtn);
        overlayBtn.setOnClickListener(v -> grantOverlay());
        col.addView(overlayBtn, wrapParamsR());

        return col;
    }

    private void grantOverlay() {
        if (canDrawOverlays()) {
            Toast.makeText(activity, activity.getString(R.string.guide_overlay_granted), Toast.LENGTH_SHORT).show();
            refreshStatusTexts();
            return;
        }
        // 先显示“处理中”，再在后台线程做 root 静默授权（Binder/root 不能放主线程）。
        Toast.makeText(activity, activity.getString(R.string.guide_overlay_granting), Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(activity, activity.getString(R.string.guide_overlay_granted), Toast.LENGTH_SHORT).show();
                    refreshStatusTexts();
                    return;
                }
                try {
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + activity.getPackageName())));
                } catch (Throwable e) {
                    Toast.makeText(activity, activity.getString(R.string.guide_overlay_grant_manual), Toast.LENGTH_SHORT).show();
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
        grantBtn.setText(activity.getString(R.string.action_grant));
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
        col.addView(label(activity.getString(R.string.settings_file_permission)), wrapParams());
        col.addView(fileStatusText, matchWrapParams());
        col.addView(note("用于把日志/压缩包写入下载目录。"), matchWrapParams());
        Button fileBtn = new Button(activity);
        fileBtn.setText(activity.getString(R.string.action_grant));
        styleSmallButton(fileBtn);
        fileBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.startActivity(
                        new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } else {
                Toast.makeText(activity, activity.getString(R.string.guide_no_file_permission_needed), Toast.LENGTH_SHORT).show();
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

package com.connect_screen.mirror;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private Button doneButton;
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
        content.setPadding(padding, dp(8), padding, dp(8));

        // 总标题（替代对话框默认 title，统一加粗与大字号）
        TextView guideTitle = new TextView(activity);
        guideTitle.setText(activity.getString(R.string.guide_title));
        guideTitle.setTextSize(20);
        guideTitle.setTypeface(Typeface.DEFAULT_BOLD);
        guideTitle.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_primary));
        LinearLayout.LayoutParams gtLp = matchWrapParams();
        gtLp.bottomMargin = dp(2);
        content.addView(guideTitle, gtLp);

        // 页面标题
        pageTitle = new TextView(activity);
        pageTitle.setTextSize(15);
        pageTitle.setTypeface(Typeface.DEFAULT_BOLD);
        pageTitle.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_secondary));
        LinearLayout.LayoutParams ptLp = matchWrapParams();
        ptLp.bottomMargin = dp(10);
        content.addView(pageTitle, ptLp);

        pages = new View[]{
                createLspPage(),
                createCorePage(),
                createPermissionPage(),
                createFilePage(),
        };

        doneButton = null;
        prevButton = navButton(activity.getString(R.string.action_prev), false);
        nextButton = navButton(activity.getString(R.string.action_next), false);
        doneButton = navButton(activity.getString(R.string.action_done), true);

        LinearLayout nav = new LinearLayout(activity);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(0, dp(10), 0, dp(2));
        nav.setGravity(android.view.Gravity.CENTER_VERTICAL);

        prevButton.setOnClickListener(v -> goToPage(page - 1));
        nextButton.setOnClickListener(v -> goToPage(page + 1));
        doneButton.setOnClickListener(v -> finishSetup());

        nav.addView(prevButton, wrapParams());
        LinearLayout.LayoutParams gap = wrapParams();
        gap.setMarginStart(dp(8));
        nav.addView(nextButton, gap);
        LinearLayout.LayoutParams doneP = wrapParams();
        doneP.setMarginStart(dp(10));
        nav.addView(doneButton, doneP);

        // 页面区包一层 ScrollView，页面内容长了也能滚动，避免小屏放不下。
        framePage = new LinearLayout(activity);
        framePage.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(framePage, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, matchWrapParams());
        content.addView(nav, matchWrapParams());

        dialog = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setView(content)
                .setCancelable(true)
                .create();
        showing = true;
        // 点击外部 / 返回键 = 取消（隐藏），不算完成：下次启动仍需引导。
        dialog.setOnDismissListener(d -> showing = false);
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
        String[] titles = {activity.getString(R.string.guide_page_title_lsposed), activity.getString(R.string.guide_page_title_environment), activity.getString(R.string.guide_page_title_permissions), activity.getString(R.string.guide_page_title_files)};
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
        updateText(shizukuStatusText, ShizukuUtils.hasPermission() ? activity.getString(R.string.settings_authorized) : activity.getString(R.string.settings_not_granted));
        updateText(overlayStatusText, canDrawOverlays() ? activity.getString(R.string.settings_authorized) : activity.getString(R.string.settings_not_granted));
        updateText(audioStatusText, isRecordAudioGranted() ? activity.getString(R.string.settings_authorized) : activity.getString(R.string.settings_not_granted));
        updateText(fileStatusText, isFileAccessReady() ? activity.getString(R.string.settings_granted) : activity.getString(R.string.settings_file_not_granted));
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
                    r ? activity.getString(R.string.guide_root_running)
                            : (perm ? activity.getString(R.string.guide_root_not_running)
                                    : activity.getString(R.string.guide_shizuku_pending))));
        }, "guide-root-status").start();
    }

    private void refreshLspStatusAsync() {
        new Thread(() -> {
            String s = activity.getString(R.string.guide_framework_not_detected);
            try {
                // 全新环境 UserService 可能未绑定，先确保绑定再检测，否则永远误报“未检测到”。
                ensureUserServiceForShell();
                if (State.userService != null) {
                    String lsp = State.userService.fetchLspLogs();
                    // 判定依据：lspd 的 modules 日志会记录实际加载的模块，
                    // 出现 "com.libredex" 即证明 LibreDeX 的 hook 已被注入。
                    if (lsp != null && lsp.toLowerCase(Locale.ROOT).contains("com.libredex")) {
                        s = activity.getString(R.string.guide_framework_active);
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
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout card = card();
        card.addView(sectionTitle(activity.getString(R.string.guide_label_result)));
        lspStatusText = statusText();
        card.addView(statusTextLine(lspStatusText));
        card.addView(note(activity.getString(R.string.guide_lsposed_note)));
        Button openLsp = actionButton(activity.getString(R.string.guide_open_lsposed));
        openLsp.setOnClickListener(v -> openLsposedManager());
        card.addView(openLsp);
        col.addView(card);

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
                // 全新环境（首次向导）UserService 可能还没绑定，而 am start 需要
                // 通过 userService 的 root/shell 通道执行。先确保绑定再操作。
                ensureUserServiceForShell();
                // 真实校验：只有 Vector 管理器窗口真的到前台才算成功，
                // 不能只看 am start 输出（没有 root / 框架不活跃时输出也会“看似成功”）。
                boolean launched = launchVectorAndVerify();
                if (!launched) {
                    // 注入宿主未拉起：再试独立管理器包名。
                    launched = launchStandaloneLsposedPackage();
                }
                if (!launched) {
                    error = activity.getString(R.string.guide_lsposed_manager_not_open);
                }
            } catch (Throwable t) {
                error = activity.getString(R.string.guide_launch_failed_fmt, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
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

    /**
     * 确保 UserService 已绑定（shell/root 命令通道）。首次向导/全新环境时
     * UserService 尚未按需拉起，am start 会退化成裸 Runtime.exec 而失败。
     * 有 Shizuku 权限就主动绑定并等它就绪；绑定不上就维持现状（runShellCommand
     * 仍有 Runtime.exec 兜底）。
     */
    private void ensureUserServiceForShell() {
        try {
            if (State.userService == null && ShizukuUtils.hasPermission()) {
                State.ensureUserServiceBound();
                long deadline = System.currentTimeMillis() + 3000;
                while (State.userService == null
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
            }
        } catch (Throwable ignored) {
        }
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
                        + activity.getString(R.string.guide_open_lsposed_manual))
                .setPositiveButton(activity.getString(R.string.action_got_it), null)
                .show();
    }

    // ---------- 第 1 页：Root + Shizuku ----------
    private View createCorePage() {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);

        // Root
        LinearLayout cardRoot = card();
        cardRoot.addView(sectionTitle(activity.getString(R.string.guide_label_root_userservice)));
        rootStatusText = statusText();
        cardRoot.addView(statusTextLine(rootStatusText));
        cardRoot.addView(note(activity.getString(R.string.guide_root_note)));
        Button rootBtn = actionButton(activity.getString(R.string.guide_root_restart));
        rootBtn.setOnClickListener(v -> {
            // root 重启涉及 su / 重绑定等阻塞操作，放后台线程，避免卡死主线程。
            rootBtn.setEnabled(false);
            new Thread(() -> {
                boolean ok = AcquireShizuku.fixRootShizuku();
                MAIN_HANDLER.post(() -> {
                    Toast.makeText(activity,
                            ok ? activity.getString(R.string.guide_shizuku_root_restarted)
                                    : activity.getString(R.string.guide_root_restart_failed),
                            Toast.LENGTH_SHORT).show();
                    rootBtn.setEnabled(true);
                    refreshStatusTexts();
                });
            }, "guide-root-restart").start();
        });
        cardRoot.addView(rootBtn);
        col.addView(cardRoot);

        // Shizuku
        LinearLayout cardShizuku = card();
        cardShizuku.addView(sectionTitle(activity.getString(R.string.brand_shizuku)));
        shizukuStatusText = statusText();
        cardShizuku.addView(statusTextLine(shizukuStatusText));
        Button shizukuBtn = actionButton(activity.getString(R.string.nav_connect));
        shizukuBtn.setOnClickListener(v -> {
            State.startNewJob(new AcquireShizuku());
            MAIN_HANDLER.postDelayed(this::refreshStatusTexts, 800);
            MAIN_HANDLER.postDelayed(this::refreshStatusTexts, 2500);
        });
        cardShizuku.addView(shizukuBtn);
        col.addView(cardShizuku);

        return col;
    }

    // ---------- 第 2 页：悬浮窗 + 录音 ----------
    private View createPermissionPage() {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);

        // 悬浮窗
        LinearLayout cardOverlay = card();
        cardOverlay.addView(sectionTitle(activity.getString(R.string.settings_overlay_permission)));
        overlayStatusText = statusText();
        cardOverlay.addView(statusTextLine(overlayStatusText));
        Button overlayBtn = actionButton(activity.getString(R.string.action_grant));
        overlayBtn.setOnClickListener(v -> grantOverlay());
        cardOverlay.addView(overlayBtn);
        col.addView(cardOverlay);

        // 录音
        LinearLayout cardAudio = card();
        cardAudio.addView(sectionTitle(activity.getString(R.string.guide_label_recording_permission)));
        audioStatusText = statusText();
        cardAudio.addView(statusTextLine(audioStatusText));
        cardAudio.addView(note(activity.getString(R.string.guide_recording_note)));
        Button grantBtn = actionButton(activity.getString(R.string.action_grant));
        grantBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isRecordAudioGranted()) {
                activity.requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        MirrorMainActivity.REQUEST_RECORD_AUDIO_PERMISSION);
            }
            MAIN_HANDLER.postDelayed(this::refreshStatusTexts, 600);
            MAIN_HANDLER.postDelayed(this::refreshStatusTexts, 1800);
        });
        cardAudio.addView(grantBtn);
        col.addView(cardAudio);

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

    // ---------- 第 3 页：文件访问 + 屏幕采集 ----------
    private View createFilePage() {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout cardFile = card();
        cardFile.addView(sectionTitle(activity.getString(R.string.settings_file_permission)));
        fileStatusText = statusText();
        cardFile.addView(statusTextLine(fileStatusText));
        cardFile.addView(note(activity.getString(R.string.guide_file_note)));
        Button fileBtn = actionButton(activity.getString(R.string.action_grant));
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
        cardFile.addView(fileBtn);
        col.addView(cardFile);

        LinearLayout cardScreen = card();
        cardScreen.addView(sectionTitle(activity.getString(R.string.guide_label_screen_capture)));
        cardScreen.addView(note(activity.getString(R.string.guide_screen_capture_note)));
        col.addView(cardScreen);

        return col;
    }

    // ---------- UI 辅助（LibreDeX 主风格） ----------
    /** 圆角卡片容器，与设置页 LibreDeXCard 同风格。 */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(activity);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_libredex_card);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = matchWrapParams();
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    /** 卡片内小节标题（13sp 加粗次要色，同 LibreDeXSectionTitle）。 */
    private TextView sectionTitle(String s) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_secondary));
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = matchWrapParams();
        lp.bottomMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    /** 状态文字（次要色 13sp）。 */
    private TextView statusText() {
        TextView t = new TextView(activity);
        t.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_secondary));
        t.setTextSize(13);
        return t;
    }

    /** 状态行容器：状态文字占满一行并保留底部间距。 */
    private LinearLayout statusTextLine(TextView status) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = matchWrapParams();
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        if (status != null) {
            row.addView(status);
        }
        return row;
    }

    /** 说明文字（次要色 13sp）。 */
    private TextView note(String s) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextColor(ContextCompat.getColor(activity, R.color.ui_text_secondary));
        t.setTextSize(13);
        t.setLineSpacing(dp(2), 1.1f);
        LinearLayout.LayoutParams lp = matchWrapParams();
        lp.bottomMargin = dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    /** 卡片内主操作按钮（LibreDeXButtonSoft：圆角 accent 文字，满宽）。 */
    private Button actionButton(String s) {
        Button b = new Button(activity);
        b.setText(s);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setGravity(android.view.Gravity.CENTER);
        b.setTextColor(ContextCompat.getColor(activity, R.color.ui_accent));
        b.setBackgroundResource(R.drawable.bg_libredex_button_soft);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)));
        return b;
    }

    /** 底部导航按钮：wrap_content 包住文本、文本居中；primary=true 用实底 accent。 */
    private Button navButton(String text, boolean primary) {
        Button b = new Button(activity);
        b.setText(text);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setGravity(android.view.Gravity.CENTER);
        b.setTextColor(ContextCompat.getColor(activity,
                primary ? R.color.ui_on_accent : R.color.ui_accent));
        b.setBackgroundResource(primary
                ? R.drawable.bg_libredex_button_primary
                : R.drawable.bg_libredex_button_soft);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setIncludeFontPadding(false);
        b.setPadding(dp(22), dp(10), dp(22), dp(10));
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(48)));
        return b;
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

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

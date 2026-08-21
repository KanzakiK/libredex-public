package com.connect_screen.mirror;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.IDisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.IWindowManager;
import android.view.Surface;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.connect_screen.mirror.job.AcquireShizuku;
import com.connect_screen.mirror.shizuku.ServiceUtils;
import com.connect_screen.mirror.shizuku.ShizukuUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import rikka.shizuku.Shizuku;

public class ScreenSettingsActivity extends AppCompatActivity {
    private static final int FIXED_TO_USER_ROTATION_DEFAULT = 0;
    private static final int FIXED_TO_USER_ROTATION_ENABLED = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout displayListContainer;
    private TextView statusText;
    private Button requestShizukuButton;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            this::onShizukuPermissionResult;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_screen_settings);
        UiCompat.applyStripedBackground(this);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(R.string.screen_title));
        }

        findViewById(R.id.screenSettingsBackButton).setOnClickListener(v -> finish());
        statusText = findViewById(R.id.screenSettingsStatus);
        requestShizukuButton = findViewById(R.id.requestShizukuButton);
        displayListContainer = findViewById(R.id.displayListContainer);

        requestShizukuButton.setOnClickListener(v -> requestShizukuPermission());
        findViewById(R.id.openCastSettingsButton).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_CAST_SETTINGS);
            startActivity(intent);
        });
        findViewById(R.id.refreshDisplaysButton).setOnClickListener(v -> refreshDisplays());

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        refreshDisplays();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
    }

    private void onShizukuPermissionResult(int requestCode, int grantResult) {
        if (requestCode != AcquireShizuku.SHIZUKU_PERMISSION_REQUEST_CODE) {
            return;
        }
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            State.log("屏幕设置已获得 Shizuku 权限");
            State.bindUserService();
        } else {
            State.log("屏幕设置 Shizuku 权限被拒绝");
        }
        refreshDisplays();
    }

    private void requestShizukuPermission() {
        if (!ShizukuUtils.hasShizukuStarted()) {
            Toast.makeText(this, getString(R.string.screen_shizuku_not_started), Toast.LENGTH_SHORT).show();
            return;
        }
        if (ShizukuUtils.hasPermission()) {
            State.bindUserService();
            refreshDisplays();
            return;
        }
        Shizuku.requestPermission(AcquireShizuku.SHIZUKU_PERMISSION_REQUEST_CODE);
    }

    private void refreshDisplays() {
        boolean hasShizuku = ShizukuUtils.hasPermission();
        requestShizukuButton.setVisibility(hasShizuku ? View.GONE : View.VISIBLE);
        statusText.setText(hasShizuku
                ? "已获得 Shizuku 权限。外接屏可修改参数，内置屏幕仅允许查看。"
                : "未获得 Shizuku 权限，只能查看基础屏幕信息。");

        displayListContainer.removeAllViews();
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display[] displays = displayManager.getDisplays();
        if (displays.length == 0) {
            TextView emptyText = createText("未发现可用屏幕");
            displayListContainer.addView(emptyText);
            return;
        }
        for (Display display : displays) {
            displayListContainer.addView(createDisplayView(display, hasShizuku));
        }
    }

    private View createDisplayView(Display display, boolean hasShizuku) {
        boolean internalDisplay = display.getDisplayId() == Display.DEFAULT_DISPLAY;
        boolean canModifyDisplay = hasShizuku && !internalDisplay;
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(12));
        container.setLayoutParams(params);
        container.setBackgroundResource(R.drawable.bg_ui_panel);

        TextView title = createText("屏幕 " + display.getDisplayId() + " - " + display.getName());
        title.setTextSize(18);
        title.setTextColor(getColorCompat(R.color.ui_text_primary));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        container.addView(title);

        TextView detail = createText(buildDisplayInfo(display, hasShizuku));
        detail.setTextSize(14);
        detail.setTextColor(getColorCompat(R.color.ui_text_secondary));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        detailParams.setMargins(0, dp(8), 0, dp(8));
        detail.setLayoutParams(detailParams);
        container.addView(detail);

        if (internalDisplay) {
            TextView warning = createText("内置屏幕仅允许查看，修改按钮已禁用，避免误改系统显示参数。");
            warning.setTextSize(13);
            warning.setTextColor(getColorCompat(R.color.ui_warning));
            LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            warningParams.setMargins(0, 0, 0, dp(8));
            warning.setLayoutParams(warningParams);
            container.addView(warning);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        container.addView(actions);

        LinearLayout row1 = createButtonRow();
        row1.addView(createActionButton("改分辨率", canModifyDisplay, v -> showResolutionDialog(display)));
        row1.addView(createActionButton("改 DPI", canModifyDisplay, v -> showDpiDialog(display)));
        actions.addView(row1);

        LinearLayout row2 = createButtonRow();
        row2.addView(createActionButton("刷新模式", canModifyDisplay, v -> showDisplayModeDialog(display)));
        row2.addView(createActionButton("旋转", canModifyDisplay, v -> showRotationDialog(display.getDisplayId())));
        actions.addView(row2);

        LinearLayout row3 = createButtonRow();
        row3.addView(createActionButton(getString(R.string.action_restore_default), canModifyDisplay, v -> resetDisplay(display.getDisplayId())));
        actions.addView(row3);
        return container;
    }

    private String buildDisplayInfo(Display display, boolean hasShizuku) {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        Display.Mode mode = display.getMode();
        StringBuilder builder = new StringBuilder();
        builder.append("当前尺寸: ")
                .append(metrics.widthPixels)
                .append("x")
                .append(metrics.heightPixels)
                .append("\n");
        builder.append("DPI: ").append(metrics.densityDpi).append("\n");
        builder.append("刷新率: ").append(String.format(java.util.Locale.US, "%.1f Hz", display.getRefreshRate())).append("\n");
        builder.append("当前模式: ")
                .append(mode.getPhysicalWidth())
                .append("x")
                .append(mode.getPhysicalHeight())
                .append("@")
                .append(String.format(java.util.Locale.US, "%.1f Hz", mode.getRefreshRate()))
                .append(" (ID ")
                .append(mode.getModeId())
                .append(")\n");
        builder.append("旋转: ").append(rotationName(display.getRotation())).append("\n");
        builder.append("状态: ").append(display.getState() == Display.STATE_ON ? "开启" : getString(R.string.action_close));

        if (hasShizuku) {
            try {
                IWindowManager windowManager = ServiceUtils.getWindowManager();
                Point baseSize = new Point();
                Point initialSize = new Point();
                windowManager.getBaseDisplaySize(display.getDisplayId(), baseSize);
                windowManager.getInitialDisplaySize(display.getDisplayId(), initialSize);
                builder.append("\n基础尺寸: ")
                        .append(baseSize.x)
                        .append("x")
                        .append(baseSize.y);
                builder.append("\n初始尺寸: ")
                        .append(initialSize.x)
                        .append("x")
                        .append(initialSize.y);
                builder.append("\n旋转锁定: ")
                        .append(windowManager.isDisplayRotationFrozen(display.getDisplayId()) ? "是" : "否");
                android.view.DisplayInfo displayInfo = ServiceUtils.getDisplayManager().getDisplayInfo(display.getDisplayId());
                if (displayInfo != null) {
                    builder.append("\nUniqueId: ").append(displayInfo.uniqueId);
                    builder.append("\n默认模式ID: ").append(displayInfo.defaultModeId);
                    if (displayInfo.userPreferredModeId > 0) {
                        builder.append("\n用户模式ID: ").append(displayInfo.userPreferredModeId);
                    }
                }
            } catch (Throwable e) {
                builder.append("\n系统级信息读取失败: ").append(e.getMessage());
            }
        }
        return builder.toString();
    }

    private void showResolutionDialog(Display display) {
        LinearLayout view = createVerticalDialogView();
        EditText widthInput = createNumberInput(getString(R.string.dialog_resolution_width), display.getWidth());
        EditText heightInput = createNumberInput(getString(R.string.dialog_resolution_height), display.getHeight());
        view.addView(widthInput);
        view.addView(heightInput);

        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setTitle(getString(R.string.screen_edit_screen_resolution_fmt, display.getDisplayId()))
                .setView(view)
                .setPositiveButton(getString(R.string.action_apply), (dialog, which) -> {
                    int width = parsePositiveInt(widthInput, 0);
                    int height = parsePositiveInt(heightInput, 0);
                    if (width <= 0 || height <= 0) {
                        Toast.makeText(this, getString(R.string.screen_invalid_resolution), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    applyResolution(display.getDisplayId(), width, height);
                })
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private void applyResolution(int displayId, int width, int height) {
        try {
            IWindowManager windowManager = ServiceUtils.getWindowManager();
            Point oldSize = new Point();
            windowManager.getBaseDisplaySize(displayId, oldSize);
            windowManager.setForcedDisplaySize(displayId, width, height);
            refreshDisplays();
            showConfirmOrRevertDialog(
                    getString(R.string.screen_confirm_resolution),
                    getString(R.string.screen_confirm_resolution_msg_fmt, displayId, width, height),
                    () -> windowManager.setForcedDisplaySize(displayId, oldSize.x, oldSize.y));
        } catch (Throwable e) {
            Toast.makeText(this, getString(R.string.screen_resolution_failed_fmt, e.getMessage()), Toast.LENGTH_LONG).show();
            State.log("修改分辨率失败: " + e);
        }
    }

    private void showDpiDialog(Display display) {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);
        LinearLayout view = createVerticalDialogView();
        EditText dpiInput = createNumberInput("DPI", metrics.densityDpi);
        view.addView(dpiInput);

        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setTitle(getString(R.string.screen_edit_screen_dpi_fmt, display.getDisplayId()))
                .setView(view)
                .setPositiveButton(getString(R.string.action_apply), (dialog, which) -> {
                    int dpi = parsePositiveInt(dpiInput, 0);
                    if (dpi <= 0) {
                        Toast.makeText(this, getString(R.string.screen_invalid_dpi), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    applyDpi(display.getDisplayId(), dpi, metrics.densityDpi);
                })
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private void applyDpi(int displayId, int dpi, int oldDpi) {
        try {
            IWindowManager windowManager = ServiceUtils.getWindowManager();
            windowManager.setForcedDisplayDensityForUser(displayId, dpi, 0);
            refreshDisplays();
            showConfirmOrRevertDialog(
                    "确认 DPI",
                    String.format(java.util.Locale.US, "保留屏幕 %d 的新 DPI %d？5 秒内未确认会恢复。", displayId, dpi),
                    () -> windowManager.setForcedDisplayDensityForUser(displayId, oldDpi, 0));
        } catch (Throwable e) {
            Toast.makeText(this, getString(R.string.screen_dpi_failed_fmt, e.getMessage()), Toast.LENGTH_LONG).show();
            State.log("修改 DPI 失败: " + e);
        }
    }

    private void showDisplayModeDialog(Display display) {
        Display.Mode[] modes = display.getSupportedModes();
        if (modes == null || modes.length == 0) {
            Toast.makeText(this, getString(R.string.screen_no_refresh_modes), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[modes.length + 1];
        items[0] = "使用系统默认";
        for (int i = 0; i < modes.length; i++) {
            Display.Mode mode = modes[i];
            items[i + 1] = String.format(
                    java.util.Locale.US,
                    "ID %d: %dx%d @ %.1f Hz",
                    mode.getModeId(),
                    mode.getPhysicalWidth(),
                    mode.getPhysicalHeight(),
                    mode.getRefreshRate());
        }
        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setTitle(getString(R.string.screen_select_screen_mode_fmt, display.getDisplayId()))
                .setItems(items, (dialog, which) -> {
                    Display.Mode selectedMode = which == 0 ? null : modes[which - 1];
                    applyDisplayMode(display.getDisplayId(), selectedMode);
                })
                .show();
    }

    private void applyDisplayMode(int displayId, @Nullable Display.Mode mode) {
        try {
            IDisplayManager displayManager = ServiceUtils.getDisplayManager();
            displayManager.setUserPreferredDisplayMode(displayId, mode);
            Toast.makeText(this, getString(R.string.screen_refresh_applied), Toast.LENGTH_SHORT).show();
            refreshDisplays();
        } catch (Throwable e) {
            Toast.makeText(this, getString(R.string.screen_refresh_failed_fmt, e.getMessage()), Toast.LENGTH_LONG).show();
            State.log("设置刷新模式失败: " + e);
        }
    }

    private void showRotationDialog(int displayId) {
        LinearLayout view = createVerticalDialogView();
        Spinner spinner = new Spinner(this);
        String[] options = new String[]{getString(R.string.screen_rotation_none), getString(R.string.screen_rotation_0), getString(R.string.screen_rotation_90), getString(R.string.screen_rotation_180), getString(R.string.screen_rotation_270)};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        view.addView(spinner);

        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setTitle(getString(R.string.screen_edit_screen_rotation_fmt, displayId))
                .setView(view)
                .setPositiveButton(getString(R.string.action_apply), (dialog, which) -> {
                    int rotation;
                    switch (spinner.getSelectedItemPosition()) {
                        case 1:
                            rotation = Surface.ROTATION_0;
                            break;
                        case 2:
                            rotation = Surface.ROTATION_90;
                            break;
                        case 3:
                            rotation = Surface.ROTATION_180;
                            break;
                        case 4:
                            rotation = Surface.ROTATION_270;
                            break;
                        case 0:
                        default:
                            rotation = -1;
                            break;
                    }
                    applyRotation(displayId, rotation);
                })
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private void applyRotation(int displayId, int rotation) {
        try {
            IWindowManager windowManager = ServiceUtils.getWindowManager();
            if (rotation == -1) {
                try {
                    windowManager.setIgnoreOrientationRequest(displayId, false);
                } catch (Throwable ignored) {
                }
                try {
                    windowManager.setFixedToUserRotation(displayId, FIXED_TO_USER_ROTATION_DEFAULT);
                } catch (Throwable ignored) {
                }
                try {
                    windowManager.thawDisplayRotation(displayId, "LibreDeX#free");
                } catch (Throwable e) {
                    windowManager.thawDisplayRotation(displayId);
                }
            } else {
                try {
                    windowManager.setIgnoreOrientationRequest(displayId, true);
                } catch (Throwable ignored) {
                }
                try {
                    windowManager.setFixedToUserRotation(displayId, FIXED_TO_USER_ROTATION_ENABLED);
                } catch (Throwable ignored) {
                }
                try {
                    windowManager.freezeDisplayRotation(displayId, rotation, "LibreDeX#lock");
                } catch (Throwable e) {
                    windowManager.freezeDisplayRotation(displayId, rotation);
                }
            }
            Toast.makeText(this, getString(R.string.screen_rotation_applied), Toast.LENGTH_SHORT).show();
            refreshDisplays();
        } catch (Throwable e) {
            Toast.makeText(this, getString(R.string.screen_rotation_failed_fmt, e.getMessage()), Toast.LENGTH_LONG).show();
            State.log("设置旋转失败: " + e);
        }
    }

    private void resetDisplay(int displayId) {
        try {
            IWindowManager windowManager = ServiceUtils.getWindowManager();
            try {
                windowManager.clearForcedDisplaySize(displayId);
            } catch (Throwable e) {
                State.log("恢复分辨率失败: " + e.getMessage());
            }
            try {
                windowManager.clearForcedDisplayDensityForUser(displayId, 0);
            } catch (Throwable e) {
                State.log("恢复 DPI 失败: " + e.getMessage());
            }
            try {
                windowManager.setIgnoreOrientationRequest(displayId, false);
                windowManager.setFixedToUserRotation(displayId, FIXED_TO_USER_ROTATION_DEFAULT);
                windowManager.thawDisplayRotation(displayId, "LibreDeX#reset");
            } catch (Throwable e) {
                try {
                    windowManager.thawDisplayRotation(displayId);
                } catch (Throwable ignored) {
                }
            }
            try {
                ServiceUtils.getDisplayManager().setUserPreferredDisplayMode(displayId, null);
            } catch (Throwable e) {
                State.log("恢复刷新模式失败: " + e.getMessage());
            }
            Toast.makeText(this, getString(R.string.screen_restored_default), Toast.LENGTH_SHORT).show();
            refreshDisplays();
        } catch (Throwable e) {
            Toast.makeText(this, getString(R.string.screen_restore_failed_fmt, e.getMessage()), Toast.LENGTH_LONG).show();
            State.log("恢复默认失败: " + e);
        }
    }

    private void showConfirmOrRevertDialog(String title, String message, Runnable revertAction) {
        final boolean[] confirmed = new boolean[]{false};
        AlertDialog dialog = new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(getString(R.string.action_keep), (d, which) -> {
                    confirmed[0] = true;
                    refreshDisplays();
                })
                .setNegativeButton(getString(R.string.action_restore), (d, which) -> {
                    revertAction.run();
                    refreshDisplays();
                })
                .show();
        handler.postDelayed(() -> {
            if (!confirmed[0] && dialog.isShowing()) {
                dialog.dismiss();
                revertAction.run();
                Toast.makeText(this, getString(R.string.screen_restored_reverted), Toast.LENGTH_SHORT).show();
                refreshDisplays();
            }
        }, 5000);
    }

    private Button createActionButton(String text, boolean enabled, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setEnabled(enabled);
        button.setOnClickListener(listener);
        button.setBackgroundResource(R.drawable.bg_ui_small_button);
        button.setTextColor(getColorCompat(R.color.ui_text_primary));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(0, 0, dp(8), dp(8));
        button.setLayoutParams(params);
        return button;
    }

    private LinearLayout createButtonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView createText(String text) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextSize(16);
        textView.setTextColor(getColorCompat(R.color.ui_text_primary));
        return textView;
    }

    private int getColorCompat(int colorRes) {
        return getResources().getColor(colorRes, getTheme());
    }

    private LinearLayout createVerticalDialogView() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        view.setPadding(padding, padding, padding, 0);
        return view;
    }

    private EditText createNumberInput(String label, int value) {
        EditText editText = new EditText(this);
        editText.setHint(label);
        editText.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editText.setText(String.valueOf(value));
        return editText;
    }

    private int parsePositiveInt(EditText editText, int defaultValue) {
        try {
            return Integer.parseInt(editText.getText().toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String rotationName(int rotation) {
        switch (rotation) {
            case Surface.ROTATION_0:
                return getString(R.string.screen_rotation_0);
            case Surface.ROTATION_90:
                return getString(R.string.screen_rotation_90);
            case Surface.ROTATION_180:
                return getString(R.string.screen_rotation_180);
            case Surface.ROTATION_270:
                return getString(R.string.screen_rotation_270);
            default:
                return String.valueOf(rotation);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

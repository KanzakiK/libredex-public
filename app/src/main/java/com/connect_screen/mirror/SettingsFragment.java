package com.connect_screen.mirror;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.connect_screen.mirror.job.AcquireShizuku;
import com.connect_screen.mirror.job.FetchLogAndShare;
import com.connect_screen.mirror.shizuku.ShizukuUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import rikka.shizuku.Shizuku;

public class SettingsFragment extends Fragment {
    private TextView shizukuStatus;
    private TextView overlayStatus;
    private TextView storageStatus;
    private TextView userServiceStatus;
    private TextView rootShizukuStatus;
    private Button shizukuGrantButton;
    private Button restartUserServiceButton;
    private Button rootShizukuButton;
    private Button themeSystemButton;
    private Button themeLightButton;
    private Button themeDarkButton;
    private boolean pendingLogExport;

    private final ActivityResultLauncher<Intent> storagePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                boolean granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        && Environment.isExternalStorageManager();
                if (granted && pendingLogExport) {
                    startLogExport();
                } else if (pendingLogExport) {
                    Toast.makeText(requireContext(), getString(R.string.settings_export_need_file_perm),
                            Toast.LENGTH_SHORT).show();
                }
                pendingLogExport = false;
                refreshStatus();
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        shizukuStatus = view.findViewById(R.id.shizukuStatus);
        overlayStatus = view.findViewById(R.id.overlayStatus);
        storageStatus = view.findViewById(R.id.storageStatus);
        userServiceStatus = view.findViewById(R.id.userServiceStatus);
        rootShizukuStatus = view.findViewById(R.id.rootShizukuStatus);
        shizukuGrantButton = view.findViewById(R.id.shizukuGrantButton);
        restartUserServiceButton = view.findViewById(R.id.restartUserServiceButton);
        rootShizukuButton = view.findViewById(R.id.rootShizukuButton);
        themeSystemButton = view.findViewById(R.id.themeSystemButton);
        themeLightButton = view.findViewById(R.id.themeLightButton);
        themeDarkButton = view.findViewById(R.id.themeDarkButton);

        tintButton(shizukuGrantButton, R.color.ui_accent_soft, R.color.ui_accent);

        shizukuGrantButton.setOnClickListener(v ->
                Shizuku.requestPermission(AcquireShizuku.SHIZUKU_PERMISSION_REQUEST_CODE));
        restartUserServiceButton.setOnClickListener(v -> restartUserService());
        rootShizukuButton.setOnClickListener(v -> restartRootShizuku());

        themeSystemButton.setOnClickListener(v -> setThemeMode("system"));
        themeLightButton.setOnClickListener(v -> setThemeMode("light"));
        themeDarkButton.setOnClickListener(v -> setThemeMode("dark"));

        view.findViewById(R.id.wizardEntry).setOnClickListener(v ->
                InitializationGuideDialog.show(requireActivity()));
        view.findViewById(R.id.logsEntry).setOnClickListener(v -> DebugLogDialog.show(requireContext()));
        view.findViewById(R.id.exportLogsEntry).setOnClickListener(v -> requestLogExport());
        view.findViewById(R.id.aboutEntry).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AboutActivity.class)));

        refreshStatus();
        return view;
    }

    private android.os.Handler statusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable statusPoller = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            statusHandler.postDelayed(this, 3000L);
        }
    };
    private boolean statusPolling;

    @Override
    public void onResume() {
        super.onResume();
        refreshStatus();
        startStatusPolling();
    }

    @Override
    public void onPause() {
        stopStatusPolling();
        super.onPause();
    }

    private void startStatusPolling() {
        if (statusPolling) {
            return;
        }
        statusPolling = true;
        statusHandler.postDelayed(statusPoller, 3000L);
    }

    private void stopStatusPolling() {
        statusPolling = false;
        statusHandler.removeCallbacks(statusPoller);
    }

    private void refreshStatus() {
        if (shizukuStatus == null) {
            return;
        }
        boolean shizuku = ShizukuUtils.hasPermission();
        boolean overlay = Settings.canDrawOverlays(requireContext());
        boolean storage = isStorageAccessReady();
        if (userServiceStatus != null) {
            userServiceStatus.setText(State.isUserServiceAlive() ? "online" : "offline");
        }
        updateRootShizukuStatus();
        shizukuStatus.setText(shizuku ? "已授权 · UserService 在线" : getString(R.string.settings_not_granted));
        overlayStatus.setText(overlay ? "已授权" : getString(R.string.settings_not_granted));
        storageStatus.setText(storage ? "已授予" : getString(R.string.settings_file_not_granted));
        shizukuGrantButton.setVisibility(shizuku ? View.GONE : View.VISIBLE);
        rootShizukuButton.setVisibility(shizuku ? View.VISIBLE : View.GONE);

        String mode = Pref.getThemeMode();
        setSegButton(themeSystemButton, "system".equals(mode));
        setSegButton(themeLightButton, "light".equals(mode));
        setSegButton(themeDarkButton, "dark".equals(mode));
    }

    private void requestLogExport() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !Environment.isExternalStorageManager()) {
            pendingLogExport = true;
            new MaterialAlertDialogBuilder(requireContext(),
                    R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                    .setTitle(getString(R.string.settings_export_dialog_title))
                    .setMessage(getString(R.string.settings_export_dialog_msg))
                    .setPositiveButton(getString(R.string.action_grant_perm), (d, which) ->
                            storagePermissionLauncher.launch(
                                    new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)))
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .show();
            return;
        }
        startLogExport();
    }

    private void startLogExport() {
        State.startNewJob(new FetchLogAndShare(requireContext()));
    }

    private void restartUserService() {
        if (!ShizukuUtils.hasPermission()) {
            Toast.makeText(requireContext(), getString(R.string.settings_need_shizuku), Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(requireContext(), getString(R.string.settings_restarting_userservice), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                if (State.userService != null) {
                    State.userService.exit();
                }
            } catch (Throwable ignored) {
            }
            State.unbindUserService();
            State.userService = null;
            State.ensureUserServiceBound();
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(), getString(R.string.settings_userservice_restarted), Toast.LENGTH_SHORT).show();
                    refreshStatus();
                });
            }
        }).start();
    }

    private boolean isStorageAccessReady() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    private void updateRootShizukuStatus() {
        if (rootShizukuStatus == null) {
            return;
        }
        if (!ShizukuUtils.hasPermission() || !State.isUserServiceAlive()) {
            rootShizukuStatus.setText(getString(R.string.settings_root_status));
            return;
        }
        // isRooted() 是跨进程 Binder 调用，放后台线程，避免阻塞主线程（轮询时尤其不能卡）。
        final TextView statusView = rootShizukuStatus;
        new Thread(() -> {
            String text = getString(R.string.settings_root_status);
            try {
                text = State.userService.isRooted() ? "Root: root" : "Root: shell";
            } catch (Throwable ignored) {
            }
            final String t = text;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> statusView.setText(t));
            } else {
                statusView.setText(t);
            }
        }, "settings-root-status").start();
    }

    private void restartRootShizuku() {
        if (!ShizukuUtils.hasPermission()) {
            Toast.makeText(requireContext(), getString(R.string.settings_need_shizuku), Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(requireContext(), getString(R.string.settings_restarting_shizuku_root), Toast.LENGTH_SHORT).show();
        rootShizukuButton.setEnabled(false);
        new Thread(() -> {
            boolean ok = AcquireShizuku.fixRootShizuku();
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    rootShizukuButton.setEnabled(true);
                    Toast.makeText(requireContext(),
                            ok ? "Shizuku 已以 root 重启"
                                    : "以 root 重启 Shizuku 失败",
                            Toast.LENGTH_LONG).show();
                    refreshStatus();
                });
            }
        }).start();
    }

    private void setThemeMode(String mode) {
        Pref.setThemeMode(mode);
        AppCompatDelegate.setDefaultNightMode("dark".equals(mode)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : "light".equals(mode)
                ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        refreshStatus();
        // setDefaultNightMode alone only affects the next activity creation; the
        // already-live settings UI needs a recreate for the theme to apply in real time.
        try {
            requireActivity().recreate();
        } catch (Throwable ignored) {
        }
    }

    private void setSegButton(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.setBackgroundResource(active
                ? R.drawable.bg_libredex_seg_button_active
                : R.drawable.bg_libredex_seg_button);
        ViewCompat.setBackgroundTintList(button, ColorStateList.valueOf(
                active ? ContextCompat.getColor(requireContext(), R.color.ui_accent_soft)
                        : android.graphics.Color.TRANSPARENT));
        button.setTextColor(ContextCompat.getColor(requireContext(),
                active ? R.color.ui_accent : R.color.ui_text_secondary));
    }

    private void tintButton(Button button, int tintRes, int textRes) {
        if (button == null) {
            return;
        }
        ViewCompat.setBackgroundTintList(button,
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), tintRes)));
        button.setTextColor(ContextCompat.getColor(requireContext(), textRes));
    }

    private void showToast(String message) {
        if (getContext() != null) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        }
    }
}

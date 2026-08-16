package com.connect_screen.mirror;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
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
    private Button shizukuGrantButton;
    private Button overlayGrantButton;
    private Button storageGrantButton;
    private Button restartUserServiceButton;
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
                    Toast.makeText(requireContext(), "未授予文件访问权限，无法导出日志",
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
        shizukuGrantButton = view.findViewById(R.id.shizukuGrantButton);
        overlayGrantButton = view.findViewById(R.id.overlayGrantButton);
        storageGrantButton = view.findViewById(R.id.storageGrantButton);
        restartUserServiceButton = view.findViewById(R.id.restartUserServiceButton);
        themeSystemButton = view.findViewById(R.id.themeSystemButton);
        themeLightButton = view.findViewById(R.id.themeLightButton);
        themeDarkButton = view.findViewById(R.id.themeDarkButton);

        tintButton(shizukuGrantButton, R.color.ui_accent_soft, R.color.ui_accent);
        tintButton(overlayGrantButton, R.color.ui_accent_soft, R.color.ui_accent);
        tintButton(storageGrantButton, R.color.ui_accent_soft, R.color.ui_accent);

        shizukuGrantButton.setOnClickListener(v ->
                Shizuku.requestPermission(AcquireShizuku.SHIZUKU_PERMISSION_REQUEST_CODE));
        overlayGrantButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
        });
        storageGrantButton.setOnClickListener(v -> {
            pendingLogExport = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                storagePermissionLauncher.launch(
                        new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        });
        restartUserServiceButton.setOnClickListener(v -> restartUserService());

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

    @Override
    public void onResume() {
        super.onResume();
        refreshStatus();
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
        shizukuStatus.setText(shizuku ? "已授权 · UserService 在线" : "未授权");
        overlayStatus.setText(overlay ? "已授权" : "未授权");
        storageStatus.setText(storage ? "已授予" : "未授予");
        shizukuGrantButton.setVisibility(shizuku ? View.GONE : View.VISIBLE);
        overlayGrantButton.setVisibility(overlay ? View.GONE : View.VISIBLE);
        storageGrantButton.setVisibility(storage ? View.GONE : View.VISIBLE);

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
                    .setTitle("导出日志需要文件访问权限")
                    .setMessage("导出日志会把日志文件写入下载目录，需要授予所有文件访问权限。")
                    .setPositiveButton("去授权", (d, which) ->
                            storagePermissionLauncher.launch(
                                    new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)))
                    .setNegativeButton("取消", null)
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
            Toast.makeText(requireContext(), "需要 Shizuku 权限", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(requireContext(), "正在重启 UserService…", Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(requireContext(), "UserService 已重启", Toast.LENGTH_SHORT).show();
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

    private void setThemeMode(String mode) {
        Pref.setThemeMode(mode);
        AppCompatDelegate.setDefaultNightMode("dark".equals(mode)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : "light".equals(mode)
                ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        refreshStatus();
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

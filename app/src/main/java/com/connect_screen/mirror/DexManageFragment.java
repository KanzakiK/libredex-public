package com.connect_screen.mirror;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.connect_screen.mirror.job.CreateVirtualDisplay;
import com.connect_screen.mirror.job.ExitAll;
import com.connect_screen.mirror.job.OutputSource;
import com.connect_screen.mirror.job.ProjectViaDp;
import com.connect_screen.mirror.job.SunshineServer;
import com.connect_screen.mirror.transport.TransportRegistry;

import java.io.File;

public class DexManageFragment extends Fragment {
    private final ActivityResultLauncher<Intent> wallpaperPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        DexWallpaper.applyPickedWallpaper(requireContext(), uri, dexDisplayId());
                        new Handler(Looper.getMainLooper()).postDelayed(this::refreshWallpaperPreview, 1500);
                    }
                }
            });

    private View mirrorBanner;
    private View dexContent;
    private View mirrorActionCard;
    private View dexExperimentalTitle;
    private View dexExperimentalCard;
    private TextView dexSessionBadge;
    private TextView dexDisplayValue;
    private TextView dexLauncherValue;
    private Button dexTouchpadButton;
    private Button dexRestartButton;
    private Button dexReleaseButton;
    private Button dexChangeWallpaperButton;
    private SwitchCompat dexLayerFixSwitch;
    private SwitchCompat dexAutoRotateSwitch;
    private SwitchCompat dexAutoScaleSwitch;
    private SwitchCompat dexAutoMatchSwitch;
    private SwitchCompat dexAutoScreenOffSwitch;
    private SwitchCompat dexBlackImageSwitch;
    private SwitchCompat dexPreventLockSwitch;
    private SwitchCompat dexFakeScreenSwitch;
    private SwitchCompat dexPreventSleepSwitch;
    private ImageView wallpaperPreview;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_dex_manage, container, false);

        mirrorBanner = view.findViewById(R.id.mirrorBanner);
        dexContent = view.findViewById(R.id.dexContent);
        mirrorActionCard = view.findViewById(R.id.mirrorActionCard);
        dexExperimentalTitle = view.findViewById(R.id.dexExperimentalTitle);
        dexExperimentalCard = view.findViewById(R.id.dexExperimentalCard);
        dexSessionBadge = view.findViewById(R.id.dexSessionBadge);
        dexDisplayValue = view.findViewById(R.id.dexDisplayValue);
        dexLauncherValue = view.findViewById(R.id.dexLauncherValue);
        dexTouchpadButton = view.findViewById(R.id.dexTouchpadButton);
        dexRestartButton = view.findViewById(R.id.dexRestartButton);
        dexReleaseButton = view.findViewById(R.id.dexReleaseButton);
        dexChangeWallpaperButton = view.findViewById(R.id.dexChangeWallpaperButton);
        dexLayerFixSwitch = view.findViewById(R.id.dexLayerFixSwitch);
        dexAutoRotateSwitch = view.findViewById(R.id.dexAutoRotateSwitch);
        dexAutoScaleSwitch = view.findViewById(R.id.dexAutoScaleSwitch);
        dexAutoMatchSwitch = view.findViewById(R.id.dexAutoMatchSwitch);
        dexAutoScreenOffSwitch = view.findViewById(R.id.dexAutoScreenOffSwitch);
        dexBlackImageSwitch = view.findViewById(R.id.dexBlackImageSwitch);
        dexPreventLockSwitch = view.findViewById(R.id.dexPreventLockSwitch);
        dexFakeScreenSwitch = view.findViewById(R.id.dexFakeScreenSwitch);
        dexPreventSleepSwitch = view.findViewById(R.id.dexPreventSleepSwitch);
        wallpaperPreview = view.findViewById(R.id.wallpaperPreview);

        tintButton(dexTouchpadButton, R.color.ui_accent_soft, R.color.ui_accent);
        tintButton(dexChangeWallpaperButton, R.color.ui_accent_soft, R.color.ui_accent);
        tintButton(view.findViewById(R.id.dexManualScreenOffButton), R.color.ui_accent_soft, R.color.ui_accent);
        tintButton(dexRestartButton, R.color.ui_surface, R.color.ui_text_primary);
        tintButton(dexReleaseButton, R.color.ui_danger, R.color.ui_on_accent);
        tintButton(view.findViewById(R.id.dexStopMirrorButton), R.color.ui_danger, R.color.ui_on_accent);

        dexLayerFixSwitch.setChecked(Pref.getDexLayerFixEnabled());
        dexLayerFixSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> setLayerFixEnabled(isChecked));
        dexAutoRotateSwitch.setChecked(Pref.getAutoRotate());
        dexAutoRotateSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                Pref.getPreferences().edit().putBoolean(Pref.KEY_AUTO_ROTATE, isChecked).apply());
        dexAutoScaleSwitch.setChecked(Pref.getAutoScale());
        dexAutoScaleSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                Pref.getPreferences().edit().putBoolean(Pref.KEY_AUTO_SCALE, isChecked).apply());
        dexAutoMatchSwitch.setChecked(Pref.getAutoMatchAspectRatio());
        dexAutoMatchSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                Pref.getPreferences().edit().putBoolean(Pref.KEY_AUTO_MATCH_ASPECT_RATIO, isChecked).apply());
        dexAutoScreenOffSwitch.setChecked(Pref.getAutoScreenOff());
        dexAutoScreenOffSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                Pref.getPreferences().edit().putBoolean(Pref.KEY_AUTO_SCREEN_OFF, isChecked).apply());
        dexBlackImageSwitch.setChecked(Pref.getUseBlackImage());
        dexBlackImageSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                Pref.getPreferences().edit().putBoolean(Pref.KEY_USE_BLACK_IMAGE, isChecked).apply());
        dexPreventLockSwitch.setChecked(Pref.getPreventAutoLock());
        dexPreventLockSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                Pref.getPreferences().edit().putBoolean(Pref.KEY_PREVENT_AUTO_LOCK, isChecked).apply());
        dexFakeScreenSwitch.setChecked(Pref.getFakeScreen());
        dexFakeScreenSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Pref.setFakeScreen(isChecked);
            applyFakeScreenProp(isChecked);
            refreshStatus();
        });
        dexPreventSleepSwitch.setChecked(Pref.getPreventSleep());
        dexPreventSleepSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Pref.setPreventSleep(isChecked);
            applyPreventSleepProp(isChecked);
        });

        dexTouchpadButton.setOnClickListener(v -> DexTouchpadLauncher.launch(requireContext()));
        dexRestartButton.setOnClickListener(v -> restartSession());
        dexReleaseButton.setOnClickListener(v -> ExitAll.stopServices(requireContext()));
        dexChangeWallpaperButton.setOnClickListener(v -> openWallpaperPicker());
        view.findViewById(R.id.dexManualScreenOffButton).setOnClickListener(v ->
                CreateVirtualDisplay.doPowerOffScreen(requireContext()));
        view.findViewById(R.id.dexScreenSettingsEntry).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ScreenSettingsActivity.class)));
        view.findViewById(R.id.dexStopMirrorButton).setOnClickListener(v ->
                ExitAll.stopServices(requireContext()));

        refreshStatus();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        if (dexSessionBadge == null) {
            return;
        }
        boolean mirror = OutputSource.isMirrorActive();
        boolean connected = SunshineServer.isMoonlightSessionActive()
                || ProjectViaDp.isActive()
                || TransportRegistry.isOptionalActive();
        mirrorBanner.setVisibility(mirror ? View.VISIBLE : View.GONE);
        dexContent.setVisibility(mirror ? View.GONE : View.VISIBLE);
        mirrorActionCard.setVisibility(mirror ? View.VISIBLE : View.GONE);
        // 屏幕设置（实验）入口不是 DeX 专属：镜像输出（DP/moonlight）时
        // 也需要用它设置外接屏分辨率/DPI/刷新率/旋转，所以镜像模式下
        // 保留入口卡片和"实验"标题（保持布局完整）。
        dexExperimentalTitle.setVisibility(View.VISIBLE);
        dexExperimentalCard.setVisibility(View.VISIBLE);

        dexSessionBadge.setText(connected ? "运行中" : "待机");
        dexSessionBadge.setBackgroundResource(connected
                ? R.drawable.bg_libredex_badge_accent
                : R.drawable.bg_libredex_badge);
        dexSessionBadge.setTextColor(getResources().getColor(connected
                ? R.color.ui_accent
                : R.color.ui_text_secondary));

        int dexDisplay = dexDisplayId();
        dexDisplayValue.setText((dexDisplay >= 0 ? String.valueOf(dexDisplay) : "--")
                + " · 1920×1080 · 60Hz");
        dexLauncherValue.setText(connected ? "已运行" : "待启动");

        setEnabled(dexTouchpadButton, connected);
        setEnabled(dexRestartButton, connected);
        setEnabled(dexReleaseButton, connected);
        setEnabled(dexChangeWallpaperButton, connected);

        dexLayerFixSwitch.setChecked(Pref.getDexLayerFixEnabled());
        dexAutoRotateSwitch.setChecked(Pref.getAutoRotate());
        dexAutoScaleSwitch.setChecked(Pref.getAutoScale());
        dexAutoMatchSwitch.setChecked(Pref.getAutoMatchAspectRatio());
        dexAutoMatchSwitch.setEnabled(mirror);
        dexAutoMatchSwitch.setAlpha(mirror ? 1.0f : 0.38f);
        dexAutoScreenOffSwitch.setChecked(Pref.getAutoScreenOff());
        dexBlackImageSwitch.setChecked(Pref.getUseBlackImage());
        dexPreventLockSwitch.setChecked(Pref.getPreventAutoLock());
        dexFakeScreenSwitch.setChecked(Pref.getFakeScreen());
        dexPreventSleepSwitch.setChecked(Pref.getPreventSleep());
        boolean fakeScreen = Pref.getFakeScreen();
        setEnabled(dexAutoScreenOffSwitch, !fakeScreen);
        setEnabled(dexBlackImageSwitch, !fakeScreen);
        refreshWallpaperPreview();
    }

    private int dexDisplayId() {
        if (SunshineServer.activeDexDisplayId >= 0) {
            return SunshineServer.activeDexDisplayId;
        }
        if (ProjectViaDp.isActive() && State.externalDisplayId > 0) {
            return State.externalDisplayId;
        }
        if (TransportRegistry.isOptionalActive()) {
            return TransportRegistry.activeDisplayId();
        }
        return -1;
    }

    private void refreshWallpaperPreview() {
        if (wallpaperPreview == null) {
            return;
        }
        File preview = new File(requireContext().getFilesDir(), "dex_wallpaper_preview.png");
        if (preview.exists() && preview.length() > 0) {
            Bitmap bitmap = BitmapFactory.decodeFile(preview.getAbsolutePath());
            if (bitmap != null) {
                wallpaperPreview.setImageBitmap(bitmap);
                return;
            }
        }
        wallpaperPreview.setImageDrawable(null);
    }

    private void setEnabled(View view, boolean enabled) {
        if (view == null) {
            return;
        }
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1.0f : 0.38f);
    }

    private void tintButton(Button button, int tintRes, int textRes) {
        if (button == null) {
            return;
        }
        ViewCompat.setBackgroundTintList(button,
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), tintRes)));
        button.setTextColor(ContextCompat.getColor(requireContext(), textRes));
    }

    private void setLayerFixEnabled(boolean enabled) {
        String mode = enabled ? "self" : "source";
        if (Pref.getPreferences() != null) {
            Pref.getPreferences().edit().putBoolean(Pref.KEY_DEX_LAYER_FIX, enabled).apply();
        }
        if (State.isUserServiceAlive()) {
            try {
                State.userService.executeCommand("setprop persist.dex.lspmirror.mode " + mode);
            } catch (Throwable t) {
                State.log("set layer fix mode failed: " + t.getMessage());
            }
        }
    }

    private void applyFakeScreenProp(boolean enabled) {
        if (State.isUserServiceAlive()) {
            try {
                State.userService.executeShellCommand(
                        "setprop persist.dex.lspmirror.fake_screen " + (enabled ? 1 : 0));
                State.log("fake screen prop set to " + enabled);
            } catch (Throwable t) {
                State.log("fake screen prop set failed: " + t.getMessage());
            }
        }
    }

    private void applyPreventSleepProp(boolean enabled) {
        if (State.isUserServiceAlive()) {
            try {
                State.userService.executeShellCommand(
                        "setprop persist.dex.lspmirror.prevent_sleep " + (enabled ? 1 : 0));
                State.log("prevent sleep prop set to " + enabled);
            } catch (Throwable t) {
                State.log("prevent sleep prop set failed: " + t.getMessage());
            }
        }
    }

    private void restartSession() {
        if (SunshineServer.isMoonlightSessionActive()) {
            SunshineServer.restartDexSession();
        } else if (ProjectViaDp.isActive() && State.externalDisplayId > 0) {
            try {
                State.userService.restartSecondaryLauncher(
                        State.externalDisplayId,
                        State.externalDisplayWidth,
                        State.externalDisplayHeight);
            } catch (Throwable e) {
                showToast("DP 会话重启失败：" + e.getMessage());
            }
        } else if (TransportRegistry.restartActive(true, (displayId, error) -> {
                if (getContext() == null) {
                    return;
                }
                if (error != null) {
                    showToast("会话重启失败：" + error);
                } else {
                    showToast("会话已重启");
                }
                refreshStatus();
            })) {
            // optional transport restart handled above
        }
        new Handler(Looper.getMainLooper()).postDelayed(this::refreshStatus, 1200);
        showToast("会话已重启");
    }

    private void openWallpaperPicker() {
        if (dexDisplayId() < 0) {
            showToast("请先连接 DeX");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        wallpaperPicker.launch(Intent.createChooser(intent, "选择 DeX 壁纸"));
    }

    private void showToast(String message) {
        if (getContext() != null) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        }
    }
}

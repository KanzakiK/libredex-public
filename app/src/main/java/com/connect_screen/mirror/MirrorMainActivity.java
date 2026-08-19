package com.connect_screen.mirror;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.viewpager2.widget.ViewPager2;

import com.connect_screen.mirror.job.AcquireShizuku;
import com.connect_screen.mirror.job.ExitAll;
import com.connect_screen.mirror.job.StartSunshineService;
import com.connect_screen.mirror.job.SunshineServer;
import com.connect_screen.mirror.shizuku.ShizukuUtils;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.topjohnwu.superuser.Shell;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import rikka.shizuku.Shizuku;

public class MirrorMainActivity extends AppCompatActivity implements IMainActivity {

    static {
        Shell.enableVerboseLogging = BuildConfig.DEBUG;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10));
    }

    public static final int REQUEST_CODE_MEDIA_PROJECTION = 1001;
    public static final int REQUEST_RECORD_AUDIO_PERMISSION = 1002;
    public static final String TAG = "MirrorMainActivity";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private DisplayManager.DisplayListener displayListener;

    private ViewPager2 mainPager;
    private BottomNavigationView bottomNavigation;
    private ConnectionFragment homePageFragment;

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            State.resumeJob();
            if (InitializationGuideDialog.needsSetup(this)) {
                InitializationGuideDialog.show(this);
            }
        } else {
            State.log("未知权限请求代码: " + requestCode);
        }
    }

    private void onRequestShizukuPermissionsResult(int requestCode, int grantResult) {
        if (requestCode == AcquireShizuku.SHIZUKU_PERMISSION_REQUEST_CODE) {
            State.log("Shizuku 权限请求结果: "
                    + (grantResult == PackageManager.PERMISSION_GRANTED ? "已授权" : "被拒绝"));
            State.resumeJob();
            if (InitializationGuideDialog.needsSetup(this)) {
                InitializationGuideDialog.show(this);
            }
        } else {
            State.log("未知 Shizuku 请求代码: " + requestCode);
        }
    }

    private final Shizuku.OnRequestPermissionResultListener requestPermissionResultListener =
            this::onRequestShizukuPermissionsResult;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("");
                android.util.Log.i(TAG, "已启用 HiddenApiBypass");
            } catch (Exception e) {
                android.util.Log.e(TAG, "HiddenApiBypass 初始化失败: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String themeMode = Pref.getThemeMode();
        AppCompatDelegate.setDefaultNightMode("dark".equals(themeMode)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : "light".equals(themeMode)
                ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        super.onCreate(savedInstanceState);
        State.setCurrentActivity(this);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        registerCurrentScreenListener();
        refreshCurrentScreenCache();

        boolean doNotAutoStartMoonlight = getIntent().getBooleanExtra("DoNotAutoStartMoonlight", false);
        if (doNotAutoStartMoonlight) {
            Pref.doNotAutoStartMoonlight = true;
        }

        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener);
        setContentView(R.layout.activity_main);
        UiCompat.applyStripedBackground(this);

        TextView versionTitle = findViewById(R.id.versionTitle);
        versionTitle.setText(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME);
        versionTitle.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));

        ImageButton themeToggleButton = findViewById(R.id.themeToggleButton);
        updateThemeIcon(themeToggleButton);
        themeToggleButton.setOnClickListener(v -> toggleTheme());

        mainPager = findViewById(R.id.mainPager);
        bottomNavigation = findViewById(R.id.bottomNavigation);
        mainPager.setAdapter(new MainPagerAdapter(this));
        mainPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                int itemId = position == 1 ? R.id.nav_dex : position == 2 ? R.id.nav_settings : R.id.nav_home;
                if (bottomNavigation.getSelectedItemId() != itemId) {
                    bottomNavigation.setSelectedItemId(itemId);
                }
                captureHomeFragment();
            }
        });
        bottomNavigation.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_dex) {
                mainPager.setCurrentItem(1, true);
                return true;
            }
            if (item.getItemId() == R.id.nav_settings) {
                mainPager.setCurrentItem(2, true);
                return true;
            }
            mainPager.setCurrentItem(0, true);
            return true;
        });

        State.uiState.observe(this, this::updateUI);
        State.streamingDebugInfo.observe(this, info -> {
            captureHomeFragment();
            if (homePageFragment != null) {
                homePageFragment.updateDebugInfo(info);
            }
        });

        State.log(SunshineService.getLifecycleState() == SunshineService.LifecycleState.STOPPED
                ? "SunshineService 未启动，请点击启动服务"
                : "SunshineService 正在运行");
        refresh();
        if (InitializationGuideDialog.needsSetup(this)) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> InitializationGuideDialog.show(this), 400);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        State.setCurrentActivity(this);
        refreshCurrentScreenCache();
        forceRefreshUi();
        if (InitializationGuideDialog.needsSetup(this)) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> InitializationGuideDialog.show(this), 200);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (displayListener != null) {
            try {
                DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) {
                    dm.unregisterDisplayListener(displayListener);
                }
            } catch (Throwable ignored) {
            }
            displayListener = null;
        }
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener);
        State.clearCurrentActivity(this);
    }

    private void registerCurrentScreenListener() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                return;
            }
            displayListener = new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    mainHandler.post(MirrorMainActivity.this::refreshCurrentScreenCache);
                }
            };
            dm.registerDisplayListener(displayListener, mainHandler);
        } catch (Throwable t) {
            State.log("register current screen listener failed: " + t.getMessage());
        }
    }

    private void refreshCurrentScreenCache() {
        int displayId = -1;
        try {
            if (getWindow() != null && getWindow().getDecorView() != null) {
                Display display = getWindow().getDecorView().getDisplay();
                if (display != null) {
                    displayId = display.getDisplayId();
                }
            }
        } catch (Throwable ignored) {
        }
        if (displayId < 0) {
            try {
                Display display = getDisplay();
                if (display != null) {
                    displayId = display.getDisplayId();
                }
            } catch (Throwable ignored) {
            }
        }
        if (State.currentScreenDisplayId != displayId) {
            State.currentScreenDisplayId = displayId;
            State.log("[CurrentScreen] activity display cached " + displayId);
        }
    }

    public void startSunshineServiceWithPreflight() {
        State.setCurrentActivity(this);
        SunshineService.LifecycleState lifecycleState = SunshineService.getLifecycleState();
        if (lifecycleState == SunshineService.LifecycleState.STOPPED) {
            State.startNewJob(new StartSunshineService());
            refresh();
            return;
        }
        if (lifecycleState == SunshineService.LifecycleState.STARTING
                || lifecycleState == SunshineService.LifecycleState.STOPPING) {
            State.log("SunshineService 正在切换状态，重试停止以强制结束");
            SunshineService.markStopping();
            refresh();
            ExitAll.stopServices(this);
            refresh();
            return;
        }
        State.log("手动停止 SunshineService");
        SunshineService.markStopping();
        refresh();
        ExitAll.stopServices(this);
        refresh();
    }

    private void updateThemeIcon(ImageButton button) {
        boolean dark = "dark".equals(Pref.getThemeMode());
        button.setImageResource(dark ? R.drawable.ic_theme_sun : R.drawable.ic_theme_moon);
    }

    private void toggleTheme() {
        boolean dark = "dark".equals(Pref.getThemeMode());
        Pref.setThemeMode(dark ? "light" : "dark");
        AppCompatDelegate.setDefaultNightMode(dark
                ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_YES);
        // setDefaultNightMode only affects the next activity creation; recreate
        // so the theme switch takes effect immediately.
        recreate();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                State.log("用户授予了投屏权限");
                if (SunshineService.instance == null) {
                    Intent sunshineServiceIntent = new Intent(this, SunshineService.class);
                    sunshineServiceIntent.putExtra("data", data);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(sunshineServiceIntent);
                    } else {
                        startService(sunshineServiceIntent);
                    }
                    State.log("启动 SunshineService 服务");
                    refresh();
                } else {
                    MediaProjectionManager mediaProjectionManager =
                            (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                    State.setMediaProjection(mediaProjectionManager.getMediaProjection(RESULT_OK, data));
                    State.getMediaProjection().registerCallback(new MediaProjection.Callback() {
                        @Override
                        public void onStop() {
                            super.onStop();
                            State.log("MediaProjection onStop 回调");
                        }
                    }, null);
                    State.resumeJob();
                }
            } else {
                State.log("用户拒绝了投屏权限");
                SunshineService.markStopped();
                refresh();
                State.resumeJob();
            }
        }
    }

    @Override
    public void updateLogs() {
        captureHomeFragment();
        if (homePageFragment != null) {
            homePageFragment.updateLogs();
        }
    }

    public void startMediaProjectionService() {
        if (SunshineService.getLifecycleState() == SunshineService.LifecycleState.STOPPING) {
            State.log("SunshineService 正在停止，请稍候再启动");
            refresh();
            return;
        }
        MediaProjectionManager mediaProjectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mediaProjectionManager == null) {
            throw new RuntimeException("无法获取 MediaProjectionManager 服务");
        }

        Intent captureIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            captureIntent = mediaProjectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            captureIntent = mediaProjectionManager.createScreenCaptureIntent();
        }
        startActivityForResult(captureIntent, REQUEST_CODE_MEDIA_PROJECTION);
    }

    public void refresh() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread(this::refresh);
            return;
        }

        MirrorUiState newUiState = new MirrorUiState();
        newUiState.screenOffBtnText = "息屏";

        SunshineService.LifecycleState lifecycleState = SunshineService.getLifecycleState();
        boolean connected = SunshineServer.isMoonlightSessionActive()
                || State.mirrorVirtualDisplay != null
                || State.lastSingleAppDisplay != 0;

        if (lifecycleState == SunshineService.LifecycleState.STOPPED) {
            newUiState.mirrorStatusText = "Sunshine 服务未启动";
            newUiState.screenOffBtnVisibility = false;
            newUiState.screenOffBtnEnabled = false;
        } else if (lifecycleState == SunshineService.LifecycleState.STARTING) {
            newUiState.mirrorStatusText = "Sunshine 服务启动中";
            newUiState.screenOffBtnVisibility = false;
            newUiState.screenOffBtnEnabled = false;
        } else if (lifecycleState == SunshineService.LifecycleState.STOPPING) {
            newUiState.mirrorStatusText = "Sunshine 服务关闭中";
            newUiState.screenOffBtnVisibility = false;
            newUiState.screenOffBtnEnabled = false;
        } else if (connected) {
            newUiState.mirrorStatusText = "已连接到客户端";
            newUiState.screenOffBtnVisibility = true;
            newUiState.screenOffBtnEnabled = Pref.getUseBlackImage() || ShizukuUtils.hasPermission();
        } else {
            newUiState.mirrorStatusText = "Sunshine 服务已启动，等待连接中";
            newUiState.screenOffBtnVisibility = false;
            newUiState.screenOffBtnEnabled = false;
        }
        State.uiState.setValue(newUiState);
    }

    public void forceRefreshUi() {
        refresh();
        captureHomeFragment();
        if (homePageFragment != null) {
            homePageFragment.updateUiState(State.uiState.getValue());
            homePageFragment.updateDebugInfo(State.streamingDebugInfo.getValue());
            homePageFragment.syncTransportWithActiveSession();
        }
    }

    private void updateUI(MirrorUiState state) {
        captureHomeFragment();
        if (homePageFragment != null) {
            homePageFragment.updateUiState(state);
            homePageFragment.syncTransportWithActiveSession();
        }
    }

    private void captureHomeFragment() {
        androidx.fragment.app.Fragment fragment =
                getSupportFragmentManager().findFragmentByTag("f" + 0);
        if (fragment instanceof ConnectionFragment) {
            homePageFragment = (ConnectionFragment) fragment;
        }
    }
}

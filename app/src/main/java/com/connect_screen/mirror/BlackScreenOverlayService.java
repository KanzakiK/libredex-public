package com.connect_screen.mirror;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.connect_screen.mirror.job.CurrentScreen;

public class BlackScreenOverlayService extends Service {
    private static final String EXTRA_DISPLAY_ID = "display_id";

    private WindowManager windowManager;
    private View blackView;

    public static void show(Context context) {
        if (context == null) {
            return;
        }
        show(context, CurrentScreen.detect(context).displayId);
    }

    public static void show(Context context, int displayId) {
        if (context == null) {
            return;
        }
        if (!Settings.canDrawOverlays(context)) {
            State.showErrorStatus("需要悬浮窗权限才能使用黑色画面模拟息屏");
            return;
        }
        Intent intent = new Intent(context, BlackScreenOverlayService.class);
        intent.putExtra(EXTRA_DISPLAY_ID, displayId);
        context.startService(intent);
    }

    public static void hide(Context context) {
        if (context == null) {
            return;
        }
        context.stopService(new Intent(context, BlackScreenOverlayService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int displayId = intent != null ? intent.getIntExtra(EXTRA_DISPLAY_ID, -1) : -1;
        showOverlay(displayId);
        return START_STICKY;
    }

    private void showOverlay(int displayId) {
        if (blackView != null) {
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            State.showErrorStatus("需要悬浮窗权限才能使用黑色画面模拟息屏");
            stopSelf();
            return;
        }

        WindowManager baseWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (baseWindowManager == null) {
            State.log("[BlackScreenOverlay] WindowManager unavailable");
            stopSelf();
            return;
        }
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display targetDisplay = null;
        if (displayManager != null && displayId >= 0) {
            targetDisplay = displayManager.getDisplay(displayId);
        }
        if (targetDisplay == null) {
            targetDisplay = baseWindowManager.getDefaultDisplay();
        }
        if (targetDisplay == null) {
            State.log("[BlackScreenOverlay] no target display");
            stopSelf();
            return;
        }
        Context displayContext = createDisplayContext(targetDisplay);
        windowManager = (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            State.log("[BlackScreenOverlay] display WindowManager unavailable");
            stopSelf();
            return;
        }

        blackView = new View(displayContext);
        blackView.setBackgroundColor(Color.BLACK);
        blackView.setOnClickListener(v -> stopSelf());

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        try {
            windowManager.addView(blackView, params);
            State.log("[BlackScreenOverlay] shown on display " + targetDisplay.getDisplayId());
        } catch (Throwable e) {
            blackView = null;
            State.showErrorStatus("黑色画面模拟息屏启动失败: " + e.getMessage());
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (blackView != null && windowManager != null) {
            try {
                windowManager.removeView(blackView);
                State.log("[BlackScreenOverlay] hidden");
            } catch (Throwable e) {
                State.log("[BlackScreenOverlay] remove failed: " + e.getMessage());
            }
        }
        blackView = null;
        windowManager = null;
        super.onDestroy();
    }
}

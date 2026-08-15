package com.connect_screen.mirror.job;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.connect_screen.mirror.BuildConfig;
import com.connect_screen.mirror.PureBlackActivity;
import com.connect_screen.mirror.State;
import com.connect_screen.mirror.SunshineService;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

public class ExitAll {
    public static void execute(Context context, boolean restart) {
        boolean wasSunshineStarted = stopServices(context);

        // 重启应用
        if (restart) {
            PackageManager packageManager = context.getPackageManager();
            Intent intent = packageManager.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID);
            ComponentName componentName = intent.getComponent();
            Intent mainIntent = Intent.makeRestartActivityTask(componentName);
            // Required for API 34 and later
            // Ref: https://developer.android.com/about/versions/14/behavior-changes-14#safer-intents
            mainIntent.setPackage(context.getPackageName());
            mainIntent.putExtra("DoNotAutoStartMoonlight", true);
            context.startActivity(mainIntent);
        }

        // 退出应用进程
        System.exit(0);
        try {
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch(Throwable e) {
            // ignore
        }
    }

    public static boolean stopServices(Context context) {
        SunshineService.markStopping();
        ScreenSession.setActive(false);
        State.cancelCurrentJob("SunshineService stopping");
        if (SunshineService.instance != null) {
            SunshineService.instance.releaseWakeLock();
        }
        CreateVirtualDisplay.restoreAspectRatio();
        SunshineAudio.restoreVolume(context);
        SunshineServer.stopVirtualDisplay();
        boolean wasSunshineStarted = SunshineServer.exitServer();
        State.unbindUserService();
        if (State.mediaProjectionInUse != null) {
            State.mediaProjectionInUse.stop();
            State.mediaProjectionInUse = null;
        }
        State.setMediaProjection(null);

        if (State.mirrorVirtualDisplay != null) {
            State.mirrorVirtualDisplay.release();
            State.mirrorVirtualDisplay = null;
        }
        State.lastSingleAppDisplay = 0;
        State.externalDisplayId = -1;
        State.externalControlDisplayId = -1;
        ProjectViaDp.stop();

        if (context != null) {
            context.stopService(new Intent(context, SunshineService.class));
            scheduleStopRetry(context.getApplicationContext(), 1);
        }
        if (SunshineService.instance == null && !wasSunshineStarted) {
            SunshineService.markStopped();
        }
        State.refreshMainActivity();
        return wasSunshineStarted;
    }

    private static void scheduleStopRetry(Context context, int attempt) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            SunshineService.LifecycleState state = SunshineService.getLifecycleState();
            if (state == SunshineService.LifecycleState.STOPPED) {
                return;
            }
            if (SunshineService.instance == null && !SunshineService.isNativeThreadRunning()) {
                SunshineService.markStopped();
                State.refreshMainActivity();
                return;
            }
            State.log("SunshineService stop retry " + attempt + ": native service is still stopping");
            SunshineService.markStopping();
            State.cancelCurrentJob("SunshineService stop retry");
            SunshineServer.stopVirtualDisplay();
            SunshineServer.exitServer();
            SunshineAudio.restoreVolume(context);
            if (State.mediaProjectionInUse != null) {
                State.mediaProjectionInUse.stop();
                State.mediaProjectionInUse = null;
            }
            State.setMediaProjection(null);
            context.stopService(new Intent(context, SunshineService.class));
            State.refreshMainActivity();
            if (attempt >= 2) {
                State.log("SunshineService stop watchdog: force STOPPED state after cleanup");
                SunshineService.markStopped();
                State.refreshMainActivity();
            } else {
                scheduleStopRetry(context, attempt + 1);
            }
        }, 2500);
    }
}

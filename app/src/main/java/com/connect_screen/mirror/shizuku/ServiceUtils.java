package com.connect_screen.mirror.shizuku;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityOptionsHidden;
import android.app.PendingIntentHidden;
import android.content.ComponentName;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.IDisplayManager;
import android.hardware.input.IInputManager;
import android.media.IAudioService;
import android.os.Build;
import android.permission.IPermissionManager;
import android.view.Display;
import android.view.IWindowManager;
import android.widget.Toast;

import com.connect_screen.mirror.State;

import dev.rikka.tools.refine.Refine;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

import java.util.List;

public class ServiceUtils {
    private static IActivityManager activityManager;
    private static IActivityTaskManager activityTaskManager;
    private static IWindowManager windowManager;
    private static IDisplayManager displayManager;
    private static IInputManager inputManager;
    private static IPermissionManager permissionManager;
    private static IPackageManager packageManager;
    private static IAudioService audioManager;

    private static void initWithShizuku() {
        if (!ShizukuUtils.hasPermission()) {
            return;
        }
        try {
        activityTaskManager = IActivityTaskManager.Stub.asInterface(new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity_task")));
        } catch (Throwable e) {
            // ignore - service may be unavailable on this API level
        }
        try {
        activityManager = IActivityManager.Stub.asInterface(new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE)));
        } catch (Throwable e) {
            // ignore
        }
        try {
        windowManager = IWindowManager.Stub.asInterface(new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.WINDOW_SERVICE)));
        } catch (Throwable e) {
            // ignore
        }
        try {
        displayManager = IDisplayManager.Stub.asInterface(new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE)));
        } catch (Throwable e) {
            // ignore
        }
        try {
        inputManager = IInputManager.Stub.asInterface(new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.INPUT_SERVICE)));
        } catch (Throwable e) {
            // ignore - INPUT_SERVICE may be unavailable on API 27 via Shizuku
        }
        try {
            permissionManager = IPermissionManager.Stub.asInterface(new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("permissionmgr")));
        } catch(Throwable e) {
            // ignore;
        }
        try {
        packageManager = IPackageManager.Stub.asInterface(new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package")));
        } catch (Throwable e) {
            // ignore
        }
        try {
        audioManager = IAudioService.Stub.asInterface(new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.AUDIO_SERVICE)));
        } catch (Throwable e) {
            // ignore
        }
    }

    /**
     * 判断某个服务是否正在运行的方法
     *
     * @param mContext    上下文
     * @param serviceName 是包名+服务的类名（例如：net.loonggg.testbackstage.TestService）
     * @return true代表正在运行，false代表服务没有正在运行
     */
    public static boolean isServiceWork(Context mContext, String serviceName) {
        boolean isWork = false;
        ActivityManager myAM = (ActivityManager) mContext
                .getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningServiceInfo> myList = myAM.getRunningServices(40);
        if (myList.isEmpty()) {
            return false;
        }
        for (int i = 0; i < myList.size(); i++) {
            String mName = myList.get(i).service.getClassName();
            if (mName.equals(serviceName)) {
                isWork = true;
                break;
            }
        }
        return isWork;
    }

    public static int startActivity(Intent intent, ActivityOptions options) {
        if (activityManager == null) {
            initWithShizuku();
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                return activityManager.startActivityAsUserWithFeature(
                        null, "com.android.shell", null, intent,
                        intent.getType(), null, null, 0, 0,
                        null, options.toBundle(), 0
                );
            } else {
                return activityManager.startActivity(
                        null, "com.android.shell", intent,
                        intent.getType(), null, null, 0, 0,
                        null, options.toBundle()
                );
            }
        } catch (Exception e) {
            State.log("failed to start activity: " + e.getMessage());
            return -1;
        }
    }

    public static int callPendingIntent(PendingIntent pendingIntent, ActivityOptions options, int displayId) {
        if (activityManager == null) {
            throw new IllegalStateException("ServiceUtils not initialized, call initWithShizuku() first");
        }

        try {
            PendingIntentHidden pendingIntentHidden = Refine.unsafeCast(pendingIntent);
            ActivityOptionsHidden optionsHidden = Refine.unsafeCast(options);
            optionsHidden.setCallerDisplayId(displayId);

            return activityManager.sendIntentSender(
                    pendingIntentHidden.getTarget(), pendingIntentHidden.getWhitelistToken(), 0, null,
                    null, null, null, optionsHidden.toBundle()
            );
        } catch (Exception e) {
            State.log("failed to send pending intent: " + e.getMessage());
            return -1;
        }
    }

    public static IWindowManager getWindowManager() {
        if (windowManager == null) {
            initWithShizuku();
        }
        return windowManager;
    }

    public static IDisplayManager getDisplayManager() {
        if (displayManager == null) {
            initWithShizuku();
        }
        return displayManager;
    }

    public static IInputManager getInputManager() {
        if (inputManager == null) {
            initWithShizuku();
        }
        return inputManager;
    }

    public static IActivityTaskManager getActivityTaskManager() {
        if (activityTaskManager == null) {
            initWithShizuku();
        }
        return activityTaskManager;
    }

    public static IPermissionManager getPermissionManager() {
        if (permissionManager == null) {
            initWithShizuku();
        }
        return permissionManager;
    }

    public static IPackageManager getPackageManager() {
        if (packageManager == null) {
            initWithShizuku();
        }
        return packageManager;
    }

    public static IAudioService getAudioManager() {
        if (audioManager == null) {
            initWithShizuku();
        }
        return audioManager;
    }

}

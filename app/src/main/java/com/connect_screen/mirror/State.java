package com.connect_screen.mirror;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.connect_screen.mirror.job.Job;
import com.connect_screen.mirror.job.ProjectViaDp;
import com.connect_screen.mirror.job.ScreenSession;
import com.connect_screen.mirror.job.SunshineServer;
import com.connect_screen.mirror.job.YieldException;
import com.connect_screen.mirror.shizuku.IUserService;
import com.connect_screen.mirror.shizuku.ShizukuUtils;
import com.connect_screen.mirror.shizuku.SurfaceControl;
import com.connect_screen.mirror.shizuku.UserService;
import com.connect_screen.mirror.transport.TransportRegistry;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ShellUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import rikka.shizuku.Shizuku;

public class State {
    // 弱引用保存当前的 MainActivity 实例
    private static WeakReference<MirrorMainActivity> currentActivity = new WeakReference<>(null);
    public static final MutableLiveData<MirrorUiState> uiState = new MutableLiveData<>(new MirrorUiState());
    public static final MutableLiveData<String> streamingDebugInfo = new MutableLiveData<>("串流未启动");
    public static volatile String lastMoonlightHandshakeInfo;
    public static volatile String lastMoonlightControlInputInfo;
    public static String serverUuid;
    private static Job currentJob;
    public static List<String> logs = new ArrayList<>();
    private static MediaProjection mediaProjection;
    public static MediaProjection mediaProjectionInUse;
    public static int lastSingleAppDisplay;
    public static int externalDisplayId = -1;
    public static int externalControlDisplayId = -1;
    public static int externalDisplayWidth;
    public static int externalDisplayHeight;
    public static volatile int currentScreenDisplayId = -1;
    public static volatile boolean stoppingAllSessions;
    public static VirtualDisplay mirrorVirtualDisplay;
    public static IBinder mirrorExternalToken;
    public static Activity isInPureBlackActivity = null;
    public static volatile IUserService userService;
    public static Set<String> discoveredConnectScreenClients = new HashSet<>();

    public static MirrorMainActivity getCurrentActivity() {
        if (currentActivity == null) {
            return null;
        }
        return currentActivity.get();
    }

    public static void setCurrentActivity(MirrorMainActivity activity) {
        currentActivity = new WeakReference<>(activity);
    }

    public static void clearCurrentActivity(MirrorMainActivity activity) {
        MirrorMainActivity current = getCurrentActivity();
        if (current == activity) {
            currentActivity = new WeakReference<>(null);
        }
    }

    public static final ServiceConnection userServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder binder) {
            State.log("user service connected");
            State.userService = IUserService.Stub.asInterface(binder);
            userServiceBindInFlight = false;
            mainHandler.removeCallbacks(userServiceBindTimeout);
            if (State.currentActivity != null && State.currentActivity.get() != null) {
                MirrorMainActivity context = State.currentActivity.get();
                context.runOnUiThread(() -> {
                    State.resumeJob();
                });
            }
            SharedPreferences preferences = Pref.getPreferences();
            if (preferences != null && preferences.getInt("AUTO_GRANT_PERMISSION", 0) != BuildConfig.VERSION_CODE) {
                preferences.edit().putInt("AUTO_GRANT_PERMISSION", BuildConfig.VERSION_CODE).apply();
                State.log("授予媒体投影权限和悬浮窗权限");
                try {
                    State.userService.executeCommand("appops set " + BuildConfig.APPLICATION_ID + " PROJECT_MEDIA allow");
                    State.userService.executeCommand("appops set " + BuildConfig.APPLICATION_ID + " SYSTEM_ALERT_WINDOW allow");
                } catch (Throwable e) {
                    // ignorepp
                }
            }
            // Restore the session marker from live session state. A UserService
            // rebind during an active DP/Moonlight/optional transport session must not
            // clear the marker, or the fake-screen hook would stop intercepting
            // power presses and the system could lock (and on DP DeX can crash
            // PowerManagerService) instead.
            if (!stoppingAllSessions) {
                boolean activeSession = SunshineServer.isMoonlightSessionActive()
                        || ProjectViaDp.isActive()
                        || TransportRegistry.isOptionalActive();
                ScreenSession.setActive(activeSession);
            }
            if (!ProjectViaDp.isActive() && Pref.getDpSessionStarted()) {
                Pref.setDpSessionStarted(false);
                try {
                    State.userService.executeShellCommand(
                            "setprop persist.dex.lspmirror.dp_display_id \"\"");
                    State.userService.executeShellCommand(
                            "setprop dex.lspmirror.dp_session_active \"\"");
                    State.userService.executeShellCommand(
                            "setprop dex.lspmirror.session_active \"\"");
                    State.userService.executeShellCommand(
                            "settings delete global libredex_dp_display_id");
                } catch (Throwable ignored) {
                }
                try {
                    int displayId = State.externalDisplayId > 0
                            ? State.externalDisplayId : 0;
                    State.userService.stopSecondaryLauncher(displayId);
                } catch (Throwable ignored) {
                }
            }
            try {
                State.userService.executeShellCommand(
                        "setprop persist.dex.lspmirror.fake_screen "
                                + (Pref.getFakeScreen() ? 1 : 0));
                State.userService.executeShellCommand(
                        "setprop persist.dex.lspmirror.prevent_sleep "
                                + (Pref.getPreventSleep() ? 1 : 0));
            } catch (Throwable e) {
                State.log("sync lspmirror props failed: " + e.getMessage());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            State.log("user service disconnected");
            State.userService = null;
            userServiceBindInFlight = false;
        }

        @Override
        public void onBindingDied(ComponentName componentName) {
            State.log("user service binding died");
            State.userService = null;
            userServiceBindInFlight = false;
        }

        @Override
        public void onNullBinding(ComponentName componentName) {
            State.log("user service null binding");
            State.userService = null;
            userServiceBindInFlight = false;
        }
    };

    public static Shizuku.UserServiceArgs userServiceArgs = new Shizuku.UserServiceArgs(new ComponentName(BuildConfig.APPLICATION_ID, UserService.class.getName()))
            .daemon(true)
            .tag("temp7")
            .processNameSuffix("connect-screen")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE);

    private static final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile boolean userServiceBindInFlight;
    private static final Runnable userServiceBindTimeout = () -> userServiceBindInFlight = false;

    public static boolean isJobRunning() {
        return currentJob != null;
    }   

    public static void cancelCurrentJob(String reason) {
        if (currentJob == null) {
            return;
        }
        State.log("取消任务 " + currentJob.getClass().getSimpleName() + ": " + reason);
        currentJob = null;
    }

    public static void startNewJob(Job job) {
        if (currentJob != null) {
            if (currentActivity != null && currentActivity.get() != null) {
                State.log("当前任务 " + currentJob.getClass().getSimpleName() + " 正在进行中");
            }
            return;
        }
        currentJob = job;
        try {
            State.log("开始任务 " + job.getClass().getSimpleName());
            currentJob.start();
            State.log("任务 " + job.getClass().getSimpleName() + " 完成");
            currentJob = null;
        } catch (YieldException e) {
            State.log("任务 " + job.getClass().getSimpleName() + " 暂停, " + e.getMessage());
        } catch (RuntimeException e) {
            State.log("任务 " + job.getClass().getSimpleName() + " 启动失败");
            String stackTrace = android.util.Log.getStackTraceString(e);
            State.log("堆栈跟踪: " + stackTrace);
            currentJob = null;
        }
    }

    public static void resumeJob() {
        if (currentJob == null) {
            return;
        }
        try {
            State.log("恢复任务 " + currentJob.getClass().getSimpleName());
            currentJob.start();
            State.log("任务 " + currentJob.getClass().getSimpleName() + " 完成");
            currentJob = null;
        } catch (YieldException e) {
            State.log("任务 " + currentJob.getClass().getSimpleName() + " 暂停, " + e.getMessage());
        } catch (RuntimeException e) {
            State.log("任务 " + currentJob.getClass().getSimpleName() + " 恢复失败");
            String stackTrace = android.util.Log.getStackTraceString(e);
            State.log("堆栈跟踪: " + stackTrace);
            currentJob = null;
        }
    }

    public static void resumeJobLater(long delayMillis) {
        if (currentActivity.get() != null) {
            mainHandler.postDelayed(() -> {
                resumeJob();
            }, delayMillis);
        }
    }

    public static void log(String message) {
        logs.add(message);
        Log.i("ConnectScreen", message);
        if (currentActivity != null && currentActivity.get() != null) {
            IMainActivity  mainActivity = (IMainActivity) currentActivity.get();
            mainActivity.updateLogs();
        }
    }

    public static MediaProjection getMediaProjection() {
        return mediaProjection;
    }

    public static void setMediaProjection(MediaProjection newMediaProjection) {
        if (newMediaProjection == null) {
            Log.d("State", "MediaProjection used");
            mediaProjection = null;
        } else {
            Log.d("State", "MediaProjection acquired");
            mediaProjection = newMediaProjection;
            mediaProjectionInUse = newMediaProjection;
        }
    }

    public static int getMirrorVirtualDisplayId() {
        if (mirrorVirtualDisplay == null) {
            return -1;
        }
        return mirrorVirtualDisplay.getDisplay().getDisplayId();
    }

    public static void unbindUserService() {
        try {
            boolean removeService = true;
            Shizuku.unbindUserService(State.userServiceArgs, userServiceConnection, removeService);
            State.userService = null;
            userServiceBindInFlight = false;
            mainHandler.removeCallbacks(userServiceBindTimeout);
            State.log("user service unbound with removeService=true");
        } catch (Exception e) {
            // ignore
        }
    }

    public static void refreshMainActivity() {
        MirrorMainActivity mirrorMainActivity = currentActivity.get();
        if (mirrorMainActivity != null) {
            mirrorMainActivity.runOnUiThread(mirrorMainActivity::forceRefreshUi);
        }
    }

    public static void bringMainActivityToFront() {
        MirrorMainActivity mirrorMainActivity = currentActivity.get();
        if (mirrorMainActivity == null) {
            return;
        }
        mirrorMainActivity.runOnUiThread(() -> {
            try {
                Intent intent = new Intent(mirrorMainActivity, MirrorMainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                mirrorMainActivity.startActivity(intent);
            } catch (Throwable ignored) {
            }
        });
    }

    public static void bringMainActivityToFrontLater(long delayMillis) {
        mainHandler.postDelayed(State::bringMainActivityToFront, delayMillis);
    }

    public static void showErrorStatus(String msg) {
        State.log(msg);
        MirrorUiState newUiState = new MirrorUiState();
        newUiState.errorStatusText = msg;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            State.uiState.setValue(newUiState);
        } else {
            State.uiState.postValue(newUiState);
        }
    }

    public static Context getContext() {
        if (currentActivity != null && currentActivity.get() != null) {
            return currentActivity.get();
        }
        if (SunshineService.instance != null) {
            return SunshineService.instance;
        }
        return null;
    }

    public static void bindUserService() {
        if (isUserServiceAlive() || userServiceBindInFlight) {
            return;
        }
        userServiceBindInFlight = true;
        Shizuku.peekUserService(State.userServiceArgs, State.userServiceConnection);
        Shizuku.bindUserService(State.userServiceArgs, State.userServiceConnection);
        mainHandler.removeCallbacks(userServiceBindTimeout);
        mainHandler.postDelayed(userServiceBindTimeout, 20_000);
    }

    public static boolean isUserServiceAlive() {
        IUserService service = State.userService;
        if (service == null) {
            return false;
        }
        IBinder binder = service.asBinder();
        return binder != null && binder.isBinderAlive();
    }

    public static void ensureUserServiceBound() {
        if (!ShizukuUtils.hasPermission()) {
            return;
        }
        if (isUserServiceAlive()) {
            return;
        }
        State.bindUserService();
    }
}

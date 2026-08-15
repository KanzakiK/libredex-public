package com.connect_screen.mirror.job;

import android.content.Intent;
import android.os.Build;

import com.connect_screen.mirror.MirrorMainActivity;
import com.connect_screen.mirror.SunshineService;
import com.connect_screen.mirror.State;

public class StartSunshineService implements Job {
    @Override
    public void start() throws YieldException {
        if (SunshineService.getLifecycleState() != SunshineService.LifecycleState.STOPPED) {
            State.log("SunshineService is already starting or running");
            return;
        }

        MirrorMainActivity activity = State.getCurrentActivity();
        if (activity == null) {
            State.showErrorStatus("Cannot start SunshineService without an active UI");
            return;
        }

        SunshineService.markStarting();
        activity.refresh();
        if (State.getMediaProjection() != null) {
            Intent sunshineServiceIntent = new Intent(activity, SunshineService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(sunshineServiceIntent);
            } else {
                activity.startService(sunshineServiceIntent);
            }
            State.log("启动 SunshineService 服务（复用已有投屏权限用于原生音频捕获）");
            activity.refresh();
            return;
        }
        State.log("启动 SunshineService 前请求投屏权限，用于 Android 原生音频捕获");
        activity.startMediaProjectionService();
        throw new YieldException("等待用户授予投屏权限");
    }
}

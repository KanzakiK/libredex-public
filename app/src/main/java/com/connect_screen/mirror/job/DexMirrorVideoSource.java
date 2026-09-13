package com.connect_screen.mirror.job;

import android.os.RemoteException;
import android.view.Surface;

import com.connect_screen.mirror.State;

import dev.lizardbyte.sunshine.android.SunshineVideoSource;

/**
 * 官方 sunshine-android 视频源实现（m4）。
 *
 * 官方 SunshineHost 在编码器就绪后通过 onVideoInputSurface 回调 encoder surface，
 * 本类把 LibreDeX 的 DeX 桌面（createDexMirror）渲染到该 surface，与私有
 * SunshineServer.startDexAnywhereVideoSource 的核心链路一致。
 *
 * 约定：start() 非阻塞返回（官方在主线程调用），VD 保活由后台线程持有。
 */
public final class DexMirrorVideoSource implements SunshineVideoSource, AutoCloseable {

    private final Object lock = new Object();
    private Surface surface;
    private int width;
    private int height;
    private int frameRate;
    private int dexDisplayId = -1;
    private volatile boolean active;
    private Thread keepAliveThread;

    @Override
    public void start(Surface surface, int width, int height, int frameRate) {
        synchronized (lock) {
            stopLocked();
            this.surface = surface;
            this.width = width;
            this.height = height;
            this.frameRate = Math.max(1, frameRate);
            active = true;
        }
        // 确保 UserService 就绪（参考 SunshineServer.startDexAnywhereVideoSource）
        if (!State.isUserServiceAlive()) {
            State.ensureUserServiceBound();
            long deadline = System.currentTimeMillis() + 5000;
            while (!State.isUserServiceAlive() && System.currentTimeMillis() < deadline && active) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (!active || !State.isUserServiceAlive()) {
            return;
        }
        try {
            int vdId = State.userService.createDexMirror(
                    "dex-anywhere-dex-flag-enc", width, height, frameRate, surface);
            if (vdId < 0) {
                State.log("[DexMirrorVideoSource] createDexMirror failed: " + vdId);
                return;
            }
            synchronized (lock) {
                dexDisplayId = vdId;
            }
            // 共享状态：触控/输入注入目标显示（m5 复用）
            SunshineServer.activeDexDisplayId = vdId;
            SunshineMouse.setDexTargetDisplayId(vdId);
            SunshineKeyboard.setDexTargetDisplayId(vdId);
            // 关键：initialize 设 screenWidth/screenHeight/mapMouseToTouch/IInputManager
            // 没调的话 handleAbsMouseMovePacket 里 x/width 会出 NaN，注入事件全被 reject
            try {
                SunshineMouse.initialize(width, height);
            } catch (Throwable ignored) {
            }
            try {
                SunshineKeyboard.initialize();
            } catch (Throwable ignored) {
            }
            try {
                InputRouting.bindAllExternalInputToDisplay(vdId);
                State.log("[DexMirrorVideoSource] bound external input to display " + vdId);
            } catch (Throwable t) {
                State.log("[DexMirrorVideoSource] bind external input failed: " + t.getMessage());
            }
            State.log("[DexMirrorVideoSource] DeX mirror started id=" + vdId
                    + " " + width + "x" + height + "@" + frameRate);
            // 保活：VD 生命周期跟随本 source，直到 stop()
            Thread thread = new Thread(() -> {
                while (active) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "dex-video-source");
            thread.setDaemon(true);
            synchronized (lock) {
                keepAliveThread = thread;
            }
            thread.start();
        } catch (RemoteException e) {
            State.log("[DexMirrorVideoSource] start failed: " + e.getMessage());
        } catch (Throwable t) {
            State.log("[DexMirrorVideoSource] start error: " + t.getMessage());
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    private void stopLocked() {
        active = false;
        Thread thread = keepAliveThread;
        keepAliveThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (dexDisplayId >= 0 && State.isUserServiceAlive()) {
            try {
                State.userService.destroyExternalMirror();
            } catch (RemoteException e) {
                State.log("[DexMirrorVideoSource] destroy failed: " + e.getMessage());
            }
            dexDisplayId = -1;
        }
        if (SunshineServer.activeDexDisplayId >= 0) {
            SunshineServer.activeDexDisplayId = -1;
        }
        Surface s = surface;
        surface = null;
        if (s != null) {
            try {
                s.release();
            } catch (Throwable ignored) {
            }
        }
        State.log("[DexMirrorVideoSource] stopped");
    }

    public int dexDisplayId() {
        synchronized (lock) {
            return dexDisplayId;
        }
    }

    @Override
    public void close() {
        stop();
    }
}

package com.connect_screen.mirror.job;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.IDisplayManager;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.media.projection.IMediaProjection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionHidden;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IWindowManager;
import android.view.Surface;

import androidx.annotation.NonNull;


import com.connect_screen.mirror.BlackScreenOverlayService;
import com.connect_screen.mirror.Pref;
import com.connect_screen.mirror.PureBlackActivity;
import com.connect_screen.mirror.State;
import com.connect_screen.mirror.shizuku.ServiceUtils;
import com.connect_screen.mirror.shizuku.ShizukuUtils;
import com.connect_screen.mirror.shizuku.SurfaceControl;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import dev.rikka.tools.refine.Refine;

public class CreateVirtualDisplay {

    // Internal fields copied from android.hardware.display.DisplayManager
    private static final int VIRTUAL_DISPLAY_FLAG_PUBLIC = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
    private static final int VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 << 6;
    private static final int VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 << 7;
    private static final int VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;
    private static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 << 12;
    private static final int VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 << 14;
    private static final int VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP = 1 << 15;
    public static boolean isCreating = false;
    private static final Set<Integer> aspectRatioForcedDisplays =
            Collections.synchronizedSet(new HashSet<Integer>());
    private static volatile int screenOffDisplayId = -1;

    public static VirtualDisplay createVirtualDisplay(VirtualDisplayArgs virtualDisplayArgs, Surface surface) {
        isCreating = true;
        try {
            if (ShizukuUtils.hasPermission()) {
                try {
                    VirtualDisplay virtualDisplay = createByShizuku(virtualDisplayArgs, surface, true, null);
                    android.util.Log.i("CreateVirtualDisplay", "created virtual display: " + virtualDisplay.getDisplay().getDisplayId());
                    return virtualDisplay;
                } catch(Exception e) {
                    VirtualDisplay virtualDisplay = createByShizuku(virtualDisplayArgs, surface, true, State.getMediaProjection());
                    android.util.Log.i("CreateVirtualDisplay", "created virtual display: " + virtualDisplay.getDisplay().getDisplayId());
                    return virtualDisplay;
                }
            } else {
                new Handler(Looper.getMainLooper()).post(() -> {
                   State.log("No Shizuku permission, cannot do single-app projection");
                });
                return null;
            }
        } finally {
            isCreating = false;
        }
    }

    public static void powerOffScreen() {
        Context context = State.getContext();
        if (context == null) {
            return;
        }
        boolean autoScreenOff = Pref.getAutoScreenOff();
        if (!autoScreenOff) {
            return;
        }
        doPowerOffScreen(context);
    }

    public static void doPowerOffScreen(Context context) {
        if (Pref.getFakeScreen() && State.userService != null && !State.stoppingAllSessions) {
            ScreenSession.setActive(true);
            try {
                State.userService.pressPowerKey();
                State.log("fake screen power key pressed");
                return;
            } catch (RemoteException e) {
                State.log("fake screen power key failed: " + e.getMessage());
            }
        }
        CurrentScreen currentScreen = CurrentScreen.detect(context);
        if (Pref.getUseBlackImage()) {
            BlackScreenOverlayService.show(context, currentScreen.displayId);
            return;
        }
        boolean singleApp = Pref.getSingleAppMode();
        if (State.userService != null) {
            try {
                State.userService.startListenVolumeKey();
                boolean poweredOff;
                if (currentScreen.displayId == Display.DEFAULT_DISPLAY) {
                    poweredOff = State.userService.setScreenPower(SurfaceControl.POWER_MODE_OFF);
                } else {
                    poweredOff = State.userService.setScreenPowerForDisplay(
                            currentScreen.displayId, SurfaceControl.POWER_MODE_OFF);
                }
                if (poweredOff) {
                    screenOffDisplayId = currentScreen.displayId;
                } else {
                    if (singleApp) {
                        Intent intent = new Intent(context, PureBlackActivity.class);
                        ActivityOptions options = ActivityOptions.makeBasic();
                        context.startActivity(intent, options.toBundle());
                    }
                }
            } catch (RemoteException e2) {
                State.log("powerOffScreen failed: " + e2.getMessage());
            }
        } else if (singleApp) {
            Intent intent = new Intent(context, PureBlackActivity.class);
            ActivityOptions options = ActivityOptions.makeBasic();
            context.startActivity(intent, options.toBundle());
        } else {
            State.log("Mirror projection requires Shizuku permission to turn off screen");
        }
    }

    private static VirtualDisplay createByMediaProjection(VirtualDisplayArgs virtualDisplayArgs, Surface surface) {
        VirtualDisplay virtualDisplay = State.getMediaProjection().createVirtualDisplay(virtualDisplayArgs.virtualDisplayName,
                virtualDisplayArgs.width, virtualDisplayArgs.height, virtualDisplayArgs.dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                surface, null, null);
        State.setMediaProjection(null);
        return virtualDisplay;
    }

    private static @NonNull VirtualDisplay createByShizuku(VirtualDisplayArgs virtualDisplayArgs, Surface surface, boolean ownContentOnly, MediaProjection mediaProjection) {
        int virtualDisplayWidth = virtualDisplayArgs.width;
        IDisplayManager displayManager = ServiceUtils.getDisplayManager();
        int flags = getFlags(ownContentOnly, virtualDisplayArgs.rotatesWithContent);
        VirtualDisplayConfig config = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            config = new VirtualDisplayConfig.Builder(
                    virtualDisplayArgs.virtualDisplayName,
                    virtualDisplayWidth, virtualDisplayArgs.height, virtualDisplayArgs.dpi)
                    .setSurface(surface)
                    .setFlags(flags)
                    .setRequestedRefreshRate(virtualDisplayArgs.refreshRate)
                    .build();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            config = new VirtualDisplayConfig.Builder(
                    virtualDisplayArgs.virtualDisplayName,
                    virtualDisplayWidth, virtualDisplayArgs.height, virtualDisplayArgs.dpi)
                    .setSurface(surface)
                    .setFlags(flags)
                    .build();
        } else {
            // config = null
        }
        IVirtualDisplayCallback callback = new VirtualDisplayCallback();
        IMediaProjection projection = null;
        if (mediaProjection != null) {
            MediaProjectionHidden mediaProjectionHidden = Refine.unsafeCast(mediaProjection);
            projection = mediaProjectionHidden.getProjection();
        }
        int displayId = -1;
        String packageName = "com.android.shell";
        try {
            if (State.userService != null && State.userService.isRooted()) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    State.log("Shizuku started with root may not support single-app projection; prefer starting it with adb permission");
                });
            }
        } catch (Throwable e) {
            // ignore;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayId = displayManager.createVirtualDisplay(config, callback, projection, packageName);
        } else {
            displayId = displayManager.createVirtualDisplay(callback, projection, packageName, virtualDisplayArgs.virtualDisplayName, virtualDisplayWidth, virtualDisplayArgs.height, virtualDisplayArgs.dpi, surface, flags, virtualDisplayArgs.virtualDisplayName);
        }
        DisplayInfo displayInfo = ServiceUtils.getDisplayManager().getDisplayInfo(displayId);
        android.util.Log.i("CreateVirtualDisplay", "Virtual display created, displayId: " + displayId + ", uniqueId: " + displayInfo.uniqueId);
        VirtualDisplay virtualDisplay = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            virtualDisplay = DisplayManagerGlobal.getInstance().createVirtualDisplayWrapper(config, callback, displayId);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            virtualDisplay = DisplayManagerGlobal.getInstance().createVirtualDisplayWrapper(config, null, callback, displayId);
        } else {
            try {
                DisplayManagerGlobal displayManagerGlobal = DisplayManagerGlobal.getInstance();
                Class<?> virtualDisplayClass = VirtualDisplay.class;
                Constructor<?> constructor = virtualDisplayClass.getDeclaredConstructor(
                        DisplayManagerGlobal.class,
                        Display.class,
                        IVirtualDisplayCallback.class,
                        Surface.class
                );
                constructor.setAccessible(true);
                Display display = displayManagerGlobal.getRealDisplay(displayId);
                virtualDisplay = (VirtualDisplay) constructor.newInstance(
                        displayManagerGlobal,
                        display,
                        callback,
                        surface
                );
            } catch(Throwable e) {
                throw new RuntimeException(e);
            }
        }
        State.setMediaProjection(null);
        return virtualDisplay;
    }

    public static int getFlags(boolean ownContentOnly, boolean rotatesWithContent) {
        int flags = VIRTUAL_DISPLAY_FLAG_PUBLIC
                | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH;
        //    | VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL;
        if (ownContentOnly) {
            flags |= VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        }
        if (rotatesWithContent) {
            flags |= VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT;
        }
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_33_ANDROID_13) {
            flags |= VIRTUAL_DISPLAY_FLAG_TRUSTED
                    | VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                    | VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                    | VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED;
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                flags |= VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP;
                //    flags |= VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                //            | VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP;
            }
        }
        return flags;
    }

    public static void powerOnScreen() {
        if (Pref.getFakeScreen() && State.userService != null && !State.stoppingAllSessions) {
            ScreenSession.setActive(true);
            try {
                State.userService.pressPowerKey();
                State.log("fake screen power key pressed to wake");
                return;
            } catch (RemoteException e) {
                State.log("fake screen wake key failed: " + e.getMessage());
            }
        }
        BlackScreenOverlayService.hide(State.getContext());
        if (State.isInPureBlackActivity != null) {
            State.isInPureBlackActivity.finish();
        } else {
            if (State.userService != null) {
                try {
                    State.userService.stopListenVolumeKey();
                    if (screenOffDisplayId > Display.DEFAULT_DISPLAY) {
                        State.userService.setScreenPowerForDisplay(
                                screenOffDisplayId, SurfaceControl.POWER_MODE_NORMAL);
                    } else {
                        State.userService.setScreenPower(SurfaceControl.POWER_MODE_NORMAL);
                    }
                } catch (RemoteException e) {
                    State.log("powerUpScreen failed: " + e.getMessage());
                }
            }
        }
        screenOffDisplayId = -1;
    }

    private static boolean shouldChangeAspectRatio() {
        if (!ShizukuUtils.hasPermission()) {
            return false;
        }
        if (!OutputSource.isMirrorActive()) {
            return false;
        }
        boolean autoMatchAspectRatio = Pref.getAutoMatchAspectRatio();
        if (!autoMatchAspectRatio) {
            return false;
        }
        return true;
    }

    public static void changeAspectRatio(int width, int height) {
        Context context = State.getContext();
        if (context == null) {
            return;
        }
        changeAspectRatio(CurrentScreen.detect(context).displayId, width, height);
    }

    public static void changeAspectRatio(int displayId, int width, int height) {
        if(!shouldChangeAspectRatio()) {
            return;
        }
        IWindowManager wm = ServiceUtils.getWindowManager();
        if (wm == null) {
            return;
        }
        Point baseSize = new Point();
        try {
            wm.getInitialDisplaySize(displayId, baseSize);
        } catch (Throwable t) {
            State.log("changeAspectRatio: getInitialDisplaySize(" + displayId + ") failed: "
                    + t.getMessage());
            return;
        }
        if (baseSize.x <= 0 || baseSize.y <= 0) {
            return;
        }
        int internalWidth = Math.min(baseSize.x, baseSize.y);
        int internalHeight = Math.max(baseSize.x, baseSize.y);
        // Original LibreDeX/TNT behavior: treat the connected screen as
        // portrait-oriented (min width, max height) so the phone keeps its
        // portrait layout and only its numeric resolution ratio is adapted.
        // Auto-rotate then rotates the mirror 90 degrees to display it as
        // landscape on the receiver.
        float externalWidth = Math.min(width, height);
        float externalHeight = Math.max(width, height);
        if (externalWidth <= 0 || externalHeight <= 0) {
            return;
        }
        internalHeight = (int) (internalWidth * (externalHeight / externalWidth));
        if (internalHeight <= 0) {
            return;
        }
        // Keep the tall main panel usable; the small cover screen is allowed to
        // shrink so the mirror still adapts to the connected screen.
        if (displayId == Display.DEFAULT_DISPLAY && internalHeight < 1600) {
            return;
        }
        aspectRatioForcedDisplays.add(displayId);
        wm.setForcedDisplaySize(displayId, internalWidth, internalHeight);
        State.log("changeAspectRatio displayId=" + displayId + " forced " + internalWidth
                + "x" + internalHeight + " target " + width + "x" + height);
    }

    public static void restoreAspectRatio() {
        Integer[] displays;
        synchronized (aspectRatioForcedDisplays) {
            displays = aspectRatioForcedDisplays.toArray(new Integer[0]);
        }
        if (displays.length == 0) {
            return;
        }
        try {
            if (!ShizukuUtils.hasPermission()) {
                return;
            }
            IWindowManager wm = ServiceUtils.getWindowManager();
            for (int displayId : displays) {
                try {
                    Point initialSize = new Point();
                    wm.getInitialDisplaySize(displayId, initialSize);
                    wm.clearForcedDisplaySize(displayId);
                    wm.setForcedDisplaySize(displayId, initialSize.x, initialSize.y);
                    State.log("restoreAspectRatio displayId=" + displayId
                            + " restored " + initialSize.x + "x" + initialSize.y);
                } catch (Throwable t) {
                    State.log("restoreAspectRatio displayId=" + displayId + " failed: "
                            + t.getMessage());
                }
            }
        } finally {
            aspectRatioForcedDisplays.clear();
        }
    }

    public static class VirtualDisplayCallback extends IVirtualDisplayCallback.Stub {
        public void onPaused() {
        }
        public void onResumed() {
        }
        public void onStopped() {
        }
    }
}

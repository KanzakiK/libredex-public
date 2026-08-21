package com.dex.lspmirror;

import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.util.SparseArray;
import android.hardware.display.DisplayManager;
import android.view.Display;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public final class DexLayerStackHook implements IXposedHookLoadPackage {
    private static final String TAG = "DexLspMirror";
    private static final String MODE_SELF = "self";
    private static final String MODE_SOURCE = "source";

    private static final Map<IBinder, Integer> MIRROR_TOKENS = new ConcurrentHashMap<>();
    private static final Set<Integer> DEX_FLAG_DISPLAY_IDS = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, Object> DP_TASK_DISPLAY_AREAS = new ConcurrentHashMap<>();
    private static final Set<Integer> DEX_MODE_NOTIFIED = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> DEX_ELIGIBLE_NOTIFIED = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> DEX_DEXCONTROLLER_NOTIFIED = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> WALLPAPER_ATTACH_DONE = ConcurrentHashMap.newKeySet();
    private static final Set<String> TDA_DISPLAY_ID_DIAGNOSED = ConcurrentHashMap.newKeySet();
    private static volatile boolean dexControllerGuardInstalled;
    private static volatile boolean dpMirrorGuardArmed;
    private static volatile int dpExternalStackId = -1;
    private static volatile boolean homeSupportGuardInstalled;
    private static Object inputManagerService;
    private static Object systemUiTouchpadController;
    private static Object windowManagerService;
    private static Object logicalDisplayMapper;
    private static ClassLoader systemServerClassLoader;
    private static volatile int lastConfiguredDpDisplayId = -1;
    private static volatile boolean tdaDisplayIdFallbackLogged;
    private static volatile boolean bootDpStateCleanupDone;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if ("com.android.systemui".equals(lpparam.packageName)) {
            hookSystemUiTouchpad(lpparam.classLoader);
            installSystemUiDexMirrorGuard(lpparam.classLoader);
            return;
        }
        if (!"android".equals(lpparam.packageName)) {
            return;
        }
        systemServerClassLoader = lpparam.classLoader;
        cleanupStaleDpStateAtBoot();
        XposedBridge.log(TAG + ": hooking system_server");
        ClassLoader cl = lpparam.classLoader;
        installFakeScreenHooks(cl);
        installRefreshRateUnlockHooks(cl);
        try {
            Class<?> adapter = XposedHelpers.findClass(
                    "com.android.server.display.VirtualDisplayAdapter", cl);
            Class<?> vdd = XposedHelpers.findClass(
                    "com.android.server.display.VirtualDisplayAdapter$VirtualDisplayDevice", cl);
            Class<?> callback = XposedHelpers.findClass(
                    "com.android.server.display.VirtualDisplayAdapter$Callback", cl);
            Class<?> mpCallback = XposedHelpers.findClass(
                    "com.android.server.display.VirtualDisplayAdapter$MediaProjectionCallback", cl);
            Class<?> iMedia = XposedHelpers.findClass(
                    "android.media.projection.IMediaProjection", cl);
            Class<?> vdConfig = XposedHelpers.findClass(
                    "android.hardware.display.VirtualDisplayConfig", cl);

            Constructor<?> vddCtor = null;
            for (Constructor<?> c : vdd.getDeclaredConstructors()) {
                if (c.getParameterTypes().length == 12) {
                    vddCtor = c;
                    break;
                }
            }
            if (vddCtor != null) {
                vddCtor.setAccessible(true);
                XposedBridge.hookMethod(vddCtor, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object self = param.thisObject;
                                IBinder token = (IBinder) XposedHelpers.getObjectField(self, "mDisplayToken");
                                int mirrorId = XposedHelpers.getIntField(self, "mDisplayIdToMirror");
                                if (token != null && mirrorId >= 0) {
                                    MIRROR_TOKENS.put(token, mirrorId);
                                    XposedBridge.log(TAG + ": tracked mirror VD token=" + token
                                            + " mirrorId=" + mirrorId);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": constructor hook failed: " + t);
                            }
                        }
                    });
            } else {
                XposedBridge.log(TAG + ": VirtualDisplayDevice constructor not found");
            }

            Class<?> dms = XposedHelpers.findClass(
                    "com.android.server.display.DisplayManagerService", cl);
            Method getInfoInternal = dms.getDeclaredMethod(
                    "getDisplayInfoInternal", int.class, int.class);
            XposedBridge.hookMethod(getInfoInternal, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object info = param.result;
                                if (info == null) {
                                    return;
                                }
                                syncConfiguredDpDisplay();
                                java.lang.reflect.Field nameField =
                                        info.getClass().getField("name");
                                String name = (String) nameField.get(info);
                                if (name != null && name.startsWith("dex-anywhere-dex-flag")) {
                                    boolean first = DEX_FLAG_DISPLAY_IDS.add((Integer) param.args[0]);
                                    java.lang.reflect.Field flagsField =
                                            info.getClass().getField("flags");
                                    java.lang.reflect.Field typeField =
                                            info.getClass().getField("type");
                                    int flags = flagsField.getInt(info);
                                    int type = typeField.getInt(info);
                                    int newFlags = flags | 0x20000 | 0x4000000 | 0x8000000 | 0x200;
                                    flagsField.setInt(info, newFlags);
                                    typeField.setInt(info, 2);
                                    if (first) {
                                        XposedBridge.log(TAG + ": dex info inject name=" + name
                                                + " flags " + flags + " -> " + newFlags
                                                + " (alwaysUnlocked) type " + type + " -> 2");
                                        int displayId = (Integer) param.args[0];
                                        scheduleWallpaperAttach(cl, displayId);
                                    }
                                }
                                if (name == null || !name.startsWith("dex-anywhere-dex-flag")) {
                                    int configured = configuredDpDisplayId();
                                    int displayId = (Integer) param.args[0];
                                    if (configured == displayId) {
                                        boolean first = DEX_FLAG_DISPLAY_IDS.add(displayId);
                                        dpMirrorGuardArmed = true;
                                        dpExternalStackId = displayId;
                                        java.lang.reflect.Field flagsField =
                                                info.getClass().getField("flags");
                                        java.lang.reflect.Field typeField =
                                                info.getClass().getField("type");
                                        int flags = flagsField.getInt(info);
                                        int type = typeField.getInt(info);
                                        int newFlags = flags | 0x20000 | 0x4000000 | 0x8000000 | 0x200;
                                        flagsField.setInt(info, newFlags);
                                        typeField.setInt(info, 2);
                                        forceCanHostTasks(info);
                                        if (first) {
                                            XposedBridge.log(TAG + ": dp dex info inject displayId="
                                                    + displayId + " flags 0x" + Integer.toHexString(flags)
                                                    + " -> 0x" + Integer.toHexString(newFlags)
                                                    + " (alwaysUnlocked) type " + type + " -> 2");
                                            scheduleWallpaperAttach(cl, displayId);
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": getDisplayInfoInternal hook failed: " + t);
                            }
                        }
                    });

            try {
                Class<?> mapper = XposedHelpers.findClass(
                        "com.android.server.display.LogicalDisplayMapper", cl);
                Method updateDisplays = mapper.getDeclaredMethod(
                        "updateLogicalDisplaysLocked", int.class, boolean.class);
                XposedBridge.hookMethod(updateDisplays, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                logicalDisplayMapper = param.thisObject;
                                java.lang.reflect.Field displaysField =
                                        param.thisObject.getClass().getField("mLogicalDisplays");
                                SparseArray<?> displays =
                                        (SparseArray<?>) displaysField.get(param.thisObject);
                                for (int i = 0; i < displays.size(); i++) {
                                    Object logical = displays.valueAt(i);
                                    if (logical == null) {
                                        continue;
                                    }
                                    java.lang.reflect.Field groupNameField =
                                            logical.getClass().getField("mDisplayGroupName");
                                    String groupName = (String) groupNameField.get(logical);
                                    if (groupName != null && !groupName.isEmpty()) {
                                        continue;
                                    }
                                    Method getInfo = logical.getClass()
                                            .getDeclaredMethod("getDisplayInfoLocked");
                                    getInfo.setAccessible(true);
                                    Object info = getInfo.invoke(logical);
                                    if (info == null) {
                                        continue;
                                    }
                                    String name = (String) info.getClass()
                                            .getField("name").get(info);
                                    int displayId = info.getClass()
                                            .getField("displayId").getInt(info);
                                    boolean dexVirtual = name != null
                                            && name.startsWith("dex-anywhere-dex-flag");
                                    if (dexVirtual || configuredDpDisplayId() == displayId) {
                                        String newGroupName = "libredex-dex-" + displayId;
                                        groupNameField.set(logical, newGroupName);
                                        forceLogicalCanHostTasks(logical);
                                        XposedBridge.log(TAG + ": forced own display group "
                                                + newGroupName + " displayId=" + displayId);
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": own display group hook failed: " + t);
                            }
                        }
                    });
                XposedBridge.log(TAG + ": own display group hook installed");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": own display group hook setup failed: " + t);
            }

            try {
                Class<?> dmsViewport = XposedHelpers.findClass(
                        "com.android.server.display.DisplayManagerService", cl);
                Class<?> ddiViewport = XposedHelpers.findClass(
                        "com.android.server.display.DisplayDeviceInfo", cl);
                Method getViewportType = dmsViewport.getDeclaredMethod(
                        "getViewportType", ddiViewport);
                XposedBridge.hookMethod(getViewportType, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object info = param.args[0];
                                    java.lang.reflect.Field uniqueField = info.getClass()
                                            .getField("uniqueId");
                                    String uniqueId = (String) uniqueField.get(info);
                                    if (uniqueId != null && uniqueId.startsWith(
                                            "virtual:com.libredex,0,dex-anywhere-dex-flag")) {
                                        param.result = java.util.Optional.of(3);
                                        XposedBridge.log(TAG + ": dex viewport forced uniqueId="
                                                + uniqueId);
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": viewport hook failed: " + t);
                                }
                            }
                        });
                XposedBridge.log(TAG + ": viewport hook installed");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": viewport hook setup failed: " + t);
            }

            try {
                Class<?> ims = XposedHelpers.findClass(
                        "com.android.server.input.InputManagerService", cl);
                Method getDexDisplay = ims.getMethod("getDisplayIdForDex");
                XposedBridge.hookMethod(getDexDisplay, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    inputManagerService = param.thisObject;
                                    Object result = param.result;
                                    int did = result instanceof Integer ? (Integer) result : -1;
                                    if (did < 0 || !DEX_FLAG_DISPLAY_IDS.contains(did)) {
                                        did = liveDexDisplayId(param.thisObject);
                                        if (did < 0) {
                                            for (int fakeId : DEX_FLAG_DISPLAY_IDS) {
                                                did = fakeId;
                                                break;
                                            }
                                        }
                                        if (did >= 0) {
                                            param.result = did;
                                            XposedBridge.log(TAG + ": dex input display forced=" + did);
                                        }
                                    }
                                    if (did >= 0 && DEX_MODE_NOTIFIED.add(did)) {
                                        Method setState = param.thisObject.getClass()
                                                .getMethod("setDesktopModeDisplayState", int.class);
                                        setState.invoke(param.thisObject, 1);
                                        XposedBridge.log(TAG + ": desktop mode input state=1 displayId=" + did);
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": dex input state hook failed: " + t);
                                }
                            }
                        });
                XposedBridge.log(TAG + ": input manager service hook installed");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": input manager service hook setup failed: " + t);
            }

            try {
                Class<?> dmsKeepOn = XposedHelpers.findClass(
                        "com.android.server.display.DisplayManagerService", cl);
                Method requestState = null;
                for (Method m : dmsKeepOn.getDeclaredMethods()) {
                    if (m.getName().contains("requestDisplayStateInternal")
                            && (m.getParameterTypes().length == 4
                            || m.getParameterTypes().length == 5)) {
                        requestState = m;
                        break;
                    }
                }
                if (requestState == null) {
                    XposedBridge.log(TAG + ": dex display keep-on method not found");
                } else {
                    XposedBridge.hookMethod(requestState, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                int offset =
                                        ((java.lang.reflect.Method) param.method)
                                                .getParameterTypes().length == 5
                                        ? 1 : 0;
                                int displayId = (Integer) param.args[offset];
                                int state = (Integer) param.args[offset + 1];
                                if (state == android.view.Display.STATE_OFF
                                        && DEX_FLAG_DISPLAY_IDS.contains(displayId)) {
                                    param.args[offset + 1] = android.view.Display.STATE_ON;
                                    XposedBridge.log(TAG
                                            + ": dex display kept on displayId=" + displayId);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG
                                        + ": dex display keep-on hook failed: " + t);
                            }
                        }
                    });
                    XposedBridge.log(TAG + ": dex display keep-on hook installed");
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": dex display keep-on hook setup failed: " + t);
            }

            try {
                Class<?> dps = XposedHelpers.findClass(
                        "com.android.server.display.DisplayPowerState", cl);
                Method prepareFade = dps.getDeclaredMethod(
                        "prepareColorFade", android.content.Context.class, int.class);
                XposedBridge.hookMethod(prepareFade, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            int displayId = XposedHelpers.getIntField(
                                    param.thisObject, "mDisplayId");
                            if (DEX_FLAG_DISPLAY_IDS.contains(displayId)) {
                                param.result = Boolean.FALSE;
                                XposedBridge.log(TAG
                                        + ": dex display color fade skipped displayId="
                                        + displayId);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG
                                    + ": dex display color fade hook failed: " + t);
                        }
                    }
                });
                Method setFadeLevel = dps.getDeclaredMethod(
                        "setColorFadeLevel", float.class);
                XposedBridge.hookMethod(setFadeLevel, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            int displayId = XposedHelpers.getIntField(
                                    param.thisObject, "mDisplayId");
                            if (DEX_FLAG_DISPLAY_IDS.contains(displayId)) {
                                param.args[0] = 0f;
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG
                                    + ": dex display color fade level hook failed: " + t);
                        }
                    }
                });
                XposedBridge.log(TAG + ": dex display color fade hook installed");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": dex display color fade hook setup failed: " + t);
            }

            try {
                Class<?> wms = Class.forName(
                        "com.android.server.wm.WindowManagerService", false, cl);
                Method eligible = wms.getDeclaredMethod(
                        "isEligibleForDesktopMode", int.class);
                XposedBridge.hookMethod(eligible, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    int displayId = (Integer) param.args[0];
                                    if (DEX_FLAG_DISPLAY_IDS.contains(displayId)) {
                                        param.result = Boolean.TRUE;
                                        registerDexController(param.thisObject, displayId);
                                        if (DEX_ELIGIBLE_NOTIFIED.add(displayId)) {
                                            XposedBridge.log(TAG + ": wms desktop eligible forced displayId="
                                                    + displayId);
                                        }
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": wms eligible hook failed: " + t);
                                }
                            }
                        });
                XposedBridge.log(TAG + ": wms eligible hook installed");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": wms eligible hook setup failed: " + t);
            }

            try {
                Class<?> inputCallback = XposedHelpers.findClass(
                        "com.android.server.wm.InputManagerCallback", cl);
                Method getPointerDisplay = inputCallback.getDeclaredMethod("getPointerDisplayId");
                XposedBridge.hookMethod(getPointerDisplay, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    int did = liveDexDisplayId(inputManagerService);
                                    if (did < 0) {
                                        for (int fakeId : DEX_FLAG_DISPLAY_IDS) {
                                            did = fakeId;
                                            break;
                                        }
                                    }
                                    if (did >= 0 && !Integer.valueOf(did).equals(param.result)) {
                                        param.result = did;
                                        XposedBridge.log(TAG + ": pointer display forced=" + did);
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": pointer display hook failed: " + t);
                                }
                            }
                        });
                XposedBridge.log(TAG + ": pointer display hook installed");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": pointer display hook setup failed: " + t);
            }

            try {
                Class<?> tda = XposedHelpers.findClass(
                        "com.android.server.wm.TaskDisplayArea", cl);
                Class<?> wc = XposedHelpers.findClass(
                        "com.android.server.wm.WindowContainer", cl);
                Class<?> scTxn = XposedHelpers.findClass(
                        "android.view.SurfaceControl$Transaction", cl);

                Method addChild = tda.getDeclaredMethod("addChild", wc, int.class);
                XposedBridge.hookMethod(addChild, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                ensureDexRootOrder(param.thisObject);
                            }
                        });

                Method positionChildAt = tda.getDeclaredMethod(
                        "positionChildAt", int.class, wc, boolean.class);
                XposedBridge.hookMethod(positionChildAt, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                ensureDexRootOrder(param.thisObject);
                            }
                        });

                Method assignChildLayers = tda.getDeclaredMethod(
                        "assignChildLayers", scTxn);
                XposedBridge.hookMethod(assignChildLayers, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                ensureDexRootOrder(param.thisObject);
                            }
                        });
                XposedBridge.log(TAG + ": task display area root order hook installed");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": task display area hook setup failed: " + t);
            }

            Class<?> txn = XposedHelpers.findClass(
                    "android.view.SurfaceControl$Transaction", cl);
            Method setLayerStack = txn.getDeclaredMethod(
                    "setDisplayLayerStack", IBinder.class, int.class);
            XposedBridge.hookMethod(setLayerStack, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                IBinder token = (IBinder) param.args[0];
                                Integer mirror = MIRROR_TOKENS.get(token);
                                if (mirror != null && mirror >= 0) {
                                    int old = (Integer) param.args[1];
                                    if (MODE_SOURCE.equals(mirrorMode())) {
                                        param.args[1] = mirror;
                                        XposedBridge.log(TAG + ": layerStack override source token=" + token
                                                + " " + old + " -> " + mirror);
                                    } else {
                                        XposedBridge.log(TAG + ": layerStack keep self token=" + token
                                                + " stack=" + old);
                                    }
                                }
                                forcePhoneMirrorLayerStack(token, param);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": setDisplayLayerStack hook failed: " + t);
                            }
                        }
                    });
            try {
                Method staticSetLayerStack = Class.forName(
                        "android.view.SurfaceControl", false, cl)
                        .getMethod("setDisplayLayerStack", IBinder.class, int.class);
                XposedBridge.hookMethod(staticSetLayerStack, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    forcePhoneMirrorLayerStack(
                                            (IBinder) param.args[0], param);
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG
                                            + ": static setDisplayLayerStack hook failed: "
                                            + t);
                                }
                            }
                        });
                XposedBridge.log(TAG + ": static layer stack hook installed");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": static layer stack hook setup failed: " + t);
            }
            try {
                Class<?> txnClass = Class.forName(
                        "android.view.SurfaceControl$Transaction", false, cl);
                Class<?> scClass = Class.forName(
                        "android.view.SurfaceControl", false, cl);
                for (Method m : txnClass.getDeclaredMethods()) {
                    if (!"setLayerStack".equals(m.getName())
                            || m.getParameterTypes().length != 2
                            || m.getParameterTypes()[0] != scClass
                            || m.getParameterTypes()[1] != int.class) {
                        continue;
                    }
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    try {
                                        int old = (Integer) param.args[1];
                                        if (dpMirrorGuardArmed
                                                && configuredDpDisplayId() < 0
                                                && old == dpExternalStackId) {
                                            param.args[1] = 0;
                                            XposedBridge.log(TAG
                                                    + ": txn surface layer stack forced "
                                                    + old + " -> 0");
                                        }
                                    } catch (Throwable t) {
                                        XposedBridge.log(TAG
                                                + ": txn setLayerStack hook failed: " + t);
                                    }
                                }
                            });
                    XposedBridge.log(TAG + ": txn setLayerStack hook installed");
                    break;
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": txn setLayerStack hook setup failed: " + t);
            }
            XposedBridge.log(TAG + ": hooks installed");
            logLayerStackApis(cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setup failed: " + t);
        }
    }

    private static void logLayerStackApis(ClassLoader cl) {
        try {
            for (String name : new String[]{
                    "android.view.SurfaceControl",
                    "android.view.SurfaceControl$Transaction",
                    "com.android.server.display.DisplayDevice",
                    "com.android.server.display.DisplayManagerService"}) {
                try {
                    Class<?> c = Class.forName(name, false, cl);
                    StringBuilder sb = new StringBuilder();
                    for (Method m : c.getDeclaredMethods()) {
                        String n = m.getName().toLowerCase();
                        if (n.contains("layerstack") || n.contains("layer_stack")) {
                            sb.append(m.getName()).append('(')
                              .append(java.util.Arrays.toString(m.getParameterTypes()))
                              .append(") ");
                        }
                    }
                    XposedBridge.log(TAG + ": layerStackApis " + name + " -> " + sb);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": layerStackApis class missing " + name + " " + t);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": logLayerStackApis failed " + t);
        }
    }

    private static void hookSystemUiTouchpad(ClassLoader cl) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable controllerInstaller = new Runnable() {
            private int attempts = 0;

            @Override
            public void run() {
                if (systemUiTouchpadController != null) {
                    return;
                }
                try {
                    Class<?> controllerClass = XposedHelpers.findClass(
                            "com.android.systemui.dextouchpad.DexTouchpadController", cl);
                    for (java.lang.reflect.Constructor<?> c :
                            controllerClass.getDeclaredConstructors()) {
                        c.setAccessible(true);
                        XposedBridge.hookMethod(c, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    systemUiTouchpadController = param.thisObject;
                                    XposedBridge.log(TAG
                                            + ": sysui touchpad controller captured");
                                }
                            });
                    }
                } catch (Throwable t) {
                    attempts++;
                    if (attempts == 1 || attempts % 20 == 0) {
                        XposedBridge.log(TAG + ": sysui touchpad controller retry "
                                + attempts + ": " + t);
                    }
                    handler.postDelayed(this, 3000);
                }
            }
        };
        handler.post(controllerInstaller);

        final Runnable fragmentInstaller = new Runnable() {
            private int attempts = 0;
            private boolean installed = false;

            @Override
            public void run() {
                if (installed) {
                    return;
                }
                try {
                    Class<?> fragment = XposedHelpers.findClass(
                            "com.android.systemui.dextouchpad.activity.TouchpadFragment", cl);
                    Method onStart = fragment.getDeclaredMethod("onStart");
                    XposedBridge.hookMethod(onStart, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object dmObj = XposedHelpers.getObjectField(
                                            param.thisObject, "mDisplayManager");
                                    if (dmObj instanceof DisplayManager) {
                                        DisplayManager dm = (DisplayManager) dmObj;
                                        for (Display display : dm.getDisplays()) {
                                            android.view.DisplayInfo info =
                                                    new android.view.DisplayInfo();
                                            Method getInfo = display.getClass().getMethod(
                                                    "getDisplayInfo",
                                                    android.view.DisplayInfo.class);
                                            boolean ok = (Boolean) getInfo.invoke(display, info);
                                            int flags = info.getClass()
                                                    .getField("flags").getInt(info);
                                            String name = display.getName();
                                            XposedBridge.log(TAG + ": sysui touchpad display id="
                                                    + display.getDisplayId() + " name=" + name
                                                    + " flags=0x" + Integer.toHexString(flags)
                                                    + " ok=" + ok);
                                            if (name != null
                                                    && name.startsWith("dex-anywhere-dex-flag")) {
                                                java.lang.reflect.Field rotationField =
                                                        param.thisObject.getClass()
                                                                .getField("mDexDisplayRotation");
                                                rotationField.setInt(
                                                        param.thisObject,
                                                        display.getRotation());
                                                XposedBridge.log(TAG + ": sysui touchpad forced "
                                                        + "dex rotation displayId="
                                                        + display.getDisplayId());
                                                if (systemUiTouchpadController != null) {
                                                    Object dexDisplayIds = XposedHelpers
                                                            .getObjectField(
                                                                    systemUiTouchpadController,
                                                                    "mDexDisplayIds");
                                                    if (dexDisplayIds instanceof java.util.Set) {
                                                        ((java.util.Set) dexDisplayIds)
                                                                .add(display.getDisplayId());
                                                        XposedBridge.log(TAG
                                                                + ": sysui touchpad "
                                                                + "dexDisplayIds += "
                                                                + display.getDisplayId());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(
                                            TAG + ": sysui touchpad hook failed: " + t);
                                }
                            }
                        });
                    installed = true;
                    XposedBridge.log(TAG + ": sysui touchpad fragment hook installed");
                } catch (Throwable t) {
                    attempts++;
                    if (attempts == 1 || attempts % 20 == 0) {
                        XposedBridge.log(TAG + ": sysui touchpad fragment retry "
                                + attempts + ": " + t);
                    }
                    handler.postDelayed(this, 3000);
                }
            }
        };
        handler.post(fragmentInstaller);

        final Runnable windowInstaller = new Runnable() {
            private int attempts = 0;
            private boolean installed = false;

            @Override
            public void run() {
                if (installed) {
                    return;
                }
                try {
                    Class<?> touchpadWindow = XposedHelpers.findClass(
                            "com.android.systemui.dextouchpad.activity.TouchpadWindow", cl);
                    Method onStartSetup = touchpadWindow.getMethod("onStartSetup");
                    XposedBridge.hookMethod(onStartSetup, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object view = XposedHelpers.getObjectField(
                                        param.thisObject, "mWindowView");
                                if (view instanceof android.view.View) {
                                    CoverTouchpadBridge.install((android.view.View) view);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG
                                        + ": sysui touchpad bridge hook failed: " + t);
                            }
                        }
                    });
                    installed = true;
                    XposedBridge.log(TAG + ": sysui touchpad window hook installed");
                } catch (Throwable t) {
                    attempts++;
                    if (attempts == 1 || attempts % 20 == 0) {
                        XposedBridge.log(TAG + ": sysui touchpad window retry "
                                + attempts + ": " + t);
                    }
                    handler.postDelayed(this, 3000);
                }
            }
        };
        handler.post(windowInstaller);
    }

    // The fake VirtualDisplay is never seen by the official WifiDisplayAdapter
    // path, so startSystemDecorations skips DexController registration. Register
    // it manually to activate the official input/IME/refresh-rate state machine.
    private static void registerDexController(Object wms, int displayId) {
        try {
            if (!DEX_DEXCONTROLLER_NOTIFIED.add(displayId)) {
                return;
            }
            windowManagerService = wms;
            Object atm = XposedHelpers.getObjectField(wms, "mAtmService");
            Object dexController = XposedHelpers.getObjectField(atm, "mDexController");
            installDexControllerGuard(dexController.getClass());
            // The physical DP display is already registered, so the official
            // method can run and also switches the TaskDisplayArea to the DeX
            // windowing mode. The fake virtual display is not registered yet,
            // so keep using the manual state mirror for it.
            if (configuredDpDisplayId() == displayId) {
                Method setExternal = findSetExternalDesktopDisplayId(dexController.getClass());
                if (setExternal != null) {
                    try {
                        setExternal.invoke(dexController, displayId);
                        XposedBridge.log(TAG + ": dex controller official register displayId="
                                + displayId);
                        return;
                    } catch (Throwable t) {
                        Throwable cause = t.getCause() != null ? t.getCause() : t;
                        XposedBridge.log(TAG + ": official dex controller register failed, "
                                + "falling back to manual state: " + cause);
                    }
                }
            }
            // The official setExternalDesktopDisplayId dereferences
            // RootWindowContainer.getDisplayContent() before the display is fully
            // registered from our hook, so mirror only the state it would set.
            java.lang.reflect.Field primaryDisplayField = dexController.getClass()
                    .getDeclaredField("mPrimaryExternalDesktopDisplayId");
            primaryDisplayField.setAccessible(true);
            primaryDisplayField.setInt(dexController, displayId);
            Object ims = XposedHelpers.getObjectField(wms, "mInputManager");
            ims.getClass().getMethod(
                    "setDesktopModeDisplayState", int.class).invoke(ims, 1);
            ims.getClass().getMethod(
                    "setDisplayIdForPointerIcon", int.class).invoke(ims, displayId);
            Object nativeIms = XposedHelpers.getObjectField(ims, "mNative");
            nativeIms.getClass().getMethod(
                    "setPointerDisplayId", int.class).invoke(nativeIms, displayId);
            XposedBridge.log(TAG + ": dex controller registered displayId=" + displayId);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            XposedBridge.log(TAG + ": registerDexController failed: " + cause);
        }
    }

    private static Method findSetExternalDesktopDisplayId(Class<?> dexControllerClass) {
        for (Method method : dexControllerClass.getDeclaredMethods()) {
            if ("setExternalDesktopDisplayId".equals(method.getName())
                    && method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0] == int.class) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static void installDexControllerGuard(Class<?> dexControllerClass) {
        if (dexControllerGuardInstalled) {
            return;
        }
        Method setExternal = findSetExternalDesktopDisplayId(dexControllerClass);
        if (setExternal == null) {
            return;
        }
        XposedBridge.hookMethod(setExternal, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                int displayId = (Integer) param.args[0];
                if (displayId >= 0 && !isActiveDexDisplay(displayId)) {
                    param.setResult(null);
                    XposedBridge.log(TAG + ": dex controller registration blocked displayId="
                            + displayId);
                }
            }
        });
        dexControllerGuardInstalled = true;
        XposedBridge.log(TAG + ": dex controller guard installed");
    }

    private static boolean isActiveDexDisplay(int displayId) {
        return DEX_FLAG_DISPLAY_IDS.contains(displayId)
                || configuredDpDisplayId() == displayId;
    }

    private static void forcePhoneMirrorLayerStack(
            IBinder token, XC_MethodHook.MethodHookParam param) {
        if (!dpMirrorGuardArmed || configuredDpDisplayId() >= 0) {
            return;
        }
        int old = (Integer) param.args[1];
        if (old == dpExternalStackId) {
            param.args[1] = 0;
            XposedBridge.log(TAG + ": dp mirror stack forced token="
                    + token + " " + old + " -> 0");
        }
    }

    private static void installSystemUiDexMirrorGuard(ClassLoader cl) {
        if (!aggressiveHooksEnabled()) {
            return;
        }
        try {
            Class<?> dmClass = Class.forName(
                    "android.hardware.display.DisplayManager", false, cl);
            for (Method m : dmClass.getDeclaredMethods()) {
                if (!"isExternalDesktopDisplay".equals(m.getName())) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object info = param.args[0];
                            int displayId = -1;
                            try {
                                displayId = info.getClass()
                                        .getField("displayId").getInt(info);
                            } catch (Throwable ignored) {
                            }
                            if (displayId >= 0
                                    && wasManagedDpDisplay(displayId)
                                    && configuredDpDisplayId() < 0) {
                                param.setResult(Boolean.FALSE);
                                XposedBridge.log(TAG + ": sysui dex display blocked displayId="
                                        + displayId);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": sysui dex guard hook failed: " + t);
                        }
                    }
                });
                XposedBridge.log(TAG + ": sysui dex guard installed");
                return;
            }
            XposedBridge.log(TAG + ": sysui isExternalDesktopDisplay not found");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": sysui dex guard setup failed: " + t);
        }
    }

    private static boolean wasManagedDpDisplay(int displayId) {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke(null);
            Object systemContext = atClass.getMethod("getSystemContext").invoke(at);
            android.content.ContentResolver resolver = (android.content.ContentResolver)
                    systemContext.getClass().getMethod("getContentResolver")
                            .invoke(systemContext);
            String value = android.provider.Settings.Global.getString(
                    resolver, "libredex_dp_managed_display");
            return value != null && String.valueOf(displayId).equals(value.trim());
        } catch (Throwable t) {
            return false;
        }
    }

    private static void syncConfiguredDpDisplay() {
        int configured = configuredDpDisplayId();
        int previous = lastConfiguredDpDisplayId;
        if (previous == configured) {
            return;
        }
        if (previous >= 0 && previous != configured) {
            DEX_FLAG_DISPLAY_IDS.remove(previous);
            DEX_MODE_NOTIFIED.remove(previous);
            DEX_ELIGIBLE_NOTIFIED.remove(previous);
            DEX_DEXCONTROLLER_NOTIFIED.remove(previous);
            WALLPAPER_ATTACH_DONE.remove(previous);
            setLogicalDisplayCanHostTasks(previous, false);
            unmarkHomeSupported(previous);
            resetDexController(previous);
            restoreDpTaskDisplayArea(previous);
            XposedBridge.log(TAG + ": dp dex cleared displayId=" + previous);
        }
        lastConfiguredDpDisplayId = configured;
    }

    private static void removeHomeRootTask(int displayId) {
        if (displayId <= 0) {
            return;
        }
        try {
            Object wms = windowManagerService;
            if (wms == null) {
                return;
            }
            Object root = XposedHelpers.getObjectField(wms, "mRoot");
            Object displayContent = root.getClass().getMethod(
                    "getDisplayContent", int.class).invoke(root, displayId);
            if (displayContent == null) {
                XposedBridge.log(TAG + ": removeHomeRootTask displayContent null displayId="
                        + displayId);
                return;
            }
            Object tda = null;
            try {
                tda = displayContent.getClass().getMethod(
                        "getDefaultTaskDisplayArea").invoke(displayContent);
            } catch (Throwable ignored) {
            }
            if (tda == null) {
                try {
                    tda = XposedHelpers.getObjectField(displayContent, "mTaskDisplayArea");
                } catch (Throwable ignored) {
                }
            }
            if (tda == null) {
                try {
                    tda = displayContent.getClass().getMethod(
                            "getDisplayArea").invoke(displayContent);
                } catch (Throwable ignored) {
                }
            }
            if (tda == null) {
                XposedBridge.log(TAG + ": removeHomeRootTask TDA null displayId=" + displayId);
                return;
            }
            Object homeRoot = null;
            try {
                homeRoot = tda.getClass().getMethod(
                        "getRootTask", int.class, int.class).invoke(tda, 1, 2);
            } catch (Throwable ignored) {
            }
            if (homeRoot == null) {
                XposedBridge.log(TAG + ": removeHomeRootTask home root null displayId="
                        + displayId);
                return;
            }
            try {
                tda.getClass().getMethod("removeRootTask", homeRoot.getClass())
                        .invoke(tda, homeRoot);
                XposedBridge.log(TAG + ": removeHomeRootTask removed home root displayId="
                        + displayId);
                return;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": removeRootTask failed displayId="
                        + displayId + " " + t);
            }
            try {
                homeRoot.getClass().getMethod("removeIfPossible").invoke(homeRoot);
                XposedBridge.log(TAG + ": removeHomeRootTask removeIfPossible displayId="
                        + displayId);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": removeHomeRootTask removeIfPossible failed displayId="
                        + displayId + " " + t);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": removeHomeRootTask failed displayId="
                    + displayId + " " + t);
        }
    }

    private static void forceDpTaskDisplayAreaFreeform(Object tda, int displayId) {
        forceTaskDisplayAreaFreeform(tda, displayId, true);
    }

    private static void forceTaskDisplayAreaFreeform(
            Object tda, int displayId, boolean trackRestore) {
        try {
            Method getMode = tda.getClass().getMethod("getWindowingMode");
            int mode = (Integer) getMode.invoke(tda);
            if (mode != 4) {
                tda.getClass().getMethod("setWindowingMode", int.class)
                        .invoke(tda, 4);
                XposedBridge.log(TAG + ": dex TDA freeform forced displayId="
                        + displayId + " old=" + mode);
            }
            if (trackRestore) {
                DP_TASK_DISPLAY_AREAS.put(displayId, tda);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": forceTaskDisplayAreaFreeform failed: " + t);
        }
    }

    private static void restoreDpTaskDisplayArea(int displayId) {
        Object tda = DP_TASK_DISPLAY_AREAS.remove(displayId);
        if (tda == null) {
            return;
        }
        try {
            int id = taskDisplayAreaDisplayId(tda);
            if (id != displayId) {
                return;
            }
            Method getMode = tda.getClass().getMethod("getWindowingMode");
            if ((Integer) getMode.invoke(tda) == 4) {
                tda.getClass().getMethod("setWindowingMode", int.class).invoke(tda, 1);
                XposedBridge.log(TAG + ": dp TDA fullscreen restored displayId="
                        + displayId);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": restoreDpTaskDisplayArea failed: " + t);
        }
    }

    private static int taskDisplayAreaDisplayId(Object tda) {
        if (tda == null) {
            return -1;
        }
        try {
            int id = XposedHelpers.getIntField(tda, "mDisplayId");
            if (id >= 0) {
                return id;
            }
        } catch (Throwable ignored) {
        }
        try {
            int id = (Integer) invokeNoArg(tda, "getDisplayId");
            if (id >= 0) {
                return id;
            }
        } catch (Throwable ignored) {
        }
        for (Class<?> c = tda.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("getDisplayId");
                m.setAccessible(true);
                int id = (Integer) m.invoke(tda);
                if (id >= 0) {
                    logTdaDisplayIdFallback();
                    return id;
                }
            } catch (Throwable ignored) {
            }
        }
        Object dc = null;
        try {
            dc = invokeNoArg(tda, "getDisplayContent");
        } catch (Throwable ignored) {
        }
        if (dc == null) {
            try {
                dc = XposedHelpers.getObjectField(tda, "mDisplayContent");
            } catch (Throwable ignored) {
            }
        }
        if (dc == null) {
            try {
                dc = XposedHelpers.getObjectField(tda, "mDisplay");
            } catch (Throwable ignored) {
            }
        }
        if (dc == null) {
            for (String name : new String[]{"mDisplayContent", "mDisplay", "mDisplayId"}) {
                try {
                    Field f = findDeclaredField(tda.getClass(), name);
                    if (f == null) {
                        continue;
                    }
                    Object value = f.get(tda);
                    if (value instanceof Integer) {
                        logTdaDisplayIdFallback();
                        return (Integer) value;
                    }
                    if (value != null && dc == null) {
                        dc = value;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (dc != null) {
            try {
                int id = (Integer) invokeNoArg(dc, "getDisplayId");
                if (id >= 0) {
                    logTdaDisplayIdFallback();
                    return id;
                }
            } catch (Throwable ignored) {
            }
            for (Class<?> c = dc.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod("getDisplayId");
                    m.setAccessible(true);
                    int id = (Integer) m.invoke(dc);
                    if (id >= 0) {
                        logTdaDisplayIdFallback();
                        return id;
                    }
                } catch (Throwable ignored) {
                }
            }
            try {
                int id = XposedHelpers.getIntField(dc, "mDisplayId");
                if (id >= 0) {
                    logTdaDisplayIdFallback();
                    return id;
                }
            } catch (Throwable ignored) {
            }
        }
        logTdaDisplayIdUnavailable(tda);
        return -1;
    }

    private static Field findDeclaredField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method findDeclaredMethod(
            Class<?> clazz, String name, Class<?>... paramTypes) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String name) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static void logTdaDisplayIdFallback() {
        if (tdaDisplayIdFallbackLogged) {
            return;
        }
        tdaDisplayIdFallbackLogged = true;
        XposedBridge.log(TAG + ": TaskDisplayArea.getDisplayId resolved via fallback");
    }

    private static void logTdaDisplayIdUnavailable(Object tda) {
        if (!TDA_DISPLAY_ID_DIAGNOSED.add(tda.getClass().getName())) {
            return;
        }
        StringBuilder methods = new StringBuilder();
        StringBuilder fields = new StringBuilder();
        for (Class<?> c = tda.getClass(); c != null; c = c.getSuperclass()) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    String name = m.getName().toLowerCase();
                    if (name.contains("display") || name.contains("content")) {
                        methods.append(m.getName()).append(' ');
                    }
                }
                for (Field f : c.getDeclaredFields()) {
                    String name = f.getName().toLowerCase();
                    if (name.contains("display") || name.contains("content")) {
                        fields.append(f.getName()).append(' ');
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        XposedBridge.log(TAG + ": tda displayId unavailable class=" + tda.getClass().getName()
                + " displayMethods=" + methods + " displayFields=" + fields);
    }

    private static void resetDexController(int displayId) {
        try {
            if (windowManagerService == null) {
                return;
            }
            Object atm = XposedHelpers.getObjectField(windowManagerService, "mAtmService");
            Object dexController = XposedHelpers.getObjectField(atm, "mDexController");
            Method setExternal = findSetExternalDesktopDisplayId(dexController.getClass());
            if (setExternal != null) {
                try {
                    setExternal.invoke(dexController, -1);
                    XposedBridge.log(TAG + ": dex controller cleared displayId=" + displayId);
                    return;
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    XposedBridge.log(TAG + ": official dex controller clear failed, "
                            + "falling back to primary field: " + cause);
                }
            }
            java.lang.reflect.Field primaryDisplayField = dexController.getClass()
                    .getDeclaredField("mPrimaryExternalDesktopDisplayId");
            primaryDisplayField.setAccessible(true);
            primaryDisplayField.setInt(dexController, -1);
            XposedBridge.log(TAG + ": dex controller primary field cleared displayId="
                    + displayId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": resetDexController failed: " + t);
        }
    }

    private static void forceCanHostTasks(Object info) {
        try {
            java.lang.reflect.Field field;
            try {
                field = info.getClass().getField("canHostTasks");
            } catch (NoSuchFieldException e) {
                field = info.getClass().getDeclaredField("canHostTasks");
                field.setAccessible(true);
            }
            field.setBoolean(info, true);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": canHostTasks inject failed: " + t);
        }
    }

    private static void forceLogicalCanHostTasks(Object logical) {
        try {
            java.lang.reflect.Field canHostField = logical.getClass()
                    .getDeclaredField("mCanHostTasks");
            canHostField.setAccessible(true);
            canHostField.setBoolean(logical, true);
            java.lang.reflect.Field baseInfoField = logical.getClass()
                    .getDeclaredField("mBaseDisplayInfo");
            baseInfoField.setAccessible(true);
            Object baseInfo = baseInfoField.get(logical);
            if (baseInfo != null) {
                forceCanHostTasks(baseInfo);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": forceLogicalCanHostTasks failed: " + t);
        }
    }

    private static void setLogicalDisplayCanHostTasks(int displayId, boolean canHost) {
        try {
            if (logicalDisplayMapper == null) {
                return;
            }
            java.lang.reflect.Field displaysField = logicalDisplayMapper.getClass()
                    .getField("mLogicalDisplays");
            SparseArray<?> displays = (SparseArray<?>) displaysField.get(logicalDisplayMapper);
            if (displays == null) {
                return;
            }
            for (int i = 0; i < displays.size(); i++) {
                Object logical = displays.valueAt(i);
                if (logical == null) {
                    continue;
                }
                Method getInfo = logical.getClass().getDeclaredMethod("getDisplayInfoLocked");
                getInfo.setAccessible(true);
                Object info = getInfo.invoke(logical);
                if (info == null) {
                    continue;
                }
                int id = info.getClass().getField("displayId").getInt(info);
                if (id != displayId) {
                    continue;
                }
                java.lang.reflect.Field canHostField = logical.getClass()
                        .getDeclaredField("mCanHostTasks");
                canHostField.setAccessible(true);
                canHostField.setBoolean(logical, canHost);
                java.lang.reflect.Field baseInfoField = logical.getClass()
                        .getDeclaredField("mBaseDisplayInfo");
                baseInfoField.setAccessible(true);
                Object baseInfo = baseInfoField.get(logical);
                if (baseInfo != null) {
                    java.lang.reflect.Field infoField;
                    try {
                        infoField = baseInfo.getClass().getField("canHostTasks");
                    } catch (NoSuchFieldException e) {
                        infoField = baseInfo.getClass().getDeclaredField("canHostTasks");
                        infoField.setAccessible(true);
                    }
                    infoField.setBoolean(baseInfo, canHost);
                }
                XposedBridge.log(TAG + ": logical canHostTasks " + canHost
                        + " displayId=" + displayId);
                return;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setLogicalDisplayCanHostTasks failed: " + t);
        }
    }

    // DeX keeps the launcher root as an always-on-top freeform task; without this
    // invariant it can sit above the activatable root and cover every app window.
    private static void ensureDexRootOrder(Object tda) {
        try {
            int displayId = taskDisplayAreaDisplayId(tda);
            if (displayId < 0) {
                XposedBridge.log(TAG + ": ensureDexRootOrder tda displayId unavailable tda="
                        + tda.getClass().getName());
                return;
            }
            if (!DEX_FLAG_DISPLAY_IDS.contains(displayId)) {
                return;
            }
            forceTaskDisplayAreaFreeform(
                    tda, displayId, configuredDpDisplayId() == displayId);
            Object home = tda.getClass().getMethod(
                    "getOrCreateRootHomeTask", boolean.class).invoke(tda, false);
            if (home == null) {
                return;
            }
            Object active = tda.getClass().getMethod(
                    "getActivatedDesktopTask").invoke(tda);
            if (active == null || active == home) {
                return;
            }
            java.lang.reflect.Field boostField;
            try {
                boostField = active.getClass().getField("mBoostRootTaskLayerForFreeform");
            } catch (NoSuchFieldException e) {
                boostField = findDeclaredField(active.getClass(), "mBoostRootTaskLayerForFreeform");
            }
            if (boostField == null) {
                XposedBridge.log(TAG + ": dex boost field missing on this build");
                return;
            }
            if (!boostField.getBoolean(active)) {
                try {
                    Object[] args = {Boolean.TRUE, Boolean.FALSE};
                    try {
                        active.getClass().getMethod(
                                "setBoostTaskLayerForFreeform", boolean.class, boolean.class)
                                .invoke(active, args);
                    } catch (NoSuchMethodException ePublic) {
                        // Android 16 / One UI 8.5 may make this method non-public
                        // or move it up the hierarchy; fall back to declared+accessible.
                        java.lang.reflect.Method m = findDeclaredMethod(
                                active.getClass(), "setBoostTaskLayerForFreeform",
                                boolean.class, boolean.class);
                        if (m == null) {
                            throw ePublic;
                        }
                        m.setAccessible(true);
                        m.invoke(active, args);
                    }
                    XposedBridge.log(TAG + ": dex activatable root boosted display="
                            + displayId + " task=" + active);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": dex activatable root boost failed: "
                            + t + " (non-fatal)");
                }
            }
            java.util.ArrayList<Object> children = (java.util.ArrayList<Object>)
                    XposedHelpers.getObjectField(tda, "mChildren");
            int homeIdx = children.indexOf(home);
            int activeIdx = children.indexOf(active);
            if (homeIdx >= 0 && activeIdx >= 0 && activeIdx < homeIdx) {
                children.remove(activeIdx);
                children.add(homeIdx, active);
                XposedBridge.log(TAG + ": dex root order fixed display=" + displayId
                        + " active=" + active + " home=" + home);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ensureDexRootOrder failed: " + t);
        }
    }

    private static int liveDexDisplayId(Object ims) {
        if (ims == null) {
            return -1;
        }
        try {
            Object dm = XposedHelpers.getObjectField(ims, "mDisplayManager");
            if (dm instanceof DisplayManager) {
                for (Display display : ((DisplayManager) dm).getDisplays()) {
                    String name = display.getName();
                    if (name != null && name.startsWith("dex-anywhere-dex-flag")) {
                        return display.getDisplayId();
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": live dex display lookup failed: " + t);
        }
        return -1;
    }

    private static String mirrorMode() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, "persist.dex.lspmirror.mode", MODE_SELF);
        } catch (Throwable t) {
            return MODE_SELF;
        }
    }

    private static volatile int fakeScreenPowerMode = 0;

    // Vote priorities on One UI 16 (services.jar com.android.server.display.mode.Vote):
    //   15 = PRIORITY_SYNCHRONIZED_REFRESH_RATE
    //   16 = PRIORITY_SYNCHRONIZED_RENDER_FRAME_RATE
    //   17 = PRIORITY_LIMIT_MODE
    //   22 = PRIORITY_LOW_POWER_MODE_MODES
    // All of them cap the physical display refresh rate (60 Hz) when a
    // virtual/external display activates. We drop them while a LibreDeX
    // session is active so the panel stays at its native 120 Hz and the
    // streaming virtual display can actually be fed at >60 fps.
    private static volatile boolean refreshRateUnlockHooksInstalled;

    private static void installRefreshRateUnlockHooks(ClassLoader cl) {
        if (refreshRateUnlockHooksInstalled) {
            return;
        }
        refreshRateUnlockHooksInstalled = true;
        try {
            Class<?> voteClass = XposedHelpers.findClass(
                    "com.android.server.display.mode.Vote", cl);
            Class<?> votesStorage = XposedHelpers.findClass(
                    "com.android.server.display.mode.VotesStorage", cl);
            Method updateVote = votesStorage.getDeclaredMethod(
                    "updateVote", int.class, int.class, voteClass);
            XposedBridge.hookMethod(updateVote, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!sessionActiveEnabled()) {
                        return;
                    }
                    int displayId = (Integer) param.args[0];
                    int priority = (Integer) param.args[1];
                    Object vote = param.args[2];
                    if (displayId == -1 && vote != null && isRefreshRateLimitPriority(priority)) {
                        XposedBridge.log(TAG + ": dropped refresh-rate limiting vote priority="
                                + priority + " displayId=" + displayId);
                        param.setResult(null);
                    }
                }
            });
            XposedBridge.log(TAG + ": refresh-rate unlock hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": refresh-rate unlock hook setup failed: " + t);
        }
        try {
            // Samsung VRR talks to SurfaceFlinger directly through
            // SurfaceControl.notifyHFRmode(token, mode). In SEAMLESS mode
            // (value 1) the panel drops to 60 Hz whenever content looks static,
            // which keeps the streaming virtual display at 60 fps. While a
            // LibreDeX session is active we force HIGH (value 2 = 120 Hz fixed).
            Class<?> surfaceControl = XposedHelpers.findClass(
                    "android.view.SurfaceControl", cl);
            for (Method method : surfaceControl.getDeclaredMethods()) {
                if ("notifyHFRmode".equals(method.getName())) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            boolean sessionActive = sessionActiveEnabled();
                            int mode = (Integer) param.args[1];
                            XposedBridge.log(TAG + ": notifyHFRmode mode=" + mode
                                    + " session=" + sessionActive);
                            if (sessionActive && mode != 2) {
                                param.args[1] = 2;
                                XposedBridge.log(TAG + ": forced notifyHFRmode -> HIGH(2)");
                            }
                        }
                    });
                    XposedBridge.log(TAG + ": notifyHFRmode hook installed");
                    break;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": notifyHFRmode hook setup failed: " + t);
        }
    }

    private static boolean isRefreshRateLimitPriority(int priority) {
        switch (priority) {
            case 15: // PRIORITY_SYNCHRONIZED_REFRESH_RATE
            case 16: // PRIORITY_SYNCHRONIZED_RENDER_FRAME_RATE
            case 17: // PRIORITY_LIMIT_MODE
            case 22: // PRIORITY_LOW_POWER_MODE_MODES
                return true;
            default:
                return false;
        }
    }

    // Returns the mode id of the highest-refresh-rate mode of the given
    // display, or null when it cannot be resolved. Mirrors defaultModeId
    // semantics on this device (mode 1 = 120 Hz).
    private static Integer findHighestRefreshModeId(ClassLoader cl, int displayId) {
        try {
            Class<?> displayManagerGlobal = Class.forName(
                    "android.hardware.display.DisplayManagerGlobal", false, cl);
            Object global = displayManagerGlobal.getMethod("getInstance").invoke(null);
            Object info = displayManagerGlobal.getMethod(
                    "getDisplayInfo", int.class).invoke(global, displayId);
            if (info == null) {
                return null;
            }
            Object[] modes = (Object[]) info.getClass().getField("supportedModes").get(info);
            float bestRate = -1f;
            int bestId = -1;
            for (Object mode : modes) {
                float rate = (Float) mode.getClass().getMethod("getRefreshRate").invoke(mode);
                if (rate > bestRate) {
                    bestRate = rate;
                    bestId = (Integer) mode.getClass().getMethod("getModeId").invoke(mode);
                }
            }
            return bestRate >= 119f ? bestId : null;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": findHighestRefreshModeId failed: " + t);
            return null;
        }
    }

    private static void installFakeScreenHooks(ClassLoader cl) {
        try {
            // Hooks are always installed; the runtime checks read the props on
            // every power press, matching NeverSleep's per-press pref reload.
            Class<?> pwm = XposedHelpers.findClass(
                    "com.android.server.policy.PhoneWindowManager", cl);
            for (Method method : pwm.getDeclaredMethods()) {
                if ("powerPress".equals(method.getName())) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!fakeScreenEnabled() || !sessionActiveEnabled()) {
                                return;
                            }
                            IBinder token = fakeScreenDisplayToken(cl);
                            if (token == null) {
                                XposedBridge.log(TAG + ": fake screen no display token");
                                return;
                            }
                            Class<?> surfaceControl = Class.forName(
                                    "android.view.SurfaceControl", false, cl);
                            Method setPower = surfaceControl.getMethod(
                                    "setDisplayPowerMode", IBinder.class, int.class);
                            setPower.invoke(null, token, fakeScreenPowerMode);
                            fakeScreenPowerMode = fakeScreenPowerMode == 0 ? 2 : 0;
                            param.setResult(null);
                            XposedBridge.log(TAG + ": fake screen power toggled to "
                                    + fakeScreenPowerMode);
                        }
                    });
                }
            }

            Class<?> pms = XposedHelpers.findClass(
                    "com.android.server.power.PowerManagerService", cl);
            for (Method method : pms.getDeclaredMethods()) {
                if ("updateUserActivitySummaryLocked".equals(method.getName())) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (preventSleepEnabled()) {
                                param.setResult(null);
                            }
                        }
                    });
                }
            }
            XposedBridge.log(TAG + ": fake screen hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": fake screen hook setup failed: " + t);
        }
    }

    private static boolean fakeScreenEnabled() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String value = (String) get.invoke(null, "persist.dex.lspmirror.fake_screen", "0");
            return "1".equals(value);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean preventSleepEnabled() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String value = (String) get.invoke(
                    null, "persist.dex.lspmirror.prevent_sleep", "0");
            return "1".equals(value);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean sessionActiveEnabled() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String value = (String) get.invoke(
                    null, "dex.lspmirror.session_active", "0");
            return "1".equals(value);
        } catch (Throwable t) {
            return false;
        }
    }

    private static IBinder fakeScreenDisplayToken(ClassLoader cl) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                Class<?> dc = Class.forName(
                        "com.android.server.display.DisplayControl", false, cl);
                long[] ids = (long[]) dc.getMethod("getPhysicalDisplayIds").invoke(null);
                if (ids != null && ids.length > 0) {
                    return (IBinder) dc.getMethod("getPhysicalDisplayToken", long.class)
                            .invoke(null, ids[0]);
                }
                return null;
            }
            Class<?> sc = Class.forName("android.view.SurfaceControl", false, cl);
            if (Build.VERSION.SDK_INT >= 29) {
                return (IBinder) sc.getMethod("getInternalDisplayToken").invoke(null);
            }
            return (IBinder) sc.getMethod("getBuiltInDisplay", int.class).invoke(null, 0);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": fakeScreenDisplayToken failed: " + t);
            return null;
        }
    }

    private static void cleanupStaleDpStateAtBoot() {
        if (bootDpStateCleanupDone) {
            return;
        }
        bootDpStateCleanupDone = true;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method set = sp.getMethod("set", String.class, String.class);
            set.invoke(null, "persist.dex.lspmirror.dp_display_id", "");
            set.invoke(null, "dex.lspmirror.dp_session_active", "");
            set.invoke(null, "dex.lspmirror.session_active", "");
            set.invoke(null, "persist.dex.lspmirror.session_active", "0");
            XposedBridge.log(TAG + ": boot cleanup cleared dp display property");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": boot cleanup property failed: " + t);
        }
        try {
            android.content.ContentResolver resolver = systemContextContentResolver();
            if (resolver != null) {
                for (String key : new String[]{
                        "libredex_dp_display_id", "libredex_dp_managed_display"}) {
                    resolver.delete(android.provider.Settings.Global.getUriFor(key), null, null);
                }
                XposedBridge.log(TAG + ": boot cleanup cleared dp display settings");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": boot cleanup settings failed: " + t);
        }
    }

    private static android.content.ContentResolver systemContextContentResolver() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke(null);
            Object systemContext = atClass.getMethod("getSystemContext").invoke(at);
            return (android.content.ContentResolver) systemContext.getClass()
                    .getMethod("getContentResolver").invoke(systemContext);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean aggressiveHooksEnabled() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String value = (String) get.invoke(null, "persist.dex.lspmirror.aggressive_hooks", "0");
            return value != null && "1".equals(value.trim());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isDpSessionActiveThisBoot() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String value = (String) get.invoke(null, "dex.lspmirror.dp_session_active", "");
            return value != null && "1".equals(value.trim());
        } catch (Throwable t) {
            return false;
        }
    }

    private static int configuredDpDisplayId() {
        if (!isDpSessionActiveThisBoot()) {
            return -1;
        }
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String value = (String) get.invoke(null, "persist.dex.lspmirror.dp_display_id", "");
            if (value != null && !value.trim().isEmpty()) {
                return Integer.parseInt(value.trim());
            }
        } catch (Throwable t) {
            // Property not configured or unreadable; treat as disabled.
        }
        try {
            android.content.ContentResolver resolver = null;
            try {
                Class<?> atClass = Class.forName("android.app.ActivityThread");
                Object at = atClass.getMethod("currentActivityThread").invoke(null);
                Object systemContext = at.getClass().getMethod("getSystemContext").invoke(at);
                resolver = (android.content.ContentResolver) systemContext.getClass()
                        .getMethod("getContentResolver").invoke(systemContext);
            } catch (Throwable ignored) {
            }
            if (resolver != null) {
                String value = android.provider.Settings.Global.getString(
                        resolver, "libredex_dp_display_id");
                if (value != null && !value.trim().isEmpty()) {
                    return Integer.parseInt(value.trim());
                }
            }
        } catch (Throwable t) {
            // Global setting unavailable; fall through to disabled.
        }
        return -1;
    }

    private static void unmarkHomeSupported(int displayId) {
        try {
            ClassLoader cl = systemServerClassLoader;
            if (cl == null) {
                cl = DexLayerStackHook.class.getClassLoader();
            }
            Class<?> localServices = Class.forName("com.android.server.LocalServices", false, cl);
            Class<?> wmInternal = Class.forName(
                    "com.android.server.wm.WindowManagerInternal", false, cl);
            Object wmService = localServices.getMethod("getService", Class.class)
                    .invoke(null, wmInternal);
            installHomeSupportGuard(wmService);
            wmService.getClass().getMethod(
                    "setHomeSupportedOnDisplay", String.class, int.class, boolean.class)
                    .invoke(wmService, "com.sec.android.app.launcher", displayId, Boolean.FALSE);
            XposedBridge.log(TAG + ": home supported cleared displayId=" + displayId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": home supported clear failed displayId=" + displayId + " " + t);
        }
    }

    private static void installHomeSupportGuard(Object wmService) {
        if (homeSupportGuardInstalled || wmService == null) {
            return;
        }
        Method setHome = null;
        for (Method m : wmService.getClass().getDeclaredMethods()) {
            if ("setHomeSupportedOnDisplay".equals(m.getName())
                    && m.getParameterTypes().length == 3) {
                setHome = m;
                break;
            }
        }
        if (setHome == null) {
            return;
        }
        setHome.setAccessible(true);
        XposedBridge.hookMethod(setHome, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                int displayId = (Integer) param.args[1];
                boolean value = (Boolean) param.args[2];
                if (value && dpMirrorGuardArmed
                        && configuredDpDisplayId() < 0
                        && displayId == dpExternalStackId) {
                    param.args[2] = Boolean.FALSE;
                    XposedBridge.log(TAG + ": home support re-add blocked displayId="
                            + displayId);
                }
            }
        });
        homeSupportGuardInstalled = true;
        XposedBridge.log(TAG + ": home support guard installed");
    }

    private static void scheduleWallpaperAttach(ClassLoader cl, int displayId) {
        for (int i = 0; i < 3; i++) {
            final int attempt = i;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (WALLPAPER_ATTACH_DONE.contains(displayId)
                        || !isActiveDexDisplay(displayId)) {
                    return;
                }
                forceWallpaperAttach(cl, displayId, attempt);
            }, 2000L + 2000L * attempt);
        }
    }

    // The fake virtual display is never seen by the official DeX attach path,
    // so WMS never attaches a wallpaper window. Mark it home-capable and call
    // the wallpaper service's display-added callback directly.
    private static void forceWallpaperAttach(ClassLoader cl, int displayId, int attempt) {
        if (!isActiveDexDisplay(displayId)) {
            XposedBridge.log(TAG + ": wallpaper attach skipped (inactive) displayId="
                    + displayId);
            return;
        }
        try {
            Class<?> localServices = Class.forName(
                    "com.android.server.LocalServices", false, cl);
            Class<?> wmInternal = Class.forName(
                    "com.android.server.wm.WindowManagerInternal", false, cl);
            Method getService = localServices.getMethod("getService", Class.class);
            Object wmService = getService.invoke(null, wmInternal);
            if (wmService != null) {
                wmService.getClass().getMethod(
                        "setHomeSupportedOnDisplay", String.class, int.class, boolean.class)
                        .invoke(wmService, "com.sec.android.app.launcher", displayId, Boolean.TRUE);
                XposedBridge.log(TAG + ": home supported forced displayId=" + displayId);
            }

            Class<?> wallpaperLocal = Class.forName(
                    "com.android.server.wallpaper.WallpaperManagerService$LocalService",
                    false, cl);
            Object wallpaperService = getService.invoke(null, wallpaperLocal);
            if (wallpaperService != null) {
                wallpaperLocal.getMethod(
                        "onDisplayAddSystemDecorations", int.class)
                        .invoke(wallpaperService, displayId);
                WALLPAPER_ATTACH_DONE.add(displayId);
                XposedBridge.log(TAG + ": wallpaper attach triggered displayId=" + displayId
                        + " attempt=" + attempt);
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            XposedBridge.log(TAG + ": forceWallpaperAttach failed displayId=" + displayId
                    + " attempt=" + attempt + ": " + cause);
        }
    }

}

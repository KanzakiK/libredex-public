package com.dex.mirror;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.media.ImageReader;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class MirrorTest {

    private static final String PACKAGE_NAME = "com.android.shell";

    public static void main(String[] args) throws Exception {
        int displayId = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int width = args.length > 1 ? Integer.parseInt(args[1]) : 1920;
        int height = args.length > 2 ? Integer.parseInt(args[2]) : 1080;
        int density = args.length > 3 ? Integer.parseInt(args[3]) : 240;
        int holdSeconds = args.length > 4 ? Integer.parseInt(args[4]) : 30;
        String mode = args.length > 5 ? args[5] : "vd";

        if ("methods".equals(mode)) {
            dumpMethods();
            return;
        }

        prepareMainLooper();
        prepareActivityThread();

        Context fakeContext = new FakeContext(getSystemContext());
        DisplayManager dm = createDisplayManager(fakeContext);

        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        Surface surface = reader.getSurface();
        final long[] frameCount = {0L};
        Handler handler = new Handler(Looper.getMainLooper());
        reader.setOnImageAvailableListener(r -> {
            try (android.media.Image image = r.acquireLatestImage()) {
                if (image != null) {
                    frameCount[0]++;
                }
            } catch (Throwable ignored) {
            }
        }, handler);

        if ("sc".equals(mode)) {
            runSurfaceControlMirror(dm, displayId, width, height, surface, holdSeconds, frameCount, density);
            reader.close();
            return;
        }

        if ("fixstack".equals(mode)) {
            VirtualDisplay mirrorVd = createMirrorViaHiddenApi(
                    "dex-anywhere-mirror-test", width, height, displayId, surface);
            log("hidden API created displayId=" + mirrorVd.getDisplay().getDisplayId());
            fixDisplayLayerStack(dm, mirrorVd.getDisplay(), displayId);
            log("fixed layer stack to source=" + displayId);
            for (int i = 1; i <= holdSeconds; i++) {
                Thread.sleep(1000L);
                log("t+" + i + "s frames=" + frameCount[0]);
            }
            mirrorVd.release();
            reader.close();
            return;
        }

        if ("wmmirror".equals(mode)) {
            testWindowManagerMirror(displayId);
            return;
        }

        if ("showmirror".equals(mode)) {
            testWindowManagerMirrorOnPrimary(displayId, holdSeconds);
            return;
        }

        if ("writemirror".equals(mode)) {
            writeMirrorSurface(displayId, holdSeconds);
            return;
        }

        if ("token".equals(mode)) {
            testDisplayToken(displayId);
            return;
        }

        VirtualDisplay vd = null;
        try {
            vd = createMirrorViaHiddenApi("dex-anywhere-mirror-test", width, height, displayId, surface);
            log("hidden createVirtualDisplay API succeeded");
        } catch (Throwable hiddenError) {
            log("hidden API failed: " + hiddenError);
            VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                    "dex-anywhere-mirror-test", width, height, density)
                    .setSurface(surface)
                    .setFlags(0x10  // AUTO_MIRROR
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                            | 0x40   // SUPPORTS_TOUCH
                            | 0x80   // ROTATES_WITH_CONTENT
                            | 0x200  // SHOULD_SHOW_SYSTEM_DECORATIONS
                            | 0x400  // TRUSTED
                            | 0x800  // OWN_DISPLAY_GROUP
                            | 0x1000 // ALWAYS_UNLOCKED
                            | 0x2000 // TOUCH_FEEDBACK_DISABLED
                            | 0x4000 // OWN_FOCUS -> FLAG_EXTERNAL_DEX_HOSTING
                            | 0x8000 // DEVICE_DISPLAY_GROUP
                            | 0x10000); // STEAL_TOP_FOCUS_DISABLED
            setDisplayIdToMirror(builder, displayId);
            VirtualDisplayConfig config = builder.build();
            vd = dm.createVirtualDisplay(config, handler, new VirtualDisplay.Callback() {
                @Override public void onPaused() { log("onPaused"); }
                @Override public void onResumed() { log("onResumed"); }
                @Override public void onStopped() { log("onStopped"); }
            });
        }

        log("created displayId=" + (vd == null ? "null" : vd.getDisplay().getDisplayId())
                + " mirror=" + displayId + " size=" + width + "x" + height);

        for (int i = 1; i <= holdSeconds; i++) {
            Thread.sleep(1000L);
            log("t+" + i + "s frames=" + frameCount[0]);
        }
        log("releasing after " + holdSeconds + "s");
        if (vd != null) vd.release();
        reader.close();
    }

    private static DisplayManager createDisplayManager(Context context) throws Exception {
        Constructor<DisplayManager> ctor =
                DisplayManager.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        return ctor.newInstance(context);
    }

    private static void prepareMainLooper() {
        try {
            Looper.prepareMainLooper();
        } catch (Throwable ignored) {
        }
    }

    private static void prepareActivityThread() throws Exception {
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = atClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object activityThread = ctor.newInstance();
        Field current = atClass.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, activityThread);
        try {
            Class<?> configClass = Class.forName("android.app.ConfigurationController");
            Class<?> internalClass = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> configCtor = configClass.getDeclaredConstructor(internalClass);
            configCtor.setAccessible(true);
            Object configController = configCtor.newInstance(activityThread);
            Field configField = atClass.getDeclaredField("mConfigurationController");
            configField.setAccessible(true);
            configField.set(activityThread, configController);
        } catch (Throwable ignored) {
        }
    }

    private static Context getSystemContext() throws Exception {
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Field currentField = atClass.getDeclaredField("sCurrentActivityThread");
        currentField.setAccessible(true);
        Object activityThread = currentField.get(null);
        Method method = atClass.getDeclaredMethod("getSystemContext");
        method.setAccessible(true);
        return (Context) method.invoke(activityThread);
    }

    private static void log(String msg) {
        System.out.println("[MirrorTest] " + msg);
        System.out.flush();
    }

    private static void dumpMethods() {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            log("SurfaceControl: " + sc.getClassLoader());
            for (java.lang.reflect.Method m : sc.getDeclaredMethods()) {
                if (m.getName().toLowerCase().contains("display")
                        || m.getName().toLowerCase().contains("transaction")
                        || m.getName().contains("mirror")) {
                    log("SC." + m.toGenericString());
                }
            }
        } catch (Throwable t) {
            log("SurfaceControl dump failed: " + t);
        }
        try {
            Class<?> txn = Class.forName("android.view.SurfaceControl$Transaction");
            log("Transaction methods:");
            for (java.lang.reflect.Method m : txn.getDeclaredMethods()) {
                if (m.getName().toLowerCase().contains("display")) {
                    log("TXN." + m.toGenericString());
                }
            }
        } catch (Throwable t) {
            log("Transaction dump failed: " + t);
        }
        try {
            Class<?> dc = Class.forName("com.android.server.display.DisplayControl");
            log("DisplayControl via boot classpath: " + dc);
            for (java.lang.reflect.Method m : dc.getDeclaredMethods()) {
                log("DC." + m.toGenericString());
            }
        } catch (Throwable t) {
            log("DisplayControl boot classpath failed: " + t);
            try {
                dalvik.system.PathClassLoader loader = new dalvik.system.PathClassLoader(
                        "/system/framework/services.jar",
                        Class.forName("android.view.SurfaceControl").getClassLoader());
                Class<?> dc = Class.forName("com.android.server.display.DisplayControl", true, loader);
                log("DisplayControl via PathClassLoader: " + dc);
                for (java.lang.reflect.Method m : dc.getDeclaredMethods()) {
                    log("DC." + m.toGenericString());
                }
            } catch (Throwable t2) {
                log("DisplayControl PathClassLoader failed: " + t2);
            }
        }
    }

    private static void testWindowManagerMirror(int displayId) throws Exception {
        Class<?> wmGlobal = Class.forName("android.view.WindowManagerGlobal");
        Method getWms = wmGlobal.getMethod("getWindowManagerService");
        Object wms = getWms.invoke(null);
        Class<?> iwm = Class.forName("android.view.IWindowManager");
        Class<?> scClass = Class.forName("android.view.SurfaceControl");
        Method mirror = iwm.getMethod("mirrorDisplay", int.class, scClass);
        Object out = scClass.getConstructor().newInstance();
        boolean ok = (Boolean) mirror.invoke(wms, displayId, out);
        Method isValid = scClass.getMethod("isValid");
        log("mirrorDisplay displayId=" + displayId + " ok=" + ok
                + " isValid=" + isValid.invoke(out) + " sc=" + out);
        if ((Boolean) isValid.invoke(out)) {
            Class<?> txnClass = Class.forName("android.view.SurfaceControl$Transaction");
            Object txn = txnClass.getConstructor().newInstance();
            txnClass.getMethod("setPosition", scClass, float.class, float.class)
                    .invoke(txn, out, 0f, 0f);
            txnClass.getMethod("apply").invoke(txn);
            Thread.sleep(2000L);
            txnClass.getMethod("remove", scClass).invoke(txn, out);
            txnClass.getMethod("apply").invoke(txn);
            log("removed mirror surface");
        }
    }

    private static void testWindowManagerMirrorOnPrimary(int displayId, int holdSeconds) throws Exception {
        Class<?> wmGlobal = Class.forName("android.view.WindowManagerGlobal");
        Method getWms = wmGlobal.getMethod("getWindowManagerService");
        Object wms = getWms.invoke(null);
        Class<?> scClass = Class.forName("android.view.SurfaceControl");
        Class<?> iwm = Class.forName("android.view.IWindowManager");
        Method mirror = iwm.getMethod("mirrorDisplay", int.class, scClass);
        Object out = scClass.getConstructor().newInstance();
        boolean ok = (Boolean) mirror.invoke(wms, displayId, out);
        Method isValid = scClass.getMethod("isValid");
        boolean valid = (Boolean) isValid.invoke(out);
        log("showmirror displayId=" + displayId + " ok=" + ok + " valid=" + valid);
        if (!ok || !valid) {
            return;
        }
        Class<?> txnClass = Class.forName("android.view.SurfaceControl$Transaction");
        Object txn = txnClass.getConstructor().newInstance();
        txnClass.getMethod("reparent", scClass, scClass).invoke(txn, out, null);
        txnClass.getMethod("setLayer", scClass, int.class).invoke(txn, out, 1000000);
        txnClass.getMethod("setLayerStack", scClass, int.class).invoke(txn, out, 0);
        txnClass.getMethod("setPosition", scClass, float.class, float.class)
                .invoke(txn, out, 0f, 0f);
        txnClass.getMethod("setBufferSize", scClass, int.class, int.class)
                .invoke(txn, out, 1080, 2640);
        txnClass.getMethod("show", scClass).invoke(txn, out);
        txnClass.getMethod("apply").invoke(txn);
        log("showmirror applied, holding " + holdSeconds + "s");
        for (int i = 1; i <= holdSeconds; i++) {
            Thread.sleep(1000L);
            log("hold t+" + i + "s");
        }
        Object remove = txnClass.getConstructor().newInstance();
        txnClass.getMethod("reparent", scClass, scClass).invoke(remove, out, null);
        txnClass.getMethod("hide", scClass).invoke(remove, out);
        txnClass.getMethod("apply").invoke(remove);
        scClass.getMethod("release").invoke(out);
        log("showmirror removed");
    }

    private static void writeMirrorSurface(int displayId, int holdSeconds) throws Exception {
        Class<?> wmGlobal = Class.forName("android.view.WindowManagerGlobal");
        Method getWms = wmGlobal.getMethod("getWindowManagerService");
        Object wms = getWms.invoke(null);
        Class<?> scClass = Class.forName("android.view.SurfaceControl");
        Class<?> iwm = Class.forName("android.view.IWindowManager");
        Method mirror = iwm.getMethod("mirrorDisplay", int.class, scClass);
        Object out = scClass.getConstructor().newInstance();
        boolean ok = (Boolean) mirror.invoke(wms, displayId, out);
        Method isValid = scClass.getMethod("isValid");
        boolean valid = (Boolean) isValid.invoke(out);
        log("writemirror displayId=" + displayId + " ok=" + ok + " valid=" + valid);
        log("step1 mirrorDisplay done");
        if (!ok || !valid) {
            return;
        }
        log("step2 obtaining parcel");
        android.os.Parcel p = android.os.Parcel.obtain();
        log("step3 parcel obtained");
        scClass.getMethod("writeToParcel", android.os.Parcel.class, int.class)
                .invoke(out, p, 0);
        log("step4 writeToParcel done size=" + p.dataSize());
        log("step5a before marshall");
        byte[] bytes = p.marshall();
        log("step5 marshall done len=" + bytes.length);
        log("step5b before recycle");
        p.recycle();
        log("step6 writing file");
        java.io.FileOutputStream fos = new java.io.FileOutputStream("/data/local/tmp/dex_mirror_surface.bin");
        fos.write(bytes);
        fos.close();
        log("step7 file written");
        log("step8 before sleep");
        log("wrote " + bytes.length + " bytes to /data/local/tmp/dex_mirror_surface.bin");
        log("holding mirror alive " + holdSeconds + "s");
        for (int i = 1; i <= holdSeconds; i++) {
            Thread.sleep(1000L);
        }
        scClass.getMethod("release").invoke(out);
        log("released mirror");
    }

    private static void testDisplayToken(int displayId) throws Exception {
        Class<?> scClass = Class.forName("android.view.SurfaceControl");
        for (java.lang.reflect.Method m : scClass.getDeclaredMethods()) {
            if (m.getName().toLowerCase().contains("displaytoken")
                    || m.getName().toLowerCase().contains("physicaldisplay")) {
                log("SC method: " + m.toGenericString());
            }
        }
        try {
            Method m = scClass.getMethod("getDisplayToken", long.class);
            Object token = m.invoke(null, (long) displayId);
            log("getDisplayToken(long) displayId=" + displayId + " token=" + token);
        } catch (Throwable t) {
            log("getDisplayToken(long) failed: " + t);
        }
        try {
            Method m = scClass.getMethod("getDisplayToken", Class.forName("android.view.DisplayAddress"));
            Object addr = Class.forName("android.view.DisplayAddress")
                    .getMethod("fromPhysicalDisplayId", long.class)
                    .invoke(null, (long) displayId);
            Object token = m.invoke(null, addr);
            log("getDisplayToken(DisplayAddress) physicalId=" + displayId + " token=" + token);
        } catch (Throwable t) {
            log("getDisplayToken(DisplayAddress) failed: " + t);
        }
    }

    private static void runSurfaceControlMirror(
            DisplayManager dm,
            int displayId,
            int width,
            int height,
            Surface surface,
            int holdSeconds,
            long[] frameCount,
            int density) throws Exception {
        Class<?> dcClass = loadDisplayControl();
        log("DisplayControl loaded: " + dcClass.getName());
        Method create = dcClass.getMethod("createVirtualDisplay", String.class, boolean.class);
        IBinder token = (IBinder) create.invoke(null, "dex-anywhere-sc-mirror", false);
        log("display token created: " + token);
        int layerStack = getLayerStack(dm, displayId);
        log("source layerStack=" + layerStack);

        Class<?> txnClass = Class.forName("android.view.SurfaceControl$Transaction");
        Object txn = txnClass.getConstructor().newInstance();
        Rect rect = new Rect(0, 0, width, height);
        txnClass.getMethod("setDisplaySurface", IBinder.class, Surface.class)
                .invoke(txn, token, surface);
        txnClass.getMethod("setDisplaySize", IBinder.class, int.class, int.class)
                .invoke(txn, token, width, height);
        txnClass.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                .invoke(txn, token, 0, rect, rect);
        txnClass.getMethod("setDisplayLayerStack", IBinder.class, int.class)
                .invoke(txn, token, layerStack);
        log("transaction built, applying");
        txnClass.getMethod("apply", boolean.class).invoke(txn, true);
        log("transaction applied");
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        sc.getMethod("setDisplayPowerMode", IBinder.class, int.class).invoke(null, token, 2);
        log("SurfaceControl mirror created token=" + token + " layerStack=" + layerStack
                + " size=" + width + "x" + height);
        for (int i = 1; i <= holdSeconds; i++) {
            Thread.sleep(1000L);
            log("t+" + i + "s frames=" + frameCount[0]);
        }
        dcClass.getMethod("destroyVirtualDisplay", IBinder.class).invoke(null, token);
    }

    private static Class<?> loadDisplayControl() throws Exception {
        try {
            return Class.forName("com.android.server.display.DisplayControl");
        } catch (ClassNotFoundException e) {
            dalvik.system.PathClassLoader loader = new dalvik.system.PathClassLoader(
                    "/system/framework/services.jar",
                    Class.forName("android.view.SurfaceControl").getClassLoader());
            return Class.forName("com.android.server.display.DisplayControl", true, loader);
        }
    }

    private static int getLayerStack(DisplayManager dm, int displayId) throws Exception {
        log("getLayerStack(" + displayId + ") start");
        Class<?> infoClass = Class.forName("android.view.DisplayInfo");
        Object info = infoClass.getConstructor().newInstance();
        Display display = dm.getDisplay(displayId);
        Method getInfoMethod = Display.class.getMethod("getDisplayInfo", infoClass);
        log("getLayerStack got display=" + display + " method=" + getInfoMethod);
        if (display == null || !((Boolean) getInfoMethod.invoke(display, info))) {
            throw new IllegalStateException("cannot read source display " + displayId);
        }
        int ls = infoClass.getField("layerStack").getInt(info);
        log("getLayerStack result=" + ls);
        return ls;
    }

    private static void fixDisplayLayerStack(DisplayManager dm, Display display, int sourceDisplayId)
            throws Exception {
        log("fixDisplayLayerStack start");
        Method getAddress = Display.class.getMethod("getAddress");
        Object address = getAddress.invoke(display);
        log("display address=" + address);
        if (address == null) {
            throw new IllegalStateException("display has no address");
        }
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        Method getToken = sc.getMethod("getDisplayToken", Class.forName("android.view.DisplayAddress"));
        IBinder token = (IBinder) getToken.invoke(null, address);
        log("display token=" + token);
        int layerStack = getLayerStack(dm, sourceDisplayId);
        log("source layerStack=" + layerStack);
        Class<?> txnClass = Class.forName("android.view.SurfaceControl$Transaction");
        Object txn = txnClass.getConstructor().newInstance();
        txnClass.getMethod("setDisplayLayerStack", IBinder.class, int.class)
                .invoke(txn, token, layerStack);
        log("transaction set, applying");
        txnClass.getMethod("apply", boolean.class).invoke(txn, false);
        log("transaction applied");
        log("fixDisplayLayerStack token=" + token + " newLayerStack=" + layerStack);
    }

    private static void setDisplayIdToMirror(VirtualDisplayConfig.Builder builder, int displayId)
            throws Exception {
        Method method = builder.getClass().getDeclaredMethod("setDisplayIdToMirror", int.class);
        method.setAccessible(true);
        method.invoke(builder, displayId);
    }

    private static VirtualDisplay createMirrorViaHiddenApi(
            String name, int width, int height, int displayId, Surface surface) throws Exception {
        Method method = DisplayManager.class.getMethod(
                "createVirtualDisplay", String.class, int.class, int.class, int.class, Surface.class);
        return (VirtualDisplay) method.invoke(null, name, width, height, displayId, surface);
    }

    private static final class FakeContext extends ContextWrapper {
        FakeContext(Context base) {
            super(base);
        }

        @Override
        public String getPackageName() {
            return PACKAGE_NAME;
        }

        @Override
        public String getOpPackageName() {
            return PACKAGE_NAME;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }
    }
}

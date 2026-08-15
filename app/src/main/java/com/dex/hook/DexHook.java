package com.dex.hook;

import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 并入 APP 的 DexHook Settings 入口 hooks（5 个）。
 * 原 DexBootHook / DexPowerHook（电源键/防休眠）按产品计划删除。
 */
public final class DexHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.contains("settings")) {
            return;
        }
        ClassLoader cl = lpparam.classLoader;
        hookRuneMethod(cl, "supportDesktopMode");
        hookRuneMethod(cl, "isSamsungDexMode", Context.class);
        hookControllerAvailability(cl,
                "com.samsung.android.settings.homepage.TopLevelDexSettingsController");
        hookControllerAvailability(cl,
                "com.samsung.android.settings.multidevices.dex.DexModePreferenceController");
        hookBasePreferenceController(cl);
        XposedBridge.log("DexHook: all 5 settings hooks installed");
    }

    private static void hookRuneMethod(ClassLoader cl, String name, Class<?>... parameterTypes) {
        try {
            Class<?> rune = XposedHelpers.findClass("com.samsung.android.settings.Rune", cl);
            XposedBridge.hookMethod(
                    rune.getDeclaredMethod(name, parameterTypes),
                    new DexCallback(DexCallback.MODE_RUNE_BOOLEAN));
            XposedBridge.log("DexHook: " + name + " hooked");
        } catch (Throwable t) {
            XposedBridge.log("DexHook: " + name + " hook failed: " + t);
        }
    }

    private static void hookControllerAvailability(ClassLoader cl, String className) {
        try {
            Class<?> controller = XposedHelpers.findClass(className, cl);
            XposedBridge.hookMethod(
                    controller.getDeclaredMethod("getAvailabilityStatus"),
                    new DexCallback(DexCallback.MODE_AVAILABILITY_INT));
            XposedBridge.log("DexHook: " + className + " hooked");
        } catch (Throwable t) {
            XposedBridge.log("DexHook: " + className + " hook failed: " + t);
        }
    }

    private static void hookBasePreferenceController(ClassLoader cl) {
        try {
            Class<?> base = XposedHelpers.findClass(
                    "com.android.settings.core.BasePreferenceController", cl);
            XposedBridge.hookMethod(
                    base.getDeclaredMethod("isAvailable"),
                    new DexCallback(DexCallback.MODE_IS_AVAILABLE_CACHE));
            XposedBridge.log("DexHook: BasePreferenceController.isAvailable hooked");
        } catch (Throwable t) {
            XposedBridge.log("DexHook: BasePreferenceController.isAvailable hook failed: " + t);
        }
    }
}

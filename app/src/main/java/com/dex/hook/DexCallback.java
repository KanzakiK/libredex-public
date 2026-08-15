package com.dex.hook;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Settings 入口 hook 回调。行为对齐 archive/DexHook README 语义：
 * 0 -> getAvailabilityStatus 返回 0（AVAILABLE）；1 -> Rune 布尔返回 TRUE；
 * 3 -> 仅对类名含 DexMode 的 controller 修复缓存并返回 TRUE。
 */
final class DexCallback extends XC_MethodHook {

    static final int MODE_AVAILABILITY_INT = 0;
    static final int MODE_RUNE_BOOLEAN = 1;
    static final int MODE_IS_AVAILABLE_CACHE = 3;

    private final int mode;

    DexCallback(int mode) {
        this.mode = mode;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        if (mode == MODE_IS_AVAILABLE_CACHE) {
            Object self = param.thisObject;
            if (self == null || !self.getClass().getName().contains("DexMode")) {
                return;
            }
            XposedBridge.log("DexHook cache-fix for: " + self.getClass().getName());
            fixLastAvailableCache(self, "mLastAvailableChecked");
            fixLastAvailableCache(self, "mLastAvailable");
            param.setResult(Boolean.TRUE);
            return;
        }
        if (mode == MODE_AVAILABILITY_INT) {
            param.setResult(0);
        } else {
            param.setResult(Boolean.TRUE);
        }
    }

    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        if (mode != MODE_RUNE_BOOLEAN) {
            return;
        }
        Object self = param.thisObject;
        if (self == null || !self.getClass().getName().contains("DexMode")) {
            return;
        }
        XposedBridge.log("DexHook after isAvailable: " + self.getClass().getName());
        param.setResult(Boolean.TRUE);
    }

    private static void fixLastAvailableCache(Object self, String fieldName) {
        try {
            Field field = Class.forName("com.android.settings.core.BasePreferenceController")
                    .getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setBoolean(self, true);
        } catch (Exception ignored) {
            // 缓存字段在不同固件上可能不存在，忽略即可
        }
    }
}

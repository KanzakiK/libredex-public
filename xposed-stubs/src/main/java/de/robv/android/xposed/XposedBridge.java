package de.robv.android.xposed;

import java.lang.reflect.Member;

public final class XposedBridge {
    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    public static XC_MethodHook.Unhook hookMethod(Member member, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }
}

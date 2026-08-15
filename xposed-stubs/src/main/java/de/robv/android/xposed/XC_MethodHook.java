package de.robv.android.xposed;

public class XC_MethodHook {
    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object result;
        public java.lang.reflect.Member method;

        public void setResult(Object result) {
            this.result = result;
        }

        public void setResult(boolean result) {
            this.result = result;
        }

        public void setResult(int result) {
            this.result = result;
        }
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static class Unhook {
    }
}

package com.connect_screen.mirror;

import android.content.Context;
import android.content.SharedPreferences;

public class Pref {
    private static volatile Context appContext;
    public static final String PREF_NAME = "mirror_settings";

    public static final String KEY_AUTO_ROTATE = "auto_rotate";
    public static final String KEY_AUTO_SCALE = "auto_scale";
    public static final String KEY_SINGLE_APP_MODE = "single_app_mode";
    public static final String KEY_SELECTED_APP_PACKAGE = "selected_app_package";
    public static final String KEY_SELECTED_APP_NAME = "selected_app_name";
    public static final String KEY_AUTO_SCREEN_OFF = "auto_screen_off";
    public static final String KEY_AUTO_BIND_INPUT = "auto_bind_input";
    public static final String KEY_DISABLE_USB_AUDIO = "disable_usb_audio";
    public static final String KEY_AUTO_MATCH_ASPECT_RATIO = "auto_match_aspect_ratio";
    public static final String KEY_SELECTED_CLIENT = "selected_client";
    public static final String KEY_AUTO_CONNECT_CLIENT = "auto_connect_client";
    public static final String KEY_USE_BLACK_IMAGE = "use_black_image";
    public static final String KEY_PREVENT_AUTO_LOCK = "prevent_auto_lock";
    public static final String KEY_FAKE_SCREEN = "fake_screen";
    public static final String KEY_PREVENT_SLEEP = "prevent_sleep";
    public static final String KEY_USE_ANDROID_CURSOR_OVERLAY = "use_android_cursor_overlay";
    public static final String KEY_MAP_MOUSE_TO_TOUCH = "map_mouse_to_touch";
    public static final String KEY_DARK_MODE = "dark_mode";
    public static final String KEY_THEME_MODE = "theme_mode";
    public static final String KEY_DEX_LAYER_FIX = "dex_layer_fix_enabled";
    public static final String KEY_ENCODER_CODEC = "encoder_codec";
    public static final String KEY_ENCODER_BITRATE_PERCENT = "encoder_bitrate_percent";
    public static final String KEY_ENCODER_BITRATE_MODE = "encoder_bitrate_mode";
    public static final String KEY_ENCODER_COMPLEXITY = "encoder_complexity";
    public static final String KEY_ENCODER_I_FRAME_INTERVAL = "encoder_i_frame_interval";
    public static final String KEY_ENCODER_MAX_FPS = "encoder_max_fps";
    public static final String KEY_ENCODER_LOW_LATENCY = "encoder_low_latency";
    public static final String KEY_ENCODER_DISABLE_B_FRAMES = "encoder_disable_b_frames";
    public static final String KEY_ENCODER_REALTIME_PRIORITY = "encoder_realtime_priority";
    public static final String KEY_ENCODER_DYNAMIC_FRAME_RATE = "encoder_dynamic_frame_rate";
    public static final String KEY_STREAM_FEC_PERCENT = "stream_fec_percent";
    public static final String KEY_ENCODER_AVC_BASELINE = "encoder_avc_baseline";
    public static final String KEY_INITIAL_SETUP_COMPLETE = "initial_setup_complete";
    public static final String KEY_FIRST_USE_HIDDEN = "first_use_hidden";
    public static final String KEY_LAST_RUN_VERSION_CODE = "last_run_version_code";
    public static final String KEY_LAST_RUN_COMMIT = "last_run_commit";
    public static final String KEY_DP_SESSION_STARTED = "dp_session_started";
    public static final String KEY_DP_OUTPUT_MODE = "dp_output_mode";
    public static final int ENCODER_CODEC_H264 = 0;
    public static final int ENCODER_CODEC_H265 = 1;
    public static boolean doNotAutoStartMoonlight;

    public static void init(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    public static boolean getAutoRotate() {
        return getBoolean(KEY_AUTO_ROTATE, true);
    }

    public static boolean  getAutoScale() {
        return getBoolean(KEY_AUTO_SCALE, true);
    }

    public static boolean getSingleAppMode() {
        return getBoolean(KEY_SINGLE_APP_MODE, false);
    }

    public static boolean getAutoScreenOff() {
        return getBoolean(KEY_AUTO_SCREEN_OFF, true);
    }

    public static boolean getAutoBindInput() {
        return getBoolean(KEY_AUTO_BIND_INPUT, true);
    }

    public static boolean getDisableUsbAudio() {
        return getBoolean(KEY_DISABLE_USB_AUDIO, false);
    }

    public static boolean getAutoMatchAspectRatio() {
        return getBoolean(KEY_AUTO_MATCH_ASPECT_RATIO, false);
    }

    public static boolean getAutoConnectClient() {
        return getBoolean(KEY_AUTO_CONNECT_CLIENT, false);
    }

    public static String getSelectedAppPackage() {
        return getString(KEY_SELECTED_APP_PACKAGE, "");
    }

    public static String getSelectedClient() {
        return getString(KEY_SELECTED_CLIENT, "");
    }

    public static boolean getUseBlackImage() {
        return getBoolean(KEY_USE_BLACK_IMAGE, true);
    }

    public static boolean getPreventAutoLock() {
        return getBoolean(KEY_PREVENT_AUTO_LOCK, false);
    }

    public static boolean getFakeScreen() {
        return getBoolean(KEY_FAKE_SCREEN, false);
    }

    public static void setFakeScreen(boolean enabled) {
        getPreferences().edit().putBoolean(KEY_FAKE_SCREEN, enabled).apply();
    }

    public static boolean getPreventSleep() {
        return getBoolean(KEY_PREVENT_SLEEP, false);
    }

    public static void setPreventSleep(boolean enabled) {
        getPreferences().edit().putBoolean(KEY_PREVENT_SLEEP, enabled).apply();
    }

    public static boolean getUseAndroidCursorOverlay() {
        return getBoolean(KEY_USE_ANDROID_CURSOR_OVERLAY, false);
    }

    public static boolean getMapMouseToTouch() {
        return getBoolean(KEY_MAP_MOUSE_TO_TOUCH, false);
    }

    public static boolean getDarkMode() {
        return getBoolean(KEY_DARK_MODE, false);
    }

    public static String getThemeMode() {
        return getString(KEY_THEME_MODE, "light");
    }

    public static void setThemeMode(String mode) {
        SharedPreferences preferences = getPreferences();
        if (preferences != null) {
            preferences.edit().putString(KEY_THEME_MODE, mode).apply();
        }
    }

    public static boolean getDexLayerFixEnabled() {
        return getBoolean(KEY_DEX_LAYER_FIX, true);
    }

    public static int getEncoderCodec() {
        return getInt(KEY_ENCODER_CODEC, ENCODER_CODEC_H264);
    }

    public static int getEncoderBitratePercent() {
        return getInt(KEY_ENCODER_BITRATE_PERCENT, 100);
    }

    public static int getEncoderBitrateMode() {
        return getInt(KEY_ENCODER_BITRATE_MODE, 2);
    }

    public static int getEncoderComplexity() {
        return getInt(KEY_ENCODER_COMPLEXITY, 5);
    }

    public static int getEncoderIFrameInterval() {
        return getInt(KEY_ENCODER_I_FRAME_INTERVAL, 3);
    }

    public static int getEncoderMaxFps() {
        return getInt(KEY_ENCODER_MAX_FPS, 60);
    }

    public static boolean getEncoderLowLatency() {
        return getBoolean(KEY_ENCODER_LOW_LATENCY, true);
    }

    public static boolean getEncoderDisableBFrames() {
        return getBoolean(KEY_ENCODER_DISABLE_B_FRAMES, true);
    }

    public static boolean getEncoderRealtimePriority() {
        return getBoolean(KEY_ENCODER_REALTIME_PRIORITY, true);
    }

    public static boolean getEncoderAvcBaseline() {
        return getBoolean(KEY_ENCODER_AVC_BASELINE, true);
    }

    public static boolean getEncoderDynamicFrameRate() {
        return getBoolean(KEY_ENCODER_DYNAMIC_FRAME_RATE, false);
    }

    public static int getStreamFecPercent() {
        return getInt(KEY_STREAM_FEC_PERCENT, 0);
    }

    public static boolean isInitialSetupComplete() {
        return getBoolean(KEY_INITIAL_SETUP_COMPLETE, false);
    }

    public static boolean isFirstUseHidden() {
        return getBoolean(KEY_FIRST_USE_HIDDEN, false);
    }

    public static void setFirstUseHidden(boolean hidden) {
        SharedPreferences preferences = getPreferences();
        if (preferences != null) {
            preferences.edit().putBoolean(KEY_FIRST_USE_HIDDEN, hidden).apply();
        }
    }

    public static int getLastRunVersionCode() {
        return getInt(KEY_LAST_RUN_VERSION_CODE, 0);
    }

    public static void setLastRunVersionCode(int versionCode) {
        getPreferences().edit().putInt(KEY_LAST_RUN_VERSION_CODE, versionCode).apply();
    }

    public static String getLastRunCommit() {
        return getString(KEY_LAST_RUN_COMMIT, "");
    }

    public static void setLastRunCommit(String commit) {
        getPreferences().edit().putString(KEY_LAST_RUN_COMMIT, commit).apply();
    }

    public static boolean getDpSessionStarted() {
        return getBoolean(KEY_DP_SESSION_STARTED, false);
    }

    public static void setDpSessionStarted(boolean started) {
        getPreferences().edit().putBoolean(KEY_DP_SESSION_STARTED, started).apply();
    }

    /**
     * The user-chosen wired (DP) output mode "w:h:refresh", used as the DeX
     * render resolution / refresh rate so rendering follows the DP output
     * signal instead of the external panel's native metrics (which can be 4K
     * and is the main source of jank on the wired path). Defaults to 1080p.
     * Returns {width, height, refresh}; 0 tiles mean "not set" (fall back to
     * the external display's native metrics).
     */
    public static int[] getDpOutputMode() {
        String raw = getString(KEY_DP_OUTPUT_MODE, "");
        if (raw == null || raw.trim().isEmpty()) {
            return new int[]{0, 0, 0};
        }
        String[] parts = raw.trim().split(":");
        if (parts.length != 3) {
            return new int[]{0, 0, 0};
        }
        try {
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            int r = Integer.parseInt(parts[2].trim());
            if (w <= 0 || h <= 0 || r <= 0) {
                return new int[]{0, 0, 0};
            }
            return new int[]{w, h, r};
        } catch (NumberFormatException e) {
            return new int[]{0, 0, 0};
        }
    }

    public static void setDpOutputMode(int width, int height, int refresh) {
        getPreferences().edit()
                .putString(KEY_DP_OUTPUT_MODE, width + ":" + height + ":" + refresh)
                .apply();
    }

    public static void setInitialSetupComplete(boolean complete) {
        SharedPreferences preferences = getPreferences();
        if (preferences != null) {
            preferences.edit().putBoolean(KEY_INITIAL_SETUP_COMPLETE, complete).apply();
        }
    }

    private static String getString(String key, String defaultValue) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) {
            return defaultValue;
        }
        return preferences.getString(key, defaultValue);
    }

    private static int getInt(String key, int defaultValue) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) {
            return defaultValue;
        }
        return preferences.getInt(key, defaultValue);
    }

    private static boolean getBoolean(String key, boolean defaultValue) {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) {
            return defaultValue;
        }
        return preferences.getBoolean(key, defaultValue);
    }

    public static SharedPreferences getPreferences() {
        Context context = State.getContext();
        if (context == null) {
            context = appContext;
        }
        if (context == null) {
            return null;
        }
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

    }
}

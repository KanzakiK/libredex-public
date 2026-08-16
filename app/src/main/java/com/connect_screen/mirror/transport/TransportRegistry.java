package com.connect_screen.mirror.transport;

import android.util.Log;

import androidx.annotation.Nullable;

import com.connect_screen.mirror.BuildConfig;

/** Runtime registry for optional transports loaded from a private source overlay. */
public final class TransportRegistry {
    private static final String TAG = "TransportRegistry";
    private static final String INSTANCE_FIELD = "INSTANCE";

    private static volatile OptionalTransportProvider optional;

    private TransportRegistry() {
    }

    public static synchronized void discover() {
        if (optional != null) {
            return;
        }
        if (BuildConfig.OPTIONAL_TRANSPORT_PROVIDER == null
                || BuildConfig.OPTIONAL_TRANSPORT_PROVIDER.isEmpty()) {
            return;
        }
        try {
            Class<?> providerClass = Class.forName(BuildConfig.OPTIONAL_TRANSPORT_PROVIDER);
            Object instance = providerClass.getField(INSTANCE_FIELD).get(null);
            if (instance instanceof OptionalTransportProvider) {
                optional = (OptionalTransportProvider) instance;
                Log.i(TAG, "optional transport registered: " + optional.id());
            }
        } catch (Throwable ignored) {
            Log.d(TAG, "optional transport not present");
        }
    }

    @Nullable
    public static OptionalTransportProvider optional() {
        return optional;
    }

    public static boolean isOptionalActive() {
        return optional != null && optional.isActive();
    }

    public static int activeDisplayId() {
        return optional != null ? optional.activeDisplayId() : -1;
    }

    public static boolean restartActive(boolean dexSource, @Nullable TransportResultCallback callback) {
        return optional != null && optional.restart(dexSource, callback);
    }

    public static void stopAll() {
        if (optional != null) {
            optional.stop();
        }
    }
}

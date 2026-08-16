package com.connect_screen.mirror.transport;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/** Optional output transport contributed by a private source overlay. */
public interface OptionalTransportProvider {
    String id();

    String label();

    Fragment createFragment();

    boolean isActive();

    int activeDisplayId();

    boolean restart(boolean dexSource, @Nullable TransportResultCallback callback);

    void stop();
}

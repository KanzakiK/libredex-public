package com.connect_screen.mirror.transport;

import androidx.annotation.Nullable;

/** Result callback shared by optional transports. */
public interface TransportResultCallback {
    void onResult(@Nullable Integer displayId, @Nullable String error);
}

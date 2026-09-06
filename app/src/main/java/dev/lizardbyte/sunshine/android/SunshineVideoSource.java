package dev.lizardbyte.sunshine.android;

import android.view.Surface;

/**
 * Supplies frames into the native encoder input surface owned by Sunshine.
 */
public interface SunshineVideoSource {
    void start(Surface surface, int width, int height, int frameRate);

    void stop();
}

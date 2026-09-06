package dev.lizardbyte.sunshine.android;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

/**
 * MediaProjection source that writes a virtual display directly into Sunshine's encoder surface.
 */
public final class SunshineMediaProjectionSource implements SunshineVideoSource, AutoCloseable {
    private final MediaProjection mediaProjection;
    private final int densityDpi;
    private final int flags;
    private final String name;
    private final MediaProjection.Callback projectionCallback;

    private VirtualDisplay virtualDisplay;
    private boolean callbackRegistered;

    public SunshineMediaProjectionSource(MediaProjection mediaProjection, int densityDpi) {
        this(mediaProjection, densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, "SunshineMediaProjection");
    }

    public SunshineMediaProjectionSource(MediaProjection mediaProjection, int densityDpi, int flags, String name) {
        if (mediaProjection == null) {
            throw new IllegalArgumentException("mediaProjection is required");
        }
        this.mediaProjection = mediaProjection;
        this.densityDpi = Math.max(1, densityDpi);
        this.flags = flags;
        this.name = name != null && !name.isEmpty() ? name : "SunshineMediaProjection";
        this.projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                SunshineMediaProjectionSource.this.stop();
            }
        };
        this.mediaProjection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));
        callbackRegistered = true;
    }

    @Override
    public void start(Surface surface, int width, int height, int frameRate) {
        if (surface == null || !surface.isValid()) {
            throw new IllegalArgumentException("surface is invalid");
        }
        stop();
        virtualDisplay = mediaProjection.createVirtualDisplay(
                name,
                Math.max(1, width),
                Math.max(1, height),
                densityDpi,
                flags,
                surface,
                null,
                null
        );
        if (virtualDisplay == null) {
            throw new IllegalStateException("Unable to create MediaProjection virtual display");
        }
    }

    @Override
    public void stop() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
    }

    @Override
    public void close() {
        stop();
        if (callbackRegistered) {
            try {
                mediaProjection.unregisterCallback(projectionCallback);
            } catch (RuntimeException ignored) {
            }
            callbackRegistered = false;
        }
        try {
            mediaProjection.stop();
        } catch (RuntimeException ignored) {
        }
    }
}

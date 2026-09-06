package dev.lizardbyte.sunshine.android;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.util.DisplayMetrics;

public final class SunshineMediaProjectionHelper {
    private SunshineMediaProjectionHelper() {
    }

    public static Intent createCaptureIntent(Context context) {
        return manager(context).createScreenCaptureIntent();
    }

    public static MediaProjection getMediaProjection(Context context, int resultCode, Intent data) {
        return manager(context).getMediaProjection(resultCode, data);
    }

    public static SunshineMediaProjectionSource createSource(Context context, int resultCode, Intent data) {
        return new SunshineMediaProjectionSource(getMediaProjection(context, resultCode, data), getDensityDpi(context));
    }

    public static int getDensityDpi(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.max(1, metrics.densityDpi);
    }

    private static MediaProjectionManager manager(Context context) {
        Object service = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (!(service instanceof MediaProjectionManager)) {
            throw new IllegalStateException("MediaProjection service is unavailable");
        }
        return (MediaProjectionManager) service;
    }
}

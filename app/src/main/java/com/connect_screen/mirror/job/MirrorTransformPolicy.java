package com.connect_screen.mirror.job;

import android.content.Context;
import android.graphics.Rect;
import android.view.DisplayInfo;
import android.view.Surface;

import com.connect_screen.mirror.Pref;
import com.connect_screen.mirror.State;

import java.lang.reflect.Field;

/**
 * Single source of truth for the pure-mirror transform settings:
 * auto rotate, auto scale (black-bar removal) and auto aspect-ratio match.
 * Surface transports use the GL pipeline; the wired DP transport maps the same
 * decisions onto display projection geometry.
 */
public final class MirrorTransformPolicy {

    private static final long SURFACE_PIPELINE_READY_TIMEOUT_MS = 5000L;

    private MirrorTransformPolicy() {
    }

    public static boolean shouldUseSurfacePipeline() {
        return Pref.getAutoRotate() || Pref.getAutoScale() || Pref.getAutoMatchAspectRatio();
    }

    /**
     * Applies the shared source aspect-ratio change. Returns true when a change
     * was requested so callers can wait for the forced display size to settle.
     */
    public static boolean prepareAspectRatio(Context context, CurrentScreen screen,
                                             int outWidth, int outHeight) {
        if (!OutputSource.isMirrorActive() || !Pref.getAutoMatchAspectRatio()) {
            return false;
        }
        if (screen == null) {
            screen = CurrentScreen.detect(context);
        }
        CreateVirtualDisplay.changeAspectRatio(screen.displayId, outWidth, outHeight);
        return true;
    }

    /**
     * Starts the shared GL surface pipeline and waits until the mirror virtual
     * display exists. Returns null when the pipeline cannot be started.
     */
    public static AutoRotateAndScaleForMoonlight startSurfacePipeline(
            Surface outputSurface,
            CurrentScreen screen,
            int outWidth,
            int outHeight,
            int frameRate,
            String mirrorName,
            String failureMessage,
            boolean showControlHint) {
        return startSurfacePipeline(outputSurface, screen, outWidth, outHeight,
                frameRate, mirrorName, failureMessage, showControlHint, false);
    }

    public static AutoRotateAndScaleForMoonlight startSurfacePipeline(
            Surface outputSurface,
            CurrentScreen screen,
            int outWidth,
            int outHeight,
            int frameRate,
            String mirrorName,
            String failureMessage,
            boolean showControlHint,
            boolean plain) {
        AutoRotateAndScaleForMoonlight pipeline = new AutoRotateAndScaleForMoonlight(
                new VirtualDisplayArgs(mirrorName, outWidth, outHeight, frameRate, 160, false));
        pipeline.showControlHint = showControlHint;
        pipeline.forcePlainOutput = plain;
        try {
            pipeline.start(outputSurface, screen.displayId, mirrorName, failureMessage);
        } catch (Throwable t) {
            State.log("MirrorTransformPolicy: surface pipeline start failed: " + t);
            pipeline.stop();
            return null;
        }
        long deadline = System.currentTimeMillis() + SURFACE_PIPELINE_READY_TIMEOUT_MS;
        while (pipeline.activeVirtualDisplayId < 0
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pipeline.stop();
                return null;
            }
        }
        if (pipeline.activeVirtualDisplayId < 0) {
            pipeline.stop();
            State.log("MirrorTransformPolicy: surface pipeline did not create a mirror display");
            return null;
        }
        return pipeline;
    }

    /**
     * Geometry used by the wired DP path. The GL pipeline already encapsulates
     * the same settings, so this class is the only place that maps them onto
     * SurfaceFlinger projection values.
     */
    public static ProjectionGeometry projectionGeometry(CurrentScreen screen,
                                                       int outWidth, int outHeight) {
        int orientation = 0;
        if (Pref.getAutoRotate() && screen != null) {
            boolean sourceLandscape = screen.isLandscape();
            boolean outputLandscape = outWidth > outHeight;
            if (sourceLandscape != outputLandscape) {
                orientation = 90;
            }
        }
        boolean fill = Pref.getAutoScale() || Pref.getAutoMatchAspectRatio();
        return new ProjectionGeometry(orientation, fill);
    }

    public static Rect sourceRect(DisplayInfo info, int fallbackWidth, int fallbackHeight) {
        int width = fallbackWidth;
        int height = fallbackHeight;
        if (info != null) {
            int[] logical = logicalSize(info);
            if (logical != null) {
                width = logical[0];
                height = logical[1];
            }
        }
        if (width <= 0 || height <= 0) {
            width = Math.max(1, fallbackWidth);
            height = Math.max(1, fallbackHeight);
        }
        return new Rect(0, 0, width, height);
    }

    private static int[] logicalSize(DisplayInfo info) {
        try {
            Field widthField = info.getClass().getDeclaredField("logicalWidth");
            Field heightField = info.getClass().getDeclaredField("logicalHeight");
            widthField.setAccessible(true);
            heightField.setAccessible(true);
            return new int[]{widthField.getInt(info), heightField.getInt(info)};
        } catch (Throwable t) {
            return null;
        }
    }

    public static Rect displayRect(Rect sourceRect, int outWidth, int outHeight,
                                   int orientation, boolean fill) {
        if (fill || sourceRect == null || sourceRect.isEmpty()) {
            return new Rect(0, 0, outWidth, outHeight);
        }
        boolean rotated = orientation % 180 != 0;
        float srcWidth = rotated ? sourceRect.height() : sourceRect.width();
        float srcHeight = rotated ? sourceRect.width() : sourceRect.height();
        float scale = Math.min(outWidth / srcWidth, outHeight / srcHeight);
        int displayWidth = Math.max(1, Math.round(srcWidth * scale));
        int displayHeight = Math.max(1, Math.round(srcHeight * scale));
        int left = (outWidth - displayWidth) / 2;
        int top = (outHeight - displayHeight) / 2;
        return new Rect(left, top, left + displayWidth, top + displayHeight);
    }

    public static Rect layerStackRect(Rect sourceRect, int outWidth, int outHeight,
                                      int orientation, boolean fill) {
        if (!fill || sourceRect == null || sourceRect.isEmpty()) {
            return sourceRect;
        }
        boolean rotated = orientation % 180 != 0;
        float srcWidth = rotated ? sourceRect.height() : sourceRect.width();
        float srcHeight = rotated ? sourceRect.width() : sourceRect.height();
        float scale = Math.max(outWidth / srcWidth, outHeight / srcHeight);
        int effectiveCropWidth = Math.max(1, Math.round(outWidth / scale));
        int effectiveCropHeight = Math.max(1, Math.round(outHeight / scale));
        int cropWidth = rotated ? effectiveCropHeight : effectiveCropWidth;
        int cropHeight = rotated ? effectiveCropWidth : effectiveCropHeight;
        cropWidth = Math.min(cropWidth, sourceRect.width());
        cropHeight = Math.min(cropHeight, sourceRect.height());
        int left = sourceRect.left + (sourceRect.width() - cropWidth) / 2;
        int top = sourceRect.top + (sourceRect.height() - cropHeight) / 2;
        return new Rect(left, top, left + cropWidth, top + cropHeight);
    }

    public static final class ProjectionGeometry {
        public final int orientation;
        public final boolean fill;

        private ProjectionGeometry(int orientation, boolean fill) {
            this.orientation = orientation;
            this.fill = fill;
        }
    }
}

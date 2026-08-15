package com.connect_screen.mirror.job;

import android.app.Presentation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.connect_screen.mirror.State;

import java.util.concurrent.CountDownLatch;

/**
 * Renders the shared mirror-transform pipeline onto a fullscreen Presentation
 * on the wired DP display. This gives DP the same GL rotate/scale/black-bar
 * behavior as Moonlight instead of only low-level projection.
 */
public final class DpMirrorPresentation {
    private static final long SHOW_TIMEOUT_MS = 4000L;
    private static final long PIPELINE_TIMEOUT_MS = 10000L;

    private final Context context;
    private final Display display;
    private final CurrentScreen source;
    private final int width;
    private final int height;
    private final int frameRate;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CountDownLatch readyLatch = new CountDownLatch(1);

    private Presentation presentation;
    private volatile AutoRotateAndScaleForMoonlight pipeline;
    private volatile boolean stopping;
    private volatile boolean ready;

    private DpMirrorPresentation(Context context, Display display, CurrentScreen source,
                                 int width, int height, int frameRate) {
        this.context = context.getApplicationContext();
        this.display = display;
        this.source = source;
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
    }

    public static DpMirrorPresentation start(Context context, Display display,
                                             CurrentScreen source,
                                             int width, int height, int frameRate) {
        DpMirrorPresentation mirror = new DpMirrorPresentation(
                context, display, source, width, height, frameRate);
        if (!mirror.showAndWait()) {
            mirror.stop();
            return null;
        }
        return mirror;
    }

    private boolean showAndWait() {
        CountDownLatch shown = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                presentation = new MirrorPresentation(context, display);
                presentation.show();
            } catch (Throwable t) {
                State.log("DpMirrorPresentation: show failed: " + t);
            } finally {
                shown.countDown();
            }
        });
        try {
            if (!shown.await(SHOW_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return false;
            }
            if (presentation == null) {
                return false;
            }
            return readyLatch.await(PIPELINE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    && ready;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void stop() {
        stopping = true;
        AutoRotateAndScaleForMoonlight activePipeline = pipeline;
        if (activePipeline != null) {
            activePipeline.stop();
        }
        mainHandler.post(() -> {
            if (presentation != null) {
                try {
                    presentation.dismiss();
                } catch (Throwable t) {
                    State.log("DpMirrorPresentation: dismiss failed: " + t);
                }
                presentation = null;
            }
        });
    }

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            if (stopping || ready) {
                return;
            }
            final Surface surface = holder.getSurface();
            new Thread(() -> {
                try {
                    if (stopping) {
                        return;
                    }
                    pipeline = MirrorTransformPolicy.startSurfacePipeline(
                            surface,
                            source,
                            width,
                            height,
                            frameRate,
                            "LibreDeXDP",
                            "DP mirror failed to mirror the current screen",
                            false);
                    ready = pipeline != null;
                    if (stopping && pipeline != null) {
                        pipeline.stop();
                        pipeline = null;
                        ready = false;
                    }
                    State.log("DpMirrorPresentation: pipeline ready=" + ready);
                } catch (Throwable t) {
                    State.log("DpMirrorPresentation: pipeline thread failed: " + t);
                } finally {
                    readyLatch.countDown();
                }
            }, "dp-mirror-gl").start();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int surfaceWidth,
                                   int surfaceHeight) {
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            readyLatch.countDown();
        }
    };

    private final class MirrorPresentation extends Presentation {
        MirrorPresentation(Context context, Display display) {
            super(context, display);
        }

        @Override
        protected void onCreate(android.os.Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            if (getWindow() != null) {
                getWindow().addFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
            SurfaceView surfaceView = new SurfaceView(context);
            surfaceView.getHolder().setFormat(PixelFormat.RGBA_8888);
            surfaceView.getHolder().addCallback(surfaceCallback);
            setContentView(surfaceView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }
}

package dev.lizardbyte.sunshine.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.Window;

/**
 * PixelCopy-based source that captures an app window and draws it into Sunshine's encoder surface.
 */
public final class SunshineWindowCapture implements SunshineVideoSource {
    private final Window window;
    private HandlerThread thread;
    private Handler handler;
    private Bitmap bitmap;
    private Surface surface;
    private Rect outputBounds;
    private int frameDelayMs;
    private boolean running;
    private boolean copyPending;
    private int generation;

    public SunshineWindowCapture(Window window) {
        if (window == null) {
            throw new IllegalArgumentException("window is required");
        }
        this.window = window;
    }

    @Override
    public void start(Surface surface, int width, int height, int frameRate) {
        stop();
        generation++;
        this.surface = surface;
        outputBounds = new Rect(0, 0, width, height);
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        thread = new HandlerThread("sunshine-pixelcopy");
        thread.start();
        handler = new Handler(thread.getLooper());
        frameDelayMs = Math.max(1, 1000 / Math.max(1, frameRate));
        running = true;
        requestNextFrame();
    }

    @Override
    public void stop() {
        running = false;
        generation++;
        copyPending = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        surface = null;
        outputBounds = null;
        bitmap = null;
    }

    private void requestNextFrame() {
        Bitmap targetBitmap = bitmap;
        Handler targetHandler = handler;
        int targetGeneration = generation;
        if (!running || copyPending || targetBitmap == null || targetHandler == null) {
            return;
        }
        copyPending = true;
        Rect bounds = new Rect(0, 0, window.getDecorView().getWidth(), window.getDecorView().getHeight());
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            copyPending = false;
            targetHandler.postDelayed(this::requestNextFrame, frameDelayMs);
            return;
        }
        PixelCopy.request(window, bounds, targetBitmap, result -> {
            if (targetGeneration != generation) {
                return;
            }
            copyPending = false;
            if (running && result == PixelCopy.SUCCESS) {
                submitFrame(targetBitmap);
            }
            if (running && handler != null) {
                handler.postDelayed(this::requestNextFrame, frameDelayMs);
            }
        }, targetHandler);
    }

    private void submitFrame(Bitmap frame) {
        Surface targetSurface = surface;
        Rect targetBounds = outputBounds;
        if (targetSurface == null || targetBounds == null || !targetSurface.isValid()) {
            return;
        }

        Canvas canvas = null;
        try {
            canvas = lockCanvas(targetSurface, targetBounds);
            canvas.drawBitmap(frame, null, targetBounds, null);
        } catch (RuntimeException ignored) {
            return;
        } finally {
            if (canvas != null) {
                try {
                    targetSurface.unlockCanvasAndPost(canvas);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private Canvas lockCanvas(Surface targetSurface, Rect targetBounds) {
        try {
            return targetSurface.lockHardwareCanvas();
        } catch (RuntimeException ignored) {
            return targetSurface.lockCanvas(targetBounds);
        }
    }
}

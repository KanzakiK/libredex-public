package com.connect_screen.mirror.job;

import static android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.EGLExt;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;

import com.connect_screen.mirror.State;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps MediaCodec fed at a steady cadence even when the mirrored display is static.
 */
public class ExternalDisplayFramePacer {
    private final int width;
    private final int height;
    private final int frameRate;
    private final Surface outputSurface;
    private final float[] identityMatrix = new float[16];

    private HandlerThread renderThread;
    private Handler renderHandler;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglOutputSurface = EGL14.EGL_NO_SURFACE;
    private int inputTextureId = -1;
    private SurfaceTexture inputSurfaceTexture;
    private Surface inputSurface;
    private ExternalTextureRenderer renderer;
    private boolean frameAvailable;
    private boolean hasFrame;
    private boolean stopping;
    private boolean released;
    private long nextFrameAtMs;
    private long lastDebugAtMs;
    private volatile long lastSourceFrameAtMs;
    private final AtomicLong sourceFrames = new AtomicLong();
    private final AtomicLong renderedFrames = new AtomicLong();
    private final AtomicLong repeatedFrames = new AtomicLong();
    private final AtomicLong lateTicks = new AtomicLong();
    private final AtomicLong renderTimeNs = new AtomicLong();
    private final AtomicLong maxRenderTimeNs = new AtomicLong();

    public ExternalDisplayFramePacer(int width, int height, int frameRate, Surface outputSurface) {
        this.width = width;
        this.height = height;
        this.frameRate = Math.max(1, frameRate);
        this.outputSurface = outputSurface;
        android.opengl.Matrix.setIdentityM(identityMatrix, 0);
    }

    public Surface start() {
        renderThread = new HandlerThread("ExternalDisplayFramePacer");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());

        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        renderHandler.post(() -> {
            try {
                initGl();
                ready.countDown();
                nextFrameAtMs = SystemClock.uptimeMillis();
                renderHandler.post(frameTicker);
            } catch (RuntimeException e) {
                error.set(e);
                ready.countDown();
            }
        });

        try {
            if (!ready.await(2, TimeUnit.SECONDS)) {
                stop();
                throw new RuntimeException("External display frame pacer init timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            throw new RuntimeException("External display frame pacer init interrupted", e);
        }

        if (error.get() != null) {
            stop();
            throw error.get();
        }
        return inputSurface;
    }

    public String collectDebugLine() {
        long now = SystemClock.uptimeMillis();
        long windowMs = Math.max(1, now - lastDebugAtMs);
        lastDebugAtMs = now;
        long source = sourceFrames.getAndSet(0);
        long rendered = renderedFrames.getAndSet(0);
        long repeated = repeatedFrames.getAndSet(0);
        long late = lateTicks.getAndSet(0);
        long renderNs = renderTimeNs.getAndSet(0);
        long maxRenderNs = maxRenderTimeNs.getAndSet(0);
        double sourceFps = source * 1000.0 / windowMs;
        double renderFps = rendered * 1000.0 / windowMs;
        double avgRenderMs = rendered > 0 ? renderNs / 1_000_000.0 / rendered : 0;
        double maxRenderMs = maxRenderNs / 1_000_000.0;
        long sourceIdleMs = lastSourceFrameAtMs > 0 ? now - lastSourceFrameAtMs : -1;
        return String.format(Locale.US,
                "Frame pacer: source=%.1ffps render=%.1ffps repeated=%d late=%d render=%.1f/%.1fms sourceIdle=%dms",
                sourceFps, renderFps, repeated, late, avgRenderMs, maxRenderMs, sourceIdleMs);
    }

    public Surface getInputSurface() {
        return inputSurface;
    }

    public void stop() {
        stopping = true;
        if (renderHandler == null) {
            return;
        }

        if (Thread.currentThread() == renderThread) {
            releaseGlSafely();
            renderThread.quitSafely();
            renderThread = null;
            renderHandler = null;
            return;
        }

        CountDownLatch released = new CountDownLatch(1);
        renderHandler.removeCallbacks(frameTicker);
        renderHandler.postAtFrontOfQueue(() -> {
            try {
                releaseGlSafely();
            } finally {
                released.countDown();
            }
        });
        try {
            released.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (renderThread != null) {
            renderThread.quitSafely();
            renderThread = null;
        }
        renderHandler = null;
    }

    private void initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("Cannot get EGL display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new RuntimeException("Cannot initialize EGL");
        }

        int[] configAttribs = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] <= 0) {
            throw new RuntimeException("Cannot choose EGL config");
        }

        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        eglOutputSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], outputSurface, null, 0);
        if (!EGL14.eglMakeCurrent(eglDisplay, eglOutputSurface, eglOutputSurface, eglContext)) {
            throw new RuntimeException("Cannot make EGL context current");
        }

        GLES20.glViewport(0, 0, width, height);
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        inputTextureId = textures[0];
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, inputTextureId);
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        renderer = new ExternalTextureRenderer(inputTextureId);
        inputSurfaceTexture = new SurfaceTexture(inputTextureId);
        inputSurfaceTexture.setDefaultBufferSize(width, height);
        inputSurfaceTexture.setOnFrameAvailableListener(surfaceTexture -> {
            sourceFrames.incrementAndGet();
            lastSourceFrameAtMs = SystemClock.uptimeMillis();
            frameAvailable = true;
        }, renderHandler);
        inputSurface = new Surface(inputSurfaceTexture);
        lastDebugAtMs = SystemClock.uptimeMillis();
        State.log("[ExternalDisplayFramePacer] started, size=" + width + "x" + height + " fps=" + frameRate);
    }

    private final Runnable frameTicker = new Runnable() {
        @Override
        public void run() {
            if (stopping || released || renderHandler == null) {
                return;
            }
            long frameIntervalMs = Math.max(1, Math.round(1000f / frameRate));
            long beforeRenderMs = SystemClock.uptimeMillis();
            if (beforeRenderMs - nextFrameAtMs > frameIntervalMs) {
                lateTicks.incrementAndGet();
            }
            try {
                renderFrameIfReady();
            } catch (RuntimeException e) {
                State.log("[ExternalDisplayFramePacer] render failed: " + e.getMessage());
            }

            nextFrameAtMs += frameIntervalMs;
            long now = SystemClock.uptimeMillis();
            if (nextFrameAtMs < now) {
                nextFrameAtMs = now + frameIntervalMs;
            }
            renderHandler.postAtTime(this, nextFrameAtMs);
        }
    };

    private void renderFrameIfReady() {
        if (stopping || released) {
            return;
        }
        boolean consumedNewFrame = false;
        if (frameAvailable && inputSurfaceTexture != null) {
            inputSurfaceTexture.updateTexImage();
            frameAvailable = false;
            hasFrame = true;
            consumedNewFrame = true;
        }
        if (stopping || released || !hasFrame || renderer == null) {
            return;
        }
        long renderStartNs = SystemClock.elapsedRealtimeNanos();
        renderer.renderFrame(identityMatrix);
        if (stopping || released) {
            return;
        }
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglOutputSurface, System.nanoTime());
        EGL14.eglSwapBuffers(eglDisplay, eglOutputSurface);
        long renderElapsedNs = SystemClock.elapsedRealtimeNanos() - renderStartNs;
        renderedFrames.incrementAndGet();
        if (!consumedNewFrame) {
            repeatedFrames.incrementAndGet();
        }
        renderTimeNs.addAndGet(renderElapsedNs);
        updateMax(maxRenderTimeNs, renderElapsedNs);
    }

    private void releaseGlSafely() {
        if (released) {
            return;
        }
        released = true;
        try {
            releaseGl();
        } catch (Throwable e) {
            State.log("[ExternalDisplayFramePacer] release failed: " + e.getMessage());
        }
    }

    private void releaseGl() {
        if (inputSurfaceTexture != null) {
            inputSurfaceTexture.setOnFrameAvailableListener(null);
        }
        if (renderer != null) {
            renderer.release();
            renderer = null;
        }
        if (inputTextureId != -1) {
            GLES20.glDeleteTextures(1, new int[]{inputTextureId}, 0);
            inputTextureId = -1;
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglOutputSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglOutputSurface);
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglOutputSurface = EGL14.EGL_NO_SURFACE;
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (inputSurfaceTexture != null) {
            inputSurfaceTexture.release();
            inputSurfaceTexture = null;
        }
        frameAvailable = false;
        hasFrame = false;
        State.log("[ExternalDisplayFramePacer] stopped");
    }

    private static void updateMax(AtomicLong target, long value) {
        long current;
        do {
            current = target.get();
            if (value <= current) {
                return;
            }
        } while (!target.compareAndSet(current, value));
    }
}

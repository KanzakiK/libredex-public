package com.connect_screen.mirror.job;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.input.IInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.MotionEventHidden;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.connect_screen.mirror.Pref;
import com.connect_screen.mirror.R;
import com.connect_screen.mirror.State;
import com.connect_screen.mirror.SunshineService;
import com.connect_screen.mirror.shizuku.ServiceUtils;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Method;
import java.util.Locale;

import dev.rikka.tools.refine.Refine;

public class SunshineMouse {
    private static String TAG = "SunshineMouse";
    private static final boolean DEBUG_INPUT_EVENTS = false;
    public static volatile AutoRotateAndScaleForMoonlight autoRotateAndScaleForMoonlight;
    private static volatile ExternalDisplayFramePacer externalDisplayFramePacer;
    private static volatile long externalDisplayFramePacerSessionId;
    private static IInputManager inputManager;
    private static Method injectInputEventOtherScreensMethod;
    private static float defaultDisplayWidth;
    private static float defaultDisplayHeight;
    // screenWidth * screenHeight always in landscape mode
    private static float screenWidth;
    private static float screenHeight;
    private static float portraitMirrorWidth;
    private static float portraitMirrorHeight;
    private static float landscapeMirrorWidth;
    private static float landscapeMirrorHeight;
    private static boolean autoScale;
    private static boolean singleAppMode;
    private static boolean autoRotate;
    private static boolean externalMirrorMode;
    private static int externalMirrorDisplayId = Display.DEFAULT_DISPLAY;
    private static int dexTargetDisplayId = Integer.MIN_VALUE;
    private static float externalMirrorWidth;
    private static float externalMirrorHeight;
    private static boolean leftMouseDown;
    private static long mouseDownTime;
    private static long mouseTouchDownTime;
    private static long touchGestureDownTime;
    private static boolean mapMouseToTouch;
    private static Method setActionButtonMethod;
    private static int lastFocusedDisplayId = Integer.MIN_VALUE;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static WindowManager cursorWindowManager;
    private static WindowManager.LayoutParams cursorParams;
    private static ImageView cursorView;
    private static int cursorDisplayId = Integer.MIN_VALUE;
    private static int cursorHotspotX;
    private static int cursorHotspotY;
    private static boolean useAndroidCursorOverlay;
    private static final int MOUSE_TOUCH_POINTER_ID = 0;
    private static final Object touchInputDebugLock = new Object();
    private static long touchInputDebugWindowStartMs;
    private static long touchInputDebugLastCollectMs;
    private static String lastTouchInputDebugLine = "";
    private static final Map<Integer, Point> touchPacketDebugLastPoints = new HashMap<>();
    private static float touchPacketDebugMinX;
    private static float touchPacketDebugMaxX;
    private static float touchPacketDebugMinY;
    private static float touchPacketDebugMaxY;
    private static long touchPacketMoveCount;
    private static long touchPacketRepeatedMoveCount;
    private static long touchPacketTinyMoveCount;
    private static double touchPacketTotalStepPx;
    private static double touchPacketMaxStepPx;
    private static long touchPacketLastMoveAtMs;
    private static double touchPacketTotalGapMs;
    private static double touchPacketMaxGapMs;
    private static long touchPacketGapOver33MsCount;
    private static long touchPacketGapOver50MsCount;
    private static Point injectedMoveDebugLastPoint;
    private static long injectedMoveCount;
    private static long injectedRepeatedMoveCount;
    private static long injectedTinyMoveCount;
    private static double injectedTotalStepPx;
    private static double injectedMaxStepPx;
    private static long injectedLastMoveAtMs;
    private static double injectedTotalGapMs;
    private static double injectedMaxGapMs;
    private static long injectedGapOver33MsCount;
    private static long injectedGapOver50MsCount;

    public static void initialize(int width, int height) {
        Context context = State.getContext();
        if (context == null) {
            return;
        }
        if (ShizukuUtils.hasPermission()) {
            inputManager = ServiceUtils.getInputManager();
        }
        screenWidth = width;
        screenHeight = height;
        singleAppMode = Pref.getSingleAppMode();
        autoRotate = Pref.getAutoRotate();
        autoScale = Pref.getAutoScale();
        externalMirrorMode = !singleAppMode && false && State.externalDisplayId > 0;
        externalMirrorDisplayId = externalMirrorMode ? getExternalControlDisplayId() : Display.DEFAULT_DISPLAY;
        externalMirrorWidth = Math.max(1, State.externalDisplayWidth);
        externalMirrorHeight = Math.max(1, State.externalDisplayHeight);
        mapMouseToTouch = Pref.getMapMouseToTouch();
        useAndroidCursorOverlay = Pref.getUseAndroidCursorOverlay();
        singlePoint = null;
        leftMouseDown = false;
        mouseDownTime = 0;
        mouseTouchDownTime = 0;
        touchGestureDownTime = 0;
        lastFocusedDisplayId = Integer.MIN_VALUE;
        resetTouchInputDebugStats();
        pointers.clear();
        bufferedMove.clear();
        if (useAndroidCursorOverlay) {
            showCursorOverlay();
        } else {
            cleanupCursorOverlay();
        }

        DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (!singleAppMode && Pref.getAutoMatchAspectRatio() && ShizukuUtils.hasPermission()) {
            MirrorTransformPolicy.prepareAspectRatio(context, null, width, height);
            IWindowManager windowManager = ServiceUtils.getWindowManager();
            android.graphics.Point baseSize = new android.graphics.Point();
            windowManager.getBaseDisplaySize(Display.DEFAULT_DISPLAY, baseSize);
            defaultDisplayWidth = Math.max(baseSize.x, baseSize.y);
            defaultDisplayHeight = Math.min(baseSize.x, baseSize.y);
            float aspectRatio1 = defaultDisplayWidth / defaultDisplayHeight;
            float aspectRatio2 = screenWidth / screenHeight;
            if (Math.abs(aspectRatio1 - aspectRatio2) > 0.01) {
                // 修改分辨率有画面拉伸
                defaultDisplayWidth = screenWidth;
                DisplayCutout cutout = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    cutout = defaultDisplay.getCutout();
                }
                if (cutout != null) {
                    for(Rect rect : cutout.getBoundingRects()) {
                        if (rect.top == 0) {
                            defaultDisplayWidth += rect.bottom * 2;
                            break;
                        }
                    }
                }
            }
        } else {
            android.graphics.Point realSize = new android.graphics.Point();
            defaultDisplay.getRealSize(realSize);
            defaultDisplayWidth = Math.max(realSize.x, realSize.y);
            defaultDisplayHeight = Math.min(realSize.x, realSize.y);
        }
        float aspectRatio = defaultDisplayWidth / defaultDisplayHeight;

        landscapeMirrorHeight = screenHeight;
        landscapeMirrorWidth = landscapeMirrorHeight * aspectRatio;
        if (landscapeMirrorWidth > screenWidth) {
            landscapeMirrorWidth = screenWidth;
            landscapeMirrorHeight = landscapeMirrorWidth / aspectRatio;
        }

        portraitMirrorHeight = screenHeight;
        portraitMirrorWidth = portraitMirrorHeight / aspectRatio;
        if (portraitMirrorWidth > screenWidth) {
            portraitMirrorWidth = screenWidth;
            portraitMirrorHeight = portraitMirrorWidth * aspectRatio;
        }

        State.log("主屏尺寸 defaultDisplayWidth: " + defaultDisplayWidth + " defaultDisplayHeight: " + defaultDisplayHeight);
        State.log("客户端屏幕尺寸 screenWidth: " + screenWidth + " screenHeight: " + screenHeight);
        State.log("Moonlight input target displayId=" + getTargetDisplayId()
                + " externalMirrorMode=" + externalMirrorMode
                + " externalSize=" + externalMirrorWidth + "x" + externalMirrorHeight);
        if (!singleAppMode) {
            State.log("镜像模式时 portraitMirrorWidth: " + portraitMirrorWidth + " portraitMirrorHeight: " + portraitMirrorHeight + " landscapeMirrorWidth: " + landscapeMirrorWidth + " landscapeMirrorHeight: " + landscapeMirrorHeight);
        }
    }

    public static String collectFramePacerDebugLine() {
        ExternalDisplayFramePacer pacer = externalDisplayFramePacer;
        return pacer != null ? pacer.collectDebugLine() : "";
    }

    public static String collectTouchInputDebugLine() {
        synchronized (touchInputDebugLock) {
            long now = SystemClock.uptimeMillis();
            if (touchInputDebugWindowStartMs == 0) {
                touchInputDebugWindowStartMs = now;
            }
            if (touchInputDebugLastCollectMs != 0 && now - touchInputDebugLastCollectMs < 100) {
                return lastTouchInputDebugLine;
            }
            double elapsedSec = Math.max(0.001, (now - touchInputDebugWindowStartMs) / 1000.0);
            double packetAvgStepPx = touchPacketMoveCount > 0 ? touchPacketTotalStepPx / touchPacketMoveCount : 0.0;
            double injectedAvgStepPx = injectedMoveCount > 0 ? injectedTotalStepPx / injectedMoveCount : 0.0;
            double packetAvgGapMs = touchPacketMoveCount > 1 ? touchPacketTotalGapMs / (touchPacketMoveCount - 1) : 0.0;
            double injectedAvgGapMs = injectedMoveCount > 1 ? injectedTotalGapMs / (injectedMoveCount - 1) : 0.0;
            float packetSpanX = touchPacketMoveCount > 0 ? touchPacketDebugMaxX - touchPacketDebugMinX : 0f;
            float packetSpanY = touchPacketMoveCount > 0 ? touchPacketDebugMaxY - touchPacketDebugMinY : 0f;
            lastTouchInputDebugLine = String.format(
                    Locale.US,
                    "Touch path (last %.1fs): packetMoves=%d repeated=%d tiny<1px=%d avgStep=%.2fpx maxStep=%.2fpx avgGap=%.1fms maxGap=%.1fms gap>33=%d gap>50=%d span=%.1fx%.1fpx injectedMoves=%d repeated=%d tiny<1px=%d avgStep=%.2fpx maxStep=%.2fpx avgGap=%.1fms maxGap=%.1fms gap>33=%d gap>50=%d",
                    elapsedSec,
                    touchPacketMoveCount,
                    touchPacketRepeatedMoveCount,
                    touchPacketTinyMoveCount,
                    packetAvgStepPx,
                    touchPacketMaxStepPx,
                    packetAvgGapMs,
                    touchPacketMaxGapMs,
                    touchPacketGapOver33MsCount,
                    touchPacketGapOver50MsCount,
                    packetSpanX,
                    packetSpanY,
                    injectedMoveCount,
                    injectedRepeatedMoveCount,
                    injectedTinyMoveCount,
                    injectedAvgStepPx,
                    injectedMaxStepPx,
                    injectedAvgGapMs,
                    injectedMaxGapMs,
                    injectedGapOver33MsCount,
                    injectedGapOver50MsCount
            );
            touchInputDebugLastCollectMs = now;
            touchInputDebugWindowStartMs = now;
            touchPacketMoveCount = 0;
            touchPacketRepeatedMoveCount = 0;
            touchPacketTinyMoveCount = 0;
            touchPacketTotalStepPx = 0.0;
            touchPacketMaxStepPx = 0.0;
            touchPacketTotalGapMs = 0.0;
            touchPacketMaxGapMs = 0.0;
            touchPacketGapOver33MsCount = 0;
            touchPacketGapOver50MsCount = 0;
            injectedMoveCount = 0;
            injectedRepeatedMoveCount = 0;
            injectedTinyMoveCount = 0;
            injectedTotalStepPx = 0.0;
            injectedMaxStepPx = 0.0;
            injectedTotalGapMs = 0.0;
            injectedMaxGapMs = 0.0;
            injectedGapOver33MsCount = 0;
            injectedGapOver50MsCount = 0;
            touchPacketLastMoveAtMs = 0;
            injectedLastMoveAtMs = 0;
            if (!touchPacketDebugLastPoints.isEmpty()) {
                Point anchor = touchPacketDebugLastPoints.values().iterator().next();
                touchPacketDebugMinX = anchor.x;
                touchPacketDebugMaxX = anchor.x;
                touchPacketDebugMinY = anchor.y;
                touchPacketDebugMaxY = anchor.y;
            }
            return lastTouchInputDebugLine;
        }
    }

    public static void setExternalDisplayFramePacer(ExternalDisplayFramePacer pacer, long sessionId) {
        externalDisplayFramePacer = pacer;
        externalDisplayFramePacerSessionId = pacer != null ? sessionId : 0;
    }

    public static void stopExternalDisplayFramePacer(long sessionId, boolean force) {
        ExternalDisplayFramePacer pacer = externalDisplayFramePacer;
        if (pacer == null) {
            return;
        }
        if (!force && externalDisplayFramePacerSessionId != sessionId) {
            State.log("[ExternalDisplayFramePacer] skip stale cleanup, session="
                    + sessionId + " active=" + externalDisplayFramePacerSessionId);
            return;
        }
        externalDisplayFramePacer = null;
        externalDisplayFramePacerSessionId = 0;
        pacer.stop();
    }


    private static class Point {
        public float x = 0;
        public float y = 0;
    }

    private static Map<Integer, Point> pointers = new HashMap<>();

    private static Point translate(float x, float y) {
        if (dexTargetDisplayId >= 0) {
            Point point = new Point();
            point.x = clamp(x * screenWidth, 0, screenWidth);
            point.y = clamp(y * screenHeight, 0, screenHeight);
            return point;
        }
        if (singleAppMode) {
            return translateSingleAppMode(x, y);
        } else if (externalMirrorMode) {
            return translateExternalMirrorMode(x, y);
        } else {
            return translateMirrorMode(x, y);
        }
    }

    private static Point translateExternalMirrorMode(float x, float y) {
        Point point = new Point();
        float sourceWidth = externalMirrorWidth > 0 ? externalMirrorWidth : screenWidth;
        float sourceHeight = externalMirrorHeight > 0 ? externalMirrorHeight : screenHeight;
        float sourceAspect = sourceWidth / sourceHeight;
        float streamAspect = screenWidth / screenHeight;
        float visibleWidth = screenWidth;
        float visibleHeight = screenHeight;
        float xBlackBar = 0;
        float yBlackBar = 0;

        if (sourceAspect > streamAspect) {
            visibleHeight = screenWidth / sourceAspect;
            yBlackBar = (screenHeight - visibleHeight) / 2;
        } else if (sourceAspect < streamAspect) {
            visibleWidth = screenHeight * sourceAspect;
            xBlackBar = (screenWidth - visibleWidth) / 2;
        }

        float adjustedX = clamp(x * screenWidth - xBlackBar, 0, visibleWidth);
        float adjustedY = clamp(y * screenHeight - yBlackBar, 0, visibleHeight);
        point.x = (adjustedX / visibleWidth) * sourceWidth;
        point.y = (adjustedY / visibleHeight) * sourceHeight;
        return point;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Point translateMirrorMode(float x, float y) {
        boolean isLandscape = SunshineService.instance.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        float xInScreen = x * screenWidth;
        float yInScreen = y * screenHeight;
        if (isLandscape) {
            return translateRotation90Mirror(xInScreen, yInScreen);
        } else {
            return translateRotation0Mirror(xInScreen, yInScreen);
        }
    }

    private static Point translateRotation0Mirror(float xInScreen, float yInScreen) {
        if (autoRotate) {
            Point point = new Point();
            float xBlackBar = (screenWidth - landscapeMirrorWidth) / 2;
            float yBlackBar = (screenHeight - landscapeMirrorHeight) / 2;
            float adjustedX = xInScreen - xBlackBar;
            if (adjustedX > landscapeMirrorWidth) {
                adjustedX = landscapeMirrorWidth;
            } else if (adjustedX < 0) {
                adjustedX = 0;
            }
            float adjustedY = yInScreen - yBlackBar;
            if (adjustedY > landscapeMirrorHeight) {
                adjustedY = landscapeMirrorHeight;
            } else if (adjustedY < 0) {
                adjustedY = 0;
            }
            point.y = (adjustedX / landscapeMirrorWidth) * defaultDisplayWidth;
            point.x = (1 - (adjustedY / landscapeMirrorHeight)) * defaultDisplayHeight;
            return point;
        } else {
            Point point = new Point();
            float xBlackBar = (screenWidth - portraitMirrorWidth) / 2;
            float yBlackBar = (screenHeight - portraitMirrorHeight) / 2;
            float adjustedX = xInScreen - xBlackBar;
            if (adjustedX > portraitMirrorWidth) {
                adjustedX = portraitMirrorWidth;
            } else if (adjustedX < 0) {
                adjustedX = 0;
            }
            float adjustedY = yInScreen - yBlackBar;
            if (adjustedY > portraitMirrorHeight) {
                adjustedY = portraitMirrorHeight;
            } else if (adjustedY < 0) {
                adjustedY = 0;
            }
            point.x = (adjustedX / portraitMirrorWidth) * defaultDisplayHeight;
            point.y = (adjustedY / portraitMirrorHeight) * defaultDisplayWidth;
            return point;
        }
    }

    private static Point translateRotation90Mirror(float xInScreen, float yInScreen) {
        Point point = new Point();
        float xBlackBar = (screenWidth - landscapeMirrorWidth) / 2;
        float yBlackBar = (screenHeight - landscapeMirrorHeight) / 2;
        float adjustedX = xInScreen - xBlackBar;
        if (adjustedX > landscapeMirrorWidth) {
            adjustedX = landscapeMirrorWidth;
        } else if (adjustedX < 0) {
            adjustedX = 0;
        }
        float adjustedY = yInScreen - yBlackBar;
        if (adjustedY > landscapeMirrorHeight) {
            adjustedY = landscapeMirrorHeight;
        } else if (adjustedY < 0) {
            adjustedY = 0;
        }
        point.x = (adjustedX / landscapeMirrorWidth) * defaultDisplayWidth;
        point.y = (adjustedY / landscapeMirrorHeight) * defaultDisplayHeight;
        return point;
    }

    private static @NonNull Point translateSingleAppMode(float x, float y) {
        int displayRotation = State.mirrorVirtualDisplay.getDisplay().getRotation();
        Point point = new Point();
        switch (displayRotation) {
            case Surface.ROTATION_0:
                point.x = x * screenWidth;
                point.y = y * screenHeight;
                break;
            case Surface.ROTATION_90:
                point.x = y * screenHeight;
                point.y = (1 - x) * screenWidth;
                break;
            case Surface.ROTATION_180:
                point.x = (1 - x) * screenWidth;
                point.y = (1 - y) * screenHeight;
                break;
            case Surface.ROTATION_270:
                point.x = (1 - y) * screenHeight;
                point.y = x * screenWidth;
                break;
        }
        return point;
    }

    private static Point singlePoint = null;
    public static void handleRelMouseMovePacket(int deltaX, int deltaY) {
        Point bounds = getPointerBounds();
        Point point = ensureSinglePoint();
        float scaleX = bounds.x / Math.max(1.0f, screenWidth);
        float scaleY = bounds.y / Math.max(1.0f, screenHeight);
        point.x = clamp(point.x + deltaX * scaleX, 0, bounds.x);
        point.y = clamp(point.y + deltaY * scaleY, 0, bounds.y);
        singlePoint = point;
        if (useAndroidCursorOverlay) {
            updateCursorOverlay(singlePoint.x, singlePoint.y);
        }
        if (mapMouseToTouch) {
            if (leftMouseDown) {
                handleTouchEventMove(MOUSE_TOUCH_POINTER_ID, singlePoint.x, singlePoint.y);
            }
            return;
        }
        if (leftMouseDown) {
            injectMouseMove(singlePoint.x, singlePoint.y);
        } else {
            injectMouseHover(singlePoint.x, singlePoint.y);
        }
    }

    public static void handleAbsMouseMovePacket(float x, float y, float width, float height) {
        x = x / width;
        y = y / height;
        // 根据屏幕旋转调整坐标
        Point point = translate(x, y);
        singlePoint = point;
        if (useAndroidCursorOverlay) {
            updateCursorOverlay(singlePoint.x, singlePoint.y);
        }
        if (mapMouseToTouch) {
            if (leftMouseDown) {
                handleTouchEventMove(MOUSE_TOUCH_POINTER_ID, singlePoint.x, singlePoint.y);
            }
            return;
        }
        if (leftMouseDown) {
            injectMouseMove(singlePoint.x, singlePoint.y);
        } else {
            injectMouseHover(singlePoint.x, singlePoint.y);
        }
    }

    public static void handleLeftMouseButton(boolean release) {
        if (singlePoint == null) {
            singlePoint = ensureSinglePoint();
        }
        if (mapMouseToTouch) {
            if (release) {
                if (leftMouseDown) {
                    handleTouchEventUp(MOUSE_TOUCH_POINTER_ID, singlePoint.x, singlePoint.y, false);
                }
                leftMouseDown = false;
                mouseTouchDownTime = 0;
            } else if (!leftMouseDown) {
                leftMouseDown = true;
                mouseTouchDownTime = SystemClock.uptimeMillis();
                handleTouchEventDown(MOUSE_TOUCH_POINTER_ID, singlePoint.x, singlePoint.y);
            }
            return;
        }
        if (release) {
            if (leftMouseDown) {
                injectMouseButton(true, singlePoint.x, singlePoint.y);
            }
            leftMouseDown = false;
        } else {
            leftMouseDown = true;
            injectMouseButton(false, singlePoint.x, singlePoint.y);
        }
    }

    // 添加处理触摸事件的静态方法
    public static void handleMouseScroll(int verticalAmount, int horizontalAmount) {
        if (mapMouseToTouch) {
            return;
        }
        Point point = singlePoint;
        if (point == null) {
            point = new Point();
            point.x = screenWidth / 2.0f;
            point.y = screenHeight / 2.0f;
        }
        injectMouseScroll(point.x, point.y, verticalAmount / 120.0f, horizontalAmount / 120.0f);
    }

    public static void handleTouchPacket(int eventType, int rotation, int pointerId,
                                         float x, float y, float pressureOrDistance,
                                         float contactAreaMajor, float contactAreaMinor) {
        // 根据屏幕旋转调整坐标
        Point point = translate(x, y);
        pointerId = pointerId % 10;
        recordTouchPacketDebug(eventType, pointerId, point.x, point.y);
        switch (eventType) {
            case 0x01: // LI_TOUCH_EVENT_DOWN
                handleTouchEventDown(pointerId, point.x, point.y);
                break;
            case 0x02: // LI_TOUCH_EVENT_UP
                handleTouchEventUp(pointerId, point.x, point.y, false);
                break;
            case 0x03: // LI_TOUCH_EVENT_MOVE
                handleTouchEventMove(pointerId, point.x, point.y);
                break;
            case 0x04: // LI_TOUCH_EVENT_CANCEL
                handleTouchEventUp(pointerId, point.x, point.y, true);
                break;
            case 0x07: // LI_TOUCH_EVENT_CANCEL_ALL
                handleTouchEventCancelAll();
                break;
            default:
                Log.e(TAG, "未知的触摸事件类型: " + eventType);
        }
    }

    private static void handleTouchEventDown(int pointerId, float x, float y) {
        if (!bufferedMove.isEmpty()) {
            bufferedMove.clear();
            triggerTouchEventMove();
        }

        // 先保存当前触摸点
        Point point = new Point();
        point.x = x;
        point.y = y;

        // 确定是否是第一个触摸点
        boolean isFirstPointer = pointers.isEmpty();

        // 添加到指针集合
        pointers.put(pointerId, point);

        ArrayList<Integer> pointerIds = new ArrayList<>(pointers.keySet());
        // 确定正确的动作类型
        int action;
        if (isFirstPointer) {
            action = MotionEvent.ACTION_DOWN;
        } else {
            // 查找当前pointerId在所有活跃指针中的索引
            int pointerIndex = 0;
            int i = 0;
            for (Integer id : pointerIds) {
                if (id == pointerId) {
                    pointerIndex = i;
                    break;
                }
                i++;
            }
            action = MotionEvent.ACTION_POINTER_DOWN | (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }

        // 构造 MotionEvent
        long eventTime = SystemClock.uptimeMillis();
        if (isFirstPointer || touchGestureDownTime == 0) {
            touchGestureDownTime = eventTime;
        }
        long downTime = touchGestureDownTime;

        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[pointers.size()];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointers.size()];

        int index = 0;
        for (Integer k : pointerIds) {
            Point status = pointers.get(k);
            properties[index] = new MotionEvent.PointerProperties();
            properties[index].id = k;  // 保持id为原始的pointerId
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;

            coords[index] = new MotionEvent.PointerCoords();
            coords[index].x = status.x;
            coords[index].y = status.y;
            coords[index].pressure = 1.0f;
            index++;
        }

        // 构造 MotionEvent
        MotionEvent event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                pointers.size(), // 使用实际的触摸点数量
                properties,
                coords,
                0, // metaState
                0, // buttonState
                1.0f, // xPrecision
                1.0f, // yPrecision
                0, // deviceId
                0, // edgeFlags
                InputDevice.SOURCE_TOUCHSCREEN,
                0 // flags
        );
        injectEvent("inject down", event);
    }

    private static int getTargetDisplayId() {
        if (dexTargetDisplayId >= 0) {
            return dexTargetDisplayId;
        }
        if (singleAppMode) {
            if (State.mirrorVirtualDisplay == null) {
                return -1;
            }
            return State.mirrorVirtualDisplay.getDisplay().getDisplayId();
        }
        if (externalMirrorMode) {
            return externalMirrorDisplayId;
        }
        return Display.DEFAULT_DISPLAY;
    }

    public static void setDexTargetDisplayId(int displayId) {
        dexTargetDisplayId = displayId;
        State.log("SunshineMouse Dex target displayId=" + displayId);
    }

    private static int getExternalControlDisplayId() {
        return State.externalControlDisplayId > 0 ? State.externalControlDisplayId : State.externalDisplayId;
    }

    private static boolean forwardEventToDisplay(MotionEvent event, int displayId) {
        if (inputManager == null || android.os.Build.VERSION.SDK_INT >= 28) {
            return false;
        }
        try {
            if (injectInputEventOtherScreensMethod == null) {
                injectInputEventOtherScreensMethod = IInputManager.class.getMethod(
                        "injectInputEventOtherScreens", android.view.InputEvent.class, int.class);
            }
            Boolean accepted = (Boolean) injectInputEventOtherScreensMethod.invoke(inputManager, event, 2);
            if (!Boolean.TRUE.equals(accepted)) {
                Log.w(TAG, "injectInputEventOtherScreens rejected event for displayId=" + displayId);
            }
            return Boolean.TRUE.equals(accepted);
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            Log.w(TAG, "injectInputEventOtherScreens failed for displayId=" + displayId + ": "
                    + cause.getClass().getSimpleName() + " " + cause.getMessage());
            return false;
        }
    }

    private static boolean setEventDisplayId(MotionEvent event, int displayId) {
        if (displayId < 0) {
            return false;
        }
        if (displayId == Display.DEFAULT_DISPLAY) {
            return true;
        }
        MotionEventHidden motionEventHidden = Refine.unsafeCast(event);
        motionEventHidden.setDisplayId(displayId);
        return true;
    }

    public static void cleanupCursorOverlay() {
        mainHandler.post(SunshineMouse::cleanupCursorOverlayOnMain);
    }

    private static void cleanupCursorOverlayOnMain() {
        if (cursorWindowManager != null && cursorView != null) {
            try {
                cursorWindowManager.removeView(cursorView);
            } catch (Throwable e) {
                Log.w(TAG, "remove cursor overlay failed: " + e.getMessage());
            }
        }
        cursorWindowManager = null;
        cursorParams = null;
        cursorView = null;
        cursorDisplayId = Integer.MIN_VALUE;
    }

    private static void showCursorOverlay() {
        if (!useAndroidCursorOverlay) {
            return;
        }
        int targetDisplayId = getTargetDisplayId();
        if (targetDisplayId < 0) {
            return;
        }
        mainHandler.post(() -> {
            int currentTargetDisplayId = getTargetDisplayId();
            if (currentTargetDisplayId < 0) {
                return;
            }
            if (cursorView != null && cursorDisplayId == currentTargetDisplayId) {
                cursorView.setVisibility(android.view.View.VISIBLE);
                return;
            }
            cleanupCursorOverlayOnMain();
            Context context = State.getContext();
            if (context == null) {
                return;
            }
            DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager == null) {
                return;
            }
            Display targetDisplay = displayManager.getDisplay(currentTargetDisplayId);
            if (targetDisplay == null) {
                return;
            }
            Context displayContext = context.createDisplayContext(targetDisplay);
            cursorWindowManager = (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);
            if (cursorWindowManager == null) {
                return;
            }

            int cursorSize = Math.max(24, (int) (32 * displayContext.getResources().getDisplayMetrics().density));
            cursorHotspotX = 0;
            cursorHotspotY = 0;
            cursorView = new ImageView(displayContext);
            cursorView.setImageResource(R.drawable.mouse_cursor);
            cursorView.setScaleType(ImageView.ScaleType.FIT_START);
            cursorParams = new WindowManager.LayoutParams(
                    cursorSize,
                    cursorSize,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            cursorParams.gravity = Gravity.TOP | Gravity.START;
            cursorParams.x = 0;
            cursorParams.y = 0;
            try {
                cursorWindowManager.addView(cursorView, cursorParams);
                cursorDisplayId = currentTargetDisplayId;
                State.log("Moonlight cursor overlay shown on displayId=" + currentTargetDisplayId);
            } catch (Throwable e) {
                Log.w(TAG, "show cursor overlay failed: " + e.getMessage(), e);
                cursorWindowManager = null;
                cursorParams = null;
                cursorView = null;
                cursorDisplayId = Integer.MIN_VALUE;
            }
        });
    }

    private static void updateCursorOverlay(float x, float y) {
        if (!useAndroidCursorOverlay) {
            return;
        }
        int targetDisplayId = getTargetDisplayId();
        if (targetDisplayId < 0) {
            return;
        }
        mainHandler.post(() -> {
            if (cursorView == null || cursorParams == null || cursorWindowManager == null || cursorDisplayId != getTargetDisplayId()) {
                showCursorOverlay();
            }
            if (cursorView == null || cursorParams == null || cursorWindowManager == null) {
                return;
            }
            cursorParams.x = Math.round(x) - cursorHotspotX;
            cursorParams.y = Math.round(y) - cursorHotspotY;
            try {
                cursorWindowManager.updateViewLayout(cursorView, cursorParams);
            } catch (Throwable e) {
                Log.w(TAG, "update cursor overlay failed: " + e.getMessage());
            }
        });
    }

    private static void injectMouseHover(float x, float y) {
        injectMouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, 0);
    }

    private static void injectMouseMove(float x, float y) {
        injectMouseEvent(MotionEvent.ACTION_MOVE, x, y, MotionEvent.BUTTON_PRIMARY, 0);
    }

    private static void injectMouseButton(boolean release, float x, float y) {
        if (release) {
            injectMouseEvent(MotionEvent.ACTION_BUTTON_RELEASE, x, y, 0, MotionEvent.BUTTON_PRIMARY);
            injectMouseEvent(MotionEvent.ACTION_UP, x, y, 0, 0);
            mouseDownTime = 0;
        } else {
            mouseDownTime = SystemClock.uptimeMillis();
            injectMouseEvent(MotionEvent.ACTION_DOWN, x, y, MotionEvent.BUTTON_PRIMARY, 0);
            injectMouseEvent(MotionEvent.ACTION_BUTTON_PRESS, x, y, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY);
        }
    }

    private static Point ensureSinglePoint() {
        if (singlePoint != null) {
            return singlePoint;
        }
        Point bounds = getPointerBounds();
        Point point = new Point();
        point.x = bounds.x / 2.0f;
        point.y = bounds.y / 2.0f;
        singlePoint = point;
        return point;
    }

    private static Point getPointerBounds() {
        Point bounds = new Point();
        if (dexTargetDisplayId >= 0) {
            bounds.x = screenWidth;
            bounds.y = screenHeight;
        } else if (singleAppMode) {
            bounds.x = screenWidth;
            bounds.y = screenHeight;
        } else if (externalMirrorMode) {
            bounds.x = externalMirrorWidth > 0 ? externalMirrorWidth : screenWidth;
            bounds.y = externalMirrorHeight > 0 ? externalMirrorHeight : screenHeight;
        } else {
            bounds.x = defaultDisplayWidth > 0 ? defaultDisplayWidth : screenWidth;
            bounds.y = defaultDisplayHeight > 0 ? defaultDisplayHeight : screenHeight;
        }
        bounds.x = Math.max(1.0f, bounds.x);
        bounds.y = Math.max(1.0f, bounds.y);
        return bounds;
    }

    private static void injectMouseScroll(float x, float y, float verticalScroll, float horizontalScroll) {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = x;
        coords[0].y = y;
        coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, verticalScroll);
        coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, horizontalScroll);
        MotionEvent event = MotionEvent.obtain(
                now,
                now,
                MotionEvent.ACTION_SCROLL,
                1,
                properties,
                coords,
                0,
                0,
                1.0f,
                1.0f,
                0,
                0,
                InputDevice.SOURCE_MOUSE,
                0);
        injectEvent("inject mouse scroll", event);
    }

    private static void injectMouseEvent(int action, float x, float y, int buttonState, int actionButton) {
        long now = SystemClock.uptimeMillis();
        long downTime = mouseDownTime != 0 ? mouseDownTime : now;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = buttonState == 0 ? 0.0f : 1.0f;
        MotionEvent event = MotionEvent.obtain(
                downTime,
                now,
                action,
                1,
                properties,
                coords,
                0,
                buttonState,
                1.0f,
                1.0f,
                0,
                0,
                InputDevice.SOURCE_MOUSE,
                0);
        if (actionButton != 0) {
            setActionButton(event, actionButton);
        }
        injectEvent("inject mouse", event);
    }

    private static boolean setActionButton(MotionEvent event, int actionButton) {
        try {
            if (setActionButtonMethod == null) {
                setActionButtonMethod = MotionEvent.class.getMethod("setActionButton", int.class);
            }
            setActionButtonMethod.invoke(event, actionButton);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "setActionButton failed: " + e.getMessage());
            return false;
        }
    }

    private static void injectEvent(String prefix, MotionEvent event) {
        if (autoScale && autoRotateAndScaleForMoonlight != null) {
            autoRotateAndScaleForMoonlight.exitScale();
        }
        int targetDisplayId = getTargetDisplayId();
        if (inputManager != null) {
            if (targetDisplayId != Display.DEFAULT_DISPLAY
                    && android.os.Build.VERSION.SDK_INT < 28) {
                forwardEventToDisplay(event, targetDisplayId);
                if (lastFocusedDisplayId != targetDisplayId) {
                    InputRouting.setFocus(inputManager, targetDisplayId);
                    lastFocusedDisplayId = targetDisplayId;
                }
                if (DEBUG_INPUT_EVENTS) {
                    Log.d(TAG, prefix + " (forwarder): " + event);
                }
                return;
            }
            if (!setEventDisplayId(event, targetDisplayId)) {
                return;
            }
            if (targetDisplayId != Display.DEFAULT_DISPLAY && lastFocusedDisplayId != targetDisplayId) {
                InputRouting.setFocus(inputManager, targetDisplayId);
                lastFocusedDisplayId = targetDisplayId;
            }
            inputManager.injectInputEvent(event, 0);
            if (DEBUG_INPUT_EVENTS) {
                Log.d(TAG, prefix + ": " + event);
            }
        }
    }

    private static void handleTouchEventUp(int pointerId, float x, float y, boolean cancelled) {
        Point status = pointers.get(pointerId);
        if(status == null) {
            return;
        }
        if (!bufferedMove.isEmpty()) {
            bufferedMove.clear();
            triggerTouchEventMove();
        }
        status.x = x;
        status.y = y;

        // 查找当前pointerId在所有活跃指针中的索引
        int pointerIndex = 0;
        int i = 0;
        ArrayList<Integer> pointerIds = new ArrayList<>(pointers.keySet());
        for (Integer id : pointerIds) {
            if (id == pointerId) {
                pointerIndex = i;
                break;
            }
            i++;
        }

        // 确定动作类型
        int action;
        if (pointers.size() == 1) {
            action = MotionEvent.ACTION_UP;
        } else {
            action = MotionEvent.ACTION_POINTER_UP | (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }

        // 构造 MotionEvent
        long eventTime = SystemClock.uptimeMillis();
        long downTime = touchGestureDownTime != 0 ? touchGestureDownTime : eventTime;

        // 创建包含所有活跃触摸点的属性数组
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[pointers.size()];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointers.size()];

        int index = 0;
        for (Integer k : pointerIds) {
            Point ps = pointers.get(k);
            properties[index] = new MotionEvent.PointerProperties();
            properties[index].id = k;  // 保持id为原始的pointerId
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;

            coords[index] = new MotionEvent.PointerCoords();
            coords[index].x = ps.x;
            coords[index].y = ps.y;
            coords[index].pressure = k == pointerId ? 0.0f : 1.0f;
            index++;
        }

        // 构造并注入 MotionEvent
        MotionEvent event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                pointers.size(), // 使用实际的触摸点数量
                properties,
                coords,
                0, // metaState
                0, // buttonState
                1.0f, // xPrecision
                1.0f, // yPrecision
                0, // deviceId
                0, // edgeFlags
                InputDevice.SOURCE_TOUCHSCREEN,
                cancelled ? MotionEvent.FLAG_CANCELED : 0 // flags
        );

        pointers.remove(pointerId);
        if (pointers.isEmpty()) {
            touchGestureDownTime = 0;
        }

        injectEvent("inject up", event);
    }

    private static Set<Integer> bufferedMove = new HashSet<>();

    private static void handleTouchEventMove(int pointerId, float x, float y) {
        Point status = pointers.get(pointerId);
        if (status == null) {
            return;
        }

        // 更新指针位置
        status.x = x;
        status.y = y;

        if (pointers.size() == 1) {
            bufferedMove.clear();
            triggerTouchEventMove();
            return;
        }

        bufferedMove.add(pointerId);
        if (bufferedMove.size() >= pointers.size()) {
            bufferedMove.clear();
            triggerTouchEventMove();
        }
    }

    private static void handleTouchEventCancelAll() {
        // 取消所有触摸事件
        long eventTime = SystemClock.uptimeMillis();
        long downTime = touchGestureDownTime != 0 ? touchGestureDownTime : eventTime;


        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[pointers.size()];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointers.size()];

        int index = 0;
        for (Integer k : pointers.keySet()) {
            Point status = pointers.get(k);
            properties[index] = new MotionEvent.PointerProperties();
            properties[index].id = k;
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;

            coords[index] = new MotionEvent.PointerCoords();
            coords[index].x = status.x;
            coords[index].y = status.y;
            coords[index].pressure = 1.0f;
            index++;
        }

        MotionEvent event = MotionEvent.obtain(
                downTime,
                eventTime,
                android.view.MotionEvent.ACTION_CANCEL,
                pointers.size(),
                properties,
                coords,
                0, 0, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
        );
        pointers.clear();
        touchGestureDownTime = 0;

        injectEvent("inject cancel", event);
    }

    private static void triggerTouchEventMove() {
        if (pointers.isEmpty()) {
            return;
        }
        Point centroid = computeActiveTouchCentroid();
        recordInjectedTouchMoveDebug(centroid.x, centroid.y);
        long eventTime = SystemClock.uptimeMillis();
        long downTime = touchGestureDownTime != 0 ? touchGestureDownTime : eventTime;

        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[pointers.size()];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointers.size()];

        int index = 0;
        for (Integer k : pointers.keySet()) {
            Point status = pointers.get(k);
            properties[index] = new MotionEvent.PointerProperties();
            properties[index].id = k;
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;

            coords[index] = new MotionEvent.PointerCoords();
            coords[index].x = status.x;
            coords[index].y = status.y;
            coords[index].pressure = 1.0f;
            index++;
        }

        MotionEvent event = MotionEvent.obtain(
                downTime,
                eventTime,
                android.view.MotionEvent.ACTION_MOVE,
                pointers.size(),
                properties,
                coords,
                0, 0, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
        );
        injectEvent("inject move", event);
    }

    public static void resetInjectedInputState() {
        if (mouseTouchDownTime != 0 && singlePoint != null) {
            handleTouchEventUp(MOUSE_TOUCH_POINTER_ID, singlePoint.x, singlePoint.y, true);
        }
        if (!pointers.isEmpty()) {
            handleTouchEventCancelAll();
        }
        pointers.clear();
        bufferedMove.clear();
        resetTouchInputDebugStats();
        leftMouseDown = false;
        mouseDownTime = 0;
        mouseTouchDownTime = 0;
        touchGestureDownTime = 0;
        singlePoint = null;
        lastFocusedDisplayId = Integer.MIN_VALUE;
    }

    private static void resetTouchInputDebugStats() {
        synchronized (touchInputDebugLock) {
            touchInputDebugWindowStartMs = SystemClock.uptimeMillis();
            touchInputDebugLastCollectMs = 0;
            lastTouchInputDebugLine = "";
            touchPacketDebugLastPoints.clear();
            touchPacketDebugMinX = 0f;
            touchPacketDebugMaxX = 0f;
            touchPacketDebugMinY = 0f;
            touchPacketDebugMaxY = 0f;
            touchPacketMoveCount = 0;
            touchPacketRepeatedMoveCount = 0;
            touchPacketTinyMoveCount = 0;
            touchPacketTotalStepPx = 0.0;
            touchPacketMaxStepPx = 0.0;
            touchPacketLastMoveAtMs = 0;
            touchPacketTotalGapMs = 0.0;
            touchPacketMaxGapMs = 0.0;
            touchPacketGapOver33MsCount = 0;
            touchPacketGapOver50MsCount = 0;
            injectedMoveDebugLastPoint = null;
            injectedMoveCount = 0;
            injectedRepeatedMoveCount = 0;
            injectedTinyMoveCount = 0;
            injectedTotalStepPx = 0.0;
            injectedMaxStepPx = 0.0;
            injectedLastMoveAtMs = 0;
            injectedTotalGapMs = 0.0;
            injectedMaxGapMs = 0.0;
            injectedGapOver33MsCount = 0;
            injectedGapOver50MsCount = 0;
        }
    }

    private static void recordTouchPacketDebug(int eventType, int pointerId, float x, float y) {
        synchronized (touchInputDebugLock) {
            long now = SystemClock.uptimeMillis();
            Point current = new Point();
            current.x = x;
            current.y = y;
            if (touchPacketDebugLastPoints.isEmpty()) {
                touchPacketDebugMinX = x;
                touchPacketDebugMaxX = x;
                touchPacketDebugMinY = y;
                touchPacketDebugMaxY = y;
            } else {
                touchPacketDebugMinX = Math.min(touchPacketDebugMinX, x);
                touchPacketDebugMaxX = Math.max(touchPacketDebugMaxX, x);
                touchPacketDebugMinY = Math.min(touchPacketDebugMinY, y);
                touchPacketDebugMaxY = Math.max(touchPacketDebugMaxY, y);
            }
            if (eventType == 0x03) {
                touchPacketMoveCount++;
                if (touchPacketLastMoveAtMs != 0) {
                    double gapMs = now - touchPacketLastMoveAtMs;
                    touchPacketTotalGapMs += gapMs;
                    touchPacketMaxGapMs = Math.max(touchPacketMaxGapMs, gapMs);
                    if (gapMs > 33.0) {
                        touchPacketGapOver33MsCount++;
                    }
                    if (gapMs > 50.0) {
                        touchPacketGapOver50MsCount++;
                    }
                }
                touchPacketLastMoveAtMs = now;
                Point previous = touchPacketDebugLastPoints.get(pointerId);
                if (previous != null) {
                    double dx = x - previous.x;
                    double dy = y - previous.y;
                    double distance = Math.hypot(dx, dy);
                    touchPacketTotalStepPx += distance;
                    touchPacketMaxStepPx = Math.max(touchPacketMaxStepPx, distance);
                    if (distance < 0.01) {
                        touchPacketRepeatedMoveCount++;
                    }
                    if (distance < 1.0) {
                        touchPacketTinyMoveCount++;
                    }
                }
            }
            if (eventType == 0x07) {
                touchPacketDebugLastPoints.clear();
            } else if (eventType == 0x02 || eventType == 0x04) {
                touchPacketDebugLastPoints.remove(pointerId);
            } else {
                touchPacketDebugLastPoints.put(pointerId, current);
            }
        }
    }

    private static void recordInjectedTouchMoveDebug(float x, float y) {
        synchronized (touchInputDebugLock) {
            long now = SystemClock.uptimeMillis();
            injectedMoveCount++;
            if (injectedLastMoveAtMs != 0) {
                double gapMs = now - injectedLastMoveAtMs;
                injectedTotalGapMs += gapMs;
                injectedMaxGapMs = Math.max(injectedMaxGapMs, gapMs);
                if (gapMs > 33.0) {
                    injectedGapOver33MsCount++;
                }
                if (gapMs > 50.0) {
                    injectedGapOver50MsCount++;
                }
            }
            injectedLastMoveAtMs = now;
            Point current = new Point();
            current.x = x;
            current.y = y;
            if (injectedMoveDebugLastPoint != null) {
                double dx = x - injectedMoveDebugLastPoint.x;
                double dy = y - injectedMoveDebugLastPoint.y;
                double distance = Math.hypot(dx, dy);
                injectedTotalStepPx += distance;
                injectedMaxStepPx = Math.max(injectedMaxStepPx, distance);
                if (distance < 0.01) {
                    injectedRepeatedMoveCount++;
                }
                if (distance < 1.0) {
                    injectedTinyMoveCount++;
                }
            }
            injectedMoveDebugLastPoint = current;
        }
    }

    private static Point computeActiveTouchCentroid() {
        Point centroid = new Point();
        if (pointers.isEmpty()) {
            return centroid;
        }
        for (Point point : pointers.values()) {
            centroid.x += point.x;
            centroid.y += point.y;
        }
        centroid.x /= pointers.size();
        centroid.y /= pointers.size();
        return centroid;
    }
}

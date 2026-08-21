package com.connect_screen.mirror.shizuku;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.Surface;

interface IUserService {

    void destroy() = 16777114; // Destroy method defined by Shizuku server

    void exit() = 1; // Exit method defined by user

    String fetchLogs() = 2;

    String executeCommand(String command) = 3;

    boolean setScreenPower(int powerMode) = 4;
    boolean setScreenPowerForDisplay(int displayId, int powerMode) = 28;
    boolean pressPowerKey() = 29;

    void startListenVolumeKey() = 5;

    void stopListenVolumeKey() = 6;

    int createVirtualDisplay(in Surface surface) = 7;

    boolean isRooted() = 8;

    int readAudio(out float[] buffer) = 9;

    boolean startRecordingAudio() = 10;

    boolean stopRecordingAudio() = 11;

    IBinder createDisplay(String name, boolean secure) = 12;

    int createExternalMirror(String name, int width, int height, int displayIdToMirror, int frameRate, in Surface surface) = 13;
    void destroyExternalMirror() = 14;
    String executeShellCommand(String command) = 15;
   int redirectDisplayToSurface(int displayId, in Surface surface) = 18;
   boolean forceTntAudioRoute(boolean enabled) = 19;
   int createDexMirror(String name, int width, int height, int frameRate, in Surface surface) = 23;
   boolean writeDexWallpaper(in Bitmap bitmap) = 21;
   void restartSecondaryLauncher(int displayId, int width, int height) = 22;
   void startSecondaryLauncher(int displayId, int width, int height) = 24;
   String getHdmiCfgIdx() = 25;
   String setHdmiCfgIdx(String value) = 26;
   int applyExternalDisplayMode(int displayId, int width, int height, int refresh) = 27;
   int startDpMirror(int externalDisplayId, int sourceDisplayId, int outWidth, int outHeight) = 30;
   int stopDpMirror(int externalDisplayId) = 31;
   int startDpMirrorWithGeometry(int externalDisplayId, int sourceDisplayId, int outWidth, int outHeight,
                                 int orientation, in Rect layerStackRect, in Rect displayRect) = 32;
   int resetDpMirror(int externalDisplayId) = 33;
   int mirrorPhoneToExternal(int externalDisplayId, int sourceDisplayId, int outWidth, int outHeight,
                             int orientation, in Rect layerStackRect, in Rect displayRect) = 37;
   String executeRootShellCommand(String command) = 34;
   String getEnvironmentInfo() = 35;
   int stopSecondaryLauncher(int displayId) = 36;
   String fetchLspLogs() = 38;
}

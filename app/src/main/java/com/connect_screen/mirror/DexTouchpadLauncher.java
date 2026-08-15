package com.connect_screen.mirror;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import android.widget.Toast;

public final class DexTouchpadLauncher {
    private static final String COMPONENT = "com.android.systemui/.dextouchpad.activity.TouchpadActivity";

    private DexTouchpadLauncher() {
    }

    public static void launch(Context context) {
        int displayId = pickCoverDisplay(context);
        String displayArg = displayId >= 0 ? " --display " + displayId : "";
        State.log("DeX touchpad launch displayId=" + displayId);
        try {
            Intent intent = new Intent();
            intent.setComponent(ComponentName.unflattenFromString(COMPONENT));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && displayId >= 0) {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(displayId);
                context.startActivity(intent, options.toBundle());
            } else {
                context.startActivity(intent);
            }
            return;
        } catch (Exception directFailure) {
            State.log("DeX touchpad direct launch failed: " + directFailure.getMessage());
        }
        if (State.isUserServiceAlive()) {
            try {
                State.userService.executeCommand("am start" + displayArg + " -n " + COMPONENT);
                return;
            } catch (Exception shellFailure) {
                State.log("DeX touchpad shell launch failed: " + shellFailure.getMessage());
            }
            try {
                State.userService.executeCommand(
                        "su -c 'am start" + displayArg + " -n " + COMPONENT + "'");
                return;
            } catch (Exception rootFailure) {
                State.log("DeX touchpad root launch failed: " + rootFailure.getMessage());
            }
        }
        Toast.makeText(context, "无法打开 DeX 触控板", Toast.LENGTH_SHORT).show();
    }

    // On the Flip5 the cover screen is a second internal display. Launching on
    // display 0 while folded makes SystemUI mirror the touchpad from the off
    // main display, so its layout/coordinates never match the cover screen and
    // cursor injection does not take effect.
    private static int pickCoverDisplay(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return -1;
        }
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            return -1;
        }
        Point defaultSize = new Point();
        Display defaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (defaultDisplay != null) {
            defaultDisplay.getRealSize(defaultSize);
        }
        for (Display display : dm.getDisplays()) {
            if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
                continue;
            }
            try {
                if (display.getState() != Display.STATE_ON) {
                    continue;
                }
            } catch (Throwable t) {
                continue;
            }
            String name = display.getName();
            if (name != null && (name.startsWith("dex-anywhere-")
                    || name.startsWith("LibreDeX")
                    || name.startsWith("Moonlight-"))) {
                continue;
            }
            Point size = new Point();
            display.getRealSize(size);
            if (defaultSize.x > 0 && defaultSize.y > 0
                    && size.x < defaultSize.x && size.y < defaultSize.y) {
                return display.getDisplayId();
            }
        }
        return -1;
    }
}

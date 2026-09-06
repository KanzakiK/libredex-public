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
    private DexTouchpadLauncher() {
    }

    public static void launch(Context context) {
        int displayId = pickCoverDisplay(context);
        String displayArg = displayId >= 0 ? " --display " + displayId : "";
        State.log("DeX touchpad launch displayId=" + displayId);
        // The activity host is the installed applicationId (com.libredex), but the
        // class package is com.connect_screen.mirror. Build the intent from the class
        // so the host is resolved from the app, not hard-coded.
        try {
            Intent intent = new Intent(context, DexTouchpadActivity.class);
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
        String component = context.getPackageName() + "/" + DexTouchpadActivity.class.getName();
        if (State.isUserServiceAlive()) {
            try {
                State.userService.executeCommand("am start" + displayArg + " -n " + component);
                return;
            } catch (Exception shellFailure) {
                State.log("DeX touchpad shell launch failed: " + shellFailure.getMessage());
            }
            try {
                State.userService.executeCommand(
                        "su -c 'am start" + displayArg + " -n " + component + "'");
                return;
            } catch (Exception rootFailure) {
                State.log("DeX touchpad root launch failed: " + rootFailure.getMessage());
            }
        }
        Toast.makeText(context, context.getString(R.string.touchpad_open_failed), Toast.LENGTH_SHORT).show();
    }

    /**
     * Launches the OEM (SystemUI) DeX touchpad instead of the self-drawn one.
     * Used by the DeX manage page entry; the connection page keeps using the
     * self-drawn {@link #launch(Context)}. Same display pick + fallback chain as
     * launch(): direct start with launch display, then shell am start.
     */
    public static void launchSystem(Context context) {
        final String component = "com.android.systemui/.dextouchpad.activity.TouchpadActivity";
        int displayId = pickCoverDisplay(context);
        String displayArg = displayId >= 0 ? " --display " + displayId : "";
        State.log("DeX system touchpad launch displayId=" + displayId);
        try {
            Intent intent = new Intent();
            intent.setComponent(ComponentName.unflattenFromString(component));
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
            State.log("DeX system touchpad direct launch failed: "
                    + directFailure.getMessage());
        }
        if (State.isUserServiceAlive()) {
            try {
                State.userService.executeCommand("am start" + displayArg + " -n " + component);
                return;
            } catch (Exception shellFailure) {
                State.log("DeX system touchpad shell launch failed: "
                        + shellFailure.getMessage());
            }
            try {
                State.userService.executeCommand(
                        "su -c 'am start" + displayArg + " -n " + component + "'");
                return;
            } catch (Exception rootFailure) {
                State.log("DeX system touchpad root launch failed: "
                        + rootFailure.getMessage());
            }
        }
        Toast.makeText(context, context.getString(R.string.touchpad_open_failed), Toast.LENGTH_SHORT).show();
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
                    || name.startsWith(context.getString(R.string.notify_sunshine_title))
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

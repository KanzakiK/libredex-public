package com.connect_screen.mirror.job;


import android.content.Context;
import android.app.ActivityTaskManager;
import android.hardware.input.IInputManager;
import android.hardware.input.InputManager;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.view.DisplayAddress;
import android.view.DisplayInfo;
import android.view.InputDevice;
import android.widget.Toast;

import com.connect_screen.mirror.Pref;
import com.connect_screen.mirror.State;
import com.connect_screen.mirror.shizuku.ServiceUtils;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import com.connect_screen.mirror.R;

public class InputRouting {
    public static Map<String, String> getInputDeviceDescriptorToPortMap() {
        if (State.userService == null) {
            State.log("user service not running, cannot get input device descriptor -> port mapping");
            return new HashMap<>();
        }
        Map<String, String> map = new HashMap<>();
        try {
            String inputDump = State.userService.executeCommand("dumpsys input");
            String[] lines = inputDump.split("\n");
            String lastDescriptor = "";
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("Descriptor:")) {
                    lastDescriptor = line.substring("Descriptor:".length()).trim();
                }
                if (line.startsWith("Location:")) {
                    String inputPort = line.substring("Location:".length()).trim();
                    map.put(lastDescriptor, inputPort);
                }
            }
        } catch (RemoteException ex) {
            throw new RuntimeException(ex);
        }
        return map;
    }

    public static void bindInputToDisplay(DisplayInfo displayInfo, InputDevice inputDevice, IInputManager inputManager, Map<String, String> inputDeviceDescriptorToPortMap) {
        if (displayInfo == null || inputDevice == null || inputManager == null || !inputDevice.isExternal()) {
            return;
        }
        State.log("Trying to bind device " + inputDevice.getId());
        try {
            inputManager.removeUniqueIdAssociationByDescriptor(inputDevice.getDescriptor());
            inputManager.addUniqueIdAssociationByDescriptor(inputDevice.getDescriptor(), String.valueOf(displayInfo.uniqueId));
            State.log("Successfully updated input device routing: " + inputDevice.getName() + ", " + inputDevice.getDescriptor());
        } catch(Throwable e) {
            String inputPort = inputDeviceDescriptorToPortMap.get(inputDevice.getDescriptor());
            if (inputPort == null) {
                State.log("Failed to update input device routing: " + inputDevice + ", " + e.getMessage());
            } else {
                try {
                    inputManager.removeUniqueIdAssociation(inputPort);
                    inputManager.addUniqueIdAssociation(inputPort, String.valueOf(displayInfo.uniqueId));
                    State.log("Successfully updated input device routing: " + inputDevice.getName() + ", " + inputPort + " => " + displayInfo.uniqueId);
                } catch(Throwable e2) {
                    try {
                        inputManager.removePortAssociation(inputPort);
                        int displayPort = ((DisplayAddress.Physical) displayInfo.address).getPort();
                        inputManager.addPortAssociation(inputPort, displayPort);
                        State.log("Successfully updated input device routing: " + inputDevice.getName() + ", " + inputPort + " => " + displayPort);
                    } catch(Throwable e3) {
                        State.log("Falling back to input port still failed to update routing: " + inputDevice.getName() + ", " + e3.getMessage());
                        Context context = State.getContext();
                        if (ShizukuUtils.hasPermission() && context != null) {
                            Toast.makeText(context, context.getString(R.string.input_simulate_off_required), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            }
        }
    }

    public static InputDevice findInputDevice(InputManager inputManager, UsbDevice usbDevice) {
        for(int inputDeviceId : inputManager.getInputDeviceIds()) {
            InputDevice inputDevice = inputManager.getInputDevice(inputDeviceId);
            if (inputDevice.isExternal() && inputDevice.getVendorId() == usbDevice.getVendorId() && inputDevice.getProductId() == usbDevice.getProductId()) {
                return inputDevice;
            }
        }
        return null;
    }

    public static void bindAllExternalInputToDisplay(int displayId) {
        if (!shouldBind()) {
            State.log("Skipping binding peripheral to display: " + displayId);
            return;
        }
        DisplayInfo displayInfo = ServiceUtils.getDisplayManager().getDisplayInfo(displayId);
        IInputManager inputManager = ServiceUtils.getInputManager();
        Map<String, String> inputDeviceDescriptorToPortMap = InputRouting.getInputDeviceDescriptorToPortMap();
        for (int deviceId : inputManager.getInputDeviceIds()) {
            InputDevice inputDevice = inputManager.getInputDevice(deviceId);
            InputRouting.bindInputToDisplay(displayInfo, inputDevice, inputManager, inputDeviceDescriptorToPortMap);
        }
    }

    /**
     * Reset every external input device's routing back to the default display.
     *
     * The legacy (deX) output path associates external inputs to the external
     * display's uniqueId via {@link #bindInputToDisplay}. That association is
     * left behind after the session stops/unplugs, so a mouse stays routed to a
     * display that no longer exists and its pointer keeps getting canceled
     * (cursor invisible / input dead). Call this on session teardown.
     */
    public static void unbindAllExternalInputToDefaultDisplay() {
        if (!shouldBind()) {
            return;
        }
        IInputManager inputManager = ServiceUtils.getInputManager();
        if (inputManager == null) {
            return;
        }
        for (int deviceId : inputManager.getInputDeviceIds()) {
            InputDevice inputDevice = inputManager.getInputDevice(deviceId);
            if (inputDevice == null || !inputDevice.isExternal()) {
                continue;
            }
            try {
                inputManager.removeUniqueIdAssociationByDescriptor(inputDevice.getDescriptor());
                State.log("Reset external input routing to default: " + inputDevice.getName());
            } catch (Throwable e) {
                State.log("Failed to reset routing for " + inputDevice.getName() + ": " + e.getMessage());
            }
        }
    }

    private static final AtomicBoolean sListenerAttached = new AtomicBoolean(false);
    private static volatile Integer sListenerDisplayId;

    /**
     * Re-bind external inputs to the given display whenever an external input
     * device is added while the session is live. {@link #bindAllExternalInputToDisplay}
     * only covers devices already enumerated at start; a mouse that appears on
     * (re)plug just after that would otherwise keep the default routing.
     */
    public static void attachInputDeviceListener(Context context, int displayId) {
        if (!shouldBind() || context == null) {
            return;
        }
        try {
            InputManager inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            if (inputManager == null) {
                return;
            }
            detachInputDeviceListener();
            sListenerDisplayId = displayId;
            sListener = new InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int deviceId) {
                    Integer target = sListenerDisplayId;
                    if (target == null) {
                        return;
                    }
                    try {
                        IInputManager manager = ServiceUtils.getInputManager();
                        if (manager == null) {
                            return;
                        }
                        InputDevice device = manager.getInputDevice(deviceId);
                        if (device != null && device.isExternal()) {
                            bindInputToDisplay(
                                    ServiceUtils.getDisplayManager().getDisplayInfo(target),
                                    device, manager, getInputDeviceDescriptorToPortMap());
                        }
                    } catch (Throwable e) {
                        State.log("External input hot-plug re-binding failed: " + e.getMessage());
                    }
                }

                @Override
                public void onInputDeviceRemoved(int deviceId) {
                }

                @Override
                public void onInputDeviceChanged(int deviceId) {
                }
            };
            if (sListenerAttached.compareAndSet(false, true)) {
                // Touch a real main-thread Handler so the registration never
                // builds a Handler on a non-Looper thread (this can run on the
                // background DeX startup thread).
                inputManager.registerInputDeviceListener(sListener,
                        new Handler(Looper.getMainLooper()));
                State.log("Attached external input hot-plug re-binding to display: " + displayId);
            }
        } catch (Throwable e) {
            State.log("Attach external input hot-plug listener failed: " + e.getMessage());
        }
    }

    public static void detachInputDeviceListener() {
        Context context = State.getContext();
        InputManager inputManager = context != null
                ? (InputManager) context.getSystemService(Context.INPUT_SERVICE)
                : null;
        if (inputManager != null && sListener != null
                && sListenerAttached.compareAndSet(true, false)) {
            inputManager.unregisterInputDeviceListener(sListener);
            State.log("Detached external input hot-plug re-binding");
        }
        sListener = null;
        sListenerDisplayId = null;
    }

    private static InputManager.InputDeviceListener sListener;

    public static void setFocus(IInputManager inputManager, int displayId) {
        if (inputManager == null) {
            return;
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceUtils.getActivityTaskManager().focusTopTask(displayId);
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                List<ActivityTaskManager.RootTaskInfo> taskInfos =
                        ServiceUtils.getActivityTaskManager().getAllRootTaskInfosOnDisplay(displayId);
                for (ActivityTaskManager.RootTaskInfo taskInfo : taskInfos) {
                    ServiceUtils.getActivityTaskManager().setFocusedRootTask(taskInfo.taskId);
                    break;
                }
            } else {
                List<Object> stackInfos =
                        ServiceUtils.getActivityTaskManager().getAllStackInfosOnDisplay(displayId);
                if (!stackInfos.isEmpty()) {
                    Object stackInfo = stackInfos.get(0);
                    Field stackIdField = stackInfo.getClass().getDeclaredField("stackId");
                    stackIdField.setAccessible(true);
                    int stackId = stackIdField.getInt(stackInfo);
                    ServiceUtils.getActivityTaskManager().setFocusedStack(stackId);
                }
            }
        } catch (Throwable e) {
            State.log("setDisplayFocus failed: " + e.getMessage());
        }
    }

    private static boolean shouldBind() {
        try {
            return Pref.getAutoBindInput();
        } catch(Exception e) {
            // ignore
        }
        return true;
    }
}

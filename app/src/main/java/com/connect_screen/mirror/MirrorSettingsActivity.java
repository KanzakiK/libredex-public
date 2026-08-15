package com.connect_screen.mirror;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.WallpaperManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.Display;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.connect_screen.mirror.job.AcquireShizuku;
import com.connect_screen.mirror.job.ConnectToClient;
import com.connect_screen.mirror.job.SunshineServer;
import com.connect_screen.mirror.shizuku.PermissionManager;
import com.connect_screen.mirror.shizuku.ShizukuUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MirrorSettingsActivity extends AppCompatActivity {
    public static final String PREF_NAME = "mirror_settings";
    private static final String MANUAL_INPUT_LABEL = "Manual input";

    private SharedPreferences preferences;
    private TextView currentEncoderSettingsText;
    private ActivityResultLauncher<Intent> wallpaperPicker;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mirror_settings);
        preferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Settings take effect next time the feature starts");
        }

        SwitchCompat autoRotateCheckbox = findViewById(R.id.autoRotateCheckbox);
        SwitchCompat autoScaleCheckbox = findViewById(R.id.autoScaleCheckbox);
        SwitchCompat autoScreenOffCheckbox = findViewById(R.id.autoScreenOffCheckbox);
        SwitchCompat disableUsbAudioCheckbox = findViewById(R.id.disableUsbAudioCheckbox);
        SwitchCompat autoMatchAspectRatioCheckbox = findViewById(R.id.autoMatchAspectRatioCheckbox);
        SwitchCompat autoConnectClientCheckbox = findViewById(R.id.autoConnectClientCheckbox);
        SwitchCompat useBlackImageCheckbox = findViewById(R.id.useBlackImageCheckbox);
        SwitchCompat preventAutoLockCheckbox = findViewById(R.id.preventAutoLockCheckbox);
        SwitchCompat useAndroidCursorOverlayCheckbox = findViewById(R.id.useAndroidCursorOverlayCheckbox);
        SwitchCompat mapMouseToTouchCheckbox = findViewById(R.id.mapMouseToTouchCheckbox);

        styleSwitch(autoRotateCheckbox);
        styleSwitch(autoScaleCheckbox);
        styleSwitch(autoScreenOffCheckbox);
        styleSwitch(disableUsbAudioCheckbox);
        styleSwitch(autoMatchAspectRatioCheckbox);
        styleSwitch(autoConnectClientCheckbox);
        styleSwitch(useBlackImageCheckbox);
        styleSwitch(preventAutoLockCheckbox);
        styleSwitch(useAndroidCursorOverlayCheckbox);
        styleSwitch(mapMouseToTouchCheckbox);

        LinearLayout clientConnectionContainer = findViewById(R.id.clientConnectionContainer);
        Spinner clientSpinner = findViewById(R.id.clientSpinner);
        Button connectClientButton = findViewById(R.id.connectClientButton);
        TextView shizukuStatus = findViewById(R.id.shizukuStatus);
        TextView overlayStatus = findViewById(R.id.overlayStatus);
        Button shizukuPermissionBtn = findViewById(R.id.shizukuPermissionBtn);

        autoRotateCheckbox.setChecked(Pref.getAutoRotate());
        autoScaleCheckbox.setChecked(Pref.getAutoScale());
        autoScreenOffCheckbox.setChecked(Pref.getAutoScreenOff());
        disableUsbAudioCheckbox.setChecked(Pref.getDisableUsbAudio());
        autoMatchAspectRatioCheckbox.setChecked(Pref.getAutoMatchAspectRatio());
        autoConnectClientCheckbox.setChecked(Pref.getAutoConnectClient());
        useBlackImageCheckbox.setChecked(Pref.getUseBlackImage());
        preventAutoLockCheckbox.setChecked(Pref.getPreventAutoLock());
        useAndroidCursorOverlayCheckbox.setChecked(Pref.getUseAndroidCursorOverlay());
        mapMouseToTouchCheckbox.setChecked(Pref.getMapMouseToTouch());

        if (ShizukuUtils.hasPermission()) {
            autoScreenOffCheckbox.setText("Auto screen off");
        }

        updateShizukuStatus(shizukuStatus, shizukuPermissionBtn);
        updateOverlayStatus(overlayStatus);

        shizukuPermissionBtn.setOnClickListener(v -> State.startNewJob(new AcquireShizuku()));

        Button initializationGuideButton = findViewById(R.id.initializationGuideButton);
        initializationGuideButton.setOnClickListener(v -> InitializationGuideDialog.show(this));

        Button wallpaperButton = findViewById(R.id.wallpaperButton);
        wallpaperPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK
                            && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            applyPickedWallpaper(uri);
                        }
                    }
                });
        wallpaperButton.setOnClickListener(v -> openWallpaperPicker());

        Button aboutButton = findViewById(R.id.aboutButton);
        aboutButton.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));

        Button screenSettingsButton = findViewById(R.id.screenSettingsButton);
        screenSettingsButton.setOnClickListener(v -> startActivity(new Intent(this, ScreenSettingsActivity.class)));

        Button viewRecentHandshakeButton = findViewById(R.id.viewRecentHandshakeButton);
        viewRecentHandshakeButton.setOnClickListener(v -> showLastMoonlightHandshakeDialog());

        Button viewRecentControlInputButton = findViewById(R.id.viewRecentControlInputButton);
        viewRecentControlInputButton.setOnClickListener(v -> showLastMoonlightControlInputDialog());


        autoRotateCheckbox.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(Pref.KEY_AUTO_ROTATE, isChecked).apply());
        autoScaleCheckbox.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(Pref.KEY_AUTO_SCALE, isChecked).apply());
        autoScreenOffCheckbox.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(Pref.KEY_AUTO_SCREEN_OFF, isChecked).apply());
        autoMatchAspectRatioCheckbox.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(Pref.KEY_AUTO_MATCH_ASPECT_RATIO, isChecked).apply());
        useBlackImageCheckbox.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(Pref.KEY_USE_BLACK_IMAGE, isChecked).apply());
        preventAutoLockCheckbox.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(Pref.KEY_PREVENT_AUTO_LOCK, isChecked).apply());
        useAndroidCursorOverlayCheckbox.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(Pref.KEY_USE_ANDROID_CURSOR_OVERLAY, isChecked).apply());
        mapMouseToTouchCheckbox.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(Pref.KEY_MAP_MOUSE_TO_TOUCH, isChecked).apply());

        disableUsbAudioCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            preferences.edit().putBoolean(Pref.KEY_DISABLE_USB_AUDIO, isChecked).apply();
            if (ShizukuUtils.hasPermission() && PermissionManager.grant("android.permission.WRITE_SECURE_SETTINGS")) {
                try {
                    Settings.Secure.putInt(
                            getContentResolver(),
                            "usb_audio_automatic_routing_disabled",
                            isChecked ? 1 : 0);
                } catch (SecurityException e) {
                    State.log("failed to set usb_audio_automatic_routing_disabled: " + e);
                }
            }
        });

        boolean autoConnectClient = Pref.getAutoConnectClient();
        clientConnectionContainer.setVisibility(autoConnectClient ? View.VISIBLE : View.GONE);
        autoConnectClientCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            preferences.edit().putBoolean(Pref.KEY_AUTO_CONNECT_CLIENT, isChecked).apply();
            clientConnectionContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (isChecked) {
                loadClientList(clientSpinner);
            }
        });
        if (autoConnectClient) {
            loadClientList(clientSpinner);
        }

        connectClientButton.setOnClickListener(v -> {
            String selectedClient = (String) clientSpinner.getSelectedItem();
            if (selectedClient == null || selectedClient.isEmpty()) {
                return;
            }
            if (MANUAL_INPUT_LABEL.equals(selectedClient)) {
                showManualInputDialog();
                return;
            }
            preferences.edit().putString(Pref.KEY_SELECTED_CLIENT, selectedClient).apply();
            int pin = (int) (Math.random() * 9000) + 1000;
            SunshineServer.suppressPin = String.valueOf(pin);
            ConnectToClient.connect(pin);
        });

        if (!ShizukuUtils.hasPermission()) {
            disableUsbAudioCheckbox.setEnabled(false);
            autoMatchAspectRatioCheckbox.setEnabled(false);
            preventAutoLockCheckbox.setEnabled(false);
        }

        currentEncoderSettingsText = findViewById(R.id.currentEncoderSettingsText);
        updateEncoderSettingsText();
    }

    private void showLastMoonlightHandshakeDialog() {
        String handshakeInfo = State.lastMoonlightHandshakeInfo;
        if (handshakeInfo == null || handshakeInfo.trim().isEmpty()) {
            handshakeInfo = "尚无最近一次 Moonlight 连接握手信息";
        }
        showReadonlyDebugDialog("最近握手信息", handshakeInfo, "Moonlight 握手信息", "已复制握手信息");
    }

    private void showLastMoonlightControlInputDialog() {
        String controlInputInfo = State.lastMoonlightControlInputInfo;
        if (controlInputInfo == null || controlInputInfo.trim().isEmpty()) {
            controlInputInfo = "尚无最近一次 Moonlight 控制输入统计";
        }
        showReadonlyDebugDialog("最近控制输入统计", controlInputInfo, "Moonlight 控制输入统计", "已复制控制输入统计");
    }

    private void openWallpaperPicker() {
        if (SunshineServer.activeDexDisplayId < 0) {
            Toast.makeText(this, "请先连接 DeX", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        wallpaperPicker.launch(Intent.createChooser(intent, "选择 DeX 壁纸"));
    }

    private void applyPickedWallpaper(Uri uri) {
        new Thread(() -> {
            try {
                int[] size = dexDisplaySize();
                Bitmap bitmap = decodeCenterCrop(uri, size[0], size[1]);
                if (bitmap == null) {
                    State.log("wallpaper decode failed uri=" + uri);
                    return;
                }
                State.log("wallpaper decoded " + bitmap.getWidth()
                        + "x" + bitmap.getHeight());
                trySetDexWallpaper(bitmap);
            } catch (Throwable t) {
                State.log("wallpaper picker apply failed uri=" + uri
                        + " err=" + t);
            }
        }).start();
    }

    private int[] dexDisplaySize() {
        try {
            DisplayManager displayManager =
                    (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display display = displayManager.getDisplay(SunshineServer.activeDexDisplayId);
            if (display != null) {
                Point size = new Point();
                display.getRealSize(size);
                if (size.x > 0 && size.y > 0) {
                    return new int[]{size.x, size.y};
                }
            }
        } catch (Throwable ignored) {
        }
        return new int[]{1920, 1080};
    }

    private Bitmap decodeCenterCrop(Uri uri, int targetW, int targetH) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, bounds);
        } catch (Throwable t) {
            State.log("wallpaper bounds read failed: " + t);
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetW
                && bounds.outHeight / (sample * 2) >= targetH) {
            sample *= 2;
        }
        State.log("wallpaper bounds " + bounds.outWidth + "x" + bounds.outHeight
                + " target " + targetW + "x" + targetH + " sample=" + sample);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap src;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            src = BitmapFactory.decodeStream(is, null, opts);
        } catch (Throwable t) {
            State.log("wallpaper decode stream failed: " + t);
            return null;
        }
        if (src == null) {
            State.log("wallpaper sampled decode returned null");
            return null;
        }
        State.log("wallpaper sampled " + src.getWidth() + "x" + src.getHeight());
        float scale = Math.max(
                (float) targetW / src.getWidth(),
                (float) targetH / src.getHeight());
        int sw = Math.round(src.getWidth() * scale);
        int sh = Math.round(src.getHeight() * scale);
        Bitmap scaled = Bitmap.createScaledBitmap(src, sw, sh, true);
        if (scaled != src) {
            src.recycle();
        }
        int x = Math.max(0, (sw - targetW) / 2);
        int y = Math.max(0, (sh - targetH) / 2);
        int cw = Math.min(targetW, scaled.getWidth());
        int ch = Math.min(targetH, scaled.getHeight());
        Bitmap cropped = Bitmap.createBitmap(scaled, x, y, cw, ch);
        if (cropped != scaled) {
            scaled.recycle();
        }
        return cropped;
    }

    private void trySetDexWallpaper(Bitmap bitmap) {
        int displayId = SunshineServer.activeDexDisplayId;
        if (displayId < 0) {
            Toast.makeText(this, "请先连接 DeX", Toast.LENGTH_SHORT).show();
            return;
        }
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display display = displayManager.getDisplay(displayId);
        if (display == null) {
            Toast.makeText(this, "DeX display 不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        Context displayContext = createDisplayContext(display);
        new Thread(() -> {
            try {
                WallpaperManager wallpaperManager = WallpaperManager.getInstance(displayContext);
                Method setBitmap = WallpaperManager.class.getMethod(
                        "setBitmap", Bitmap.class, Rect.class, boolean.class, int.class);
                int semFlagDex = WallpaperManager.class.getField("SEM_FLAG_DEX").getInt(null);
                int which = WallpaperManager.FLAG_SYSTEM | semFlagDex;
                setBitmap.invoke(wallpaperManager, bitmap, null, false, which);
                State.log("DeX wallpaper setBitmap OK displayId=" + displayId + " which=" + which);
                writeWallpaperFile(bitmap);
                runOnUiThread(() -> Toast.makeText(this, "display 壁纸 API 已调用", Toast.LENGTH_SHORT).show());
            } catch (Throwable e) {
                State.log("DeX wallpaper setBitmap failed: " + e);
                if (e.getCause() != null) {
                    State.log("DeX wallpaper setBitmap cause: " + e.getCause());
                }
                tryDexManagerWallpaper(bitmap);
            }
        }).start();
    }

    private void writeWallpaperFile(Bitmap bitmap) {
        try {
            if (State.userService != null && State.userService.writeDexWallpaper(bitmap)) {
                State.log("launcher wallpaper file written");
            } else {
                State.log("launcher wallpaper file write skipped/failed");
            }
        } catch (Throwable t) {
            State.log("launcher wallpaper file write error: " + t);
        }
    }

    private void tryDexManagerWallpaper(Bitmap bitmap) {
        try {
            Class<?> dexManagerClass = Class.forName("com.samsung.android.knox.dex.DexManager");
            Object instance = dexManagerClass.getMethod("getInstance").invoke(null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            InputStream inputStream = new ByteArrayInputStream(baos.toByteArray());
            Method setWallpaper = dexManagerClass.getMethod(
                    "setWallpaper", Context.class, InputStream.class, Rect.class, boolean.class, int.class);
            int rc = (Integer) setWallpaper.invoke(instance, this, inputStream, null, false, 1);
            State.log("DexManager.setWallpaper rc=" + rc);
            runOnUiThread(() -> Toast.makeText(this, "DexManager rc=" + rc, Toast.LENGTH_SHORT).show());
        } catch (Throwable e) {
            State.log("DexManager.setWallpaper failed: " + e);
            runOnUiThread(() -> Toast.makeText(this, "DexManager failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    private void showReadonlyDebugDialog(String title,
                                         String content,
                                         String clipLabel,
                                         String copiedToastText) {
        final String textToCopy = content;
        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setTitle(title)
                .setMessage(content)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制", (dialog, which) -> {
                    ClipboardManager clipboardManager =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboardManager != null) {
                        clipboardManager.setPrimaryClip(
                                ClipData.newPlainText(clipLabel, textToCopy));
                        Toast.makeText(this, copiedToastText, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateShizukuStatus(findViewById(R.id.shizukuStatus), findViewById(R.id.shizukuPermissionBtn));
        updateOverlayStatus(findViewById(R.id.overlayStatus));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        SunshineServer.suppressPin = null;
    }

    private void updateShizukuStatus(TextView statusView, Button permissionBtn) {
        boolean started = ShizukuUtils.hasShizukuStarted();
        boolean hasPermission = ShizukuUtils.hasPermission();
        if (!started) {
            statusView.setText("Not started");
            permissionBtn.setVisibility(View.GONE);
        } else if (!hasPermission) {
            statusView.setText("Started, permission required");
            permissionBtn.setVisibility(View.VISIBLE);
        } else {
            statusView.setText("Authorized");
            permissionBtn.setVisibility(View.GONE);
        }
    }

    private void updateOverlayStatus(TextView statusView) {
        boolean hasPermission = Settings.canDrawOverlays(this);
        statusView.setText(hasPermission ? "Authorized" : "Permission required");

        View parent = (View) statusView.getParent();
        Button overlayPermissionBtn = parent.findViewById(R.id.overlayPermissionBtn);
        if (overlayPermissionBtn != null) {
            overlayPermissionBtn.setVisibility(hasPermission ? View.GONE : View.VISIBLE);
            overlayPermissionBtn.setOnClickListener(v -> {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            });
        }
    }








    private void updateEncoderSettingsText() {
        String codecName = Pref.getEncoderCodec() == Pref.ENCODER_CODEC_H264 ? "H.264" : "H.265";
        String modeName;
        switch (Pref.getEncoderBitrateMode()) {
            case 0:
                modeName = "CQ";
                break;
            case 1:
                modeName = "VBR";
                break;
            case 2:
            default:
                modeName = "CBR";
                break;
        }
        String text = String.format(
                Locale.US,
                "Codec %s | Bitrate %d%% | Mode %s | Complexity %d | I-frame %ds | Max FPS %d | Frame %s | FEC %d%%",
                codecName,
                Pref.getEncoderBitratePercent(),
                modeName,
                Pref.getEncoderComplexity(),
                Pref.getEncoderIFrameInterval(),
                Pref.getEncoderMaxFps(),
                Pref.getEncoderDynamicFrameRate() ? "dynamic" : "fixed",
                Pref.getStreamFecPercent());
        currentEncoderSettingsText.setText(text);
    }

    private void loadClientList(Spinner spinner) {
        String selectedClient = Pref.getSelectedClient();
        List<String> clients = new ArrayList<>();
        clients.add(MANUAL_INPUT_LABEL);
        if (!selectedClient.isEmpty() && !clients.contains(selectedClient)) {
            clients.add(selectedClient);
        }
        for (String discovered : State.discoveredConnectScreenClients) {
            if (!clients.contains(discovered)) {
                clients.add(discovered);
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                clients);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        if (!selectedClient.isEmpty()) {
            for (int i = 0; i < clients.size(); i++) {
                if (clients.get(i).equals(selectedClient)) {
                    spinner.setSelection(i);
                    break;
                }
            }
        }
    }

    private void showManualInputDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_manual_client_input, null);
        EditText ipEditText = dialogView.findViewById(R.id.ipEditText);
        EditText portEditText = dialogView.findViewById(R.id.portEditText);
        portEditText.setText("42515");

        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setTitle("Manual client address")
                .setView(dialogView)
                .setPositiveButton("OK", (dialog, which) -> {
                    String ip = ipEditText.getText().toString().trim();
                    String port = portEditText.getText().toString().trim();
                    if (ip.isEmpty()) {
                        return;
                    }
                    String clientAddress = port.isEmpty() ? ip : ip + ":" + port;
                    preferences.edit().putString(Pref.KEY_SELECTED_CLIENT, clientAddress).apply();
                    Spinner clientSpinner = findViewById(R.id.clientSpinner);
                    loadClientList(clientSpinner);
                    int pin = (int) (Math.random() * 9000) + 1000;
                    SunshineServer.suppressPin = String.valueOf(pin);
                    ConnectToClient.connect(pin);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void styleSwitch(SwitchCompat switchCompat) {
        if (switchCompat == null) {
            return;
        }
        int textColor = ContextCompat.getColor(this, R.color.ui_text_primary);
        int[][] states = new int[][]{
                new int[]{-android.R.attr.state_enabled},
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        ColorStateList thumbColors = new ColorStateList(states, new int[]{
                0xFFD0D4D8,
                0xFF4CAF50,
                0xFF9EA4AA
        });
        ColorStateList trackColors = new ColorStateList(states, new int[]{
                0x223F454A,
                0x664CAF50,
                0x553F454A
        });
        switchCompat.setTextColor(textColor);
        switchCompat.setThumbTintList(thumbColors);
        switchCompat.setTrackTintList(trackColors);
    }
}

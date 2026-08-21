package com.connect_screen.mirror;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.connect_screen.mirror.job.CreateVirtualDisplay;
import com.connect_screen.mirror.job.ConnectToClient;
import com.connect_screen.mirror.job.ExitAll;
import com.connect_screen.mirror.job.ExternalDisplayMonitor;
import com.connect_screen.mirror.job.OutputSource;
import com.connect_screen.mirror.job.ProjectViaDp;
import com.connect_screen.mirror.job.SunshineServer;
import com.connect_screen.mirror.shizuku.ShizukuUtils;
import com.connect_screen.mirror.transport.OptionalTransportProvider;
import com.connect_screen.mirror.transport.TransportRegistry;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Set;

public class ConnectionFragment extends Fragment {
    private final ActivityResultLauncher<Intent> wallpaperPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        DexWallpaper.applyPickedWallpaper(requireContext(), uri, currentDexDisplayId());
                    }
                }
            });

    private final DisplayManager.DisplayListener externalDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    refreshExternalDisplayState();
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    refreshExternalDisplayState();
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    refreshExternalDisplayState();
                }
            };

    private Button transportMoonlight;
    private Button transportDp;
    private Button transportOptional;
    private Button sourceDexButton;
    private Button sourceMirrorButton;
    private Button quickTouchpad;
    private Button quickScreenOff;
    private Button quickWallpaper;
    private Button quickRestart;
    private Button serviceActionButton;
    private Button mirrorActionButton;
    private Button manualClientButton;
    private TextView serviceSub;
    private TextView serviceBadge;
    private TextView ipText;
    private View telemetryChips;
    private View sharedCardsContainer;
    private TextView codecValue;
    private TextView resolutionValue;
    private TextView bitrateValue;
    private TextView inputFpsValue;
    private TextView outputFpsValue;
    private TextView pingValue;
    private TextView mirrorStatusText;
    private TextView placeholderTitle;
    private TextView placeholderSub;
    private TextView placeholderStatusText;
    private Button placeholderActionButton;
    private Button placeholderModeButton;
    private LinearLayout placeholderModeCard;
    private LinearLayout placeholderCustomModeCard;
    private EditText placeholderModeWidthInput;
    private EditText placeholderModeHeightInput;
    private EditText placeholderModeRefreshInput;
    private Button placeholderCustomModeButton;
    private LinearLayout moonlightContent;
    private LinearLayout placeholderContent;
    private LinearLayout dexContent;
    private LinearLayout mirrorContent;
    private LinearLayout firstUseCard;
    private SwitchCompat autoConnectSwitch;
    private Spinner clientSpinner;
    private FrameLayout subPageContainer;
    private View transportTabsLayout;
    private FrameLayout optionalTransportContainer;
    private Fragment optionalTransportFragment;
    private TextView entryEncoderSub;
    private ScrollView rootScroll;
    private View fragmentRootView;
    private View quickActionsRow;
    private View quickActionsTitle;

    private String currentTransport = "moonlight";
    private String currentSource = OutputSource.getActive().id();
    private boolean hideFirstUse = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_connection, container, false);
        rootScroll = (ScrollView) view;
        fragmentRootView = view;

        transportMoonlight = view.findViewById(R.id.transportMoonlight);
        transportDp = view.findViewById(R.id.transportDp);
        sourceDexButton = view.findViewById(R.id.sourceDexButton);
        sourceMirrorButton = view.findViewById(R.id.sourceMirrorButton);
        quickActionsRow = view.findViewById(R.id.quickActionsRow);
        quickActionsTitle = view.findViewById(R.id.quickActionsTitle);
        quickTouchpad = view.findViewById(R.id.quickTouchpad);
        quickScreenOff = view.findViewById(R.id.quickScreenOff);
        quickWallpaper = view.findViewById(R.id.quickWallpaper);
        quickRestart = view.findViewById(R.id.quickRestart);
        serviceActionButton = view.findViewById(R.id.serviceActionButton);
        mirrorActionButton = view.findViewById(R.id.mirrorActionButton);
        manualClientButton = view.findViewById(R.id.manualClientButton);
        tintButton(manualClientButton, R.color.ui_accent_soft, R.color.ui_accent);
        serviceSub = view.findViewById(R.id.serviceSub);
        serviceBadge = view.findViewById(R.id.serviceBadge);
        ipText = view.findViewById(R.id.ipText);
        telemetryChips = view.findViewById(R.id.telemetryChips);
        sharedCardsContainer = view.findViewById(R.id.sharedCardsContainer);
        codecValue = view.findViewById(R.id.chipCodecValue);
        resolutionValue = view.findViewById(R.id.chipResolutionValue);
        bitrateValue = view.findViewById(R.id.chipBitrateValue);
        inputFpsValue = view.findViewById(R.id.chipInputFpsValue);
        outputFpsValue = view.findViewById(R.id.chipOutputFpsValue);
        pingValue = view.findViewById(R.id.chipPingValue);
        mirrorStatusText = view.findViewById(R.id.mirrorStatusText);
        placeholderTitle = view.findViewById(R.id.placeholderTitle);
        placeholderSub = view.findViewById(R.id.placeholderSub);
        placeholderStatusText = view.findViewById(R.id.placeholderStatusText);
        placeholderActionButton = view.findViewById(R.id.placeholderActionButton);
        placeholderModeButton = view.findViewById(R.id.placeholderModeButton);
        placeholderModeCard = view.findViewById(R.id.placeholderModeCard);
        placeholderCustomModeCard = view.findViewById(R.id.placeholderCustomModeCard);
        placeholderModeWidthInput = view.findViewById(R.id.placeholderModeWidthInput);
        placeholderModeHeightInput = view.findViewById(R.id.placeholderModeHeightInput);
        placeholderModeRefreshInput = view.findViewById(R.id.placeholderModeRefreshInput);
        placeholderCustomModeButton = view.findViewById(R.id.placeholderCustomModeButton);
        placeholderActionButton.setOnClickListener(v -> onPlaceholderActionClicked());
        placeholderModeButton.setOnClickListener(v -> showDpModeDialog());
        placeholderCustomModeButton.setOnClickListener(v -> applyCustomDpMode());
        moonlightContent = view.findViewById(R.id.moonlightContent);
        placeholderContent = view.findViewById(R.id.placeholderContent);
        dexContent = view.findViewById(R.id.dexContent);
        mirrorContent = view.findViewById(R.id.mirrorContent);
        firstUseCard = view.findViewById(R.id.firstUseCard);
        hideFirstUse = Pref.isFirstUseHidden();
        autoConnectSwitch = view.findViewById(R.id.autoConnectSwitch);
        clientSpinner = view.findViewById(R.id.clientSpinner);
        subPageContainer = view.findViewById(R.id.subPageContainer);
        transportTabsLayout = view.findViewById(R.id.transportTabs);
        entryEncoderSub = view.findViewById(R.id.entryEncoderSub);

        TransportRegistry.discover();
        OptionalTransportProvider optionalTransport = TransportRegistry.optional();
        if (optionalTransport != null) {
            transportOptional = (Button) LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_optional_transport_tab,
                            transportTabsLayout instanceof ViewGroup
                                    ? (ViewGroup) transportTabsLayout : null, false);
            transportOptional.setText(optionalTransport.label());
            if (transportTabsLayout instanceof LinearLayout) {
                ((LinearLayout) transportTabsLayout).addView(transportOptional);
            }
            transportOptional.setOnClickListener(v -> setTransport(optionalTransport.id()));
        }
        optionalTransportContainer = view.findViewById(R.id.optionalTransportContainer);

        transportMoonlight.setOnClickListener(v -> setTransport("moonlight"));
        transportDp.setOnClickListener(v -> setTransport("dp"));
        sourceDexButton.setOnClickListener(v -> setSource("dex"));
        sourceMirrorButton.setOnClickListener(v -> setSource("mirror"));
        serviceActionButton.setOnClickListener(v -> toggleService());
        mirrorActionButton.setOnClickListener(v -> toggleService());
        quickTouchpad.setOnClickListener(v -> openTouchpad());
        quickScreenOff.setOnClickListener(v -> CreateVirtualDisplay.doPowerOffScreen(requireContext()));
        quickWallpaper.setOnClickListener(v -> openWallpaperPicker());
        quickRestart.setOnClickListener(v -> restartCurrentDexSession());
        manualClientButton.setOnClickListener(v -> showManualClientDialog());
        view.findViewById(R.id.entryEncoder).setOnClickListener(v -> openSubPage("encoder"));
        view.findViewById(R.id.entryAudio).setOnClickListener(v -> openSubPage("audio"));
        view.findViewById(R.id.entryLogs).setOnClickListener(v -> showDebugLogPanel());
        view.findViewById(R.id.openWizardButton).setOnClickListener(v ->
                InitializationGuideDialog.show(requireActivity()));
        view.findViewById(R.id.hideFirstUseButton).setOnClickListener(v -> {
            hideFirstUse = true;
            Pref.setFirstUseHidden(true);
            applyFirstUseVisibility();
        });

        autoConnectSwitch.setChecked(Pref.getAutoConnectClient());
        autoConnectSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                Pref.getPreferences().edit().putBoolean(Pref.KEY_AUTO_CONNECT_CLIENT, isChecked).apply());
        clientSpinner.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"192.168.50.50 · Moonlight"}));

        syncTransportWithActiveSession();
        applySource();
        updateTransportTabAvailability();
        refreshIpAddress();
        updateDebugInfo(State.streamingDebugInfo.getValue());
        updateUiState(State.uiState.getValue());
        updateEncoderSummary();
        return view;
    }

    private void openSubPage(String page) {
        if (subPageContainer == null) {
            return;
        }
        transportTabsLayout.setVisibility(View.GONE);
        moonlightContent.setVisibility(View.GONE);
        placeholderContent.setVisibility(View.GONE);
        firstUseCard.setVisibility(View.GONE);
        subPageContainer.removeAllViews();
        View pageView = "encoder".equals(page) ? buildEncoderPage() : buildAudioPage();
        if (pageView != null) {
            subPageContainer.addView(pageView);
            subPageContainer.setVisibility(View.VISIBLE);
        }
        // The shared cards (telemetry chips / client / transport / logs) stay
        // visible above the sub page, so scroll to the sub page itself instead
        // of the very top, otherwise the opened settings are off-screen.
        if (rootScroll != null) {
            rootScroll.post(() -> {
                try {
                    int target = subPageContainer.getTop();
                    rootScroll.scrollTo(0, Math.max(0, target));
                } catch (Throwable ignored) {
                }
            });
        }
    }

    private void closeSubPage() {
        subPageContainer.removeAllViews();
        subPageContainer.setVisibility(View.GONE);
        transportTabsLayout.setVisibility(View.VISIBLE);
        if (rootScroll != null) {
            rootScroll.scrollTo(0, 0);
        }
        applyTransport();
        applyFirstUseVisibility();
        updateEncoderSummary();
    }

    private View buildEncoderPage() {
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.fragment_encoder_settings, null);
        view.findViewById(R.id.encoderBackButton).setOnClickListener(v -> closeSubPage());

        Spinner codecSpinner = view.findViewById(R.id.encoderCodecSpinner);
        Spinner bitrateModeSpinner = view.findViewById(R.id.encoderBitrateModeSpinner);
        ArrayAdapter<String> codecAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{"H.264 / AVC", "H.265 / HEVC"});
        codecAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        codecSpinner.setAdapter(codecAdapter);
        ArrayAdapter<String> bitrateModeAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{"CBR - 稳定带宽", "VBR - 动态码率", "CQ - 恒定质量"});
        bitrateModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bitrateModeSpinner.setAdapter(bitrateModeAdapter);

        EditText bitrateEdit = view.findViewById(R.id.encoderBitrateEdit);
        EditText complexityEdit = view.findViewById(R.id.encoderComplexityEdit);
        EditText iframeEdit = view.findViewById(R.id.encoderIFrameEdit);
        EditText maxFpsEdit = view.findViewById(R.id.encoderMaxFpsEdit);
        EditText fecEdit = view.findViewById(R.id.encoderFecEdit);
        SwitchCompat lowLatencySwitch = view.findViewById(R.id.encoderLowLatencySwitch);
        SwitchCompat disableBSwitch = view.findViewById(R.id.encoderDisableBSwitch);
        SwitchCompat realtimeSwitch = view.findViewById(R.id.encoderRealtimeSwitch);
        SwitchCompat dynamicSwitch = view.findViewById(R.id.encoderDynamicSwitch);

        bitrateEdit.setText(String.valueOf(Pref.getEncoderBitratePercent()));
        complexityEdit.setText(String.valueOf(Pref.getEncoderComplexity()));
        iframeEdit.setText(String.valueOf(Pref.getEncoderIFrameInterval()));
        maxFpsEdit.setText(String.valueOf(Pref.getEncoderMaxFps()));
        fecEdit.setText(String.valueOf(Pref.getStreamFecPercent()));
        lowLatencySwitch.setChecked(Pref.getEncoderLowLatency());
        disableBSwitch.setChecked(Pref.getEncoderDisableBFrames());
        realtimeSwitch.setChecked(Pref.getEncoderRealtimePriority());
        dynamicSwitch.setChecked(Pref.getEncoderDynamicFrameRate());
        selectValue(codecSpinner, new int[]{Pref.ENCODER_CODEC_H264, Pref.ENCODER_CODEC_H265}, Pref.getEncoderCodec());
        selectValue(bitrateModeSpinner, new int[]{2, 1, 0}, Pref.getEncoderBitrateMode());

        view.findViewById(R.id.encoderSaveButton).setOnClickListener(v -> {
            int codecIndex = codecSpinner.getSelectedItemPosition();
            int bitrateModeIndex = bitrateModeSpinner.getSelectedItemPosition();
            Pref.getPreferences().edit()
                    .putInt(Pref.KEY_ENCODER_CODEC,
                            new int[]{Pref.ENCODER_CODEC_H264, Pref.ENCODER_CODEC_H265}[Math.max(0, Math.min(1, codecIndex))])
                    .putInt(Pref.KEY_ENCODER_BITRATE_PERCENT, parseClampedInt(bitrateEdit, 100, 25, 200))
                    .putInt(Pref.KEY_ENCODER_BITRATE_MODE,
                            new int[]{2, 1, 0}[Math.max(0, Math.min(2, bitrateModeIndex))])
                    .putInt(Pref.KEY_ENCODER_COMPLEXITY, parseClampedInt(complexityEdit, 5, 0, 10))
                    .putInt(Pref.KEY_ENCODER_I_FRAME_INTERVAL, parseClampedInt(iframeEdit, 3, 1, 10))
                    .putInt(Pref.KEY_ENCODER_MAX_FPS, parseClampedInt(maxFpsEdit, 60, 1, 240))
                    .putBoolean(Pref.KEY_ENCODER_LOW_LATENCY, lowLatencySwitch.isChecked())
                    .putBoolean(Pref.KEY_ENCODER_DISABLE_B_FRAMES, disableBSwitch.isChecked())
                    .putBoolean(Pref.KEY_ENCODER_REALTIME_PRIORITY, realtimeSwitch.isChecked())
                    .putBoolean(Pref.KEY_ENCODER_DYNAMIC_FRAME_RATE, dynamicSwitch.isChecked())
                    .putInt(Pref.KEY_STREAM_FEC_PERCENT, parseClampedInt(fecEdit, 0, 0, 50))
                    .apply();
            SunshineServer.setEncoderSettingsFromPreferences();
            SunshineServer.setVideoCodec(Pref.getEncoderCodec());
            showToast("编码设置已保存");
            updateEncoderSummary();
        });
        view.findViewById(R.id.encoderResetButton).setOnClickListener(v -> {
            Pref.getPreferences().edit()
                    .putInt(Pref.KEY_ENCODER_BITRATE_PERCENT, 100)
                    .putInt(Pref.KEY_ENCODER_CODEC, Pref.ENCODER_CODEC_H264)
                    .putInt(Pref.KEY_ENCODER_BITRATE_MODE, 2)
                    .putInt(Pref.KEY_ENCODER_COMPLEXITY, 5)
                    .putInt(Pref.KEY_ENCODER_I_FRAME_INTERVAL, 3)
                    .putInt(Pref.KEY_ENCODER_MAX_FPS, 60)
                    .putBoolean(Pref.KEY_ENCODER_LOW_LATENCY, true)
                    .putBoolean(Pref.KEY_ENCODER_DISABLE_B_FRAMES, true)
                    .putBoolean(Pref.KEY_ENCODER_REALTIME_PRIORITY, true)
                    .putBoolean(Pref.KEY_ENCODER_DYNAMIC_FRAME_RATE, false)
                    .putInt(Pref.KEY_STREAM_FEC_PERCENT, 0)
                    .apply();
            bitrateEdit.setText("100");
            complexityEdit.setText("5");
            iframeEdit.setText("3");
            maxFpsEdit.setText("60");
            fecEdit.setText("0");
            lowLatencySwitch.setChecked(true);
            disableBSwitch.setChecked(true);
            realtimeSwitch.setChecked(true);
            dynamicSwitch.setChecked(false);
            codecSpinner.setSelection(0);
            bitrateModeSpinner.setSelection(2);
            SunshineServer.setEncoderSettingsFromPreferences();
            SunshineServer.setVideoCodec(Pref.getEncoderCodec());
            showToast("编码设置已恢复默认");
            updateEncoderSummary();
        });
        view.findViewById(R.id.encoderHandshakeEntry).setOnClickListener(v ->
                DebugDialogs.showLastMoonlightHandshakeDialog(requireContext()));
        view.findViewById(R.id.encoderControlEntry).setOnClickListener(v ->
                DebugDialogs.showLastMoonlightControlInputDialog(requireContext()));
        tintButton(view.findViewById(R.id.encoderSaveButton), R.color.ui_accent, R.color.ui_on_accent);
        tintButton(view.findViewById(R.id.encoderResetButton), R.color.ui_surface, R.color.ui_text_primary);
        return view;
    }

    private View buildAudioPage() {
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.fragment_audio_input, null);
        view.findViewById(R.id.audioBackButton).setOnClickListener(v -> closeSubPage());
        SwitchCompat disableUsbSwitch = view.findViewById(R.id.audioDisableUsbSwitch);
        SwitchCompat cursorSwitch = view.findViewById(R.id.audioCursorSwitch);
        SwitchCompat mouseSwitch = view.findViewById(R.id.audioMouseSwitch);
        disableUsbSwitch.setChecked(Pref.getDisableUsbAudio());
        cursorSwitch.setChecked(Pref.getUseAndroidCursorOverlay());
        mouseSwitch.setChecked(Pref.getMapMouseToTouch());
        disableUsbSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                Pref.getPreferences().edit().putBoolean(Pref.KEY_DISABLE_USB_AUDIO, isChecked).apply());
        cursorSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                Pref.getPreferences().edit().putBoolean(Pref.KEY_USE_ANDROID_CURSOR_OVERLAY, isChecked).apply());
        mouseSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                Pref.getPreferences().edit().putBoolean(Pref.KEY_MAP_MOUSE_TO_TOUCH, isChecked).apply());
        return view;
    }

    private void updateEncoderSummary() {
        if (entryEncoderSub == null) {
            return;
        }
        String codec = Pref.getEncoderCodec() == Pref.ENCODER_CODEC_H265 ? "H.265" : "H.264";
        entryEncoderSub.setText(codec + " · 码率 " + Pref.getEncoderBitratePercent()
                + "% · " + Pref.getEncoderMaxFps() + "fps");
    }

    private void selectValue(Spinner spinner, int[] values, int target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private int parseClampedInt(EditText editText, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(editText.getText().toString().trim());
            return Math.max(min, Math.min(max, value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private void setTransport(String transport) {
        currentTransport = transport;
        applyTransport();
    }

    void syncTransportWithActiveSession() {
        String target = "moonlight";
        if (ProjectViaDp.isActive()) {
            target = "dp";
        } else if (TransportRegistry.isOptionalActive()) {
            OptionalTransportProvider optional = TransportRegistry.optional();
            if (optional != null) {
                target = optional.id();
            }
        } else if (SunshineService.getLifecycleState() != SunshineService.LifecycleState.STOPPED) {
            target = "moonlight";
        }
        if (!target.equals(currentTransport)) {
            setTransport(target);
        } else {
            applyTransport();
        }
    }

    private void applyTransport() {
        setTabButton(transportMoonlight, "moonlight".equals(currentTransport));
        setTabButton(transportDp, "dp".equals(currentTransport));
        OptionalTransportProvider optional = TransportRegistry.optional();
        boolean optionalSelected = optional != null && optional.id().equals(currentTransport);
        setTabButton(transportOptional, optionalSelected);
        boolean moonlight = "moonlight".equals(currentTransport);
        moonlightContent.setVisibility(moonlight ? View.VISIBLE : View.GONE);
        // Shared cards (client / transport / logs / telemetry) only make
        // sense for the Moonlight transport; hide them for DP / AirPlay.
        if (sharedCardsContainer != null) {
            sharedCardsContainer.setVisibility(moonlight ? View.VISIBLE : View.GONE);
        }
        if (optionalSelected) {
            attachOptionalFragment();
            if (optionalTransportContainer != null) {
                optionalTransportContainer.setVisibility(View.VISIBLE);
            }
            placeholderContent.setVisibility(View.GONE);
        } else {
            detachOptionalFragment();
            if (optionalTransportContainer != null) {
                optionalTransportContainer.setVisibility(View.GONE);
            }
            placeholderContent.setVisibility(moonlight ? View.GONE : View.VISIBLE);
        }
        if ("dp".equals(currentTransport)) {
            placeholderTitle.setText("DP / HDMI 有线");
            placeholderSub.setText("外接屏直连 DeX");
        }
        refreshPlaceholderState();
    }

    private void attachOptionalFragment() {
        OptionalTransportProvider provider = TransportRegistry.optional();
        if (provider == null || optionalTransportContainer == null || optionalTransportFragment != null) {
            return;
        }
        optionalTransportFragment = provider.createFragment();
        getChildFragmentManager().beginTransaction()
                .replace(R.id.optionalTransportContainer, optionalTransportFragment)
                .commitNow();
    }

    private void detachOptionalFragment() {
        if (optionalTransportFragment != null) {
            getChildFragmentManager().beginTransaction()
                    .remove(optionalTransportFragment)
                    .commitNow();
            optionalTransportFragment = null;
        }
        if (optionalTransportContainer != null) {
            optionalTransportContainer.removeAllViews();
        }
    }

    private void updateTransportTabAvailability() {
        if (transportMoonlight == null || transportDp == null) {
            return;
        }
        boolean sessionActive = ProjectViaDp.isActive()
                || TransportRegistry.isOptionalActive()
                || SunshineServer.isMoonlightSessionActive();
        SunshineService.LifecycleState lifecycleState = SunshineService.getLifecycleState();
        boolean moonlightActive = SunshineServer.isMoonlightSessionActive()
                || lifecycleState == SunshineService.LifecycleState.STARTING
                || lifecycleState == SunshineService.LifecycleState.RUNNING
                || lifecycleState == SunshineService.LifecycleState.STOPPING;
        sessionActive = sessionActive || moonlightActive;
        setEnabled(transportMoonlight, !sessionActive || "moonlight".equals(currentTransport));
        setEnabled(transportDp, !sessionActive || "dp".equals(currentTransport));
        OptionalTransportProvider optional = TransportRegistry.optional();
        setEnabled(transportOptional, !sessionActive
                || (optional != null && optional.id().equals(currentTransport)));
    }

    @Override
    public void onDestroyView() {
        detachOptionalFragment();
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        DisplayManager dm = (DisplayManager) requireContext()
                .getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) {
            dm.registerDisplayListener(externalDisplayListener, new Handler(Looper.getMainLooper()));
        }
        ExternalDisplayMonitor.refreshState(requireContext());
        syncTransportWithActiveSession();
        refreshPlaceholderState();
    }

    @Override
    public void onPause() {
        DisplayManager dm = (DisplayManager) requireContext()
                .getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) {
            dm.unregisterDisplayListener(externalDisplayListener);
        }
        super.onPause();
    }

    private void refreshExternalDisplayState() {
        if (getContext() == null) {
            return;
        }
        ExternalDisplayMonitor.refreshState(getContext());
        new Handler(Looper.getMainLooper()).post(this::refreshPlaceholderState);
    }

    private void onPlaceholderActionClicked() {
        if ("dp".equals(currentTransport)) {
            if (ProjectViaDp.isActive()) {
                toggleDpOutput();
            } else if (SunshineServer.isMoonlightSessionActive()
                    || TransportRegistry.isOptionalActive()) {
                stopAllOutputs();
            } else {
                toggleDpOutput();
            }
        }
    }

    private void stopAllOutputs() {
        ExitAll.stopServices(requireContext());
        showToast("已停止全部输出");
        refreshPlaceholderState();
    }

    private void toggleDpOutput() {
        if (ProjectViaDp.isActive()) {
            ProjectViaDp.stop();
            showToast("已停止 DP 输出");
        } else {
            State.startNewJob(new ProjectViaDp(!OutputSource.isMirrorActive()));
            showToast("正在启动 DP 输出");
        }
        refreshPlaceholderState();
    }

    private void showDpModeDialog() {
        if (State.externalDisplayId <= 0) {
            showToast("未检测到外接屏");
            return;
        }
        android.view.Display display = ExternalDisplayMonitor.getPrimaryExternalDisplay(requireContext());
        if (display == null) {
            showToast("未检测到外接屏");
            return;
        }
        android.view.Display.Mode[] modes = display.getSupportedModes();
        if (modes == null || modes.length == 0) {
            showToast("此屏幕没有可选模式");
            return;
        }
        String[] items = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            android.view.Display.Mode mode = modes[i];
            items[i] = mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight() + "@"
                    + String.format(java.util.Locale.US, "%.1f Hz", mode.getRefreshRate());
        }
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setTitle("选择外接屏模式")
                .setItems(items, (dialog, which) -> applyDpMode(modes[which]))
                .show();
    }

    private void applyDpMode(android.view.Display.Mode mode) {
        applyDpMode(mode.getPhysicalWidth(), mode.getPhysicalHeight(),
                Math.round(mode.getRefreshRate()));
    }

    private void applyDpMode(int width, int height, int refresh) {
        if (!State.isUserServiceAlive()) {
            State.ensureUserServiceBound();
        }
        // Remember the user's chosen wired (DP) output mode so ProjectViaDp
        // can render DeX at the same resolution/refresh rate as the DP signal.
        Pref.setDpOutputMode(width, height, refresh);
        try {
            int result = State.userService.applyExternalDisplayMode(
                    State.externalDisplayId, width, height, refresh);
            if (result == 0) {
                showToast("已应用外接屏模式");
            } else if (result == 1) {
                showDpReplugDialog();
            } else {
                showToast("外接屏模式应用失败");
            }
        } catch (Throwable e) {
            showToast("外接屏模式应用失败：" + e.getMessage());
        }
    }

    private void applyCustomDpMode() {
        try {
            int width = Integer.parseInt(placeholderModeWidthInput.getText().toString().trim());
            int height = Integer.parseInt(placeholderModeHeightInput.getText().toString().trim());
            int refresh = Integer.parseInt(placeholderModeRefreshInput.getText().toString().trim());
            if (width <= 0 || height <= 0 || refresh <= 0) {
                throw new NumberFormatException();
            }
            applyDpMode(width, height, refresh);
        } catch (NumberFormatException e) {
            showToast("请输入有效的宽、高、刷新率");
        }
    }

    private void showDpReplugDialog() {
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setTitle("需要重新插拔 DP 线")
                .setMessage("已写入外接屏模式属性，请拔下并重新插上 DP/HDMI 线，让显示驱动按新模式初始化。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void refreshPlaceholderState() {
        if (getContext() == null || !isAdded()) {
            return;
        }
        if (placeholderActionButton == null || placeholderStatusText == null) {
            return;
        }
        updateQuickActions();
        updateTransportTabAvailability();
        boolean dp = "dp".equals(currentTransport);
        placeholderActionButton.setVisibility(dp ? View.VISIBLE : View.GONE);
        if (placeholderModeButton != null) {
            placeholderModeButton.setVisibility(View.GONE);
        }
        if (placeholderModeCard != null) {
            placeholderModeCard.setVisibility(View.GONE);
        }
        if (placeholderCustomModeCard != null) {
            placeholderCustomModeCard.setVisibility(View.GONE);
        }
        if (!dp) {
            return;
        }
        ExternalDisplayMonitor.refreshState(requireContext());
        boolean hasExternal = State.externalDisplayId > 0;
        if (placeholderModeButton != null) {
            placeholderModeButton.setVisibility(View.VISIBLE);
            placeholderModeButton.setEnabled(hasExternal);
        }
        if (placeholderModeCard != null) {
            placeholderModeCard.setVisibility(hasExternal ? View.VISIBLE : View.GONE);
        }
        if (placeholderCustomModeCard != null) {
            placeholderCustomModeCard.setVisibility(hasExternal ? View.VISIBLE : View.GONE);
        }
        if (hasExternal && placeholderModeWidthInput != null
                && placeholderModeWidthInput.getText().toString().trim().isEmpty()) {
            placeholderModeWidthInput.setText(String.valueOf(State.externalDisplayWidth));
            placeholderModeHeightInput.setText(String.valueOf(State.externalDisplayHeight));
            int refresh = 60;
            android.view.Display display = ExternalDisplayMonitor.getPrimaryExternalDisplay(requireContext());
            if (display != null && display.getMode() != null) {
                refresh = Math.round(display.getMode().getRefreshRate());
            }
            placeholderModeRefreshInput.setText(String.valueOf(refresh));
        }
        if (ProjectViaDp.isActive()) {
            placeholderStatusText.setText("运行中 · 屏幕 " + State.externalDisplayId);
            setButton(placeholderActionButton, "停止 DP 输出", R.color.ui_danger,
                    R.color.ui_on_accent, true);
        } else if (SunshineServer.isMoonlightSessionActive()
                || TransportRegistry.isOptionalActive()) {
            placeholderStatusText.setText("其他输出运行中 · 外接屏 " + State.externalDisplayId);
            setButton(placeholderActionButton, "停止服务", R.color.ui_danger,
                    R.color.ui_on_accent, true);
        } else if (State.externalDisplayId > 0) {
            placeholderStatusText.setText("外接屏 " + State.externalDisplayId
                    + " · " + State.externalDisplayWidth + "x" + State.externalDisplayHeight);
            setButton(placeholderActionButton, "开始 DP 输出", R.color.ui_accent,
                    R.color.ui_on_accent, true);
        } else {
            placeholderStatusText.setText("未检测到外接屏");
            setButton(placeholderActionButton, "开始 DP 输出", R.color.ui_accent,
                    R.color.ui_on_accent, true);
        }
    }

    private void setTabButton(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.setBackgroundResource(active
                ? R.drawable.bg_libredex_transport_tab_active
                : R.drawable.bg_libredex_transport_tab);
        ViewCompat.setBackgroundTintList(button, ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), active ? R.color.ui_accent_soft : R.color.ui_surface)));
        button.setTextColor(ContextCompat.getColor(requireContext(),
                active ? R.color.ui_accent : R.color.ui_text_secondary));
    }

    private void setSource(String source) {
        boolean changed = !source.equals(currentSource);
        currentSource = source;
        OutputSource.setActive(OutputSource.fromId(source));
        if (changed && ProjectViaDp.isActive()) {
            ProjectViaDp.stop();
            State.startNewJob(new ProjectViaDp(!OutputSource.isMirrorActive()));
            showToast("已切换 DP 输出源");
        }
        applySource();
    }

    private void applySource() {
        boolean dex = "dex".equals(currentSource);
        setSegButton(sourceDexButton, dex);
        setSegButton(sourceMirrorButton, !dex);
        boolean mirror = "mirror".equals(currentSource);
        if (quickActionsRow != null) {
            quickActionsRow.setVisibility((dex || mirror) ? View.VISIBLE : View.GONE);
        }
        if (quickActionsTitle != null) {
            quickActionsTitle.setVisibility((dex || mirror) ? View.VISIBLE : View.GONE);
        }
        dexContent.setVisibility(dex ? View.VISIBLE : View.GONE);
        mirrorContent.setVisibility(dex ? View.GONE : View.VISIBLE);
        updateUiState(State.uiState.getValue());
        updateQuickActions();
    }

    private void setSegButton(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.setBackgroundResource(active
                ? R.drawable.bg_libredex_seg_button_active
                : R.drawable.bg_libredex_seg_button);
        ViewCompat.setBackgroundTintList(button, ColorStateList.valueOf(
                active ? ContextCompat.getColor(requireContext(), R.color.ui_accent_soft)
                        : android.graphics.Color.TRANSPARENT));
        button.setTextColor(ContextCompat.getColor(requireContext(),
                active ? R.color.ui_accent : R.color.ui_text_secondary));
    }

    private void tintButton(Button button, int tintRes, int textRes) {
        if (button == null) {
            return;
        }
        ViewCompat.setBackgroundTintList(button,
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), tintRes)));
        button.setTextColor(ContextCompat.getColor(requireContext(), textRes));
    }

    private void toggleService() {
        MirrorMainActivity activity = getMainActivity();
        if (activity != null) {
            activity.startSunshineServiceWithPreflight();
        }
    }

    private void openTouchpad() {
        boolean dex = "dex".equals(currentSource);
        boolean connected = SunshineServer.isMoonlightSessionActive()
                || ProjectViaDp.isActive()
                || TransportRegistry.isOptionalActive();
        if (dex && connected) {
            DexTouchpadLauncher.launch(requireContext());
        } else {
            showToast("需要 DeX 会话");
        }
    }

    public void updateUiState(MirrorUiState state) {
        if (state == null || serviceSub == null) {
            return;
        }
        SunshineService.LifecycleState lifecycleState = SunshineService.getLifecycleState();
        boolean connected = SunshineServer.isMoonlightSessionActive();
        updateTelemetryVisibility(connected);
        switch (lifecycleState) {
            case STARTING:
                serviceSub.setText("Sunshine 服务启动中");
                setBadge(serviceBadge, "处理中", false);
                setButton(serviceActionButton, "停止服务", R.color.ui_danger,
                        R.color.ui_on_accent, true);
                break;
            case STOPPING:
                serviceSub.setText("Sunshine 服务关闭中");
                setBadge(serviceBadge, "处理中", false);
                setButton(serviceActionButton, "停止服务", R.color.ui_danger,
                        R.color.ui_on_accent, true);
                break;
            case RUNNING:
                if (connected) {
                    serviceSub.setText("已连接到客户端");
                    setBadge(serviceBadge, "运行中", true);
                    setButton(serviceActionButton, "停止服务", R.color.ui_danger,
                            R.color.ui_on_accent, true);
                } else {
                    serviceSub.setText("Sunshine 服务已启动");
                    setBadge(serviceBadge, "等待连接", true);
                    setButton(serviceActionButton, "停止服务", R.color.ui_danger,
                            R.color.ui_on_accent, true);
                }
                break;
            case STOPPED:
            default:
                serviceSub.setText("Sunshine 服务未启动");
                setBadge(serviceBadge, "待机", false);
                setButton(serviceActionButton, "启动服务", R.color.ui_accent,
                        R.color.ui_on_accent, true);
                break;
        }
        boolean sessionActive = connected
                || State.mirrorVirtualDisplay != null
                || State.lastSingleAppDisplay != 0;
        boolean screenOffEnabled = sessionActive && (Pref.getUseBlackImage() || ShizukuUtils.hasPermission());
        setEnabled(quickScreenOff, screenOffEnabled);
        boolean serviceRunning = lifecycleState == SunshineService.LifecycleState.RUNNING;
        if (connected) {
            mirrorStatusText.setText("镜像中");
            setButton(mirrorActionButton, "停止服务", R.color.ui_danger,
                    R.color.ui_on_accent, true);
        } else if (lifecycleState == SunshineService.LifecycleState.STARTING
                || lifecycleState == SunshineService.LifecycleState.STOPPING) {
            mirrorStatusText.setText("服务切换中");
            setButton(mirrorActionButton, "停止服务", R.color.ui_danger,
                    R.color.ui_on_accent, true);
        } else if (serviceRunning) {
            mirrorStatusText.setText("服务已启动 · 等待连接");
            setButton(mirrorActionButton, "停止服务", R.color.ui_danger,
                    R.color.ui_on_accent, true);
        } else {
            mirrorStatusText.setText("未启动");
            setButton(mirrorActionButton, "启动服务", R.color.ui_accent,
                    R.color.ui_on_accent, true);
        }
        updateQuickActions();
        applyFirstUseVisibility();
        refreshIpAddress();
        refreshPlaceholderState();
    }

    private void setBadge(TextView badge, String text, boolean accent) {
        if (badge == null) {
            return;
        }
        android.content.Context context = getContext();
        if (context == null) {
            return;
        }
        badge.setText(text);
        badge.setBackgroundResource(accent
                ? R.drawable.bg_libredex_badge_accent
                : R.drawable.bg_libredex_badge);
        badge.setTextColor(ContextCompat.getColor(context,
                accent ? R.color.ui_accent : R.color.ui_text_secondary));
    }

    private void setButton(Button button, String text, int tintColorRes, int textColorRes, boolean enabled) {
        android.content.Context context = getContext();
        if (button == null || context == null) {
            return;
        }
        button.setText(text);
        button.setTextColor(ContextCompat.getColor(context, textColorRes));
        ViewCompat.setBackgroundTintList(button,
                ColorStateList.valueOf(ContextCompat.getColor(context, tintColorRes)));
        setEnabled(button, enabled);
    }

    private void setEnabled(View view, boolean enabled) {
        if (view == null) {
            return;
        }
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1.0f : 0.38f);
    }

    private void updateQuickActions() {
        boolean connected = SunshineServer.isMoonlightSessionActive()
                || ProjectViaDp.isActive()
                || TransportRegistry.isOptionalActive();
        boolean dex = "dex".equals(currentSource);
        boolean mirror = "mirror".equals(currentSource);
        boolean sessionActive = connected
                || State.mirrorVirtualDisplay != null
                || State.lastSingleAppDisplay != 0;
        if (quickTouchpad != null) {
            quickTouchpad.setVisibility(dex ? View.VISIBLE : View.GONE);
        }
        if (quickWallpaper != null) {
            quickWallpaper.setVisibility(dex ? View.VISIBLE : View.GONE);
        }
        if (quickRestart != null) {
            quickRestart.setVisibility(dex ? View.VISIBLE : View.GONE);
        }
        if (quickScreenOff != null) {
            quickScreenOff.setVisibility((dex || mirror) ? View.VISIBLE : View.GONE);
        }
        setEnabled(quickTouchpad, dex && connected);
        setEnabled(quickWallpaper, dex && connected);
        setEnabled(quickScreenOff, sessionActive && (Pref.getUseBlackImage() || ShizukuUtils.hasPermission()));
        setEnabled(quickRestart, connected);
    }

    private void applyFirstUseVisibility() {
        if (firstUseCard == null) {
            return;
        }
        boolean show = SunshineService.getLifecycleState() == SunshineService.LifecycleState.STOPPED
                && !hideFirstUse;
        firstUseCard.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    public void updateDebugInfo(String info) {
        if (codecValue == null) {
            return;
        }
        updateTelemetryVisibility(SunshineServer.isMoonlightSessionActive());
        updateQuickActions();
        updateSimpleDebugPanel(info);
    }

    private void updateTelemetryVisibility(boolean connected) {
        if (telemetryChips != null) {
            telemetryChips.setVisibility(connected ? View.VISIBLE : View.GONE);
        }
    }

    public void updateLogs() {
        // 完整日志弹窗在打开时读取 State.logs。
    }

    private void refreshIpAddress() {
        if (ipText == null || getContext() == null) {
            return;
        }
        try {
            Set<String> ips = SunshineService.getAllWifiIpAddresses(requireContext());
            ipText.setText(ips.isEmpty() ? "IP：--" : "IP：" + TextUtils.join(" / ", ips));
        } catch (Throwable e) {
            ipText.setText("IP：--");
        }
    }

    private void updateSimpleDebugPanel(String info) {
        if (info == null || info.trim().isEmpty() || "串流未启动".equals(info.trim())) {
            setSimpleDebugValues("--", "--", "--", "--", "--", "--");
            return;
        }
        String codec = valueAfterPrefix(info, "Codec:");
        String resolution = valueAfterPrefix(info, "Size:");
        String bitrate = valueAfterPrefix(info, "Encoded bitrate:");
        if (bitrate.isEmpty()) {
            bitrate = valueAfterPrefix(info, "Target bitrate:");
        }
        String inputFps = extractInputFps(info);
        String outputFps = valueAfterPrefix(info, "Output FPS:");
        String ping = valueAfterPrefix(info, "Ping:");
        setSimpleDebugValues(
                emptyAsDash(codec),
                emptyAsDash(resolution),
                emptyAsDash(bitrate),
                emptyAsDash(inputFps),
                emptyAsDash(outputFps),
                emptyAsDash(ping));
    }

    private void setSimpleDebugValues(String codec,
                                      String resolution,
                                      String bitrate,
                                      String inputFps,
                                      String outputFps,
                                      String ping) {
        setTextIfReady(codecValue, codec);
        setTextIfReady(resolutionValue, resolution);
        setTextIfReady(bitrateValue, bitrate);
        setTextIfReady(inputFpsValue, inputFps);
        setTextIfReady(outputFpsValue, outputFps);
        setTextIfReady(pingValue, ping);
    }

    private void setTextIfReady(TextView textView, String value) {
        if (textView != null) {
            textView.setText(value);
        }
    }

    private String buildDebugPanelSummaryText(String info) {
        if (info == null || info.trim().isEmpty() || "串流未启动".equals(info.trim())) {
            return "编码状态：未启动\n编码器：--\n分辨率：--\n帧率：输入 -- / 输出 --\n码率：--\nPing：--";
        }

        String codec = valueAfterPrefix(info, "Codec:");
        String level = valueAfterPrefix(info, "H.264 level:");
        String size = valueAfterPrefix(info, "Size:");
        String status = valueAfterPrefix(info, "Status:");
        String clientAndEncoderFps = valueAfterPrefix(info, "Client FPS:");
        String inputFps = extractInputFps(info);
        String outputFps = valueAfterPrefix(info, "Output FPS:");
        String targetBitrate = valueAfterPrefix(info, "Target bitrate:");
        String encodedBitrate = valueAfterPrefix(info, "Encoded bitrate:");
        String ping = valueAfterPrefix(info, "Ping:");
        String priority = valueAfterPrefix(info, "Priority hint:");
        String audio = valueAfterPrefix(info, "Audio:");
        String color = valueAfterPrefix(info, "Color:");
        String outputGap = valueAfterPrefix(info, "Output gap max:");
        String queue = valueAfterPrefix(info, "Queue:");
        String nativeCost = valueAfterPrefix(info, "Avg native cost:");
        String framePacer = findLineStartingWith(info, "Frame pacer:");

        StringBuilder builder = new StringBuilder();
        builder.append("编码状态：").append(emptyAsDash(status)).append('\n');
        builder.append("编码器：").append(emptyAsDash(codec));
        if (!level.isEmpty() && !"-".equals(level)) {
            builder.append(" / Level ").append(level);
        }
        builder.append('\n');
        builder.append("分辨率：").append(emptyAsDash(size)).append('\n');
        builder.append("请求/编码帧率：").append(emptyAsDash(clientAndEncoderFps)).append('\n');
        builder.append("输入/输出帧率：").append(emptyAsDash(inputFps))
                .append(" / ").append(emptyAsDash(outputFps)).append('\n');
        builder.append("码率：").append(emptyAsDash(encodedBitrate))
                .append(" / 目标 ").append(emptyAsDash(targetBitrate)).append('\n');
        builder.append("Ping：").append(emptyAsDash(ping));
        appendIfPresent(builder, "优先级", priority);
        appendIfPresent(builder, "音频", audio);
        appendIfPresent(builder, "色彩", color);
        appendIfPresent(builder, "输出间隔", outputGap);
        appendIfPresent(builder, "队列", queue);
        appendIfPresent(builder, "Native", nativeCost);
        appendIfPresent(builder, "Frame pacer", stripPrefix(framePacer, "Frame pacer:"));
        return builder.toString();
    }

    private String valueAfterPrefix(String info, String prefix) {
        for (String line : info.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private String extractInputFps(String info) {
        for (String line : info.split("\\n")) {
            String trimmed = line.trim();
            int sourceIndex = trimmed.indexOf("source=");
            if (sourceIndex >= 0) {
                int end = trimmed.indexOf(' ', sourceIndex);
                return end > sourceIndex
                        ? trimmed.substring(sourceIndex + 7, end)
                        : trimmed.substring(sourceIndex + 7);
            }
            if (trimmed.startsWith("Client FPS:")) {
                continue;
            }
            String inputFps = valueAfterKnownPrefix(trimmed, "Input FPS:");
            if (!inputFps.isEmpty()) {
                return inputFps;
            }
            String sourceFps = valueAfterKnownPrefix(trimmed, "Source FPS:");
            if (!sourceFps.isEmpty()) {
                return sourceFps;
            }
        }
        return "";
    }

    private String valueAfterKnownPrefix(String line, String prefix) {
        return line.startsWith(prefix) ? line.substring(prefix.length()).trim() : "";
    }

    private String findLineStartingWith(String info, String prefix) {
        for (String line : info.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed;
            }
        }
        return "";
    }

    private String stripPrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()).trim() : value;
    }

    private void appendIfPresent(StringBuilder builder, String label, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        builder.append('\n').append(label).append("：").append(value.trim());
    }

    private String emptyAsDash(String value) {
        return value == null || value.isEmpty() ? "--" : value;
    }

    private void showDebugLogPanel() {
        DebugLogDialog.show(requireContext());
    }

    private void openWallpaperPicker() {
        if (currentDexDisplayId() < 0) {
            showToast("请先连接 DeX");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        wallpaperPicker.launch(Intent.createChooser(intent, "选择 DeX 壁纸"));
    }

    private int currentDexDisplayId() {
        if (SunshineServer.activeDexDisplayId >= 0) {
            return SunshineServer.activeDexDisplayId;
        }
        if (ProjectViaDp.isActive() && State.externalDisplayId > 0) {
            return State.externalDisplayId;
        }
        if (TransportRegistry.isOptionalActive()) {
            return TransportRegistry.activeDisplayId();
        }
        return -1;
    }

    private void restartCurrentDexSession() {
        showToast("正在重启会话…");
        if (SunshineServer.isMoonlightSessionActive()) {
            SunshineServer.restartDexSession();
            return;
        }
        if (ProjectViaDp.isActive() && State.externalDisplayId > 0) {
            try {
                State.userService.restartSecondaryLauncher(
                        State.externalDisplayId,
                        State.externalDisplayWidth,
                        State.externalDisplayHeight);
            } catch (Throwable e) {
                showToast("DP 会话重启失败：" + e.getMessage());
            }
            return;
        }
        if (TransportRegistry.restartActive(!OutputSource.isMirrorActive(), (displayId, error) -> {
                if (getContext() == null) {
                    return;
                }
                if (error != null) {
                    showToast("会话重启失败：" + error);
                } else {
                    showToast("会话已重启");
                }
                refreshPlaceholderState();
            })) {
            return;
        }
        showToast("没有可重启的 DeX 会话");
    }

    private void showManualClientDialog() {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_manual_client_input, null);
        EditText ipEditText = dialogView.findViewById(R.id.ipEditText);
        EditText portEditText = dialogView.findViewById(R.id.portEditText);
        portEditText.setText("42515");
        new MaterialAlertDialogBuilder(
                requireContext(),
                R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setTitle("手动客户端")
                .setView(dialogView)
                .setPositiveButton("连接", (dialog, which) -> {
                    String ip = ipEditText.getText().toString().trim();
                    String port = portEditText.getText().toString().trim();
                    if (ip.isEmpty()) {
                        return;
                    }
                    String clientAddress = port.isEmpty() ? ip : ip + ":" + port;
                    Pref.getPreferences().edit()
                            .putString(Pref.KEY_SELECTED_CLIENT, clientAddress).apply();
                    int pin = (int) (Math.random() * 9000) + 1000;
                    SunshineServer.suppressPin = String.valueOf(pin);
                    ConnectToClient.connect(pin);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showToast(String message) {
        if (getContext() != null) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    private MirrorMainActivity getMainActivity() {
        return getActivity() instanceof MirrorMainActivity ? (MirrorMainActivity) getActivity() : null;
    }
}

package com.connect_screen.mirror;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

public final class UiCompat {
    private UiCompat() {
    }

    public static void tintSwitch(Context context, SwitchCompat switchCompat) {
        if (context == null || switchCompat == null) {
            return;
        }
        int accent = ContextCompat.getColor(context, R.color.ui_accent);
        int disabled = 0xFFD0D4D8;
        int unchecked = 0xFF9EA4AA;
        int[][] states = new int[][]{
                new int[]{-android.R.attr.state_enabled},
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        int trackChecked = (0x66 << 24) | (accent & 0x00FFFFFF);
        int trackUnchecked = 0x553F454A;
        int trackDisabled = 0x223F454A;
        switchCompat.setThumbTintList(new ColorStateList(states, new int[]{disabled, accent, unchecked}));
        switchCompat.setTrackTintList(new ColorStateList(states, new int[]{trackDisabled, trackChecked, trackUnchecked}));
        switchCompat.setTextColor(ContextCompat.getColor(context, R.color.ui_text_primary));
    }

    public static void applyStripedBackground(View view) {
        if (view == null || view.getContext() == null) {
            return;
        }
        view.setBackgroundColor(ContextCompat.getColor(view.getContext(), R.color.ui_background));
    }

    public static void applyStripedBackground(Activity activity) {
        if (activity == null) {
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) {
            return;
        }
        applyStripedBackground(content.getChildAt(0));
    }
}

package com.connect_screen.mirror;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class DebugLogDialog {
    private DebugLogDialog() {
    }

    public static void show(Context context) {
        View content = LayoutInflater.from(context)
                .inflate(R.layout.dialog_debug_log_panel, null);
        TextView summary = content.findViewById(R.id.debugSummaryText);
        summary.setText(buildDebugPanelSummaryText(State.streamingDebugInfo.getValue()));
        RecyclerView recyclerView = content.findViewById(R.id.debugLogRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        LogAdapter adapter = new LogAdapter(State.logs);
        recyclerView.setAdapter(adapter);
        content.findViewById(R.id.viewRecentHandshakeButton).setOnClickListener(v ->
                DebugDialogs.showLastMoonlightHandshakeDialog(context));
        content.findViewById(R.id.viewRecentControlInputButton).setOnClickListener(v ->
                DebugDialogs.showLastMoonlightControlInputDialog(context));
        AlertDialog dialog = new MaterialAlertDialogBuilder(
                context,
                R.style.ThemeOverlay_LibreDeX_MaterialAlertDialog)
                .setView(content)
                .setPositiveButton(context.getString(R.string.action_done), null)
                .create();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable refreshRunnable = new Runnable() {
            private int lastLogCount = -1;

            @Override
            public void run() {
                if (!dialog.isShowing()) {
                    return;
                }
                summary.setText(buildDebugPanelSummaryText(State.streamingDebugInfo.getValue()));
                if (lastLogCount != State.logs.size()) {
                    lastLogCount = State.logs.size();
                    adapter.notifyDataSetChanged();
                    recyclerView.scrollToPosition(Math.max(0, adapter.getItemCount() - 1));
                }
                handler.postDelayed(this, 500);
            }
        };
        dialog.setOnShowListener(d -> {
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.92f);
                window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            handler.post(refreshRunnable);
        });
        dialog.setOnDismissListener(d -> handler.removeCallbacks(refreshRunnable));
        dialog.show();
    }

    private static String buildDebugPanelSummaryText(String info) {
        if (info == null || info.trim().isEmpty() || "串流未启动".equals(info.trim())) {
            return State.getContext().getString(R.string.debug_summary_placeholder);
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
        builder.append(State.getContext().getString(R.string.debug_label_status)).append(emptyAsDash(status)).append('\n');
        builder.append(State.getContext().getString(R.string.debug_label_encoder)).append(emptyAsDash(codec));
        if (!level.isEmpty() && !"-".equals(level)) {
            builder.append(State.getContext().getString(R.string.debug_join_level, level));
        }
        builder.append('\n');
        builder.append(State.getContext().getString(R.string.debug_label_resolution)).append(emptyAsDash(size)).append('\n');
        builder.append(State.getContext().getString(R.string.debug_label_fps_req)).append(emptyAsDash(clientAndEncoderFps)).append('\n');
        builder.append(State.getContext().getString(R.string.debug_label_fps_io)).append(emptyAsDash(inputFps))
                .append(" / ").append(emptyAsDash(outputFps)).append('\n');
        builder.append(State.getContext().getString(R.string.debug_label_bitrate)).append(emptyAsDash(encodedBitrate))
                .append(State.getContext().getString(R.string.debug_join_target, emptyAsDash(targetBitrate))).append('\n');
        builder.append(State.getContext().getString(R.string.debug_label_ping)).append(emptyAsDash(ping));
        appendIfPresent(builder, State.getContext().getString(R.string.debug_field_priority), priority);
        appendIfPresent(builder, State.getContext().getString(R.string.debug_field_audio), audio);
        appendIfPresent(builder, State.getContext().getString(R.string.debug_field_color), color);
        appendIfPresent(builder, State.getContext().getString(R.string.debug_field_output_gap), outputGap);
        appendIfPresent(builder, State.getContext().getString(R.string.debug_field_queue), queue);
        appendIfPresent(builder, State.getContext().getString(R.string.debug_field_native), nativeCost);
        appendIfPresent(builder, State.getContext().getString(R.string.debug_field_frame_pacer), stripPrefix(framePacer, "Frame pacer:"));
        return builder.toString();
    }

    private static String valueAfterPrefix(String info, String prefix) {
        for (String line : info.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static String extractInputFps(String info) {
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

    private static String valueAfterKnownPrefix(String line, String prefix) {
        return line.startsWith(prefix) ? line.substring(prefix.length()).trim() : "";
    }

    private static String findLineStartingWith(String info, String prefix) {
        for (String line : info.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed;
            }
        }
        return "";
    }

    private static String stripPrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()).trim() : value;
    }

    private static void appendIfPresent(StringBuilder builder, String label, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        builder.append('\n').append(label).append(State.getContext().getString(R.string.debug_field_sep)).append(value.trim());
    }

    private static String emptyAsDash(String value) {
        return value == null || value.isEmpty() ? State.getContext().getString(R.string.value_placeholder) : value;
    }
}

package dev.lizardbyte.sunshine.android;

public interface SunshineHostListener {
    default void onPinRequested() {
    }

    default void onSessionStateChanged(SunshineSessionState state) {
    }

    default void onClientConnected(String clientName) {
    }

    default void onClientConnected(String clientName, String clientAddress) {
        onClientConnected(clientName);
    }

    default void onClientDisconnected(String clientName) {
    }

    default void onStats(SunshineStats stats) {
    }

    default void onLog(SunshineLogLevel level, String message) {
    }

    default void onEncoderError(String message) {
    }
}

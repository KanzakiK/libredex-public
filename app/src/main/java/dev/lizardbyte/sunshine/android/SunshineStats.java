package dev.lizardbyte.sunshine.android;

public final class SunshineStats {
    public final SunshineSessionState state;
    public final String clientName;
    public final long uptimeMs;
    public final long videoFrames;
    public final long videoKeyFrames;
    public final long videoBytes;
    public final long audioFrames;
    public final long audioSamples;

    SunshineStats(
            SunshineSessionState state,
            String clientName,
            long uptimeMs,
            long videoFrames,
            long videoKeyFrames,
            long videoBytes,
            long audioFrames,
            long audioSamples) {
        this.state = state;
        this.clientName = clientName;
        this.uptimeMs = uptimeMs;
        this.videoFrames = videoFrames;
        this.videoKeyFrames = videoKeyFrames;
        this.videoBytes = videoBytes;
        this.audioFrames = audioFrames;
        this.audioSamples = audioSamples;
    }
}

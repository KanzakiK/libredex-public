package dev.lizardbyte.sunshine.android;

public final class SunshineAudioFormat {
    public final int sampleRate;
    public final int channelCount;
    public final int frameSize;

    public SunshineAudioFormat(int sampleRate, int channelCount, int frameSize) {
        this.sampleRate = Math.max(1, sampleRate);
        this.channelCount = Math.max(1, channelCount);
        this.frameSize = Math.max(1, frameSize);
    }

    public int sampleCount() {
        return frameSize * channelCount;
    }
}

package dev.lizardbyte.sunshine.android;

public interface SunshineAudioSource {
    int READ_ERROR = -1;
    int READ_TIMEOUT = 0;

    void start(SunshineAudioFormat format);

    /**
     * Fills interleaved float PCM in [-1.0, 1.0].
     *
     * @return number of frames written, READ_TIMEOUT for no data yet, or READ_ERROR to reinitialize.
     */
    int read(float[] buffer, int frameCount);

    void stop();
}

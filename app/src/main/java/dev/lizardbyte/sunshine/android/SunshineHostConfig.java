package dev.lizardbyte.sunshine.android;

import java.io.File;

public final class SunshineHostConfig {
    public final String hostName;
    public final File filesDir;
    public final int port;
    public final boolean enableHevc;
    public final boolean enableAudio;

    private SunshineHostConfig(Builder builder) {
        hostName = builder.hostName;
        filesDir = builder.filesDir;
        port = builder.port;
        enableHevc = builder.enableHevc;
        enableAudio = builder.enableAudio;
    }

    public static final class Builder {
        private String hostName = "Sunshine Android";
        private File filesDir;
        private int port = 47989;
        private boolean enableHevc = true;
        private boolean enableAudio = true;

        public Builder(File filesDir) {
            this.filesDir = filesDir;
        }

        public Builder setHostName(String hostName) {
            this.hostName = hostName;
            return this;
        }

        public Builder setPort(int port) {
            this.port = port;
            return this;
        }

        public Builder setEnableHevc(boolean enableHevc) {
            this.enableHevc = enableHevc;
            return this;
        }

        public Builder setEnableAudio(boolean enableAudio) {
            this.enableAudio = enableAudio;
            return this;
        }

        public SunshineHostConfig build() {
            if (filesDir == null) {
                throw new IllegalStateException("filesDir is required");
            }
            return new SunshineHostConfig(this);
        }
    }
}

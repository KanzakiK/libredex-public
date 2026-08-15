package com.connect_screen.mirror.job;

/**
 * The video source half of an output chain. Kept outside the UI layer so the
 * transport implementations do not depend on ConnectionFragment state.
 */
public enum OutputSource {
    DEX("dex"),
    MIRROR("mirror");

    private final String id;

    private static volatile OutputSource active = DEX;

    OutputSource(String id) {
        this.id = id;
    }

    public static OutputSource fromId(String id) {
        return "mirror".equals(id) ? MIRROR : DEX;
    }

    public static OutputSource getActive() {
        return active;
    }

    public static void setActive(OutputSource source) {
        if (source != null) {
            active = source;
        }
    }

    public static boolean isMirrorActive() {
        return active == MIRROR;
    }

    public String id() {
        return id;
    }
}

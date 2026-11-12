package com.neoalive.tacz_sewv.client;

/** Server radio switches as last told by {@code PacketRadioFlags}. Defaults until the login sync lands. */
public final class RadioFlagsClient {

    private static volatile boolean requireRadio = true;
    private static volatile boolean voice = false;

    private RadioFlagsClient() {}

    public static void accept(boolean requireRadio, boolean voice) {
        RadioFlagsClient.requireRadio = requireRadio;
        RadioFlagsClient.voice = voice;
    }

    public static boolean requireRadio() {
        return requireRadio;
    }

    /** Server has radio voice on and Simple Voice Chat running. */
    public static boolean voice() {
        return voice;
    }
}

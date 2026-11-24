package com.neoalive.tacz_sewv.client.voice;

/**
 * Where a voice order's audio comes from. Frames are 16 kHz mono signed 16-bit little endian, pushed on
 * the source's own thread. Names no Vosk or Voice Chat type, so either compat package can implement it.
 */
public interface MicSource {

    @FunctionalInterface
    interface Sink {
        void accept(byte[] pcm, int length);
    }

    /** Begin pushing frames. False if the microphone could not be opened. */
    boolean start(Sink sink);

    /** Stop pushing frames. Every frame read before this returns has been pushed. */
    void stop();
}

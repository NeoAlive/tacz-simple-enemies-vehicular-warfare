package com.neoalive.tacz_sewv.compat.voicechat;

import com.neoalive.tacz_sewv.audio.Downsample48to16;
import com.neoalive.tacz_sewv.client.voice.MicSource;

/** Voice Chat's own microphone frames, taken off its send path, so a voice order opens no second capture. */
final class SvcMicSource implements MicSource {

    private final Downsample48to16 down = new Downsample48to16();
    private MicSource.Sink sink;

    @Override
    public synchronized boolean start(MicSource.Sink sink) {
        this.sink = sink;
        this.down.reset();
        VoiceOrderTap.attach(this);
        return true;
    }

    @Override
    public synchronized void stop() {
        VoiceOrderTap.detach(this);
        this.sink = null;
    }

    /** Voice Chat's audio thread. */
    synchronized void push(short[] raw) {
        if (this.sink == null) return;
        byte[] pcm = this.down.process(raw);
        if (pcm.length > 0) this.sink.accept(pcm, pcm.length);
    }
}

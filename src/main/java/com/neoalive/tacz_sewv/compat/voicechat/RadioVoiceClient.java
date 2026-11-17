package com.neoalive.tacz_sewv.compat.voicechat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;

import com.neoalive.tacz_sewv.audio.RadioVoiceDsp;
import com.neoalive.tacz_sewv.client.RadioVoiceHud;
import com.neoalive.tacz_sewv.config.ClientConfig;

/**
 * Client half of radio voice, on Voice Chat's audio threads (one per incoming channel). Each radio stream
 * gets its own {@link RadioVoiceDsp}, seeded from the channel, so a speaker keeps one radio voice. Clicks
 * and the HUD are not played from here: {@link RadioVoiceHud} does that from the client tick.
 */
final class RadioVoiceClient {

    private static final long EVICT_NANOS = 10_000_000_000L;

    private static final Map<UUID, Stream> STREAMS = new ConcurrentHashMap<>();

    private RadioVoiceClient() {}

    private static final class Stream {
        final RadioVoiceDsp dsp;
        volatile long last;

        Stream(UUID id) {
            this.dsp = new RadioVoiceDsp(id.getLeastSignificantBits());
        }
    }

    static void onReceive(ClientReceiveSoundEvent.StaticSound event) {
        UUID id = event.getId();
        if (!RadioVoiceIds.isRadio(id)) return;
        long now = System.nanoTime();
        Stream stream = STREAMS.get(id);
        if (stream == null) {
            STREAMS.values().removeIf(s -> now - s.last > EVICT_NANOS);
            stream = STREAMS.computeIfAbsent(id, Stream::new);
        }
        stream.last = now;
        boolean fresh = RadioVoiceHud.noteReceive(id, now);
        short[] raw = event.getRawAudio();
        if (raw == null || !ClientConfig.flag(ClientConfig.RADIO_VOICE_EFFECT)) return;
        if (fresh) stream.dsp.reset();
        event.setRawAudio(stream.dsp.process(raw));
    }

    static void onTransmit(ClientSoundEvent event) {
        if (!event.isWhispering()) RadioVoiceHud.noteTransmit(System.nanoTime());
    }
}

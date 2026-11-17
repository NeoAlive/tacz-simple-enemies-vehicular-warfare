package com.neoalive.tacz_sewv.compat.voicechat;

import java.util.List;
import java.util.UUID;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;

import com.neoalive.tacz_sewv.notify.RadioNet;

/**
 * Server half of radio voice, on Voice Chat's network thread. The opus payload is forwarded untouched as a
 * static packet to each listener {@link RadioVoiceNet} lists: no decode or encode here, the radio sound is
 * applied by each receiving client. The speaker's own proximity voice is left alone, and a whisper never
 * keys the radio.
 */
final class RadioVoiceRelay {

    private RadioVoiceRelay() {}

    static void onMic(MicrophonePacketEvent event) {
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null || sender.getPlayer() == null || !RadioNet.voiceEnabled()) return;
        MicrophonePacket packet = event.getPacket();
        if (packet.isWhispering()) return;
        UUID speaker = sender.getPlayer().getUuid();
        VoicechatServerApi api = event.getVoicechat();
        int echo = RadioVoiceLoopback.delayMs(speaker);
        if (echo >= 0 && RadioVoiceNet.isTransmitting(speaker)) {
            UUID channel = RadioVoiceIds.of(speaker);
            RadioVoiceEcho.schedule(api, sender, packet.staticSoundPacketBuilder()
                    .channelId(channel)
                    .sender(channel)
                    .category(SewvVoicechatPlugin.CATEGORY)
                    .build(), echo);
        }
        List<UUID> listeners = RadioVoiceNet.receiversOf(speaker);
        if (listeners.isEmpty()) return;
        StaticSoundPacket out = packet.staticSoundPacketBuilder()
                .channelId(RadioVoiceIds.of(speaker))
                .category(SewvVoicechatPlugin.CATEGORY)
                .build();
        for (UUID id : listeners) {
            VoicechatConnection conn = api.getConnectionOf(id);
            if (conn == null || !conn.isConnected() || conn.isDisabled()) continue;
            api.sendStaticSoundPacketTo(conn, out);
        }
    }
}

package com.neoalive.tacz_sewv.compat.voicechat;

import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;

import com.neoalive.tacz_sewv.client.voice.MicSource;
import com.neoalive.tacz_sewv.client.voice.VoiceOrders;

/**
 * Where voice orders meet Voice Chat on the client. A voice order listens through Voice Chat's microphone
 * whenever Voice Chat is sending (voice activation, or its own push to talk held), so the device is opened
 * once; otherwise the order uses its own capture, which is safe beside Voice Chat on shared mode audio.
 * While an order is being spoken the outgoing frames are cancelled unless the player chose to be heard;
 * a cancelled frame is not sent at all, so nobody sees a silent speaker.
 */
final class VoiceOrderTap {

    private static final String ACTIVATION_KEY = "microphone_activation_type";

    private static volatile VoicechatClientApi api;
    private static volatile boolean connected;
    private static volatile SvcMicSource active;

    private VoiceOrderTap() {}

    static void onConnection(ClientVoicechatConnectionEvent event) {
        api = event.getVoicechat();
        connected = event.isConnected();
    }

    /** Voice Chat's microphone if it is sending right now, else null. */
    static MicSource micIfLive() {
        VoicechatClientApi a = api;
        if (a == null || !connected || a.isDisabled() || a.isMuted()) return null;
        boolean ptt = !"VOICE".equalsIgnoreCase(a.getClientConfig().getString(ACTIVATION_KEY, "PTT"));
        if (ptt && !a.isPushToTalkKeyPressed()) return null;
        return new SvcMicSource();
    }

    static void attach(SvcMicSource source) {
        active = source;
    }

    static void detach(SvcMicSource source) {
        if (active == source) active = null;
    }

    /** First look at every outgoing frame. True when the frame was withheld. Voice Chat's audio thread. */
    static boolean onFrame(ClientSoundEvent event) {
        short[] raw = event.getRawAudio();
        SvcMicSource source = active;
        if (source != null && raw != null) source.push(raw);
        if (VoiceOrders.muteOutgoing()) {
            event.cancel();
            return true;
        }
        return false;
    }
}

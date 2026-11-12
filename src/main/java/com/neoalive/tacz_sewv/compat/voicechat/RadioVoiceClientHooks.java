package com.neoalive.tacz_sewv.compat.voicechat;

import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;

/** Client event wiring, kept out of the plugin class so a dedicated server never links the client side. */
final class RadioVoiceClientHooks {

    private RadioVoiceClientHooks() {}

    static void register(EventRegistration registration) {
        registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class, RadioVoiceClient::onReceive);
        registration.registerEvent(ClientSoundEvent.class, RadioVoiceClient::onTransmit);
    }
}

package com.neoalive.tacz_sewv.compat.voicechat;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;

import com.neoalive.tacz_sewv.TaczSewv;

/** Found by Voice Chat's annotation scan, so it only ever loads when Voice Chat is installed. */
@ForgeVoicechatPlugin
public class SewvVoicechatPlugin implements VoicechatPlugin {

    public static final String CATEGORY = "tacz_sewv_radio";

    @Override
    public String getPluginId() {
        return TaczSewv.MODID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        TaczSewv.LOGGER.info("Simple Voice Chat found: handheld radio voice nets on");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, SewvVoicechatPlugin::onServerStarted);
        registration.registerEvent(VoicechatServerStoppedEvent.class, e -> VoicechatCompat.serverStopped());
        registration.registerEvent(MicrophonePacketEvent.class, RadioVoiceRelay::onMic);
        if (FMLEnvironment.dist.isClient()) RadioVoiceClientHooks.register(registration);
    }

    private static void onServerStarted(VoicechatServerStartedEvent event) {
        VoicechatServerApi api = event.getVoicechat();
        VolumeCategory radio = api.volumeCategoryBuilder()
                .setId(CATEGORY)
                .setName("Radio")
                .setNameTranslationKey("voicechat.category.tacz_sewv_radio")
                .setDescription("Voices heard over a handheld radio")
                .setDescriptionTranslationKey("voicechat.category.tacz_sewv_radio.description")
                .build();
        api.registerVolumeCategory(radio);
        VoicechatCompat.serverStarted(api.getVoiceChatDistance());
    }
}

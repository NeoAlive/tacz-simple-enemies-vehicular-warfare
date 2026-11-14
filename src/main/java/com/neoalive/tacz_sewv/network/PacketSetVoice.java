package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.crew.PmcVoices;
import com.neoalive.tacz_sewv.crew.VoicePreference;

/** Client to server: the sender's crew radio language for their PMC crews. Applies at once. */
public class PacketSetVoice {

    private final String voice;

    public PacketSetVoice(String voice) {
        this.voice = voice;
    }

    public PacketSetVoice(FriendlyByteBuf buf) {
        this.voice = buf.readUtf(32);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.voice, 32);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !PmcVoices.valid(this.voice)) return;
            VoicePreference.set(player, this.voice);
            PmcVoices.Voice v = PmcVoices.of(this.voice);
            Component name = v == null || v.name() == null
                    ? Component.translatable("gui.tacz_sewv.tdt.voice.default")
                    : Component.literal(v.name());
            NetworkHandler.sendOrderFeedback(player,
                    Component.translatable("message.tacz_sewv.identity.voice_set", name));
        });
        ctx.get().setPacketHandled(true);
    }
}

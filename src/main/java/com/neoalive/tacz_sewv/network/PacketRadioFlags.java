package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.RadioFlagsClient;
import com.neoalive.tacz_sewv.compat.voicechat.VoicechatCompat;
import com.neoalive.tacz_sewv.notify.RadioNet;

/** Server to client on login: the server radio switches the panel needs to label its toggles. */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public class PacketRadioFlags {

    private final boolean requireRadio;
    private final boolean voice;

    public PacketRadioFlags(boolean requireRadio, boolean voice) {
        this.requireRadio = requireRadio;
        this.voice = voice;
    }

    public PacketRadioFlags(FriendlyByteBuf buf) {
        this.requireRadio = buf.readBoolean();
        this.voice = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(this.requireRadio);
        buf.writeBoolean(this.voice);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> RadioFlagsClient.accept(this.requireRadio, this.voice)));
        ctx.get().setPacketHandled(true);
    }

    public static void sync(ServerPlayer player) {
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketRadioFlags(RadioNet.requireRadio(), RadioNet.voiceEnabled() && VoicechatCompat.serverActive()));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player);
    }
}

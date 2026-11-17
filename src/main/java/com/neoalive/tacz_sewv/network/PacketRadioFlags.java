package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.RadioFlagsClient;
import com.neoalive.tacz_sewv.compat.voicechat.VoicechatCompat;
import com.neoalive.tacz_sewv.notify.RadioNet;
import com.neoalive.tacz_sewv.platform.DistExecutor;
import com.neoalive.tacz_sewv.platform.NetworkEvent;
import com.neoalive.tacz_sewv.platform.PacketDistributor;

/**
 * Server to client: the server radio switches the panel needs to label its toggles. Sent on login, and
 * again to everyone when a switch flips (config edit, Voice Chat starting late), checked once a second.
 */
@EventBusSubscriber(modid = TaczSewv.MODID)
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

    private static int lastSent = -1;

    private static int current() {
        return (RadioNet.requireRadio() ? 1 : 0) | (RadioNet.voiceEnabled() && VoicechatCompat.serverActive() ? 2 : 0);
    }

    private static PacketRadioFlags of(int bits) {
        return new PacketRadioFlags((bits & 1) != 0, (bits & 2) != 0);
    }

    public static void sync(ServerPlayer player) {
        int bits = current();
        lastSent = bits;
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), of(bits));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0) return;
        int bits = current();
        if (bits == lastSent) return;
        lastSent = bits;
        NetworkHandler.CHANNEL.send(PacketDistributor.ALL.noArg(), of(bits));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        lastSent = -1;
    }
}

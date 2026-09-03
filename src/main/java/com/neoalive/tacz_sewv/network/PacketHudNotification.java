package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.client.NotificationHud;

/** Server→client: enqueue one HUD notification on that player's screen. */
public class PacketHudNotification {

    private final String title;
    private final String body;

    public PacketHudNotification(String title, String body) {
        this.title = title == null ? "" : title;
        this.body = body == null ? "" : body;
    }

    public PacketHudNotification(FriendlyByteBuf buf) {
        this.title = buf.readUtf();
        this.body = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.title);
        buf.writeUtf(this.body);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> NotificationHud.push(
                                Component.literal(this.title), Component.literal(this.body))));
        ctx.get().setPacketHandled(true);
    }

    public static void sendTo(ServerPlayer player, String title, String body) {
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketHudNotification(title, body));
    }
}

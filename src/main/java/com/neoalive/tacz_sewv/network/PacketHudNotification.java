package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;

import com.neoalive.tacz_sewv.client.NotificationHud;
import com.neoalive.tacz_sewv.notify.NotificationKind;
import com.neoalive.tacz_sewv.platform.BufCompat;
import com.neoalive.tacz_sewv.platform.DistExecutor;
import com.neoalive.tacz_sewv.platform.NetworkEvent;
import com.neoalive.tacz_sewv.platform.PacketDistributor;

/** Server→client: enqueue one HUD notification on that player's screen. */
public class PacketHudNotification {

    private final Component title;
    private final Component body;
    private final NotificationKind kind;

    public PacketHudNotification(Component title, Component body, NotificationKind kind) {
        this.title = title == null ? Component.empty() : title;
        this.body = body == null ? Component.empty() : body;
        this.kind = kind == null ? NotificationKind.GENERIC : kind;
    }

    public PacketHudNotification(FriendlyByteBuf buf) {
        this.title = BufCompat.readComponent(buf);
        this.body = BufCompat.readComponent(buf);
        this.kind = NotificationKind.byOrdinal(buf.readVarInt());
    }

    public void encode(FriendlyByteBuf buf) {
        BufCompat.writeComponent(buf, this.title);
        BufCompat.writeComponent(buf, this.body);
        buf.writeVarInt(this.kind.ordinal());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> NotificationHud.push(this.title, this.body, this.kind)));
        ctx.get().setPacketHandled(true);
    }

    public static void sendTo(ServerPlayer player, Component title, Component body,
                              NotificationKind kind) {
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketHudNotification(title, body, kind));
    }

    /** Debug / literal convenience. */
    public static void sendTo(ServerPlayer player, String title, String body, NotificationKind kind) {
        sendTo(player, Component.literal(title == null ? "" : title),
                Component.literal(body == null ? "" : body), kind);
    }
}

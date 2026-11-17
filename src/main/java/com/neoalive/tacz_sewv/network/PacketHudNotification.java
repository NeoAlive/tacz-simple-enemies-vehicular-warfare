package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;

import com.neoalive.tacz_sewv.client.NotificationHud;
import com.neoalive.tacz_sewv.client.RadioCue;
import com.neoalive.tacz_sewv.notify.NotificationKind;
import com.neoalive.tacz_sewv.notify.RadioNet;
import com.neoalive.tacz_sewv.platform.BufCompat;
import com.neoalive.tacz_sewv.platform.DistExecutor;
import com.neoalive.tacz_sewv.platform.NetworkEvent;
import com.neoalive.tacz_sewv.platform.PacketDistributor;

/**
 * Server to client: enqueue one HUD notification on that player's screen. Every toast arrives over the
 * radio, so {@link #sendTo} drops it for a player who carries none ({@link RadioNet}); only the grace
 * broadcast goes through {@link #sendUngated}.
 */
public class PacketHudNotification {

    private final Component title;
    private final Component body;
    private final NotificationKind kind;
    private final boolean urgent;

    public PacketHudNotification(Component title, Component body, NotificationKind kind, boolean urgent) {
        this.title = title == null ? Component.empty() : title;
        this.body = body == null ? Component.empty() : body;
        this.kind = kind == null ? NotificationKind.GENERIC : kind;
        this.urgent = urgent;
    }

    public PacketHudNotification(FriendlyByteBuf buf) {
        this.title = BufCompat.readComponent(buf);
        this.body = BufCompat.readComponent(buf);
        this.kind = NotificationKind.byOrdinal(buf.readVarInt());
        this.urgent = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        BufCompat.writeComponent(buf, this.title);
        BufCompat.writeComponent(buf, this.body);
        buf.writeVarInt(this.kind.ordinal());
        buf.writeBoolean(this.urgent);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                    if (NotificationHud.push(this.title, this.body, this.kind)) RadioCue.notification(this.urgent);
                }));
        ctx.get().setPacketHandled(true);
    }

    public static void sendTo(ServerPlayer player, Component title, Component body, NotificationKind kind) {
        sendTo(player, title, body, kind, kind != null && kind.urgent());
    }

    public static void sendTo(ServerPlayer player, Component title, Component body, NotificationKind kind,
                              boolean urgent) {
        if (!RadioNet.receives(player)) return;
        sendUngated(player, title, body, kind, urgent);
    }

    /** Bypasses the radio gate. Game-state broadcasts only (grace period). */
    public static void sendUngated(ServerPlayer player, Component title, Component body, NotificationKind kind,
                                   boolean urgent) {
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketHudNotification(title, body, kind, urgent));
    }

    /** Debug / literal convenience. */
    public static void sendTo(ServerPlayer player, String title, String body, NotificationKind kind) {
        sendTo(player, Component.literal(title == null ? "" : title),
                Component.literal(body == null ? "" : body), kind);
    }
}

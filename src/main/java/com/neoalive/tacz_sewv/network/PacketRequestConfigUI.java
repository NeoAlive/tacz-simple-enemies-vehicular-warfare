package com.neoalive.tacz_sewv.network;

import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.config.ConfigApplier;

/** Client requests the config UI; server replies with {@link PacketOpenConfigUI}. */
public class PacketRequestConfigUI {

    public PacketRequestConfigUI() {}

    public PacketRequestConfigUI(FriendlyByteBuf ignored) {}

    public void encode(FriendlyByteBuf buf) {}

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = ctx.get().getSender();
            if (player == null || player.getServer() == null) return;
            boolean canEdit = player.hasPermissions(2);
            Map<Integer, Object> server = canEdit
                    ? ConfigApplier.captureServerSnapshot(player.getServer())
                    : Map.of();
            NetworkHandler.CHANNEL.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new PacketOpenConfigUI(canEdit, server));
        });
        ctx.get().setPacketHandled(true);
    }
}

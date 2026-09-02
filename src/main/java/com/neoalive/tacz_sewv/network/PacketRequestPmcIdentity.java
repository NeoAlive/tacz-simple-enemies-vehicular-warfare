package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.NamePreference;
import com.neoalive.tacz_sewv.crew.PmcIdentityPreference;

/** C→S: TDT Identity tab opened — request saved PMC identity + name category. */
public final class PacketRequestPmcIdentity {

    public PacketRequestPmcIdentity() {
    }

    public PacketRequestPmcIdentity(FriendlyByteBuf buf) {
    }

    public void encode(FriendlyByteBuf buf) {
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            String category = NamePreference.get(player, SewvConfig.DEFAULT_NAME_CATEGORY.get());
            PmcIdentityPreference.PmcIdentity identity = PmcIdentityPreference.get(player);
            NetworkHandler.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new PacketSyncPmcIdentity(identity, category));
        });
        ctx.get().setPacketHandled(true);
    }
}

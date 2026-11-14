package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.skin.WebLogoCache;

/** Client to server: download this link as a logo preview. Answered with {@link PacketWebLogo}. */
public final class PacketFetchWebLogo {

    private final String url;

    public PacketFetchWebLogo(String url) {
        this.url = url;
    }

    public PacketFetchWebLogo(FriendlyByteBuf buf) {
        this.url = buf.readUtf(WebLogoCache.MAX_URL * 4);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.url, WebLogoCache.MAX_URL * 4);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) WebLogoCache.request(player, this.url);
        });
        ctx.get().setPacketHandled(true);
    }
}

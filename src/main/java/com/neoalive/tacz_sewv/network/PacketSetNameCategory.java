package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.crew.NamePreference;

/**
 * TDT "Identity" category, "Full Names" control: sets the sender's own preferred
 * {@link com.neoalive.tacz_sewv.crew.NamePools} category for their future PMC recruits. Targets
 * no unit — just the sender's own preference — so no {@code OrderAuth} ownership check applies.
 */
public class PacketSetNameCategory {

    private final String category;

    public PacketSetNameCategory(String category) {
        this.category = category;
    }

    public PacketSetNameCategory(FriendlyByteBuf buf) {
        this.category = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.category);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (!(player instanceof ServerPlayer sp)) return;

            NamePreference.set(sp, this.category);
            NetworkHandler.sendOrderFeedback(sp,
                    Component.translatable("message.tacz_sewv.identity.category_set", this.category));
        });
        ctx.get().setPacketHandled(true);
    }
}

package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.config.ClientConfig;

public class PacketOrderFeedback {

    private final Component message;

    public PacketOrderFeedback(Component message) {
        this.message = message;
    }

    public PacketOrderFeedback(FriendlyByteBuf buf) {
        this.message = buf.readComponent();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeComponent(this.message);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> this::show));
        ctx.get().setPacketHandled(true);
    }

    /**
     * Chat, not the action bar. The bar is one line that the next message overwrites, so an order's
     * result and the reason a unit refused it could never be read together — and there is no
     * scrollback to check afterwards. Every server-side order result flows through this one call,
     * which is why the whole channel moves here rather than at ~20 call sites.
     */
    private void show() {
        if (!ClientConfig.SHOW_ORDER_FEEDBACK.get()) return;
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(this.message, false);
        }
    }
}

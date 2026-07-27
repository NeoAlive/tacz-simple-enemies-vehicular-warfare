package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

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

    private void show() {
        if (!ClientConfig.SHOW_ORDER_FEEDBACK.get()) return;
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(this.message, true);
        }
    }
}

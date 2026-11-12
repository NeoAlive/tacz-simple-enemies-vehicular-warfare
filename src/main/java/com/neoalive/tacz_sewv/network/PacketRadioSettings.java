package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.item.RadioSettings;

/**
 * Client to server: the radio panel's comms toggles. The panel's own NBT write lands on the client copy
 * only and the next slot sync would revert it, so the server stack is written here.
 */
public class PacketRadioSettings {

    private final RadioSettings.Comms comms;

    public PacketRadioSettings(RadioSettings.Comms comms) {
        this.comms = comms;
    }

    public PacketRadioSettings(FriendlyByteBuf buf) {
        this.comms = new RadioSettings.Comms(buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                RadioSettings.clampNet(buf.readVarInt()));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(this.comms.alerts());
        buf.writeBoolean(this.comms.chatter());
        buf.writeBoolean(this.comms.voice());
        buf.writeVarInt(this.comms.net());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ItemStack radio = HandheldRadioItem.findCarried(player);
            if (!radio.isEmpty()) RadioSettings.writeComms(radio, this.comms);
        });
        ctx.get().setPacketHandled(true);
    }
}

package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.item.PlaneAttackMode;
import com.neoalive.tacz_sewv.item.RadioFrequency;
import com.neoalive.tacz_sewv.item.RadioSettings;

/**
 * Client to server: everything the radio panel edits. The panel's own NBT write lands on the client copy
 * only, and the next slot sync would revert it, so the server stack is written here on every change.
 */
public class PacketRadioSettings {

    private final RadioSettings.State state;
    private final RadioSettings.Comms comms;

    public PacketRadioSettings(RadioSettings.State state, RadioSettings.Comms comms) {
        this.state = state;
        this.comms = comms;
    }

    public PacketRadioSettings(FriendlyByteBuf buf) {
        RadioFrequency[] bands = RadioFrequency.values();
        RadioFrequency band = bands[Math.max(0, Math.min(buf.readVarInt(), bands.length - 1))];
        int delay = RadioSettings.clampDelay(buf.readVarInt());
        PlaneAttackMode plane = PlaneAttackMode.byOrdinal(buf.readVarInt());
        this.state = new RadioSettings.State(band, delay, plane).withFrequency(band);
        this.comms = new RadioSettings.Comms(buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                RadioSettings.clampNet(buf.readVarInt()));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.state.frequency().ordinal());
        buf.writeVarInt(this.state.delaySeconds());
        buf.writeVarInt(this.state.planeMode().ordinal());
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
            if (radio.isEmpty()) return;
            RadioSettings.write(radio, this.state);
            RadioSettings.writeComms(radio, this.comms);
        });
        ctx.get().setPacketHandled(true);
    }
}

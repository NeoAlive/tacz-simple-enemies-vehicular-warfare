package com.neoalive.tacz_sewv.network;

import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.config.ConfigApplier;
import com.neoalive.tacz_sewv.config.ConfigWireCodec;

/** Client → server: apply changed server-scope config drafts. */
public class PacketSaveConfigUI {

    private final Map<Integer, String> changes;

    public PacketSaveConfigUI(Map<Integer, String> changes) {
        this.changes = changes;
    }

    public PacketSaveConfigUI(FriendlyByteBuf buf) {
        this.changes = ConfigWireCodec.readDraftChanges(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        ConfigWireCodec.writeDraftChanges(buf, this.changes);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = ctx.get().getSender();
            if (player == null) return;
            ConfigApplier.applyServer(player, this.changes);
        });
        ctx.get().setPacketHandled(true);
    }
}

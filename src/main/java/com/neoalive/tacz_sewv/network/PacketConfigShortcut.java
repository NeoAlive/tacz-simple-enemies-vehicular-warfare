package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.invasion.MiscEditorAccess;
import com.neoalive.tacz_sewv.util.PoolEditorAccess;
import com.neoalive.tacz_sewv.util.TargetPriorityAccess;

/** Client → server: open a specialised editor from ConfigUI shortcuts. */
public class PacketConfigShortcut {

    private final String action;

    public PacketConfigShortcut(String action) {
        this.action = action;
    }

    public PacketConfigShortcut(FriendlyByteBuf buf) {
        this.action = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.action);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = ctx.get().getSender();
            if (player == null || !player.hasPermissions(2)) return;
            switch (this.action) {
                case "pool" -> PoolEditorAccess.open(player);
                case "misc" -> MiscEditorAccess.open(player);
                case "target_priority" -> TargetPriorityAccess.open(player);
                default -> { }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}

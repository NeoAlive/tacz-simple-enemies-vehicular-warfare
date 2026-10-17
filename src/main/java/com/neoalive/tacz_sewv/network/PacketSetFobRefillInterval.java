package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.fob.FobInstance;
import com.neoalive.tacz_sewv.fob.FobManager;
import com.neoalive.tacz_sewv.fob.FobNetworking;

/** Quarters bench: set this FOB's Periodic Refill interval (ticks, 0 = off). No GUI refresh reply. */
public class PacketSetFobRefillInterval {

    public static final int MAX_TICKS = 999_999;

    private final BlockPos commandPos;
    private final int ticks;

    public PacketSetFobRefillInterval(BlockPos commandPos, int ticks) {
        this.commandPos = commandPos;
        this.ticks = ticks;
    }

    public PacketSetFobRefillInterval(FriendlyByteBuf buf) {
        this.commandPos = buf.readBlockPos();
        this.ticks = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.commandPos);
        buf.writeVarInt(this.ticks);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            FobInstance fob = FobNetworking.resolveFob(level, this.commandPos, player.getUUID());
            if (fob == null) return;
            int ticks = Math.max(0, Math.min(MAX_TICKS, this.ticks));
            if (ticks == fob.periodicRefillTicks) return;
            fob.periodicRefillTicks = ticks;
            // Pull the next dispatch in if the new interval is shorter than what is left of the old
            // one, but never fire it on every keystroke.
            fob.nextPeriodicRefill = Math.min(fob.nextPeriodicRefill, level.getGameTime() + ticks);
            FobManager.get(level).setDirty();
        });
        ctx.get().setPacketHandled(true);
    }
}

package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.airport.HelipadClearance;
import com.neoalive.tacz_sewv.airport.HelipadTraffic;
import com.neoalive.tacz_sewv.block.HelipadBlockEntity;

/** Client to server: Check Clearance from the helipad panel. */
public class PacketHelipadAction {

    private static final double MAX_DISTANCE_SQ = 16.0 * 16.0;

    private final BlockPos pos;

    public PacketHelipadAction(BlockPos pos) {
        this.pos = pos;
    }

    public PacketHelipadAction(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            if (player.distanceToSqr(this.pos.getX() + 0.5, this.pos.getY() + 0.5, this.pos.getZ() + 0.5)
                    > MAX_DISTANCE_SQ) {
                return;
            }
            if (!(level.getBlockEntity(this.pos) instanceof HelipadBlockEntity pad)) return;

            // Drop the cached clearance first, exactly as the runway check does, so a failed
            // re-check cannot leave a stale "cleared" pad behind.
            pad.clearClearance();
            HelipadClearance.Result result = HelipadClearance.check(level, this.pos);
            if (result.status() == HelipadClearance.Status.OK) pad.applyClearance();
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new PacketOpenHelipadGui(this.pos, pad.isCleared(), result.status(), result.blocker(),
                            HelipadTraffic.isOccupied(level, this.pos)));
        });
        ctx.get().setPacketHandled(true);
    }
}

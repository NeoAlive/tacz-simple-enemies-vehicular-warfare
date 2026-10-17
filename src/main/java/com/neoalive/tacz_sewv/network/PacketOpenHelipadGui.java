package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.airport.HelipadClearance;
import com.neoalive.tacz_sewv.block.HelipadBlockEntity;
import com.neoalive.tacz_sewv.client.HelipadClient;

/** Server to client: open the helipad panel, or refresh it after Check Clearance. */
public class PacketOpenHelipadGui {

    private final BlockPos pos;
    private final boolean cleared;
    private final int status;
    @Nullable private final BlockPos blocker;
    private final boolean occupied;

    public PacketOpenHelipadGui(BlockPos pos, boolean cleared, HelipadClearance.Status status,
                                @Nullable BlockPos blocker, boolean occupied) {
        this.pos = pos;
        this.cleared = cleared;
        this.status = status.ordinal();
        this.blocker = blocker;
        this.occupied = occupied;
    }

    public PacketOpenHelipadGui(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.cleared = buf.readBoolean();
        this.status = buf.readVarInt();
        this.blocker = buf.readBoolean() ? buf.readBlockPos() : null;
        this.occupied = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeBoolean(this.cleared);
        buf.writeVarInt(this.status);
        buf.writeBoolean(this.blocker != null);
        if (this.blocker != null) buf.writeBlockPos(this.blocker);
        buf.writeBoolean(this.occupied);
    }

    public static PacketOpenHelipadGui of(HelipadBlockEntity pad, HelipadClearance.Status status,
                                          @Nullable BlockPos blocker, boolean occupied) {
        return new PacketOpenHelipadGui(pad.getBlockPos(), pad.isCleared(), status, blocker, occupied);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            HelipadClearance.Status[] all = HelipadClearance.Status.values();
            HelipadClearance.Status st = all[Math.max(0, Math.min(this.status, all.length - 1))];
            HelipadClient.open(this.pos, this.cleared, st, this.blocker, this.occupied);
        }));
        ctx.get().setPacketHandled(true);
    }
}

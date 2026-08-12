package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.airport.AirportClearance;
import com.neoalive.tacz_sewv.block.RunwayBlockEntity;
import com.neoalive.tacz_sewv.client.AirportClient;

/** Server → client: open / refresh the runway editor. */
public class PacketOpenAirportGui {

    private final BlockPos pos;
    private final int x1;
    private final int z1;
    private final int x2;
    private final int z2;
    private final boolean cleared;
    private final int status;
    @Nullable private final BlockPos blocker;
    private final int length;
    private final int width;
    private final int capacity;
    private final float slotFactor;
    private final float bufferFactor;
    private final float extraFactor;

    public PacketOpenAirportGui(BlockPos pos, int x1, int z1, int x2, int z2, boolean cleared,
                                AirportClearance.Status status, @Nullable BlockPos blocker,
                                int length, int width, int capacity,
                                float slotFactor, float bufferFactor, float extraFactor) {
        this.pos = pos;
        this.x1 = x1;
        this.z1 = z1;
        this.x2 = x2;
        this.z2 = z2;
        this.cleared = cleared;
        this.status = status.ordinal();
        this.blocker = blocker;
        this.length = length;
        this.width = width;
        this.capacity = capacity;
        this.slotFactor = slotFactor;
        this.bufferFactor = bufferFactor;
        this.extraFactor = extraFactor;
    }

    public PacketOpenAirportGui(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.x1 = buf.readVarInt();
        this.z1 = buf.readVarInt();
        this.x2 = buf.readVarInt();
        this.z2 = buf.readVarInt();
        this.cleared = buf.readBoolean();
        this.status = buf.readVarInt();
        this.blocker = buf.readBoolean() ? buf.readBlockPos() : null;
        this.length = buf.readVarInt();
        this.width = buf.readVarInt();
        this.capacity = buf.readVarInt();
        this.slotFactor = buf.readFloat();
        this.bufferFactor = buf.readFloat();
        this.extraFactor = buf.readFloat();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeVarInt(this.x1);
        buf.writeVarInt(this.z1);
        buf.writeVarInt(this.x2);
        buf.writeVarInt(this.z2);
        buf.writeBoolean(this.cleared);
        buf.writeVarInt(this.status);
        buf.writeBoolean(this.blocker != null);
        if (this.blocker != null) buf.writeBlockPos(this.blocker);
        buf.writeVarInt(this.length);
        buf.writeVarInt(this.width);
        buf.writeVarInt(this.capacity);
        buf.writeFloat(this.slotFactor);
        buf.writeFloat(this.bufferFactor);
        buf.writeFloat(this.extraFactor);
    }

    public static PacketOpenAirportGui open(RunwayBlockEntity runway) {
        return new PacketOpenAirportGui(
                runway.getBlockPos(),
                runway.getX1(), runway.getZ1(), runway.getX2(), runway.getZ2(),
                runway.isCleared(),
                AirportClearance.Status.NONE,
                null,
                0, 0, 0,
                (float) runway.getSlotFactor(), (float) runway.getBufferFactor(),
                (float) runway.getExtraFactor());
    }

    public static PacketOpenAirportGui result(RunwayBlockEntity runway, AirportClearance.Status status,
                                              @Nullable BlockPos blocker) {
        int length = 0;
        int width = 0;
        int capacity = 0;
        if (status != AirportClearance.Status.NONE) {
            AirportClearance.Result geo = AirportClearance.evaluate(
                    runway.corner1(), runway.corner2(), runway.getBlockPos().getY(),
                    AirportClearance.Rules.forRunway(runway.getSlotFactor(),
                            runway.getBufferFactor(), runway.getExtraFactor()));
            length = geo.length();
            width = geo.width();
            capacity = geo.slots() == null ? 0 : geo.slots().capacity();
        }
        return new PacketOpenAirportGui(
                runway.getBlockPos(),
                runway.getX1(), runway.getZ1(), runway.getX2(), runway.getZ2(),
                runway.isCleared(),
                status,
                blocker,
                length, width, capacity,
                (float) runway.getSlotFactor(), (float) runway.getBufferFactor(),
                (float) runway.getExtraFactor());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            AirportClearance.Status st = AirportClearance.Status.values()[
                    Math.max(0, Math.min(this.status, AirportClearance.Status.values().length - 1))];
            AirportClient.open(
                    this.pos, this.x1, this.z1, this.x2, this.z2, this.cleared,
                    st, this.blocker, this.length, this.width, this.capacity,
                    this.slotFactor, this.bufferFactor, this.extraFactor);
        }));
        ctx.get().setPacketHandled(true);
    }
}

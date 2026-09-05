package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.PlayerRappelClient;

/**
 * S→C: player self-rappel input lock.
 * {@code mode}: {@link #MODE_OFF}, {@link #MODE_HOVER}, or {@link #MODE_ROPE}.
 * {@code hullId} is only meaningful for {@link #MODE_HOVER}.
 */
public final class PacketPlayerSelfRappelLock {

    public static final byte MODE_OFF = 0;
    public static final byte MODE_HOVER = 1;
    public static final byte MODE_ROPE = 2;

    private final byte mode;
    private final int hullId;

    public PacketPlayerSelfRappelLock(byte mode, int hullId) {
        this.mode = mode;
        this.hullId = hullId;
    }

    public static PacketPlayerSelfRappelLock off() {
        return new PacketPlayerSelfRappelLock(MODE_OFF, -1);
    }

    public static PacketPlayerSelfRappelLock hover(int hullId) {
        return new PacketPlayerSelfRappelLock(MODE_HOVER, hullId);
    }

    public static PacketPlayerSelfRappelLock rope() {
        return new PacketPlayerSelfRappelLock(MODE_ROPE, -1);
    }

    public PacketPlayerSelfRappelLock(FriendlyByteBuf buf) {
        this.mode = buf.readByte();
        this.hullId = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(this.mode);
        buf.writeVarInt(this.hullId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> PlayerRappelClient.setLock(this.mode, this.hullId)));
        ctx.get().setPacketHandled(true);
    }
}

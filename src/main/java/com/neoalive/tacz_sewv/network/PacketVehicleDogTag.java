package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** S→C: PMC logo grid for SBW dogTag bones (entity data alone is not reliably client-visible). */
public final class PacketVehicleDogTag {

    private static final int SIZE = 16;

    private final int entityId;
    /** Flat row-major 16×16 palette indices; {@code -1} = transparent. */
    private final short[] pixels;

    public PacketVehicleDogTag(int entityId, List<List<Short>> grid) {
        this.entityId = entityId;
        this.pixels = flatten(grid);
    }

    public PacketVehicleDogTag(FriendlyByteBuf buf) {
        this.entityId = buf.readVarInt();
        this.pixels = new short[SIZE * SIZE];
        for (int i = 0; i < this.pixels.length; i++) {
            this.pixels[i] = buf.readShort();
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        for (short pixel : this.pixels) {
            buf.writeShort(pixel);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> this::applyClient));
        ctx.get().setPacketHandled(true);
    }

    private void applyClient() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity entity = mc.level.getEntity(this.entityId);
        if (!(entity instanceof VehicleEntity hull)) return;
        hull.setDogTagIcon(unflatten(this.pixels));
    }

    private static short[] flatten(List<List<Short>> grid) {
        short[] out = new short[SIZE * SIZE];
        for (int x = 0; x < SIZE; x++) {
            List<Short> col = x < grid.size() ? grid.get(x) : List.of();
            for (int y = 0; y < SIZE; y++) {
                out[x * SIZE + y] = y < col.size() ? col.get(y) : -1;
            }
        }
        return out;
    }

    private static List<List<Short>> unflatten(short[] pixels) {
        List<List<Short>> grid = new ArrayList<>(SIZE);
        for (int x = 0; x < SIZE; x++) {
            List<Short> col = new ArrayList<>(SIZE);
            for (int y = 0; y < SIZE; y++) {
                col.add(pixels[x * SIZE + y]);
            }
            grid.add(col);
        }
        return grid;
    }
}

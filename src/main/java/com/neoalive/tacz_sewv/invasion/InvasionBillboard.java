package com.neoalive.tacz_sewv.invasion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * One world-space invasion billboard snapshot (S→C). Progress bar is shown only when
 * {@link #showProgress} is true (capturing or contested).
 */
public record InvasionBillboard(
        ResourceKey<Level> dimension,
        BlockPos pos,
        double yOffset,
        String label,
        int colorRgb,
        float progress,
        boolean showProgress,
        boolean contested
) {
    public static InvasionBillboard decode(FriendlyByteBuf buf) {
        return new InvasionBillboard(
                buf.readResourceKey(Registries.DIMENSION),
                buf.readBlockPos(),
                buf.readDouble(),
                buf.readUtf(64),
                buf.readInt() & 0xFFFFFF,
                buf.readFloat(),
                buf.readBoolean(),
                buf.readBoolean());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceKey(this.dimension);
        buf.writeBlockPos(this.pos);
        buf.writeDouble(this.yOffset);
        buf.writeUtf(this.label, 64);
        buf.writeInt(this.colorRgb);
        buf.writeFloat(this.progress);
        buf.writeBoolean(this.showProgress);
        buf.writeBoolean(this.contested);
    }
}

package com.neoalive.tacz_sewv.invasion;

import javax.annotation.Nullable;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Wire/DTO for the active Sweep &amp; Advance map overlay. Xaero-free — drawn from
 * {@code MapMarkers} by {@code MixinGuiMap}.
 */
public record SweepOverlayState(
        ResourceKey<Level> dimension,
        int left,
        int top,
        int right,
        int bottom,
        int quietSeconds,
        int quietNeed,
        boolean contested) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension.location());
        buf.writeVarInt(left);
        buf.writeVarInt(top);
        buf.writeVarInt(right);
        buf.writeVarInt(bottom);
        buf.writeVarInt(quietSeconds);
        buf.writeVarInt(quietNeed);
        buf.writeBoolean(contested);
    }

    public static SweepOverlayState decode(FriendlyByteBuf buf) {
        ResourceLocation dimId = buf.readResourceLocation();
        ResourceKey<Level> dim = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, dimId);
        return new SweepOverlayState(
                dim,
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean());
    }

    public static void encodeOptional(FriendlyByteBuf buf, @Nullable SweepOverlayState state) {
        buf.writeBoolean(state != null);
        if (state != null) state.encode(buf);
    }

    @Nullable
    public static SweepOverlayState decodeOptional(FriendlyByteBuf buf) {
        return buf.readBoolean() ? decode(buf) : null;
    }
}

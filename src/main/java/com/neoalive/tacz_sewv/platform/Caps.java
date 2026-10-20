package com.neoalive.tacz_sewv.platform;

import javax.annotation.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Item-handler lookups in the shape this mod used Forge's {@code getCapability(ITEM_HANDLER)}:
 * null when absent. For a unit's own pockets use {@code crew.UnitInventory}, which keeps the
 * "only a PMC has one" rule.
 */
public final class Caps {

    private Caps() {}

    @Nullable
    public static IItemHandler entityItems(@Nullable Entity entity) {
        return entity == null ? null : entity.getCapability(Capabilities.ItemHandler.ENTITY, null);
    }

    @Nullable
    public static IItemHandler blockItems(@Nullable BlockEntity be, @Nullable Direction side) {
        if (be == null || be.getLevel() == null) return null;
        return be.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, be.getBlockPos(), be.getBlockState(), be, side);
    }
}

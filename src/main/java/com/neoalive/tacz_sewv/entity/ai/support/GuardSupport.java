package com.neoalive.tacz_sewv.entity.ai.support;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

/**
 * GUARD_POSITION cache and the REACH promote-to-HOLD flag.
 *
 * <p>The post ({@code sewv:guard_pos}) lives on its {@link #holder}: the <b>hull</b> for a crew, so it
 * survives crew swaps and other orders until overwritten or the vehicle is destroyed, and the unit
 * itself on foot. The promote flag lives on the <b>PMC unit</b> ({@code sewv:reach_guard}) for the
 * duration of one REACH move only, and only a driver reads it.
 */
public final class GuardSupport {

    public static final String TAG_POS = "sewv:guard_pos";
    public static final String TAG_REACH = "sewv:reach_guard";

    private GuardSupport() {}

    /** Where {@code pmc}'s guard post is kept: the hull it rides, or the unit on foot. */
    public static Entity holder(PmcUnitEntity pmc) {
        return pmc.getVehicle() instanceof VehicleEntity hull ? hull : pmc;
    }

    public static void set(Entity holder, BlockPos pos) {
        holder.getPersistentData().putLong(TAG_POS, pos.asLong());
    }

    @Nullable
    public static BlockPos get(Entity holder) {
        CompoundTag data = holder.getPersistentData();
        if (!data.contains(TAG_POS)) return null;
        return BlockPos.of(data.getLong(TAG_POS));
    }

    public static boolean has(Entity holder) {
        return holder.getPersistentData().contains(TAG_POS);
    }

    public static void setReaching(PmcUnitEntity pmc, boolean reaching) {
        if (reaching) {
            pmc.getPersistentData().putBoolean(TAG_REACH, true);
        } else {
            pmc.getPersistentData().remove(TAG_REACH);
        }
    }

    public static boolean isReaching(AbstractUnit unit) {
        return unit instanceof PmcUnitEntity
                && unit.getPersistentData().getBoolean(TAG_REACH);
    }

    public static void clearReach(AbstractUnit unit) {
        unit.getPersistentData().remove(TAG_REACH);
    }
}

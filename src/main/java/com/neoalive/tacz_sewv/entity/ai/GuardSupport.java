package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Per-hull GUARD_POSITION cache and the REACH promote-to-HOLD flag.
 *
 * <p>Guard lives on the <b>hull</b> ({@code sewv:guard_pos}) so it survives crew swaps and other
 * orders until overwritten or the vehicle is destroyed. The promote flag lives on the <b>PMC
 * unit</b> ({@code sewv:reach_guard}) for the duration of one REACH move only.
 */
public final class GuardSupport {

    public static final String TAG_POS = "sewv:guard_pos";
    public static final String TAG_REACH = "sewv:reach_guard";

    private GuardSupport() {}

    public static void set(VehicleEntity hull, BlockPos pos) {
        hull.getPersistentData().putLong(TAG_POS, pos.asLong());
    }

    @Nullable
    public static BlockPos get(VehicleEntity hull) {
        CompoundTag data = hull.getPersistentData();
        if (!data.contains(TAG_POS)) return null;
        return BlockPos.of(data.getLong(TAG_POS));
    }

    public static boolean has(VehicleEntity hull) {
        return hull.getPersistentData().contains(TAG_POS);
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

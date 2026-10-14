package com.neoalive.tacz_sewv.spawn;

import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import com.neoalive.tacz_sewv.compat.lv.LvCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * The one place an event's crewed vehicle may come from the Limitless Vehicles pool instead of
 * the SBW one, so no event has to know LV exists. Each site passes its own SBW spawn as the
 * fallback and keeps using it unchanged.
 *
 * <p>The {@code lvPoolMixChance} roll is made only when LV is loaded and the faction has an LV
 * pool — otherwise the level's random sequence is exactly what it was before this existed. A
 * failed LV placement falls back to SBW, so turning the mix on never costs an event its vehicle.
 */
public final class EventVehicleSpawns {

    private EventVehicleSpawns() {}

    /** @return whether a crewed hull was spawned (from either pool) */
    public static boolean spawnCrewed(ServerLevel level, BlockPos pos, TankFaction faction, boolean water,
                                      BooleanSupplier sbw) {
        if (LvCompat.hasPool(faction)) {
            double chance = SewvConfig.LV_POOL_MIX_CHANCE.get();
            if (chance > 0.0 && level.random.nextDouble() < chance
                    && LvCompat.spawnCrewedForEvent(level, pos, faction, water) != null) {
                return true;
            }
        }
        return sbw.getAsBoolean();
    }
}

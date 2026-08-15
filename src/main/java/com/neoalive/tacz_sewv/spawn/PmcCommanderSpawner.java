package com.neoalive.tacz_sewv.spawn;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;

import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;
import com.neoalive.tacz_sewv.init.ModEntities;

/** Debug/egg-spawn entry point for {@link PmcCommanderEntity} — the only two ways one can appear. */
public final class PmcCommanderSpawner {

    private PmcCommanderSpawner() {}

    @Nullable
    public static PmcCommanderEntity spawn(ServerLevel level, BlockPos pos, @Nullable UUID owner) {
        PmcCommanderEntity unit = ModEntities.PMC_COMMANDER.get().create(level);
        if (unit == null) return null;
        unit.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        unit.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.COMMAND, null, null);
        if (owner != null) {
            unit.setOwner(owner);
        }
        level.addFreshEntity(unit);
        return unit;
    }
}

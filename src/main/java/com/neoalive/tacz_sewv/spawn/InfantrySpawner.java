package com.neoalive.tacz_sewv.spawn;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * One rifleman on the ground, ungated. World events reach it through {@code EventSpawns.infantry},
 * which applies the spawn toggles first; an invasion fields its infantry straight through here, the
 * same way it fields hulls with {@code requireSpawnsEnabled = false} — a match the op started is not
 * ambient spawning.
 * <p>
 * The scatter is applied in X/Z and the <b>Y is then re-read from the heightmap</b>, so a unit
 * scattered onto a slope stands on it rather than inside it or floating over it.
 */
public final class InfantrySpawner {

    private InfantrySpawner() {}

    /** {@code ownerId} only matters for PMC: the player whose command menu the unit answers. */
    public static AbstractUnit spawn(ServerLevel level, BlockPos anchor, TankFaction faction,
                                     @Nullable UUID ownerId, int scatter) {
        AbstractUnit unit = TankSpawner.createCrewUnit(level, faction, ownerId);
        int s = Math.max(0, scatter);
        int x = anchor.getX() + level.random.nextInt(s * 2 + 1) - s;
        int z = anchor.getZ() + level.random.nextInt(s * 2 + 1) - s;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        unit.setPos(x + 0.5, y, z + 0.5);
        unit.finalizeSpawn(level, level.getCurrentDifficultyAt(unit.blockPosition()), MobSpawnType.EVENT, null, null);
        level.addFreshEntity(unit);
        return unit;
    }
}

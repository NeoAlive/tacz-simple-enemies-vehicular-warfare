package com.neoalive.tacz_sewv.procedural.events;

import com.neoalive.tacz_sewv.util.TankSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.utils.UnitRole;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import net.nekoyuni.SimpleEnemyMod.registry.ModEntities;

import javax.annotation.Nullable;

/**
 * The two things every event in this package was writing out for itself: dropping a rifleman on
 * the ground, and choosing which of RU/US an event happens to be about.
 *
 * <p>Neither is interesting enough to live in five copies, and the infantry one in particular has a
 * detail worth having in exactly one place — the scatter is applied in X/Z and the <b>Y is then
 * re-read from the heightmap</b>, so a unit scattered onto a slope stands on it rather than inside
 * it or floating over it.
 */
final class EventSpawns {

    private EventSpawns() {}

    /** One rifleman of {@code faction}, scattered up to {@code scatter} blocks around {@code anchor}. */
    static AbstractUnit infantry(ServerLevel level, BlockPos anchor, TankSpawner.TankFaction faction, int scatter) {
        AbstractUnit unit = faction == TankSpawner.TankFaction.RU
                ? new RUunitEntity(ModEntities.RUUNIT.get(), level)
                : new USunitEntity(ModEntities.USUNIT.get(), level);
        unit.setRole(UnitRole.DEFAULT);

        int x = anchor.getX() + level.random.nextInt(scatter * 2 + 1) - scatter;
        int z = anchor.getZ() + level.random.nextInt(scatter * 2 + 1) - scatter;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        unit.setPos(x + 0.5, y, z + 0.5);
        unit.finalizeSpawn(level, level.getCurrentDifficultyAt(unit.blockPosition()), MobSpawnType.EVENT, null, null);
        level.addFreshEntity(unit);
        return unit;
    }

    /**
     * RU or US at random, restricted to a faction that actually has a usable vehicle pool, or null
     * when neither does. PMC is never a candidate: these events are world events, and a PMC crew
     * without an owner is friendly scenery rather than a fight.
     */
    @Nullable
    static TankSpawner.TankFaction pickVehicleFaction(ServerLevel level) {
        boolean ru = TankSpawner.hasSpawnableVehicle(level, TankSpawner.TankFaction.RU);
        boolean us = TankSpawner.hasSpawnableVehicle(level, TankSpawner.TankFaction.US);
        if (ru && us) return level.random.nextBoolean() ? TankSpawner.TankFaction.RU : TankSpawner.TankFaction.US;
        if (ru) return TankSpawner.TankFaction.RU;
        return us ? TankSpawner.TankFaction.US : null;
    }

    static TankSpawner.TankFaction opposite(TankSpawner.TankFaction faction) {
        return faction == TankSpawner.TankFaction.RU
                ? TankSpawner.TankFaction.US : TankSpawner.TankFaction.RU;
    }
}

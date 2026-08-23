package com.neoalive.tacz_sewv.procedural.events;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.utils.UnitRole;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import net.nekoyuni.SimpleEnemyMod.registry.ModEntities;

import com.neoalive.tacz_sewv.init.ModGameRules;
import com.neoalive.tacz_sewv.spawn.TankSpawner;

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
    @Nullable
    static AbstractUnit infantry(ServerLevel level, BlockPos anchor, TankSpawner.TankFaction faction, int scatter) {
        if (!TankSpawner.spawnsEnabled(level, faction)) return null;
        if (faction == TankSpawner.TankFaction.PMC
                && !level.getGameRules().getBoolean(ModGameRules.PMC_AMBIENT_SPAWNS)) {
            return null;
        }
        AbstractUnit unit;
        switch (faction) {
            case RU -> {
                unit = new RUunitEntity(ModEntities.RUUNIT.get(), level);
                unit.setRole(UnitRole.DEFAULT);
            }
            case US -> {
                unit = new USunitEntity(ModEntities.USUNIT.get(), level);
                unit.setRole(UnitRole.DEFAULT);
            }
            default -> {
                // Ownerless FRIENDLY_DEFAULT — same contract as Berezka PMC structure crews
                // (TankSpawner.createCrewUnit with a null ownerId).
                PmcUnitEntity pmc = new PmcUnitEntity(ModEntities.PMCUNIT.get(), level);
                pmc.setRole(UnitRole.FRIENDLY_DEFAULT);
                unit = pmc;
            }
        }

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
        return coinFlip(level,
                TankSpawner.hasSpawnableCombatVehicle(level, TankSpawner.TankFaction.RU),
                TankSpawner.hasSpawnableCombatVehicle(level, TankSpawner.TankFaction.US));
    }

    /**
     * Derelict-only: RU/US with a ground pool, plus ownerless PMC when ambient PMC spawns are on.
     * Uses {@link TankSpawner#hasSpawnableVehicle} (ground) because derelicts come from
     * {@link TankSpawner#spawnBareVehicle}, not the combat (ground|heli) mix.
     */
    @Nullable
    static TankSpawner.TankFaction pickDerelictFaction(ServerLevel level) {
        List<TankSpawner.TankFaction> candidates = new ArrayList<>(3);
        if (TankSpawner.hasSpawnableVehicle(level, TankSpawner.TankFaction.RU)) {
            candidates.add(TankSpawner.TankFaction.RU);
        }
        if (TankSpawner.hasSpawnableVehicle(level, TankSpawner.TankFaction.US)) {
            candidates.add(TankSpawner.TankFaction.US);
        }
        if (level.getGameRules().getBoolean(ModGameRules.PMC_AMBIENT_SPAWNS)
                && TankSpawner.hasSpawnableVehicle(level, TankSpawner.TankFaction.PMC)) {
            candidates.add(TankSpawner.TankFaction.PMC);
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(level.random.nextInt(candidates.size()));
    }

    /** RU or US with a non-empty plane pool — never PMC (overflights must spawn airborne). */
    @Nullable
    static TankSpawner.TankFaction pickPlaneFaction(ServerLevel level) {
        return coinFlip(level,
                TankSpawner.hasSpawnablePlane(level, TankSpawner.TankFaction.RU),
                TankSpawner.hasSpawnablePlane(level, TankSpawner.TankFaction.US));
    }

    @Nullable
    static TankSpawner.TankFaction pickAmbientFaction(ServerLevel level) {
        return coinFlip(level,
                TankSpawner.spawnsEnabled(level, TankSpawner.TankFaction.RU),
                TankSpawner.spawnsEnabled(level, TankSpawner.TankFaction.US));
    }

    /** RU if only it is eligible, US if only it is, a fair coin if both, null if neither. */
    @Nullable
    private static TankSpawner.TankFaction coinFlip(ServerLevel level, boolean ru, boolean us) {
        if (ru && us) return level.random.nextBoolean() ? TankSpawner.TankFaction.RU : TankSpawner.TankFaction.US;
        if (ru) return TankSpawner.TankFaction.RU;
        return us ? TankSpawner.TankFaction.US : null;
    }

    static TankSpawner.TankFaction opposite(TankSpawner.TankFaction faction) {
        return faction == TankSpawner.TankFaction.RU
                ? TankSpawner.TankFaction.US : TankSpawner.TankFaction.RU;
    }
}

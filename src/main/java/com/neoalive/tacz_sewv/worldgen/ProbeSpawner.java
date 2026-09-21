package com.neoalive.tacz_sewv.worldgen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.block.SpawnProbeBlockEntity;
import com.neoalive.tacz_sewv.block.SpawnProbeCategory;
import com.neoalive.tacz_sewv.block.SpawnProbeInfantryEntry;
import com.neoalive.tacz_sewv.spawn.TankSpawner;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/** Turns one consumed spawn probe's payload into entities. */
final class ProbeSpawner {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> WARNED = new HashSet<>();

    /** A probe's data, copied out before its block is removed. */
    record Payload(SpawnProbeCategory category, List<String> vehicles, boolean preCrewed,
                   List<SpawnProbeInfantryEntry> infantry) {
        static Payload of(SpawnProbeBlockEntity probe) {
            return new Payload(probe.getCategory(), new ArrayList<>(probe.getVehicleList()),
                    probe.isPreCrewedSpawn(), new ArrayList<>(probe.getInfantryList()));
        }
    }

    private ProbeSpawner() {}

    static void spawn(ServerLevel level, BlockPos pos, TankFaction faction, Payload payload) {
        // The RU/US gamerules gate probes exactly as they gate every other spawn path.
        if (faction != TankFaction.PMC && !TankSpawner.spawnsEnabled(level, faction)) return;
        if (payload.category() == SpawnProbeCategory.INFANTRY) {
            infantry(level, pos, payload.infantry());
        } else {
            vehicle(level, pos, faction, payload);
        }
    }

    /** One Chance roll per row: the whole row of Count units spawns, or none of it does. */
    private static void infantry(ServerLevel level, BlockPos pos, List<SpawnProbeInfantryEntry> rows) {
        for (SpawnProbeInfantryEntry row : rows) {
            if (level.random.nextInt(100) >= row.chance()) continue;
            EntityType<?> type = entityType(row.id());
            if (type == null) {
                warnOnce("Spawn probe row names unknown entity '" + row.id() + "'");
                continue;
            }
            for (int i = 0; i < row.count(); i++) {
                Entity entity = type.create(level);
                if (!(entity instanceof Mob mob)) {
                    warnOnce("Spawn probe row '" + row.id() + "' is not a mob");
                    break;
                }
                BlockPos at = TankSpawner.findClearSpawnAt(level, pos, type);
                if (at == null) {
                    mob.discard();
                    break;
                }
                mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.STRUCTURE, null, null);
                mob.setPersistenceRequired();
                level.addFreshEntityWithPassengers(mob);
            }
        }
    }

    private static void vehicle(ServerLevel level, BlockPos pos, TankFaction faction, Payload payload) {
        List<String> ids = payload.vehicles();
        if (ids.isEmpty()) return;
        String id = ids.get(level.random.nextInt(ids.size()));
        if (!supportedHull(level, id)) {
            warnOnce("Spawn probe vehicle '" + id + "' is unknown, not a vehicle, or a plane/ship (not supported)");
            return;
        }
        List<String> pool = List.of(id);
        VehicleEntity hull = payload.preCrewed()
                ? TankSpawner.spawnTankWithCrewFromPoolAt(level, pos, faction, null, id, pool)
                : TankSpawner.spawnBareVehicleFromPool(level, pos, pool, true);
        if (hull == null) LOGGER.debug("[tacz_sewv] spawn probe at {} found no room for {}", pos, id);
    }

    @Nullable
    private static EntityType<?> entityType(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        // The entity registry is defaulted: a bare getValue() on a typo would answer a pig.
        return rl != null && ForgeRegistries.ENTITY_TYPES.containsKey(rl) ? ForgeRegistries.ENTITY_TYPES.getValue(rl) : null;
    }

    /** Ground and helicopter hulls only. An entity that is created and never added needs no cleanup. */
    private static boolean supportedHull(ServerLevel level, String id) {
        EntityType<?> type = entityType(id);
        if (type == null || !(type.create(level) instanceof VehicleEntity hull)) return false;
        try {
            EngineType engine = hull.computed().getEngineType();
            return engine != EngineType.AIRCRAFT && engine != EngineType.SHIP;
        } catch (Exception e) {
            return false;
        }
    }

    private static void warnOnce(String message) {
        if (WARNED.add(message)) LOGGER.warn("[tacz_sewv] {}", message);
    }
}

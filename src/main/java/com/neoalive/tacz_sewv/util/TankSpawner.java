package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModItems;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.utils.UnitRole;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import net.nekoyuni.SimpleEnemyMod.registry.ModEntities;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TankSpawner {

    private TankSpawner() {}

    /** Which faction crews the vehicle; each has its own configurable vehicle pool. */
    public enum TankFaction {
        RU, US, PMC;

        public List<? extends String> vehiclePool() {
            return switch (this) {
                case RU -> SewvConfig.RU_VEHICLE_POOL.get();
                case US -> SewvConfig.US_VEHICLE_POOL.get();
                case PMC -> SewvConfig.PMC_VEHICLE_POOL.get();
            };
        }
    }

    /**
     * Spawns a faction vehicle picked at random from the faction's configured pool.
     * Equivalent to {@link #spawnTankWithCrew(ServerLevel, BlockPos, TankFaction, UUID, String)}
     * with no specific vehicle requested.
     */
    @Nullable
    public static VehicleEntity spawnTankWithCrew(ServerLevel level, BlockPos pos, TankFaction faction, @Nullable UUID ownerId) {
        return spawnTankWithCrew(level, pos, faction, ownerId, null);
    }

    /**
     * Spawns a faction vehicle with a full crew of the matching faction: one unit per
     * seat the vehicle exposes, mounted in seat order (seat 0 becomes the driver).
     * When {@code vehicleId} is non-null it must be one of the faction's configured
     * pool entries and that exact vehicle is used; when null, one is picked at random
     * from the pool. For PMC, {@code ownerId} (when non-null) makes the crew
     * commandable by that player. Returns the spawned vehicle, or null if it couldn't
     * be spawned (no space, the requested id isn't a valid pooled SW vehicle, the pool
     * is empty, or SW isn't loaded).
     */
    @Nullable
    public static VehicleEntity spawnTankWithCrew(ServerLevel level, BlockPos pos, TankFaction faction,
                                                  @Nullable UUID ownerId, @Nullable String vehicleId) {
        EntityType<?> tankType = selectVehicleType(faction.vehiclePool(), vehicleId, level.random);
        if (tankType == null) return null; // nothing valid configured/requested — bail safely

        if (!hasSpace(level, pos, tankType)) return null;

        Entity tankEntity = tankType.create(level);
        if (!(tankEntity instanceof VehicleEntity tank)) return null; // configured id isn't an SW vehicle

        tank.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        level.addFreshEntity(tank);

        // Freshly-created vehicles start with 0 energy and an empty inventory — an AI
        // driver can't refuel/rearm itself, so fully charge it and stock a creative
        // ammo box (rather than hardcoding per-vehicle ammo items) so it can move and fire.
        if (tank.hasEnergyStorage()) {
            tank.setEnergy(tank.getMaxEnergy());
        }
        if (tank.hasContainer() && tank.getContainerSize() > 0) {
            tank.setItem(0, new ItemStack(ModItems.CREATIVE_AMMO_BOX.get()));
        }

        // One unit per seat, mounted in join order: SW's VehicleEntity assigns
        // seats sequentially, so the first rider lands in seat 0 (driver) and
        // the rest man the remaining weapon/passenger stations.
        int seats = Math.max(1, tank.getMaxPassengers());
        for (int i = 0; i < seats; i++) {
            AbstractUnit crew = createCrewUnit(level, faction, ownerId);
            crew.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            crew.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
            level.addFreshEntity(crew);
            if (!crew.startRiding(tank)) {
                // Seat refused the rider (vehicle full despite getMaxPassengers, or
                // a mod cancelled the mount) — don't leave the unit standing around.
                crew.discard();
                break;
            }
        }

        // Helicopters spawn on the ground, so order the pilot (seat 0) to take off:
        // the crew climbs straight up to cruise altitude before transiting, rather
        // than skimming terrain toward its first objective. Every crew type now
        // implements IHelicopterPilot, so this drives RU/US autonomous crews the
        // same as owned PMC ones; ground vehicles ignore it (the goal only reads
        // the command while mounted in a helicopter).
        if (tank.getEngineInfo() instanceof EngineInfo.Helicopter
                && tank.getFirstPassenger() instanceof IHelicopterPilot pilot) {
            pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_TAKEOFF);
        }

        return tank;
    }

    private static AbstractUnit createCrewUnit(ServerLevel level, TankFaction faction, @Nullable UUID ownerId) {
        switch (faction) {
            case RU: {
                RUunitEntity unit = new RUunitEntity(ModEntities.RUUNIT.get(), level);
                unit.setRole(UnitRole.DEFAULT);
                return unit;
            }
            case US: {
                USunitEntity unit = new USunitEntity(ModEntities.USUNIT.get(), level);
                unit.setRole(UnitRole.DEFAULT);
                return unit;
            }
            default: {
                // FRIENDLY_DEFAULT mirrors SEM's plain PMC spawn egg; the owner makes
                // the crew respond to that player's SEM command menu.
                PmcUnitEntity unit = new PmcUnitEntity(ModEntities.PMCUNIT.get(), level);
                unit.setRole(UnitRole.FRIENDLY_DEFAULT);
                if (ownerId != null) unit.setOwner(ownerId);
                return unit;
            }
        }
    }

    // Resolve the vehicle to spawn: a specific pooled id when one is requested,
    // otherwise a random pick from the pool. A requested id that isn't actually in
    // the configured pool is rejected (returns null) — the command must only spawn
    // what the config allows.
    @Nullable
    private static EntityType<?> selectVehicleType(List<? extends String> pool, @Nullable String requestedId, RandomSource random) {
        if (requestedId == null) return pickVehicleType(pool, random);
        if (!pool.contains(requestedId)) return null; // not a configured pool entry
        ResourceLocation rl = ResourceLocation.tryParse(requestedId);
        if (rl == null || !ForgeRegistries.ENTITY_TYPES.containsKey(rl)) return null;
        return ForgeRegistries.ENTITY_TYPES.getValue(rl);
    }

    // Random pick among the pool entries that resolve to a real entity type.
    // containsKey() guard matters: the entity-type registry is defaulted, so a
    // bare getValue() on a typo'd id would silently return minecraft:pig.
    @Nullable
    private static EntityType<?> pickVehicleType(List<? extends String> pool, RandomSource random) {
        List<EntityType<?>> candidates = new ArrayList<>(pool.size());
        for (String id : pool) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null && ForgeRegistries.ENTITY_TYPES.containsKey(rl)) {
                candidates.add(ForgeRegistries.ENTITY_TYPES.getValue(rl));
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }

    public static boolean hasSpace(ServerLevel level, BlockPos pos, EntityType<?> type) {
        var aabb = type.getDimensions().makeBoundingBox(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return level.noCollision(aabb);
    }

    public static BlockPos adjustHeight(ServerLevel level, BlockPos pos) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), y, pos.getZ());
    }
}

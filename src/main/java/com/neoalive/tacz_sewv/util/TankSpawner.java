package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModItems;
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

        List<? extends String> vehiclePool() {
            return switch (this) {
                case RU -> SewvConfig.RU_VEHICLE_POOL.get();
                case US -> SewvConfig.US_VEHICLE_POOL.get();
                case PMC -> SewvConfig.PMC_VEHICLE_POOL.get();
            };
        }
    }

    /**
     * Spawns a faction vehicle (picked at random from the faction's configured pool)
     * with a driver of the matching faction already mounted in seat 0.
     * For PMC, {@code ownerId} (when non-null) makes the crew commandable by that player.
     * Returns the spawned vehicle, or null if it couldn't be spawned (no space,
     * no valid vehicle id in the pool, or SW not loaded).
     */
    @Nullable
    public static VehicleEntity spawnTankWithDriver(ServerLevel level, BlockPos pos, TankFaction faction, @Nullable UUID ownerId) {
        EntityType<?> tankType = pickVehicleType(faction.vehiclePool(), level.random);
        if (tankType == null) return null; // nothing valid configured — bail safely

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

        AbstractUnit driver = createDriver(level, faction, ownerId);
        driver.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        driver.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
        level.addFreshEntity(driver);

        // Put the driver straight into the tank (seat 0 → the drive AI takes over)
        driver.startRiding(tank);

        return tank;
    }

    private static AbstractUnit createDriver(ServerLevel level, TankFaction faction, @Nullable UUID ownerId) {
        switch (faction) {
            case RU: {
                RUunitEntity driver = new RUunitEntity(ModEntities.RUUNIT.get(), level);
                driver.setRole(UnitRole.DEFAULT);
                return driver;
            }
            case US: {
                USunitEntity driver = new USunitEntity(ModEntities.USUNIT.get(), level);
                driver.setRole(UnitRole.DEFAULT);
                return driver;
            }
            default: {
                // FRIENDLY_DEFAULT mirrors SEM's plain PMC spawn egg; the owner makes
                // the crew respond to that player's SEM command menu.
                PmcUnitEntity driver = new PmcUnitEntity(ModEntities.PMCUNIT.get(), level);
                driver.setRole(UnitRole.FRIENDLY_DEFAULT);
                if (ownerId != null) driver.setOwner(ownerId);
                return driver;
            }
        }
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

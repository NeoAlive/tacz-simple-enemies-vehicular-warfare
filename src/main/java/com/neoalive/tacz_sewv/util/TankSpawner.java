package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.utils.UnitRole;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import net.nekoyuni.SimpleEnemyMod.registry.ModEntities;

import javax.annotation.Nullable;

public final class TankSpawner {

    private TankSpawner() {}

    /**
     * Spawns a faction tank with a driver of the matching faction already mounted in seat 0.
     * Returns the spawned tank, or null if it couldn't be spawned (no space, or SW not loaded).
     */
    @Nullable
    public static VehicleEntity spawnTankWithDriver(ServerLevel level, BlockPos pos, boolean isRu) {
        ResourceLocation tankId = isRu
                ? new ResourceLocation("superbwarfare", "t_90a")
                : new ResourceLocation("superbwarfare", "m_1a_2");

        EntityType<?> tankType = ForgeRegistries.ENTITY_TYPES.getValue(tankId);
        if (tankType == null) return null; // SW not loaded or ID wrong  bail safely

        if (!hasSpace(level, pos, tankType)) return null;

        Entity tankEntity = tankType.create(level);
        if (!(tankEntity instanceof VehicleEntity tank)) return null;

        tank.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        level.addFreshEntity(tank);

        // Freshly-created vehicles start with 0 energy and an empty inventory
        // driver can't refuel/rearm itself, so fully charge it and stock a creative
        // ammo box (rather than hardcoding per-vehicle ammo items) so it can move and fire.
        if (tank.hasEnergyStorage()) {
            tank.setEnergy(tank.getMaxEnergy());
        }
        if (tank.hasContainer() && tank.getContainerSize() > 0) {
            tank.setItem(0, new ItemStack(ModItems.CREATIVE_AMMO_BOX.get()));
        }

        AbstractUnit driver = isRu
                ? new RUunitEntity(ModEntities.RUUNIT.get(), level)
                : new USunitEntity(ModEntities.USUNIT.get(), level);
        driver.setRole(UnitRole.DEFAULT);
        driver.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        driver.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
        level.addFreshEntity(driver);

        // Put the driver straight into the tank (seat 0 → the drive AI takes over)
        driver.startRiding(tank);

        return tank;
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

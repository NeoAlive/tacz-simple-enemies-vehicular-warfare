package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.utils.UnitRole;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import net.nekoyuni.SimpleEnemyMod.registry.ModEntities;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Target the concrete event class
@Mixin(targets = "net.nekoyuni.SimpleEnemyMod.procedural.events.type.CombatEvent")
public abstract class MixinCombatEvent {

    @Inject(method = "execute", at = @At("TAIL"), remap = false)
    private void tacz_sewv$maybeSpawnTanks(
            ServerLevel level, ServerPlayer player, BlockPos centerPos,
            CallbackInfoReturnable<Boolean> cir) {

        // Config gate
        if (!SewvConfig.TANKS_IN_EVENTS.get()) return;

        // Only proceed if the event actually succeeded (returned true)
        if (cir.getReturnValue() == null || !cir.getReturnValue()) return;

        int separation = 24;

        // Roll for RU tank (extremely low chance)
        if (level.random.nextDouble() < SewvConfig.TANK_SPAWN_CHANCE.get()) {
            BlockPos posRu = tacz_sewv$adjustHeight(level, centerPos.offset(separation, 0, 0));
            tacz_sewv$spawnTankWithDriver(level, posRu, true);
        }

        // Roll for US tank (independent roll)
        if (level.random.nextDouble() < SewvConfig.TANK_SPAWN_CHANCE.get()) {
            BlockPos posUs = tacz_sewv$adjustHeight(level, centerPos.offset(-separation, 0, 0));
            tacz_sewv$spawnTankWithDriver(level, posUs, false);
        }
    }

    private void tacz_sewv$spawnTankWithDriver(ServerLevel level, BlockPos pos, boolean isRu) {
        // Pick the faction's tank
        ResourceLocation tankId = isRu
                ? new ResourceLocation("superbwarfare", "t_90a")
                : new ResourceLocation("superbwarfare", "m_1a_2");

        EntityType<?> tankType = ForgeRegistries.ENTITY_TYPES.getValue(tankId);
        if (tankType == null) return; // SW not loaded or ID wrong — bail safely

        // Space check — will the tank physically fit here?
        // (Using a simple bounding-box check; ContainerBlock.canOpen equivalent)
        if (!tacz_sewv$hasSpace(level, pos, tankType)) return;

        // Spawn the tank
        Entity tankEntity = tankType.create(level);
        if (!(tankEntity instanceof VehicleEntity tank)) return;

        tank.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        level.addFreshEntity(tank);

        // Spawn ONE driver of the matching faction
        AbstractUnit driver;
        if (isRu) {
            driver = new RUunitEntity(ModEntities.RUUNIT.get(), level);
        } else {
            driver = new USunitEntity(ModEntities.USUNIT.get(), level);
        }
        driver.setRole(UnitRole.DEFAULT);
        driver.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        driver.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
        level.addFreshEntity(driver);

        // Put the driver straight into the tank (seat 0 → your drive AI takes over)
        driver.startRiding(tank);
    }

    private boolean tacz_sewv$hasSpace(ServerLevel level, BlockPos pos, EntityType<?> type) {
        // Simple check: is the vehicle's bounding box area free of solid blocks?
        var aabb = type.getDimensions().makeBoundingBox(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return level.noCollision(aabb);
    }

    private BlockPos tacz_sewv$adjustHeight(ServerLevel level, BlockPos pos) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), y, pos.getZ());
    }
}
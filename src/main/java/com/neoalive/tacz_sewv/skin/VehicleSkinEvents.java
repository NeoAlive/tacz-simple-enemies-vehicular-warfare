package com.neoalive.tacz_sewv.skin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;

/**
 * Field-capture skin roll + tracking sync. Command/event crewed spawns paint in
 * {@link com.neoalive.tacz_sewv.spawn.TankSpawner}; engineer repair apply lives in {@code RepairGoal}.
 * Players pick skins through the spray GUI only.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class VehicleSkinEvents {

    private VehicleSkinEvents() {
    }

    /**
     * Abandoned/empty hull boarded in the field — chance roll only. Spawned crews are painted
     * after mount in TankSpawner (always), so a miss here is overwritten for those paths.
     */
    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!event.isMounting()) return;
        if (!(event.getEntityMounting() instanceof AbstractUnit unit)) return;
        if (!(event.getEntityBeingMounted() instanceof VehicleEntity vehicle)) return;
        // First rider only — Forge fires after the rider is already in passengers.
        if (vehicle.getPassengers().size() > 1) return;

        CrewFacts.Faction faction = CrewFacts.factionOfCrew(unit);
        if (faction == null) return;

        // Already painted — still try to stamp a missing dogTag on PMC captures.
        if (VehicleSkinSupport.get(vehicle) != null) {
            if (faction == CrewFacts.Faction.PMC && unit instanceof PmcUnitEntity pmc) {
                PmcVehicleLogoSupport.applyIfPmcCaptured(vehicle, pmc.getOwnerUUID());
            }
            return;
        }

        double chance = SewvConfig.VEHICLE_SKIN_MOUNT_CHANCE.get();
        if (chance <= 0.0 || unit.getRandom().nextDouble() >= chance) return;

        VehicleSkinSupport.apply(vehicle, faction);
        if (faction == CrewFacts.Faction.PMC && unit instanceof PmcUnitEntity pmc) {
            PmcVehicleLogoSupport.applyIfPmcCaptured(vehicle, pmc.getOwnerUUID());
        }
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Entity target = event.getTarget();
        if (!(target instanceof VehicleEntity vehicle)) return;
        if (VehicleSkinSupport.get(vehicle) == null) return;
        VehicleSkinSupport.syncTo(player, vehicle);
        PmcVehicleLogoSupport.syncTo(player, vehicle);
    }
}

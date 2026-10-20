package com.neoalive.tacz_sewv.skin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;

/**
 * Field-capture skin roll + tracking sync. Command/event crewed spawns paint in
 * {@link com.neoalive.tacz_sewv.spawn.TankSpawner}; engineer repair apply lives in {@code RepairGoal}.
 * Players pick skins through the spray GUI only.
 *
 * <p>DogTag logos follow live crew ownership independently of the skin chance roll.
 */
@EventBusSubscriber(modid = TaczSewv.MODID)
public final class VehicleSkinEvents {

    private VehicleSkinEvents() {
    }

    /**
     * Abandoned/empty hull boarded in the field — chance roll only. Spawned crews are painted
     * after mount in TankSpawner (always), so a miss here is overwritten for those paths.
     * Logos always re-evaluate from unanimous crew ownership.
     */
    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!event.isMounting()) return;
        if (!(event.getEntityMounting() instanceof AbstractUnit unit)) return;
        if (!(event.getEntityBeingMounted() instanceof VehicleEntity vehicle)) return;
        // First rider only for the skin chance — Forge fires after the rider is already in passengers.
        boolean firstRider = vehicle.getPassengers().size() <= 1;

        if (firstRider) {
            CrewFacts.Faction faction = CrewFacts.factionOfCrew(unit);
            if (faction != null && VehicleSkinSupport.get(vehicle) == null) {
                double chance = SewvConfig.VEHICLE_SKIN_MOUNT_CHANCE.get();
                if (chance > 0.0 && unit.getRandom().nextDouble() < chance) {
                    VehicleSkinSupport.apply(vehicle, faction);
                    CamoSupport.inheritHull(vehicle, unit);
                }
            }
        }

        // Logos track ownership, not the sticky skin roll — any seat change can flip unanimous faction.
        PmcVehicleLogoSupport.applyFromOwnership(vehicle);
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Entity target = event.getTarget();
        if (target instanceof AbstractUnit) {
            CamoSupport.syncTo(player, target);
            return;
        }
        if (!(target instanceof VehicleEntity vehicle)) return;
        CamoSupport.syncTo(player, vehicle);
        if (VehicleSkinSupport.get(vehicle) != null) {
            VehicleSkinSupport.syncTo(player, vehicle);
        }
        PmcVehicleLogoSupport.syncTo(player, vehicle);
    }
}

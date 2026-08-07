package com.neoalive.tacz_sewv.skin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.item.gun.special.RepairToolItem;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;

/**
 * Field-capture skin roll + tracking sync. Command/event crewed spawns paint in
 * {@link com.neoalive.tacz_sewv.spawn.TankSpawner}; engineer repair apply lives in {@code RepairGoal};
 * manual cycle is sneak-right-click with the repair tool (client packet).
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
        // First rider only: still empty when this fires from startRiding.
        if (!vehicle.getPassengers().isEmpty()) return;
        // Already painted (e.g. spawn path applied before a later remount) — leave it.
        if (VehicleSkinSupport.get(vehicle) != null) return;

        double chance = SewvConfig.VEHICLE_SKIN_MOUNT_CHANCE.get();
        if (chance <= 0.0 || unit.getRandom().nextDouble() >= chance) return;

        CrewFacts.Faction faction = CrewFacts.factionOfCrew(unit);
        VehicleSkinSupport.apply(vehicle, faction);
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        Entity target = event.getTarget();
        if (!(target instanceof VehicleEntity vehicle)) return;
        if (VehicleSkinSupport.get(vehicle) == null) return;
        VehicleSkinSupport.syncTo(player, vehicle);
    }

    /** Keep sneak+repair-tool right-click from mounting on the server; the client packet applies the skin. */
    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        // Client must NOT cancel here — that would eat the client subscriber that sends the cycle packet
        // (Forge skips later listeners once an event is canceled).
        if (event.getLevel().isClientSide()) return;
        if (!(event.getTarget() instanceof VehicleEntity)) return;
        Player player = event.getEntity();
        if (!player.isShiftKeyDown()) return;
        if (!isRepairTool(event.getItemStack()) && !isRepairTool(player.getOffhandItem())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static boolean isRepairTool(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof RepairToolItem;
    }
}

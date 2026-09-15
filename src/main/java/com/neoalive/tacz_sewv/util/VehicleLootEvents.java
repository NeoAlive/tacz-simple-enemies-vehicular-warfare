package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Applies EngineType inventory loot when an NPC hull unlocks (enemy crew gone).
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class VehicleLootEvents {

    private VehicleLootEvents() {
    }

    @SubscribeEvent
    public static void onDismount(EntityMountEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.isMounting()) return;
        if (!(event.getEntityBeingMounted() instanceof VehicleEntity hull)) return;
        // Defer one tick so the dismounting passenger is already off the list.
        if (hull.level().getServer() != null) {
            hull.level().getServer().execute(() -> applySafe(hull));
        }
    }

    /**
     * Player opens / boards an unlocked pending hull — fill before SBW opens the container.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getTarget() instanceof VehicleEntity hull)) return;
        applySafe(hull);
    }

    private static void applySafe(VehicleEntity hull) {
        try {
            if (VehicleEngineLoot.isLockedByEnemyCrew(hull)) return;
            VehicleEngineLoot.tryApplyOnUnlock(hull);
        } catch (Throwable t) {
            // Same hot-recompile pitfall as ConfigUI — don't crash the server tick.
            TaczSewv.LOGGER.error("[sewv] Vehicle engine loot failed", t);
        }
    }
}

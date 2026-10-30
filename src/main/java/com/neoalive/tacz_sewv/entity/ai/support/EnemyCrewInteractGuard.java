package com.neoalive.tacz_sewv.entity.ai.support;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.compat.lv.LvCompat;
import com.neoalive.tacz_sewv.crew.VehicleAccess.Verdict;
import com.neoalive.tacz_sewv.crew.VehicleAccessLive;
import com.neoalive.tacz_sewv.mixin.MixinVehicleInteractLock;
import com.neoalive.tacz_sewv.util.VehicleEngineLoot;

/**
 * Blocks a refused hull click at the Forge event bus — the seam addons that short-circuit
 * {@code VehicleEntity.interact} (FCP shift-open hold, emplacement hand-load, leg rotate) never
 * reach {@link MixinVehicleInteractLock}. Same {@link VehicleAccessLive} decision as the mixin.
 *
 * <p>{@link #hasEnemyCrew} stays the loot gate: an alliance that lets a player click does not
 * fill the container while the RU/US crew is still aboard.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class EnemyCrewInteractGuard {

    private EnemyCrewInteractGuard() {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        handle(event, event.getTarget());
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        handle(event, event.getTarget());
    }

    private static void handle(PlayerInteractEvent event, Entity target) {
        if (!(target instanceof VehicleEntity) && !LvCompat.isLvHull(target)) return;
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        Verdict verdict = VehicleAccessLive.verdict(player, target);
        if (verdict != Verdict.ALLOW) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            VehicleAccessLive.tell(player, verdict);
            return;
        }
        if (target instanceof VehicleEntity hull) VehicleEngineLoot.tryApplyOnUnlock(hull);
    }

    /** Any RU/US unit aboard. Takes any hull — SBW or Limitless Vehicles — since only passengers are read. */
    public static boolean hasEnemyCrew(Entity hull) {
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof RUunitEntity || passenger instanceof USunitEntity) {
                return true;
            }
        }
        return false;
    }
}

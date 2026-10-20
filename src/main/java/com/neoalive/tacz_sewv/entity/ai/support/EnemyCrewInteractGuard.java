package com.neoalive.tacz_sewv.entity.ai.support;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.compat.lv.LvCompat;
import com.neoalive.tacz_sewv.mixin.MixinVehicleInteractLock;
import com.neoalive.tacz_sewv.util.VehicleEngineLoot;

/**
 * Blocks player interaction with RU/US-crewed hulls at the Forge event bus — the seam addons
 * that short-circuit {@code VehicleEntity.interact} (FCP shift-open hold, emplacement hand-load,
 * leg rotate) never reach {@link MixinVehicleInteractLock}.
 *
 * <p>Same passenger test as the mixin; both stay. Also runs unlock loot when the hull is clear.
 */
@EventBusSubscriber(modid = TaczSewv.MODID)
public final class EnemyCrewInteractGuard {

    private EnemyCrewInteractGuard() {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        handle(event, event.getTarget(), result -> {
            event.setCanceled(true);
            event.setCancellationResult(result);
        });
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        handle(event, event.getTarget(), result -> {
            event.setCanceled(true);
            event.setCancellationResult(result);
        });
    }

    // NeoForge port: only the concrete interact events are cancellable, so each passes its own cancel.
    private static void handle(PlayerInteractEvent event, Entity target,
                               java.util.function.Consumer<InteractionResult> cancel) {
        if (!(target instanceof VehicleEntity) && !LvCompat.isLvHull(target)) return;
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        if (hasEnemyCrew(target)) {
            cancel.accept(InteractionResult.FAIL);
            player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.interact.enemy_crew")
                            .withStyle(ChatFormatting.GRAY), true);
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

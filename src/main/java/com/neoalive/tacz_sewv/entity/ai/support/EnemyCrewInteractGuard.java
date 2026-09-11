package com.neoalive.tacz_sewv.entity.ai.support;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.mixin.MixinVehicleInteractLock;
import com.neoalive.tacz_sewv.util.VehicleEngineLoot;

/**
 * Blocks player interaction with RU/US-crewed hulls at the Forge event bus — the seam addons
 * that short-circuit {@code VehicleEntity.interact} (FCP shift-open hold, emplacement hand-load,
 * leg rotate) never reach {@link MixinVehicleInteractLock}.
 *
 * <p>Same passenger test as the mixin; both stay. Also runs unlock loot when the hull is clear.
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
        if (!(target instanceof VehicleEntity hull)) return;
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        if (hasEnemyCrew(hull)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.interact.enemy_crew")
                            .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        VehicleEngineLoot.tryApplyOnUnlock(hull);
    }

    public static boolean hasEnemyCrew(VehicleEntity hull) {
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof RUunitEntity || passenger instanceof USunitEntity) {
                return true;
            }
        }
        return false;
    }
}

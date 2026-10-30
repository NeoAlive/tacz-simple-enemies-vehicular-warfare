package com.neoalive.tacz_sewv.crew;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.compat.lv.LvCompat;
import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;

/**
 * Player clicks on someone else's PMC, and mounts onto a hull the access rules reject.
 * Vehicle right-clicks themselves stay on {@link com.neoalive.tacz_sewv.entity.ai.support.EnemyCrewInteractGuard}
 * and {@link com.neoalive.tacz_sewv.mixin.MixinVehicleInteractLock}, which call the same predicate.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class VehicleAccessEvents {

    private VehicleAccessEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        refusePmc(event, event.getTarget());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        refusePmc(event, event.getTarget());
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        // Server only. A client cancel races the riding sync of a mount the server already allowed.
        if (event.getLevel().isClientSide() || !event.isMounting()) return;
        Entity hull = event.getEntityBeingMounted();
        if (!(hull instanceof VehicleEntity) && !LvCompat.isLvHull(hull)) return;
        Entity rider = event.getEntityMounting();
        if (VehicleAccessLive.deniesMount(rider, hull)) {
            event.setCanceled(true);
            if (rider instanceof Player player) {
                VehicleAccessLive.tell(player, VehicleAccessLive.verdict(player, hull));
            }
            return;
        }
        if (!event.getLevel().isClientSide()) VehicleAccessLive.stampOnMount(hull, rider);
    }

    /** Ownerless units stay recruitable. Everyone else needs {@link PmcOwnerSupport#isOwner}. */
    private static void refusePmc(PlayerInteractEvent event, Entity target) {
        if (!(target instanceof PmcUnitEntity pmc)) return;
        if (pmc.getOwnerUUID() == null && PmcOwnerSupport.ownerTeamOf(pmc) == null) return;
        Player player = event.getEntity();
        if (PmcOwnerSupport.isOwner(player, pmc)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        if (!player.level().isClientSide()) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("message.tacz_sewv.interact.not_owner")
                            .withStyle(net.minecraft.ChatFormatting.GRAY), true);
        }
    }
}

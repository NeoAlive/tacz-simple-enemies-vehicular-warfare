package com.neoalive.tacz_sewv.notify;

import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Final PMC death toast. Runs LOWEST so {@code PmcDownedSupport}'s cancelled first hit is skipped
 * and only a real (uncancelled) death notifies.
 */
@EventBusSubscriber(modid = TaczSewv.MODID)
public final class HudNotifyEvents {

    private HudNotifyEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof PmcUnitEntity pmc)) return;
        if (entity.level().isClientSide()) return;
        HudNotify.pmcKilled(pmc, event.getSource());
    }
}

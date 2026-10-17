package com.neoalive.tacz_sewv.notify;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Final PMC death toast. Runs LOWEST so {@code PmcDownedSupport}'s cancelled first hit is skipped
 * and only a real (uncancelled) death notifies.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
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

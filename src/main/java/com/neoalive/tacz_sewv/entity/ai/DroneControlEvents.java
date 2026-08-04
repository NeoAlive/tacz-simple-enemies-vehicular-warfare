package com.neoalive.tacz_sewv.entity.ai;

import com.neoalive.tacz_sewv.TaczSewv;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/**
 * Zero-latency unlock when a drone-locked engineer takes damage.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class DroneControlEvents {

    private DroneControlEvents() {}

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof AbstractUnit unit)) return;
        if (!DroneControl.isEngineer(unit) || !DroneControl.isLocked(unit)) return;
        if (event.getAmount() <= 0.0f) return;
        DroneOperatorGoal.unlockEngineer(unit);
    }
}

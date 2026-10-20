package com.neoalive.tacz_sewv.entity.ai.support;

import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.entity.ai.goal.DroneOperatorGoal;

/**
 * Engineer lock/hurt unlock, and death → crash-dive owned drones.
 */
@EventBusSubscriber(modid = TaczSewv.MODID)
public final class DroneControlEvents {

    private DroneControlEvents() {}

    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof AbstractUnit unit)) return;
        if (!DroneControl.isEngineer(unit) || !DroneControl.isLocked(unit)) return;
        if (event.getAmount() <= 0.0f) return;
        DroneOperatorGoal.unlockEngineer(unit);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        if (!(entity instanceof AbstractUnit unit) || !DroneControl.isEngineer(unit)) return;
        DroneSupport.onOperatorKilled(unit);
    }
}

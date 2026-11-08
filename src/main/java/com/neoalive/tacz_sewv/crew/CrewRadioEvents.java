package com.neoalive.tacz_sewv.crew;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Crew radio triggers that live on Forge events: a crewman dying in his seat, a crew killing or
 * hitting something that is not a hull (hulls are handled in {@code MixinVehicleVoicelines}), and
 * the last crewman leaving a hull. Every handler bails on the cheapest test first; the hurt and
 * death events fire for every living thing in the world.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class CrewRadioEvents {

    private CrewRadioEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || !CrewRadio.enabled()) return;
        if (victim instanceof AbstractUnit unit && unit.getVehicle() instanceof VehicleEntity hull) {
            CrewRadio.onCrewDeath(hull, unit);
            return;
        }
        // A rider's death is the hull's business: the hull kill is credited on destroy.
        if (victim.getVehicle() instanceof VehicleEntity) return;
        VehicleEntity killer = CrewRadio.crewedHullOf(event.getSource().getEntity());
        if (killer != null) CrewRadio.onKill(killer);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onHurt(LivingHurtEvent event) {
        Entity attacker = event.getSource().getEntity();
        if (!(attacker instanceof AbstractUnit) && !(attacker instanceof VehicleEntity)) return;
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || !CrewRadio.enabled()) return;
        if (victim.getVehicle() instanceof VehicleEntity) return;
        // A lethal hit gets the kill line instead.
        if (event.getAmount() >= victim.getHealth()) return;
        VehicleEntity shooter = CrewRadio.crewedHullOf(attacker);
        if (shooter != null) CrewRadio.onHitLanded(shooter);
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (!(event.getEntityBeingMounted() instanceof VehicleEntity hull)) return;
        if (!(event.getEntityMounting() instanceof AbstractUnit unit) || hull.level().isClientSide()) return;
        if (!CrewRadio.enabled()) return;
        // Mounting is announced by the crew goals' start(); here only the hull emptying matters.
        if (event.isMounting()) return;
        for (Entity passenger : hull.getPassengers()) {
            if (passenger != unit && passenger instanceof AbstractUnit) return;
        }
        CrewRadio.onHullEmptied(hull);
    }
}

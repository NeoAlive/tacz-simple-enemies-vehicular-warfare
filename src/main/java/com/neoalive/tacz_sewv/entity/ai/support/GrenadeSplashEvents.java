package com.neoalive.tacz_sewv.entity.ai.support;

import com.atsuishio.superbwarfare.entity.projectile.HandGrenadeEntity;
import com.atsuishio.superbwarfare.entity.projectile.RgoGrenadeEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Grenades thrown by a unit never hurt that unit's allies (or the thrower). SBW's explosion damages
 * everything in radius with no faction filter, so the veto lives at the damage seam — it covers the
 * fuse, bounces and allies walking in, which no pre-throw check can. Cancelling {@code hurt} before it
 * runs also means no {@code setLastHurtByMob}, so no retaliation cascade. Enemies in the same blast
 * still take full damage.
 *
 * <p>Both the blast and the 1-HP impact hit carry the throwing unit as {@code getEntity()} and the
 * grenade as {@code getDirectEntity()}. Player-thrown grenades are untouched.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class GrenadeSplashEvents {

    private GrenadeSplashEvents() {}

    @SubscribeEvent
    public static void onAttack(LivingAttackEvent event) {
        DamageSource src = event.getSource();
        Entity direct = src.getDirectEntity();
        if (!(direct instanceof HandGrenadeEntity || direct instanceof RgoGrenadeEntity)) return;
        if (!(src.getEntity() instanceof AbstractUnit thrower)) return;

        LivingEntity victim = event.getEntity();
        if (VehicleTargeting.isFriendly(thrower, victim)
                || VehicleTargeting.isSplashProtected(thrower, victim)) {
            event.setCanceled(true);
        }
    }
}

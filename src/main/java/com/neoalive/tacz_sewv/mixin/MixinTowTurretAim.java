package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.TowEntity;
import com.atsuishio.superbwarfare.tools.EntityFindUtil;
import com.neoalive.tacz_sewv.entity.ai.TowSupport;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Points an AI-crewed TOW straight at its target instead of lofting it.
 *
 * <p>{@code VehicleEntity.tick} hands an AI-crewed turret to {@code turretAutoAimFromUuid},
 * which solves a BALLISTIC firing solution from the weapon's {@code GunProp.GRAVITY}. For
 * the TOW that value is the schema default 0.05 — {@code tow.json} never sets Gravity —
 * while its wire-guided missile has a real gravity of 0 and rides the barrel's beam. The
 * solver's loft is therefore pure error, and the beam-riding missile follows it over the
 * target's head on every shot. {@link TowSupport#aimVector} has the full reasoning.
 *
 * <p>Hooked here rather than aimed from the goal because this is where SBW aims: doing it
 * from a goal would leave two writers racing over the barrel within the same tick, decided
 * by entity iteration order. Cancelling SBW's aim and replacing it is deterministic.
 *
 * <p>Only AI crews are touched. A Player in the seat never reaches this method at all —
 * {@code tick} routes players to {@code adjustTurretAngle} — but the {@link AbstractUnit}
 * check keeps this off any other mod's mobs, whose launchers we know nothing about.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinTowTurretAim {

    @Inject(
            method = "turretAutoAimFromUuid(Ljava/lang/String;Lnet/minecraft/world/entity/LivingEntity;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$aimTowFlat(
            String targetUuid, LivingEntity controller, CallbackInfo ci) {

        if (!((Object) this instanceof TowEntity tow)) return;
        if (!(controller instanceof AbstractUnit)) return;

        Entity target = EntityFindUtil.findEntity(tow.level(), targetUuid);
        // Nothing to aim at. Fall through rather than cancel: SBW's own path bails on the
        // same condition, and leaving it in charge keeps that behaviour in one place.
        if (target == null) return;

        // A target riding a hull means the hull, the way SBW resolves it — otherwise the
        // barrel tracks a crewman's hitbox inside the tank we actually want to kill.
        if (target.getVehicle() != null) target = target.getVehicle();

        tow.turretAutoAimFromVector(TowSupport.aimVector(tow, controller, target));
        ci.cancel();
    }
}

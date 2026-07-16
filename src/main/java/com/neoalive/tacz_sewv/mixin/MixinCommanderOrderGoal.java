package com.neoalive.tacz_sewv.mixin;

import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.entity.ai.VehicleFormation;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.CommanderOrderGoal;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Walks the loose infantry of a VEHICLE formation to the same slots the hulls drive to.
 *
 * <p>They share one numbering with the hulls, so they have to share one geometry: left alone,
 * SEM sends them to its own 2.0/2.5-block slots laid out on the commander's live body yaw, which
 * land inside the vehicle-spaced ones — a rifleman standing where a tank is about to park.
 *
 * <p>Both injections no-op unless the unit is actually in one of our formations, so a plain SEM
 * infantry wedge is untouched.
 */
@Mixin(CommanderOrderGoal.class)
public abstract class MixinCommanderOrderGoal {

    @Shadow(remap = false)
    @Final
    private PathfinderMob mob;

    @Inject(method = "calculateClassicFormationTarget", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$vehicleSpacedSlot(LivingEntity owner, OrderType order, int index,
                                             CallbackInfoReturnable<Vec3> cir) {
        Direction axis = tacz_sewv$formationAxis();
        if (axis == null || index < 0) return;
        cir.setReturnValue(VehicleFormation.slotCenter(owner.position(), axis, order, index));
    }

    /**
     * SEM's COLUMN never calls the formation math for index > 0 — it files each man 1.5 blocks
     * behind whoever holds index-1. In a vehicle column index-1 is a TANK, and 1.5 blocks behind
     * a moving hull is under its tracks. Answering "no predecessor" drops performFollowOwner back
     * onto calculateClassicFormationTarget, which the injection above owns.
     *
     * <p>Side benefit: it also skips a 20-block entity scan per tick per man.
     */
    @Inject(method = "findPredecessor", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$noPredecessorInVehicleColumn(LivingEntity owner, int index,
                                                        CallbackInfoReturnable<PmcUnitEntity> cir) {
        if (tacz_sewv$formationAxis() != null) cir.setReturnValue(null);
    }

    /** The axis this unit is formed on, or null when it is not in one of our formations. */
    @Unique
    private Direction tacz_sewv$formationAxis() {
        if (!(this.mob instanceof PmcUnitEntity pmc)) return null;
        OrderType order = pmc.getOrder();
        if (order != OrderType.FORM_WEDGE && order != OrderType.FORM_COLUMN) return null;
        return ((IFormationMember) pmc).sewv$getFormationDirection();
    }
}

package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.ICaptureMedic;
import com.neoalive.tacz_sewv.bridge.IMedicCaptured;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.MedicCaptureSupport;

/**
 * The dispatched half of medic capture: a PMC ordered via the TDT "Capture Medic" button (see
 * {@code ICaptureMedic}, {@code PacketCaptureMedic}) walks to the nearest medic — captured or
 * still alive and running — subdues it if it is not captured yet, then auto-converts it using its
 * owner's currency. Movement uses the same speed boost engineers get chasing a tank
 * ({@code SewvConfig.ENGINEER_REPAIR_SPEED_BOOST}), since a foot unit chasing a moving target has
 * the identical "must not be permanently outrun" problem.
 *
 * <p><b>Subduing bypasses normal targeting on purpose.</b> {@code VehicleTargeting.isMedic} units
 * are never valid {@code setTarget} candidates for any AI — the Geneva-convention rule enforced in
 * {@code MixinAbstractUnit} — so there is no way to "aim a gun" at a medic through the ordinary
 * combat pipeline, and there should not be: that veto exists to stop a squad opening fire on one by
 * accident. An explicit player order is a different thing entirely, so this goal calls
 * {@code LivingEntity.hurt} directly once in melee range — one lethal strike, exactly the same
 * {@code LivingDeathEvent} → {@code MedicCaptureSupport.onDeath} path a player's own kill goes
 * through — rather than ever touching {@code setTarget}. Nothing else in the game gets this
 * exception; only this goal, only under this specific order.
 *
 * <p><b>Player-dispatched, not autonomous</b>: unlike an RU/US engineer, a PMC never decides on its
 * own to go do this — {@code canUse()} hard-gates on the order flag, matching this codebase's
 * general rule that PMC behaviour is commanded, not spontaneous. One order clears itself once the
 * goal finishes (captures/converts, or fails to find or reach a medic) or is interrupted by a real
 * combat target — it is a one-shot dispatch, not a standing stance.
 */
public class PmcCaptureMedicGoal extends Goal {

    private static final double CHASE_SPEED = 1.0;
    /** Close enough to interact with an already-captured medic. */
    private static final double INTERACT_DISTANCE_SQ = 9.0;
    /** Close enough to deliver a capture strike on a still-fleeing medic. */
    private static final double MELEE_RANGE_SQ = 4.0;
    /** Retry interval between strike attempts — normally only one strike is ever needed. */
    private static final int STRIKE_RETRY_TICKS = 10;
    /** Goal ticks before the unit gives up chasing an unreachable/evading medic. */
    private static final int MAX_APPROACH_TICKS = 600;

    /** Same boost engineers get chasing a tank — fixed id so a restart never stacks a second copy. */
    private static final UUID SPEED_BOOST_ID =
            UUID.fromString("d3c1a9b2-8f4e-4a7a-9e0a-6b6a1c9d2f30");

    private final PmcUnitEntity unit;
    private AbstractUnit targetMedic;
    private int approachTicks;
    private int strikeCooldown;

    public PmcCaptureMedicGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (!SewvConfig.MEDIC_CAPTURE_ENABLED.get()) return false;
        if (!(this.unit instanceof ICaptureMedic order) || !order.tacz_sewv$isCaptureMedicOrdered()) return false;
        if (this.unit.isPassenger() || this.unit.getTarget() != null) return false;

        this.targetMedic = findNearestMedic();
        if (this.targetMedic == null) {
            // Nothing to chase right now — drop the order rather than leaving it silently armed
            // forever waiting for a medic that may never appear again.
            order.tacz_sewv$setCaptureMedicOrdered(false);
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(this.unit instanceof ICaptureMedic order) || !order.tacz_sewv$isCaptureMedicOrdered()) return false;
        if (this.unit.isPassenger() || this.unit.getTarget() != null) return false;
        if (this.targetMedic == null || !this.targetMedic.isAlive()) return false;
        return this.approachTicks < MAX_APPROACH_TICKS;
    }

    @Override
    public void start() {
        this.approachTicks = 0;
        this.strikeCooldown = 0;
        this.unit.getNavigation().moveTo(this.targetMedic, CHASE_SPEED);
        applySpeedBoost();
    }

    @Override
    public void stop() {
        this.unit.getNavigation().stop();
        clearSpeedBoost();
        if (this.unit instanceof ICaptureMedic order) {
            order.tacz_sewv$setCaptureMedicOrdered(false);
        }
        this.targetMedic = null;
        this.approachTicks = 0;
    }

    @Override
    public void tick() {
        if (this.targetMedic == null) return;
        this.approachTicks++;
        this.unit.getLookControl().setLookAt(this.targetMedic, 30.0F, 30.0F);

        boolean captured = this.targetMedic instanceof IMedicCaptured mc && mc.sewv$isCaptured();
        double workRangeSq = captured ? INTERACT_DISTANCE_SQ : MELEE_RANGE_SQ;
        double distSq = this.unit.distanceToSqr(this.targetMedic);

        if (distSq > workRangeSq) {
            if (this.unit.getNavigation().isDone()) {
                this.unit.getNavigation().moveTo(this.targetMedic, CHASE_SPEED);
            }
            return;
        }

        this.unit.getNavigation().stop();
        if (captured) {
            attemptConversion();
            return;
        }

        if (this.strikeCooldown > 0) {
            this.strikeCooldown--;
            return;
        }
        deliverCaptureStrike();
        this.strikeCooldown = STRIKE_RETRY_TICKS;
    }

    /** One lethal hit, routed through the same death path a player's own kill uses. */
    private void deliverCaptureStrike() {
        DamageSource source = this.unit.level().damageSources().mobAttack(this.unit);
        float lethal = this.targetMedic.getHealth() + 1.0F;
        this.targetMedic.hurt(source, lethal);
        MedicCaptureSupport.debugLog("capture-medic: {} struck {} (lethal={})", this.unit, this.targetMedic, lethal);

        // hurt() -> die() -> LivingDeathEvent -> MedicCaptureSupport.onDeath all run synchronously,
        // so the captured flag is already set by the time hurt() returns — convert immediately
        // rather than waiting a tick.
        if (this.targetMedic instanceof IMedicCaptured mc && mc.sewv$isCaptured()) {
            attemptConversion();
        }
    }

    /** Resolves the owning player and hands off to the same conversion path the right-click uses. */
    private void attemptConversion() {
        UUID ownerId = this.unit.getOwnerUUID();
        if (ownerId == null || this.unit.getServer() == null) {
            this.targetMedic = null; // No owner to charge — nothing this goal can do.
            return;
        }
        ServerPlayer owner = this.unit.getServer().getPlayerList().getPlayer(ownerId);
        if (owner == null) {
            this.targetMedic = null;
            return;
        }

        InteractionResult result = MedicCaptureSupport.tryConvert(this.targetMedic, owner);
        MedicCaptureSupport.debugLog("capture-medic: {} attempted conversion via {} -> {}",
                this.unit, owner.getGameProfile().getName(), result);
        // Either outcome ends this dispatch — a FAIL (insufficient currency) is not worth silently
        // retrying every tick; the player re-issues the order once they have enough.
        this.targetMedic = null;
    }

    /** Nearest medic in range, captured or not — anything {@code VehicleTargeting.isMedic} names. */
    private AbstractUnit findNearestMedic() {
        double radius = SewvConfig.PMC_CAPTURE_MEDIC_RADIUS.get();
        List<AbstractUnit> candidates = this.unit.level().getEntitiesOfClass(
                AbstractUnit.class,
                this.unit.getBoundingBox().inflate(radius),
                candidate -> VehicleTargeting.isMedic(candidate) && candidate.isAlive());
        return candidates.stream()
                .min(Comparator.comparingDouble(this.unit::distanceToSqr))
                .orElse(null);
    }

    private void applySpeedBoost() {
        AttributeInstance speed = this.unit.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null || speed.getModifier(SPEED_BOOST_ID) != null) return;
        double amount = SewvConfig.ENGINEER_REPAIR_SPEED_BOOST.get() - 1.0;
        speed.addTransientModifier(new AttributeModifier(
                SPEED_BOOST_ID, "sewv_capture_medic_speed_boost", amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    private void clearSpeedBoost() {
        AttributeInstance speed = this.unit.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(SPEED_BOOST_ID);
    }
}

package com.neoalive.tacz_sewv.compat.lv;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import com.neoalive.tacz_sewv.entity.ai.core.HullProfile;
import com.neoalive.tacz_sewv.entity.ai.core.ShipHelm;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons.TargetCategory;
import com.neoalive.tacz_sewv.entity.ai.support.WaterSupport;

/**
 * Drives a Limitless Vehicles boat from seat 0 through the same {@link ShipHelm} an SBW ship uses —
 * duty-cycled turns, back and fill, the beam-wide sensor — steering through {@link LvHullInput}.
 *
 * <p>Same decisions as {@code DriveShipGoal} where they do not depend on SBW hull data: every
 * destination is pulled onto water first (range is measured to the real target, steering goes to
 * the projected point), and in a fight the boat holds its gun band and opens the range when hurt.
 * Like the ground goal it stays running while it has the wheel, since LV keeps the last flags.
 */
final class LvShipGoal extends Goal {

    // DriveShipGoal's bands: close enough to shoot, far enough not to ram.
    private static final double TOO_CLOSE = 12.0;
    private static final double TOO_FAR = 30.0;
    private static final double VEHICLE_RING = 36.0;
    private static final double RING_DEADBAND = 5.0;
    private static final float HURT_FRACTION = 0.25F;
    private static final double HURT_RETREAT_MARGIN = 10.0;
    /** Re-projecting a destination onto water is a spiral scan; only redo it when it moves. */
    private static final double REPROJECT_DRIFT_SQ = 25.0;

    private final AbstractUnit unit;
    private final ShipHelm helm;

    private AbstractVehicle hull;
    private HullProfile profile;
    private BlockPos rawDestination;
    private BlockPos waterDestination;

    LvShipGoal(AbstractUnit unit) {
        this.unit = unit;
        this.helm = new ShipHelm(unit);
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!LvCompat.aiEnabled()) return false;
        if (!(this.unit.getVehicle() instanceof AbstractVehicle v)) return false;
        if (LvHulls.kindOf(v) != LvHulls.Kind.VESSEL) return false;
        if (LvHulls.seat0Occupant(v) != this.unit || v.isDestroyed()) return false;
        HullProfile p = LvHulls.profileOf(v);
        if (p == null) return false;
        if (this.hull != v) this.helm.attach(v, new LvHullInput(v, LvSteering.Kind.VESSEL), p);
        this.hull = v;
        this.profile = p;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.hull != null
                && this.unit.getVehicle() == this.hull
                && !this.hull.isDestroyed()
                && LvHulls.seat0Occupant(this.hull) == this.unit
                && LvCompat.aiEnabled();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        this.helm.tickTimers();
        LvSupply.topUpFuel(this.hull);
        LivingEntity target = LvOrders.liveTarget(this.unit);
        if (target != null && (!LvOrders.positional(this.unit) || LvHulls.writtenOff(this.hull, HURT_FRACTION))) {
            fight(target);
            return;
        }
        BlockPos dest = waterDestination(LvOrders.destination(this.unit));
        if (dest == null) {
            hold();
            return;
        }
        double dx = dest.getX() + 0.5 - this.hull.getX();
        double dz = dest.getZ() + 0.5 - this.hull.getZ();
        double arrive = VehicleTargeting.arrivalDistance(this.unit, this.profile.width());
        if (dx * dx + dz * dz > arrive * arrive) {
            this.helm.navigateTo(dest);
        } else {
            hold();
        }
    }

    private void fight(LivingEntity target) {
        BlockPos targetPos = target.blockPosition();
        double dist = Math.sqrt(this.hull.distanceToSqr(
                targetPos.getX() + 0.5, this.hull.getY(), targetPos.getZ() + 0.5));
        boolean armour = VehicleWeapons.classifyTarget(target) == TargetCategory.VEHICLE;
        double ring = armour ? VEHICLE_RING : TOO_FAR;

        if (LvHulls.writtenOff(this.hull, HURT_FRACTION)) {
            if (dist > ring + HURT_RETREAT_MARGIN) {
                hold();
            } else {
                this.helm.openDistance(targetPos, ring + HURT_RETREAT_MARGIN);
            }
            return;
        }
        BlockPos combatPos = waterDestination(targetPos);
        if (dist > ring + RING_DEADBAND && combatPos != null) {
            this.helm.navigateTo(combatPos);
        } else if (dist < (armour ? ring - RING_DEADBAND : TOO_CLOSE)) {
            this.helm.openDistance(targetPos, ring);
        } else {
            hold(); // on station: a stable platform for the gunner
        }
    }

    private void hold() {
        this.helm.stopEngines();
        this.helm.clearRecovery();
    }

    /** {@code raw} pulled onto navigable water, cached against drift. */
    private BlockPos waterDestination(BlockPos raw) {
        if (raw == null) {
            this.rawDestination = null;
            this.waterDestination = null;
            return null;
        }
        if (this.rawDestination == null || this.rawDestination.distSqr(raw) > REPROJECT_DRIFT_SQ) {
            this.rawDestination = raw;
            this.waterDestination = WaterSupport.projectToWater(this.unit.level(), raw);
        }
        return this.waterDestination;
    }

    @Override
    public void stop() {
        if (this.hull != null) this.helm.stopEngines();
        this.helm.clear();
        this.hull = null;
        this.profile = null;
        this.rawDestination = null;
        this.waterDestination = null;
    }
}

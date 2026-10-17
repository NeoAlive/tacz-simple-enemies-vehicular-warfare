package com.neoalive.tacz_sewv.compat.lv;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.HullProfile;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleDriver;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons.TargetCategory;
import com.neoalive.tacz_sewv.entity.ai.utility.Facts;

/**
 * Drives a wheeled or tracked Limitless Vehicles hull from seat 0, through the same
 * {@link VehicleDriver} an SBW ground hull uses — pathfinding, terrain sensor, stuck recovery and
 * all — steering through {@link LvHullInput}.
 *
 * <p>What it decides is deliberately the simple version: drive to the order's destination, and in
 * a fight hold the same engagement ring the utility brain does ({@link Facts#preferredRange}),
 * opening the range when the hull is badly hurt. The brain itself reads SBW hull data throughout
 * and is not carried over.
 *
 * <p>The goal stays running for as long as the unit has the wheel, because LV keeps whatever
 * control flags were last written until seat 0 empties: a crew with nowhere to go still has to
 * say "stop" every tick, and leaving the seat must release them (see {@link #stop}).
 */
final class LvDriveGoal extends Goal {

    /** Below this fraction of max health the crew opens the range instead of holding it. */
    private static final float HURT_FRACTION = 0.35F;
    private static final double HURT_EXTRA_RANGE = 20.0;

    private final AbstractUnit unit;
    private final HullFacts facts = new HullFacts();
    private final VehicleDriver driver;

    private AbstractVehicle hull;
    private HullProfile profile;

    LvDriveGoal(AbstractUnit unit) {
        this.unit = unit;
        this.driver = new VehicleDriver(unit, this.facts);
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!LvCompat.aiEnabled()) return false;
        if (!(this.unit.getVehicle() instanceof AbstractVehicle v)) return false;
        LvHulls.Kind kind = LvHulls.kindOf(v);
        if (kind != LvHulls.Kind.WHEELED && kind != LvHulls.Kind.TRACKED) return false;
        if (LvHulls.seat0Occupant(v) != this.unit || v.isDestroyed()) return false;
        HullProfile p = LvHulls.profileOf(v);
        if (p == null) return false;
        if (this.hull != v) {
            this.facts.attachProfile(v, p);
            this.driver.attach(v, new LvHullInput(v, LvHullInput.steeringKind(kind)), p);
        }
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
        return true; // the steering ramp and stuck detector assume one tick per game tick
    }

    @Override
    public void tick() {
        this.driver.tickTimers();
        LvSupply.topUpFuel(this.hull);
        LivingEntity target = LvOrders.liveTarget(this.unit);
        if (target != null && !LvOrders.positional(this.unit)) {
            fight(target);
        } else {
            travel();
        }
    }

    private void travel() {
        BlockPos dest = LvOrders.destination(this.unit);
        if (dest == null) {
            hold();
            return;
        }
        double distSq = horizontalDistSq(dest);
        double arrive = VehicleTargeting.arrivalDistance(this.unit, this.profile.width());
        if (distSq <= arrive * arrive) {
            this.driver.holdStation(VehicleTargeting.formationForward(this.unit));
            this.driver.clearRecovery(); // parked at destination, not stuck
        } else {
            this.driver.navigateTo(dest, distSq);
        }
    }

    /** Hold the ring for this kind of target; open it when hurt. Same numbers as the utility brain. */
    private void fight(LivingEntity target) {
        TargetCategory category = VehicleWeapons.classifyTarget(target);
        double ring = Facts.preferredRange(category);
        double band = Facts.rangeDeadband(category);
        BlockPos targetPos = target.blockPosition();
        double distSq = horizontalDistSq(targetPos);
        if (LvHulls.writtenOff(this.hull, HURT_FRACTION)) {
            this.driver.retreatFrom(targetPos, ring + HURT_EXTRA_RANGE, distSq);
            return;
        }
        double dist = Math.sqrt(distSq);
        if (dist > ring + band) {
            this.driver.navigateTo(targetPos, distSq);
        } else if (dist < ring - band) {
            this.driver.retreatFrom(targetPos, ring, distSq);
        } else {
            hold(); // on the ring: stand and let the gunner work
        }
    }

    private void hold() {
        this.driver.stop();
        this.driver.clearRecovery();
    }

    private double horizontalDistSq(BlockPos p) {
        double dx = p.getX() + 0.5 - this.hull.getX();
        double dz = p.getZ() + 0.5 - this.hull.getZ();
        return dx * dx + dz * dz;
    }

    @Override
    public void stop() {
        // LV keeps the last flags while seat 0 is occupied — release them on the way out.
        if (this.hull != null) this.driver.stop();
        this.driver.clear();
        this.hull = null;
        this.profile = null;
    }
}

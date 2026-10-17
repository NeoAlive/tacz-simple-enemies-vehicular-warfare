package com.neoalive.tacz_sewv.entity.ai.core;

import java.util.Set;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Vector3f;

import com.neoalive.tacz_sewv.entity.ai.navigation.ShipVehicleNodeEvaluator;
import com.neoalive.tacz_sewv.entity.ai.sensor.ShipTerrainSensor;
import com.neoalive.tacz_sewv.entity.ai.support.WaterSupport;

/**
 * Makes a floating hull go where it is told — the locomotion half of {@code DriveShipGoal}, split
 * out so a boat from another vehicle mod (compat/lv) is steered by the same rules. Knows nothing
 * about why; the goal decides where.
 *
 * <p>A hull that floats handles nothing like one that rolls: it steers only while making way (SBW
 * scales yaw change by current speed), it cannot pivot in place, it dies outright if it beaches,
 * and every destination it is given has to be pulled back onto water first. The manoeuvre that
 * replaces a tank's turn-in-place is <b>back and fill</b>: when the bearing is behind the beam or
 * the bow is fouled, reverse with the rudder over, which swings the bow around, then drive out of
 * it.
 *
 * <p>Steering goes through {@link HullInput} only. {@code sbw} is the same hull when it is an SBW
 * one and gates the collaborators that exist only there (beached-hull tow recovery).
 */
public final class ShipHelm {

    private static final double MIN_ANGLE_RAD = Math.toRadians(6.0);
    private static final double MAX_ANGLE_RAD = Math.toRadians(30.0);
    private static final double MIN_DISTANCE = 4.0;
    private static final double MAX_DISTANCE = 32.0;

    // Throttle duty through a turn: engine on one tick in N. A hard-over rudder gets the deeper
    // cut because that is where the momentum overshoot actually hurts. Never 0 — steerage way is
    // what makes a ship answer the rudder at all.
    private static final double HARD_TURN_RAD = Math.toRadians(45.0);
    private static final int TURN_DUTY = 2;
    private static final int HARD_TURN_DUTY = 3;

    /** Past this bearing error the hull backs and fills instead of trying to sweep round. */
    private static final double REVERSE_ANGLE_RAD = Math.toRadians(110.0);
    private static final int REVERSE_TICKS = 40;
    private static final int REVERSE_MAX_TICKS = 90;

    private static final int PATH_RECALC_COOLDOWN = 20;
    private static final int PATH_FAIL_COOLDOWN = 60;
    private static final int MAX_PATH_AGE_TICKS = 100;
    private static final double PATH_TARGET_DRIFT_SQ = 16.0;
    private static final double NODE_REACHED_SQ = 16.0;
    private static final int PATH_SEARCH_RANGE = 48;
    private static final int PATH_SEARCH_VERTICAL = 8;

    private static final int STUCK_TICKS_THRESHOLD = 30;
    private static final double STUCK_MOVE_EPSILON_SQ = 0.01;
    private static final float STUCK_YAW_EPSILON_DEG = 1.0F;

    private final AbstractUnit unit;
    private final ShipTerrainSensor sensor;
    private final ShipVehicleNodeEvaluator nodeEvaluator = new ShipVehicleNodeEvaluator();
    private final PathFinder pathFinder = new PathFinder(this.nodeEvaluator, 512);

    private Entity vehicle;
    private VehicleEntity sbw;
    private HullInput input;

    private Path currentPath;
    private int pathRecalcCooldown;
    private int pathAge;
    private BlockPos lastPathTarget;

    private int reverseTicks;
    private int reverseTotal;
    private boolean reverseSwingLeft;

    private Vec3 lastStuckPos;
    private float lastStuckYaw;
    private int stuckTicks;

    /** Free-running counter behind the turn throttle's duty cycle. */
    private int throttlePhase;

    public ShipHelm(AbstractUnit unit) {
        this.unit = unit;
        this.sensor = new ShipTerrainSensor(unit);
    }

    public void attach(VehicleEntity v) {
        if (this.sbw != v || this.input == null) this.input = new SbwHullInput(v);
        this.vehicle = v;
        this.sbw = v;
        this.sensor.attach(v);
    }

    /** A boat that is not an SBW hull: steering through {@code in}, size from {@code p}. */
    public void attach(Entity hull, HullInput in, HullProfile p) {
        this.vehicle = hull;
        this.sbw = null;
        this.input = in;
        this.sensor.attach(hull, in, p);
    }

    /** Age the path throttles. Call once per game tick before any steering. */
    public void tickTimers() {
        if (this.pathRecalcCooldown > 0) this.pathRecalcCooldown--;
        this.pathAge++;
    }

    /** Open the range: drive to a standoff point on water rather than backing away blindly. */
    public void openDistance(BlockPos from, double radius) {
        BlockPos out = VehicleTargeting.computeStandoffPoint(this.vehicle, this.input::forwardDirection,
                from, radius, 0.0);
        BlockPos wet = WaterSupport.projectToWater(this.unit.level(), out);
        navigateTo(wet != null ? wet : out);
    }

    public void navigateTo(BlockPos dest) {
        if (this.reverseTicks > 0) {
            backAndFill(dest);
            return;
        }

        BlockPos steer = getSteerTarget(dest);
        Vec3 desired = new Vec3(
                steer.getX() + 0.5 - this.vehicle.getX(), 0.0, steer.getZ() + 0.5 - this.vehicle.getZ());
        if (desired.lengthSqr() < 1.0E-6) {
            stopEngines();
            return;
        }
        desired = desired.normalize();

        Vector3f forward = this.input.forwardDirection().normalize();
        double angle = VehicleTargeting.signedAngleTo(forward, desired);

        // Evaluated unconditionally: || would short-circuit past it on a wide bearing and leave
        // the tracker's baseline stale.
        boolean wedged = updateStuck();
        if (wedged && this.sbw != null && this.vehicle.onGround()
                && !this.vehicle.isInWater() && !this.vehicle.isUnderWater()) {
            com.neoalive.tacz_sewv.entity.ai.support.TowRecoverySupport.onBeachedStuck(this.sbw);
        }
        // Too far off the bow to sweep round, or wedged: back and fill.
        if (Math.abs(angle) > REVERSE_ANGLE_RAD || wedged) {
            beginReverse(angle);
            backAndFill(dest);
            return;
        }

        Vec3 bearing = desired;
        if (this.sensor.enabled()) {
            bearing = this.sensor.chooseClearBearing(desired);
            if (bearing == null) {
                // Nothing ahead is water — never plough on, back out the way we came.
                beginReverse(angle);
                backAndFill(dest);
                return;
            }
            angle = VehicleTargeting.signedAngleTo(forward, bearing);
        }

        driveForward(angle, this.vehicle.distanceToSqr(
                steer.getX() + 0.5, this.vehicle.getY(), steer.getZ() + 0.5));
    }

    /**
     * Ahead, rudder over once roughly lined up — but <b>throttled back through the turn</b>.
     *
     * <p>A boat does not go where it is pointing, it goes where its momentum is carrying it: SBW
     * integrates {@code deltaMovement}, so while the bow swings the hull keeps sliding along its old
     * course and the turn comes out wide. At full ahead that overshoot is what puts a speedboat on
     * the rocks it was steering around, and the sensor cannot save it because its own look-ahead
     * shrinks the moment the hull is finally slow enough to matter.
     *
     * <p>Throttle is a boolean in SBW, so "slower" is a duty cycle: the sharper the turn, the fewer
     * ticks in which the engine is actually driving. That has to bleed speed <b>without stopping</b>
     * — yaw change is scaled by current speed, so a stalled boat cannot turn at all — and pulsing
     * settles at an equilibrium against drag rather than running down to zero, which is exactly the
     * property wanted here. No speed constant is involved, so it holds for any hull.
     */
    private void driveForward(double angle, double distanceSq) {
        double threshold = Mth.clampedLerp(MIN_ANGLE_RAD, MAX_ANGLE_RAD,
                Mth.inverseLerp(Math.sqrt(distanceSq), MIN_DISTANCE, MAX_DISTANCE));
        boolean turning = Math.abs(angle) >= threshold;

        this.input.setInputs(!turning || throttleTick(Math.abs(angle)), false,
                turning && angle > 0, turning && angle < 0);
    }

    /**
     * Whether the engine drives on this tick, given how hard over the rudder is. One tick in two
     * through a moderate turn, one in three through a hard one; anything gentler runs full ahead
     * and never reaches here.
     */
    private boolean throttleTick(double angle) {
        int duty = angle >= HARD_TURN_RAD ? HARD_TURN_DUTY : TURN_DUTY;
        return this.throttlePhase++ % duty == 0;
    }

    /**
     * Astern with the rudder over: the stern walks across, the bow swings toward the bearing. The
     * steering sense inverts because SBW flips the yaw term on negative power.
     */
    private void backAndFill(BlockPos dest) {
        this.reverseTicks--;
        this.reverseTotal++;
        this.input.setInputs(false, true, !this.reverseSwingLeft, this.reverseSwingLeft);

        if (this.reverseTicks > 0) return;

        // Bounded: a hull that can't find a way out astern either stops backing and tries ahead
        // again rather than reversing forever.
        if (this.reverseTotal >= REVERSE_MAX_TICKS) {
            clearRecovery();
            return;
        }

        // Come out of it only when there is somewhere to go; otherwise keep backing.
        Vec3 desired = new Vec3(
                dest.getX() + 0.5 - this.vehicle.getX(), 0.0, dest.getZ() + 0.5 - this.vehicle.getZ());
        if (desired.lengthSqr() < 1.0E-6) {
            clearRecovery();
            return;
        }
        desired = desired.normalize();
        Vector3f forward = this.input.forwardDirection().normalize();
        boolean lined = Math.abs(VehicleTargeting.signedAngleTo(forward, desired)) < REVERSE_ANGLE_RAD;
        boolean clear = !this.sensor.enabled() || this.sensor.chooseClearBearing(desired) != null;
        if (lined && clear) {
            clearRecovery();
        } else {
            this.reverseTicks = REVERSE_TICKS;
        }
    }

    private void beginReverse(double angle) {
        if (this.reverseTicks <= 0) {
            this.reverseTicks = REVERSE_TICKS;
            // Swing the bow the short way round.
            this.reverseSwingLeft = angle > 0;
        }
        this.stuckTicks = 0;
        this.currentPath = null;
        this.pathRecalcCooldown = 0;
    }

    private BlockPos getSteerTarget(BlockPos dest) {
        double drift = this.lastPathTarget == null ? Double.MAX_VALUE : this.lastPathTarget.distSqr(dest);
        boolean stale = this.currentPath == null
                || this.currentPath.isDone()
                || this.pathAge > MAX_PATH_AGE_TICKS
                || drift > PATH_TARGET_DRIFT_SQ;

        if (stale && this.pathRecalcCooldown <= 0) {
            recomputePath(dest);
            this.lastPathTarget = dest;
            this.pathAge = 0;
            this.pathRecalcCooldown = this.currentPath == null ? PATH_FAIL_COOLDOWN : PATH_RECALC_COOLDOWN;
        }

        while (this.currentPath != null && !this.currentPath.isDone()) {
            BlockPos node = this.currentPath.getNextNodePos();
            double distSq = this.vehicle.distanceToSqr(
                    node.getX() + 0.5, this.vehicle.getY(), node.getZ() + 0.5);
            if (distSq >= NODE_REACHED_SQ) return node;
            this.currentPath.advance();
        }
        return dest;
    }

    private void recomputePath(BlockPos target) {
        try {
            BlockPos origin = this.vehicle.blockPosition();
            PathNavigationRegion region = new PathNavigationRegion(
                    this.unit.level(),
                    origin.offset(-PATH_SEARCH_RANGE, -PATH_SEARCH_VERTICAL, -PATH_SEARCH_RANGE),
                    origin.offset(PATH_SEARCH_RANGE, PATH_SEARCH_VERTICAL, PATH_SEARCH_RANGE));
            this.currentPath = this.pathFinder.findPath(
                    region, this.unit, Set.of(target), PATH_SEARCH_RANGE, 1, 1.0F);
        } catch (Exception e) {
            this.currentPath = null;
        }
    }

    /** No headway and no swing while under power — aground or fouled. */
    private boolean updateStuck() {
        Vec3 pos = this.vehicle.position();
        float yaw = this.vehicle.getYRot();
        boolean moved = this.lastStuckPos == null
                || pos.distanceToSqr(this.lastStuckPos) > STUCK_MOVE_EPSILON_SQ
                || Math.abs(Mth.degreesDifference(yaw, this.lastStuckYaw)) > STUCK_YAW_EPSILON_DEG;
        if (moved) {
            this.lastStuckPos = pos;
            this.lastStuckYaw = yaw;
            this.stuckTicks = 0;
            return false;
        }
        return ++this.stuckTicks > STUCK_TICKS_THRESHOLD;
    }

    public void clearRecovery() {
        this.reverseTicks = 0;
        this.reverseTotal = 0;
        this.stuckTicks = 0;
        this.lastStuckPos = null;
    }

    /** Release every steering input. An instruction to hold, not the absence of one. */
    public void stopEngines() {
        this.input.release();
    }

    /** Forget the hull entirely, for a crew leaving its seat. */
    public void clear() {
        this.vehicle = null;
        this.sbw = null;
        this.input = null;
        this.currentPath = null;
        this.lastPathTarget = null;
        this.reverseTicks = 0;
        this.reverseTotal = 0;
        this.stuckTicks = 0;
        this.lastStuckPos = null;
        this.sensor.clear();
    }
}

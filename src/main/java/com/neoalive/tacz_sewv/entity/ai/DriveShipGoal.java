package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.VehicleWeapons.TargetCategory;
import com.neoalive.tacz_sewv.entity.ai.navigation.ShipVehicleNodeEvaluator;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Vector3f;

import java.util.EnumSet;
import java.util.Set;

/**
 * Drives a ship. Separate from {@link DriveVehicleGoal} because a hull that floats handles nothing
 * like one that rolls: it steers only while making way (SBW scales yaw change by current speed),
 * it cannot pivot in place, it dies outright if it beaches, and every destination it is given has
 * to be pulled back onto water first.
 *
 * <p>The manoeuvre that replaces a tank's turn-in-place is <b>back and fill</b>: when the bearing
 * is behind the beam or the bow is fouled, reverse with the rudder over, which swings the bow
 * around, then drive out of it.
 */
public class DriveShipGoal extends Goal {

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

    /** Engagement band for boat guns — close enough to shoot, far enough not to ram. */
    private static final double TOO_CLOSE = 12.0;
    private static final double TOO_FAR = 30.0;
    private static final double VEHICLE_RING = 36.0;
    private static final double RING_DEADBAND = 5.0;

    private static final float PRESERVE_HEALTH_FRACTION = 0.25F;
    private static final double PRESERVE_RETREAT_MARGIN = 10.0;
    private static final float PRESERVE_SMOKE_CHANCE = 0.5F;

    private static final int PATH_RECALC_COOLDOWN = 20;
    private static final int PATH_FAIL_COOLDOWN = 60;
    private static final int MAX_PATH_AGE_TICKS = 100;
    private static final double PATH_TARGET_DRIFT_SQ = 16.0;
    private static final double NODE_REACHED_SQ = 16.0;
    private static final int PATH_SEARCH_RANGE = 48;
    private static final int PATH_SEARCH_VERTICAL = 8;

    /** Re-projecting a destination onto water is a spiral scan; only redo it when it moves. */
    private static final double REPROJECT_DRIFT_SQ = 25.0;

    private static final int STUCK_TICKS_THRESHOLD = 30;
    private static final double STUCK_MOVE_EPSILON_SQ = 0.01;
    private static final float STUCK_YAW_EPSILON_DEG = 1.0F;

    private final AbstractUnit unit;
    private final HullFacts hull = new HullFacts();
    private final ShipTerrainSensor sensor;
    private final StalemateBreaker breaker;
    private final DecoyEpisode smoke = new DecoyEpisode();
    private final VehicleTargeting.AllyAssist allyAssist = new VehicleTargeting.AllyAssist();

    private final ShipVehicleNodeEvaluator nodeEvaluator = new ShipVehicleNodeEvaluator();
    private final PathFinder pathFinder = new PathFinder(this.nodeEvaluator, 512);

    private VehicleEntity vehicle;

    private Path currentPath;
    private int pathRecalcCooldown;
    private int pathAge;
    private BlockPos lastPathTarget;

    private BlockPos rawDestination;
    private BlockPos waterDestination;

    private int reverseTicks;
    private int reverseTotal;
    private boolean reverseSwingLeft;

    private Vec3 lastStuckPos;
    private float lastStuckYaw;
    private int stuckTicks;

    private int weaponSwitchCooldown;
    private int selectedRole = VehicleWeapons.UNCLASSIFIED;

    /** Free-running counter behind the turn throttle's duty cycle. */
    private int throttlePhase;

    public DriveShipGoal(AbstractUnit unit) {
        this.unit = unit;
        this.sensor = new ShipTerrainSensor(unit);
        this.breaker = new StalemateBreaker(unit);
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!(this.unit.getVehicle() instanceof VehicleEntity v)) return false;
        if (v.getFirstPassenger() != this.unit) return false;
        this.hull.attach(v);
        if (!this.hull.isShip()) return false;
        this.vehicle = v;
        this.sensor.attach(v);
        this.breaker.attach(v);
        return getTargetPos() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.unit.getVehicle() == this.vehicle
                && this.vehicle != null
                && this.vehicle.getFirstPassenger() == this.unit
                && !this.vehicle.isWreck()
                && getTargetPos() != null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        if (this.vehicle != null) {
            stopEngines();
            this.vehicle.setDecoyInputDown(false);
        }
        this.vehicle = null;
        this.currentPath = null;
        this.lastPathTarget = null;
        this.rawDestination = null;
        this.waterDestination = null;
        this.reverseTicks = 0;
        this.reverseTotal = 0;
        this.stuckTicks = 0;
        this.lastStuckPos = null;
        this.selectedRole = VehicleWeapons.UNCLASSIFIED;
        this.allyAssist.clear();
        this.sensor.clear();
        this.breaker.clear();
    }

    @Override
    public void tick() {
        if (this.weaponSwitchCooldown > 0) this.weaponSwitchCooldown--;
        if (this.pathRecalcCooldown > 0) this.pathRecalcCooldown--;
        this.pathAge++;

        LivingEntity target = this.unit.getTarget();
        if (target == null || !isLowHealth() || !this.smoke.isDeploying()) {
            this.vehicle.setDecoyInputDown(false);
        }

        BlockPos dest = getTargetPos();
        if (dest == null) {
            stopEngines();
            return;
        }

        if (target != null) {
            // dest is already the target's position pulled onto water (resolveDestination returns
            // the target for every order that fights), so combat reuses it rather than re-running
            // the projection scan every tick.
            fightTick(target, dest);
            return;
        }

        double dx = dest.getX() + 0.5 - this.vehicle.getX();
        double dz = dest.getZ() + 0.5 - this.vehicle.getZ();
        double arrive = VehicleTargeting.arrivalDistance(this.unit, this.vehicle);
        if (dx * dx + dz * dz > arrive * arrive) {
            navigateTo(dest);
        } else {
            stopEngines();
            clearRecovery();
        }
    }

    /**
     * Standoff band, retreat when hurt, weapons on the whole time. Range is measured to the
     * TARGET but steering goes to {@code combatPos} (the same point pulled onto water) — a target
     * ashore is unreachable, so the hull closes on the range that matters and holds the water off
     * it rather than aiming at a spot it would run aground on.
     */
    private void fightTick(LivingEntity target, BlockPos combatPos) {
        BlockPos targetPos = target.blockPosition();
        double dist = Math.sqrt(this.vehicle.distanceToSqr(
                targetPos.getX() + 0.5, this.vehicle.getY(), targetPos.getZ() + 0.5));

        TargetCategory category = VehicleWeapons.classifyTarget(target);
        boolean armour = category == TargetCategory.VEHICLE;
        double ring = armour ? VEHICLE_RING : TOO_FAR;

        selectWeaponForTarget(target);
        if (this.selectedRole == VehicleWeapons.WEAPON_SPECIAL) {
            VehicleWeapons.tryAiFireAssist(this.vehicle, this.unit, target,
                    SewvConfig.AI_FIRE_ASSIST_CONE_DEG.get());
        }

        if (isLowHealth()) {
            preserveRetreat(target, ring);
            return;
        }

        BlockPos orbit = this.breaker.update(target, targetPos, ring);
        if (orbit != null) {
            BlockPos wet = waterNear(orbit);
            navigateTo(wet != null ? wet : combatPos);
            return;
        }

        if (dist > ring + RING_DEADBAND) {
            navigateTo(combatPos);
        } else if (dist < (armour ? ring - RING_DEADBAND : TOO_CLOSE)) {
            openDistance(targetPos, ring);
        } else {
            // On station: hold way on so the turret has a stable platform, but stop closing.
            stopEngines();
            clearRecovery();
        }
    }

    private void preserveRetreat(LivingEntity threat, double ring) {
        if (this.smoke.roll(this.unit.level().getGameTime(), this.unit.getRandom(), PRESERVE_SMOKE_CHANCE)
                && this.vehicle.hasDecoy()) {
            this.vehicle.setDecoyInputDown(true);
        }
        BlockPos threatPos = threat.blockPosition();
        double dist = Math.sqrt(this.vehicle.distanceToSqr(
                threatPos.getX() + 0.5, this.vehicle.getY(), threatPos.getZ() + 0.5));
        if (dist > ring + PRESERVE_RETREAT_MARGIN) {
            stopEngines();
            clearRecovery();
            return;
        }
        openDistance(threatPos, ring + PRESERVE_RETREAT_MARGIN);
    }

    /** Open the range: drive to a standoff point on water rather than backing away blindly. */
    private void openDistance(BlockPos from, double radius) {
        BlockPos out = VehicleTargeting.computeStandoffPoint(this.vehicle, from, radius);
        BlockPos wet = waterNear(out);
        navigateTo(wet != null ? wet : out);
    }

    private void navigateTo(BlockPos dest) {
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

        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double angle = VehicleTargeting.signedAngleTo(forward, desired);

        // Evaluated unconditionally: || would short-circuit past it on a wide bearing and leave
        // the tracker's baseline stale.
        boolean wedged = updateStuck();
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

        this.vehicle.setBackInputDown(false);
        this.vehicle.setForwardInputDown(!turning || throttleTick(Math.abs(angle)));
        this.vehicle.setLeftInputDown(turning && angle > 0);
        this.vehicle.setRightInputDown(turning && angle < 0);
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
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(true);
        this.vehicle.setLeftInputDown(!this.reverseSwingLeft);
        this.vehicle.setRightInputDown(this.reverseSwingLeft);

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
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
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

    private void clearRecovery() {
        this.reverseTicks = 0;
        this.reverseTotal = 0;
        this.stuckTicks = 0;
        this.lastStuckPos = null;
    }

    private void stopEngines() {
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
    }

    private boolean isLowHealth() {
        float max = this.vehicle.getMaxHealth();
        return max > 0.0F && this.vehicle.getHealth() < max * PRESERVE_HEALTH_FRACTION;
    }

    private void selectWeaponForTarget(LivingEntity target) {
        int seat = this.vehicle.getSeatIndex(this.unit);
        if (seat < 0 || this.weaponSwitchCooldown > 0) return;
        this.selectedRole = VehicleWeapons.selectWeaponForTarget(this.vehicle, seat, target);
        this.weaponSwitchCooldown = SewvConfig.WEAPON_SWITCH_COOLDOWN_TICKS.get();
    }

    /**
     * The order destination, pulled onto navigable water. Orders point at people and places on
     * land — a followed commander standing on a beach, a patrol waypoint inland — and steering a
     * hull at one of those is how it runs aground. Cached: the projection is a spiral scan.
     */
    private BlockPos getTargetPos() {
        BlockPos raw = VehicleTargeting.resolveDestination(this.unit, this.vehicle, this.allyAssist);
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

    private BlockPos waterNear(BlockPos pos) {
        return WaterSupport.projectToWater(this.unit.level(), pos);
    }
}

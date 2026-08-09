package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Vector3f;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.DroneControl;
import com.neoalive.tacz_sewv.entity.ai.support.DroneSupport;

/**
 * Deploys and flies one kamikaze drone for an RU/US engineer. Claims no flags — engineer freeze
 * is {@link DroneControlLockGoal}; this goal steers the hull only while locked and owns lock
 * enter/exit. Close-range threats (and recent hostile hits) drop the lock and park the drone
 * with zero inputs; once clear, the engineer re-locks and resumes control. A live SEM target at
 * any range is <em>not</em> an unlock — that left every combat-zone drone parked forever.
 *
 * <p>SBW drone {@code travel()} ignores forward/strafe while {@code onGround()}, and a held
 * {@code up} input grows {@code holdTickY} unboundedly (~0.05×ticks vertical impulse — ~18
 * blocks in one second of continuous hold). Steering therefore uses short pulses and always
 * takeoffs before lateral input.
 */
public class DroneOperatorGoal extends Goal {

    private static final double ARRIVE_RADIUS = 4.0;
    /** Middle ground: continuous hold overshoots; light pulses feel sluggish. */
    private static final double ALT_DEADBAND = 2.0;
    private static final double MAX_YAW_STEP_DEG = 5.0;
    private static final double FACE_THRESHOLD_DEG = 16.0;
    private static final double WANDER_RADIUS = 24.0;
    private static final int WANDER_REPICK_TICKS = 80;
    /** Below this AGL (or {@code onGround}), only climb — lateral inputs are inert on the deck. */
    private static final double TAKEOFF_AGL = 2.5;

    /** ~60–75% duty: enough authority, still resets holdTick before it runs away. */
    private static final int ALT_PERIOD = 6;
    private static final int FWD_PERIOD = 5;

    private final AbstractUnit unit;
    private final List<DroneEntity> drones = new ArrayList<>();
    private long nextDeployCheck;
    private long nextThreatScan;
    private long nextWanderRepick;
    private int scanCooldown;
    private double wanderX = Double.NaN;
    private double wanderZ = Double.NaN;
    /** Sticky between rescans — returning false mid-window used to re-lock every tick (sit flicker). */
    private boolean holdUnlock;
    @javax.annotation.Nullable
    private VehicleEntity diveTarget;

    public DroneOperatorGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        return !this.unit.level().isClientSide();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        this.drones.removeIf(d -> {
            if (d.isAlive()) return false;
            DroneControl.clearDroneClaim(this.unit);
            return true;
        });
        maybeDeploy();
        syncOwnedList();

        DroneEntity drone = this.drones.isEmpty() ? null : this.drones.get(0);
        if (drone == null) {
            if (DroneControl.isLocked(this.unit)) unlock();
            return;
        }

        updateLockState(drone);

        if (!DroneControl.isLocked(this.unit)) {
            DroneControl.setDiveArmed(drone, false);
            DroneControl.zeroInputs(drone);
            this.diveTarget = null;
            return;
        }

        flyLocked(drone);
    }

    private void syncOwnedList() {
        if (!(this.unit.level() instanceof ServerLevel level)) return;
        List<DroneEntity> owned = DroneSupport.findOwnedDrones(level, this.unit);
        this.drones.retainAll(owned);
        for (DroneEntity d : owned) {
            if (!this.drones.contains(d)) this.drones.add(d);
        }
    }

    private void maybeDeploy() {
        if (!(this.unit.level() instanceof ServerLevel level)) return;
        List<DroneEntity> owned = DroneSupport.findOwnedDrones(level, this.unit);
        this.drones.retainAll(owned);
        for (DroneEntity drone : owned) {
            if (!this.drones.contains(drone)) this.drones.add(drone);
        }

        int max = SewvConfig.DRONE_MAX_PER_ENGINEER.get();
        if (this.drones.size() >= max) return;
        // Claimed UUID exists but chunk unloaded — do not spawn a second drone.
        if (DroneSupport.hasUnloadedClaim(level, this.unit)) return;

        long now = this.unit.level().getGameTime();
        if (now < this.nextDeployCheck) return;
        this.nextDeployCheck = now + SewvConfig.DRONE_DEPLOY_CHECK_INTERVAL_TICKS.get();

        if (this.unit.getRandom().nextFloat() >= SewvConfig.DRONE_DEPLOY_CHANCE.get()) return;

        DroneEntity drone = DroneSupport.spawnDrone(level, this.unit);
        if (drone != null) {
            this.drones.add(drone);
            CrewRadio.speakUnit(this.unit, CrewRadio.Line.DRONE);
        }
    }

    private void updateLockState(DroneEntity drone) {
        boolean locked = DroneControl.isLocked(this.unit);
        if (shouldBreakLock()) {
            if (locked) unlock();
            return;
        }
        if (!locked) {
            DroneControl.setLocked(this.unit, true);
        }
    }

    private boolean shouldBreakLock() {
        // Close-range only. A distant SEM getTarget() must NOT unlock — engineers in a warzone
        // always hold one, and that parked every drone after deploy.
        LivingEntity lastHurt = this.unit.getLastHurtByMob();
        if (lastHurt != null && this.unit.tickCount - this.unit.getLastHurtByMobTimestamp() < DroneControl.HURT_MEMORY_TICKS) {
            if (!VehicleTargeting.isNonHostile(this.unit, lastHurt)) return true;
        }

        long now = this.unit.level().getGameTime();
        if (now >= this.nextThreatScan) {
            this.nextThreatScan = now + DroneControl.LOCK_THREAT_RESCAN_TICKS;
            // Real hull repair outranks drone sit. Drones are excluded from findNearestRepairable
            // so the engineer does not unlock to chase his own kamikaze.
            this.holdUnlock = RepairGoal.findNearestRepairable(this.unit) != null || nearbyHostile();
        }
        return this.holdUnlock;
    }

    private boolean nearbyHostile() {
        double r = DroneControl.LOCK_THREAT_RADIUS;
        AABB box = this.unit.getBoundingBox().inflate(r);
        for (LivingEntity e : this.unit.level().getEntitiesOfClass(LivingEntity.class, box,
                living -> living.isAlive() && living.isAttackable()
                        && !VehicleTargeting.isNonHostile(this.unit, living))) {
            if (this.unit.distanceToSqr(e) <= r * r) return true;
        }
        return false;
    }

    /** Called from LivingHurt and from this goal. Parks owned drones until the engineer re-locks. */
    public static void unlockEngineer(AbstractUnit unit) {
        if (!DroneControl.isLocked(unit)) return;
        DroneControl.setLocked(unit, false);
        if (!(unit.level() instanceof ServerLevel level)) return;
        for (DroneEntity drone : DroneSupport.findOwnedDrones(level, unit)) {
            DroneControl.setDiveArmed(drone, false);
            DroneControl.zeroInputs(drone);
        }
    }

    private void unlock() {
        unlockEngineer(this.unit);
        this.diveTarget = null;
    }

    private void flyLocked(DroneEntity drone) {
        // Deck: SBW ignores forward/strafe while onGround — climb clear before anything else.
        if (needsTakeoff(drone)) {
            DroneControl.setDiveArmed(drone, false);
            takeoff(drone);
            return;
        }

        if (this.scanCooldown > 0) this.scanCooldown--;
        if (this.scanCooldown <= 0) {
            this.scanCooldown = SewvConfig.DRONE_SCAN_INTERVAL_TICKS.get();
            this.diveTarget = DroneSupport.findHostileVehicle(drone, this.unit);
        }

        if (this.diveTarget != null && this.diveTarget.isAlive() && !this.diveTarget.isWreck()
                && DroneSupport.hasHostilePassenger(this.unit, this.diveTarget)) {
            DroneControl.setDiveArmed(drone, true);
            diveAt(drone, this.diveTarget);
            return;
        }

        DroneControl.setDiveArmed(drone, false);
        this.diveTarget = null;
        wander(drone);
    }

    private void wander(DroneEntity drone) {
        double leash = SewvConfig.DRONE_LEASH_RADIUS.get();
        double dxHome = this.unit.getX() - drone.getX();
        double dzHome = this.unit.getZ() - drone.getZ();
        double distHome = Math.sqrt(dxHome * dxHome + dzHome * dzHome);

        long now = this.unit.level().getGameTime();
        if (distHome > leash) {
            steerHorizontal(drone, dxHome, dzHome, distHome, 3);
            pulseAltitude(drone, cruiseAlt(drone), 4);
            return;
        }

        if (Double.isNaN(this.wanderX) || now >= this.nextWanderRepick
                || Math.sqrt((drone.getX() - this.wanderX) * (drone.getX() - this.wanderX)
                + (drone.getZ() - this.wanderZ) * (drone.getZ() - this.wanderZ)) < ARRIVE_RADIUS) {
            double angle = this.unit.getRandom().nextDouble() * Math.PI * 2.0;
            double radius = this.unit.getRandom().nextDouble() * WANDER_RADIUS;
            this.wanderX = this.unit.getX() + Math.cos(angle) * radius;
            this.wanderZ = this.unit.getZ() + Math.sin(angle) * radius;
            this.nextWanderRepick = now + WANDER_REPICK_TICKS;
        }

        double dx = this.wanderX - drone.getX();
        double dz = this.wanderZ - drone.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > ARRIVE_RADIUS) {
            steerHorizontal(drone, dx, dz, dist, 3);
        } else {
            drone.setForwardInputDown(false);
            drone.setBackInputDown(false);
            drone.setLeftInputDown(false);
            drone.setRightInputDown(false);
        }
        pulseAltitude(drone, cruiseAlt(drone), 4);
    }

    private void diveAt(DroneEntity drone, VehicleEntity target) {
        double dx = target.getX() - drone.getX();
        double dy = target.getY() + target.getBbHeight() * 0.5 - drone.getY();
        double dz = target.getZ() - drone.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        drone.setBackInputDown(false);
        drone.setLeftInputDown(false);
        drone.setRightInputDown(false);

        if (horiz > 0.1) {
            Vec3 dir = new Vec3(dx / horiz, 0, dz / horiz);
            Vector3f forward = drone.getForwardDirection().normalize();
            double yawErrDeg = Math.toDegrees(VehicleTargeting.signedAngleTo(forward, dir));
            double step = Mth.clamp(-yawErrDeg, -MAX_YAW_STEP_DEG * 1.5, MAX_YAW_STEP_DEG * 1.5);
            drone.setYRot((float) (drone.getYRot() + step));
            // Dive: denser forward pulse once roughly aimed; still not a continuous hold.
            boolean aimed = Math.abs(yawErrDeg) < FACE_THRESHOLD_DEG * 2.0;
            drone.setForwardInputDown(aimed && pulse(drone.tickCount, FWD_PERIOD, 4));
        } else {
            drone.setForwardInputDown(pulse(drone.tickCount, FWD_PERIOD, 4));
        }

        // Dive altitude: denser pulses than wander; brief off ticks still reset holdTickY.
        drone.setUpInputDown(false);
        drone.setDownInputDown(false);
        if (dy > ALT_DEADBAND) {
            drone.setUpInputDown(pulse(drone.tickCount, ALT_PERIOD, 5));
        } else if (dy < -ALT_DEADBAND) {
            drone.setDownInputDown(pulse(drone.tickCount, ALT_PERIOD, 5));
        }
    }

    /**
     * Yaw toward destination; forward only on a short duty cycle once roughly facing it.
     * {@code fwdOn} = consecutive forward ticks per {@link #FWD_PERIOD}.
     */
    private void steerHorizontal(DroneEntity drone, double dx, double dz, double dist, int fwdOn) {
        drone.setBackInputDown(false);
        drone.setLeftInputDown(false);
        drone.setRightInputDown(false);

        Vec3 dirToDest = new Vec3(dx / dist, 0, dz / dist);
        Vector3f forward = drone.getForwardDirection().normalize();
        double yawErrDeg = Math.toDegrees(VehicleTargeting.signedAngleTo(forward, dirToDest));
        double step = Mth.clamp(-yawErrDeg, -MAX_YAW_STEP_DEG, MAX_YAW_STEP_DEG);
        drone.setYRot((float) (drone.getYRot() + step));
        boolean aimed = Math.abs(yawErrDeg) < FACE_THRESHOLD_DEG;
        drone.setForwardInputDown(aimed && pulse(drone.tickCount, FWD_PERIOD, fwdOn));
    }

    private void pulseAltitude(DroneEntity drone, double targetY, int onTicks) {
        double dy = targetY - drone.getY();
        drone.setUpInputDown(false);
        drone.setDownInputDown(false);
        if (Math.abs(dy) <= ALT_DEADBAND) return;
        // Extra on-tick for large errors — still leave ≥1 off tick so holdTickY resets.
        int on = Math.abs(dy) > 12.0 ? Math.min(onTicks + 1, ALT_PERIOD - 1) : onTicks;
        if (dy > 0) {
            drone.setUpInputDown(pulse(drone.tickCount, ALT_PERIOD, on));
        } else {
            drone.setDownInputDown(pulse(drone.tickCount, ALT_PERIOD, on));
        }
    }

    /** Climb-only until clear of the deck (lateral inputs do nothing while grounded). */
    private void takeoff(DroneEntity drone) {
        DroneControl.zeroInputs(drone);
        drone.setUpInputDown(pulse(drone.tickCount, ALT_PERIOD, 4));
    }

    private static boolean needsTakeoff(DroneEntity drone) {
        if (drone.onGround()) return true;
        return agl(drone) < TAKEOFF_AGL;
    }

    private static double agl(DroneEntity drone) {
        int surface = drone.level().getHeight(Heightmap.Types.WORLD_SURFACE, drone.getBlockX(), drone.getBlockZ());
        return drone.getY() - surface;
    }

    private static boolean pulse(int tick, int period, int onTicks) {
        int on = Mth.clamp(onTicks, 1, period - 1);
        return (tick % period) < on;
    }

    private static double cruiseAlt(DroneEntity drone) {
        int surface = drone.level().getHeight(Heightmap.Types.WORLD_SURFACE, drone.getBlockX(), drone.getBlockZ());
        return surface + SewvConfig.DRONE_SCAN_ALTITUDE.get();
    }
}

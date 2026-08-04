package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Deploys and flies one kamikaze drone for an RU/US engineer. Claims no flags — engineer freeze
 * is {@link DroneControlLockGoal}; this goal only steers the hull and owns lock enter/exit.
 */
public class DroneOperatorGoal extends Goal {

    private static final double ARRIVE_RADIUS = 4.0;
    private static final double ALT_DEADBAND = 1.5;
    private static final double MAX_YAW_STEP_DEG = 6.0;
    private static final double FACE_THRESHOLD_DEG = 15.0;
    private static final double WANDER_RADIUS = 24.0;
    private static final int WANDER_REPICK_TICKS = 80;

    private final AbstractUnit unit;
    private final List<DroneEntity> drones = new ArrayList<>();
    private long nextDeployCheck;
    private long nextThreatScan;
    private long nextWanderRepick;
    private int scanCooldown;
    private double wanderX = Double.NaN;
    private double wanderZ = Double.NaN;
    @javax.annotation.Nullable
    private LivingEntity diveTarget;

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
        if (drone != null) this.drones.add(drone);
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
        if (this.unit.getTarget() != null) return true;

        LivingEntity lastHurt = this.unit.getLastHurtByMob();
        if (lastHurt != null && this.unit.tickCount - this.unit.getLastHurtByMobTimestamp() < DroneControl.HURT_MEMORY_TICKS) {
            if (!VehicleTargeting.isNonHostile(this.unit, lastHurt)) return true;
        }

        long now = this.unit.level().getGameTime();
        if (now < this.nextThreatScan) return false;
        this.nextThreatScan = now + DroneControl.LOCK_THREAT_RESCAN_TICKS;

        double r = DroneControl.LOCK_THREAT_RADIUS;
        AABB box = this.unit.getBoundingBox().inflate(r);
        for (LivingEntity e : this.unit.level().getEntitiesOfClass(LivingEntity.class, box,
                living -> living.isAlive() && living.isAttackable()
                        && !VehicleTargeting.isNonHostile(this.unit, living))) {
            if (this.unit.distanceToSqr(e) <= r * r) return true;
        }
        return false;
    }

    /** Called from LivingHurt and from this goal. */
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
        if (this.scanCooldown > 0) this.scanCooldown--;
        if (this.scanCooldown <= 0) {
            this.scanCooldown = SewvConfig.DRONE_SCAN_INTERVAL_TICKS.get();
            LivingEntity contact = DroneSupport.findInnerRingEnemy(drone, this.unit);
            this.diveTarget = contact;
        }

        if (this.diveTarget != null && this.diveTarget.isAlive()
                && !VehicleTargeting.isNonHostile(this.unit, this.diveTarget)) {
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
            steerHorizontal(drone, dxHome, dzHome, distHome);
            holdAltitude(drone, cruiseAlt(drone));
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
            steerHorizontal(drone, dx, dz, dist);
        } else {
            drone.setForwardInputDown(false);
            drone.setBackInputDown(false);
            drone.setLeftInputDown(false);
            drone.setRightInputDown(false);
        }
        holdAltitude(drone, cruiseAlt(drone));
    }

    private void diveAt(DroneEntity drone, LivingEntity target) {
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
            double step = Mth.clamp(-yawErrDeg, -MAX_YAW_STEP_DEG * 2.0, MAX_YAW_STEP_DEG * 2.0);
            drone.setYRot((float) (drone.getYRot() + step));
            drone.setForwardInputDown(Math.abs(yawErrDeg) < FACE_THRESHOLD_DEG * 2.0);
        } else {
            drone.setForwardInputDown(true);
        }

        drone.setUpInputDown(dy > ALT_DEADBAND);
        drone.setDownInputDown(dy < -ALT_DEADBAND);
    }

    private void steerHorizontal(DroneEntity drone, double dx, double dz, double dist) {
        drone.setBackInputDown(false);
        drone.setLeftInputDown(false);
        drone.setRightInputDown(false);

        Vec3 dirToDest = new Vec3(dx / dist, 0, dz / dist);
        Vector3f forward = drone.getForwardDirection().normalize();
        double yawErrDeg = Math.toDegrees(VehicleTargeting.signedAngleTo(forward, dirToDest));
        double step = Mth.clamp(-yawErrDeg, -MAX_YAW_STEP_DEG, MAX_YAW_STEP_DEG);
        drone.setYRot((float) (drone.getYRot() + step));
        drone.setForwardInputDown(Math.abs(yawErrDeg) < FACE_THRESHOLD_DEG);
    }

    private void holdAltitude(DroneEntity drone, double targetY) {
        double dy = targetY - drone.getY();
        drone.setUpInputDown(dy > ALT_DEADBAND);
        drone.setDownInputDown(dy < -ALT_DEADBAND);
    }

    private static double cruiseAlt(DroneEntity drone) {
        int surface = drone.level().getHeight(Heightmap.Types.WORLD_SURFACE, drone.getBlockX(), drone.getBlockZ());
        return surface + SewvConfig.DRONE_SCAN_ALTITUDE.get();
    }
}

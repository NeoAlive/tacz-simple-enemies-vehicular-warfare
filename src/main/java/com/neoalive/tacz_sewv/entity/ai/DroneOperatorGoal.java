package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Deploys and flies up to {@code droneMaxPerEngineer} recon drones for an RU/US engineer.
 * Recon only, by design — see {@link DroneSupport}: a drone is spawned unarmed and never
 * fed a player controller, so nothing else in SuperbWarfare moves or fires it; this goal is
 * the drone's entire AI.
 *
 * <p>Claims NO flags: everything it touches belongs to the drones it owns, never to the
 * engineer itself, so it never contends with {@code RepairGoal} or the engineer's combat kit.
 *
 * <p>Flight model is deliberately simpler than {@link DriveHelicopterGoal}'s: no whiskers, no
 * terrain-following cruise, no combat profile — a drone holds station over the engineer's
 * current position (staggered in altitude per drone index so a pair doesn't fight over the
 * same point) and does nothing else but look for enemies to relay. It's small, expendable,
 * short-range escort, not a gunship; if it needs to dodge terrain later, that's the moment to
 * borrow {@link AirTerrainSensor}, not before.
 */
public class DroneOperatorGoal extends Goal {

    private static final double ARRIVE_RADIUS = 4.0;
    private static final double ALT_DEADBAND = 1.5;
    private static final double MAX_YAW_STEP_DEG = 6.0;
    private static final double FACE_THRESHOLD_DEG = 15.0;
    // Two drones stack rather than orbit side by side — simplest possible collision avoidance
    // for a cap this small.
    private static final double ALTITUDE_STAGGER = 8.0;

    private final AbstractUnit unit;
    private final List<DroneEntity> drones = new ArrayList<>();
    /** Game time, so droneDeployCheckIntervalTicks means what it says (goals tick every other tick). */
    private long nextDeployCheck;
    private int scanCooldown;

    public DroneOperatorGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        return !this.unit.level().isClientSide();
    }

    // Re-asserts decaying input flags every tick — same reasoning as DriveHelicopterGoal:
    // vanilla only ticks a running goal every OTHER tick unless this is overridden.
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        this.drones.removeIf(d -> !d.isAlive());
        maybeDeploy();

        boolean doScan = this.scanCooldown <= 0;
        for (int i = 0; i < this.drones.size(); i++) {
            DroneEntity drone = this.drones.get(i);
            flyEscort(drone, i);
            if (doScan) scanAndBroadcast(drone);
        }
        if (doScan) {
            this.scanCooldown = SewvConfig.DRONE_SCAN_INTERVAL_TICKS.get();
        } else {
            this.scanCooldown--;
        }
    }

    // A full flight skips this entirely — the cheapest possible check comes first, same
    // reasoning as VehicleTargetScanGoal's "already engaged — don't retarget every scan": most
    // engineers, most of the time, already have their cap and there's nothing to decide.
    private void maybeDeploy() {
        int max = SewvConfig.DRONE_MAX_PER_ENGINEER.get();
        if (this.drones.size() >= max) return;

        long now = this.unit.level().getGameTime();
        if (now < this.nextDeployCheck) return;
        this.nextDeployCheck = now + SewvConfig.DRONE_DEPLOY_CHECK_INTERVAL_TICKS.get();

        if (!(this.unit.level() instanceof ServerLevel level)) return;

        // Re-adopt anything of mine still alive that isn't cached yet — this, not the cache
        // itself, is what makes the cap survive the engineer's goal instance being rebuilt on
        // a chunk reload (see DroneSupport's class doc). Runs every check, win or lose the roll
        // below, so a reload's worth of orphaned drones is picked back up within one interval
        // rather than waiting on a lucky roll.
        for (DroneEntity owned : DroneSupport.findOwnedDrones(level, this.unit)) {
            if (!this.drones.contains(owned)) this.drones.add(owned);
        }
        if (this.drones.size() >= max) return;

        if (this.unit.getRandom().nextFloat() >= SewvConfig.DRONE_DEPLOY_CHANCE.get()) return; // rolled no this cycle

        DroneEntity drone = DroneSupport.spawnDrone(level, this.unit);
        if (drone != null) this.drones.add(drone);
    }

    // Yaw is set directly rather than through SBW's own mouse-stick path (which only reads
    // from a player-held Monitor item and would never move without one) — the four directional
    // input flags are the same generic VehicleEntity surface DriveHelicopterGoal already drives
    // helicopters through; only the yaw/pitch update is player-gated, so that part is bypassed.
    private void flyEscort(DroneEntity drone, int index) {
        double targetAlt = surfaceBelow(drone) + SewvConfig.DRONE_SCAN_ALTITUDE.get() + index * ALTITUDE_STAGGER;
        applyCollective(drone, targetAlt);

        double dx = this.unit.getX() - drone.getX();
        double dz = this.unit.getZ() - drone.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        drone.setBackInputDown(false);
        drone.setLeftInputDown(false);
        drone.setRightInputDown(false);

        if (dist <= ARRIVE_RADIUS) {
            drone.setForwardInputDown(false);
            return;
        }

        Vec3 dirToDest = new Vec3(dx / dist, 0, dz / dist);
        Vector3f forward = drone.getForwardDirection().normalize();
        double yawErrDeg = Math.toDegrees(VehicleTargeting.signedAngleTo(forward, dirToDest));
        double step = Mth.clamp(-yawErrDeg, -MAX_YAW_STEP_DEG, MAX_YAW_STEP_DEG);
        drone.setYRot((float) (drone.getYRot() + step));

        drone.setForwardInputDown(Math.abs(yawErrDeg) < FACE_THRESHOLD_DEG);
    }

    private void applyCollective(DroneEntity drone, double targetY) {
        double dy = targetY - drone.getY();
        drone.setUpInputDown(dy > ALT_DEADBAND);
        drone.setDownInputDown(dy < -ALT_DEADBAND);
    }

    private void scanAndBroadcast(DroneEntity drone) {
        LivingEntity spotted = DroneSupport.findVisibleEnemy(drone, this.unit,
                SewvConfig.DRONE_DETECTION_RADIUS.get());
        if (spotted == null) return;
        if (!(this.unit.level() instanceof ServerLevel level)) return;

        DroneSupport.broadcastTarget(level, this.unit, spotted, drone.position(),
                SewvConfig.DRONE_BROADCAST_RADIUS.get());
    }

    private static int surfaceBelow(DroneEntity drone) {
        return drone.level().getHeight(Heightmap.Types.WORLD_SURFACE, drone.getBlockX(), drone.getBlockZ());
    }
}

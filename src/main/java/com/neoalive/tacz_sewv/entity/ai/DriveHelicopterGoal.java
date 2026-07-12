package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Vector3f;

import java.util.EnumSet;

/**
 * MVP autopilot for SuperbWarfare helicopters (and, incidentally, fixed-wing —
 * both report {@link EngineInfo.Helicopter}). Takes off, flies to a destination in
 * 3D, orbits a combat target at standoff, and hovers in place when idle. The
 * companion {@link DriveVehicleGoal} declines helicopters, so exactly one of the
 * two drives any given hull.
 *
 * <p><b>Control model (from SBW's {@code helicopterEngine}).</b> Unlike a ground
 * hull, a helicopter's {@code forwardInputDown} is the <i>collective</i> — it
 * builds rotor power to climb; {@code downInputDown} descends. Horizontal
 * translation comes from <i>pitch</i>: {@code mouseMoveSpeedY} noses the hull over
 * so its lift vector tilts forward. Heading comes from {@code mouseMoveSpeedX}
 * (yaw). The analog inputs decay ×0.95 each tick and every boolean input is
 * force-cleared while there is no controlling passenger, so <b>every input is
 * re-asserted every tick.</b> The physics only responds while airborne, with
 * energy, and above ~10% health (below that it enters an uncontrollable spin).
 *
 * <p><b>Sign caveat.</b> The exact sign of the yaw/pitch commands and the pitch
 * magnitude are the two things SBW decodes least obviously; the constants below
 * are starting points and may need flipping/tuning against the running game.
 */
public class DriveHelicopterGoal extends Goal {

    // Below this fraction of max health the engine takes over with a crash-spin —
    // nothing the pilot inputs matters, so we stop fighting it.
    private static final float CRASH_HEALTH_FRACTION = 0.10F;

    // Yaw command = clamp(YAW_GAIN * yawErrorRadians), so full stick deflection is
    // reached around a ~38° heading error and eases off as the nose comes onto line.
    private static final double YAW_GAIN = 1.5;
    // Only translate forward once roughly pointed at the steer point; while the nose
    // is still swinging, pitching over would drag the hull off toward the old heading.
    private static final double ALIGN_THRESHOLD_RAD = Math.toRadians(35.0);
    // Cruise pitch command (nose-down → forward). Deliberately gentle for an MVP.
    private static final float FORWARD_PITCH = 0.5F;

    // Orbit: steer toward a point this far ahead (radians) along the standoff circle,
    // in a fixed direction, so the yaw+forward loop traces a smooth circle. Deadband
    // gives the ring width so TRANSIT↔ORBIT doesn't flip-flop on the exact radius.
    private static final double ORBIT_LEAD_RAD = Math.toRadians(20.0);
    private static final double ORBIT_DEADBAND = 6.0;
    // Horizontal distance to a non-combat destination at which we call it arrived and
    // switch to holding a hover, rather than jittering back and forth over the point.
    private static final double ARRIVE_RADIUS = 6.0;

    private final AbstractUnit unit;
    private final VehicleTargeting.AllyAssist allyAssist = new VehicleTargeting.AllyAssist();
    // Fixed per-goal orbit direction so a flight of helicopters doesn't all circle the
    // same way and pile up on one arc.
    private final int orbitDir;

    private VehicleEntity vehicle;
    // Altitude to hold while hovering, captured on entry so an idle helicopter neither
    // sinks nor spontaneously climbs. NaN = not currently hovering.
    private double hoverTargetY = Double.NaN;

    private VehicleEntity helicopterCacheVehicle;
    private boolean helicopterCacheValue;

    public DriveHelicopterGoal(AbstractUnit unit) {
        this.unit = unit;
        this.orbitDir = unit.getRandom().nextBoolean() ? 1 : -1;
        this.setFlags(EnumSet.noneOf(Flag.class)); // flying doesn't need to lock move/look flags
    }

    @Override
    public boolean canUse() {
        if (!(this.unit.getVehicle() instanceof VehicleEntity v)) return false;
        // ONLY the driver (seat 0) flies — same driver/commander model as the ground goal.
        if (v.getFirstPassenger() != this.unit) return false;
        this.vehicle = v;
        // Run whenever mounted in a helicopter, even with no destination: a helicopter
        // must be actively controlled to hold station, so "idle" means "hover", not "off".
        return isHelicopter();
    }

    @Override
    public boolean canContinueToUse() {
        return this.unit.getVehicle() == this.vehicle
                && this.vehicle != null
                && this.vehicle.getFirstPassenger() == this.unit
                && !this.vehicle.isWreck()
                && isHelicopter();
    }

    @Override
    public void stop() {
        if (this.vehicle != null) releaseInputs();
        this.vehicle = null;
        this.hoverTargetY = Double.NaN;
        this.allyAssist.clear();
    }

    @Override
    public void tick() {
        // Sub-10% health: the engine flies it into the ground on its own. Let go.
        float max = this.vehicle.getMaxHealth();
        if (max > 0.0F && this.vehicle.getHealth() < max * CRASH_HEALTH_FRACTION) {
            releaseInputs();
            return;
        }
        // No power to the rotor — inputs do nothing anyway; don't pretend to fly.
        if (this.vehicle.getEnergy() <= 0) {
            releaseInputs();
            return;
        }

        BlockPos dest = VehicleTargeting.resolveDestination(this.unit, this.vehicle, this.allyAssist);
        if (dest == null) {
            holdHover(); // nowhere to be — hold current altitude and station
            return;
        }

        double dx = (dest.getX() + 0.5) - this.vehicle.getX();
        double dz = (dest.getZ() + 0.5) - this.vehicle.getZ();
        double horizDistSq = dx * dx + dz * dz;

        LivingEntity combatTarget = this.unit.getTarget();
        if (combatTarget != null) {
            double orbitBand = orbitRadius() + ORBIT_DEADBAND;
            if (horizDistSq <= orbitBand * orbitBand) {
                orbit(dest); // in the ring — circle the target
            } else {
                transit(dest); // beyond the ring — close in
            }
            return;
        }

        // Non-combat destination (move order, follow, formation, ally assist).
        if (horizDistSq <= ARRIVE_RADIUS * ARRIVE_RADIUS) {
            holdHover(); // arrived — loiter here
        } else {
            transit(dest);
        }
    }

    // Fly a straight leg toward the destination at cruise altitude.
    private void transit(BlockPos dest) {
        this.hoverTargetY = Double.NaN;
        flyToward(dest.getX() + 0.5, dest.getZ() + 0.5, cruiseAltitude());
    }

    // Circle the target: aim at a point a fixed angle ahead of the hull's current
    // bearing around the target, on the standoff ring. Steering toward a point always
    // slightly ahead traces a smooth orbit using the same yaw+forward loop as transit.
    private void orbit(BlockPos target) {
        this.hoverTargetY = Double.NaN;
        double cx = target.getX() + 0.5;
        double cz = target.getZ() + 0.5;
        double bearing = Math.atan2(this.vehicle.getZ() - cz, this.vehicle.getX() - cx);
        double lead = bearing + this.orbitDir * ORBIT_LEAD_RAD;
        double radius = orbitRadius();
        double steerX = cx + Math.cos(lead) * radius;
        double steerZ = cz + Math.sin(lead) * radius;
        flyToward(steerX, steerZ, cruiseAltitude());
    }

    // The one control primitive: hold altitude with the collective, yaw the nose onto
    // the steer point, and pitch forward once aligned. Every input is set explicitly
    // so nothing stale carries over (the analog inputs also decay, hence re-asserting).
    private void flyToward(double steerX, double steerZ, double desiredY) {
        applyCollective(desiredY);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setHoverMode(false); // need full control authority while moving

        Vec3 toSteer = new Vec3(steerX - this.vehicle.getX(), 0, steerZ - this.vehicle.getZ());
        if (toSteer.lengthSqr() > 1.0E-6) toSteer = toSteer.normalize();
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double yawErr = getAngleBetween(forward, toSteer);

        this.vehicle.setMouseMoveSpeedX((float) Mth.clamp(YAW_GAIN * yawErr, -1.0, 1.0));
        this.vehicle.setMouseMoveSpeedY(Math.abs(yawErr) < ALIGN_THRESHOLD_RAD ? FORWARD_PITCH : 0.0F);
    }

    // Hold a fixed hover: capture the altitude on entry, let hover mode auto-level and
    // damp drift, and keep the collective trimming toward the captured height.
    private void holdHover() {
        if (Double.isNaN(this.hoverTargetY)) this.hoverTargetY = this.vehicle.getY();
        applyCollective(this.hoverTargetY);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setMouseMoveSpeedX(0.0F);
        this.vehicle.setMouseMoveSpeedY(0.0F);
        this.vehicle.setHoverMode(true);
    }

    // Collective: climb toward desiredY, descend away from it, coast within the
    // deadband. This is also the takeoff path — on the ground it just climbs until
    // airborne. forwardInputDown is the collective here, NOT translation.
    private void applyCollective(double desiredY) {
        double dy = desiredY - this.vehicle.getY();
        double deadband = SewvConfig.HELI_ALT_DEADBAND.get();
        boolean climb = dy > deadband;
        boolean descend = dy < -deadband;
        this.vehicle.setForwardInputDown(climb);
        this.vehicle.setDownInputDown(descend);
    }

    private void releaseInputs() {
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setDownInputDown(false);
        this.vehicle.setMouseMoveSpeedX(0.0F);
        this.vehicle.setMouseMoveSpeedY(0.0F);
    }

    // Target hold altitude = terrain surface under the hull + configured clearance.
    private double cruiseAltitude() {
        int surface = this.unit.level().getHeight(
                Heightmap.Types.WORLD_SURFACE, this.vehicle.getBlockX(), this.vehicle.getBlockZ());
        return surface + SewvConfig.HELI_CRUISE_ALTITUDE.get();
    }

    private double orbitRadius() {
        return SewvConfig.HELI_ORBIT_RADIUS.get();
    }

    // Signed horizontal angle (radians) to rotate `forward` onto `target`; same
    // convention as DriveVehicleGoal so the yaw sign matches the ground goal's steering.
    private double getAngleBetween(Vector3f forward, Vec3 target) {
        double cross = forward.x * target.z - forward.z * target.x;
        double dot = forward.x * target.x + forward.z * target.z;
        return -Math.atan2(cross, dot);
    }

    private boolean isHelicopter() {
        if (this.vehicle != this.helicopterCacheVehicle) {
            this.helicopterCacheVehicle = this.vehicle;
            this.helicopterCacheValue = this.vehicle.getEngineInfo() instanceof EngineInfo.Helicopter;
        }
        return this.helicopterCacheValue;
    }
}

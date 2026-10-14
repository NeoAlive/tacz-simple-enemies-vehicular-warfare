package com.neoalive.tacz_sewv.compat.lv;

import java.util.EnumSet;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.vehicle.control.ControlUnit;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons.TargetCategory;

/**
 * Flies a Limitless Vehicles rotorcraft from seat 0. Nothing of ours flies an LV aircraft
 * otherwise: the SBW helicopter code writes SBW hull motion directly, and LV's own flight model is
 * a different machine, so this is a small autopilot written against it.
 *
 * <p>How LV's {@code RotaryWingVehicle.tickMove} answers, which is all this relies on:
 * <ul>
 *   <li><b>Lift</b> is along the rotor's up-axis, scaled by engine power × collective pitch
 *       (0-100); full power at ~71 collective hovers. The collective is set directly every tick
 *       ({@code setCollectivePitch}, public) by a PI loop on vertical speed, rather than through
 *       LV's ±5-per-tick up/down keys, which are far too coarse to hold a height.</li>
 *   <li><b>Heading</b>: with no yaw keys held LV slews the hull onto {@code controlUnit.yRot}.</li>
 *   <li><b>Speed</b>: with no pitch keys held LV slews the hull onto {@code controlUnit.xRot};
 *       nose-down tilts the lift forward, so pitch IS the throttle. Roll coordinates itself.</li>
 * </ul>
 * {@code hoverMode} is never used: it overwrites both aim angles with the current attitude.
 *
 * <p>What it decides: transit to the order's destination at a cruise height over the terrain under
 * and ahead of it; in a fight, hold a standoff ring with the nose on the target, so fixed rockets
 * bear and the crew's stations ({@link LvGunnerGoal}) have a stable platform; with nowhere to go,
 * land. Badly hurt, it opens the range. No chunk tickets — an LV helicopter that outflies loaded
 * chunks freezes there like any other entity.
 */
final class LvRotorGoal extends Goal {

    /** Height over terrain in transit, and on station in a fight. */
    private static final double CRUISE_HEIGHT = 28.0;
    private static final double COMBAT_HEIGHT = 18.0;
    /** Below this height over the ground the hull only climbs — no forward pitch near the trees. */
    private static final double LIFTOFF_HEIGHT = 6.0;
    /** Blocks of track ahead whose terrain also sets the floor. */
    private static final double TERRAIN_LOOKAHEAD_TICKS = 30.0;

    /** Collective that hovers at full engine power (LV: mainRotorForce = 1.4 g·m). */
    private static final float HOVER_COLLECTIVE = 100.0F / 1.4F;
    private static final float COLLECTIVE_GAIN = 160.0F;   // per block/tick of vertical-speed error
    private static final float COLLECTIVE_INTEGRAL_GAIN = 0.6F;
    private static final float COLLECTIVE_INTEGRAL_MAX = 30.0F;
    private static final double MAX_CLIMB = 0.35;          // blocks/tick
    private static final double MAX_SINK = 0.25;
    private static final double LANDING_SINK = 0.12;

    private static final double MAX_SPEED = 0.7;           // blocks/tick, under LV's maxAirSpeed of 1
    private static final double SPEED_PER_BLOCK = 0.025;   // approach slows in over the last ~28 blocks
    private static final float PITCH_GAIN = 45.0F;         // degrees per block/tick of speed error
    private static final float MAX_NOSE_DOWN = 20.0F;
    private static final float MAX_NOSE_UP = 15.0F;
    /** Turn onto the bearing before building speed: past this the commanded speed is cut. */
    private static final float TURN_FIRST_DEG = 35.0F;

    private static final double ARMOUR_RING = 40.0;
    private static final double TROOP_RING = 30.0;
    private static final double RING_BAND = 8.0;
    private static final float HURT_FRACTION = 0.35F;
    private static final double HURT_EXTRA_RANGE = 30.0;

    private final AbstractUnit unit;
    private RotaryWingVehicle hull;
    private float collectiveIntegral;

    LvRotorGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!LvCompat.aiEnabled()) return false;
        if (!(this.unit.getVehicle() instanceof RotaryWingVehicle v)) return false;
        if (LvHulls.kindOf(v) != LvHulls.Kind.ROTARY || v.isDestroyed()) return false;
        if (LvHulls.seat0Occupant(v) != this.unit) return false;
        if (this.hull != v) this.collectiveIntegral = 0.0F;
        this.hull = v;
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
        return true; // a flight loop at half rate overshoots every correction
    }

    @Override
    public void tick() {
        LvSupply.topUpFuel(this.hull);
        ControlUnit c = this.hull.controlUnit;
        // Every key released: the aim angles and the collective do the flying.
        c.forward = c.backward = c.left = c.right = c.up = c.down = false;
        c.leftYaw = c.rightYaw = false;
        c.xRotKeep = c.yRotKeep = false;
        this.hull.hoverMode = false;

        LivingEntity target = LvOrders.liveTarget(this.unit);
        if (target != null && (!LvOrders.positional(this.unit) || LvHulls.writtenOff(this.hull, HURT_FRACTION))) {
            fight(target);
            return;
        }
        BlockPos dest = LvOrders.destination(this.unit);
        if (dest == null) {
            land();
        } else {
            flyTo(Vec3.atBottomCenterOf(dest), CRUISE_HEIGHT, null);
        }
    }

    // ---------------------------------------------------------------- behaviours

    private void fight(LivingEntity target) {
        Vec3 tp = target.getVehicle() != null ? target.getVehicle().position() : target.position();
        boolean armour = VehicleWeapons.classifyTarget(target) == TargetCategory.VEHICLE;
        double ring = armour ? ARMOUR_RING : TROOP_RING;
        if (LvHulls.writtenOff(this.hull, HURT_FRACTION)) ring += HURT_EXTRA_RANGE;
        Vec3 away = new Vec3(this.hull.getX() - tp.x, 0.0, this.hull.getZ() - tp.z);
        if (away.lengthSqr() < 1.0E-4) away = new Vec3(1.0, 0.0, 0.0);
        double dist = away.length();
        Vec3 station = tp.add(away.normalize().scale(ring));
        // On the ring: hold position and face the target. Off it: fly to the ring point.
        boolean onStation = Math.abs(dist - ring) <= RING_BAND;
        flyTo(onStation ? this.hull.position() : station, COMBAT_HEIGHT, tp);
    }

    private void land() {
        double ground = groundY(this.hull.getX(), this.hull.getZ());
        if (this.hull.onGround()) {
            this.hull.setCollectivePitch(0.0F); // settled: unload the rotor so it stays down
            this.collectiveIntegral = 0.0F;
            aim(0.0F, this.hull.getYRot());
            return;
        }
        double height = this.hull.getY() - ground;
        holdVerticalSpeed(-Math.min(MAX_SINK, Math.max(LANDING_SINK, height * 0.03)));
        holdHorizontal(Vec3.ZERO, this.hull.getYRot());
    }

    /**
     * Fly to {@code point} at {@code height} over the terrain, nose along the track — or onto
     * {@code faceAt} once there, when given.
     */
    private void flyTo(Vec3 point, double height, @Nullable Vec3 faceAt) {
        Vec3 pos = this.hull.position();
        Vec3 vel = this.hull.getDeltaMovement();
        double floor = Math.max(groundY(pos.x, pos.z),
                groundY(pos.x + vel.x * TERRAIN_LOOKAHEAD_TICKS, pos.z + vel.z * TERRAIN_LOOKAHEAD_TICKS));
        floor = Math.max(floor, groundY(point.x, point.z));
        double altitude = floor + height;
        holdVerticalSpeed(Mth.clamp((altitude - pos.y) * 0.08, -MAX_SINK, MAX_CLIMB));

        Vec3 to = new Vec3(point.x - pos.x, 0.0, point.z - pos.z);
        double dist = to.length();
        // Taking off: straight up until clear of the ground clutter, then transit.
        boolean lifting = pos.y - groundY(pos.x, pos.z) < LIFTOFF_HEIGHT && altitude - pos.y > 2.0;
        if (dist < 4.0 || lifting) {
            float heading = faceAt != null ? yawTo(pos, faceAt) : this.hull.getYRot();
            holdHorizontal(Vec3.ZERO, heading);
            return;
        }
        float heading = yawTo(pos, point);
        double speed = Math.min(MAX_SPEED, dist * SPEED_PER_BLOCK);
        holdHorizontal(to.normalize().scale(speed), heading);
    }

    // ---------------------------------------------------------------- control loops

    /** PI on vertical speed, written straight into the collective. */
    private void holdVerticalSpeed(double targetVy) {
        double vy = this.hull.getDeltaMovement().y;
        float error = (float) (targetVy - vy);
        this.collectiveIntegral = Mth.clamp(this.collectiveIntegral + error * COLLECTIVE_INTEGRAL_GAIN,
                -COLLECTIVE_INTEGRAL_MAX, COLLECTIVE_INTEGRAL_MAX);
        float powerFraction = Math.max(0.2F, this.hull.getPower() / 100.0F);
        float collective = HOVER_COLLECTIVE / powerFraction + COLLECTIVE_GAIN * error + this.collectiveIntegral;
        this.hull.setCollectivePitch(Mth.clamp(collective, 0.0F, 100.0F));
    }

    /**
     * Turn onto {@code heading} and pitch for {@code wantedVel} along it. A velocity behind the
     * nose reads as a speed of zero along the track, so the hull turns first and then accelerates.
     */
    private void holdHorizontal(Vec3 wantedVel, float heading) {
        Vec3 fwd = Vec3.directionFromRotation(0.0F, this.hull.getYRot());
        Vec3 vel = this.hull.getDeltaMovement();
        double forwardSpeed = vel.x * fwd.x + vel.z * fwd.z;
        double wanted = wantedVel.x * fwd.x + wantedVel.z * fwd.z;
        if (Math.abs(Mth.wrapDegrees(heading - this.hull.getYRot())) > TURN_FIRST_DEG) wanted = 0.0;
        float pitch = Mth.clamp((float) (wanted - forwardSpeed) * PITCH_GAIN, -MAX_NOSE_UP, MAX_NOSE_DOWN);
        aim(pitch, heading);
    }

    private void aim(float pitch, float heading) {
        this.hull.controlUnit.xRot = pitch;
        this.hull.controlUnit.yRot = heading;
    }

    /** Vanilla yaw convention: the view vector is (-sin yaw, ·, cos yaw). */
    private static float yawTo(Vec3 from, Vec3 to) {
        return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
    }

    /** Terrain top at (x, z); the hull's own Y where the column is not loaded. */
    private double groundY(double x, double z) {
        Level level = this.hull.level();
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        if (!level.hasChunk(bx >> 4, bz >> 4)) return this.hull.getY() - CRUISE_HEIGHT;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
    }

    @Override
    public void stop() {
        if (this.hull != null) {
            // Release the aim angles; LV keeps the last collective, and resets the controls itself
            // once seat 0 is empty.
            this.hull.controlUnit.reset();
        }
        this.hull = null;
        this.collectiveIntegral = 0.0F;
    }
}

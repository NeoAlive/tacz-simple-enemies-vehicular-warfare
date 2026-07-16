package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.AutoAimableEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.IAiFireTracker;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import javax.annotation.Nullable;

/**
 * The one thing that picks a parked crew back up.
 *
 * <p>Every hold branch in {@link DriveVehicleGoal}'s doctrine is terminal: it stops the hull,
 * which is correct for "I chose to stop" and catastrophic for "I am achieving nothing",
 * because those are the same state as far as the goal can tell. The stuck detector can't
 * help — it measures hull motion, which is zero in every deliberate park by definition. So a
 * crew holding a live target it physically cannot hit sits there forever: the turret pins at
 * its elevation stop, SBW's 4° fire gate never passes, SBW never re-evaluates whether the
 * target was reachable, and both tanks in a duel do this at once.
 *
 * <p>Two detectors, one response:
 *
 * <ul>
 * <li>the <b>arc check</b> is immediate and specific — the target is outside the turret's
 *     elevation envelope, so no amount of waiting will produce a shot;</li>
 * <li>the <b>silence watchdog</b> is slow and cause-agnostic — we are holding a target and
 *     no shots are coming out, whatever the reason. It exists because the causes we HAVEN'T
 *     diagnosed matter as much as the ones we have, and because it is the only thing that
 *     sees targets set by SEM's HurtByTargetGoal (those bypass {@link VehicleTargetScanGoal}
 *     entirely, so nothing in the scan goal could ever notice them).</li>
 * </ul>
 *
 * <p>The response is always the same: walk around the ring to different ground and try
 * again. The crew never abandons the target — it shuffles until it can shoot.
 */
final class StalemateBreaker {

    /**
     * Swing far enough around the ring to reach genuinely different ground — the only lever
     * that fixes an elevation problem is a different height difference — but not so far that
     * the arc sweeps through the enemy on the way.
     */
    private static final double BREAKER_ORBIT_RAD = Math.toRadians(60.0);

    /**
     * Opening the range flattens the elevation angle (atan(dy/dist)), so it can rescue a
     * MARGINAL arc violation. Only ever applied for arc violations — a plain silence is not a
     * distance problem and stepping out would just fight the doctrine's own bands. Bounded so
     * 40+12=52 stays well inside the 96-block scan radius: orbiting out past our own
     * acquisition range would drop the very target we are repositioning for.
     */
    private static final double BREAKER_RADIUS_STEP = 12.0;

    /**
     * Long enough for a tracked hull to pivot AND translate — the turn ramp alone eats much
     * of it, and an episode that ends mid-pivot has changed nothing.
     */
    private static final int REPOSITION_TICKS = 120;

    /**
     * canAim() measures the straight line, but the turret aims at the BALLISTIC solution,
     * which sits above it — at the depression stop (the common case) that makes canAim
     * slightly pessimistic. Widen the arc so we only declare failure when clearly outside.
     */
    private static final double ARC_SLACK_DEG = 2.0;

    /** The geometry moves slowly and getShootPos() isn't free; 10 ticks is still a ~0.5s reaction. */
    private static final int ARC_CHECK_INTERVAL = 10;

    /** SBW's own fallback elevation envelope, used when the hull's data can't be read. */
    private static final float DEFAULT_TURRET_MIN_PITCH = -10.0F;
    private static final float DEFAULT_TURRET_MAX_PITCH = 30.0F;

    private final AbstractUnit unit;
    private VehicleEntity vehicle;

    private LivingEntity target;
    private long anchorTick = Long.MIN_VALUE;
    private int repositionTicksLeft;
    private boolean orbitLeft;
    private boolean arcViolation;
    private int arcCheckCooldown;

    // Cached for the same reason as HullFacts: getTurretMinPitch()/getTurretMaxPitch() route
    // through computed() → VehicleData.compute(), and the arc check reads them repeatedly.
    private float turretMinPitch = DEFAULT_TURRET_MIN_PITCH;
    private float turretMaxPitch = DEFAULT_TURRET_MAX_PITCH;

    StalemateBreaker(AbstractUnit unit) {
        this.unit = unit;
    }

    void attach(VehicleEntity v) {
        if (this.vehicle == v) return;
        this.vehicle = v;
        try {
            this.turretMinPitch = v.getTurretMinPitch();
            this.turretMaxPitch = v.getTurretMaxPitch();
        } catch (Exception ignored) {
            this.turretMinPitch = DEFAULT_TURRET_MIN_PITCH;
            this.turretMaxPitch = DEFAULT_TURRET_MAX_PITCH;
        }
    }

    /**
     * Where the hull should drive to break the stalemate, or null when there is nothing to
     * break and the caller keeps its own doctrine.
     *
     * @param ringRadius the ring the doctrine wants this crew to hold against this target
     */
    @Nullable
    BlockPos update(LivingEntity target, BlockPos combatPos, double ringRadius) {
        if (!SewvConfig.STALEMATE_BREAKER_ENABLED.get()) return null;
        // Without the fire stamp there is no way to tell a working crew from a stalled one,
        // and guessing wrong here means orbiting every crew in the world forever — so fail
        // safe to the old hold-the-ring behaviour rather than to the intervention.
        if (!(this.vehicle instanceof IAiFireTracker tracker)) return null;

        long now = this.unit.level().getGameTime();

        // New engagement — fresh anchor, and pick an orbit direction to keep for its whole
        // life. Deliberately NOT alternated per episode the way unstick swings are: flipping
        // would rock the hull back and forth over the same ground, when the entire point is to
        // reach ground with a different height difference to the target. Id parity is free,
        // stable, and splits a platoon both ways around a shared target on its own.
        if (target != this.target) {
            this.target = target;
            this.anchorTick = now;
            this.repositionTicksLeft = 0;
            this.arcViolation = false;
            this.orbitLeft = (this.unit.getId() & 1) == 0;
        }

        // A shot is the definition of "not stuck" — re-anchor and stand down.
        long lastShot = tracker.tacz_sewv$getLastAiShotTick();
        if (lastShot != IAiFireTracker.NEVER && lastShot > this.anchorTick) {
            this.anchorTick = lastShot;
            this.repositionTicksLeft = 0;
            return null;
        }

        if (this.repositionTicksLeft > 0) {
            this.repositionTicksLeft--;
            // Episode over: re-anchor so the crew settles and actually tries to shoot from the
            // new ground before we conclude anything. Without this the silence test — which
            // never sees a shot — would hold the hull in a permanent orbit.
            if (this.repositionTicksLeft == 0) this.anchorTick = now;
            return orbitPoint(combatPos, ringRadius);
        }

        if (this.arcCheckCooldown > 0) this.arcCheckCooldown--;
        else {
            this.arcCheckCooldown = ARC_CHECK_INTERVAL;
            this.arcViolation = !targetWithinTurretArc(target);
        }

        // Math.max folds in the NEVER sentinel: anchorTick is always a real game time while an
        // engagement is live, so a crew that has never landed a shot at all — the actual
        // stalemate — measures its silence from when it acquired the target. Never subtract the
        // sentinel directly; now - Long.MIN_VALUE overflows negative and reads as "fired in the
        // future", which would silence this watchdog permanently.
        long anchor = Math.max(this.anchorTick, lastShot);
        boolean silent = now - anchor > SewvConfig.STALEMATE_SILENCE_TICKS.get();

        if (!this.arcViolation && !silent) return null;

        this.repositionTicksLeft = REPOSITION_TICKS;
        return orbitPoint(combatPos, ringRadius);
    }

    /**
     * A point around the ring at a new bearing.
     *
     * <p>Measured from where the hull stands RIGHT NOW, so it slides ahead as the hull closes
     * on it — deliberately. Chasing a point that stays 60° around the ring is what makes the
     * hull actually circle the target for the whole episode; pinning it to a fixed spot would
     * have the crew arrive early and park again, which is the state we are trying to escape.
     */
    private BlockPos orbitPoint(BlockPos combatPos, double ringRadius) {
        double radius = ringRadius + (this.arcViolation ? BREAKER_RADIUS_STEP : 0.0);
        return VehicleTargeting.computeStandoffPoint(this.vehicle, combatPos, radius,
                this.orbitLeft ? BREAKER_ORBIT_RAD : -BREAKER_ORBIT_RAD);
    }

    /**
     * Is the target inside the turret's elevation envelope at all? SBW clamps the turret to
     * [TurretPitchRange] with Mth.clamp, which SATURATES — so a target above or below the arc
     * pins the barrel at the stop and the angle to it never falls under SBW's hard-coded 4°
     * fire gate. It simply aims forever. The T-90A depresses only 5°, so any real slope does
     * this.
     *
     * <p>NECESSARY, NOT SUFFICIENT: canAim is pitch-only and ignores ballistic drop, so a
     * target inside the arc may still be unhittable — that's what the silence watchdog is for.
     * This only catches the case we can prove instantly.
     */
    private boolean targetWithinTurretArc(LivingEntity target) {
        try {
            // The muzzle, matching the origin SBW's own 4° gate measures from.
            Vec3 muzzle = this.vehicle.getShootPos(this.unit, 1.0F);
            return AutoAimableEntity.canAim(muzzle, target,
                    this.turretMinPitch - ARC_SLACK_DEG,
                    this.turretMaxPitch + ARC_SLACK_DEG);
        } catch (Exception e) {
            return true; // can't read the hull — assume reachable and let the watchdog decide
        }
    }

    /** Drop engagement state so a fresh one starts clean. */
    void clear() {
        this.vehicle = null;
        this.target = null;
        this.anchorTick = Long.MIN_VALUE;
        this.repositionTicksLeft = 0;
        this.arcViolation = false;
        this.arcCheckCooldown = 0;
    }
}

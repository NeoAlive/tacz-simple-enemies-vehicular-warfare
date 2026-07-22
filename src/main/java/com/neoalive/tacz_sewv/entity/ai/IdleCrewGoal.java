package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.CrewRadio;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.EnumSet;

/**
 * What a crew does between fights that isn't driving: sweeps the turret across the horizon and
 * talks on the radio. The driving half is {@link IdleSupport}, fed to the ordinary drive goal.
 *
 * <p>Its own goal rather than a branch in {@link DriveVehicleGoal} because that goal is not running
 * for either case this covers: it stops when there is no destination (every dwell pause), and it
 * never starts on a FIXED emplacement — a parked TOW or naval mount should still scan and chatter.
 *
 * <p>Claims no flags. It writes turret angles (SBW state, not the mob's) and plays a sound; it must
 * never contend with SEM's ladder or with boarding/bailing.
 */
public class IdleCrewGoal extends Goal {

    // How long one bearing is held before a new one is rolled. Long enough that the turret is
    // visibly slewing and settling rather than twitching.
    private static final int SWEEP_TICKS = 60;
    private static final int SWEEP_JITTER = 80;
    // The sweep stays near level: a turret pointing at the sky reads as broken, not as watchful.
    private static final float SWEEP_PITCH = 5.0F;

    private final AbstractUnit unit;
    private VehicleEntity vehicle;

    private long idleSince;
    private long nextSweep;
    private Vec3 bearing = Vec3.ZERO;

    public IdleCrewGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (!(this.unit.getVehicle() instanceof VehicleEntity v) || v.isWreck()) return false;
        if (this.unit.getTarget() != null) return false;
        // One speaker and one turret writer per hull: the driver talks, the turret's own controller
        // sweeps. On most hulls that is the same seat; on FCP's BMPs it is not.
        if (v.getFirstPassenger() != this.unit && !isTurretController(v)) return false;
        this.vehicle = v;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.vehicle != null
                && this.unit.getVehicle() == this.vehicle
                && !this.vehicle.isWreck()
                && this.unit.getTarget() == null;
    }

    @Override
    public void start() {
        // Game time, not a tick counter: goals tick every other game tick, so a counter would make
        // the configured delay silently 2x.
        this.idleSince = this.unit.level().getGameTime();
        this.nextSweep = this.idleSince;
    }

    @Override
    public void stop() {
        this.vehicle = null;
    }

    @Override
    public void tick() {
        long now = this.unit.level().getGameTime();
        sweepTurret(now);

        if (now - this.idleSince < SewvConfig.IDLE_VOICELINE_DELAY_TICKS.get()) return;
        float floor = SewvConfig.IDLE_VOICELINE_HEALTH_FRACTION.get().floatValue();
        if (this.vehicle.getHealth() < floor * this.vehicle.getMaxHealth()) return;
        // Safe to call every tick: CrewRadio holds the per-line and per-hull cooldowns that pace it.
        CrewRadio.play(this.vehicle, CrewRadio.Line.IDLE);
    }

    /**
     * Points the turret somewhere new every few seconds. {@code turretAutoAimFromVector} is SBW's own
     * slew — it ramps at the hull's turret traverse speeds, clamps to its arcs and plays the traverse
     * sound — so this only has to keep handing it a bearing.
     *
     * <p>Uncontended: SBW's tick aims an AI-crewed turret through {@code turretAutoAimFromUuid}, and
     * SBW clears that UUID to "undefined" the moment the controller's target goes null (its
     * {@code LivingChangeTargetEvent} handler), which makes the call return before touching anything.
     */
    private void sweepTurret(long now) {
        if (!this.vehicle.hasTurret() || !isTurretController(this.vehicle)) return;
        if (now >= this.nextSweep) {
            this.nextSweep = now + SWEEP_TICKS + this.unit.getRandom().nextInt(SWEEP_JITTER);
            float yaw = this.unit.getRandom().nextFloat() * 360.0F;
            float pitch = (this.unit.getRandom().nextFloat() * 2.0F - 1.0F) * SWEEP_PITCH;
            this.bearing = Vec3.directionFromRotation(pitch, yaw);
        }
        this.vehicle.turretAutoAimFromVector(this.bearing);
    }

    private boolean isTurretController(VehicleEntity v) {
        return v.getNthEntity(v.getTurretControllerIndex()) == this.unit;
    }
}

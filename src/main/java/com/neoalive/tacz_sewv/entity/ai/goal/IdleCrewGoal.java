package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.entity.ai.utility.Facts;

/**
 * What a crew does between fights that isn't driving: sweeps the turret across the horizon and
 * talks on the radio. Idle chatter uses a scheduled deadline + jitter so formations do not chorus.
 */
public class IdleCrewGoal extends Goal {

    private static final int SWEEP_TICKS = 60;
    private static final int SWEEP_JITTER = 80;
    private static final float SWEEP_PITCH = 5.0F;
    /** Backoff when local airtime defers a soft line. */
    private static final int AIRTIME_BACKOFF = 40;
    private static final int AIRTIME_BACKOFF_JITTER = 60;

    private final AbstractUnit unit;
    private VehicleEntity vehicle;

    private long nextIdleAt;
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
        long now = this.unit.level().getGameTime();
        this.nextSweep = now;
        int delay = SewvConfig.IDLE_VOICELINE_DELAY_TICKS.get();
        int jitter = SewvConfig.IDLE_VOICELINE_INITIAL_JITTER_TICKS.get();
        this.nextIdleAt = now + delay + (jitter > 0 ? this.unit.getRandom().nextInt(jitter + 1) : 0);
    }

    @Override
    public void stop() {
        this.vehicle = null;
    }

    @Override
    public void tick() {
        long now = this.unit.level().getGameTime();
        sweepTurret(now);

        if (now < this.nextIdleAt) return;
        float floor = SewvConfig.IDLE_VOICELINE_HEALTH_FRACTION.get().floatValue();
        if (this.vehicle.getHealth() < floor * this.vehicle.getMaxHealth()) {
            // Dying hulls: push the deadline out so we don't spin every tick.
            this.nextIdleAt = now + 40;
            return;
        }

        boolean played = CrewRadio.playIdle(this.vehicle);
        int base = SewvConfig.IDLE_VOICELINE_REPEAT_BASE_TICKS.get();
        int jitter = SewvConfig.IDLE_VOICELINE_REPEAT_JITTER_TICKS.get();
        if (played) {
            this.nextIdleAt = now + base + (jitter > 0 ? this.unit.getRandom().nextInt(jitter + 1) : 0);
        } else {
            // Airtime / overlap defer — short backoff so dense packs stagger instead of spinning.
            this.nextIdleAt = now + AIRTIME_BACKOFF
                    + this.unit.getRandom().nextInt(AIRTIME_BACKOFF_JITTER + 1);
        }
    }

    private void sweepTurret(long now) {
        if (!this.vehicle.hasTurret() || !isTurretController(this.vehicle)) return;

        Facts facts = null;
        if (this.vehicle.getFirstPassenger() instanceof AbstractUnit driver) {
            facts = Facts.of(driver.getId());
        }
        if (facts == null) {
            facts = Facts.of(this.unit.getId());
        }
        if (facts != null && facts.outerGlanceBearing != null && now < facts.outerGlanceUntil) {
            this.vehicle.turretAutoAimFromVector(facts.outerGlanceBearing);
            this.nextSweep = Math.max(this.nextSweep, facts.outerGlanceUntil);
            return;
        }

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

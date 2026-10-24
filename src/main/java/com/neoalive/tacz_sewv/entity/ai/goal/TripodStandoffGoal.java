package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.compat.TripodCompat;
import com.neoalive.tacz_sewv.entity.ai.support.BailOutSupport;
import com.neoalive.tacz_sewv.entity.ai.support.EntrenchSupport;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleCrew;

/**
 * Keeps an RU/US unit on foot outside {@link TripodCompat#standoff()} of any tripod — a creeper
 * shying from a cat, but with the rifle still working (SEM's gun goal holds only LOOK).
 *
 * <p>Priority 0, MOVE: every other walk a unit can be on (invasion objective, trench, mortar,
 * scavenging) is priority 1, and at equal priority a running goal keeps the flag. A mortar claim
 * and a trench cell are dropped on start, so the crew does not walk back to them; the scavenge
 * goals refuse claims inside the ring for the same reason ({@link TripodCompat#blocksClaim}).
 *
 * <p>No line-of-sight test, unlike vanilla {@code AvoidEntityGoal}: a tripod behind a hill is still
 * in heat-ray range. Installed only when the mod is present; a seated unit is the hull's job
 * ({@code DriveVehicleGoal} / {@code DriveShipGoal}).
 */
public class TripodStandoffGoal extends Goal {

    private static final double SPEED = 1.2;

    private final AbstractUnit unit;
    @Nullable
    private Entity tripod;
    @Nullable
    private Vec3 dest;

    public TripodStandoffGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (VehicleCrew.suppressOnFootAi(this.unit)) return false;
        this.tripod = TripodCompat.threatening(this.unit, this.unit, TripodCompat.standoff());
        if (this.tripod == null) return false;
        this.dest = fleePoint(this.tripod.position());
        return this.dest != null;
    }

    @Override
    public void start() {
        MortarSupport.releaseClaim(this.unit);
        EntrenchSupport.clear(this.unit);
        if (this.unit.getNavigation().moveTo(this.dest.x, this.dest.y, this.dest.z, SPEED)) return;
        // The far point has no path (river, cliff): take vanilla's short flee step instead.
        Vec3 step = DefaultRandomPos.getPosAway(this.unit, 16, 7, this.tripod.position());
        if (step != null) this.unit.getNavigation().moveTo(step.x, step.y, step.z, SPEED);
    }

    @Override
    public boolean canContinueToUse() {
        return this.tripod != null && this.tripod.isAlive()
                && !this.unit.getNavigation().isDone()
                && !VehicleCrew.suppressOnFootAi(this.unit);
    }

    @Override
    public void stop() {
        this.unit.getNavigation().stop();
        this.tripod = null;
        this.dest = null;
    }

    /** A standable point past the ring on the unit's side; else vanilla's flee step, repeated per canUse. */
    @Nullable
    private Vec3 fleePoint(Vec3 threat) {
        BlockPos far = BailOutSupport.escapeFrom(this.unit, threat, TripodCompat.standoff() + TripodCompat.MARGIN);
        if (far != null) return Vec3.atBottomCenterOf(far);
        return DefaultRandomPos.getPosAway(this.unit, 16, 7, threat);
    }
}

package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.compat.AshMissileSupport;

/**
 * Stops, arms and fires an ASH coordinate missile system (Sapsan) for its SEM driver.
 *
 * <p>Sapsan has no SBW weapons — the pack fires through {@code togglePod} / {@code shootMissileTo}
 * only. Aimpoint is the unit's live target if it has one, otherwise its fire-mission BlockPos
 * (same ordering as {@link ManMortarGoal}). {@link DriveVehicleGoal} yields while this engages
 * so steering does not fight the park.
 */
public class ManMissileSystemGoal extends Goal {

    private static final int FIRE_COOLDOWN_TICKS = 100;

    private final AbstractUnit unit;
    private VehicleEntity hull;
    private long nextShotTime;

    public ManMissileSystemGoal(AbstractUnit unit) {
        this.unit = unit;
        // MOVE so DriveVehicleGoal (no flags, but still steers) is not the only writer —
        // we outrank SEM approach goals and park the hull ourselves.
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (!AshMissileSupport.shouldEngage(this.unit)) return false;
        if (!(this.unit.getVehicle() instanceof VehicleEntity v)) return false;
        this.hull = v;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.hull != null
                && this.unit.getVehicle() == this.hull
                && !this.hull.isWreck()
                && AshMissileSupport.shouldEngage(this.unit);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (this.hull == null) return;

        AshMissileSupport.stopMovement(this.hull);

        Vec3 aim = AshMissileSupport.aimpoint(this.unit);
        if (aim == null) return;

        // Face the aimpoint so the pod launch looks sensible.
        this.unit.getLookControl().setLookAt(aim.x, aim.y, aim.z);
        this.hull.setYRot(this.unit.getYRot());

        if (!AshMissileSupport.isPodToggled(this.hull)) {
            AshMissileSupport.arm(this.hull);
            return;
        }
        if (!AshMissileSupport.isPodRaised(this.hull)) {
            return; // still slewing up
        }

        long now = this.unit.level().getGameTime();
        if (now < this.nextShotTime) return;

        if (AshMissileSupport.fire(this.hull, aim)) {
            this.nextShotTime = now + FIRE_COOLDOWN_TICKS;
        }
    }

    @Override
    public void stop() {
        if (this.hull != null && this.unit.getVehicle() == this.hull) {
            AshMissileSupport.disarm(this.hull);
            AshMissileSupport.stopMovement(this.hull);
        }
        this.hull = null;
    }
}

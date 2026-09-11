package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.bridge.IMedicCaptured;
import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.entity.ai.support.GrenadeSupport;
import com.neoalive.tacz_sewv.entity.ai.support.MedicControl;
import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport;
import com.neoalive.tacz_sewv.entity.ai.support.UnitHolster;

/**
 * On-foot grenade throw: holster rifle → aim → spawn SBW projectile → restore.
 *
 * <p>Claims LOOK only so SEM's approach goal keeps MOVE. Priority 2 (below {@link AtWeaponGoal}).
 */
public class GrenadeThrowGoal extends Goal {

    private static final int AIM_TICKS = 12;

    private final AbstractUnit unit;
    private int aimTicks;
    private String pendingId;
    private boolean thrown;

    public GrenadeThrowGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        UnitHolster.sweepGrenadeStashIfStale(this.unit);

        if (this.unit.getVehicle() instanceof VehicleEntity) return false;
        if (this.unit.isPassenger()) return false;
        if (MedicControl.isTreating(this.unit)) return false;
        if (this.unit instanceof IPmcDowned downed && downed.sewv$isDownedSynced()) return false;
        if (this.unit instanceof IMedicCaptured captured && captured.sewv$isCapturedSynced()) return false;
        if (SmallArmsSupport.holdsLauncher(this.unit)) return false;
        if (UnitHolster.isThrowingGrenade(this.unit)) return false;
        if (GrenadeSupport.onCooldown(this.unit)) return false;

        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()) return false;
        if (!GrenadeSupport.inThrowRange(this.unit, target)) return false;
        if (!this.unit.getSensing().hasLineOfSight(target)) return false;

        return GrenadeSupport.pick(this.unit, target) != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.thrown) return false;
        if (this.unit.getVehicle() instanceof VehicleEntity) return false;
        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()) return false;
        if (this.pendingId == null) return false;
        return this.aimTicks <= AIM_TICKS + 2;
    }

    @Override
    public void start() {
        this.aimTicks = 0;
        this.thrown = false;
        this.pendingId = null;

        LivingEntity target = this.unit.getTarget();
        if (target == null) return;

        String id = GrenadeSupport.pick(this.unit, target);
        if (id == null) return;

        ItemStack grenade = GrenadeSupport.takeForThrow(this.unit, id);
        if (grenade.isEmpty()) return;

        this.pendingId = id;
        UnitHolster.beginGrenadeThrow(this.unit, grenade);
    }

    @Override
    public void stop() {
        UnitHolster.endGrenadeThrow(this.unit);
        this.pendingId = null;
        this.aimTicks = 0;
        this.thrown = false;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = this.unit.getTarget();
        if (target == null || this.pendingId == null) return;

        this.unit.getLookControl().setLookAt(target, 30.0F, 30.0F);
        faceTarget(target);

        if (!this.unit.getSensing().hasLineOfSight(target)) {
            this.aimTicks = 0;
            return;
        }

        if (this.aimTicks < AIM_TICKS) {
            this.aimTicks++;
            return;
        }

        if (this.thrown) return;
        if (GrenadeSupport.throwGrenade(this.unit, this.pendingId)) {
            GrenadeSupport.armCooldown(this.unit);
        }
        this.thrown = true;
        // stop() restores the rifle on the next goal teardown.
    }

    private void faceTarget(LivingEntity target) {
        double dx = target.getX() - this.unit.getX();
        double dz = target.getZ() - this.unit.getZ();
        double dy = target.getEyeY() - this.unit.getEyeY();
        double flat = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) (-(Mth.atan2(dy, flat) * Mth.RAD_TO_DEG));

        this.unit.setYRot(yaw);
        this.unit.yBodyRot = yaw;
        this.unit.yHeadRot = yaw;
        this.unit.setXRot(pitch);
    }
}

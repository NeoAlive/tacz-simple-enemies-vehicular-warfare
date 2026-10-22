package com.neoalive.tacz_sewv.entity.ai.goal;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.compat.OpenPacCompat;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.invasion.InvasionHostility;

/**
 * SEM's PMC ladder never scans Players or other PMCs — {@code Monster} even excludes
 * {@code target.getClass() == this.getClass()}. Invasion lists and Stage-4 OpenPAC {@code ENEMY}
 * therefore had no on-foot acquisition path. Mounted crews use {@link VehicleTargetScanGoal};
 * this covers the dismounted case and yields TARGET while seated so the vehicle scan owns it.
 *
 * @param <T> {@link Player} or {@link PmcUnitEntity}
 */
public class DiplomacyEnemyTargetGoal<T extends LivingEntity> extends NearestAttackableTargetGoal<T> {

    public DiplomacyEnemyTargetGoal(AbstractUnit unit, Class<T> targetClass) {
        super(unit, targetClass, 10, true, false, e -> isCandidate(unit, e));
    }

    private static boolean isCandidate(AbstractUnit unit, LivingEntity e) {
        if (e instanceof Player p && (p.isCreative() || p.isSpectator())) return false;
        return VehicleTargeting.isDiplomacyEnemy(unit, e);
    }

    @Override
    public boolean canUse() {
        if (this.mob.getVehicle() instanceof VehicleEntity) return false;
        // Super's body, with one gate between the random interval and the entity scan: nothing can
        // match without a stored diplomacy pair (manual or invasion overlay), OpenPAC parties, or a
        // stamped invasion enemy list, and on most worlds that is every PMC, every time.
        if (this.randomInterval > 0 && this.mob.getRandom().nextInt(this.randomInterval) != 0) return false;
        if (!OpenPacCompat.isLoaded()
                && DiplomacyData.get(this.mob.level()).isEmpty()
                && InvasionHostility.enemiesForShooter((AbstractUnit) this.mob).isEmpty()) {
            return false;
        }
        this.findTarget();
        return this.target != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.mob.getVehicle() instanceof VehicleEntity) return false;
        return super.canContinueToUse();
    }
}

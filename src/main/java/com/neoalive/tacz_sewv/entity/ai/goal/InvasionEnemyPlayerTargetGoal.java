package com.neoalive.tacz_sewv.entity.ai.goal;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * SEM's PMC target ladder never installs a {@code Player} scan — only RU/US do. Invasion (and
 * OpenPAC Stage-4 ENEMY) hostility therefore had nothing that would ever call {@code setTarget}
 * on an opposing player while on foot. Mounted crews use {@link VehicleTargetScanGoal}; this goal
 * covers the dismounted case and yields the TARGET flag while seated so the vehicle scan owns it.
 */
public class InvasionEnemyPlayerTargetGoal extends NearestAttackableTargetGoal<Player> {

    public InvasionEnemyPlayerTargetGoal(AbstractUnit unit) {
        super(unit, Player.class, 10, true, false,
                e -> e instanceof Player p
                        && !p.isCreative()
                        && !p.isSpectator()
                        && VehicleTargeting.isDiplomacyEnemy(unit, p));
    }

    @Override
    public boolean canUse() {
        if (this.mob.getVehicle() instanceof VehicleEntity) return false;
        return super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        if (this.mob.getVehicle() instanceof VehicleEntity) return false;
        return super.canContinueToUse();
    }
}

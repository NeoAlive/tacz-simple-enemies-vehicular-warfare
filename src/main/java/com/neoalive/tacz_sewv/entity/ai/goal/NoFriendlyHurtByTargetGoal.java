package com.neoalive.tacz_sewv.entity.ai.goal;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Replaces SEM's own priority-1 retaliation goal ({@code NoPlayerHurtByTargetGoal} for PMC, plain
 * {@code HurtByTargetGoal} for RU/US) with a version that also refuses a same-faction attacker.
 *
 * <p>Neither of SEM's originals excludes friendly fire at all — only the PMC one excludes players.
 * A unit standing near its own platoon that catches a squadmate's splash damage (mortar, cannon,
 * grenade) has {@code canUse()} attempt {@code mob.setTarget(attacker)} on a friendly, which
 * {@code MixinAbstractUnit}'s setTarget guard correctly cancels — but cancelling a call does not
 * tell {@code canUse()} it failed: it still returns {@code true}, so the goal becomes this unit's
 * ACTIVE target-selector goal with its actual target left null, which can starve every
 * lower-or-equal-priority goal (including the real enemy scan) for as long as the friendly-fire hits
 * keep coming — not just the repeated "TARGET_FRIENDLY" veto report this was originally chased for.
 *
 * <p>Mirrors SEM's own convention of resetting {@code mob.setTarget(null)} when a candidate is
 * rejected (see {@code NoPlayerHurtByTargetGoal}), so a vetoed candidate here behaves identically to
 * a vetoed player there instead of leaving the goal in the "active but pointed at nothing" state.
 */
public class NoFriendlyHurtByTargetGoal extends HurtByTargetGoal {

    private final AbstractUnit self;
    private final boolean excludePlayer;

    public NoFriendlyHurtByTargetGoal(AbstractUnit self, boolean excludePlayer) {
        super(self);
        this.self = self;
        this.excludePlayer = excludePlayer;
    }

    @Override
    public boolean canUse() {
        if (!super.canUse()) return false;

        LivingEntity attacker = this.mob.getLastHurtByMob();
        if (excludePlayer && attacker instanceof Player) {
            this.mob.setTarget(null);
            return false;
        }
        if (attacker != null && VehicleTargeting.isFriendly(self, attacker)) {
            this.mob.setTarget(null);
            return false;
        }
        return true;
    }
}

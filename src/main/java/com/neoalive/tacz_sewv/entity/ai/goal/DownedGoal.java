package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.entity.ai.support.OrderStandDown;

/**
 * Freezes a downed PMC in place and kills it for real once its bleed-out deadline
 * ({@link IPmcDowned#sewv$downedDeadline}) passes unrevived. {@code PmcDownedSupport.onDeath} is
 * what puts a unit into this state (cancels the killing blow, sets {@code isDowned}); this goal is
 * what happens for the rest of its life until either revived (external — a player's interact or
 * {@code PmcReviveGoal}'s medic, both call {@code PmcDownedSupport.revive}) or the clock runs out.
 *
 * <p>Priority 0 — must outrank every other goal on the unit, combat included: it claims MOVE, LOOK
 * and JUMP, and every action goal in this codebase needs at least one of those, so nothing else can
 * run while this is active. {@code TARGET} is deliberately not claimed (goal flags don't apply
 * across {@code goalSelector}/{@code targetSelector} anyway); the target is cleared directly instead
 * so a downed unit never reads as "targeting" something to allied logic while it cannot act on it.
 *
 * <p>Calls {@link net.minecraft.world.entity.LivingEntity#kill()} on expiry rather than clearing the
 * downed flag first: {@code kill()} routes through the normal hurt/death pipeline, so
 * {@code PmcDownedSupport.onDeath} sees it again — and because {@code isDowned} is still {@code true}
 * at that point, its own guard lets this second death proceed instead of re-cancelling it.
 *
 * <p>Also reasserts {@link IPmcDowned#sewv$setDownedSynced} every tick. That flag (not the durable
 * {@code sewv$isDowned}) is what the render-side bone-pose mixin reads, and — unlike the durable
 * NBT state — it is <b>not</b> restored across a chunk reload on its own (synced entity data is
 * live-runtime-only), so reasserting it here each tick is what makes it correct again the moment
 * this goal resumes, with no save/load hook of its own needed.
 */
public class DownedGoal extends Goal {

    private final PmcUnitEntity unit;

    public DownedGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return this.unit instanceof IPmcDowned downed && downed.sewv$isDowned();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        this.unit.getNavigation().stop();
        if (this.unit instanceof IPmcDowned) {
            OrderStandDown.clearAll(this.unit, "DownedGoal.start");
        } else {
            this.unit.setTarget(null);
        }
    }

    @Override
    public void tick() {
        this.unit.setTarget(null);
        if (!(this.unit instanceof IPmcDowned downed)) return;
        downed.sewv$setDownedSynced(true);
        if (this.unit.level().getGameTime() >= downed.sewv$downedDeadline()) {
            this.unit.kill();
        }
    }
}

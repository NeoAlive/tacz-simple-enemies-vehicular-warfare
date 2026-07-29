package com.neoalive.tacz_sewv.entity.ai;

import com.neoalive.tacz_sewv.bridge.ISweepInfantry;
import net.minecraft.world.entity.Mob;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

/**
 * Whether an on-foot PMC unit is under a "stay with me" / area order and so must fight from
 * where it stands instead of abandoning its commander (or assigned ground) to chase a target.
 *
 * <p><b>The straying this fixes is entirely SEM's own goal wiring.</b> A unit that acquires a
 * target runs straight down its combat goals:
 * {@link net.nekoyuni.SimpleEnemyMod.entity.ai.goals.CommanderOrderGoal} — the follow / hold /
 * formation goal — refuses to run while the unit has a live target (its canUse, canContinueToUse
 * and tick all bail on the same {@code getTarget()} read), and
 * {@link net.nekoyuni.SimpleEnemyMod.entity.ai.goals.MoveToAttackRangeGoal} (detection 96,
 * attack range ~88, speed 1.2) then walks the unit up to ~90 blocks toward that target. So a
 * unit told "follow me" drops the commander the instant it sees an enemy and only returns
 * ~5 s (SEM's {@code ticksSinceLastCombat} of 100) after the fight ends. SEM exposes no per-order
 * leash — only a global {@code UNIT_DETECTION_RANGE} that would also nerf enemy AI.
 *
 * <p>Two mixins consult this to close the two seams:
 * {@link com.neoalive.tacz_sewv.mixin.MixinMoveToAttackRangeGoal} suppresses the advance, and
 * {@link com.neoalive.tacz_sewv.mixin.MixinCommanderOrderGoal} keeps the follow goal live through
 * combat. Together the unit holds with its commander, takes local cover, and fires only when the
 * enemy is already in range.
 *
 * <p>Scoped to the positional orders CommanderOrderGoal governs (the same set SEM treats as
 * "isFollowOrder"): FOLLOW / HOLD / MOVE_TO_POSITION / the two FORM orders — plus a live
 * on-foot Sweep &amp; Advance ({@link ISweepInfantry}), which must not chase off its rectangle
 * either. FREE_FIRE and ATTACK_THAT_TARGET alone keep the full chase. Mounted crews are
 * excluded: their movement is the vehicle's ({@link PatrolSupport#holdsCourseThroughContact}).
 */
public final class FollowLeash {

    private FollowLeash() {}

    public static boolean leashed(Mob mob) {
        if (!(mob instanceof PmcUnitEntity pmc)) return false;
        if (pmc.getVehicle() != null) return false; // mounted: driven by the vehicle AI, not these goals
        if (((ISweepInfantry) pmc).sewv$hasInfantrySweep()) return true;
        OrderType order = pmc.getOrder();
        if (order == null) return false;
        return switch (order) {
            case FOLLOW_COMMANDER, HOLD_POSITION, MOVE_TO_POSITION, FORM_WEDGE, FORM_COLUMN -> true;
            default -> false;
        };
    }
}

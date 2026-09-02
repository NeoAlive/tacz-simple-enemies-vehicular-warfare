package com.neoalive.tacz_sewv.entity.ai.support;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IPathwayInfantry;
import com.neoalive.tacz_sewv.bridge.ISweepInfantry;
import com.neoalive.tacz_sewv.fob.FobSupport;

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
 * <p>Mixins consult this to close the seams:
 * {@link com.neoalive.tacz_sewv.mixin.MixinMoveToAttackRangeGoal} suppresses the long advance,
 * {@link com.neoalive.tacz_sewv.mixin.MixinCommanderOrderGoal} keeps the follow goal live through
 * combat, {@link com.neoalive.tacz_sewv.mixin.MixinSemOnFootTacticalGoals} blocks cover/maneuver
 * MOVE under {@link #ownsMove}, {@link com.neoalive.tacz_sewv.entity.ai.goal.FollowCommanderGoal}
 * is the hard FOLLOW_ME stick, and {@link com.neoalive.tacz_sewv.entity.ai.goal.MoveToPositionGoal}
 * is the hard MOVE stick — SEM's CommanderOrderGoal at priority 3 cannot win against combat alone.
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
        // Route to FOB is a forced MOVE: fire from where you stand, never chase off the route.
        if (FobSupport.hasRoutePending(pmc)) return true;
        if (((ISweepInfantry) pmc).sewv$hasInfantrySweep()) return true;
        if (((IPathwayInfantry) pmc).sewv$hasPathway()) return true;
        OrderType order = pmc.getOrder();
        if (order == null) return false;
        return switch (order) {
            case FOLLOW_COMMANDER, HOLD_POSITION, MOVE_TO_POSITION, FORM_WEDGE, FORM_COLUMN -> true;
            default -> false;
        };
    }

    /**
     * Stricter half of the leash: the unit must stay glued to the commander / formation slot,
     * not peel toward cover or a flank. True for FOLLOW_ME, the two FORM orders, and a live
     * infantry sweep. HOLD keeps {@link #leashed} (no chase) but still takes local cover;
     * MOVE suppresses peel only while {@link #enRouteToMove}.
     */
    public static boolean sticksToLeader(Mob mob) {
        if (!(mob instanceof PmcUnitEntity pmc)) return false;
        if (pmc.getVehicle() != null) return false;
        if (((ISweepInfantry) pmc).sewv$hasInfantrySweep()) return true;
        if (((IPathwayInfantry) pmc).sewv$hasPathway()) return true;
        OrderType order = pmc.getOrder();
        return order == OrderType.FOLLOW_COMMANDER
                || order == OrderType.FORM_WEDGE
                || order == OrderType.FORM_COLUMN;
    }

    /**
     * Still walking to a MOVE click. SEM arrives at {@code distSqr < 2.5}; until then cover /
     * tactical MOVE must not steal the transit. After arrival, local cover is allowed again.
     */
    public static boolean enRouteToMove(Mob mob) {
        if (!(mob instanceof PmcUnitEntity pmc)) return false;
        if (pmc.getVehicle() != null) return false;
        if (FobSupport.hasRoutePending(pmc)) return true;
        if (pmc.getOrder() != OrderType.MOVE_TO_POSITION) return false;
        Vec3 dest = pmc.getMoveToTarget();
        if (dest == null || dest.equals(Vec3.ZERO)) return false;
        return pmc.distanceToSqr(dest) >= 2.5D;
    }

    /** FOLLOW glue or an unfinished MOVE — locomotion belongs to the order, not combat peel. */
    public static boolean ownsMove(Mob mob) {
        return sticksToLeader(mob) || enRouteToMove(mob);
    }
}

package com.neoalive.tacz_sewv.entity.ai.utility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.util.Mth;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.entity.ai.command.Assignment;
import com.neoalive.tacz_sewv.entity.ai.command.CrewAssignment;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons.TargetCategory;
import com.neoalive.tacz_sewv.entity.ai.support.FireMissionSupport;

/**
 * Picks what one crew does next, and sticks with it.
 *
 * <p>Two jobs, both cheap. <b>Reaction</b> turns {@link Facts} into a utility score per
 * {@link Action} — a weighted sum, nothing more. <b>Planning</b> keeps the winner in place until it
 * is clearly beaten, because a vehicle that re-decides every second never finishes anything: a tank
 * needs a second just to begin turning, so a plan abandoned at one second was never executed at all.
 *
 * <p>Scoring is deliberately free of side effects and randomness — the same battlefield and the same
 * doctrine always produce the same plan. The only per-unit variation is which way a crew prefers to
 * flank, taken from its entity id so a platoon splits both ways around a shared target on its own.
 */
public final class TacticalBrain {

    private static final int MIN_PLAN_TICKS = 40;
    private static final double SWITCH_MARGIN = 10.0;
    private static final boolean DEBUG_LOGGING = false;

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How long a hit still counts as "recently hit", for the smoke and retreat signals. */
    private static final long RECENT_HIT_TICKS = 100;

    /**
     * Breaks the tie between the two flanks, which are otherwise scored identically. Small enough
     * that it can never outrank a real modifier — it only decides a coin flip that would otherwise
     * always land the same way and send every crew in the game around to the left.
     */
    private static final double FLANK_TIEBREAK = 0.5;

    private final Facts facts = new Facts();
    private final double[] signals = new double[Signal.VALUES.length];
    private final double[] scores = new double[Action.VALUES.length];
    /**
     * Which actions could be carried out at all this tick.
     *
     * <p>Kept beside the scores rather than encoded as a {@code -Infinity} score: "blocked" and
     * "scored badly" are different states, and a sentinel that has to be recognised by its value
     * leaks into everything that later reads a score — ranking, logging, and any future consumer.
     */
    private final boolean[] feasible = new boolean[Action.VALUES.length];

    private Action plan = Action.HOLD;
    private long planStarted = Long.MIN_VALUE;
    /** Game time of the last sample, so the feasibility gates can age the memory stamps. */
    private long lastSampledTick = Long.MIN_VALUE;

    /**
     * Re-read the battlefield and, if it is time, re-decide.
     *
     * @return true if the plan was re-scored this tick (the caller's cue to log or react)
     */
    public boolean update(AbstractUnit unit, VehicleEntity hull) {
        this.facts.bind(unit);
        Doctrine doctrine = Doctrine.forCrew(unit);
        if (!this.facts.refresh(unit, hull)) return false;

        long now = unit.level().getGameTime();
        UtilityWeights weights = UtilityWeights.active();

        // Order matters: the facts are projected onto signals, confidence is judged from those,
        // and only then are the actions scored — because confidence is itself an input to them.
        sample(unit, this.facts, now);
        this.facts.confidence = Confidence.evaluate(this.signals, doctrine, weights);
        this.signals[Signal.CONFIDENCE.ordinal()] = Confidence.asSignal(this.facts.confidence);

        decide(unit, doctrine, weights, now);
        return true;
    }

    /**
     * Same as {@link #update} but runs {@code posture.evaluate} after confidence is judged so
     * scoot/ambush can read confidence, then patches posture Signals before decide.
     */
    public boolean update(AbstractUnit unit, VehicleEntity hull, TacticalPosture posture) {
        this.facts.bind(unit);
        Doctrine doctrine = Doctrine.forCrew(unit);
        if (!this.facts.refresh(unit, hull)) return false;

        long now = unit.level().getGameTime();
        UtilityWeights weights = UtilityWeights.active();

        sample(unit, this.facts, now);
        this.facts.confidence = Confidence.evaluate(this.signals, doctrine, weights);
        this.signals[Signal.CONFIDENCE.ordinal()] = Confidence.asSignal(this.facts.confidence);

        posture.evaluate(unit, hull, this.facts, doctrine);
        patchPostureSignals(this.facts);

        decide(unit, doctrine, weights, now);
        return true;
    }

    private void patchPostureSignals(Facts f) {
        double[] s = this.signals;
        s[Signal.EXPOSED.ordinal()] = Mth.clamp(f.exposure, 0.0, 1.0);
        s[Signal.IN_COVER.ordinal()] = Mth.clamp(f.inCover, 0.0, 1.0);
        s[Signal.KEYHOLE.ordinal()] = Mth.clamp(f.keyholeQuality, 0.0, 1.0);
        s[Signal.RECENT_SHOT.ordinal()] = Mth.clamp(f.recentShot, 0.0, 1.0);
        s[Signal.ALLY_INFANTRY_NEAR.ordinal()] = Mth.clamp(f.alliedInfantryNear, 0.0, 1.0);
        s[Signal.POSTURE_SCOOT.ordinal()] = f.postureScoot ? 1.0 : 0.0;
        s[Signal.POSTURE_AMBUSH.ordinal()] = f.postureAmbush ? 1.0 : 0.0;
    }

    public Action plan() {
        return this.plan;
    }

    public Facts facts() {
        return this.facts;
    }

    /**
     * Override the plan from outside the scorer.
     *
     * <p>Exists for the stalemate breaker alone. That is a watchdog, not a tactic: a crew holding a
     * target it physically cannot hit must reposition whatever the weights say, or a bad number in a
     * datapack brings back the parked-forever bug the breaker was written to kill.
     */
    public void force(Action action, long now) {
        if (this.plan != action) {
            this.plan = action;
            this.planStarted = now;
        }
    }

    public void clear() {
        this.facts.clear();
        this.plan = Action.HOLD;
        this.planStarted = Long.MIN_VALUE;
        Arrays.fill(this.signals, 0.0);
    }

    /**
     * Project the facts onto the 0..1 (or -1..1) signals the weights multiply.
     *
     * <p>Everything is graded rather than a threshold flag: a crew at 40% health should be a little
     * more cautious than one at 60%, not identical to it until it crosses a line and then abruptly
     * flees. Graded signals are also what make the switch margin meaningful — a cliff would jump
     * straight past it.
     */
    private void sample(AbstractUnit unit, Facts f, long now) {
        double[] s = this.signals;
        Arrays.fill(s, 0.0);
        this.lastSampledTick = now;

        s[Signal.BASE.ordinal()] = 1.0;

        boolean hasTarget = f.target != null;
        s[Signal.ENEMY_VISIBLE.ordinal()] = hasTarget ? 1.0 : 0.0;
        s[Signal.ENEMY_ARMOR.ordinal()] = f.targetCategory == TargetCategory.VEHICLE ? 1.0 : 0.0;
        s[Signal.ENEMY_INFANTRY.ordinal()] =
                hasTarget && f.targetCategory != TargetCategory.VEHICLE ? 1.0 : 0.0;

        // CONFIDENCE is deliberately NOT set here — it is judged from this sample immediately
        // afterwards and written back by update(). See Confidence.

        // Both ramp from half-full to empty: above half neither is a concern worth scoring.
        s[Signal.LOW_HEALTH.ordinal()] = Mth.clamp(1.0 - f.health / 0.5, 0.0, 1.0);
        s[Signal.LOW_ENERGY.ordinal()] = Mth.clamp(1.0 - f.energy / 0.5, 0.0, 1.0);

        s[Signal.LOW_AMMO.ordinal()] = switch (f.ammo) {
            case OUT -> 1.0;
            case LOW -> 0.5;
            case OK -> 0.0;
        };

        s[Signal.OUTNUMBERED.ordinal()] =
                Mth.clamp(1.0 / Math.max(f.forceRatio, 1.0E-3) - 1.0, 0.0, 1.0);
        // Beyond the first, each enemy is more pressure — but the difference between four and ten
        // is not something a tank commander meaningfully distinguishes.
        s[Signal.THREAT_DENSITY.ordinal()] = Mth.clamp((f.enemies - 1) / 4.0, 0.0, 1.0);
        s[Signal.ALLIES_NEARBY.ordinal()] = Math.min(f.allies, 3) / 3.0;
        s[Signal.ALONE.ordinal()] = f.allies == 0 ? 1.0 : 0.0;

        s[Signal.LOST_CONTACT.ordinal()] = f.target == null && f.memory.hasFreshContact(now) ? 1.0 : 0.0;
        s[Signal.DISTANT_CONTACT.ordinal()] = f.outerSpotFresh ? f.outerSpotStrength : 0.0;
        s[Signal.UNDER_ORDERS.ordinal()] = f.underOrders ? 1.0 : 0.0;

        // rangeError is signed and already normalised against the preferred range, so one field
        // gives both directions and neither can be non-zero at the same time as the other.
        s[Signal.TOO_CLOSE.ordinal()] = hasTarget ? Mth.clamp(-f.rangeError, 0.0, 1.0) : 0.0;
        s[Signal.TOO_FAR.ordinal()] = hasTarget ? Mth.clamp(f.rangeError, 0.0, 1.0) : 0.0;

        long sinceHit = Facts.ticksSince(f.memory.lastDamageTick, now);
        s[Signal.RECENTLY_HIT.ordinal()] = sinceHit >= RECENT_HIT_TICKS
                ? 0.0 : 1.0 - (double) sinceHit / RECENT_HIT_TICKS;

        s[Signal.SMOKE_READY.ordinal()] = f.smokeReady ? 1.0 : 0.0;
        s[Signal.SCREENED.ordinal()] = f.screened ? 1.0 : 0.0;
        s[Signal.CANNOT_SHOOT.ordinal()] = f.canShoot ? 0.0 : 1.0;
        s[Signal.IDLE_ALLY.ordinal()] = Math.min(f.idleAllies, 3) / 3.0;

        // Exactly one of each is raised, so a weights file reads as a table of places and
        // conditions rather than a set of overlapping flags.
        Signal ground = switch (f.ground) {
            case OPEN -> Signal.OPEN;
            case FOREST -> Signal.FOREST;
            case URBAN -> Signal.URBAN;
            case MOUNTAIN -> Signal.MOUNTAIN;
            case SWAMP -> Signal.SWAMP;
            case DESERT -> Signal.DESERT;
        };
        s[ground.ordinal()] = 1.0;

        switch (f.sky) {
            case RAIN -> s[Signal.RAIN.ordinal()] = 1.0;
            case SNOW -> s[Signal.SNOW.ordinal()] = 1.0;
            case STORM -> s[Signal.STORM.ordinal()] = 1.0;
            case CLEAR -> { /* clear weather is the baseline and has no signal of its own */ }
        }

        // A three-block step over a hull length is already awkward going; past that it is a cliff
        // and the exact number stops meaning anything.
        s[Signal.STEEP_GROUND.ordinal()] = Mth.clamp(f.slope / 3.0, 0.0, 1.0);
        s[Signal.HIGH_ALTITUDE.ordinal()] = f.altitude;

        // Command-tier tasking: one projection line — raise the matching TASKED_* or none.
        CrewAssignment.raiseTaskSignals(unit.getId(), s);

        // Individual tactics / cover — Facts fields written by TacticalPosture before sample.
        s[Signal.EXPOSED.ordinal()] = Mth.clamp(f.exposure, 0.0, 1.0);
        s[Signal.IN_COVER.ordinal()] = Mth.clamp(f.inCover, 0.0, 1.0);
        s[Signal.KEYHOLE.ordinal()] = Mth.clamp(f.keyholeQuality, 0.0, 1.0);
        s[Signal.RECENT_SHOT.ordinal()] = Mth.clamp(f.recentShot, 0.0, 1.0);
        s[Signal.ALLY_INFANTRY_NEAR.ordinal()] = Mth.clamp(f.alliedInfantryNear, 0.0, 1.0);
        s[Signal.POSTURE_SCOOT.ordinal()] = f.postureScoot ? 1.0 : 0.0;
        s[Signal.POSTURE_AMBUSH.ordinal()] = f.postureAmbush ? 1.0 : 0.0;
    }

    private void decide(AbstractUnit unit, Doctrine doctrine, UtilityWeights weights, long now) {
        // Which way this crew goes round. Id-parity by default so a platoon splits both ways;
        // a TASKED_FLANK assignment overrides so the group flanks the commander's way.
        Action preferredFlank = preferredFlankOf(unit.getId());

        Action best = null;
        double bestScore = 0.0;
        for (Action action : Action.VALUES) {
            int i = action.ordinal();
            this.feasible[i] = feasible(action);
            if (!this.feasible[i]) {
                // A real number, so nothing downstream has to know about a sentinel. It is simply
                // never compared, because every read is guarded by the feasible flag.
                this.scores[i] = 0.0;
                continue;
            }
            double score = weights.score(action, this.signals, doctrine);
            if (action == preferredFlank) score += FLANK_TIEBREAK;
            this.scores[i] = score;
            if (best == null || score > bestScore) {
                best = action;
                bestScore = score;
            }
        }

        // feasible(HOLD) is unconditionally true, so this can only be null if the enum is empty.
        if (best == null) return;

        // Nothing has been decided yet, so there is no incumbent to defend. Without this the
        // constructor's placeholder HOLD gets the switch margin's protection it never earned, and a
        // crew whose best option only ever beats it by a few points sits still for good — which is
        // exactly what "idle units look static" turned out to be.
        boolean neverPlanned = this.planStarted == Long.MIN_VALUE;

        boolean planStillValid = this.feasible[this.plan.ordinal()];
        double current = planStillValid ? this.scores[this.plan.ordinal()] : bestScore;

        boolean committed = Facts.ticksSince(this.planStarted, now) < MIN_PLAN_TICKS;
        boolean beaten = bestScore > current + SWITCH_MARGIN;

        // A plan that has become impossible is dropped at once — neither the switch margin nor the
        // minimum duration may keep a crew committed to something it can no longer carry out.
        if (best != this.plan && (neverPlanned || !planStillValid || (beaten && !committed))) {
            this.plan = best;
            this.planStarted = now;
        } else if (neverPlanned) {
            // Best already IS the plan; stamp it so the hysteresis starts counting from here.
            this.planStarted = now;
        }

        if (DEBUG_LOGGING) logDecision(unit, doctrine);
    }

    /**
     * Can this action actually be carried out right now?
     *
     * <p>A hard gate in front of the weights, not a large negative inside them: a datapack typo
     * must never be able to make a crew commit to something it cannot do, because the hull would
     * then sit out its whole minimum plan duration doing nothing. {@link Action#HOLD} is always
     * feasible, which is what guarantees there is always a winner.
     */
    private boolean feasible(Action action) {
        // Combat and standing-down are disjoint: an action from the wrong set can never win, so
        // neither dispatch can ever be handed a plan it has no case for.
        if (action.needsTarget() != (this.facts.target != null)) return false;

        return switch (action) {
            case HOLD -> true;
            case DEPLOY_SMOKE -> this.facts.smokeReady;
            case ATTACK, ADVANCE, RETREAT, FLANK_LEFT, FLANK_RIGHT -> true;

            // Where an ordered crew goes is the player's business, not the scorer's. PATROL is the
            // one that stays available, because it IS "carry out the standing destination".
            case PATROL -> true;
            case SEARCH_LAST_KNOWN ->
                    !this.facts.underOrders && this.facts.memory.hasFreshContact(this.lastSampledTick);
            case REGROUP -> !this.facts.underOrders && this.facts.nearestAlly != null;

            // A request needs something to request from, and a crew that just called must not
            // call again — the cooldown is what stops one contact re-tasking every tube in the
            // field every second.
            case CALL_MORTARS -> canRequest(FireMissionSupport.Kind.MORTAR);
            case CALL_TOW -> canRequest(FireMissionSupport.Kind.TOW);
            case CALL_CAS -> canRequest(FireMissionSupport.Kind.CAS);

            case DELEGATE_TARGET -> this.facts.target != null && this.facts.idleAllies > 0;
        };
    }

    private boolean canRequest(FireMissionSupport.Kind kind) {
        return this.facts.support.contains(kind)
                && !this.facts.memory.recentlyCalledSupport(this.lastSampledTick);
    }

    /**
     * Flank direction for the tiebreak. Reads the published assignment's side when this crew is
     * a flank maneuver element; otherwise entity-id parity. Not a new brain branch — the same
     * preferredFlank slot the scorer already had.
     */
    public static Action preferredFlankOf(int unitId) {
        Assignment.FlankSide side = CrewAssignment.taskedFlankSide(unitId);
        if (side == Assignment.FlankSide.LEFT) return Action.FLANK_LEFT;
        if (side == Assignment.FlankSide.RIGHT) return Action.FLANK_RIGHT;
        return (unitId & 1) == 0 ? Action.FLANK_LEFT : Action.FLANK_RIGHT;
    }

    /**
     * One line per replan, for tuning the weights.
     *
     * <p>Blocked actions are listed separately from scored ones rather than shown with a sentinel
     * score: "could not" and "would not" are different answers, and reading a huge negative number
     * as "blocked" is exactly the confusion this avoids.
     */
    private void logDecision(AbstractUnit unit, Doctrine doctrine) {
        List<Action> ranked = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        for (Action action : Action.VALUES) {
            if (this.feasible[action.ordinal()]) ranked.add(action);
            else blocked.add(action.key);
        }
        ranked.sort((a, b) -> Double.compare(this.scores[b.ordinal()], this.scores[a.ordinal()]));

        StringBuilder top = new StringBuilder();
        for (int i = 0; i < Math.min(3, ranked.size()); i++) {
            if (i > 0) top.append(", ");
            top.append(ranked.get(i).key).append('=')
               .append(String.format("%.1f", this.scores[ranked.get(i).ordinal()]));
        }

        LOGGER.info("[sewv-ai] {}#{} plan={} conf={} hp={} ammo={} allies={} enemies={} range={}"
                        + " | best: {} | blocked: {} | {}",
                unit.getType().toShortString(), unit.getId(), this.plan.key,
                String.format("%.0f", this.facts.confidence),
                String.format("%.2f", this.facts.health),
                this.facts.ammo + "(" + this.facts.ammoCount + ")",
                this.facts.allies, this.facts.enemies,
                String.format("%.1f", this.facts.targetDist == Double.MAX_VALUE ? -1.0 : this.facts.targetDist),
                top, String.join(",", blocked), doctrine);
    }
}

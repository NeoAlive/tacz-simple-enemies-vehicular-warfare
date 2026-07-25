package com.neoalive.tacz_sewv.entity.ai.utility;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.FireMissionSupport;
import com.neoalive.tacz_sewv.entity.ai.VehicleWeapons.TargetCategory;
import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights.Signal;
import net.minecraft.util.Mth;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.slf4j.Logger;

import java.util.Arrays;

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

    private Action plan = Action.HOLD;
    private double planScore;
    private long planStarted = Long.MIN_VALUE;
    /** Game time of the last sample, so the feasibility gates can age the memory stamps. */
    private long lastSampledTick = Long.MIN_VALUE;

    /**
     * Re-read the battlefield and, if it is time, re-decide.
     *
     * @return true if the plan was re-scored this tick (the caller's cue to log or react)
     */
    public boolean update(AbstractUnit unit, VehicleEntity hull) {
        Doctrine doctrine = Doctrine.forCrew(unit);
        if (!this.facts.refresh(unit, hull, doctrine)) return false;

        long now = unit.level().getGameTime();
        sample(this.facts, now);
        decide(unit, doctrine, now);
        return true;
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
        this.planScore = Double.MAX_VALUE;
    }

    public void clear() {
        this.facts.clear();
        this.plan = Action.HOLD;
        this.planScore = 0.0;
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
    private void sample(Facts f, long now) {
        double[] s = this.signals;
        Arrays.fill(s, 0.0);
        this.lastSampledTick = now;

        s[Signal.BASE.ordinal()] = 1.0;

        boolean hasTarget = f.target != null;
        s[Signal.ENEMY_VISIBLE.ordinal()] = hasTarget ? 1.0 : 0.0;
        s[Signal.ENEMY_ARMOR.ordinal()] = f.targetCategory == TargetCategory.VEHICLE ? 1.0 : 0.0;
        s[Signal.ENEMY_INFANTRY.ordinal()] =
                hasTarget && f.targetCategory != TargetCategory.VEHICLE ? 1.0 : 0.0;

        // Confidence is 0-100 around a neutral 50; the signal is signed so a merely average
        // battlefield contributes nothing rather than a permanent half-weight bonus.
        s[Signal.CONFIDENCE.ordinal()] = Mth.clamp((f.confidence - 50.0) / 50.0, -1.0, 1.0);

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
        s[Signal.ALLIES_NEARBY.ordinal()] = Math.min(f.allies, 3) / 3.0;
        s[Signal.ALONE.ordinal()] = f.allies == 0 ? 1.0 : 0.0;

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
    }

    private void decide(AbstractUnit unit, Doctrine doctrine, long now) {
        UtilityWeights weights = UtilityWeights.active();

        // Which way this crew goes round. Fixed per unit, not re-rolled, so a crew commits to one
        // direction instead of rocking back and forth over the same ground.
        Action preferredFlank = (unit.getId() & 1) == 0 ? Action.FLANK_LEFT : Action.FLANK_RIGHT;

        Action best = null;
        double bestScore = 0.0;
        for (Action action : Action.VALUES) {
            if (!feasible(action)) {
                this.scores[action.ordinal()] = Double.NEGATIVE_INFINITY;
                continue;
            }
            double score = weights.score(action, this.signals, doctrine);
            if (action == preferredFlank) score += FLANK_TIEBREAK;
            this.scores[action.ordinal()] = score;
            if (best == null || score > bestScore) {
                best = action;
                bestScore = score;
            }
        }

        // feasible(HOLD) is unconditionally true, so this can only be null if the enum is empty.
        if (best == null) return;

        double current = feasible(this.plan)
                ? weights.score(this.plan, this.signals, doctrine)
                : Double.NEGATIVE_INFINITY;

        boolean committed = Facts.ticksSince(this.planStarted, now) < SewvConfig.UTILITY_MIN_PLAN_TICKS.get();
        boolean beaten = bestScore > current + SewvConfig.UTILITY_SWITCH_MARGIN.get();

        if (best != this.plan && beaten && !committed) {
            this.plan = best;
            this.planStarted = now;
            this.planScore = bestScore;
        } else {
            // Keep the plan, but re-record what it is worth now, so its score decays with the
            // battlefield instead of being frozen at whatever won it the job.
            this.planScore = current;
        }

        if (SewvConfig.UTILITY_DEBUG_LOGGING.get()) logDecision(unit, doctrine);
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
        return switch (action) {
            case HOLD -> true;
            case DEPLOY_SMOKE -> this.facts.smokeReady && this.facts.target != null;
            case ATTACK, ADVANCE, RETREAT, FLANK_LEFT, FLANK_RIGHT -> this.facts.target != null;

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
        return this.facts.target != null
                && this.facts.support.contains(kind)
                && !this.facts.memory.recentlyCalledSupport(this.lastSampledTick);
    }

    private void logDecision(AbstractUnit unit, Doctrine doctrine) {
        Action[] ranked = Action.VALUES.clone();
        Arrays.sort(ranked, (a, b) -> Double.compare(this.scores[b.ordinal()], this.scores[a.ordinal()]));

        StringBuilder top = new StringBuilder();
        for (int i = 0; i < Math.min(3, ranked.length); i++) {
            if (i > 0) top.append(", ");
            top.append(ranked[i].key).append('=')
               .append(String.format("%.1f", this.scores[ranked[i].ordinal()]));
        }

        LOGGER.info("[sewv-ai] {}#{} plan={} conf={} hp={} ammo={} allies={} enemies={} range={} | {} | {}",
                unit.getType().toShortString(), unit.getId(), this.plan.key,
                String.format("%.0f", this.facts.confidence),
                String.format("%.2f", this.facts.health), this.facts.ammo,
                this.facts.allies, this.facts.enemies,
                String.format("%.1f", this.facts.targetDist == Double.MAX_VALUE ? -1.0 : this.facts.targetDist),
                top, doctrine);
    }
}

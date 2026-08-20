package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.Comparator;
import java.util.EnumSet;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.compat.PlayerReviveCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Any friendly PMC — no medical kit or {@code SupportRole.MEDIC} required, unlike {@link MedicGoal} —
 * automatically revives a downed player. SEM/SBW have no notion of a player going down at all; that
 * state lives entirely in the soft-compat mod PlayerReviveMod, reached only through
 * {@link PlayerReviveCompat}. Only ever added to a unit's goal selector when that mod is present
 * ({@code MixinPmcUnitEntity}), so this class runs with no further mod-presence checks of its own.
 *
 * <p>PlayerReviveMod's own multi-helper progress accumulator ({@code IBleeding.revivingPlayers()}) is
 * {@code Player}-typed — its interact flow requires {@code Player instanceof} on both sides — so an
 * NPC cannot join it. This goal instead does its own short in-place channel and calls
 * {@link PlayerReviveCompat#revive} once. Two PMCs racing the same downed player is harmless: whichever
 * finishes first revives them, and the other's {@link #canContinueToUse} fails on its next check
 * because {@link PlayerReviveCompat#isDowned} has gone false, so it stops cleanly with no lock needed.
 *
 * <p><b>Priority 1, and it deliberately does NOT yield to combat</b> — same reasoning as
 * {@code EscortGoal}: SEM's own chase goal ({@code MoveToAttackRangeGoal}) and rifle goal
 * ({@code RangedGunAttackGoal}) sit at priority 3, so holding MOVE+LOOK at priority 1 here
 * preempts them. A player going down is a hard 60-second timer (PlayerReviveMod's default
 * {@code bleedTime}), not a "when convenient" task like ally healing, and a player usually goes
 * down mid-firefight — the exact moment {@code MedicGoal}'s own "bail while holding a target"
 * rule would leave them to die. This goal claims LOOK too (unlike {@code EscortGoal}, which only
 * takes MOVE and lets the rifle keep firing): giving aid needs the unit's attention on the
 * patient, not the enemy, for the walk-over and the whole channel.
 */
public class PlayerReviveGoal extends Goal {

    /** Close enough to work on someone. Same reach as {@code MedicGoal.TREAT_DISTANCE_SQ}. */
    private static final double REVIVE_DISTANCE_SQ = 4.0;
    /**
     * Goal ticks before looking for a downed player again after finding none. Load-bearing for the
     * same reason as {@code MedicGoal.IDLE_RESCAN}: without it, every idle PMC on the map would scan
     * for downed players on every evaluation.
     */
    private static final int IDLE_RESCAN = 40;
    /** Give up walking to a downed player after this long — they may be somewhere unreachable. */
    private static final int MAX_APPROACH_TICKS = 200;

    private final PmcUnitEntity unit;
    private Player patient;
    private int cooldown;
    private int approachTicks;
    /** Ticks left in the in-place revive channel once in range. */
    private int channelTicksLeft;

    public PlayerReviveGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (!SewvConfig.PMC_REVIVE_ENABLED.get()) return false;
        if (this.cooldown > 0) {
            this.cooldown--;
            return false;
        }
        // A crew member is busy working the vehicle. Unlike MedicGoal, holding a target does NOT
        // bail here — see the class doc for why a downed player overrides ordinary combat.
        if (this.unit.isPassenger()) return false;

        this.patient = findDownedPlayer();
        if (this.patient == null) {
            this.cooldown = IDLE_RESCAN;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.patient != null
                && this.patient.isAlive()
                && PlayerReviveCompat.isDowned(this.patient)
                && this.approachTicks < MAX_APPROACH_TICKS;
    }

    @Override
    public void start() {
        this.approachTicks = 0;
        this.channelTicksLeft = SewvConfig.PMC_REVIVE_CHANNEL_TICKS.get();
        this.unit.getNavigation().moveTo(this.patient, 1.0);
    }

    @Override
    public void stop() {
        this.unit.getNavigation().stop();
        this.patient = null;
        this.approachTicks = 0;
        this.channelTicksLeft = 0;
    }

    @Override
    public void tick() {
        if (this.patient == null) return;
        this.approachTicks++;

        this.unit.getLookControl().setLookAt(this.patient, 30.0F, 30.0F);
        if (this.unit.distanceToSqr(this.patient) > REVIVE_DISTANCE_SQ) {
            // Repath only once the last one has run out — an unreachable patient reports "done"
            // every tick and would otherwise force a full path search every tick until timeout.
            if (this.unit.getNavigation().isDone()) {
                this.unit.getNavigation().moveTo(this.patient, 1.0);
            }
            return;
        }
        this.unit.getNavigation().stop();

        this.channelTicksLeft--;
        if (this.channelTicksLeft > 0) return;

        PlayerReviveCompat.revive(this.patient);
        // No dedicated "revive" voiceline/audio asset exists; HEALING is the closest existing fit.
        CrewRadio.speakUnit(this.unit, CrewRadio.Line.HEALING);
        this.patient = null; // end the goal now rather than waiting for isDowned() to catch up
    }

    /**
     * Nearest downed player this unit should help. {@link VehicleTargeting#isFriendlyPlayer} answers
     * "is this player mine" — its own owner, an ownerless (FRIENDLY_DEFAULT) crew's anyone, or a
     * non-enemy player via diplomacy — which {@code isNonHostile} cannot: that predicate's
     * faction-friendly-toggle branch only ever answers for an RU/US shooter.
     */
    private Player findDownedPlayer() {
        double radius = SewvConfig.PMC_REVIVE_SEARCH_RADIUS.get();
        return this.unit.level().getEntitiesOfClass(
                Player.class,
                this.unit.getBoundingBox().inflate(radius),
                p -> p.isAlive()
                        && PlayerReviveCompat.isDowned(p)
                        && VehicleTargeting.isFriendlyPlayer(this.unit, p))
                .stream()
                .min(Comparator.comparingDouble(this.unit::distanceToSqr))
                .orElse(null);
    }
}

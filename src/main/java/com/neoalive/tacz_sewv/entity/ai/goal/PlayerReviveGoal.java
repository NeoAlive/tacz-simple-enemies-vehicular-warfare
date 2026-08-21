package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.Comparator;
import java.util.EnumSet;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.compat.PlayerReviveCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.MedicControl;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.network.PacketReviveProgress;

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
 * <p>Because the reviver never joins {@code revivingPlayers()}, PlayerReviveMod's own progress
 * bar / helper HUD never moves — that UI is driven entirely by that list's size, which an NPC
 * structurally cannot join (see above), and injecting a fake entry (e.g. a Forge {@code FakePlayer})
 * risks crashing PlayerReviveMod's own packet code, which assumes a real connection. So the downed
 * player instead gets this mod's own ring widget ({@code sendRingProgress} →
 * {@code PacketReviveProgress} → {@code RevivalRingOverlay} — SBW's own artillery-indicator ring,
 * reinvoked for this), independent of PlayerReviveMod's HUD entirely. An earlier version also sent a
 * repeating action-bar percentage; removed once the ring existed to show the same thing, keeping
 * only the one-shot "Revived." message on completion.
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
    /** Total channel length for this session, fixed at {@link #start()} — the ring's denominator. */
    private int channelTicksTotal;
    /** One "reviving" voiceline per session, played when the channel starts, not when it ends. */
    private boolean revivingVoiced;

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
        // A unit committed to a mortar stays committed — same precedence PacketBoardVehicle/
        // PacketEscort already give mortar duty over a reassignment order. Without this, the
        // brief window where ManMortarGoal itself yields MOVE+LOOK (beingOverrun) is enough for
        // this equal-priority goal to win the tie and walk the crew off the tube for the whole
        // revive channel, well past when the overrun that opened the window has cleared.
        if (MortarSupport.hasMortarClaim(this.unit)) return false;

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
        this.channelTicksTotal = SewvConfig.PMC_REVIVE_CHANNEL_TICKS.get();
        this.channelTicksLeft = this.channelTicksTotal;
        this.revivingVoiced = false;
        this.unit.getNavigation().moveTo(this.patient, 1.0);
    }

    @Override
    public void stop() {
        this.unit.getNavigation().stop();
        if (this.patient instanceof ServerPlayer sp) {
            PacketReviveProgress.sendTo(sp, 0.0F, false);
        }
        this.patient = null;
        this.approachTicks = 0;
        this.channelTicksLeft = 0;
        this.revivingVoiced = false;
        MedicControl.setTreating(this.unit, false);
    }

    @Override
    public void tick() {
        if (this.patient == null) return;

        this.unit.getLookControl().setLookAt(this.patient, 30.0F, 30.0F);
        if (this.unit.distanceToSqr(this.patient) > REVIVE_DISTANCE_SQ) {
            // Only the walk-over counts against the approach budget — once channeling starts
            // below, it must not also burn this timeout, or a long walk plus the channel itself
            // can together exceed MAX_APPROACH_TICKS and abort a revive that was already in
            // progress.
            this.approachTicks++;
            MedicControl.setTreating(this.unit, false);
            // Repath only once the last one has run out — an unreachable patient reports "done"
            // every tick and would otherwise force a full path search every tick until timeout.
            if (this.unit.getNavigation().isDone()) {
                this.unit.getNavigation().moveTo(this.patient, 1.0);
            }
            return;
        }
        this.unit.getNavigation().stop();
        // Hides the held weapon (UnitHolster.hideHeldItems) and drives the treating animation for
        // the whole in-range session, same as MedicGoal — a PMC administering aid should not be
        // seen with a rifle up.
        MedicControl.setTreating(this.unit, true);
        if (!this.revivingVoiced) {
            this.revivingVoiced = true;
            // No dedicated "revive" voiceline/audio asset exists; HEALING is the closest existing
            // fit. Played when the channel STARTS, so it lands while the revive is happening.
            CrewRadio.speakUnit(this.unit, CrewRadio.Line.HEALING);
        }

        sendRingProgress();

        this.channelTicksLeft--;
        if (this.channelTicksLeft > 0) return;

        PlayerReviveCompat.revive(this.patient);
        this.patient.displayClientMessage(Component.translatable("message.tacz_sewv.revive.complete"), true);
        if (this.patient instanceof ServerPlayer sp) {
            PacketReviveProgress.sendTo(sp, 1.0F, false);
        }
        this.patient = null; // end the goal now rather than waiting for isDowned() to catch up
    }

    /** Ring update every tick (unlike the throttled action-bar text) — cheap, and smooth matters here. */
    private void sendRingProgress() {
        if (!(this.patient instanceof ServerPlayer sp)) return;
        float fraction = this.channelTicksTotal <= 0 ? 1.0F
                : 1.0F - (float) this.channelTicksLeft / this.channelTicksTotal;
        PacketReviveProgress.sendTo(sp, fraction, true);
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

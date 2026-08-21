package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.MedicControl;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.entity.ai.support.PmcDownedSupport;
import com.neoalive.tacz_sewv.entity.ai.support.SupportRole;
import com.neoalive.tacz_sewv.network.PacketReviveProgress;

/**
 * A medic PMC — {@code SupportRole.MEDIC}, i.e. holding a medical kit, the same gate
 * {@link MedicGoal}'s "neutral" ally-in-contact healing uses — automatically revives a downed
 * squadmate. Deliberately stricter than {@code MedicGoal.hasKit()} (which also lets a PMC with a
 * spare kit just sitting in inventory patch allies up): bringing someone back from downed is a
 * dedicated medic's job, not anyone who happens to be carrying a spare kit.
 *
 * <p>Structured on {@link PlayerReviveGoal}, not {@code MedicGoal}: reviving is a one-shot state
 * change ({@link PmcDownedSupport#revive}), not a repeated heal-and-continue, and — like
 * {@code PlayerReviveGoal} — a downed squadmate is a hard bleed-out timer
 * ({@code SewvConfig.PMC_DOWNED_BLEED_TICKS}), so this claims priority 1 and does NOT yield to
 * combat the way {@code MedicGoal}'s own out-of-contact-only healing does.
 *
 * <p>Neither the medic nor the patient is a player, so there is no natural screen to show revive
 * progress on — this sends {@link PacketReviveProgress} (SBW's artillery-indicator ring, reinvoked)
 * to the patient's <b>owning</b> player instead, when it has one and that player is online, same
 * spirit as {@code PlayerReviveGoal} showing it to the downed player directly. Ownerless
 * (FRIENDLY_DEFAULT) crew — village garrisons, berezka structures — simply get no ring; nobody to
 * show it to.
 */
public class PmcReviveGoal extends Goal {

    /** Close enough to work on someone. Same reach as {@code PlayerReviveGoal.REVIVE_DISTANCE_SQ}. */
    private static final double REVIVE_DISTANCE_SQ = 4.0;
    /** Goal ticks before looking for a downed squadmate again after finding none. */
    private static final int IDLE_RESCAN = 40;
    /** Give up walking to a downed squadmate after this long — they may be somewhere unreachable. */
    private static final int MAX_APPROACH_TICKS = 200;

    private final PmcUnitEntity unit;
    private PmcUnitEntity patient;
    private int cooldown;
    private int approachTicks;
    /** Ticks left in the in-place revive channel once in range. */
    private int channelTicksLeft;
    /** Total channel length for this session, fixed at {@link #start()} — the ring's denominator. */
    private int channelTicksTotal;
    /** One "reviving" voiceline per session, played when the channel starts, not when it ends. */
    private boolean revivingVoiced;

    public PmcReviveGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (!SewvConfig.PMC_DOWNED_ENABLED.get()) return false;
        if (this.cooldown > 0) {
            this.cooldown--;
            return false;
        }
        if (this.unit.isPassenger()) return false;
        // A downed medic obviously cannot revive anyone.
        if (this.unit instanceof IPmcDowned self && self.sewv$isDowned()) return false;
        if (SupportRole.of(this.unit) != SupportRole.MEDIC) return false;
        // A medic committed to a mortar stays committed — see PlayerReviveGoal for why this
        // guard exists: without it, ManMortarGoal's own beingOverrun window (the one point it
        // yields MOVE+LOOK while still holding the claim) is enough for this equal-priority
        // goal to win the tie and carry the medic off the tube for a whole revive channel.
        if (MortarSupport.hasMortarClaim(this.unit)) return false;

        this.patient = findDownedAlly();
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
                && this.patient instanceof IPmcDowned downed
                && downed.sewv$isDowned()
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
        ServerPlayer owner = resolveOwner();
        if (owner != null) {
            PacketReviveProgress.sendTo(owner, 0.0F, false);
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
            // Only the walk-over counts against the approach budget — see PlayerReviveGoal for
            // why the channel itself must not also burn it.
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
        // Hides the held weapon (UnitHolster.hideHeldItems) for the whole in-range session, same
        // as MedicGoal — a medic administering aid should not be seen with a rifle up.
        MedicControl.setTreating(this.unit, true);
        if (!this.revivingVoiced) {
            this.revivingVoiced = true;
            // No dedicated "revive" voiceline/audio asset exists; HEALING is the closest existing
            // fit. Played when the channel STARTS, so it lands while the revive is happening.
            CrewRadio.speakUnit(this.unit, CrewRadio.Line.HEALING);
        }

        ServerPlayer owner = resolveOwner();
        if (owner != null) {
            float fraction = this.channelTicksTotal <= 0 ? 1.0F
                    : 1.0F - (float) this.channelTicksLeft / this.channelTicksTotal;
            PacketReviveProgress.sendTo(owner, fraction, true);
        }

        this.channelTicksLeft--;
        if (this.channelTicksLeft > 0) return;

        PmcDownedSupport.revive(this.patient);
        if (owner != null) {
            PacketReviveProgress.sendTo(owner, 1.0F, false);
        }
        this.patient = null; // end the goal now rather than waiting for isDowned() to catch up
    }

    /** Patient's owning player, if it has one and they're online — nobody for an ownerless crew. */
    @Nullable
    private ServerPlayer resolveOwner() {
        if (this.patient == null) return null;
        UUID ownerId = this.patient.getOwnerUUID();
        if (ownerId == null) return null;
        MinecraftServer server = this.patient.getServer();
        return server != null ? server.getPlayerList().getPlayer(ownerId) : null;
    }

    /** Nearest downed same-faction PMC. {@link VehicleTargeting#isFriendly} is the plain same-class check. */
    private PmcUnitEntity findDownedAlly() {
        double radius = SewvConfig.PMC_REVIVE_SEARCH_RADIUS.get();
        return this.unit.level().getEntitiesOfClass(
                PmcUnitEntity.class,
                this.unit.getBoundingBox().inflate(radius),
                other -> other != this.unit
                        && other.isAlive()
                        && other instanceof IPmcDowned downed
                        && downed.sewv$isDowned()
                        && VehicleTargeting.isFriendly(this.unit, other))
                .stream()
                .min(Comparator.comparingDouble(this.unit::distanceToSqr))
                .orElse(null);
    }
}

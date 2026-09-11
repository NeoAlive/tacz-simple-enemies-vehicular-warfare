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
import com.neoalive.tacz_sewv.entity.ai.support.PmcDownedSupport;
import com.neoalive.tacz_sewv.entity.ai.support.ReviveClaims;
import com.neoalive.tacz_sewv.network.PacketReviveProgress;

/**
 * Any friendly PMC automatically revives a downed squadmate — no medical kit / {@code SupportRole}
 * gate. Structured on {@link PlayerReviveGoal}: one-shot {@link PmcDownedSupport#revive}, priority 1
 * that does not yield to combat, and {@link ReviveClaims} so only one unit works each patient.
 *
 * <p>Neither the reviver nor the patient is a player, so progress goes to the patient's owning
 * player via {@link PacketReviveProgress} when online. Ownerless crew get no ring.
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
        if (!ReviveClaims.isEligibleReviver(this.unit)) return false;

        this.patient = findDownedAlly();
        if (this.patient == null) {
            this.cooldown = IDLE_RESCAN;
            return false;
        }
        if (!ReviveClaims.tryClaim(this.unit.level(), this.patient.getId(), this.unit.getId())) {
            this.patient = null;
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
                && ReviveClaims.isEligibleReviver(this.unit)
                && ReviveClaims.isMine(this.patient.getId(), this.unit.getId())
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
        if (this.patient != null) {
            ReviveClaims.release(this.patient.getId(), this.unit.getId());
        }
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
            this.approachTicks++;
            MedicControl.setTreating(this.unit, false);
            if (this.unit.getNavigation().isDone()) {
                this.unit.getNavigation().moveTo(this.patient, 1.0);
            }
            return;
        }
        this.unit.getNavigation().stop();
        MedicControl.setTreating(this.unit, true);
        if (!this.revivingVoiced) {
            this.revivingVoiced = true;
            CrewRadio.speakUnit(this.unit, CrewRadio.Line.UNIT_HEAL);
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
        this.patient = null;
    }

    @Nullable
    private ServerPlayer resolveOwner() {
        if (this.patient == null) return null;
        UUID ownerId = this.patient.getOwnerUUID();
        if (ownerId == null) return null;
        MinecraftServer server = this.patient.getServer();
        return server != null ? server.getPlayerList().getPlayer(ownerId) : null;
    }

    private PmcUnitEntity findDownedAlly() {
        double radius = SewvConfig.PMC_REVIVE_SEARCH_RADIUS.get();
        return this.unit.level().getEntitiesOfClass(
                PmcUnitEntity.class,
                this.unit.getBoundingBox().inflate(radius),
                other -> other != this.unit
                        && other.isAlive()
                        && other instanceof IPmcDowned downed
                        && downed.sewv$isDowned()
                        && VehicleTargeting.isFriendly(this.unit, other)
                        && ReviveClaims.isFreeOrMine(this.unit.level(), other.getId(), this.unit.getId()))
                .stream()
                .min(Comparator.comparingDouble(this.unit::distanceToSqr))
                .orElse(null);
    }
}

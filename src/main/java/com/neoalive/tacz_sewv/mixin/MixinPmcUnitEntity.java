package com.neoalive.tacz_sewv.mixin;

import java.util.UUID;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.NoPlayerHurtByTargetGoal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.bridge.ICaptureMedic;
import com.neoalive.tacz_sewv.bridge.ICaptureOrder;
import com.neoalive.tacz_sewv.bridge.IEntrenched;
import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IIssuedAmmo;
import com.neoalive.tacz_sewv.bridge.IMedicTreat;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.bridge.IPmcDowned;
import com.neoalive.tacz_sewv.bridge.ISweepInfantry;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.compat.PlayerReviveCompat;
import com.neoalive.tacz_sewv.crew.NpcIdentity;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.goal.DiplomacyEnemyTargetGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.DownedGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.EscortGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.FollowCommanderGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.MedicGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.MoveToPositionGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.NoFriendlyHurtByTargetGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.PlatoonCohesionGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.PlayerReviveGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.PmcCaptureMedicGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.PmcCombatDebugGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.PmcReviveGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.RadioObserverGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.RepairGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.SweepInfantryGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.VehicleAiGoals;
import com.neoalive.tacz_sewv.entity.ai.support.UnitHolster;

// IHelicopterPilot, IFormationMember and IVehiclePatrol need no method bodies here — their
// default methods store the flight state, the formation axis and the patrol order in the
// entity's persistent NBT (so they survive world reloads).
// The boarding order and mortar claim below are deliberately transient: they target
// an entity by network id, which is not stable across sessions, so persisting them
// would be wrong — a pending order is simply dropped on reload.
// IIssuedAmmo is only set on a PMC crew SPAWNED onto an emplacement (/sewv spawn pmc tow).
// One a player ordered onto a tube with the board key has none, and so reads the shells the
// player actually gave it — which is the whole point of hand-loading one.
@Mixin(PmcUnitEntity.class)
public abstract class MixinPmcUnitEntity
        implements IVehicleBoarder, IHelicopterPilot, IMortarCrew, IIssuedAmmo, IFormationMember,
        IVehiclePatrol, IEscort, ISweepInfantry, ICaptureOrder, IMedicTreat, IEntrenched, IPmcDowned,
        ICaptureMedic {

    @Unique
    private static final EntityDataAccessor<Boolean> tacz_sewv$TREATING;
    @Unique
    private static final EntityDataAccessor<Boolean> tacz_sewv$DOWNED;

    static {
        // Parent AbstractUnit extensions (MANNING_MORTAR) must already own their ids before
        // this subclass defineId runs — see MixinAbstractUnit's static block.
        if (UnitHolster.MANNING_MORTAR == null) {
            throw new ExceptionInInitializerError("UnitHolster.MANNING_MORTAR");
        }
        tacz_sewv$TREATING = SynchedEntityData.defineId(PmcUnitEntity.class, EntityDataSerializers.BOOLEAN);
        tacz_sewv$DOWNED = SynchedEntityData.defineId(PmcUnitEntity.class, EntityDataSerializers.BOOLEAN);
    }

    @Unique
    private int tacz_sewv$mountTargetId = -1;

    // Transient escort target (an entity network id) — the vehicle this unit sticks beside under an
    // Escort order. Dropped on reload, like the board order, since a network id means nothing across
    // sessions. -1 = not escorting.
    @Unique
    private int tacz_sewv$escortTargetId = -1;

    @Unique
    private boolean tacz_sewv$boarding = false;

    @Unique
    private boolean tacz_sewv$passengerOnly = false;

    @Unique
    private boolean tacz_sewv$boardCleared = false;

    @Unique
    private int tacz_sewv$mortarTargetId = IMortarCrew.NO_MORTAR;

    // Player-issued "go capture a medic" dispatch (TDT Capture Medic button). Transient, like the
    // escort order above — dropped on reload, and cleared once PmcCaptureMedicGoal finishes or is
    // interrupted.
    @Unique
    private boolean tacz_sewv$captureMedicOrdered = false;

    /**
     * Stash for {@link #tacz_sewv$keepDiplomacyPlayerTarget}: SEM 0.1.6's PmcUnitEntity.setTarget
     * hard-clears any Player to null before AbstractUnit runs, which would kill OpenPAC ENEMY
     * locks on opposing players. We only need the original argument on the Player→null branch.
     */
    @Unique
    private LivingEntity tacz_sewv$pendingSetTarget;

    @Override
    public void tacz_sewv$setMountTargetId(int id) {
        this.tacz_sewv$mountTargetId = id;
    }

    @Override
    public int tacz_sewv$getMountTargetId() {
        return this.tacz_sewv$mountTargetId;
    }

    @Override
    public void tacz_sewv$setBoarding(boolean boarding) {
        this.tacz_sewv$boarding = boarding;
    }

    @Override
    public boolean tacz_sewv$isBoarding() {
        return this.tacz_sewv$boarding;
    }

    @Override
    public void tacz_sewv$setPassengerOnly(boolean passengerOnly) {
        this.tacz_sewv$passengerOnly = passengerOnly;
    }

    @Override
    public boolean tacz_sewv$isPassengerOnly() {
        return this.tacz_sewv$passengerOnly;
    }

    @Override
    public void tacz_sewv$setBoardCleared(boolean cleared) {
        this.tacz_sewv$boardCleared = cleared;
    }

    @Override
    public boolean tacz_sewv$isBoardCleared() {
        return this.tacz_sewv$boardCleared;
    }

    @Override
    public void tacz_sewv$setEscortTargetId(int id) {
        this.tacz_sewv$escortTargetId = id;
    }

    @Override
    public int tacz_sewv$getEscortTargetId() {
        return this.tacz_sewv$escortTargetId;
    }

    @Override
    public void sewv$setMortarTargetId(int id) {
        this.tacz_sewv$mortarTargetId = id;
    }

    @Override
    public int sewv$getMortarTargetId() {
        return this.tacz_sewv$mortarTargetId;
    }

    @Override
    public boolean sewv$isTreating() {
        return ((Entity) (Object) this).getEntityData().get(tacz_sewv$TREATING);
    }

    @Override
    public void sewv$setTreating(boolean treating) {
        ((Entity) (Object) this).getEntityData().set(tacz_sewv$TREATING, treating);
    }

    @Override
    public void tacz_sewv$setCaptureMedicOrdered(boolean ordered) {
        this.tacz_sewv$captureMedicOrdered = ordered;
    }

    @Override
    public boolean tacz_sewv$isCaptureMedicOrdered() {
        return this.tacz_sewv$captureMedicOrdered;
    }

    @Override
    public boolean sewv$isDownedSynced() {
        return ((Entity) (Object) this).getEntityData().get(tacz_sewv$DOWNED);
    }

    @Override
    public void sewv$setDownedSynced(boolean downed) {
        ((Entity) (Object) this).getEntityData().set(tacz_sewv$DOWNED, downed);
    }

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void tacz_sewv$defineTreating(CallbackInfo ci) {
        ((Entity) (Object) this).getEntityData().define(tacz_sewv$TREATING, false);
        ((Entity) (Object) this).getEntityData().define(tacz_sewv$DOWNED, false);
    }

    @Inject(method = "setTarget", at = @At("HEAD"))
    private void tacz_sewv$stashSetTarget(LivingEntity target, CallbackInfo ci) {
        this.tacz_sewv$pendingSetTarget = target;
    }

    /**
     * SEM clears {@code setTarget(Player)} to null. Ordinal 0 is that branch's
     * {@code super.setTarget(null)}; when the stashed target is a diplomacy ENEMY player, pass
     * them through so {@code MixinAbstractUnit} and the rest of the ladder still see them.
     * Non-diplomacy Players stay nulled.
     */
    @ModifyArg(
            method = "setTarget",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/entity/unit/AbstractUnit;setTarget(Lnet/minecraft/world/entity/LivingEntity;)V",
                    ordinal = 0))
    private LivingEntity tacz_sewv$keepDiplomacyPlayerTarget(LivingEntity cleared) {
        LivingEntity pending = this.tacz_sewv$pendingSetTarget;
        if (!(pending instanceof Player)) return cleared;
        PmcUnitEntity self = (PmcUnitEntity) (Object) this;
        // Invasion enemy list (or OpenPAC ENEMY) — the only cases SEM's hard Player null-out must lose.
        if (VehicleTargeting.isDiplomacyEnemy(self, pending)) {
            return pending;
        }
        return cleared;
    }

    @Inject(method = "setTarget", at = @At("TAIL"))
    private void tacz_sewv$clearSetTargetStash(LivingEntity target, CallbackInfo ci) {
        this.tacz_sewv$pendingSetTarget = null;
    }

    /**
     * The formation axis is ours — PacketVehicleFormation is its only writer — so any other path
     * assigning this unit a slot means it has joined someone else's formation and must not carry
     * ours. setFormationIndex is that signal: SEM's PacketIssueOrder calls it for every order,
     * including the plain infantry wedge that would otherwise send one stale-axis man off to a
     * vehicle-spaced slot on an old cardinal while his squad forms normally.
     *
     * <p>The isAddedToWorld guard is load-bearing, not defensive. PmcUnitEntity's
     * readAdditionalSaveData ALSO calls setFormationIndex, and Forge restores ForgeData into
     * persistentData earlier in Entity.load than that — so without this, every world load would
     * wipe the axis it had just read back and the whole formation would return inert. An entity
     * read from disk is not added to the world until after load() returns, which makes the flag
     * say exactly what we mean: a LIVE order clears the axis; loading one is not an order.
     */
    @Inject(method = "setFormationIndex", at = @At("HEAD"), remap = false)
    private void tacz_sewv$dropFormationAxisOnReorder(int index, CallbackInfo ci) {
        if (!((Entity) (Object) this).isAddedToWorld()) return;
        this.sewv$setFormationDirection(null);
    }

    /**
     * NpcIdentity.issue is a no-op for an ownerless unit (a Berezka structure crew, an ambient
     * event rifleman) — there is no owner yet to draw a name-pool preference from. SEM's own
     * recruit-by-click (PmcUnitEntity#mobInteract) is what first hands one out, through this exact
     * setter, so that is the moment the unit's identity actually gets rolled.
     *
     * <p>The isAddedToWorld guard is load-bearing for the same reason it is on
     * setFormationIndex above: readAdditionalSaveData restores a saved owner through this same
     * setter before the entity is added to the world (an unrelated unit gaining a NEW owner can
     * only ever happen live), so without the guard every reload would re-roll an already-cached
     * name.
     */
    @Inject(method = "setOwner", at = @At("TAIL"), remap = false)
    private void tacz_sewv$reissueIdentityOnRecruit(UUID uuid, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (uuid != null && self.isAddedToWorld() && !self.level().isClientSide) {
            NpcIdentity.reissue((PmcUnitEntity) self);
        }
    }

    @Inject(method = "setupRoleGoals", at = @At("TAIL"), remap = false)
    private void tacz_sewv$addVehicleGoals(CallbackInfo ci) {
        PmcUnitEntity self = (PmcUnitEntity) (Object) this;
        // This mod's own downed mechanic (PMC only — RU/US just die). It never touches
        // PlayerReviveMod's own API/state — that mod's capability only ever attaches to Player —
        // but it is gated on the mod's presence anyway, same as PlayerReviveGoal below: bundled as
        // one optional feature set rather than a downed-PMC system that behaves differently
        // depending on an unrelated mod's presence. Priority 0: must outrank every other goal,
        // combat included, so a downed unit is fully frozen rather than still trying to fight
        // from the ground. See PmcDownedSupport (converts a killing blow into "downed") and the
        // class doc.
        if (PlayerReviveCompat.isLoaded()) {
            ((Mob) self).goalSelector.addGoal(0, new DownedGoal(self));
        }
        // Claims no flags, so its priority is nominal — it only relays a contact over the
        // radio and never competes with what the unit is doing.
        ((Mob) self).goalSelector.addGoal(1, new RadioObserverGoal(self));
        // PMC-only because the kits are player-supplied and a PMC is the one unit type SEM
        // gives an inventory to — RU/US have no ITEM_HANDLER to hold one. Priority 2 keeps
        // first aid below anything crew-served: it only runs out of contact anyway.
        ((Mob) self).goalSelector.addGoal(2, new MedicGoal(self));
        // Soft-compat: PlayerReviveMod. Any friendly PMC — no medical kit required, unlike
        // MedicGoal — revives a downed player automatically; see PlayerReviveCompat/PlayerReviveGoal.
        // Gated at goal-add time rather than inside canUse() so the goal simply doesn't exist
        // without the mod present. Priority 1, same band as EscortGoal: a downed player is a
        // hard timer, so unlike MedicGoal this one preempts ordinary combat (prio 3) rather than
        // waiting for it to end — see the class doc.
        if (PlayerReviveCompat.isLoaded()) {
            ((Mob) self).goalSelector.addGoal(1, new PlayerReviveGoal(self));
        }
        // This mod's own downed mechanic, the PMC-to-PMC half: only a medic (SupportRole.MEDIC,
        // stricter than MedicGoal's own "has a spare kit somewhere") revives a downed squadmate.
        // Gated on the same isLoaded() as DownedGoal above, for the same bundling reason — a
        // downed squadmate is pointless to revive if nothing can ever go down in the first place.
        // Same priority-1 "overrides combat" reasoning as PlayerReviveGoal.
        if (PlayerReviveCompat.isLoaded()) {
            ((Mob) self).goalSelector.addGoal(1, new PmcReviveGoal(self));
        }
        // The engineer half of the same idea: dormant on an ordinary PMC and live the moment a
        // player hands one a repair tool (RepairGoal gates itself on SupportRole). Same priority as
        // first aid — both are things a unit does when it is not otherwise busy — and it stands
        // itself down the instant the unit holds a target. No DroneOperatorGoal: recon drones are
        // RU/US doctrine, and a PMC's reconnaissance is the player's own.
        ((Mob) self).goalSelector.addGoal(2, new RepairGoal(self));
        // Same pairing the RU/US engineer gets: without the hand swap a PMC engineer with a sidearm
        // would acquire targets it can never shoot, because SEM's rifle goal fires the MAIN hand.
        ((Mob) self).goalSelector.addGoal(2, new UnitHolster.HolsterGoal(self));
        // Priority 1 stick for FOLLOW_ME — same reason as EscortGoal. CommanderOrderGoal (prio 3)
        // yields to combat; this one does not. See FollowCommanderGoal.
        ((Mob) self).goalSelector.addGoal(1, new FollowCommanderGoal(self));
        // Priority 1 MOVE stick — SeekCover (2) and formationIndex columning otherwise miss the click.
        ((Mob) self).goalSelector.addGoal(1, new MoveToPositionGoal(self));
        // Priority 1, and it has to be: it must outrank SEM's owner-follow (CommanderOrderGoal,
        // prio 3, holds MOVE for ANY order) and the chase goal (MoveToAttackRangeGoal, prio 3) so a
        // glued escort is never dragged off. PMC-only because escort is a player order. See EscortGoal.
        ((Mob) self).goalSelector.addGoal(1, new EscortGoal(self));
        // Sweep & Advance on-foot: same priority band as escort so MoveToAttackRange cannot yank
        // infantry out of the selected rectangle while the sweep is active.
        ((Mob) self).goalSelector.addGoal(1, new SweepInfantryGoal(self));
        // Platoon regroup: same priority band — it only ever engages when idle (no target, no
        // standing order), which is mutually exclusive with what the other MOVE goals here gate on.
        ((Mob) self).goalSelector.addGoal(1, new PlatoonCohesionGoal(self));
        // Diagnostic only (off unless pmcCombatDebugLogging is set): logs why an owned PMC that was
        // just shot isn't shooting back. Claims no flags and always declines to run — see the class.
        ((Mob) self).goalSelector.addGoal(1, new PmcCombatDebugGoal(self));
        // TDT "Capture Medic" dispatch: chase down a medic and subdue/convert it. Player-ordered
        // only (see ICaptureMedic), gated on getTarget()==null like RepairGoal/MedicGoal — same
        // priority-2 band as those, and it MUST be <=2: SEM's own CommanderOrderGoal/
        // MoveToAttackRangeGoal sit at priority 3, and a goal at the SAME priority can never take a
        // flag away from one already running (canBeReplacedBy needs a STRICTLY lower number) — an
        // idle PMC always has CommanderOrderGoal holding MOVE already, so priority 3 here meant this
        // goal could win canUse() and still never actually move. See FollowCommanderGoal's class doc
        // for the same fight fought at priority 1.
        ((Mob) self).goalSelector.addGoal(2, new PmcCaptureMedicGoal(self));
        // BoardVehicleGoal is NOT here any more: it moved into addDriveGoals once RU/US units
        // gained IVehicleBoarder for scavenging. It never cared where an order came from.
        // ManMortarGoal lives in addDriveGoals with the rest of the crew-served wiring:
        // working a tube needs no network bridge, so RU/US crews get it too.
        VehicleAiGoals.addDriveGoals(self);

        // SEM's own NoPlayerHurtByTargetGoal excludes players but not same-faction attackers, so a
        // unit clipped by a squadmate's splash damage retaliates against it — MixinAbstractUnit's
        // setTarget guard blocks the assignment, but canUse() still returns true, leaving the goal
        // "active" against nothing and starving the real target scan. See NoFriendlyHurtByTargetGoal.
        ((Mob) self).targetSelector.removeAllGoals(g -> g instanceof NoPlayerHurtByTargetGoal);
        ((Mob) self).targetSelector.addGoal(1,
                new NoFriendlyHurtByTargetGoal(self, true).setAlertOthers().setUnseenMemoryTicks(600));
        // SEM never installs a Player or PMC-vs-PMC scan. Invasion lists / OpenPAC ENEMY
        // therefore had no on-foot acquisition path — only the mounted VehicleTargetScanGoal, and
        // even that lost a Player lock when SEM's setTarget(Player)→null won. Priority 2 matches
        // the vehicle scan; both goals self-disable while seated.
        ((Mob) self).targetSelector.addGoal(2, new DiplomacyEnemyTargetGoal<>(self, Player.class));
        ((Mob) self).targetSelector.addGoal(2, new DiplomacyEnemyTargetGoal<>(self, PmcUnitEntity.class));
    }
}

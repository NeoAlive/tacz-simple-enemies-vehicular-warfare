package com.neoalive.tacz_sewv.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.bridge.ICaptureOrder;
import com.neoalive.tacz_sewv.bridge.IEntrenched;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IIssuedAmmo;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.bridge.ITowRecovery;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.entity.ai.goal.NoFriendlyHurtByTargetGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.VehicleAiGoals;

// RUunitEntity/USunitEntity extend AbstractUnit directly (siblings of PmcUnitEntity, not
// subclasses), so each needs its own setupRoleGoals() injection to get vehicle AI — one
// multi-target mixin, since nothing here is faction-specific.
// IHelicopterPilot's default methods (persistent-NBT-backed) supply the flight state
// DriveHelicopterGoal's takeoff/land state machine works with; TankSpawner issues the
// takeoff on spawn and these hostile crews take no player flight orders.
// IMortarCrew is here for the same shape of reason: an RU/US crew can work a mortar, but
// having no order queue it is claimed onto its tube at spawn by EmplacementSpawner
// rather than by a player keypress. The claim field is transient (an entity network id);
// the fire mission is a BlockPos and rides IMortarCrew's persistent default methods.
// IIssuedAmmo is how such a crew has anything to shoot at all: RU/US units have NO
// inventory (SEM gives one to PmcUnitEntity only), so their ammunition is issued rather
// than carried. Default methods again — nothing to implement here.
// IVehicleBoarder is the odd one out: it is NOT here because a player can command these
// units (nothing can), but because SeekAbandonedVehicleGoal writes the very same order a
// player's board keypress writes for a PMC. The order is three plain fields and the goal
// that executes it never asks who set them, so scavenging cost nothing but this interface.
// Its fields are duplicated from MixinPmcUnitEntity rather than shared: the interface has
// no default methods and a mixin cannot add fields through one. They stay TRANSIENT — the
// order names an entity by network id, which means nothing after a reload.
// ICaptureOrder: persistent CAPTURE_POINT pipeline for invasion AI fleets (Stage F). Defaults only.
@Mixin({RUunitEntity.class, USunitEntity.class})
public abstract class MixinFactionUnitEntity
        implements IVehicleBoarder, IHelicopterPilot, IMortarCrew, IIssuedAmmo, ICaptureOrder, IEntrenched,
        ITowRecovery {

    @Unique
    private int tacz_sewv$mountTargetId = -1;

    @Unique
    private boolean tacz_sewv$boarding = false;

    @Unique
    private boolean tacz_sewv$passengerOnly = false;

    // Never set true for RU/US — passenger-only is a player-issued order and these units have no
    // order queue a player command could arrive through — but the interface has no default methods,
    // so every implementor needs the field regardless.
    @Unique
    private boolean tacz_sewv$boardCleared = false;

    @Unique
    private int tacz_sewv$mortarTargetId = IMortarCrew.NO_MORTAR;

    @Unique
    private int tacz_sewv$towVictimId = -1;

    @Unique
    private int tacz_sewv$towVictimGraceTicks = 0;

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
    public void sewv$setMortarTargetId(int id) {
        this.tacz_sewv$mortarTargetId = id;
    }

    @Override
    public int sewv$getMortarTargetId() {
        return this.tacz_sewv$mortarTargetId;
    }

    @Override
    public void tacz_sewv$setTowVictimId(int id) {
        this.tacz_sewv$towVictimId = id;
    }

    @Override
    public int tacz_sewv$getTowVictimId() {
        return this.tacz_sewv$towVictimId;
    }

    @Override
    public int tacz_sewv$getTowVictimGraceTicks() {
        return this.tacz_sewv$towVictimGraceTicks;
    }

    @Override
    public void tacz_sewv$setTowVictimGraceTicks(int ticks) {
        this.tacz_sewv$towVictimGraceTicks = ticks;
    }

    @Inject(method = "setupRoleGoals", at = @At("TAIL"), remap = false)
    private void tacz_sewv$addVehicleGoals(CallbackInfo ci) {
        AbstractUnit self = (AbstractUnit) (Object) this;
        VehicleAiGoals.addDriveGoals(self);

        // SEM's own plain HurtByTargetGoal (RU/US retaliation, priority 1) never excludes a
        // same-faction attacker — see MixinPmcUnitEntity's identical fix for the PMC side and
        // NoFriendlyHurtByTargetGoal's doc for why this is more than a log-spam fix. Players are
        // deliberately still valid retaliation targets here, matching SEM's own RU/US behaviour —
        // only PMC's original excluded them.
        Mob mob = (Mob) self;
        mob.targetSelector.removeAllGoals(g -> g.getClass() == HurtByTargetGoal.class);
        NoFriendlyHurtByTargetGoal retaliate = new NoFriendlyHurtByTargetGoal(self, false);
        if (self instanceof USunitEntity) {
            retaliate.setAlertOthers(USunitEntity.class);
        } else {
            retaliate.setAlertOthers();
        }
        retaliate.setUnseenMemoryTicks(600);
        mob.targetSelector.addGoal(1, retaliate);
    }
}

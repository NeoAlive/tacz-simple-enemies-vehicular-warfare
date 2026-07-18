package com.neoalive.tacz_sewv.mixin;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IIssuedAmmo;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.entity.ai.VehicleAiGoals;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
@Mixin({RUunitEntity.class, USunitEntity.class})
public abstract class MixinFactionUnitEntity implements IHelicopterPilot, IMortarCrew, IIssuedAmmo {

    @Unique
    private int tacz_sewv$mortarTargetId = IMortarCrew.NO_MORTAR;

    @Override
    public void sewv$setMortarTargetId(int id) {
        this.tacz_sewv$mortarTargetId = id;
    }

    @Override
    public int sewv$getMortarTargetId() {
        return this.tacz_sewv$mortarTargetId;
    }

    @Inject(method = "setupRoleGoals", at = @At("TAIL"), remap = false)
    private void tacz_sewv$addVehicleGoals(CallbackInfo ci) {
        VehicleAiGoals.addDriveGoals((AbstractUnit) (Object) this);
    }
}

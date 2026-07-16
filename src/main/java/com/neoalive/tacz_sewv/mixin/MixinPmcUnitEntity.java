package com.neoalive.tacz_sewv.mixin;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IIssuedAmmo;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.entity.ai.BoardVehicleGoal;
import com.neoalive.tacz_sewv.entity.ai.RadioObserverGoal;
import com.neoalive.tacz_sewv.entity.ai.VehicleAiGoals;
import net.minecraft.world.entity.Mob;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// IHelicopterPilot needs no method bodies here — its default methods store the
// flight state in the entity's persistent NBT (so it survives world reloads).
// The boarding order and mortar claim below are deliberately transient: they target
// an entity by network id, which is not stable across sessions, so persisting them
// would be wrong — a pending order is simply dropped on reload.
// IIssuedAmmo is only set on a PMC crew SPAWNED onto an emplacement (/sewv spawn pmctow).
// One a player ordered onto a tube with the board key has none, and so reads the shells the
// player actually gave it — which is the whole point of hand-loading one.
@Mixin(PmcUnitEntity.class)
public abstract class MixinPmcUnitEntity implements IVehicleBoarder, IHelicopterPilot, IMortarCrew, IIssuedAmmo {

    @Unique
    private int tacz_sewv$mountTargetId = -1;

    @Unique
    private boolean tacz_sewv$boarding = false;

    @Unique
    private int tacz_sewv$mortarTargetId = IMortarCrew.NO_MORTAR;

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
    public void sewv$setMortarTargetId(int id) {
        this.tacz_sewv$mortarTargetId = id;
    }

    @Override
    public int sewv$getMortarTargetId() {
        return this.tacz_sewv$mortarTargetId;
    }

    @Inject(method = "setupRoleGoals", at = @At("TAIL"), remap = false)
    private void tacz_sewv$addVehicleGoals(CallbackInfo ci) {
        PmcUnitEntity self = (PmcUnitEntity) (Object) this;
        ((Mob) self).goalSelector.addGoal(1, new BoardVehicleGoal(self));
        // Claims no flags, so its priority is nominal — it only relays a contact over the
        // radio and never competes with what the unit is doing.
        ((Mob) self).goalSelector.addGoal(1, new RadioObserverGoal(self));
        // ManMortarGoal lives in addDriveGoals with the rest of the crew-served wiring:
        // working a tube needs no network bridge, so RU/US crews get it too.
        VehicleAiGoals.addDriveGoals(self);
    }
}

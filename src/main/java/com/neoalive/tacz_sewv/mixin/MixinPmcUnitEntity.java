package com.neoalive.tacz_sewv.mixin;

import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.entity.ai.BoardVehicleGoal;
import net.minecraft.world.entity.Mob;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.entity.ai.VehicleMinRangeGoal;
import com.neoalive.tacz_sewv.entity.ai.DriveVehicleGoal;
import net.minecraft.world.entity.Entity;

@Mixin(PmcUnitEntity.class)
public abstract class MixinPmcUnitEntity implements IVehicleBoarder {

    @Unique
    private int tacz_sewv$mountTargetId = -1;

    @Unique
    private boolean tacz_sewv$boarding = false;

    @Override
public void tacz_sewv$setMountTargetId(int id) {
    System.out.println("SET MOUNT " + id);
    Thread.dumpStack();
    this.tacz_sewv$mountTargetId = id;
}

    @Override
    public int tacz_sewv$getMountTargetId() {
        return this.tacz_sewv$mountTargetId;
    }

    @Override
public void tacz_sewv$setBoarding(boolean boarding) {
    System.out.println("SET BOARDING " + boarding);
    Thread.dumpStack();
    this.tacz_sewv$boarding = boarding;
}

    @Override
public boolean tacz_sewv$isBoarding() {
    System.out.println("[TACZ_SEWV] isBoarding READ=" + this.tacz_sewv$boarding 
        + " on entity " + ((net.minecraft.world.entity.Entity)(Object)this).getId() 
        + " identityHash=" + System.identityHashCode(this));
    return this.tacz_sewv$boarding;
}

    @Inject(method = "setupRoleGoals", at = @At("TAIL"), remap = false)
private void tacz_sewv$addVehicleGoals(CallbackInfo ci) {
    PmcUnitEntity self = (PmcUnitEntity) (Object) this;
    ((Mob) self).goalSelector.addGoal(1, new BoardVehicleGoal(self));
    System.out.println("[TACZ_SEWV] INJECT FIRED — added board goal to " + self.getId() 
        + ", selector now has " + ((Mob) self).goalSelector.getAvailableGoals().size() + " goals");
}

    @Inject(method = "<init>", at = @At("RETURN"))
private void ctor(CallbackInfo ci) {
    System.out.println(
        "PMC CREATED id=" +
        ((Entity)(Object)this).getId() +
        " hash=" +
        System.identityHashCode(this)
    );
}
}

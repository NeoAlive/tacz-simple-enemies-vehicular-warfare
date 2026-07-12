package com.neoalive.tacz_sewv.mixin;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.entity.ai.VehicleAiGoals;
import net.minecraft.core.BlockPos;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// USunitEntity extends AbstractUnit directly (a sibling of PmcUnitEntity, not a
// subclass), so it needs its own setupRoleGoals() injection to get vehicle AI.
// It also carries the IHelicopterPilot flight state so DriveHelicopterGoal's
// takeoff/land state machine works for its autonomous crews (TankSpawner issues
// the takeoff on spawn); these hostile crews take no player flight orders.
@Mixin(USunitEntity.class)
public abstract class MixinUSunitEntity implements IHelicopterPilot {

    @Unique
    private int tacz_sewv$heliCommand = IHelicopterPilot.HELI_CMD_NONE;

    @Unique
    private BlockPos tacz_sewv$heliLandPos = null;

    @Inject(method = "setupRoleGoals", at = @At("TAIL"), remap = false)
    private void tacz_sewv$addVehicleGoals(CallbackInfo ci) {
        VehicleAiGoals.addDriveGoals((AbstractUnit) (Object) this);
    }

    @Override
    public void sewv$setHeliCommand(int command) {
        this.tacz_sewv$heliCommand = command;
    }

    @Override
    public int sewv$getHeliCommand() {
        return this.tacz_sewv$heliCommand;
    }

    @Override
    public void sewv$setHeliLandPos(BlockPos pos) {
        this.tacz_sewv$heliLandPos = pos;
    }

    @Override
    public BlockPos sewv$getHeliLandPos() {
        return this.tacz_sewv$heliLandPos;
    }
}

package com.neoalive.tacz_sewv.entity.unit;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;

import com.neoalive.tacz_sewv.entity.ai.goal.SupportUnitGoals;
import com.neoalive.tacz_sewv.entity.ai.support.UnitHolster;

/**
 * RU mechanical engineer. Carries a repair tool / sidearm kit and may operate one kamikaze drone
 * ({@link com.neoalive.tacz_sewv.entity.ai.goal.DroneOperatorGoal}).
 */
public class RuEngineerEntity extends RUunitEntity {

    public static final EntityDataAccessor<Boolean> DRONE_CONTROL_LOCKED =
            SynchedEntityData.defineId(RuEngineerEntity.class, EntityDataSerializers.BOOLEAN);

    public RuEngineerEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DRONE_CONTROL_LOCKED, false);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("SewvDroneControlLocked", this.entityData.get(DRONE_CONTROL_LOCKED));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("SewvDroneControlLocked")) {
            this.entityData.set(DRONE_CONTROL_LOCKED, tag.getBoolean("SewvDroneControlLocked"));
        }
    }

    @Override
    public void equipRandomGun() {
        UnitHolster.equip(this);
    }

    @Override
    public void setupRoleGoals() {
        SupportUnitGoals.engineer(this, this.goalSelector, this.targetSelector);
    }

    @Override
    public void aiStep() {
        super.aiStep();
    }
}

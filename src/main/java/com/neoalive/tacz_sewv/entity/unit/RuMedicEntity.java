package com.neoalive.tacz_sewv.entity.unit;

import com.atsuishio.superbwarfare.init.ModItems;
import com.neoalive.tacz_sewv.bridge.IMedicTreat;
import com.neoalive.tacz_sewv.entity.ai.SupportUnitGoals;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;

/**
 * RU squad medic. A {@link RUunitEntity} for all faction purposes (targeting, armor, energy are all
 * {@code instanceof}-based) but neutral and unarmed: it heals its own side and is targeted by no one
 * ({@code VehicleTargeting.isMedic}). Overriding {@code setupRoleGoals} without calling super also
 * drops the vehicle-AI injection, so a medic never crews anything.
 */
public class RuMedicEntity extends RUunitEntity implements IMedicTreat {

    public static final EntityDataAccessor<Boolean> TREATING =
            SynchedEntityData.defineId(RuMedicEntity.class, EntityDataSerializers.BOOLEAN);

    /** Client heal clip while treating — never shares SEM idle/walk states. */
    public final AnimationState treatAnimationState = new AnimationState();

    public RuMedicEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(TREATING, false);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("SewvMedicTreating", this.entityData.get(TREATING));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("SewvMedicTreating")) {
            this.entityData.set(TREATING, tag.getBoolean("SewvMedicTreating"));
        }
    }

    @Override
    public boolean sewv$isTreating() {
        return this.entityData.get(TREATING);
    }

    @Override
    public void sewv$setTreating(boolean treating) {
        this.entityData.set(TREATING, treating);
    }

    @Override
    public AnimationState sewv$treatAnimationState() {
        return this.treatAnimationState;
    }

    /** No weapon — a medical kit instead. It reads as a medic, and MedicGoal treats it as its supply. */
    @Override
    public void equipRandomGun() {
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(ModItems.MEDICAL_KIT.get()));
    }

    @Override
    public void setupRoleGoals() {
        SupportUnitGoals.medic(this, this.goalSelector, this.targetSelector);
    }
}

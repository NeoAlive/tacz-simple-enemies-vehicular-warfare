package com.neoalive.tacz_sewv.entity.unit;

import com.atsuishio.superbwarfare.init.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.bridge.IMedicCaptured;
import com.neoalive.tacz_sewv.bridge.IMedicTreat;
import com.neoalive.tacz_sewv.entity.ai.goal.SupportUnitGoals;
import com.neoalive.tacz_sewv.entity.ai.support.MedicCaptureSupport;

/** US squad medic. See {@link RuMedicEntity} — same design, US faction. */
public class UsMedicEntity extends USunitEntity implements IMedicTreat, IMedicCaptured {

    public static final EntityDataAccessor<Boolean> TREATING =
            SynchedEntityData.defineId(UsMedicEntity.class, EntityDataSerializers.BOOLEAN);

    public static final EntityDataAccessor<Boolean> CAPTURED =
            SynchedEntityData.defineId(UsMedicEntity.class, EntityDataSerializers.BOOLEAN);

    public UsMedicEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(TREATING, false);
        this.entityData.define(CAPTURED, false);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("SewvMedicTreating", this.entityData.get(TREATING));
        tag.putBoolean("SewvMedicCaptured", this.entityData.get(CAPTURED));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("SewvMedicTreating")) {
            this.entityData.set(TREATING, tag.getBoolean("SewvMedicTreating"));
        }
        if (tag.contains("SewvMedicCaptured")) {
            this.entityData.set(CAPTURED, tag.getBoolean("SewvMedicCaptured"));
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
    public boolean sewv$isCapturedSynced() {
        return this.entityData.get(CAPTURED);
    }

    @Override
    public void sewv$setCapturedSynced(boolean captured) {
        this.entityData.set(CAPTURED, captured);
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

    /** See {@link RuMedicEntity#tick()} — identical belt-and-suspenders animation-state block. */
    @Override
    public void tick() {
        super.tick();
        if (sewv$isCapturedSynced()) {
            this.walkAnimationState.stop();
            this.idleAnimationState.stop();
            this.hurtAnimationState.stop();
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        InteractionResult capture = MedicCaptureSupport.tryConvert(this, player);
        if (capture != null) {
            return capture;
        }
        return super.mobInteract(player, hand);
    }
}

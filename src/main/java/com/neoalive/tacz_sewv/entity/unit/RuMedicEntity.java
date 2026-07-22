package com.neoalive.tacz_sewv.entity.unit;

import com.atsuishio.superbwarfare.init.ModItems;
import com.neoalive.tacz_sewv.entity.ai.SupportUnitGoals;
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
public class RuMedicEntity extends RUunitEntity {

    public RuMedicEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
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

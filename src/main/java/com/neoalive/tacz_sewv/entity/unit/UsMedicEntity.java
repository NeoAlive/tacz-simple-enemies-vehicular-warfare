package com.neoalive.tacz_sewv.entity.unit;

import com.atsuishio.superbwarfare.init.ModItems;
import com.neoalive.tacz_sewv.entity.ai.SupportUnitGoals;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

/** US squad medic. See {@link RuMedicEntity} — same design, US faction. */
public class UsMedicEntity extends USunitEntity {

    public UsMedicEntity(EntityType<? extends Monster> type, Level level) {
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

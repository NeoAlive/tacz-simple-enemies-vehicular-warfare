package com.neoalive.tacz_sewv.entity.unit;

import com.atsuishio.superbwarfare.init.ModItems;
import com.neoalive.tacz_sewv.entity.ai.SupportUnitGoals;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

/** US mechanical engineer. See {@link RuEngineerEntity} — same design, US faction. */
public class UsEngineerEntity extends USunitEntity {

    public UsEngineerEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    public void equipRandomGun() {
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(ModItems.REPAIR_TOOL.get()));
    }

    @Override
    public void setupRoleGoals() {
        SupportUnitGoals.engineer(this, this.goalSelector, this.targetSelector);
    }
}

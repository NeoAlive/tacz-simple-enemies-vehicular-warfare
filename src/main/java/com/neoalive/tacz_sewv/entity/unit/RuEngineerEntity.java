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
 * RU mechanical engineer. A {@link RUunitEntity} that carries a SuperbWarfare repair tool and patches
 * up friendly/empty hulls on foot ({@link com.neoalive.tacz_sewv.entity.ai.RepairGoal}). Unlike the
 * medic it is a normal target for enemies. The tool is cosmetic — repairs call {@code heal()}
 * directly — and its {@code setupRoleGoals} adds no fire goal, so it never shoots it.
 */
public class RuEngineerEntity extends RUunitEntity {

    public RuEngineerEntity(EntityType<? extends Monster> type, Level level) {
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

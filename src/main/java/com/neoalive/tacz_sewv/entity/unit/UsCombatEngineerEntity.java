package com.neoalive.tacz_sewv.entity.unit;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.entity.ai.goal.SupportUnitGoals;
import com.neoalive.tacz_sewv.entity.ai.support.UnitHolster;

/**
 * US combat engineer. See {@link RuCombatEngineerEntity} — same design, US faction.
 */
public class UsCombatEngineerEntity extends USunitEntity {

    public UsCombatEngineerEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    public void equipRandomGun() {
        UnitHolster.equipCombatEngineer(this);
    }

    @Override
    public void setupRoleGoals() {
        SupportUnitGoals.combatEngineer(this, this.goalSelector, this.targetSelector);
    }
}

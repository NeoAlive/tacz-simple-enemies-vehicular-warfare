package com.neoalive.tacz_sewv.entity.unit;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;

import com.neoalive.tacz_sewv.entity.ai.goal.SupportUnitGoals;
import com.neoalive.tacz_sewv.entity.ai.support.UnitHolster;

/**
 * RU combat engineer. Identified by SBW {@code military_shovel}; digs one foxhole structure
 * (MVP). Parallel to {@link RuEngineerEntity} (mechanic), not a subclass of it.
 */
public class RuCombatEngineerEntity extends RUunitEntity {

    public RuCombatEngineerEntity(EntityType<? extends Monster> type, Level level) {
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

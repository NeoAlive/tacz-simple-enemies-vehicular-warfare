package com.neoalive.tacz_sewv.entity.unit;

import com.neoalive.tacz_sewv.entity.ai.EngineerLoadout;
import com.neoalive.tacz_sewv.entity.ai.SupportUnitGoals;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

/** US mechanical engineer. See {@link RuEngineerEntity} — same design, US faction. */
public class UsEngineerEntity extends USunitEntity {

    public UsEngineerEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    public void equipRandomGun() {
        EngineerLoadout.equip(this);
    }

    @Override
    public void setupRoleGoals() {
        SupportUnitGoals.engineer(this, this.goalSelector, this.targetSelector);
    }

    // See RuEngineerEntity — the holster swap is a per-tick state check, not a goal.
    @Override
    public void aiStep() {
        super.aiStep();
        if (!this.level().isClientSide) EngineerLoadout.updateHolster(this);
    }
}

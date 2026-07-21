package com.neoalive.tacz_sewv.entity.unit;

import com.neoalive.tacz_sewv.entity.ai.SupportUnitGoals;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

/** US squad medic. See {@link RuMedicEntity} — same design, US faction. */
public class UsMedicEntity extends USunitEntity {

    public UsMedicEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    public void equipRandomGun() {
        // No weapon.
    }

    @Override
    public void setupRoleGoals() {
        SupportUnitGoals.medic(this, this.goalSelector, this.targetSelector);
    }
}

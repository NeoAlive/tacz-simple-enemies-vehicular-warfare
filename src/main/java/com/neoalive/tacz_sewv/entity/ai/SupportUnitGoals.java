package com.neoalive.tacz_sewv.entity.ai;

import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.utils.UnitRole;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/**
 * Goal loadouts for the medic and engineer support units. Both replace the faction unit's normal
 * {@code setupRoleGoals} entirely — no combat, no vehicle crewing — with a single job goal plus
 * idle wandering. Neither installs a targetSelector: a medic must never fight (it is neutral), and
 * an engineer is a peaceful repairer that others can still target.
 */
public final class SupportUnitGoals {

    private SupportUnitGoals() {}

    public static void medic(AbstractUnit unit, GoalSelector goals, GoalSelector targets) {
        reset(unit, goals, targets);
        goals.addGoal(0, new FloatGoal(unit));
        goals.addGoal(1, new MedicGoal(unit));
        idle(unit, goals);
    }

    public static void engineer(AbstractUnit unit, GoalSelector goals, GoalSelector targets) {
        reset(unit, goals, targets);
        goals.addGoal(0, new FloatGoal(unit));
        goals.addGoal(1, new RepairGoal(unit));
        idle(unit, goals);
    }

    private static void reset(AbstractUnit unit, GoalSelector goals, GoalSelector targets) {
        goals.removeAllGoals(g -> true);
        targets.removeAllGoals(g -> true);
        if (unit.getRole() == null) unit.setRole(UnitRole.DEFAULT);
    }

    private static void idle(AbstractUnit unit, GoalSelector goals) {
        goals.addGoal(7, new WaterAvoidingRandomStrollGoal(unit, 0.8));
        goals.addGoal(8, new LookAtPlayerGoal(unit, Player.class, 8.0F));
        goals.addGoal(9, new RandomLookAroundGoal(unit));
    }
}
